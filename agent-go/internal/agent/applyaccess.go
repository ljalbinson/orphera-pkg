// SPDX-License-Identifier: Apache-2.0

package agent

import (
	"context"
	"fmt"
	"os"
	"path/filepath"
	"strconv"
	"strings"
	"syscall"

	"github.com/ljalbinson/orphera-pkg/agent-go/internal/pb"
)

// ApplyAccess makes the changes it lists, in order, to users, groups,
// authorized keys and mounts, from a fixed set of actions, and runs no script.
// These give or take away ways to log in and change what is mounted, so they
// are a different call (and policy operation) from ApplyFiles. It stops at the
// first action that fails and says how far it got. A path the caller's role may
// not reach (the role's `paths`) refuses the whole call before anything is
// changed.
func (s *Service) ApplyAccess(ctx context.Context, req *pb.ApplyAccessRequest) (*pb.ApplyReply, error) {
	for _, a := range req.Actions {
		for _, p := range accessPaths(a) {
			if err := pathDenied(ctx, p); err != nil {
				return nil, err
			}
		}
	}
	reply := &pb.ApplyReply{}
	for i, a := range req.Actions {
		if err := applyOneAccess(a); err != nil {
			reply.Error = validUTF8(fmt.Sprintf("%s: %s", describeAccessAction(a), err.Error()))
			return reply, nil
		}
		reply.Completed = int32(i + 1)
	}
	return reply, nil
}

// authorizedKeysPath is where a user's keys are, "" if the user is not found.
func authorizedKeysPath(user string) string {
	if !validArg(user) {
		return ""
	}
	f, ok := passwdLookup(user)
	if !ok {
		return ""
	}
	return filepath.Join(f[5], ".ssh", "authorized_keys")
}

// accessPaths are the files an action touches, for the role's path limits.
func accessPaths(a *pb.AccessAction) []string {
	switch x := a.Action.(type) {
	case *pb.AccessAction_AddAuthorizedKey:
		if p := authorizedKeysPath(x.AddAuthorizedKey.User); p != "" {
			return []string{p}
		}
	case *pb.AccessAction_RemoveAuthorizedKey:
		if p := authorizedKeysPath(x.RemoveAuthorizedKey.User); p != "" {
			return []string{p}
		}
	case *pb.AccessAction_ReplaceFstab:
		return []string{fstabPath}
	case *pb.AccessAction_MountPath:
		return []string{x.MountPath.Path}
	case *pb.AccessAction_Remount:
		return []string{x.Remount.Path}
	case *pb.AccessAction_Unmount:
		return []string{x.Unmount.Path}
	}
	return nil
}

func describeAccessAction(a *pb.AccessAction) string {
	switch x := a.Action.(type) {
	case *pb.AccessAction_CreateUser:
		return "create user " + x.CreateUser.Name
	case *pb.AccessAction_ModifyUser:
		return "change user " + x.ModifyUser.Name
	case *pb.AccessAction_DeleteUser:
		return "delete user " + x.DeleteUser.Name
	case *pb.AccessAction_CreateGroup:
		return "create group " + x.CreateGroup.Name
	case *pb.AccessAction_ModifyGroup:
		return "change group " + x.ModifyGroup.Name
	case *pb.AccessAction_DeleteGroup:
		return "delete group " + x.DeleteGroup.Name
	case *pb.AccessAction_AddAuthorizedKey:
		return "add a key for " + x.AddAuthorizedKey.User
	case *pb.AccessAction_RemoveAuthorizedKey:
		return "remove a key of " + x.RemoveAuthorizedKey.User
	case *pb.AccessAction_ReplaceFstab:
		return "write " + fstabPath
	case *pb.AccessAction_MountPath:
		return "mount " + x.MountPath.Path
	case *pb.AccessAction_Remount:
		return "remount " + x.Remount.Path
	case *pb.AccessAction_Unmount:
		return "unmount " + x.Unmount.Path
	}
	return "unknown action"
}

