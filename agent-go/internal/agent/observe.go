// SPDX-License-Identifier: Apache-2.0

package agent

import (
	"bufio"
	"bytes"
	"context"
	"fmt"
	"io"
	"os"
	"os/exec"
	"path/filepath"
	"strconv"
	"strings"
	"syscall"
	"time"
	"unicode"

	"google.golang.org/grpc/codes"
	"google.golang.org/grpc/status"

	"github.com/ljalbinson/orphera-pkg/agent-go/internal/pb"
)

// ObserveNode reads what the request lists, from a fixed set of kinds, and
// runs no script: a role that may only look can make this call. Each item is
// answered by itself (values, or an error for that item), so one that cannot
// be read does not stop the others. A path the caller's role may not reach
// (the role's `paths`) refuses the whole call.
func (s *Service) ObserveNode(ctx context.Context, req *pb.ObserveRequest) (*pb.ObserveReply, error) {
	const maxItems = 2000
	if len(req.Items) > maxItems {
		return nil, status.Errorf(codes.InvalidArgument, "at most %d items in one call", maxItems)
	}
	reply := &pb.ObserveReply{}
	for _, item := range req.Items {
		for _, p := range observePaths(item) {
			if err := pathDenied(ctx, p); err != nil {
				return nil, err
			}
		}
		out := &pb.ObservedItem{Id: item.Id}
		values, err := observeOne(item)
		if err != nil {
			out.Error = validUTF8(err.Error())
		} else {
			out.Values = values
		}
		reply.Items = append(reply.Items, out)
	}
	return reply, nil
}

// observePaths are the files an item reads, for the role's path limits.
func observePaths(it *pb.Observation) []string {
	switch it.Kind {
	case "path", "file_text", "mount":
		return []string{it.Target}
	case "sysctl", "module":
		if it.Extra != "" {
			return []string{it.Extra}
		}
	}
	return nil
}

// Where the mounts are read from; tests point these elsewhere.
var (
	fstabPath     = "/etc/fstab"
	mountInfoPath = "/proc/self/mountinfo"
)

// Persisted settings are read only from these directories.
const (
	sysctlDir  = "/etc/sysctl.d/"
	modulesDir = "/etc/modules-load.d/"
)

// maxObservedText bounds the text of a file_text item.
const maxObservedText = 256 * 1024

func observeOne(it *pb.Observation) (map[string]string, error) {
	switch it.Kind {
	case "path":
		return observePath(it.Target)
	case "package":
		return observePackage(it.Target)
	case "unit":
		return observeUnit(it.Target)
	case "user":
		return observeUser(it.Target)
	case "group":
		return observeGroup(it.Target)
	case "sysctl":
		return observeSysctl(it.Target, it.Extra)
	case "module":
		return observeModule(it.Target, it.Extra)
	case "file_text":
		return observeFileText(it.Target)
	case "key":
		return observeKey(it.Target, it.Extra)
	case "mount":
		return observeMount(it.Target)
	}
	return nil, fmt.Errorf("unknown kind of observation: %q", it.Kind)
}

// validName is a name safe to pass to a program as one argument: not empty,
// not an option, no white space or control characters.
func validName(s string) bool {
	if s == "" || strings.HasPrefix(s, "-") {
		return false
	}
	for _, r := range s {
		if unicode.IsSpace(r) || unicode.IsControl(r) {
			return false
		}
	}
	return true
}

func needName(what, s string) error {
	if !validName(s) {
		return fmt.Errorf("not a valid %s: %q", what, s)
	}
	return nil
}

func needAbsolute(path string) error {
	if !filepath.IsAbs(path) || strings.ContainsRune(path, 0) {
		return fmt.Errorf("not an absolute path: %q", path)
	}
	return nil
}

// observeRun runs a fixed program (never a shell) and returns what it printed
// and its exit code (-1: it could not be run).
func observeRun(name string, args ...string) (string, int) {
	ctx, cancel := context.WithTimeout(context.Background(), 20*time.Second)
	defer cancel()
	cmd := exec.CommandContext(ctx, name, args...)
	var out bytes.Buffer
	cmd.Stdout = &out
	err := cmd.Run()
	if err == nil {
		return out.String(), 0
	}
	if ee, ok := err.(*exec.ExitError); ok {
		return out.String(), ee.ExitCode()
	}
	return out.String(), -1
}

func haveProgram(name string) bool {
	_, err := exec.LookPath(name)
	return err == nil
}

func octal(m uint32) string { return strconv.FormatUint(uint64(m), 8) }

