// SPDX-License-Identifier: Apache-2.0

package agent

import (
	"bufio"
	"errors"
	"net"
	"os"
	"path/filepath"
	"reflect"
	"strings"
	"sync/atomic"
	"testing"
	"time"

	"google.golang.org/grpc/codes"
	"google.golang.org/grpc/status"

	"github.com/ljalbinson/orphera-pkg/agent-go/internal/pb"
)

// drain returns every event queued so far.
func drain(q *queue) []*pb.Event {
	closed := make(chan struct{})
	close(closed)
	var out []*pb.Event
	for {
		e, ok := q.take(closed)
		if !ok {
			return out
		}
		out = append(out, e)
	}
}

func last(t *testing.T, evs []*pb.Event) *pb.Event {
	t.Helper()
	if len(evs) == 0 {
		t.Fatal("no events")
	}
	return evs[len(evs)-1]
}

func outputs(evs []*pb.Event) []string {
	var out []string
	for _, e := range evs {
		if e.Kind == pb.Event_OUTPUT {
			out = append(out, e.Message)
		}
	}
	return out
}

func TestLineWriterSplitsLikeReadLine(t *testing.T) {
	var got []string
	w := newLineWriter(func(s string) { got = append(got, s) })
	for _, chunk := range []string{"one\ntw", "o\r", "\nthree\rfour\r", "", "five"} {
		w.Write([]byte(chunk))
	}
	w.flush()
	want := []string{"one", "two", "three", "four", "five"}
	if !reflect.DeepEqual(got, want) {
		t.Fatalf("got %q want %q", got, want)
	}
}

func TestQueueMakesInvalidUTF8Valid(t *testing.T) {
	q := newQueue()
	q.output("bad \xff byte")
	e := drain(q)[0]
	if e.Message != "bad � byte" {
		t.Fatalf("got %q", e.Message)
	}
}

func TestRunCommand(t *testing.T) {
	q := newQueue()
	if err := runCommand(&pb.RunCommandRequest{Command: []string{"sh", "-c", "echo out; echo err >&2; exit 3"}}, q); err != nil {
		t.Fatal(err)
	}
	evs := drain(q)
	if evs[0].Kind != pb.Event_PROGRESS || evs[0].Message != "Running: sh -c echo out; echo err >&2; exit 3" {
		t.Fatalf("first event %v", evs[0])
	}
	r := last(t, evs)
	if r.Kind != pb.Event_RESULT || r.ExitCode != 3 || r.Success || r.Message != "FAILED" {
		t.Fatalf("result %v", r)
	}
	got := outputs(evs)
	if len(got) != 2 || !strings.Contains(strings.Join(got, ","), "out") || !strings.Contains(strings.Join(got, ","), "err") {
		t.Fatalf("outputs %q", got)
	}
}

func TestRunCommandUsesFixedPath(t *testing.T) {
	q := newQueue()
	runCommand(&pb.RunCommandRequest{Command: []string{"sh", "-c", "echo $PATH"}}, q)
	if got := outputs(drain(q)); len(got) != 1 || got[0] != commandPath {
		t.Fatalf("PATH %q", got)
	}
}

func TestRunCommandTimeout(t *testing.T) {
	q := newQueue()
	start := time.Now()
	runCommand(&pb.RunCommandRequest{Command: []string{"sleep", "30"}, TimeoutSeconds: 1}, q)
	if time.Since(start) > 5*time.Second {
		t.Fatal("timeout not enforced")
	}
	r := last(t, drain(q))
	if r.ExitCode != 124 || r.Success || r.Message != "Timed out after 1s" {
		t.Fatalf("result %v", r)
	}
}

func TestRunCommandSignalExitCode(t *testing.T) {
	q := newQueue()
	runCommand(&pb.RunCommandRequest{Command: []string{"sh", "-c", "kill -9 $$"}}, q)
	if r := last(t, drain(q)); r.ExitCode != 137 {
		t.Fatalf("exit %d, want 137 as Java reports", r.ExitCode)
	}
}

