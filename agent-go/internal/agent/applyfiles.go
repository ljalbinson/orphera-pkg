// SPDX-License-Identifier: Apache-2.0

package agent

import (
	"bytes"
	"context"
	"crypto/sha256"
	"encoding/hex"
	"fmt"
	"os"
	"os/exec"
	"path/filepath"
	"strconv"
	"strings"
	"syscall"
	"time"

	"github.com/ljalbinson/orphera-pkg/agent-go/internal/pb"
)

// ApplyFiles makes the changes it lists, in order, from a fixed set of actions
// and runs no script: a role that may change files and kernel settings does
// not need `run`. It stops at the first action that fails and says how far it
// got. A path the caller's role may not reach (the role's `paths`) refuses the
// whole call before anything is changed.
func (s *Service) ApplyFiles(ctx context.Context, req *pb.ApplyFilesRequest) (*pb.ApplyReply, error) {
	for _, a := range req.Actions {
		for _, p := range filePaths(a) {
			if err := pathDenied(ctx, p); err != nil {
				return nil, err
			}
		}
	}
	reply := &pb.ApplyReply{}
	for i, a := range req.Actions {
		if err := applyOneFile(a); err != nil {
			reply.Error = validUTF8(fmt.Sprintf("%s: %s", describeFileAction(a), err.Error()))
			return reply, nil
		}
		reply.Completed = int32(i + 1)
	}
	return reply, nil
}

// filePaths are the files an action touches, for the role's path limits.
func filePaths(a *pb.FileAction) []string {
	switch x := a.Action.(type) {
	case *pb.FileAction_MakeDirectory:
		return []string{x.MakeDirectory.Path}
	case *pb.FileAction_SetAttributes:
		return []string{x.SetAttributes.Path}
	case *pb.FileAction_RemovePath:
		return []string{x.RemovePath.Path}
	case *pb.FileAction_MakeSymlink:
		return []string{x.MakeSymlink.Path}
	case *pb.FileAction_ReplaceText:
		return []string{x.ReplaceText.Path}
	}
	return nil
}

func describeFileAction(a *pb.FileAction) string {
	switch x := a.Action.(type) {
	case *pb.FileAction_MakeDirectory:
		return "make directory " + x.MakeDirectory.Path
	case *pb.FileAction_SetAttributes:
		return "set attributes of " + x.SetAttributes.Path
	case *pb.FileAction_RemovePath:
		return "remove " + x.RemovePath.Path
	case *pb.FileAction_MakeSymlink:
		return "link " + x.MakeSymlink.Path
	case *pb.FileAction_SetSysctl:
		return "set sysctl " + x.SetSysctl.Key
	case *pb.FileAction_LoadModule:
		return "load module " + x.LoadModule.Name
	case *pb.FileAction_ReplaceText:
		return "write " + x.ReplaceText.Path
	}
	return "unknown action"
}

func applyOneFile(a *pb.FileAction) error {
	switch x := a.Action.(type) {
	case *pb.FileAction_MakeDirectory:
		return makeDirectory(x.MakeDirectory)
	case *pb.FileAction_SetAttributes:
		m := x.SetAttributes
		if err := needChangeablePath(m.Path); err != nil {
			return err
		}
		return setAttributes(m.Path, m.Mode, m.Owner, m.Group)
	case *pb.FileAction_RemovePath:
		return removePath(x.RemovePath)
	case *pb.FileAction_MakeSymlink:
		return makeSymlink(x.MakeSymlink)
	case *pb.FileAction_SetSysctl:
		return setSysctl(x.SetSysctl)
	case *pb.FileAction_LoadModule:
		if !validArg(x.LoadModule.Name) {
			return fmt.Errorf("not a valid module name: %q", x.LoadModule.Name)
		}
		return runFixed("modprobe", x.LoadModule.Name)
	case *pb.FileAction_ReplaceText:
		return replaceText(x.ReplaceText)
	}
	return fmt.Errorf("an action this agent does not know")
}

