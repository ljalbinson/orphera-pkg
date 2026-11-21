// SPDX-License-Identifier: Apache-2.0

package agent

import (
	"os"
	"path/filepath"
	"strings"
	"testing"
)

func TestNeedSafeTree(t *testing.T) {
	for _, p := range []string{"/", "/etc", "/etc/", "/etc/kolla/", "/etc//kolla", "/etc/kolla/../x", "/etc/./kolla", "", "etc/kolla"} {
		if err := needSafeTree(p); err == nil {
			t.Errorf("%q should be refused", p)
		}
	}
	for _, p := range []string{"/srv/kolla-sources", "/etc/kolla/keystone", "/var/lib/orphera/rgw-keystone-state"} {
		if err := needSafeTree(p); err != nil {
			t.Errorf("%q should be allowed: %v", p, err)
		}
	}
}

func TestRemoveTreeRemovesEverythingButNotWhatALinkPointsTo(t *testing.T) {
	root := t.TempDir()
	outside := filepath.Join(root, "outside")
	os.MkdirAll(outside, 0o755)
	keep := filepath.Join(outside, "keep")
	os.WriteFile(keep, []byte("x"), 0o644)

	tree := filepath.Join(root, "a", "tree")
	os.MkdirAll(filepath.Join(tree, "d1", "d2"), 0o755)
	os.WriteFile(filepath.Join(tree, "f"), []byte("x"), 0o644)
	os.WriteFile(filepath.Join(tree, "d1", "d2", "g"), []byte("x"), 0o600)
	os.Symlink(outside, filepath.Join(tree, "link-to-dir"))
	os.Symlink(keep, filepath.Join(tree, "link-to-file"))
	// a directory without write permission is still removed (the agent is root; here the owner)
	os.MkdirAll(filepath.Join(tree, "ro"), 0o755)
	os.WriteFile(filepath.Join(tree, "ro", "h"), []byte("x"), 0o644)

	if err := removeTree(tree); err != nil {
		t.Fatalf("removeTree: %v", err)
	}
	if _, err := os.Lstat(tree); !os.IsNotExist(err) {
		t.Errorf("the tree should be gone: %v", err)
	}
	if _, err := os.Stat(keep); err != nil {
		t.Errorf("what a link pointed to must survive: %v", err)
	}
	if _, err := os.Stat(filepath.Join(root, "a")); err != nil {
		t.Errorf("the parent must stay: %v", err)
	}
}

func TestRemoveTreeOfAMissingPathFails(t *testing.T) {
	// the caller looks first (Lstat) and treats "gone" as done; removeTree itself is only for what exists
	root := t.TempDir()
	if err := removeTree(filepath.Join(root, "nope", "x")); err == nil {
		t.Error("removeTree of a path whose parent is missing should fail, not claim success")
	}
}

func TestDeviceOfDoesNotFollowALink(t *testing.T) {
	root := t.TempDir()
	link := filepath.Join(root, "l")
	os.Symlink("/proc", link)
	a, err := deviceOf(link)
	if err != nil {
		t.Fatal(err)
	}
	b, _ := deviceOf(root)
	if a != b {
		t.Errorf("a link is on the filesystem it is in (%d), not where it points (%d)", b, a)
	}
	if _, err := deviceOf(filepath.Join(root, "missing")); err == nil || !strings.Contains(err.Error(), "no such file") {
		t.Errorf("a missing path: %v", err)
	}
}

func TestRemoveTreeRefusesAMountBelow(t *testing.T) {
	// /proc is a different filesystem from /: a tree that contains it must be refused untouched.
	// Run only where /proc is a mount of its own (a Linux host or container).
	pd, err1 := deviceOf("/proc")
	rd, err2 := deviceOf("/")
	if err1 != nil || err2 != nil || pd == rd {
		t.Skip("/proc is not a separate filesystem here")
	}
	root := t.TempDir()
	tree := filepath.Join(root, "t")
	os.MkdirAll(tree, 0o755)
	// removeTree compares each directory with dir's parent, so a directory whose device differs is "a mount".
	// Using /proc itself as the tree checks the refusal without removing anything: its parent is /, a
	// different device, so removeTree must stop at the first directory it looks at.
	if err := removeTree("/proc"); err == nil || !strings.Contains(err.Error(), "another filesystem") {
		t.Fatalf("a mount point must be refused: %v", err)
	}
	if _, err := os.Stat("/proc/self"); err != nil {
		t.Fatalf("/proc must be untouched: %v", err)
	}
}
