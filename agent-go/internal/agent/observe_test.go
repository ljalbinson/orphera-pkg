// SPDX-License-Identifier: Apache-2.0

package agent

import (
	"context"
	"crypto/sha256"
	"encoding/hex"
	"os"
	"path/filepath"
	"strings"
	"testing"

	"github.com/ljalbinson/orphera-pkg/agent-go/internal/pb"
)

func obs(kind, target, extra string) *pb.Observation {
	return &pb.Observation{Id: 1, Kind: kind, Target: target, Extra: extra}
}

func TestObservePath(t *testing.T) {
	dir := t.TempDir()
	file := filepath.Join(dir, "f")
	if err := os.WriteFile(file, []byte("hello\n"), 0o640); err != nil {
		t.Fatal(err)
	}
	if err := os.Chmod(file, 0o640); err != nil {
		t.Fatal(err)
	}
	link := filepath.Join(dir, "l")
	if err := os.Symlink("f", link); err != nil {
		t.Fatal(err)
	}
	sub := filepath.Join(dir, "d")
	if err := os.Mkdir(sub, 0o750); err != nil {
		t.Fatal(err)
	}
	if err := os.Chmod(sub, 0o750); err != nil {
		t.Fatal(err)
	}

	sum := sha256.Sum256([]byte("hello\n"))
	v, err := observeOne(obs("path", file, ""))
	if err != nil {
		t.Fatal(err)
	}
	if v["type"] != "file" || v["mode"] != "640" || v["sha256"] != hex.EncodeToString(sum[:]) || v["owner"] == "" || v["group"] == "" {
		t.Errorf("file: %v", v)
	}
	if v, _ := observeOne(obs("path", link, "")); v["type"] != "link" || v["target"] != "f" {
		t.Errorf("link: %v", v)
	}
	if v, _ := observeOne(obs("path", sub, "")); v["type"] != "directory" || v["mode"] != "750" {
		t.Errorf("directory: %v", v)
	}
	if v, _ := observeOne(obs("path", filepath.Join(dir, "nope"), "")); v["type"] != "absent" {
		t.Errorf("absent: %v", v)
	}
	if _, err := observeOne(obs("path", "relative/x", "")); err == nil {
		t.Error("a relative path should be refused")
	}
	if _, err := observeOne(obs("path", ProtectedDir+"/agent.env", "")); err == nil {
		t.Error("the agent's own directory should be refused")
	}
}

func TestObserveFileText(t *testing.T) {
	dir := t.TempDir()
	file := filepath.Join(dir, "f")
	if err := os.WriteFile(file, []byte("a = 1\nb = 2\n"), 0o644); err != nil {
		t.Fatal(err)
	}
	v, err := observeOne(obs("file_text", file, ""))
	if err != nil || v["exists"] != "yes" || v["content"] != "a = 1\nb = 2\n" || v["truncated"] != "no" {
		t.Errorf("text: %v %v", v, err)
	}
	if v, _ := observeOne(obs("file_text", filepath.Join(dir, "no"), "")); v["exists"] != "no" {
		t.Errorf("missing: %v", v)
	}
	big := filepath.Join(dir, "big")
	if err := os.WriteFile(big, []byte(strings.Repeat("x", maxObservedText+10)), 0o644); err != nil {
		t.Fatal(err)
	}
	v, _ = observeOne(obs("file_text", big, ""))
	if v["truncated"] != "yes" || len(v["content"]) != maxObservedText {
		t.Errorf("big: truncated=%s len=%d", v["truncated"], len(v["content"]))
	}
	if _, err := observeOne(obs("file_text", dir, "")); err == nil {
		t.Error("a directory is not text")
	}
}

func TestObserveMount(t *testing.T) {
	dir := t.TempDir()
	oldF, oldM := fstabPath, mountInfoPath
	defer func() { fstabPath, mountInfoPath = oldF, oldM }()
	fstabPath = filepath.Join(dir, "fstab")
	mountInfoPath = filepath.Join(dir, "mountinfo")
	os.WriteFile(fstabPath, []byte("# c\ntmpfs /opt/ram tmpfs size=8m,mode=0755 0 0\n/dev/x /mnt/other ext4 defaults 0 0\n"), 0o644)
	os.WriteFile(mountInfoPath, []byte("36 35 98:0 / /opt/ram rw - tmpfs tmpfs rw\n37 35 98:1 / /with\\040space rw - ext4 /dev/y rw\n"), 0o644)

	v, _ := observeOne(obs("mount", "/opt/ram", ""))
	if v["fstab"] != "yes" || v["dev"] != "tmpfs" || v["fstype"] != "tmpfs" || v["opts"] != "size=8m,mode=0755" || v["mounted"] != "yes" {
		t.Errorf("mounted: %v", v)
	}
	v, _ = observeOne(obs("mount", "/mnt/other", ""))
	if v["fstab"] != "yes" || v["mounted"] != "no" {
		t.Errorf("fstab only: %v", v)
	}
	v, _ = observeOne(obs("mount", "/with space", ""))
	if v["fstab"] != "no" || v["mounted"] != "yes" {
		t.Errorf("escaped: %v", v)
	}
	v, _ = observeOne(obs("mount", "/nowhere", ""))
	if v["fstab"] != "no" || v["mounted"] != "no" {
		t.Errorf("neither: %v", v)
	}
}