func applyOneAccess(a *pb.AccessAction) error {
	switch x := a.Action.(type) {
	case *pb.AccessAction_CreateUser:
		return createUser(x.CreateUser)
	case *pb.AccessAction_ModifyUser:
		return modifyUser(x.ModifyUser)
	case *pb.AccessAction_DeleteUser:
		if !validArg(x.DeleteUser.Name) {
			return fmt.Errorf("not a valid user name: %q", x.DeleteUser.Name)
		}
		return runFixed("userdel", x.DeleteUser.Name)
	case *pb.AccessAction_CreateGroup:
		return createGroup(x.CreateGroup)
	case *pb.AccessAction_ModifyGroup:
		g := x.ModifyGroup
		if !validArg(g.Name) || !isNumber(g.Gid) {
			return fmt.Errorf("not a valid group or gid: %q %q", g.Name, g.Gid)
		}
		return runFixed("groupmod", "-g", g.Gid, g.Name)
	case *pb.AccessAction_DeleteGroup:
		if !validArg(x.DeleteGroup.Name) {
			return fmt.Errorf("not a valid group name: %q", x.DeleteGroup.Name)
		}
		return runFixed("groupdel", x.DeleteGroup.Name)
	case *pb.AccessAction_AddAuthorizedKey:
		return addAuthorizedKey(x.AddAuthorizedKey)
	case *pb.AccessAction_RemoveAuthorizedKey:
		return removeAuthorizedKey(x.RemoveAuthorizedKey)
	case *pb.AccessAction_ReplaceFstab:
		return replaceText(&pb.ReplaceText{
			Path:           fstabPath,
			Content:        x.ReplaceFstab.Content,
			ExpectedSha256: x.ReplaceFstab.ExpectedSha256,
			CreateMode:     "0644",
		})
	case *pb.AccessAction_MountPath:
		return mountPath(x.MountPath)
	case *pb.AccessAction_Remount:
		if err := needAbsolute(x.Remount.Path); err != nil {
			return err
		}
		return runFixed("mount", "-o", "remount", x.Remount.Path)
	case *pb.AccessAction_Unmount:
		if err := needAbsolute(x.Unmount.Path); err != nil {
			return err
		}
		return runFixed("umount", x.Unmount.Path)
	}
	return fmt.Errorf("an action this agent does not know")
}

func isNumber(s string) bool {
	_, err := strconv.ParseUint(s, 10, 32)
	return err == nil
}

// validPathArg is an absolute path that is also safe as one program argument.
func validPathArg(s string) bool {
	return filepath.IsAbs(s) && validArg(s)
}

func createUser(u *pb.CreateUser) error {
	if !validArg(u.Name) {
		return fmt.Errorf("not a valid user name: %q", u.Name)
	}
	args := []string{}
	if u.Uid != "" {
		if !isNumber(u.Uid) {
			return fmt.Errorf("not a uid: %q", u.Uid)
		}
		args = append(args, "-u", u.Uid)
	}
	if u.Group != "" {
		if !validArg(u.Group) {
			return fmt.Errorf("not a valid group: %q", u.Group)
		}
		args = append(args, "-g", u.Group)
	}
	if len(u.Groups) > 0 {
		for _, g := range u.Groups {
			if !validArg(g) || strings.Contains(g, ",") {
				return fmt.Errorf("not a valid group: %q", g)
			}
		}
		args = append(args, "-G", strings.Join(u.Groups, ","))
	}
	if u.Home != "" {
		if !validPathArg(u.Home) {
			return fmt.Errorf("not a valid home directory: %q", u.Home)
		}
		args = append(args, "-d", u.Home)
	}
	if u.CreateHome {
		args = append(args, "-m")
	}
	if u.Shell != "" {
		if !validPathArg(u.Shell) {
			return fmt.Errorf("not a valid shell: %q", u.Shell)
		}
		args = append(args, "-s", u.Shell)
	}
	if u.System {
		args = append(args, "-r")
	}
	return runFixed("useradd", append(args, u.Name)...)
}

func modifyUser(u *pb.ModifyUser) error {
	if !validArg(u.Name) {
		return fmt.Errorf("not a valid user name: %q", u.Name)
	}
	args := []string{}
	if u.Uid != "" {
		if !isNumber(u.Uid) {
			return fmt.Errorf("not a uid: %q", u.Uid)
		}
		args = append(args, "-u", u.Uid)
	}
	if u.Group != "" {
		if !validArg(u.Group) {
			return fmt.Errorf("not a valid group: %q", u.Group)
		}
		args = append(args, "-g", u.Group)
	}
	if len(u.AddGroups) > 0 {
		for _, g := range u.AddGroups {
			if !validArg(g) || strings.Contains(g, ",") {
				return fmt.Errorf("not a valid group: %q", g)
			}
		}
		args = append(args, "-a", "-G", strings.Join(u.AddGroups, ","))
	}
	if u.Home != "" {
		if !validPathArg(u.Home) {
			return fmt.Errorf("not a valid home directory: %q", u.Home)
		}
		args = append(args, "-d", u.Home)
	}
	if u.Shell != "" {
		if !validPathArg(u.Shell) {
			return fmt.Errorf("not a valid shell: %q", u.Shell)
		}
		args = append(args, "-s", u.Shell)
	}
	if len(args) == 0 {
		return fmt.Errorf("nothing to change")
	}
	return runFixed("usermod", append(args, u.Name)...)
}