// observePath is what stat, readlink and sha256sum say of a path.
func observePath(path string) (map[string]string, error) {
	if err := needAbsolute(path); err != nil {
		return nil, err
	}
	if protectedRefusal(path) != "" {
		return nil, fmt.Errorf("protected path")
	}
	fi, err := os.Lstat(path)
	if os.IsNotExist(err) {
		return map[string]string{"type": "absent"}, nil
	}
	if err != nil {
		return nil, err
	}
	if fi.Mode()&os.ModeSymlink != 0 {
		target, err := os.Readlink(path)
		if err != nil {
			return nil, err
		}
		return map[string]string{"type": "link", "target": target}, nil
	}
	st, ok := fi.Sys().(*syscall.Stat_t)
	if !ok {
		return nil, fmt.Errorf("cannot read the owner of %s", path)
	}
	vals := map[string]string{
		"mode":  octal(st.Mode & 07777),
		"owner": userName(st.Uid),
		"group": groupName(st.Gid),
	}
	switch {
	case fi.Mode().IsRegular():
		sum, err := sha256File(path)
		if err != nil {
			return nil, err
		}
		vals["type"] = "file"
		vals["sha256"] = sum
	case fi.IsDir():
		vals["type"] = "directory"
	default:
		return map[string]string{"type": "other"}, nil
	}
	return vals, nil
}

// observePackage is the state of a package under dpkg or rpm.
func observePackage(name string) (map[string]string, error) {
	if err := needName("package name", name); err != nil {
		return nil, err
	}
	if haveProgram("dpkg-query") && haveProgram("apt-get") {
		out, _ := observeRun("dpkg-query", "-W", "-f", "${db:Status-Abbrev}|${Version}", name)
		vals := map[string]string{"manager": "apt", "installed": "no"}
		// "?i*": the second character is "i" when it is installed.
		if len(out) >= 2 && out[1] == 'i' {
			vals["installed"] = "yes"
			if i := strings.Index(out, "|"); i >= 0 {
				vals["versions"] = out[i+1:]
			}
		}
		return vals, nil
	}
	if haveProgram("rpm") {
		out, code := observeRun("rpm", "-q", "--qf", "%{VERSION}-%{RELEASE} ", name)
		vals := map[string]string{"manager": "dnf", "installed": "no"}
		if code == 0 {
			vals["installed"] = "yes"
			vals["versions"] = strings.Join(strings.Fields(out), ",")
		}
		return vals, nil
	}
	return map[string]string{"manager": "none"}, nil
}

// observeUnit is `systemctl is-enabled` and `is-active` for a unit.
func observeUnit(unit string) (map[string]string, error) {
	if err := needName("unit name", unit); err != nil {
		return nil, err
	}
	en, _ := observeRun("systemctl", "is-enabled", unit)
	enabled := firstLine(en)
	if enabled == "" {
		enabled = "not-found"
	}
	if enabled == "not-found" {
		if _, code := observeRun("systemctl", "cat", unit); code == 0 {
			enabled = "static"
		}
	}
	ac, _ := observeRun("systemctl", "is-active", unit)
	return map[string]string{"enabled": enabled, "active": firstLine(ac)}, nil
}

func firstLine(s string) string {
	s = strings.TrimSpace(s)
	if i := strings.IndexByte(s, '\n'); i >= 0 {
		s = s[:i]
	}
	return strings.TrimSpace(s)
}

// passwdEntry is a user's `getent passwd` line, split.
func passwdEntry(name string) ([]string, bool) {
	out, code := observeRun("getent", "passwd", name)
	if code != 0 {
		return nil, false
	}
	f := strings.Split(firstLine(out), ":")
	if len(f) < 7 {
		return nil, false
	}
	return f, true
}

func observeUser(name string) (map[string]string, error) {
	if err := needName("user name", name); err != nil {
		return nil, err
	}
	f, ok := passwdEntry(name)
	if !ok {
		return map[string]string{"exists": "no"}, nil
	}
	g, _ := observeRun("id", "-gn", name)
	gs, _ := observeRun("id", "-Gn", name)
	return map[string]string{
		"exists": "yes",
		"uid":    f[2],
		"home":   f[5],
		"shell":  f[6],
		"group":  firstLine(g),
		"groups": strings.Join(strings.Fields(gs), ","),
	}, nil
}

func observeGroup(name string) (map[string]string, error) {
	if err := needName("group name", name); err != nil {
		return nil, err
	}
	out, code := observeRun("getent", "group", name)
	if code != 0 {
		return map[string]string{"exists": "no"}, nil
	}
	f := strings.Split(firstLine(out), ":")
	if len(f) < 3 {
		return map[string]string{"exists": "no"}, nil
	}
	return map[string]string{"exists": "yes", "gid": f[2]}, nil
}

// firstLineOf is the first line of a small file, "" if it cannot be read.
func firstLineOf(path string) string {
	f, err := os.Open(path)
	if err != nil {
		return ""
	}
	defer f.Close()
	sc := bufio.NewScanner(f)
	if sc.Scan() {
		return sc.Text()
	}
	return ""
}

func inDir(path, dir string) error {
	if err := needAbsolute(path); err != nil {
		return err
	}
	if filepath.Dir(filepath.Clean(path)) != filepath.Clean(dir) {
		return fmt.Errorf("%s is not directly in %s", path, dir)
	}
	return nil
}

