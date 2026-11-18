// SPDX-License-Identifier: Apache-2.0

package agent

import (
	"context"
	"os"
	"os/user"
	"path/filepath"
	"strings"
	"testing"

	"github.com/ljalbinson/orphera-pkg/agent-go/internal/pb"
)

func addKeyA(user, key string) *pb.AccessAction {
	return &pb.AccessAction{Action: &pb.AccessAction_AddAuthorizedKey{AddAuthorizedKey: &pb.AddAuthorizedKey{User: user, Key: key}}}
}

func rmKeyA(user, id string) *pb.AccessAction {
	return &pb.AccessAction{Action: &pb.AccessAction_RemoveAuthorizedKey{RemoveAuthorizedKey: &pb.RemoveAuthorizedKey{User: user, KeyId: id}}}
}

func fstabA(content, sha string) *pb.AccessAction {
	return &pb.AccessAction{Action: &pb.AccessAction_ReplaceFstab{ReplaceFstab: &pb.ReplaceFstab{Content: content, ExpectedSha256: sha}}}
}

func applyAccess(t *testing.T, actions ...*pb.AccessAction) *pb.ApplyReply {
	t.Helper()
	reply, err := NewService().ApplyAccess(context.Background(), &pb.ApplyAccessRequest{Actions: actions})
	if err != nil {
		t.Fatal(err)
	}
	return reply
}

// A user "tester" whose home is a temporary directory and whose ids are the
// test's own, so that chown works without root.
func fakeUser(t *testing.T) (home string) {
	t.Helper()
	me, err := user.Current()
	if err != nil {
		t.Fatal(err)
	}
	home = t.TempDir()
	old := passwdLookup
	passwdLookup = func(name string) ([]string, bool) {
		if name != "tester" {
			return nil, false
		}
		return []string{"tester", "x", me.Uid, me.Gid, "", home, "/bin/sh"}, true
	}
	t.Cleanup(func() { passwdLookup = old })
	return home
}

const keyA = "ssh-ed25519 AAAAAAAA first"
const keyB = "ssh-rsa BBBBBBBB second"

func TestApplyAuthorizedKeys(t *testing.T) {
	home := fakeUser(t)
	file := filepath.Join(home, ".ssh", "authorized_keys")
	if r := applyAccess(t, addKeyA("tester", keyA)); r.Error != "" || r.Completed != 1 {
		t.Fatalf("add: %+v", r)
	}
	got, _ := os.ReadFile(file)
	if string(got) != keyA+"\n" {
		t.Errorf("keys %q", got)
	}
	if fi, _ := os.Stat(file); fi.Mode().Perm() != 0o600 {
		t.Errorf("keys mode %v", fi.Mode())
	}
	if fi, _ := os.Stat(filepath.Join(home, ".ssh")); fi.Mode().Perm() != 0o700 {
		t.Errorf(".ssh mode %v", fi.Mode())
	}
	// A file with no newline at the end gets one before the new key.
	os.WriteFile(file, []byte(keyA), 0o600)
	if r := applyAccess(t, addKeyA("tester", keyB)); r.Error != "" {
		t.Fatalf("add second: %+v", r)
	}
	got, _ = os.ReadFile(file)
	if string(got) != keyA+"\n"+keyB+"\n" {
		t.Errorf("keys %q", got)
	}
	// Removing one leaves the other.
	if r := applyAccess(t, rmKeyA("tester", "ssh-ed25519 AAAAAAAA")); r.Error != "" {
		t.Fatalf("remove: %+v", r)
	}
	got, _ = os.ReadFile(file)
	if string(got) != keyB+"\n" {
		t.Errorf("keys after remove %q", got)
	}
	if r := applyAccess(t, rmKeyA("tester", "ssh-rsa BBBBBBBB")); r.Error != "" {
		t.Fatalf("remove last: %+v", r)
	}
	if got, _ := os.ReadFile(file); strings.TrimSpace(string(got)) != "" {
		t.Errorf("the file should be empty: %q", got)
	}
	// Removing from a user with no keys file is not an error.
	os.Remove(file)
	if r := applyAccess(t, rmKeyA("tester", "ssh-rsa BBBBBBBB")); r.Error != "" {
		t.Errorf("remove with no file: %+v", r)
	}
	left, _ := filepath.Glob(filepath.Join(home, ".ssh", ".orphera-keys-*"))
	if len(left) != 0 {
		t.Errorf("temporary files left: %v", left)
	}
}

