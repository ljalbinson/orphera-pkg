// SPDX-License-Identifier: Apache-2.0

package agent

import (
	"bytes"
	"context"
	"crypto/ecdsa"
	"crypto/elliptic"
	"crypto/rand"
	"crypto/tls"
	"crypto/x509"
	"crypto/x509/pkix"
	"errors"
	"io"
	"math/big"
	"net"
	"os"
	"os/user"
	"path/filepath"
	"strings"
	"testing"
	"time"

	"google.golang.org/grpc"
	"google.golang.org/grpc/codes"
	"google.golang.org/grpc/credentials"
	"google.golang.org/grpc/metadata"
	"google.golang.org/grpc/status"

	"github.com/ljalbinson/orphera-pkg/agent-go/internal/pb"
)

const testToken = "test-token-123"

// startAgent serves the agent over TLS with the token check, as main does,
// and returns a client and a context carrying the token.
func startAgent(t *testing.T) (pb.AgentClient, context.Context) {
	t.Helper()
	key, _ := ecdsa.GenerateKey(elliptic.P256(), rand.Reader)
	tmpl := &x509.Certificate{
		SerialNumber: big.NewInt(1),
		Subject:      pkix.Name{CommonName: "localhost"},
		DNSNames:     []string{"localhost"},
		NotBefore:    time.Now().Add(-time.Hour),
		NotAfter:     time.Now().Add(time.Hour),
	}
	der, err := x509.CreateCertificate(rand.Reader, tmpl, tmpl, &key.PublicKey, key)
	if err != nil {
		t.Fatal(err)
	}
	cert := tls.Certificate{Certificate: [][]byte{der}, PrivateKey: key}
	pool := x509.NewCertPool()
	parsed, _ := x509.ParseCertificate(der)
	pool.AddCert(parsed)

	opts := append(AuthInterceptors(testToken), grpc.Creds(credentials.NewTLS(&tls.Config{Certificates: []tls.Certificate{cert}, MinVersion: tls.VersionTLS12})))
	srv := grpc.NewServer(opts...)
	svc := NewService()
	pb.RegisterAgentServer(srv, svc)
	lis, err := net.Listen("tcp", "127.0.0.1:0")
	if err != nil {
		t.Fatal(err)
	}
	go srv.Serve(lis)
	t.Cleanup(srv.Stop)

	conn, err := grpc.NewClient(lis.Addr().String(),
		grpc.WithTransportCredentials(credentials.NewTLS(&tls.Config{RootCAs: pool, ServerName: "localhost"})))
	if err != nil {
		t.Fatal(err)
	}
	t.Cleanup(func() { conn.Close() })
	ctx, cancel := context.WithTimeout(context.Background(), 30*time.Second)
	t.Cleanup(cancel)
	return pb.NewAgentClient(conn), metadata.AppendToOutgoingContext(ctx, TokenKey, testToken)
}

type eventStream interface {
	Recv() (*pb.Event, error)
}

func collect(t *testing.T, s eventStream) []*pb.Event {
	t.Helper()
	var evs []*pb.Event
	for {
		e, err := s.Recv()
		if errors.Is(err, io.EOF) {
			return evs
		}
		if err != nil {
			t.Fatal(err)
		}
		evs = append(evs, e)
	}
}

func TestE2EToken(t *testing.T) {
	c, ctx := startAgent(t)
	if _, err := c.GetVersion(context.Background(), &pb.VersionRequest{}); status.Code(err) != codes.Unauthenticated {
		t.Fatalf("no token: %v", err)
	}
	bad := metadata.AppendToOutgoingContext(context.Background(), TokenKey, "wrong")
	if _, err := c.GetVersion(bad, &pb.VersionRequest{}); status.Code(err) != codes.Unauthenticated {
		t.Fatalf("wrong token: %v", err)
	}
	s, _ := c.ExecuteCommand(context.Background(), &pb.RunCommandRequest{Command: []string{"true"}})
	if _, err := s.Recv(); status.Code(err) != codes.Unauthenticated {
		t.Fatalf("streaming call without token: %v", err)
	}
	if v, err := c.GetVersion(ctx, &pb.VersionRequest{}); err != nil || v.Version != Version {
		t.Fatalf("version %v %v", v, err)
	}
}

func TestE2EExecuteCommandEndsAtResult(t *testing.T) {
	c, ctx := startAgent(t)
	s, err := c.ExecuteCommand(ctx, &pb.RunCommandRequest{Command: []string{"sh", "-c", "for i in 1 2 3; do echo line$i; done"}})
	if err != nil {
		t.Fatal(err)
	}
	evs := collect(t, s)
	if got := outputs(evs); strings.Join(got, ",") != "line1,line2,line3" {
		t.Fatalf("outputs %q", got)
	}
	if r := last(t, evs); r.Kind != pb.Event_RESULT || !r.Success {
		t.Fatalf("result %v", r)
	}
}

func TestE2EStreamTaskFailureEndsStream(t *testing.T) {
	// A task that panics, and one that errors, must each still end the stream
	// with a FAILED RESULT.
	for _, work := range []func(*queue) error{
		func(*queue) error { panic("boom") },
		func(*queue) error { return errors.New("apt-get update exploded") },
	} {
		fs := &fakeStream{ctx: context.Background()}
		if err := streamTask(fs, work); err != nil {
			t.Fatal(err)
		}
		r := fs.sent[len(fs.sent)-1]
		if r.Kind != pb.Event_RESULT || r.Success || !strings.HasPrefix(r.Message, "FAILED: ") {
			t.Fatalf("result %v", r)
		}
	}
}

