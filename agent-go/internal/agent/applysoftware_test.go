// SPDX-License-Identifier: Apache-2.0

package agent

import (
	"context"
	"fmt"
	"strings"
	"testing"
	"time"

	"github.com/ljalbinson/orphera-pkg/agent-go/internal/pb"
)

// No test waits for the real apt: pgrep would look at the machine running the tests.
func init() { aptBusy = func() bool { return false } }

func installA(name, version string, update bool) *pb.SoftwareAction {
	return &pb.SoftwareAction{Action: &pb.SoftwareAction_PackageInstall{
		PackageInstall: &pb.PackageInstall{Name: name, Version: version, UpdateCache: update}}}
}

func removeA(name string) *pb.SoftwareAction {
	return &pb.SoftwareAction{Action: &pb.SoftwareAction_PackageRemove{PackageRemove: &pb.PackageRemove{Name: name}}}
}

func unitA(unit, verb string) *pb.SoftwareAction {
	return &pb.SoftwareAction{Action: &pb.SoftwareAction_UnitControl{UnitControl: &pb.UnitControl{Unit: unit, Verb: verb}}}
}

func reloadA() *pb.SoftwareAction {
	return &pb.SoftwareAction{Action: &pb.SoftwareAction_ReloadUnits{ReloadUnits: &pb.ReloadUnits{}}}
}

// applySoftware runs the actions with a recorder in place of the programs.
func applySoftware(t *testing.T, fail string, actions ...*pb.SoftwareAction) (*pb.ApplyReply, []string) {
	t.Helper()
	old := runSoftwareCommand
	defer func() { runSoftwareCommand = old }()
	var ran []string
	runSoftwareCommand = func(_ time.Duration, name string, args ...string) error {
		line := name + " " + strings.Join(args, " ")
		ran = append(ran, line)
		if fail != "" && strings.Contains(line, fail) {
			return fmt.Errorf("%s: boom", name)
		}
		return nil
	}
	r, err := NewService().ApplySoftware(context.Background(), &pb.ApplySoftwareRequest{Actions: actions})
	if err != nil {
		t.Fatal(err)
	}
	return r, ran
}

func TestApplySoftwareUnits(t *testing.T) {
	r, ran := applySoftware(t, "", reloadA(), unitA("ssh.service", "enable"), unitA("ssh.service", "restart"))
	if r.Error != "" || r.Completed != 3 {
		t.Fatalf("%+v", r)
	}
	want := []string{"systemctl daemon-reload", "systemctl enable ssh.service", "systemctl restart ssh.service"}
	if strings.Join(ran, "|") != strings.Join(want, "|") {
		t.Errorf("ran %q", ran)
	}
}

func TestApplySoftwareRefusals(t *testing.T) {
	for _, a := range []*pb.SoftwareAction{
		installA("-y", "", false),
		installA("a b", "", false),
		installA("a=1", "", false),
		installA("../x", "", false),
		installA("ok", "1;rm", false),
		installA("ok", "-1", false),
		removeA("--force"),
		removeA(""),
		unitA("/etc/x.service", "start"),
		unitA("x service", "start"),
		unitA("-x", "start"),
		unitA("x.service", "mask"),
		unitA("x.service", "--now"),
		{},
	} {
		r, ran := applySoftware(t, "", a)
		if r.Error == "" || len(ran) != 0 {
			t.Errorf("should be refused without running anything: %v -> %+v %q", a, r, ran)
		}
	}
}

func TestApplySoftwareStopsAtFirstFailure(t *testing.T) {
	r, ran := applySoftware(t, "enable", reloadA(), unitA("a.service", "enable"), unitA("a.service", "start"))
	if r.Error == "" || r.Completed != 1 || len(ran) != 2 {
		t.Fatalf("%+v %q", r, ran)
	}
	if !strings.Contains(r.Error, "enable a.service") {
		t.Errorf("error names the action: %q", r.Error)
	}
}

func TestApplySoftwarePackages(t *testing.T) {
	if detectPackageManager() == pmNone {
		t.Skip("no apt or dnf here")
	}
	r, ran := applySoftware(t, "", installA("curl", "7.1-2", true), removeA("curl"))
	if r.Error != "" || len(ran) < 2 {
		t.Fatalf("%+v %q", r, ran)
	}
	joined := strings.Join(ran, "\n")
	if detectPackageManager() == pmApt {
		for _, w := range []string{`apt-get "$@" update`, "apt-update -q", "apt-get install -y -q -o DPkg::Lock::Timeout=300 --allow-downgrades curl=7.1-2", "apt-get remove -y -q"} {
			if !strings.Contains(joined, w) {
				t.Errorf("missing %q in %q", w, joined)
			}
		}
	}
}

func TestApplySoftwareOperation(t *testing.T) {
	if got := operationOf("ApplySoftware"); got != "apply-software" {
		t.Errorf("operation %q", got)
	}
}

func TestWaitAptIdle(t *testing.T) {
	oldBusy, oldPoll := aptBusy, aptPoll
	defer func() { aptBusy, aptPoll = oldBusy, oldPoll }()
	aptPoll = time.Millisecond

	n := 0
	aptBusy = func() bool { n++; return n <= 3 }
	if err := waitAptIdle(time.Second); err != nil || n != 4 {
		t.Errorf("busy three times, then idle: err=%v looks=%d", err, n)
	}
	aptBusy = func() bool { return true }
	if err := waitAptIdle(20 * time.Millisecond); err == nil {
		t.Error("always busy should time out")
	}
	aptBusy = func() bool { return false }
	if err := waitAptIdle(time.Second); err != nil {
		t.Errorf("idle: %v", err)
	}
}

func TestInstallWaitsForAptFirst(t *testing.T) {
	oldBusy, oldPoll, oldRun := aptBusy, aptPoll, runSoftwareCommand
	defer func() { aptBusy, aptPoll, runSoftwareCommand = oldBusy, oldPoll, oldRun }()
	aptPoll = time.Millisecond
	busy := true
	looks := 0
	aptBusy = func() bool {
		looks++
		if looks >= 3 {
			busy = false
		}
		return busy
	}
	ranWhileBusy := false
	runSoftwareCommand = func(_ time.Duration, name string, args ...string) error {
		if busy {
			ranWhileBusy = true
		}
		return nil
	}
	if detectPackageManager() != pmApt {
		t.Skip("not an apt node")
	}
	if err := installPackage(&pb.PackageInstall{Name: "curl"}); err != nil {
		t.Fatal(err)
	}
	if ranWhileBusy || looks < 3 {
		t.Errorf("apt-get ran while busy=%v, looks=%d", ranWhileBusy, looks)
	}
}
