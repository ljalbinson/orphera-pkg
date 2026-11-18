// SPDX-License-Identifier: Apache-2.0

package agent

import (
	"context"
	"crypto/subtle"
	"crypto/tls"
	"crypto/x509"
	"errors"
	"fmt"
	"log"
	"os"
	"regexp"
	"strings"

	"google.golang.org/grpc"
	"google.golang.org/grpc/codes"
	"google.golang.org/grpc/credentials"
	"google.golang.org/grpc/metadata"
	"google.golang.org/grpc/peer"
	"google.golang.org/grpc/status"
)

// TokenKey is the metadata key carrying the shared token.
const TokenKey = "orphera-token"

// DevToken is the token used when none is configured. It is in the source
// code, so it protects nothing: an agent refuses to run with it.
const DevToken = "dev-only-insecure-token"

const howToSet = "From the orchestrator run: orphera secrets generate orphera/agent-token (once), " +
	"then utils/set-agent-token.sh <this node>."

// RequireToken is the token the agent may run with, from ORPHERA_TOKEN. With
// no token, or with the built-in one, it would accept calls from anyone who
// has read the source, so it is not started; the error says what to do.
func RequireToken(token string, set bool) (string, error) {
	switch {
	case !set || token == "":
		//lint:ignore ST1005 printed to the operator as a sentence, as by the Scala agent
		return "", errors.New("Refusing to start: ORPHERA_TOKEN is not set in /etc/orphera-agent/agent.env. " +
			"Without it the agent would accept the built-in development token, which anyone " +
			"who has the source code knows. " + howToSet)
	case token == DevToken:
		//lint:ignore ST1005 printed to the operator as a sentence, as by the Scala agent
		return "", errors.New("Refusing to start: ORPHERA_TOKEN in /etc/orphera-agent/agent.env is the built-in " +
			"development token, which anyone who has the source code knows. " + howToSet)
	}
	return token, nil
}

// accepts compares without stopping at the first difference.
func accepts(expected string, md metadata.MD) bool {
	vals := md.Get(TokenKey)
	if len(vals) == 0 {
		return false
	}
	// gRPC-Java's Metadata.get returns the last value for a key.
	provided := vals[len(vals)-1]
	return subtle.ConstantTimeCompare([]byte(provided), []byte(expected)) == 1
}

var errUnauthenticated = status.Error(codes.Unauthenticated, "missing or invalid token")

func authorised(ctx context.Context, token string) bool {
	md, ok := metadata.FromIncomingContext(ctx)
	return ok && accepts(token, md)
}

// validName is what an operator's name or role may be: as in the Scala agent
// and `orphera issue-client-cert`.
var validName = regexp.MustCompile(`^[a-z0-9][a-z0-9._-]{0,63}$`)

// DescribeCert names a caller from its client certificate: the operator and
// role in a subject of CN=<name>, OU=<role>, else the whole subject. As in the
// Scala agent. It is for the log only; no call is refused on it.
func DescribeCert(c *x509.Certificate) string {
	cn := c.Subject.CommonName
	if len(c.Subject.OrganizationalUnit) == 1 {
		ou := c.Subject.OrganizationalUnit[0]
		if validName.MatchString(cn) && validName.MatchString(ou) {
			return "operator=" + cn + " role=" + ou
		}
	}
	return "client=" + c.Subject.String()
}

// peerCertificate is the certificate the connection presented, if any (verified
// against the CA when the agent requires client certificates).
func peerCertificate(ctx context.Context) *x509.Certificate {
	p, ok := peer.FromContext(ctx)
	if !ok {
		return nil
	}
	info, ok := p.AuthInfo.(credentials.TLSInfo)
	if !ok || len(info.State.PeerCertificates) == 0 {
		return nil
	}
	return info.State.PeerCertificates[0]
}

// bareMethod is "Install" for "/orphera.common.Agent/Install".
func bareMethod(full string) string {
	if i := strings.LastIndex(full, "/"); i >= 0 {
		return full[i+1:]
	}
	return full
}