func TestApplyKeyRefusals(t *testing.T) {
	fakeUser(t)
	for _, a := range []*pb.AccessAction{
		addKeyA("nobody-here", keyA),
		addKeyA("tester", "justonefield"),
		addKeyA("tester", keyA+"\nssh-rsa EXTRA x"),
		addKeyA("-x", keyA),
		rmKeyA("nobody-here", "ssh-rsa A"),
		{},
	} {
		if r := applyAccess(t, a); r.Error == "" {
			t.Errorf("should be refused: %v", a)
		}
	}
}

func TestApplyFstab(t *testing.T) {
	dir := t.TempDir()
	old := fstabPath
	defer func() { fstabPath = old }()
	fstabPath = filepath.Join(dir, "fstab")
	os.WriteFile(fstabPath, []byte("a /a ext4 defaults 0 0\n"), 0o644)
	r := applyAccess(t, fstabA("a /a ext4 defaults 0 0\ntmpfs /m tmpfs size=8m 0 0\n", textHash("a /a ext4 defaults 0 0\n")))
	if r.Error != "" {
		t.Fatalf("edit: %+v", r)
	}
	if got, _ := os.ReadFile(fstabPath); !strings.Contains(string(got), "tmpfs /m") {
		t.Errorf("fstab %q", got)
	}
	r = applyAccess(t, fstabA("x\n", textHash("stale")))
	if r.Error == "" || !strings.Contains(r.Error, "changed since") {
		t.Errorf("a stale fstab edit: %+v", r)
	}
}

func TestApplyAccessRefusals(t *testing.T) {
	for _, a := range []*pb.AccessAction{
		{Action: &pb.AccessAction_CreateUser{CreateUser: &pb.CreateUser{Name: "-r"}}},
		{Action: &pb.AccessAction_CreateUser{CreateUser: &pb.CreateUser{Name: "u", Uid: "abc"}}},
		{Action: &pb.AccessAction_CreateUser{CreateUser: &pb.CreateUser{Name: "u", Home: "relative"}}},
		{Action: &pb.AccessAction_CreateUser{CreateUser: &pb.CreateUser{Name: "u", Shell: "sh"}}},
		{Action: &pb.AccessAction_CreateUser{CreateUser: &pb.CreateUser{Name: "u", Groups: []string{"a,b"}}}},
		{Action: &pb.AccessAction_ModifyUser{ModifyUser: &pb.ModifyUser{Name: "u"}}},
		{Action: &pb.AccessAction_DeleteUser{DeleteUser: &pb.DeleteUser{Name: "--help"}}},
		{Action: &pb.AccessAction_CreateGroup{CreateGroup: &pb.CreateGroup{Name: "g", Gid: "-1"}}},
		{Action: &pb.AccessAction_ModifyGroup{ModifyGroup: &pb.ModifyGroup{Name: "g", Gid: "x"}}},
		{Action: &pb.AccessAction_DeleteGroup{DeleteGroup: &pb.DeleteGroup{Name: ""}}},
		{Action: &pb.AccessAction_MountPath{MountPath: &pb.MountPath{Path: "relative"}}},
		{Action: &pb.AccessAction_Remount{Remount: &pb.Remount{Path: "relative"}}},
		{Action: &pb.AccessAction_Unmount{Unmount: &pb.Unmount{Path: "relative"}}},
	} {
		if r := applyAccess(t, a); r.Error == "" {
			t.Errorf("should be refused: %v", a)
		}
	}
}

func TestApplyAccessStopsAtFirstFailure(t *testing.T) {
	home := fakeUser(t)
	r := applyAccess(t, addKeyA("tester", keyA), addKeyA("nobody-here", keyB), addKeyA("tester", keyB))
	if r.Error == "" || r.Completed != 1 {
		t.Fatalf("reply: %+v", r)
	}
	got, _ := os.ReadFile(filepath.Join(home, ".ssh", "authorized_keys"))
	if strings.Contains(string(got), "second") {
		t.Errorf("the action after the failure ran: %q", got)
	}
}

func TestApplyAccessOperationAndPaths(t *testing.T) {
	if got := operationOf("ApplyAccess"); got != "apply-access" {
		t.Errorf("ApplyAccess is in operation %q, want apply-access", got)
	}
	home := fakeUser(t)
	p := accessPaths(addKeyA("tester", keyA))
	if len(p) != 1 || p[0] != filepath.Join(home, ".ssh", "authorized_keys") {
		t.Errorf("key paths %v", p)
	}
	if p := accessPaths(fstabA("x", "")); len(p) != 1 || p[0] != fstabPath {
		t.Errorf("fstab paths %v", p)
	}
	u := &pb.AccessAction{Action: &pb.AccessAction_DeleteUser{DeleteUser: &pb.DeleteUser{Name: "u"}}}
	if p := accessPaths(u); len(p) != 0 {
		t.Errorf("a user has no path: %v", p)
	}
}