type fakeStream struct {
	grpc.ServerStream
	ctx  context.Context
	sent []*pb.Event
}

func (f *fakeStream) Context() context.Context { return f.ctx }
func (f *fakeStream) Send(e *pb.Event) error   { f.sent = append(f.sent, e); return nil }

func TestE2ECopyCheckAndFetch(t *testing.T) {
	c, ctx := startAgent(t)
	dir := t.TempDir()
	dest := filepath.Join(dir, "conf.yaml")
	me, _ := user.Current()
	grp, _ := user.LookupGroupId(me.Gid)
	content := bytes.Repeat([]byte("orphera\n"), 20000) // 160 KB: several chunks back

	s, err := c.CopyFile(ctx)
	if err != nil {
		t.Fatal(err)
	}
	s.Send(&pb.FileChunk{Payload: &pb.FileChunk_Metadata{Metadata: &pb.FileMetadata{DestPath: dest, Owner: me.Username, Group: grp.Name, Mode: 0o600}}})
	for i := 0; i < len(content); i += 50000 {
		end := min(i+50000, len(content))
		s.Send(&pb.FileChunk{Payload: &pb.FileChunk_Content{Content: content[i:end]}})
	}
	s.CloseSend()
	evs := collect(t, s)
	if r := last(t, evs); !r.Success || r.Message != "OK" {
		t.Fatalf("copy result %v", evs)
	}
	got, _ := os.ReadFile(dest)
	fi, _ := os.Stat(dest)
	if !bytes.Equal(got, content) || fi.Mode().Perm() != 0o600 {
		t.Fatalf("written file wrong: %d bytes, mode %o", len(got), fi.Mode().Perm())
	}
	if exists(filepath.Join(dir, ".conf.yaml.orphera-tmp")) {
		t.Fatal("temporary file left behind")
	}

	sum, _ := sha256File(dest)
	r, err := c.CheckFile(ctx, &pb.FileCheck{DestPath: dest, Sha256: sum, Owner: me.Username, Group: grp.Name, Mode: 0o600})
	if err != nil || r.NeedsCopy || r.Reason != "unchanged" {
		t.Fatalf("check %v %v", r, err)
	}

	f, err := c.RemoteFetchFile(ctx, &pb.FetchFile{Path: dest})
	if err != nil {
		t.Fatal(err)
	}
	var meta *pb.FileMetadata
	var back []byte
	chunks := 0
	for {
		d, err := f.Recv()
		if errors.Is(err, io.EOF) {
			break
		}
		if err != nil {
			t.Fatal(err)
		}
		switch p := d.Payload.(type) {
		case *pb.FileData_Metadata:
			meta = p.Metadata
		case *pb.FileData_Content:
			back = append(back, p.Content...)
			chunks++
		case *pb.FileData_Error:
			t.Fatal(p.Error)
		}
	}
	if meta == nil || meta.Owner != me.Username || meta.Mode != 0o600 || !bytes.Equal(back, content) || chunks != 3 {
		t.Fatalf("fetch: meta %v, %d bytes in %d chunks", meta, len(back), chunks)
	}

	f, _ = c.RemoteFetchFile(ctx, &pb.FetchFile{Path: filepath.Join(dir, "missing")})
	d, err := f.Recv()
	if err != nil || d.GetError() != "File does not exist: "+filepath.Join(dir, "missing") {
		t.Fatalf("missing file: %v %v", d, err)
	}
}

func TestE2ECopyWithoutMetadata(t *testing.T) {
	c, ctx := startAgent(t)
	s, _ := c.CopyFile(ctx)
	s.CloseSend()
	if r := last(t, collect(t, s)); r.Success || r.Message != "No metadata received" {
		t.Fatalf("result %v", r)
	}
}

func TestE2ECopyBadOwnerFails(t *testing.T) {
	c, ctx := startAgent(t)
	s, _ := c.CopyFile(ctx)
	s.Send(&pb.FileChunk{Payload: &pb.FileChunk_Metadata{Metadata: &pb.FileMetadata{DestPath: filepath.Join(t.TempDir(), "x"), Owner: "orphera-no-such-user"}}})
	s.CloseSend()
	r := last(t, collect(t, s))
	if r.Success || !strings.HasPrefix(r.Message, "FAILED: Failed to set file attributes") {
		t.Fatalf("result %v", r)
	}
}

func TestE2EInstallDebRefusedWithoutNodeCert(t *testing.T) {
	c, ctx := startAgent(t)
	// The test's working directory has no tls/server.crt.
	s, _ := c.InstallDebPackage(ctx, &pb.InstallDeb{Path: "/tmp/orphera-agent.deb"})
	r := last(t, collect(t, s))
	if r.Success || r.Message != "Refusing to install: "+MissingCertMessage {
		t.Fatalf("result %v", r)
	}
}

func TestE2EFactsUptimeThreads(t *testing.T) {
	c, ctx := startAgent(t)
	f, err := c.GatherFacts(ctx, &pb.FactsRequest{})
	if err != nil || f.Hostname == "" || f.KernelVersion == "" || f.CpuCount == 0 || f.MemoryTotalBytes == 0 || len(f.Disks) == 0 {
		t.Fatalf("facts %v %v", f, err)
	}
	u, err := c.GetUptime(ctx, &pb.UptimeRequest{})
	if err != nil || u.UptimeSeconds <= 0 {
		t.Fatalf("uptime %v %v", u, err)
	}
	ti, err := c.GetThreadInfo(ctx, &pb.ThreadInfoRequest{})
	if err != nil || ti.OsThreads == 0 {
		t.Fatalf("threads %v %v", ti, err)
	}
}
