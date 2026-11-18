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

func mkdirA(path, mode, owner, group string) *pb.FileAction {
	return &pb.FileAction{Action: &pb.FileAction_MakeDirectory{MakeDirectory: &pb.MakeDirectory{Path: path, Mode: mode, Owner: owner, Group: group}}}
}

func attrsA(path, mode string) *pb.FileAction {
	return &pb.FileAction{Action: &pb.FileAction_SetAttributes{SetAttributes: &pb.SetAttributes{Path: path, Mode: mode}}}
}

func rmA(path string, dir bool) *pb.FileAction {
	return &pb.FileAction{Action: &pb.FileAction_RemovePath{RemovePath: &pb.RemovePath{Path: path, Directory: dir}}}
}

func linkA(path, target string) *pb.FileAction {
	return &pb.FileAction{Action: &pb.FileAction_MakeSymlink{MakeSymlink: &pb.MakeSymlink{Path: path, Target: target}}}
}

func textA(path, content, sha, mode string) *pb.FileAction {
	return &pb.FileAction{Action: &pb.FileAction_ReplaceText{ReplaceText: &pb.ReplaceText{Path: path, Content: content, ExpectedSha256: sha, CreateMode: mode}}}
}

func overwriteA(path, content string) *pb.FileAction {
	return &pb.FileAction{Action: &pb.FileAction_ReplaceText{ReplaceText: &pb.ReplaceText{Path: path, Content: content, Overwrite: true}}}
}

func applyFiles(t *testing.T, actions ...*pb.FileAction) *pb.ApplyReply {
	t.Helper()
	reply, err := NewService().ApplyFiles(context.Background(), &pb.ApplyFilesRequest{Actions: actions})
	if err != nil {
		t.Fatal(err)
	}
	return reply
}

func TestApplyDirectoryAndAttributes(t *testing.T) {
	dir := t.TempDir()
	me, err := user.Current()
	if err != nil {
		t.Fatal(err)
	}
	d := filepath.Join(dir, "a", "b")
	r := applyFiles(t, mkdirA(d, "0750", me.Username, ""))
	if r.Error != "" || r.Completed != 1 {
		t.Fatalf("make: %+v", r)
	}
	fi, err := os.Stat(d)
	if err != nil || !fi.IsDir() || fi.Mode().Perm() != 0o750 {
		t.Fatalf("directory: %v %v", fi, err)
	}
	r = applyFiles(t, attrsA(d, "0700"))
	if r.Error != "" {
		t.Fatalf("attrs: %+v", r)
	}
	if fi, _ := os.Stat(d); fi.Mode().Perm() != 0o700 {
		t.Errorf("mode %v", fi.Mode())
	}
	if r := applyFiles(t, attrsA(d, "9999")); r.Error == "" {
		t.Error("a bad mode should fail")
	}
	if r := applyFiles(t, rmA(filepath.Join(dir, "a"), true)); r.Error == "" {
		t.Error("a directory with something in it should not be removed")
	}
	if r := applyFiles(t, rmA(d, true), rmA(filepath.Join(dir, "a"), true)); r.Error != "" || r.Completed != 2 {
		t.Errorf("remove empty directories: %+v", r)
	}
	if r := applyFiles(t, rmA(d, true)); r.Error != "" {
		t.Errorf("removing what is gone is not an error: %+v", r)
	}
}

func TestApplyRemoveRefusesTrees(t *testing.T) {
	dir := t.TempDir()
	f := filepath.Join(dir, "f")
	os.WriteFile(f, []byte("x"), 0o644)
	if r := applyFiles(t, rmA(dir, false)); r.Error == "" {
		t.Error("a directory is not removed as a file")
	}
	if r := applyFiles(t, rmA(f, true)); r.Error == "" {
		t.Error("a file is not removed as a directory")
	}
	if r := applyFiles(t, rmA(f, false)); r.Error != "" {
		t.Errorf("remove a file: %+v", r)
	}
	if _, err := os.Lstat(f); !os.IsNotExist(err) {
		t.Error("the file should be gone")
	}
}

func TestApplySymlink(t *testing.T) {
	dir := t.TempDir()
	link := filepath.Join(dir, "current")
	if r := applyFiles(t, linkA(link, "one")); r.Error != "" {
		t.Fatalf("make: %+v", r)
	}
	if got, _ := os.Readlink(link); got != "one" {
		t.Errorf("target %q", got)
	}
	if r := applyFiles(t, linkA(link, "two")); r.Error != "" {
		t.Fatalf("change: %+v", r)
	}
	if got, _ := os.Readlink(link); got != "two" {
		t.Errorf("target %q", got)
	}
	file := filepath.Join(dir, "file")
	os.WriteFile(file, []byte("x"), 0o644)
	if r := applyFiles(t, linkA(file, "elsewhere")); r.Error != "" {
		t.Errorf("a file is replaced by a link: %+v", r)
	}
	if got, _ := os.Readlink(file); got != "elsewhere" {
		t.Errorf("target %q", got)
	}
	if r := applyFiles(t, linkA(dir, "x")); r.Error == "" {
		t.Error("a directory is not replaced by a link")
	}
	left, _ := filepath.Glob(filepath.Join(dir, ".*orphera*"))
	if len(left) != 0 {
		t.Errorf("temporary links left: %v", left)
	}
}

