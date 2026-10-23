// SPDX-License-Identifier: Apache-2.0

package agent

import (
	"bufio"
	"errors"
	"fmt"
	"os"
	"os/exec"
	"strings"
	"time"

	"golang.org/x/sys/unix"

	"github.com/ljalbinson/orphera-pkg/agent-go/internal/pb"
)

// ---------------------------------------------------------------------------
// Package manager detection and dispatch (PackageManager, Dispatcher)

type pkgManager int

const (
	pmNone pkgManager = iota
	pmApt
	pmDnf
)

// detectPackageManager decides by what is installed rather than by
// distribution name, so derivatives (Rocky, Alma, CentOS Stream, Fedora;
// Debian, Ubuntu, Mint) need no list of names.
func detectPackageManager() pkgManager {
	if isExecutable("/usr/bin/apt-get") {
		return pmApt
	}
	if isExecutable("/usr/bin/dnf") {
		return pmDnf
	}
	return pmNone
}

func isExecutable(path string) bool {
	return unix.Access(path, unix.X_OK) == nil
}

// byPackageManager runs the apt or the dnf implementation of a package task,
// whichever this host has; a host with neither gets an immediate FAILED RESULT.
func byPackageManager(q *queue, apt, dnf func() error) error {
	switch detectPackageManager() {
	case pmApt:
		return apt()
	case pmDnf:
		return dnf()
	default:
		q.result("FAILED: no supported package manager found (need apt-get or dnf)", 1, false)
		return nil
	}
}

// ---------------------------------------------------------------------------
// Process-running steps shared by the apt and dnf backends (PackageSteps)

var packageEnv = map[string]string{
	"DEBIAN_FRONTEND":          "noninteractive",
	"APT_LISTCHANGES_FRONTEND": "none",
}

// runStep runs command with its output streamed as OUTPUT events and returns
// its exit code once the process has ended and all its output has been read.
func runStep(command []string, stage string, q *queue) (int, error) {
	q.progress(stage)
	cmd := exec.Command(command[0], command[1:]...)
	cmd.Env = withEnv(packageEnv)
	out := newLineWriter(q.output)
	errw := newLineWriter(q.output)
	cmd.Stdout = out
	cmd.Stderr = errw
	if err := cmd.Start(); err != nil {
		return 0, err
	}
	exit, err := waitExit(cmd)
	out.flush()
	errw.flush()
	return exit, err
}

// runPkg runs a command and ends the task with its RESULT.
func runPkg(command []string, stage string, q *queue) error {
	exit, err := runStep(command, stage, q)
	if err != nil {
		return err
	}
	q.result(okOrFailed(exit == 0), int32(exit), exit == 0)
	return nil
}

// runNoResult is runPkg without the RESULT, for a step chained before another
// in the same task (apt-get update before install). A RESULT is what closes
// the stream, so an intermediate step that sent one would let the
// orchestrator move on while the real install was still running. A failure
// is returned as an error instead.
func runNoResult(command []string, stage string, q *queue) error {
	exit, err := runStep(command, stage, q)
	if err != nil {
		return err
	}
	if exit != 0 {
		//lint:ignore ST1005 shown to the operator as "FAILED: <message>", worded as by the Scala agent
		return fmt.Errorf("Command failed with exit %d: %s", exit, strings.Join(command, " "))
	}
	return nil
}

// runInstallWithPostCheck runs an install and, if it succeeded, waits for the
// packages' binaries to be visible before sending the RESULT. listFiles(pkg)
// is the command that lists an installed package's files (`dpkg -L`, `rpm -ql`).
func runInstallWithPostCheck(command []string, stage string, packages []string, q *queue, listFiles func(string) []string) error {
	exit, err := runStep(command, stage, q)
	if err != nil {
		return err
	}
	postCheckOK := true
	if exit == 0 {
		if err := waitForBinariesVisible(packages, listFiles); err != nil {
			fmt.Fprintf(os.Stderr, "[DEBUG-POSTCHECK-FAIL] %s\n", err)
			postCheckOK = false
		}
	}
	fmt.Fprintf(os.Stderr, "[DEBUG-POSTCHECK] exit=%d postCheckOk=%t\n", exit, postCheckOK)
	success := exit == 0 && postCheckOK
	code := 0
	switch {
	case success:
	case exit != 0:
		code = exit
	default:
		code = 1
	}
	q.result(okOrFailed(success), int32(code), success)
	return nil
}

// Polling for each binary: 40 tries, 500 ms apart.
var (
	postCheckAttempts = 40
	postCheckInterval = 500 * time.Millisecond
)