func createGroup(g *pb.CreateGroup) error {
	if !validArg(g.Name) {
		return fmt.Errorf("not a valid group name: %q", g.Name)
	}
	args := []string{}
	if g.Gid != "" {
		if !isNumber(g.Gid) {
			return fmt.Errorf("not a gid: %q", g.Gid)
		}
		args = append(args, "-g", g.Gid)
	}
	if g.System {
		args = append(args, "-r")
	}
	return runFixed("groupadd", append(args, g.Name)...)
}

func mountPath(m *pb.MountPath) error {
	if err := needAbsolute(m.Path); err != nil {
		return err
	}
	if m.MakeDirectory {
		if err := os.MkdirAll(m.Path, 0o755); err != nil {
			return err
		}
	}
	return runFixed("mount", m.Path)
}

// keyOwner is the user's home .ssh directory and keys file, and the user's ids.
func keyOwner(user string) (dir, file string, uid, gid int, err error) {
	if !validArg(user) {
		return "", "", 0, 0, fmt.Errorf("not a valid user name: %q", user)
	}
	f, ok := passwdLookup(user)
	if !ok {
		return "", "", 0, 0, fmt.Errorf("user %s does not exist", user)
	}
	if f[5] == "" || !filepath.IsAbs(f[5]) {
		return "", "", 0, 0, fmt.Errorf("user %s has no home directory", user)
	}
	uid, err = strconv.Atoi(f[2])
	if err != nil {
		return "", "", 0, 0, err
	}
	gid, err = strconv.Atoi(f[3])
	if err != nil {
		return "", "", 0, 0, err
	}
	dir = filepath.Join(f[5], ".ssh")
	return dir, filepath.Join(dir, "authorized_keys"), uid, gid, nil
}

// writeKeys writes the keys file through a temporary file beside it, mode 0600
// and owned by the user.
func writeKeys(dir, file, text string, uid, gid int) error {
	tmp, err := os.CreateTemp(dir, ".orphera-keys-*")
	if err != nil {
		return err
	}
	name := tmp.Name()
	fail := func(err error) error {
		tmp.Close()
		_ = os.Remove(name)
		return err
	}
	if _, err := tmp.WriteString(text); err != nil {
		return fail(err)
	}
	if err := tmp.Sync(); err != nil {
		return fail(err)
	}
	if err := tmp.Close(); err != nil {
		_ = os.Remove(name)
		return err
	}
	if err := os.Chown(name, uid, gid); err != nil {
		_ = os.Remove(name)
		return err
	}
	if err := os.Chmod(name, 0o600); err != nil {
		_ = os.Remove(name)
		return err
	}
	if err := os.Rename(name, file); err != nil {
		_ = os.Remove(name)
		return err
	}
	return nil
}

func addAuthorizedKey(k *pb.AddAuthorizedKey) error {
	dir, file, uid, gid, err := keyOwner(k.User)
	if err != nil {
		return err
	}
	line := strings.TrimSpace(k.Key)
	if line == "" || strings.ContainsAny(line, "\n\x00") || len(strings.Fields(line)) < 2 {
		return fmt.Errorf("not a public key line")
	}
	if err := os.MkdirAll(dir, 0o700); err != nil {
		return err
	}
	if err := os.Chown(dir, uid, gid); err != nil {
		return err
	}
	if err := os.Chmod(dir, 0o700); err != nil {
		return err
	}
	old := ""
	if data, err := os.ReadFile(file); err == nil {
		old = string(data)
	} else if !os.IsNotExist(err) {
		return err
	}
	if old != "" && !strings.HasSuffix(old, "\n") {
		old += "\n"
	}
	return writeKeys(dir, file, old+line+"\n", uid, gid)
}

func removeAuthorizedKey(k *pb.RemoveAuthorizedKey) error {
	dir, file, uid, gid, err := keyOwner(k.User)
	if err != nil {
		return err
	}
	data, err := os.ReadFile(file)
	if os.IsNotExist(err) {
		return nil
	}
	if err != nil {
		return err
	}
	var kept []string
	for _, line := range strings.Split(string(data), "\n") {
		f := strings.Fields(line)
		if len(f) >= 2 && f[0]+" "+f[1] == k.KeyId {
			continue
		}
		kept = append(kept, line)
	}
	text := strings.Join(kept, "\n")
	// Splitting a text that ends in a newline leaves an empty last element, which
	// Join turns back into that newline.
	if st, err := os.Stat(file); err == nil {
		if s, ok := st.Sys().(*syscall.Stat_t); ok {
			uid, gid = int(s.Uid), int(s.Gid)
		}
	}
	return writeKeys(dir, file, text, uid, gid)
}