func TestRunCommandEmptyAndMissing(t *testing.T) {
	q := newQueue()
	runCommand(&pb.RunCommandRequest{}, q)
	if r := last(t, drain(q)); r.Message != "No command given" || r.ExitCode != 1 {
		t.Fatalf("result %v", r)
	}
	if err := runCommand(&pb.RunCommandRequest{Command: []string{"/nonexistent/binary"}}, newQueue()); err == nil {
		t.Fatal("a command that cannot start must be an error (becomes FAILED in streamTask)")
	}
}

func TestRunNoResultAndPostCheck(t *testing.T) {
	q := newQueue()
	err := runNoResult([]string{"sh", "-c", "exit 100"}, "Updating package cache", q)
	if err == nil || err.Error() != "Command failed with exit 100: sh -c exit 100" {
		t.Fatalf("err %v", err)
	}
	for _, e := range drain(q) {
		if e.Kind == pb.Event_RESULT {
			t.Fatal("runNoResult must never send a RESULT")
		}
	}

	// list stands in for `dpkg -L`; /usr/bin/sh exists and is executable on
	// any test machine, and the other path is not a binary so is not checked.
	list := func(pkg string) []string {
		if pkg != "tool" {
			t.Errorf("version not stripped: %q", pkg)
		}
		return []string{"printf", "/usr/bin/sh\n/etc/not-a-binary\n"}
	}
	q = newQueue()
	if err := runInstallWithPostCheck([]string{"true"}, "Installing packages", []string{"tool=1.0"}, q, list); err != nil {
		t.Fatal(err)
	}
	if r := last(t, drain(q)); !r.Success || r.ExitCode != 0 || r.Message != "OK" {
		t.Fatalf("result %v", r)
	}

	postCheckAttempts, postCheckInterval = 2, 10*time.Millisecond
	defer func() { postCheckAttempts, postCheckInterval = 40, 500*time.Millisecond }()
	missing := func(string) []string { return []string{"printf", "/usr/bin/orphera-no-such-binary\n"} }
	q = newQueue()
	runInstallWithPostCheck([]string{"true"}, "Installing packages", []string{"x"}, q, missing)
	if r := last(t, drain(q)); r.Success || r.ExitCode != 1 {
		t.Fatalf("post-check failure must be FAILED exit 1, got %v", r)
	}
	q = newQueue()
	runInstallWithPostCheck([]string{"sh", "-c", "exit 100"}, "Installing packages", []string{"x"}, q, missing)
	if r := last(t, drain(q)); r.Success || r.ExitCode != 100 {
		t.Fatalf("install failure keeps its exit code, got %v", r)
	}
}

func TestDnfPackageAndAptCommands(t *testing.T) {
	if got := dnfPackage("ceph=18.2.1=x"); got != "ceph-18.2.1=x" {
		t.Fatalf("dnfPackage %q", got)
	}
}

func TestSelfInstallRefusesOtherPackages(t *testing.T) {
	q := newQueue()
	selfInstall("/tmp/x.deb", q, []string{"echo", "not-orphera"}, "Package field", "dpkg-deb", ".deb", debScript)
	r := last(t, drain(q))
	if r.Success || r.Message != "Refusing to install /tmp/x.deb: its Package field is 'not-orphera', not 'orphera-agent'." {
		t.Fatalf("result %v", r)
	}
	q = newQueue()
	selfInstall("/tmp/x.deb", q, []string{"sh", "-c", "exit 2"}, "Package field", "dpkg-deb", ".deb", debScript)
	if r := last(t, drain(q)); !strings.Contains(r.Message, "dpkg-deb exited 2") {
		t.Fatalf("result %v", r)
	}
	q = newQueue()
	selfInstall("/tmp/x.deb", q, []string{"/nonexistent/dpkg-deb"}, "Package field", "dpkg-deb", ".deb", debScript)
	if r := last(t, drain(q)); !strings.Contains(r.Message, "failed to run dpkg-deb") {
		t.Fatalf("result %v", r)
	}
}

