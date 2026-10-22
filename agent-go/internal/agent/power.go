// SPDX-License-Identifier: Apache-2.0

package agent

import (
	"fmt"
	"os"
	"time"

	"google.golang.org/grpc/codes"
	"google.golang.org/grpc/status"

	"github.com/ljalbinson/orphera-pkg/agent-go/internal/pb"
)

// A host's own refusal to be rebooted or powered off (NeverReboot). While the
// marker file exists the agent refuses TriggerReboot and TriggerShutdown. It
// is looked at on every request, so creating or removing it needs no restart.
const (
	neverRebootDefault = "/etc/orphera-agent/never-reboot"
	neverRebootEnv     = "ORPHERA_NEVER_REBOOT_FILE"
)

func neverRebootMarker(getenv func(string) (string, bool)) string {
	if p, ok := getenv(neverRebootEnv); ok {
		return p
	}
	return neverRebootDefault
}

// refusal is the error a protected host answers with; action reads as
// "refuses to <action>".
func refusal(action, marker string) error {
	return status.Error(codes.FailedPrecondition, fmt.Sprintf(
		"never-reboot: this host has %s, so its agent refuses to %s (remove that file to allow it)", marker, action))
}

// powerAction is a reboot or a power off, started detached via systemd-run
// because the agent's own unit is stopped by it: the command has to outlive
// the agent and the ack has to reach the orchestrator first.
type powerAction struct {
	verb     string // in the refusal: "reboot", "power off"
	unit     string // systemd unit prefix
	shutdown string // shutdown(8) flag
}

var (
	rebootAction   = powerAction{verb: "reboot", unit: "orphera-reboot", shutdown: "-r"}
	poweroffAction = powerAction{verb: "power off", unit: "orphera-shutdown", shutdown: "-P"}
)

// startPower is used to start the detached command; tests replace it.
var startPower = startDetached

// trigger refuses, without starting anything, if the host is protected;
// otherwise it schedules the action and returns the delay used (5 s if unset).
func (a powerAction) trigger(delaySeconds uint32, getenv func(string) (string, bool)) (uint32, error) {
	marker := neverRebootMarker(getenv)
	check := marker
	if check == "" {
		check = "." // as java.nio's Paths.get(""): an empty setting protects
	}
	if _, err := os.Stat(check); err == nil {
		return 0, refusal(a.verb, marker)
	}
	delay := delaySeconds
	if delay == 0 {
		delay = 5
	}
	unit := fmt.Sprintf("%s-%d", a.unit, time.Now().UnixMilli())
	if err := startPower("systemd-run", "--no-block", "--collect", "--unit", unit,
		"sh", "-c", fmt.Sprintf("sleep %d && shutdown %s now", delay, a.shutdown)); err != nil {
		return 0, err
	}
	return delay, nil
}

func triggerReboot(req *pb.RebootRequest) (*pb.RebootAck, error) {
	d, err := rebootAction.trigger(req.DelaySeconds, os.LookupEnv)
	if err != nil {
		return nil, err
	}
	return &pb.RebootAck{Message: fmt.Sprintf("Reboot scheduled in %ds", d)}, nil
}

func triggerShutdown(req *pb.ShutdownRequest) (*pb.ShutdownAck, error) {
	d, err := poweroffAction.trigger(req.DelaySeconds, os.LookupEnv)
	if err != nil {
		return nil, err
	}
	return &pb.ShutdownAck{Message: fmt.Sprintf("Shutdown (power off) scheduled in %ds", d)}, nil
}