// observeSysctl is the running value of a key (`sysctl -n`) and the first line
// of the file it is persisted in.
func observeSysctl(key, persisted string) (map[string]string, error) {
	if err := needName("sysctl key", key); err != nil {
		return nil, err
	}
	vals := map[string]string{}
	out, code := observeRun("sysctl", "-n", key)
	if code == 0 {
		vals["found"] = "yes"
		vals["runtime"] = strings.Join(strings.Fields(out), " ")
	} else {
		vals["found"] = "no"
	}
	if persisted != "" {
		if err := inDir(persisted, sysctlDir); err != nil {
			return nil, err
		}
		vals["persisted"] = firstLineOf(persisted)
	} else {
		vals["persisted"] = ""
	}
	return vals, nil
}

// observeModule is whether a kernel module is loaded and the first line of the
// file that loads it at boot.
func observeModule(name, persisted string) (map[string]string, error) {
	if err := needName("module name", name); err != nil {
		return nil, err
	}
	vals := map[string]string{"loaded": "no"}
	dir := "/sys/module/" + strings.ReplaceAll(name, "-", "_")
	if fi, err := os.Stat(dir); err == nil && fi.IsDir() {
		vals["loaded"] = "yes"
	}
	vals["persisted"] = ""
	if persisted != "" {
		if err := inDir(persisted, modulesDir); err != nil {
			return nil, err
		}
		vals["persisted"] = firstLineOf(persisted)
	}
	return vals, nil
}

// observeFileText is the text of a regular file (up to maxObservedText bytes),
// for the orchestrator to work out a line edit from.
func observeFileText(path string) (map[string]string, error) {
	if err := needAbsolute(path); err != nil {
		return nil, err
	}
	if protectedRefusal(path) != "" {
		return nil, fmt.Errorf("protected path")
	}
	fi, err := os.Stat(path)
	if os.IsNotExist(err) {
		return map[string]string{"exists": "no"}, nil
	}
	if err != nil {
		return nil, err
	}
	if !fi.Mode().IsRegular() {
		return nil, fmt.Errorf("%s is not a regular file", path)
	}
	f, err := os.Open(path)
	if err != nil {
		return nil, err
	}
	defer f.Close()
	buf, err := io.ReadAll(io.LimitReader(f, maxObservedText+1))
	if err != nil {
		return nil, err
	}
	n := len(buf)
	vals := map[string]string{"exists": "yes", "truncated": "no"}
	if n > maxObservedText {
		n = maxObservedText
		vals["truncated"] = "yes"
	}
	vals["content"] = validUTF8(string(buf[:n]))
	return vals, nil
}

// observeKey counts the lines of a user's authorized_keys whose first two
// fields are keyID ("type base64").
func observeKey(userName, keyID string) (map[string]string, error) {
	if err := needName("user name", userName); err != nil {
		return nil, err
	}
	f, ok := passwdEntry(userName)
	if !ok {
		return map[string]string{"exists": "no"}, nil
	}
	file := filepath.Join(f[5], ".ssh", "authorized_keys")
	vals := map[string]string{"exists": "yes", "count": "0"}
	data, err := os.ReadFile(file)
	if err != nil {
		return vals, nil
	}
	count := 0
	for _, line := range strings.Split(string(data), "\n") {
		fields := strings.Fields(line)
		if len(fields) >= 2 && fields[0]+" "+fields[1] == keyID {
			count++
		}
	}
	vals["count"] = strconv.Itoa(count)
	return vals, nil
}

// observeMount is the fstab entry of a mount point and whether it is mounted.
func observeMount(path string) (map[string]string, error) {
	if err := needAbsolute(path); err != nil {
		return nil, err
	}
	vals := map[string]string{"fstab": "no", "mounted": "no"}
	if data, err := os.ReadFile(fstabPath); err == nil {
		for _, line := range strings.Split(string(data), "\n") {
			f := strings.Fields(line)
			if len(f) >= 4 && !strings.HasPrefix(f[0], "#") && f[1] == path {
				vals["fstab"] = "yes"
				vals["dev"] = f[0]
				vals["fstype"] = f[2]
				vals["opts"] = f[3]
				break
			}
		}
	}
	if data, err := os.ReadFile(mountInfoPath); err == nil {
		for _, line := range strings.Split(string(data), "\n") {
			f := strings.Fields(line)
			if len(f) > 4 && unescapeMountField(f[4]) == path {
				vals["mounted"] = "yes"
				break
			}
		}
	}
	return vals, nil
}

// unescapeMountField undoes the octal escapes (\040 for a space) of
// /proc/self/mountinfo.
func unescapeMountField(s string) string {
	var b strings.Builder
	for i := 0; i < len(s); i++ {
		if s[i] == '\\' && i+3 < len(s) {
			if n, err := strconv.ParseUint(s[i+1:i+4], 8, 8); err == nil {
				b.WriteByte(byte(n))
				i += 3
				continue
			}
		}
		b.WriteByte(s[i])
	}
	return b.String()
}