// The AgentGuardCheck of the Scala agent: a protected host refuses to reboot
// or power off, before anything is started.
func TestNeverRebootGuard(t *testing.T) {
	started := 0
	startPower = func(string, ...string) error { started++; return nil }
	defer func() { startPower = startDetached }()

	none := func(string) (string, bool) { return "", false }
	if neverRebootMarker(none) != "/etc/orphera-agent/never-reboot" {
		t.Fatal("default marker path")
	}
	marker := filepath.Join(t.TempDir(), "never-reboot")
	env := func(k string) (string, bool) {
		if k == neverRebootEnv {
			return marker, true
		}
		return "", false
	}
	if neverRebootMarker(env) != marker {
		t.Fatal("ORPHERA_NEVER_REBOOT_FILE must override the marker path")
	}

	os.WriteFile(marker, nil, 0o644)
	for _, a := range []powerAction{rebootAction, poweroffAction} {
		_, err := a.trigger(0, env)
		if status.Code(err) != codes.FailedPrecondition {
			t.Fatalf("%s: want FAILED_PRECONDITION, got %v", a.verb, err)
		}
		msg := status.Convert(err).Message()
		if !strings.Contains(msg, marker) || !strings.Contains(msg, "refuses to "+a.verb) {
			t.Fatalf("message %q", msg)
		}
	}
	if started != 0 {
		t.Fatal("nothing may be started on a protected host")
	}

	os.Remove(marker)
	d, err := rebootAction.trigger(0, env)
	if err != nil || d != 5 || started != 1 {
		t.Fatalf("unprotected: delay %d err %v started %d", d, err, started)
	}
}

func TestFileCheck(t *testing.T) {
	dir := t.TempDir()
	p := filepath.Join(dir, "f")
	if r := checkFile(&pb.FileCheck{DestPath: p}); !r.NeedsCopy || r.Reason != "file does not exist" {
		t.Fatalf("%v", r)
	}
	os.WriteFile(p, []byte("hello"), 0o640)
	os.Chmod(p, 0o640)
	sum := "2cf24dba5fb0a30e26e83b2ac5b9e29e1b161e5c1fa7425e73043362938b9824"
	if r := checkFile(&pb.FileCheck{DestPath: p, Sha256: "00"}); r.Reason != "content differs" {
		t.Fatalf("%v", r)
	}
	if r := checkFile(&pb.FileCheck{DestPath: p, Sha256: sum, Mode: 0o644}); r.Reason != "mode differs: expected 644, got 640" {
		t.Fatalf("%v", r)
	}
	if r := checkFile(&pb.FileCheck{DestPath: p, Sha256: sum, Owner: "orphera-nobody-xyz"}); !strings.HasPrefix(r.Reason, "owner differs: expected orphera-nobody-xyz, got ") {
		t.Fatalf("%v", r)
	}
	if r := checkFile(&pb.FileCheck{DestPath: p, Sha256: sum, Mode: 0o640}); r.NeedsCopy || r.Reason != "unchanged" {
		t.Fatalf("%v", r)
	}
}

func TestJavaPath(t *testing.T) {
	for in, want := range map[string]string{"/a//b/": "/a/b", "/": "/", "a/./b": "a/./b"} {
		if got := javaPath(in); got != want {
			t.Errorf("%q: %q want %q", in, got, want)
		}
	}
}

func TestNetworkReloadConfirmAndRollback(t *testing.T) {
	root := t.TempDir()
	cfg := filepath.Join(root, "network")
	os.MkdirAll(cfg, 0o755)
	write := func(name, s string) { os.WriteFile(filepath.Join(cfg, name), []byte(s), 0o644) }
	read := func(name string) string { b, _ := os.ReadFile(filepath.Join(cfg, name)); return string(b) }
	var reloads atomic.Int32
	be := netBackend{dir: cfg, extensions: []string{".network"}, reload: func() error { reloads.Add(1); return nil }}
	r := &networkReloader{backupRoot: filepath.Join(root, "backups"), backend: func() netBackend { return be }, pending: map[string]*time.Timer{}}

	write("10-eth0.network", "good")
	write("README", "not config")
	r.initKnownGood()
	if b, _ := os.ReadFile(filepath.Join(r.knownGood(), "10-eth0.network")); string(b) != "good" {
		t.Fatal("known-good snapshot not taken")
	}
	if exists(filepath.Join(r.knownGood(), "README")) {
		t.Fatal("only backend files are snapshotted")
	}

	// A change that is not confirmed is rolled back to known-good.
	write("10-eth0.network", "bad")
	write("20-new.network", "new")
	res, err := r.reload(&pb.NetworkReload{ConfirmTimeoutSeconds: 1})
	if err != nil || res.BackupId == "" || reloads.Load() != 1 {
		t.Fatalf("reload: %v %v %d", res, err, reloads.Load())
	}
	time.Sleep(1500 * time.Millisecond)
	if read("10-eth0.network") != "good" || exists(filepath.Join(cfg, "20-new.network")) || read("README") != "not config" {
		t.Fatal("rollback did not restore the known-good configuration")
	}
	if reloads.Load() != 2 {
		t.Fatalf("rollback must reload, reloads=%d", reloads.Load())
	}

	// A confirmed change stays and becomes the new known-good.
	time.Sleep(2 * time.Millisecond) // a new backup id
	write("10-eth0.network", "better")
	res, _ = r.reload(&pb.NetworkReload{ConfirmTimeoutSeconds: 1})
	r.confirm(res.BackupId)
	time.Sleep(1500 * time.Millisecond)
	if read("10-eth0.network") != "better" {
		t.Fatal("confirmed change was rolled back")
	}
	if b, _ := os.ReadFile(filepath.Join(r.knownGood(), "10-eth0.network")); string(b) != "better" {
		t.Fatal("confirm must refresh known-good")
	}
	r.confirm("unknown") // no-op
}

