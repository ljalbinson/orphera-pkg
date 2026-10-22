// SPDX-License-Identifier: Apache-2.0

package agent

import (
	"bufio"
	"fmt"
	"io"
	"os"
	"path/filepath"
	"sort"
	"strconv"
	"strings"
	"sync"
	"syscall"
	"time"

	"github.com/ljalbinson/orphera-pkg/agent-go/internal/pb"
)

// netBackend is where a network backend keeps its configuration and how it
// re-reads it.
type netBackend struct {
	dir        string
	extensions []string
	reload     func() error
}

func (b netBackend) matches(name string) bool {
	for _, ext := range b.extensions {
		if strings.HasSuffix(name, ext) {
			return true
		}
	}
	return false
}

// networkReloader applies network configuration with an automatic rollback:
// after ReloadNetwork, the change is undone unless ConfirmNetwork arrives
// within the timeout. A rollback restores the last configuration known to
// work, taken when the agent first starts and refreshed on every confirmed
// apply, not the per-apply backup: new files are pushed before
// network-apply runs, so the per-apply backup already contains the change
// being applied and restoring it would undo nothing.
type networkReloader struct {
	backupRoot string
	backend    func() netBackend

	mu      sync.Mutex
	pending map[string]*time.Timer
}

func newNetworkReloader() *networkReloader {
	return &networkReloader{
		backupRoot: "/var/lib/orphera/network-backups",
		backend:    detectNetBackend,
		pending:    map[string]*time.Timer{},
	}
}

func (r *networkReloader) knownGood() string { return filepath.Join(r.backupRoot, "known-good") }

func exists(path string) bool {
	_, err := os.Stat(path)
	return err == nil
}

// detectNetBackend picks systemd-networkd (Ubuntu) unless only
// NetworkManager is installed (Rocky/RHEL, which ship nmcli and no
// networkctl).
func detectNetBackend() netBackend {
	if exists("/usr/bin/nmcli") && !exists("/usr/bin/networkctl") && !exists("/bin/networkctl") {
		return netBackend{
			dir:        "/etc/NetworkManager/system-connections",
			extensions: []string{".nmconnection"},
			reload:     nmcliReload,
		}
	}
	return netBackend{
		dir:        "/etc/systemd/network",
		extensions: []string{".network", ".netdev", ".link"},
		reload:     networkctlReload,
	}
}

func (r *networkReloader) reload(req *pb.NetworkReload) (*pb.NetworkReloadResult, error) {
	backupID := strconv.FormatInt(time.Now().UnixMilli(), 10)
	backupDir := filepath.Join(r.backupRoot, backupID)
	if err := os.MkdirAll(backupDir, 0o755); err != nil {
		return nil, err
	}
	be := r.backend()
	if err := backupConfig(be, backupDir); err != nil {
		return nil, err
	}
	if err := be.reload(); err != nil {
		return nil, err
	}
	timeout := req.ConfirmTimeoutSeconds
	if timeout == 0 {
		timeout = 60
	}

	r.mu.Lock()
	r.pending[backupID] = time.AfterFunc(time.Duration(timeout)*time.Second, func() {
		// Whichever comes first, the confirmation or this watchdog, removes the
		// entry; the other then does nothing.
		r.mu.Lock()
		_, still := r.pending[backupID]
		delete(r.pending, backupID)
		r.mu.Unlock()
		if !still {
			return
		}
		if err := r.rollback(be, backupDir); err != nil {
			fmt.Fprintf(os.Stderr, "network rollback of %s failed: %v\n", backupID, err)
		}
	})
	r.mu.Unlock()
	return &pb.NetworkReloadResult{BackupId: backupID}, nil
}

func (r *networkReloader) confirm(backupID string) {
	r.mu.Lock()
	t, ok := r.pending[backupID]
	if ok {
		t.Stop()
		delete(r.pending, backupID)
	}
	r.mu.Unlock()
	if ok {
		_ = r.snapshotKnownGood(r.backend())
	}
}

// initKnownGood snapshots the current configuration as known-good unless one
// already exists. Called once at start-up; failures (no network directory, a
// read-only filesystem) are ignored because the rollback then falls back to
// the per-apply backup.
func (r *networkReloader) initKnownGood() {
	if exists(r.knownGood()) {
		return
	}
	_ = r.snapshotKnownGood(r.backend())
}

func (r *networkReloader) snapshotKnownGood(be netBackend) error {
	kg := r.knownGood()
	if exists(kg) {
		entries, err := os.ReadDir(kg)
		if err != nil {
			return err
		}
		for _, e := range entries {
			if err := os.Remove(filepath.Join(kg, e.Name())); err != nil {
				return err
			}
		}
	}
	if err := os.MkdirAll(kg, 0o755); err != nil {
		return err
	}
	return backupConfig(be, kg)
}