func TestObservePersistedLocations(t *testing.T) {
	if _, err := observeOne(obs("sysctl", "net.ipv4.ip_forward", "/etc/passwd")); err == nil {
		t.Error("a sysctl may be persisted only under /etc/sysctl.d")
	}
	if _, err := observeOne(obs("module", "overlay", "/etc/sysctl.d/x.conf")); err == nil {
		t.Error("a module may be persisted only under /etc/modules-load.d")
	}
	if _, err := observeOne(obs("module", "overlay", "/etc/modules-load.d/../passwd")); err == nil {
		t.Error("a path out of the directory should be refused")
	}
}

func TestObserveNames(t *testing.T) {
	for _, bad := range []string{"", "-rf", "a b", "a\nb", "a\x00b"} {
		if validName(bad) {
			t.Errorf("%q should not be a valid name", bad)
		}
	}
	for _, good := range []string{"nginx", "net.ipv4.ip_forward", "systemd-resolved.service", "libc6:amd64"} {
		if !validName(good) {
			t.Errorf("%q should be a valid name", good)
		}
	}
	for _, kind := range []string{"package", "unit", "user", "group", "sysctl", "module"} {
		if _, err := observeOne(obs(kind, "--version", "")); err == nil {
			t.Errorf("%s: an option as the name should be refused", kind)
		}
	}
	if _, err := observeOne(obs("shell", "id", "")); err == nil {
		t.Error("an unknown kind should be an error")
	}
}

func TestObserveAccounts(t *testing.T) {
	if !haveProgram("getent") {
		t.Skip("no getent")
	}
	v, err := observeOne(obs("user", "root", ""))
	if err != nil || v["exists"] != "yes" || v["uid"] != "0" || v["home"] == "" {
		t.Errorf("root: %v %v", v, err)
	}
	if v, _ := observeOne(obs("user", "no-such-user-orphera", "")); v["exists"] != "no" {
		t.Errorf("missing user: %v", v)
	}
	if v, _ := observeOne(obs("group", "root", "")); v["exists"] != "yes" || v["gid"] != "0" {
		t.Errorf("root group: %v", v)
	}
	if v, _ := observeOne(obs("group", "no-such-group-orphera", "")); v["exists"] != "no" {
		t.Errorf("missing group: %v", v)
	}
	if v, _ := observeOne(obs("key", "no-such-user-orphera", "ssh-ed25519 AAAA")); v["exists"] != "no" {
		t.Errorf("key of a missing user: %v", v)
	}
}

// One item that cannot be read does not stop the others, and no script runs.
func TestObserveNodeCall(t *testing.T) {
	dir := t.TempDir()
	file := filepath.Join(dir, "f")
	os.WriteFile(file, []byte("x"), 0o644)
	s := NewService()
	reply, err := s.ObserveNode(context.Background(), &pb.ObserveRequest{Items: []*pb.Observation{
		{Id: 1, Kind: "path", Target: file},
		{Id: 2, Kind: "nonsense", Target: "x"},
		{Id: 3, Kind: "path", Target: filepath.Join(dir, "none")},
	}})
	if err != nil {
		t.Fatal(err)
	}
	if len(reply.Items) != 3 {
		t.Fatalf("items: %d", len(reply.Items))
	}
	if reply.Items[0].Id != 1 || reply.Items[0].Values["type"] != "file" {
		t.Errorf("first: %v", reply.Items[0])
	}
	if reply.Items[1].Error == "" || len(reply.Items[1].Values) != 0 {
		t.Errorf("second should be an error: %v", reply.Items[1])
	}
	if reply.Items[2].Values["type"] != "absent" {
		t.Errorf("third: %v", reply.Items[2])
	}
}

func TestObserveOperation(t *testing.T) {
	if got := operationOf("ObserveNode"); got != "observe" {
		t.Errorf("ObserveNode is in operation %q, want observe", got)
	}
}
