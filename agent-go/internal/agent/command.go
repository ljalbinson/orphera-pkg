// SPDX-License-Identifier: Apache-2.0

package agent

import (
	"context"
	"errors"
	"fmt"
	"os/exec"
	"strings"
	"time"

	"github.com/ljalbinson/orphera-pkg/agent-go/internal/pb"
)

const commandPath = "/usr/local/sbin:/usr/local/bin:/usr/sbin:/usr/bin:/sbin:/bin"

// runCommand is ExecuteCommand (CommandRunner): runs an arbitrary command,
// streaming its output, killing it after timeout_seconds (60 if unset).
func runCommand(req *pb.RunCommandRequest, q *queue) error {
	if len(req.Command) == 0 {
		q.result("No command given", 1, false)
		return nil
	}
	timeout := int(req.TimeoutSeconds)
	if timeout <= 0 {
		timeout = 60
	}
	q.progress("Running: " + strings.Join(req.Command, " "))

	ctx, cancel := context.WithTimeout(context.Background(), time.Duration(timeout)*time.Second)
	defer cancel()
	cmd := exec.CommandContext(ctx, req.Command[0], req.Command[1:]...)
	cmd.Env = withEnv(map[string]string{"PATH": commandPath})
	// On timeout the process is killed (SIGKILL, as destroyForcibly) and its
	// output is no longer waited for, even if a child it left behind still
	// holds the pipes open.
	cmd.WaitDelay = time.Second
	out := newLineWriter(q.output)
	errw := newLineWriter(q.output)
	cmd.Stdout = out
	cmd.Stderr = errw
	if err := cmd.Start(); err != nil {
		return err
	}
	exit, err := waitExit(cmd)
	if errors.Is(ctx.Err(), context.DeadlineExceeded) {
		q.result(fmt.Sprintf("Timed out after %ds", timeout), 124, false)
		return nil
	}
	if err != nil {
		return err
	}
	out.flush()
	errw.flush()
	q.result(okOrFailed(exit == 0), int32(exit), exit == 0)
	return nil
}
