// SPDX-License-Identifier: Apache-2.0

package agent

import (
	"os"
	"os/exec"
	"path/filepath"
	"strings"
	"testing"
)

// fakeApt puts an `apt-get` first on PATH that counts its calls in a file and
// runs body, and returns that file's path. body sees $n, the call number.
func fakeApt(t *testing.T, body string) string {
	t.Helper()
	dir := t.TempDir()
	counter := filepath.Join(dir, "calls")
	script := "#!/bin/sh\necho x >> " + counter + "\nn=$(wc -l < " + counter + ")\n" + body + "\n"
	if err := os.WriteFile(filepath.Join(dir, "apt-get"), []byte(script), 0o755); err != nil {
		t.Fatal(err)
	}
	t.Setenv("PATH", dir+":"+os.Getenv("PATH"))
	t.Setenv("APT_UPDATE_TIMEOUT", "1")
	return counter
}

func calls(t *testing.T, counter string) int {
	t.Helper()
	b, err := os.ReadFile(counter)
	if err != nil {
		return 0
	}
	return len(strings.Fields(string(b)))
}

func runUpdate(opts ...string) (string, int) {
	c := exec.Command(aptUpdateCommand(opts...)[0], aptUpdateCommand(opts...)[1:]...)
	out, err := c.CombinedOutput()
	if ee, ok := err.(*exec.ExitError); ok {
		return string(out), ee.ExitCode()
	}
	return string(out), 0
}

func TestAptUpdateCommandPassesTheOptions(t *testing.T) {
	counter := fakeApt(t, `echo "args: $*"`)
	out, code := runUpdate("-o", "X=1", "-q")
	if code != 0 || !strings.Contains(out, "args: -o X=1 -q update") {
		t.Fatalf("exit %d, output %q", code, out)
	}
	if calls(t, counter) != 1 {
		t.Fatalf("want 1 call, got %d", calls(t, counter))
	}
}

func TestAptUpdateTriesAgainAfterAStall(t *testing.T) {
	// The first call hangs past the limit; the second answers.
	counter := fakeApt(t, `if [ "$n" -eq 1 ]; then sleep 30; fi; echo done`)
	out, code := runUpdate()
	if code != 0 || !strings.Contains(out, "done") {
		t.Fatalf("exit %d, output %q", code, out)
	}
	if !strings.Contains(out, "try 1 of 3") {
		t.Fatalf("no note of the first try in %q", out)
	}
	if calls(t, counter) != 2 {
		t.Fatalf("want 2 calls, got %d", calls(t, counter))
	}
}

func TestAptUpdateGivesUpAfterThreeStalls(t *testing.T) {
	counter := fakeApt(t, `sleep 30`)
	_, code := runUpdate()
	if code != 124 && code != 137 {
		t.Fatalf("want the timeout exit code, got %d", code)
	}
	if calls(t, counter) != 3 {
		t.Fatalf("want 3 calls, got %d", calls(t, counter))
	}
}

func TestAptUpdateDoesNotRepeatARealFailure(t *testing.T) {
	counter := fakeApt(t, `echo "E: repository is broken" >&2; exit 100`)
	out, code := runUpdate()
	if code != 100 || !strings.Contains(out, "repository is broken") {
		t.Fatalf("exit %d, output %q", code, out)
	}
	if calls(t, counter) != 1 {
		t.Fatalf("a real failure must not be repeated; %d calls", calls(t, counter))
	}
}