// waitForBinariesVisible waits until every /usr/bin and /usr/sbin file a
// package ships exists and is executable; the install can report success a
// moment before that is true. It must NEVER run the binaries: an earlier
// version of the Scala agent executed each one to "prove" it worked, which on
// a package shipping halt/reboot/poweroff/shutdown (molly-guard,
// systemd-sysv) halted the host the moment the install finished.
func waitForBinariesVisible(packages []string, listFiles func(string) []string) error {
	var failed []string
	for _, raw := range packages {
		pkg := raw
		if i := strings.IndexByte(pkg, '='); i >= 0 {
			pkg = pkg[:i]
		}
		argv := listFiles(pkg)
		listing := exec.Command(argv[0], argv[1:]...)
		stdout, err := listing.StdoutPipe()
		if err != nil {
			return err
		}
		if err := listing.Start(); err != nil {
			return err
		}
		var binaries []string
		sc := bufio.NewScanner(stdout)
		sc.Buffer(make([]byte, 64*1024), 16*1024*1024)
		for sc.Scan() {
			p := strings.TrimSuffix(sc.Text(), "\r")
			if strings.HasPrefix(p, "/usr/bin/") || strings.HasPrefix(p, "/usr/sbin/") {
				binaries = append(binaries, p)
			}
		}
		_ = listing.Wait()

		for _, path := range binaries {
			ready := false
			for attempt := 0; attempt < postCheckAttempts; attempt++ {
				if _, err := os.Stat(path); err == nil && isExecutable(path) {
					ready = true
					break
				}
				time.Sleep(postCheckInterval)
			}
			if !ready {
				failed = append(failed, path)
			}
		}
	}
	if len(failed) > 0 {
		return errors.New("Binaries never became ready after install: " + strings.Join(failed, ", "))
	}
	return nil
}

func okOrFailed(ok bool) string {
	if ok {
		return "OK"
	}
	return "FAILED"
}

// ---------------------------------------------------------------------------
// apt (AptInstaller)

// How long apt/dpkg waits for another process (typically unattended-upgrades
// on a freshly provisioned VM, which can run for several minutes) to release
// the dpkg lock before giving up with exit 100.
const aptLockTimeout = "DPkg::Lock::Timeout=600"

var aptOptions = []string{
	"-o", aptLockTimeout,
	"-o", "Dpkg::Use-Pty=0",
	"-o", "APT::Color=0",
	"-o", "APT::Get::Assume-Yes=true",
}

func concat(parts ...[]string) []string {
	var out []string
	for _, p := range parts {
		out = append(out, p...)
	}
	return out
}

func aptInstall(cmd *pb.InstallPackages, q *queue) error {
	if cmd.UpdateCache {
		// A failed update must end the stream with a FAILED RESULT.
		if err := runNoResult([]string{"apt-get", "-o", aptLockTimeout, "update"}, "Updating package cache", q); err != nil {
			q.failed(err)
			return nil
		}
	}
	install := concat([]string{"apt-get"}, aptOptions, []string{"install", "-y"}, cmd.Packages)
	return runInstallWithPostCheck(install, "Installing packages", cmd.Packages, q,
		func(pkg string) []string { return []string{"dpkg", "-L", pkg} })
}

func aptRemove(cmd *pb.RemovePackages, q *queue) error {
	sub, stage := "remove", "Removing packages"
	if cmd.Purge {
		sub, stage = "purge", "Purging packages"
	}
	return runPkg(concat([]string{"apt-get"}, aptOptions, []string{sub, "-y"}, cmd.Packages), stage, q)
}

func aptAutoRemove(cmd *pb.AutoRemove, q *queue) error {
	var flags []string
	stage := "Auto-removing packages"
	if cmd.Purge {
		flags = []string{"--purge"}
		stage = "Auto-removing packages (purge)"
	}
	return runPkg(concat([]string{"apt-get"}, aptOptions, []string{"autoremove", "-y"}, flags), stage, q)
}

// aptDistUpgrade is `apt-get dist-upgrade`, optionally preceded by
// `apt-get update`. --force-confdef/--force-confold keep existing config
// files instead of stalling on a conffile prompt, since there is no tty.
func aptDistUpgrade(cmd *pb.DistUpgrade, q *queue) error {
	if cmd.UpdateCache {
		if err := runNoResult(concat([]string{"apt-get"}, aptOptions, []string{"update"}), "Updating package cache", q); err != nil {
			q.failed(err)
			return nil
		}
	}
	return runPkg(concat([]string{"apt-get"}, aptOptions, []string{
		"-o", "Dpkg::Options::=--force-confdef",
		"-o", "Dpkg::Options::=--force-confold",
		"dist-upgrade", "-y",
	}), "Upgrading packages (dist-upgrade)", q)
}

// ---------------------------------------------------------------------------
// dnf (DnfInstaller)
//
// The wire messages are package-manager neutral, so their apt-flavoured
// fields map as follows: update_cache refreshes repository metadata
// (`dnf makecache`), purge has no dnf equivalent and is ignored, and a
// name=version package becomes dnf's name-version.