// needChangeablePath is an absolute path outside the agent's own directory.
func needChangeablePath(path string) error {
	if err := needAbsolute(path); err != nil {
		return err
	}
	if protectedRefusal(path) != "" {
		return fmt.Errorf("protected path (%s)", ProtectedDir)
	}
	return nil
}

// toFileMode is a mode as chmod writes it (setuid, setgid and sticky included)
// as a Go file mode.
func toFileMode(m uint32) os.FileMode {
	fm := os.FileMode(m & 0o777)
	if m&0o4000 != 0 {
		fm |= os.ModeSetuid
	}
	if m&0o2000 != 0 {
		fm |= os.ModeSetgid
	}
	if m&0o1000 != 0 {
		fm |= os.ModeSticky
	}
	return fm
}

// parseMode is an octal mode text; "" is no mode.
func parseMode(s string) (os.FileMode, bool, error) {
	if s == "" {
		return 0, false, nil
	}
	n, err := strconv.ParseUint(s, 8, 32)
	if err != nil || n > 0o7777 {
		return 0, false, fmt.Errorf("not an octal mode: %q", s)
	}
	return toFileMode(uint32(n)), true, nil
}

// setAttributes is chown and chmod: an empty owner, group or mode is left
// alone. The owner and group first, since chown clears setuid and setgid.
func setAttributes(path, mode, owner, group string) error {
	fm, haveMode, err := parseMode(mode)
	if err != nil {
		return err
	}
	uid, gid := -1, -1
	if owner != "" {
		if uid, err = lookupUID(owner); err != nil {
			return err
		}
	}
	if group != "" {
		if gid, err = lookupGID(group); err != nil {
			return err
		}
	}
	if uid >= 0 || gid >= 0 {
		if err := os.Chown(path, uid, gid); err != nil {
			return err
		}
	}
	if haveMode {
		if err := os.Chmod(path, fm); err != nil {
			return err
		}
	}
	return nil
}

func makeDirectory(m *pb.MakeDirectory) error {
	if err := needChangeablePath(m.Path); err != nil {
		return err
	}
	if _, _, err := parseMode(m.Mode); err != nil {
		return err
	}
	if err := os.MkdirAll(m.Path, 0o755); err != nil {
		return err
	}
	return setAttributes(m.Path, m.Mode, m.Owner, m.Group)
}

// removePath removes a file or link, or an empty directory. Something already
// gone is not an error.
func removePath(r *pb.RemovePath) error {
	if err := needChangeablePath(r.Path); err != nil {
		return err
	}
	fi, err := os.Lstat(r.Path)
	if os.IsNotExist(err) {
		return nil
	}
	if err != nil {
		return err
	}
	if r.Directory {
		if !fi.IsDir() {
			return fmt.Errorf("%s is not a directory", r.Path)
		}
	} else if fi.IsDir() {
		return fmt.Errorf("%s is a directory: not removed", r.Path)
	}
	return os.Remove(r.Path)
}

// makeSymlink makes path a link to target, at once: the new link is made
// beside it and renamed over it.
func makeSymlink(l *pb.MakeSymlink) error {
	if err := needChangeablePath(l.Path); err != nil {
		return err
	}
	if l.Target == "" || strings.ContainsRune(l.Target, 0) {
		return fmt.Errorf("not a link target: %q", l.Target)
	}
	if fi, err := os.Lstat(l.Path); err == nil && fi.IsDir() {
		return fmt.Errorf("%s is a directory: not replaced by a link", l.Path)
	}
	tmp := filepath.Join(filepath.Dir(l.Path), fmt.Sprintf(".%s.orphera-%d", filepath.Base(l.Path), os.Getpid()))
	_ = os.Remove(tmp)
	if err := os.Symlink(l.Target, tmp); err != nil {
		return err
	}
	if err := os.Rename(tmp, l.Path); err != nil {
		_ = os.Remove(tmp)
		return err
	}
	return nil
}