func (r *networkReloader) rollback(be netBackend, backupDir string) error {
	src := backupDir
	if exists(r.knownGood()) {
		src = r.knownGood()
	}
	return restoreConfig(be, src)
}

func backupConfig(be netBackend, backupDir string) error {
	if !exists(be.dir) {
		return nil
	}
	entries, err := os.ReadDir(be.dir)
	if err != nil {
		return err
	}
	for _, e := range entries {
		if be.matches(e.Name()) {
			if err := copyWithAttributes(filepath.Join(be.dir, e.Name()), filepath.Join(backupDir, e.Name())); err != nil {
				return err
			}
		}
	}
	return nil
}

func restoreConfig(be netBackend, backupDir string) error {
	if exists(be.dir) {
		entries, err := os.ReadDir(be.dir)
		if err != nil {
			return err
		}
		for _, e := range entries {
			if be.matches(e.Name()) {
				if err := os.Remove(filepath.Join(be.dir, e.Name())); err != nil {
					return err
				}
			}
		}
	}
	entries, err := os.ReadDir(backupDir)
	if err != nil {
		return err
	}
	for _, e := range entries {
		if err := copyWithAttributes(filepath.Join(backupDir, e.Name()), filepath.Join(be.dir, e.Name())); err != nil {
			return err
		}
	}
	return be.reload()
}

// copyWithAttributes copies a regular file to a path that must not exist yet,
// keeping its mode, owner, group and modification time (Java's Files.copy
// with COPY_ATTRIBUTES).
func copyWithAttributes(src, dst string) error {
	fi, err := os.Stat(src)
	if err != nil {
		return err
	}
	in, err := os.Open(src)
	if err != nil {
		return err
	}
	defer in.Close()
	out, err := os.OpenFile(dst, os.O_WRONLY|os.O_CREATE|os.O_EXCL, fi.Mode().Perm())
	if err != nil {
		return err
	}
	if _, err := io.Copy(out, in); err != nil {
		out.Close()
		return err
	}
	if err := out.Close(); err != nil {
		return err
	}
	if st, ok := fi.Sys().(*syscall.Stat_t); ok {
		_ = os.Lchown(dst, int(st.Uid), int(st.Gid)) // best effort, as in Java
	}
	if err := os.Chmod(dst, fi.Mode().Perm()); err != nil {
		return err
	}
	return os.Chtimes(dst, fi.ModTime(), fi.ModTime())
}

func networkctlReload() error {
	exit, err := runInherit("networkctl", "reload")
	if err != nil {
		return err
	}
	if exit != 0 {
		return fmt.Errorf("networkctl reload failed with exit %d", exit)
	}
	return nil
}

// nmcliReload re-reads the NetworkManager keyfiles, then activates each
// profile so the change takes effect (a bare reload only updates stored
// profiles). Activating a profile is best effort; a bad profile must not stop
// the rest.
func nmcliReload() error {
	exit, err := runInherit("nmcli", "connection", "reload")
	if err != nil {
		return err
	}
	if exit != 0 {
		return fmt.Errorf("nmcli connection reload failed with exit %d", exit)
	}
	dir := "/etc/NetworkManager/system-connections"
	if !exists(dir) {
		return nil
	}
	entries, err := os.ReadDir(dir)
	if err != nil {
		return err
	}
	names := make([]string, 0, len(entries))
	for _, e := range entries {
		if strings.HasSuffix(e.Name(), ".nmconnection") {
			names = append(names, e.Name())
		}
	}
	sort.Strings(names)
	for _, n := range names {
		id, err := nmConnectionID(filepath.Join(dir, n))
		if err != nil {
			return err
		}
		if _, err := runInherit("nmcli", "connection", "up", id); err != nil {
			return err
		}
	}
	return nil
}

// nmConnectionID is the profile's id= line, or the file name without its
// extension if it has none.
func nmConnectionID(path string) (string, error) {
	f, err := os.Open(path)
	if err != nil {
		return "", err
	}
	defer f.Close()
	sc := bufio.NewScanner(f)
	for sc.Scan() {
		if line := sc.Text(); strings.HasPrefix(line, "id=") {
			return strings.TrimSpace(strings.TrimPrefix(line, "id=")), nil
		}
	}
	if err := sc.Err(); err != nil {
		return "", err
	}
	return strings.TrimSuffix(filepath.Base(path), ".nmconnection"), nil
}
