// SPDX-License-Identifier: Apache-2.0

package agent

import (
	"bytes"
	"context"
	"fmt"
	"os/exec"
	"regexp"
	"strings"
	"time"

	"github.com/ljalbinson/orphera-pkg/agent-go/internal/pb"
)

// ApplySoftware makes the changes it lists, in order, to packages and systemd
// units, from a fixed set of actions, and runs no script: install a package
// (at a version), remove one, enable, disable, start, stop or restart a unit,
// reload systemd. It is its own policy operation, `apply-software`, since a
// package's own scripts run as root. It stops at the first action that fails
// and says how far it got.
func (s *Service) ApplySoftware(ctx context.Context, req *pb.ApplySoftwareRequest) (*pb.ApplyReply, error) {
	reply := &pb.ApplyReply{}
	for i, a := range req.Actions {
		if err := applyOneSoftware(a); err != nil {
			reply.Error = validUTF8(fmt.Sprintf("%s: %s", describeSoftwareAction(a), err.Error()))
			return reply, nil
		}
		reply.Completed = int32(i + 1)
	}
	return reply, nil
}

func describeSoftwareAction(a *pb.SoftwareAction) string {
	switch x := a.Action.(type) {
	case *pb.SoftwareAction_PackageInstall:
		return "install " + x.PackageInstall.Name
	case *pb.SoftwareAction_PackageRemove:
		return "remove " + x.PackageRemove.Name
	case *pb.SoftwareAction_UnitControl:
		return x.UnitControl.Verb + " " + x.UnitControl.Unit
	case *pb.SoftwareAction_ReloadUnits:
		return "reload systemd"
	}
	return "unknown action"
}

// A package name or version as both apt and dnf write them: no "=" or "/",
// nothing that a package manager would read as an option or a file.
var packageWord = regexp.MustCompile(`^[A-Za-z0-9][A-Za-z0-9.+_:~-]*$`)

// systemd's own words for a unit's name: no path, no space.
var unitWord = regexp.MustCompile(`^[A-Za-z0-9][A-Za-z0-9:_.@-]*$`)

var unitVerbs = map[string]bool{
	"enable": true, "disable": true, "start": true, "stop": true,
	"restart": true, "try-restart": true,
}

func applyOneSoftware(a *pb.SoftwareAction) error {
	switch x := a.Action.(type) {
	case *pb.SoftwareAction_PackageInstall:
		return installPackage(x.PackageInstall)
	case *pb.SoftwareAction_PackageRemove:
		if !packageWord.MatchString(x.PackageRemove.Name) {
			return fmt.Errorf("not a valid package name: %q", x.PackageRemove.Name)
		}
		return removePackage(x.PackageRemove.Name)
	case *pb.SoftwareAction_UnitControl:
		u := x.UnitControl
		if !unitWord.MatchString(u.Unit) {
			return fmt.Errorf("not a valid unit name: %q", u.Unit)
		}
		if !unitVerbs[u.Verb] {
			return fmt.Errorf("not a unit action: %q", u.Verb)
		}
		return runSoftware(2*time.Minute, "systemctl", u.Verb, u.Unit)
	case *pb.SoftwareAction_ReloadUnits:
		return runSoftware(2*time.Minute, "systemctl", "daemon-reload")
	}
	return fmt.Errorf("an action this agent does not know")
}

func installPackage(p *pb.PackageInstall) error {
	if !packageWord.MatchString(p.Name) {
		return fmt.Errorf("not a valid package name: %q", p.Name)
	}
	if p.Version != "" && !packageWord.MatchString(p.Version) {
		return fmt.Errorf("not a valid package version: %q", p.Version)
	}
	const long = 15 * time.Minute
	switch detectPackageManager() {
	case pmApt:
		if err := waitAptIdle(long); err != nil {
			return err
		}
		spec := p.Name
		if p.Version != "" {
			spec = p.Name + "=" + p.Version
		}
		if p.UpdateCache {
			if err := runSoftware(long, "sh", "-c", aptUpdateScript, "apt-update", "-q"); err != nil {
				return err
			}
		}
		return runSoftware(long, "apt-get", "install", "-y", "-q",
			"-o", "DPkg::Lock::Timeout=300", "--allow-downgrades", spec)
	case pmDnf:
		spec := p.Name
		if p.Version != "" {
			spec = p.Name + "-" + p.Version
		}
		if err := runSoftware(long, "dnf", "-y", "-q", "install", spec); err != nil {
			// The wanted version is older than the installed one.
			if err2 := runSoftware(long, "dnf", "-y", "-q", "downgrade", spec); err2 != nil {
				return err
			}
		}
		return nil
	}
	return fmt.Errorf("neither apt nor dnf on this node")
}

func removePackage(name string) error {
	const long = 15 * time.Minute
	switch detectPackageManager() {
	case pmApt:
		if err := waitAptIdle(long); err != nil {
			return err
		}
		return runSoftware(long, "apt-get", "remove", "-y", "-q", "-o", "DPkg::Lock::Timeout=300", name)
	case pmDnf:
		return runSoftware(long, "dnf", "-y", "-q", "remove", name)
	}
	return fmt.Errorf("neither apt nor dnf on this node")
}

// runSoftwareCommand is what runs a program here; tests replace it.
var runSoftwareCommand = func(timeout time.Duration, name string, args ...string) error {
	ctx, cancel := context.WithTimeout(context.Background(), timeout)
	defer cancel()
	var out bytes.Buffer
	cmd := exec.CommandContext(ctx, name, args...)
	cmd.Env = withEnv(packageEnv)
	cmd.Stdout = &out
	cmd.Stderr = &out
	if err := cmd.Run(); err != nil {
		said := strings.TrimSpace(out.String())
		if said == "" {
			return fmt.Errorf("%s: %v", name, err)
		}
		if len(said) > 2000 {
			said = said[len(said)-2000:]
		}
		return fmt.Errorf("%s: %s", name, said)
	}
	return nil
}

func runSoftware(timeout time.Duration, name string, args ...string) error {
	return runSoftwareCommand(timeout, name, args...)
}

// aptPoll is how often waitAptIdle looks again; tests shorten it.
var aptPoll = 5 * time.Second

// aptBusy tells whether apt-get, apt, dpkg or unattended-upgrades is running.
// The dpkg lock alone is not enough: unattended-upgrades on a fresh VM can be
// between two locks, and when it ends needrestart restarts the agent in the
// middle of a task. A node without pgrep is taken to be idle.
var aptBusy = func() bool {
	for _, a := range [][]string{
		{"-x", "apt-get"}, {"-x", "apt"}, {"-x", "dpkg"},
		{"-f", "^/usr/bin/python3 /usr/bin/unattended-upgrade$"},
	} {
		if exec.Command("pgrep", a...).Run() == nil {
			return true
		}
	}
	return false
}

// waitAptIdle waits until nothing is using apt, for at most max.
func waitAptIdle(max time.Duration) error {
	deadline := time.Now().Add(max)
	for aptBusy() {
		if time.Now().After(deadline) {
			return fmt.Errorf("apt or dpkg still busy after %s (unattended-upgrades?)", max)
		}
		time.Sleep(aptPoll)
	}
	return nil
}