var dnf = []string{"dnf", "-y"}

func dnfPackage(spec string) string { return strings.Replace(spec, "=", "-", 1) }

func dnfInstall(cmd *pb.InstallPackages, q *queue) error {
	if cmd.UpdateCache {
		if err := runNoResult(concat(dnf, []string{"makecache"}), "Updating package cache", q); err != nil {
			q.failed(err)
			return nil
		}
	}
	pkgs := make([]string, len(cmd.Packages))
	for i, p := range cmd.Packages {
		pkgs[i] = dnfPackage(p)
	}
	return runInstallWithPostCheck(concat(dnf, []string{"install"}, pkgs), "Installing packages", cmd.Packages, q,
		func(pkg string) []string { return []string{"rpm", "-ql", pkg} })
}

func dnfRemove(cmd *pb.RemovePackages, q *queue) error {
	return runPkg(concat(dnf, []string{"remove"}, cmd.Packages), "Removing packages", q)
}

func dnfAutoRemove(_ *pb.AutoRemove, q *queue) error {
	return runPkg(concat(dnf, []string{"autoremove"}), "Auto-removing packages", q)
}

// dnfDistUpgrade is `dnf upgrade` (Red Hat has no separate dist-upgrade).
func dnfDistUpgrade(cmd *pb.DistUpgrade, q *queue) error {
	var refresh []string
	if cmd.UpdateCache {
		refresh = []string{"--refresh"}
	}
	return runPkg(concat(dnf, []string{"upgrade"}, refresh), "Upgrading packages (dnf upgrade)", q)
}

// ---------------------------------------------------------------------------
// Agent self-upgrade from a local package file (DebInstaller, RpmInstaller)

// The only package InstallDebPackage may install, ever, checked against the
// package's own metadata rather than its file name: TLS and the token say
// who may call this RPC, not what it may install as root.
const expectedPackageName = "orphera-agent"

// The install script gets the package path as $1 rather than having it
// pasted into the script, so a path containing shell syntax cannot run
// anything. (The Scala agent pastes it in; with an ordinary path the two
// behave the same.)
const debScript = `for i in $(seq 1 12); do
  if dpkg -i "$1"; then exit 0; fi
  sleep 5
done
exit 1`

// dnf skips an rpm whose name, version and release are already installed, so
// a rebuild at an unchanged VERSION is reinstalled.
const rpmScript = `nvr=$(rpm -qp --queryformat '%{NAME}-%{VERSION}-%{RELEASE}' "$1")
if rpm -q "$nvr" >/dev/null 2>&1; then op=reinstall; else op=install; fi
for i in $(seq 1 12); do
  if dnf -y $op "$1"; then exit 0; fi
  sleep 5
done
exit 1`

func debInstall(cmd *pb.InstallDeb, q *queue) error {
	return selfInstall(cmd.Path, q,
		[]string{"dpkg-deb", "-f", cmd.Path, "Package"},
		"Package field", "dpkg-deb", ".deb", debScript)
}

func rpmInstall(cmd *pb.InstallDeb, q *queue) error {
	return selfInstall(cmd.Path, q,
		[]string{"rpm", "-qp", "--queryformat", "%{NAME}", cmd.Path},
		"Name", "rpm", ".rpm", rpmScript)
}

// selfInstall verifies the package's name, then starts the install as a
// detached systemd unit: installing restarts the agent whose connection is
// running it, so the install has to outlive that connection.
func selfInstall(path string, q *queue, query []string, field, tool, ext, script string) error {
	out, err := exec.Command(query[0], query[1:]...).CombinedOutput()
	name := strings.TrimSpace(string(out))
	var ee *exec.ExitError
	switch {
	case err != nil && !errors.As(err, &ee):
		q.result(fmt.Sprintf("Refusing to install %s: failed to run %s (%s)", path, tool, err), 1, false)
		return nil
	case err != nil:
		q.result(fmt.Sprintf("Refusing to install %s: could not read its package metadata (%s exited %d) — is this a valid %s?",
			path, tool, exitCode(ee.ProcessState), ext), 1, false)
		return nil
	case name != expectedPackageName:
		q.result(fmt.Sprintf("Refusing to install %s: its %s is '%s', not '%s'.", path, field, name, expectedPackageName), 1, false)
		return nil
	}

	q.progress("Starting detached install of " + path)
	unit := fmt.Sprintf("orphera-deploy-%d", time.Now().UnixMilli())
	if err := startDetached("systemd-run", "--no-block", "--collect", "--unit", unit,
		"sh", "-c", script, "orphera-deploy", path); err != nil {
		return err
	}
	q.result("Install started as a detached unit — this connection will likely drop if the install restarts the agent", 0, true)
	return nil
}