func TestNetworkReloadFailureIsAnError(t *testing.T) {
	root := t.TempDir()
	be := netBackend{dir: root, extensions: []string{".network"}, reload: func() error { return errors.New("networkctl reload failed with exit 1") }}
	r := &networkReloader{backupRoot: filepath.Join(root, "b"), backend: func() netBackend { return be }, pending: map[string]*time.Timer{}}
	if _, err := r.reload(&pb.NetworkReload{}); err == nil {
		t.Fatal("want error")
	}
	if len(r.pending) != 0 {
		t.Fatal("no watchdog after a failed reload")
	}
}

func TestJavaInterfaceOrder(t *testing.T) {
	v4 := []rawAddr{
		{"lo", net.IPv4(127, 0, 0, 1).To4()},
		{"eth0", net.IPv4(10, 0, 0, 5).To4()},
		{"eth0:1", net.IPv4(10, 0, 0, 6).To4()},
		{"eth1", net.IPv4(192, 168, 1, 5).To4()},
	}
	v6 := []rawAddr{
		{"eth0", net.ParseIP("fe80::a00:27ff:fe4e:1b2c")},
		{"lo", net.ParseIP("::1")},
		{"eth2", net.ParseIP("2001:db8::1")},
	}
	list := javaInterfaceList(v4, v6, func(string) bool { return true })
	var names []string
	for _, n := range list {
		names = append(names, n.name)
	}
	if want := []string{"eth2", "eth1", "eth0", "lo"}; !reflect.DeepEqual(names, want) {
		t.Fatalf("order %v want %v", names, want)
	}
	eth0 := list[2]
	var addrs []string
	for _, ip := range eth0.addrs {
		addrs = append(addrs, javaHostAddress(ip, eth0.name))
	}
	if want := []string{"fe80:0:0:0:a00:27ff:fe4e:1b2c%eth0", "10.0.0.6", "10.0.0.5"}; !reflect.DeepEqual(addrs, want) {
		t.Fatalf("addresses %v want %v", addrs, want)
	}

	// An alias whose parent cannot be read is an interface of its own.
	list = javaInterfaceList([]rawAddr{{"eth9:1", net.IPv4(1, 2, 3, 4).To4()}}, nil, func(string) bool { return false })
	if list[0].name != "eth9:1" {
		t.Fatalf("got %s", list[0].name)
	}
}