// admit allows the call, or refuses it by the node's access policy; the log line
// carries the same words as the refusal.
func admit(ctx context.Context, fullMethod string) error {
	method := bareMethod(fullMethod)
	d := Decide(ReadPolicyFiles(PolicyPath, RevokedPath), peerCertificate(ctx), method)
	if d.Allow {
		log.Printf("%s %s allowed", method, d.Who)
		return nil
	}
	log.Printf("%s %s REFUSED: %s", method, d.Who, d.Why)
	return status.Error(codes.PermissionDenied, "refused: "+d.Why)
}

// AuthInterceptors let a call through only when it carries the token and, on a
// node that has a policy file, only when the caller's role may make it. Each
// call is logged with who made it and whether it was allowed.
func AuthInterceptors(token string) []grpc.ServerOption {
	return []grpc.ServerOption{
		grpc.UnaryInterceptor(func(ctx context.Context, req any, info *grpc.UnaryServerInfo, h grpc.UnaryHandler) (any, error) {
			if !authorised(ctx, token) {
				return nil, errUnauthenticated
			}
			if err := admit(ctx, info.FullMethod); err != nil {
				return nil, err
			}
			return h(ctx, req)
		}),
		grpc.StreamInterceptor(func(srv any, ss grpc.ServerStream, info *grpc.StreamServerInfo, h grpc.StreamHandler) error {
			if !authorised(ss.Context(), token) {
				return errUnauthenticated
			}
			if err := admit(ss.Context(), info.FullMethod); err != nil {
				return err
			}
			return h(srv, ss)
		}),
	}
}

// The TLS certificate and key, relative to /etc/orphera-agent (the service's
// working directory). Each node has its own in tls/, issued over SSH by
// `orphera issue-certs` or `orphera bootstrap`. certs/ is still read if
// present, for an install that predates per-node certificates.
var (
	perNodeCert = [2]string{"tls/server.crt", "tls/server.key"}
	legacyCert  = [2]string{"certs/server.crt", "certs/server.key"}
)

// ClientCAPath is the CA that signs the orchestrators' client certificates,
// put here by `orphera issue-certs --client-auth`. While it is present the
// agent requires every caller to present a certificate signed by it (mutual
// TLS), as well as the token; without it, any TLS client may connect, as
// before. As in the Scala agent.
const ClientCAPath = "tls/ca.crt"

// ClientCA returns ClientCAPath if this node has it.
func ClientCA() (string, bool) { return ClientCAPath, isFile(ClientCAPath) }

// ServerTLSConfig is the agent's TLS configuration: its certificate, and, when
// clientCAFile is not "", mutual TLS: a caller must present a certificate
// signed by that CA.
func ServerTLSConfig(cert tls.Certificate, clientCAFile string) (*tls.Config, error) {
	conf := &tls.Config{
		Certificates: []tls.Certificate{cert},
		MinVersion:   tls.VersionTLS12,
	}
	if clientCAFile == "" {
		return conf, nil
	}
	pem, err := os.ReadFile(clientCAFile)
	if err != nil {
		return nil, fmt.Errorf("cannot read %s: %w", clientCAFile, err)
	}
	pool := x509.NewCertPool()
	if !pool.AppendCertsFromPEM(pem) {
		return nil, fmt.Errorf("%s holds no PEM certificate", clientCAFile)
	}
	conf.ClientCAs = pool
	conf.ClientAuth = tls.RequireAndVerifyClientCert
	return conf, nil
}

// MissingCertMessage says what to do when the node has no certificate.
const MissingCertMessage = "No TLS certificate in /etc/orphera-agent/tls: this node has not been issued one. " +
	"From the orchestrator run: orphera issue-certs --nodes <this node> --ssh-user <user>"

func isFile(p string) bool {
	fi, err := os.Stat(p)
	return err == nil && fi.Mode().IsRegular()
}

func present(pair [2]string) bool { return isFile(pair[0]) && isFile(pair[1]) }

// PerNodeCertPresent is whether this node has a certificate of its own.
func PerNodeCertPresent() bool { return present(perNodeCert) }

// ChooseCert returns the certificate and key to serve with.
func ChooseCert() (cert, key string, ok bool) {
	switch {
	case present(perNodeCert):
		return perNodeCert[0], perNodeCert[1], true
	case present(legacyCert):
		return legacyCert[0], legacyCert[1], true
	}
	return "", "", false
}
