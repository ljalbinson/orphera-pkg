// SPDX-License-Identifier: Apache-2.0

package agent

import (
	"crypto/sha256"
	"encoding/hex"
	"errors"
	"fmt"
	"io"
	"os"
	"os/exec"
	"os/user"
	"path/filepath"
	"strconv"
	"strings"
	"syscall"
	"time"

	"google.golang.org/grpc"

	"github.com/ljalbinson/orphera-pkg/agent-go/internal/pb"
)

// Timeouts for the steps of writing a received file. As in the Scala agent,
// a step that times out is reported as failed; the system call itself cannot
// be interrupted and carries on in the background.
var (
	writeTimeout = 30 * time.Second
	attrTimeout  = 10 * time.Second
	moveTimeout  = 10 * time.Second
)

// receiveFile is CopyFile: the first chunk is the metadata, the rest is the
// content. The whole file is received before anything is written, then it is
// written to a temporary file beside the destination, given its owner, group
// and mode, and renamed into place, so the destination is never half-written.
func receiveFile(stream grpc.BidiStreamingServer[pb.FileChunk, pb.Event], q *queue) error {
	var meta *pb.FileMetadata
	var content [][]byte
	first := true
	for {
		chunk, err := stream.Recv()
		if errors.Is(err, io.EOF) {
			break
		}
		if err != nil {
			return err
		}
		if first {
			meta = chunk.GetMetadata()
			first = false
			continue
		}
		if c, ok := chunk.Payload.(*pb.FileChunk_Content); ok {
			content = append(content, c.Content)
		}
	}
	if meta == nil {
		q.result("No metadata received", 1, false)
		return nil
	}
	return writeFile(meta, content, q)
}

func writeFile(meta *pb.FileMetadata, content [][]byte, q *queue) error {
	dest := meta.DestPath
	tmp := filepath.Join(filepath.Dir(dest), "."+filepath.Base(dest)+".orphera-tmp")
	q.progress("Writing " + dest)

	err := withTimeout(writeTimeout, "Timed out writing content for "+dest, func() error {
		f, err := os.OpenFile(tmp, os.O_WRONLY|os.O_CREATE|os.O_TRUNC, 0o666)
		if err != nil {
			return err
		}
		for _, c := range content {
			if _, err := f.Write(c); err != nil {
				f.Close()
				return err
			}
		}
		return f.Close()
	})
	if err == nil {
		err = withTimeout(attrTimeout,
			"Timed out setting owner/group/mode on "+dest+" — possible NSS/principal-lookup hang",
			func() error { return applyAttributes(tmp, meta) })
	}
	if err == nil {
		err = withTimeout(moveTimeout, "Timed out moving temp file into place at "+dest,
			func() error { return os.Rename(tmp, dest) })
	}
	if err != nil {
		return err
	}
	q.result("OK", 0, true)
	return nil
}

// withTimeout runs f, giving up with msg if it has not finished in d.
func withTimeout(d time.Duration, msg string, f func() error) error {
	done := make(chan error, 1)
	go func() { done <- f() }()
	t := time.NewTimer(d)
	defer t.Stop()
	select {
	case err := <-done:
		return err
	case <-t.C:
		return errors.New(msg)
	}
}

func applyAttributes(path string, meta *pb.FileMetadata) error {
	wrap := func(err error) error {
		//lint:ignore ST1005 shown to the operator as "FAILED: <message>", worded as by the Scala agent
		return fmt.Errorf("Failed to set file attributes: %w", err)
	}
	if meta.Owner != "" {
		uid, err := lookupUID(meta.Owner)
		if err != nil {
			return wrap(err)
		}
		if err := os.Lchown(path, uid, -1); err != nil {
			return wrap(err)
		}
	}
	if meta.Group != "" {
		gid, err := lookupGID(meta.Group)
		if err != nil {
			return wrap(err)
		}
		if err := os.Lchown(path, -1, gid); err != nil {
			return wrap(err)
		}
	}
	if meta.Mode != 0 {
		if err := os.Chmod(path, os.FileMode(meta.Mode&0o777)); err != nil {
			return wrap(err)
		}
	}
	return nil
}

// sendFile is RemoteFetchFile: the metadata, then the content in 64 KiB
// chunks, or a single error message.
func sendFile(req *pb.FetchFile, stream grpc.ServerStreamingServer[pb.FileData]) error {
	sendErr := func(msg string) error {
		return stream.Send(&pb.FileData{Payload: &pb.FileData_Error{Error: validUTF8(msg)}})
	}
	path := javaPath(req.Path)
	if _, err := os.Stat(path); err != nil {
		if errors.Is(err, os.ErrNotExist) {
			return sendErr("File does not exist: " + req.Path)
		}
		return sendErr("Read failed: " + err.Error())
	}
	meta, err := readMetadata(path)
	if err != nil {
		return sendErr("Read failed: " + err.Error())
	}
	if err := stream.Send(&pb.FileData{Payload: &pb.FileData_Metadata{Metadata: meta}}); err != nil {
		return err
	}
	f, err := os.Open(path)
	if err != nil {
		return sendErr("Read failed: " + err.Error())
	}
	defer f.Close()
	buf := make([]byte, 64*1024)
	for {
		n, err := io.ReadFull(f, buf)
		if n > 0 {
			chunk := make([]byte, n)
			copy(chunk, buf[:n])
			if serr := stream.Send(&pb.FileData{Payload: &pb.FileData_Content{Content: chunk}}); serr != nil {
				return serr
			}
		}
		if errors.Is(err, io.EOF) || errors.Is(err, io.ErrUnexpectedEOF) {
			return nil
		}
		if err != nil {
			return sendErr("Read failed: " + err.Error())
		}
	}
}