func TestJavaInterfacesFilterAndMac(t *testing.T) {
	sys := interfaceOS{
		ipv4: func() ([]rawAddr, error) {
			return []rawAddr{{"lo", net.IPv4(127, 0, 0, 1).To4()}, {"eth0", net.IPv4(10, 0, 0, 5).To4()}, {"eth1", net.IPv4(10, 1, 0, 5).To4()}}, nil
		},
		ipv6: func() []rawAddr { return nil },
		flags: func(n string) (uint16, error) {
			switch n {
			case "lo":
				return 0x1 | 0x8 | 0x40, nil // up, loopback, running
			case "eth1":
				return 0x1, nil // up but not running
			}
			return 0x1 | 0x40, nil
		},
		hwaddr: func(string) ([]byte, error) { return []byte{0x08, 0x00, 0x27, 0xab, 0xcd, 0xef}, nil },
	}
	got := javaInterfacesWith(sys)
	if len(got) != 1 || got[0].Name != "eth0" || got[0].MacAddress != "08:00:27:ab:cd:ef" {
		t.Fatalf("got %v", got)
	}
	sys.hwaddr = func(string) ([]byte, error) { return nil, errors.New("ioctl failed") }
	if got := javaInterfacesWith(sys); got != nil {
		t.Fatal("an ioctl failure reports no interfaces at all, as the JVM agent did")
	}
}

func TestParseIfInet6(t *testing.T) {
	in := "fe800000000000000a0027fffe4e1b2c 00000002 40 20 80     eth0\n00000000000000000000000000000001 01 80 10 80       lo\n"
	got := parseIfInet6(bufio.NewScanner(strings.NewReader(in)))
	if len(got) != 2 || got[0].ifName != "eth0" || javaHostAddress(got[0].ip, "eth0") != "fe80:0:0:0:a00:27ff:fe4e:1b2c%eth0" {
		t.Fatalf("got %v", got)
	}
}

func TestReadDisksAndUnescape(t *testing.T) {
	mtab := filepath.Join(t.TempDir(), "mtab")
	os.WriteFile(mtab, []byte("/dev/root / ext4 rw 0 0\nauto /media autofs rw,ignore 0 0\nnone /no\\040such\\040dir tmpfs rw 0 0\n"), 0o644)
	got := readDisks(mtab)
	if len(got) != 1 || got[0].MountPoint != "/dev/root" || got[0].Filesystem != "ext4" || got[0].TotalBytes == 0 {
		t.Fatalf("got %v", got)
	}
	if u := unescapeMount(`a\040b\134c\\d`); u != `a b\c\d` {
		t.Fatalf("got %q", u)
	}
}

func TestOsReleaseAndMeminfo(t *testing.T) {
	dir := t.TempDir()
	p := filepath.Join(dir, "os-release")
	os.WriteFile(p, []byte("NAME=\"Ubuntu\"\nID=ubuntu\nVERSION_ID=\"24.04\"\n"), 0o644)
	m := readOsRelease(p)
	if m["ID"] != "ubuntu" || m["VERSION_ID"] != "24.04" {
		t.Fatalf("got %v", m)
	}
	mi := filepath.Join(dir, "meminfo")
	os.WriteFile(mi, []byte("MemTotal:       16314240 kB\nMemFree: 1 kB\n"), 0o644)
	if got := readMemTotalBytes(mi); got != 16314240*1024 {
		t.Fatalf("got %d", got)
	}
}

func TestThreadStats(t *testing.T) {
	for in, want := range map[string]string{"ioapp-compute-12": "ioapp-compute", "GC Thread#2": "GC Thread", "1234": "1234", "": "", "agent\n": "agent"} {
		if got := threadGroupName(in); got != want {
			t.Errorf("%q: %q want %q", in, got, want)
		}
	}
	got := counts([]string{"b", "a", "b", "c", "a", "b"})
	want := []nameCount{{"b", 3}, {"a", 2}, {"c", 1}}
	if !reflect.DeepEqual(got, want) {
		t.Fatalf("got %v", got)
	}
	if kilobytes("212340 kB") != 212340*1024 || kilobytes("") != 0 {
		t.Fatal("kilobytes")
	}
	info := readThreadInfo(true)
	if info.OsThreads == 0 || info.JvmThreads == 0 || info.RssBytes == 0 || len(info.States) == 0 {
		t.Fatalf("thread info %v", info)
	}
}

func TestRequireToken(t *testing.T) {
	if _, err := RequireToken("", false); err == nil || !strings.Contains(err.Error(), "not set") {
		t.Fatal("unset token")
	}
	if _, err := RequireToken(DevToken, true); err == nil || !strings.Contains(err.Error(), "built-in") {
		t.Fatal("dev token")
	}
	if tok, err := RequireToken("s3cret", true); err != nil || tok != "s3cret" {
		t.Fatal("good token")
	}
}
