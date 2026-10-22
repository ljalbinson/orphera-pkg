// SPDX-License-Identifier: Apache-2.0

package agent

import (
	"bytes"
	"errors"
	"os"
	"os/exec"
	"sync"
	"syscall"
)

// lineWriter turns a process's output into one OUTPUT event per line. Lines
// end at \n, \r\n or a lone \r, as with Java's BufferedReader.readLine, so
// progress output that redraws with \r still arrives as separate lines.
type lineWriter struct {
	mu   sync.Mutex
	buf  []byte
	emit func(string)
}

func newLineWriter(emit func(string)) *lineWriter { return &lineWriter{emit: emit} }

func (w *lineWriter) Write(p []byte) (int, error) {
	w.mu.Lock()
	defer w.mu.Unlock()
	w.buf = append(w.buf, p...)
	for {
		i := bytes.IndexAny(w.buf, "\r\n")
		if i < 0 {
			break
		}
		if w.buf[i] == '\r' && i == len(w.buf)-1 {
			// Wait for the next byte: this may be the start of \r\n.
			break
		}
		w.emit(string(w.buf[:i]))
		if w.buf[i] == '\r' && w.buf[i+1] == '\n' {
			i++
		}
		w.buf = w.buf[i+1:]
	}
	return len(p), nil
}

// flush emits whatever is left after the process has closed its output: a
// last line with no line ending (a lone trailing \r ends the line and is
// dropped).
func (w *lineWriter) flush() {
	w.mu.Lock()
	defer w.mu.Unlock()
	rest := bytes.TrimSuffix(w.buf, []byte("\r"))
	if len(w.buf) > 0 {
		w.emit(string(rest))
	}
	w.buf = nil
}

// exitCode is the code Java's Process.exitValue reports: the exit status, or
// 128 plus the signal number for a process killed by a signal.
func exitCode(state *os.ProcessState) int {
	if ws, ok := state.Sys().(syscall.WaitStatus); ok && ws.Signaled() {
		return 128 + int(ws.Signal())
	}
	return state.ExitCode()
}

// waitExit waits for cmd and returns its exit code. An error that is not just
// a non-zero exit (the process could not be waited for) is returned as is.
func waitExit(cmd *exec.Cmd) (int, error) {
	err := cmd.Wait()
	var ee *exec.ExitError
	if err == nil || errors.As(err, &ee) {
		return exitCode(cmd.ProcessState), nil
	}
	if cmd.ProcessState != nil && errors.Is(err, exec.ErrWaitDelay) {
		return exitCode(cmd.ProcessState), nil
	}
	return 0, err
}

// startDetached starts a command whose output goes to the agent's own (the
// journal, under systemd) and does not wait for it, like Java's
// ProcessBuilder.inheritIO().start(). It is reaped in the background.
func startDetached(name string, args ...string) error {
	cmd := exec.Command(name, args...)
	cmd.Stdout = os.Stdout
	cmd.Stderr = os.Stderr
	if err := cmd.Start(); err != nil {
		return err
	}
	go func() { _ = cmd.Wait() }()
	return nil
}

// runInherit runs a command with the agent's own output and returns its exit
// code, like Java's ProcessBuilder.inheritIO().start().waitFor().
func runInherit(name string, args ...string) (int, error) {
	cmd := exec.Command(name, args...)
	cmd.Stdout = os.Stdout
	cmd.Stderr = os.Stderr
	if err := cmd.Start(); err != nil {
		return 0, err
	}
	return waitExit(cmd)
}

// withEnv is the agent's environment with the given variables set or replaced.
func withEnv(set map[string]string) []string {
	env := os.Environ()
	out := make([]string, 0, len(env)+len(set))
	for _, kv := range env {
		k := kv
		if i := bytes.IndexByte([]byte(kv), '='); i >= 0 {
			k = kv[:i]
		}
		if _, replaced := set[k]; !replaced {
			out = append(out, kv)
		}
	}
	for k, v := range set {
		out = append(out, k+"="+v)
	}
	return out
}