// javaPath normalises a path the way java.nio's Paths.get does: repeated
// slashes collapse and a trailing slash is dropped. "." and ".." are kept.
func javaPath(p string) string {
	for strings.Contains(p, "//") {
		p = strings.ReplaceAll(p, "//", "/")
	}
	if len(p) > 1 {
		p = strings.TrimSuffix(p, "/")
	}
	return p
}

func readMetadata(path string) (*pb.FileMetadata, error) {
	fi, err := os.Stat(path)
	if err != nil {
		return nil, err
	}
	st := fi.Sys().(*syscall.Stat_t)
	return &pb.FileMetadata{
		DestPath: validUTF8(path),
		Owner:    validUTF8(userName(st.Uid)),
		Group:    validUTF8(groupName(st.Gid)),
		Mode:     uint32(fi.Mode().Perm()),
	}, nil
}

// checkFile is CheckFile: whether the destination needs copying, and why.
func checkFile(req *pb.FileCheck) *pb.FileCheckResult {
	res, err := checkFileErr(req)
	if err != nil {
		return &pb.FileCheckResult{NeedsCopy: true, Reason: validUTF8("check failed: " + err.Error())}
	}
	return res
}

func checkFileErr(req *pb.FileCheck) (*pb.FileCheckResult, error) {
	path := req.DestPath
	fi, err := os.Stat(path)
	if errors.Is(err, os.ErrNotExist) {
		return &pb.FileCheckResult{NeedsCopy: true, Reason: "file does not exist"}, nil
	}
	if err != nil {
		return nil, err
	}
	sum, err := sha256File(path)
	if err != nil {
		return nil, err
	}
	if sum != req.Sha256 {
		return &pb.FileCheckResult{NeedsCopy: true, Reason: "content differs"}, nil
	}
	st := fi.Sys().(*syscall.Stat_t)
	owner, group := userName(st.Uid), groupName(st.Gid)
	mode := uint32(fi.Mode().Perm())
	var reason string
	switch {
	case req.Owner != "" && req.Owner != owner:
		reason = fmt.Sprintf("owner differs: expected %s, got %s", req.Owner, owner)
	case req.Group != "" && req.Group != group:
		reason = fmt.Sprintf("group differs: expected %s, got %s", req.Group, group)
	case req.Mode != 0 && req.Mode != mode:
		reason = fmt.Sprintf("mode differs: expected %o, got %o", req.Mode, mode)
	}
	if reason != "" {
		return &pb.FileCheckResult{NeedsCopy: true, Reason: validUTF8(reason)}, nil
	}
	return &pb.FileCheckResult{NeedsCopy: false, Reason: "unchanged"}, nil
}

func sha256File(path string) (string, error) {
	f, err := os.Open(path)
	if err != nil {
		return "", err
	}
	defer f.Close()
	h := sha256.New()
	if _, err := io.Copy(h, f); err != nil {
		return "", err
	}
	return hex.EncodeToString(h.Sum(nil)), nil
}

// ---------------------------------------------------------------------------
// User and group names
//
// The agent is built without cgo, so os/user reads /etc/passwd and /etc/group
// only. Users and groups that come from elsewhere through NSS (LDAP, sssd) are
// looked up with `getent`, which goes through the system's NSS as the JVM did.
// As with Java's principal lookup, a name that is not found but is a number
// is taken as a numeric id.

func lookupUID(name string) (int, error) {
	if u, err := user.Lookup(name); err == nil {
		return strconv.Atoi(u.Uid)
	}
	if id, ok := getentID("passwd", name); ok {
		return id, nil
	}
	if n, err := strconv.Atoi(name); err == nil && n >= 0 {
		return n, nil
	}
	return 0, fmt.Errorf("user not found: %s", name)
}

func lookupGID(name string) (int, error) {
	if g, err := user.LookupGroup(name); err == nil {
		return strconv.Atoi(g.Gid)
	}
	if id, ok := getentID("group", name); ok {
		return id, nil
	}
	if n, err := strconv.Atoi(name); err == nil && n >= 0 {
		return n, nil
	}
	return 0, fmt.Errorf("group not found: %s", name)
}

// userName is the name of uid, or the number itself if it has no name.
func userName(uid uint32) string {
	id := strconv.FormatUint(uint64(uid), 10)
	if u, err := user.LookupId(id); err == nil {
		return u.Username
	}
	if name, ok := getentName("passwd", id); ok {
		return name
	}
	return id
}

func groupName(gid uint32) string {
	id := strconv.FormatUint(uint64(gid), 10)
	if g, err := user.LookupGroupId(id); err == nil {
		return g.Name
	}
	if name, ok := getentName("group", id); ok {
		return name
	}
	return id
}

// getent runs `getent <db> <key>` and returns the first entry's fields.
func getent(db, key string) ([]string, bool) {
	out, err := exec.Command("getent", db, key).Output()
	if err != nil {
		return nil, false
	}
	line := strings.SplitN(strings.TrimSpace(string(out)), "\n", 2)[0]
	fields := strings.Split(line, ":")
	if len(fields) < 3 {
		return nil, false
	}
	return fields, true
}

func getentID(db, name string) (int, bool) {
	f, ok := getent(db, name)
	if !ok || f[0] != name {
		return 0, false
	}
	n, err := strconv.Atoi(f[2])
	return n, err == nil
}

func getentName(db, id string) (string, bool) {
	f, ok := getent(db, id)
	if !ok || f[2] != id {
		return "", false
	}
	return f[0], true
}