func TestApplyReplaceTextOverwrite(t *testing.T) {
	dir := t.TempDir()
	f := filepath.Join(dir, "sysctl.conf")
	if r := applyFiles(t, overwriteA(f, "a = 1\n")); r.Error != "" {
		t.Fatalf("overwrite of a missing file creates it: %+v", r)
	}
	os.Chmod(f, 0o600)
	if r := applyFiles(t, overwriteA(f, "a = 2\n")); r.Error != "" {
		t.Fatalf("overwrite: %+v", r)
	}
	if got, _ := os.ReadFile(f); string(got) != "a = 2\n" {
		t.Errorf("content %q", got)
	}
	if fi, _ := os.Stat(f); fi.Mode().Perm() != 0o600 {
		t.Errorf("mode %v, want 0600 kept", fi.Mode())
	}
	if r := applyFiles(t, overwriteA(dir, "x")); r.Error == "" {
		t.Error("a directory is not overwritten")
	}
}

func TestApplyReplaceText(t *testing.T) {
	dir := t.TempDir()
	f := filepath.Join(dir, "conf")
	// Creating: the file must not exist; the mode is the one asked for.
	if r := applyFiles(t, textA(f, "a = 1\n", "", "0640")); r.Error != "" {
		t.Fatalf("create: %+v", r)
	}
	fi, _ := os.Stat(f)
	if fi.Mode().Perm() != 0o640 {
		t.Errorf("created mode %v", fi.Mode())
	}
	if r := applyFiles(t, textA(f, "other\n", "", "")); r.Error == "" {
		t.Error("creating over an existing file should fail")
	}
	// Editing: the hash must match, the mode is kept.
	os.Chmod(f, 0o600)
	if r := applyFiles(t, textA(f, "a = 2\n", textHash("a = 1\n"), "")); r.Error != "" {
		t.Fatalf("edit: %+v", r)
	}
	got, _ := os.ReadFile(f)
	if string(got) != "a = 2\n" {
		t.Errorf("content %q", got)
	}
	if fi, _ := os.Stat(f); fi.Mode().Perm() != 0o600 {
		t.Errorf("edited mode %v, want 0600 kept", fi.Mode())
	}
	// A file that changed since it was read is not overwritten.
	r := applyFiles(t, textA(f, "a = 3\n", textHash("stale"), ""))
	if r.Error == "" || !strings.Contains(r.Error, "changed since") {
		t.Errorf("stale edit: %+v", r)
	}
	if got, _ := os.ReadFile(f); string(got) != "a = 2\n" {
		t.Errorf("the file was changed: %q", got)
	}
	// An edit of a file that is gone.
	if r := applyFiles(t, textA(filepath.Join(dir, "gone"), "x", textHash("x"), "")); r.Error == "" {
		t.Error("editing a missing file should fail")
	}
	// No temporary files are left.
	left, _ := filepath.Glob(filepath.Join(dir, ".orphera-edit-*"))
	if len(left) != 0 {
		t.Errorf("temporary files left: %v", left)
	}
	if r := applyFiles(t, textA(dir, "x", "", "")); r.Error == "" {
		t.Error("a directory is not a text file")
	}
}

func TestApplyStopsAtFirstFailure(t *testing.T) {
	dir := t.TempDir()
	a := filepath.Join(dir, "a")
	c := filepath.Join(dir, "c")
	r := applyFiles(t, mkdirA(a, "", "", ""), attrsA(filepath.Join(dir, "none"), "0755"), mkdirA(c, "", "", ""))
	if r.Error == "" || r.Completed != 1 {
		t.Fatalf("reply: %+v", r)
	}
	if !strings.Contains(r.Error, "set attributes of") {
		t.Errorf("the failing action should be named: %q", r.Error)
	}
	if _, err := os.Stat(c); err == nil {
		t.Error("the action after the failure ran")
	}
}

func TestApplyRefusals(t *testing.T) {
	for _, a := range []*pb.FileAction{
		mkdirA("relative/dir", "", "", ""),
		mkdirA(ProtectedDir+"/x", "", "", ""),
		textA(ProtectedDir+"/agent.env", "x", "", ""),
		rmA(ProtectedDir+"/tls", true),
		linkA(ProtectedDir+"/l", "x"),
		{Action: &pb.FileAction_SetSysctl{SetSysctl: &pb.SetSysctl{Key: "-w", Value: "1"}}},
		{Action: &pb.FileAction_SetSysctl{SetSysctl: &pb.SetSysctl{Key: "net.ipv4.ip_forward", Value: "1\n2"}}},
		{Action: &pb.FileAction_LoadModule{LoadModule: &pb.LoadModule{Name: "--help"}}},
		{},
	} {
		if r := applyFiles(t, a); r.Error == "" {
			t.Errorf("should be refused: %v", a)
		}
	}
}

func TestApplyFilesOperation(t *testing.T) {
	if got := operationOf("ApplyFiles"); got != "apply-files" {
		t.Errorf("ApplyFiles is in operation %q, want apply-files", got)
	}
}

func TestFilePaths(t *testing.T) {
	if p := filePaths(mkdirA("/a", "", "", "")); len(p) != 1 || p[0] != "/a" {
		t.Errorf("paths %v", p)
	}
	sys := &pb.FileAction{Action: &pb.FileAction_SetSysctl{SetSysctl: &pb.SetSysctl{Key: "k", Value: "1"}}}
	if p := filePaths(sys); len(p) != 0 {
		t.Errorf("a sysctl has no path: %v", p)
	}
}