func setSysctl(s *pb.SetSysctl) error {
	if !validArg(s.Key) {
		return fmt.Errorf("not a valid sysctl key: %q", s.Key)
	}
	if strings.ContainsAny(s.Value, "\n\x00") || strings.TrimSpace(s.Value) == "" {
		return fmt.Errorf("not a valid sysctl value: %q", s.Value)
	}
	return runFixed("sysctl", "-w", s.Key+"="+s.Value)
}

// runFixed runs a fixed program (never a shell) and says what it printed when
// it fails.
func runFixed(name string, args ...string) error {
	ctx, cancel := context.WithTimeout(context.Background(), 60*time.Second)
	defer cancel()
	var out bytes.Buffer
	cmd := exec.CommandContext(ctx, name, args...)
	cmd.Stdout = &out
	cmd.Stderr = &out
	if err := cmd.Run(); err != nil {
		said := strings.TrimSpace(out.String())
		if said == "" {
			return fmt.Errorf("%s: %v", name, err)
		}
		return fmt.Errorf("%s: %s", name, said)
	}
	return nil
}

const maxReplaceText = 4 * 1024 * 1024

// replaceText writes a file's new text through a temporary file beside it,
// with the old mode and owner, and renames it into place.
func replaceText(r *pb.ReplaceText) error {
	if err := needChangeablePath(r.Path); err != nil {
		return err
	}
	if len(r.Content) > maxReplaceText {
		return fmt.Errorf("text is larger than %d bytes", maxReplaceText)
	}
	mode := os.FileMode(0o644)
	uid, gid := -1, -1
	fi, err := os.Lstat(r.Path)
	switch {
	case os.IsNotExist(err):
		if r.ExpectedSha256 != "" {
			return fmt.Errorf("the file is gone (expected one with hash %s)", r.ExpectedSha256)
		}
		fm, have, perr := parseMode(r.CreateMode)
		if perr != nil {
			return perr
		}
		if have {
			mode = fm
		}
	case err != nil:
		return err
	default:
		if !fi.Mode().IsRegular() {
			return fmt.Errorf("%s is not a regular file", r.Path)
		}
		if r.ExpectedSha256 == "" && !r.Overwrite {
			return fmt.Errorf("the file exists now (expected it not to)")
		}
		if r.ExpectedSha256 != "" {
			sum, err := sha256File(r.Path)
			if err != nil {
				return err
			}
			if sum != r.ExpectedSha256 {
				return fmt.Errorf("the file has changed since it was read (hash %s, expected %s): not edited", sum, r.ExpectedSha256)
			}
		}
		mode = fi.Mode()
		if st, ok := fi.Sys().(*syscall.Stat_t); ok {
			uid, gid = int(st.Uid), int(st.Gid)
		}
	}
	tmp, err := os.CreateTemp(filepath.Dir(r.Path), ".orphera-edit-*")
	if err != nil {
		return err
	}
	name := tmp.Name()
	cleanup := func() { _ = os.Remove(name) }
	if _, err := tmp.WriteString(r.Content); err != nil {
		tmp.Close()
		cleanup()
		return err
	}
	if err := tmp.Sync(); err != nil {
		tmp.Close()
		cleanup()
		return err
	}
	if err := tmp.Close(); err != nil {
		cleanup()
		return err
	}
	if uid >= 0 || gid >= 0 {
		if err := os.Chown(name, uid, gid); err != nil {
			cleanup()
			return err
		}
	}
	if err := os.Chmod(name, mode); err != nil {
		cleanup()
		return err
	}
	if err := os.Rename(name, r.Path); err != nil {
		cleanup()
		return err
	}
	return nil
}

// textHash is the hash a ReplaceText names, for tests and callers.
func textHash(s string) string {
	sum := sha256.Sum256([]byte(s))
	return hex.EncodeToString(sum[:])
}
