// SPDX-License-Identifier: Apache-2.0

package agent

import (
	"fmt"
	"io/fs"
	"os"
	"path/filepath"
	"strings"
	"syscall"
)

// minTreeDepth is how many path components a directory needs before it may be
// removed with everything in it: /srv/kolla-sources is allowed, /srv and / are
// not.
const minTreeDepth = 2

// needSafeTree refuses a path that is not clean (a trailing slash, "..", "//")
// or is too near the root, before anything is looked at.
func needSafeTree(path string) error {
	if !strings.HasPrefix(path, "/") {
		return fmt.Errorf("%q is not an absolute path: not removed with its contents", path)
	}
	if filepath.Clean(path) != path {
		return fmt.Errorf("%q is not a clean path: not removed with its contents", path)
	}
	if len(strings.Split(strings.Trim(path, "/"), "/")) < minTreeDepth {
		return fmt.Errorf("%s is too near the root to be removed with its contents", path)
	}
	return nil
}

// removeTree removes dir and everything under it. It refuses, before removing
// anything, when dir or a directory below it is on another filesystem than
// dir's parent: something is mounted there, and removing would empty it (a
// share, a volume). It never follows a symbolic link: a link in the tree is
// removed, not what it points to.
func removeTree(dir string) error {
	parent, err := deviceOf(filepath.Dir(dir))
	if err != nil {
		return err
	}
	err = filepath.WalkDir(dir, func(p string, d fs.DirEntry, werr error) error {
		if werr != nil {
			return werr
		}
		if !d.IsDir() {
			return nil
		}
		dev, err := deviceOf(p)
		if err != nil {
			return err
		}
		if dev != parent {
			return fmt.Errorf("%s is on another filesystem (a mount): nothing removed", p)
		}
		return nil
	})
	if err != nil {
		return err
	}
	return os.RemoveAll(dir)
}

// deviceOf is the device a path is on, not following a link.
func deviceOf(path string) (uint64, error) {
	fi, err := os.Lstat(path)
	if err != nil {
		return 0, err
	}
	st, ok := fi.Sys().(*syscall.Stat_t)
	if !ok {
		return 0, fmt.Errorf("cannot tell which filesystem %s is on", path)
	}
	return uint64(st.Dev), nil
}
