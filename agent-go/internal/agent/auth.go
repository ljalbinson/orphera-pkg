// SPDX-License-Identifier: Apache-2.0

package agent

import (
	"context"
	"crypto/subtle"
	"errors"
	"os"

	"google.golang.org/grpc"
	"google.golang.org/grpc/codes"
	"google.golang.org/grpc/metadata"
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
		return "", errors.New("Refusing to start: ORPHERA_TOKEN is not set in /etc/orphera-agent/agent.env. " +
			"Without it the agent would accept the built-in development token, which anyone " +
			"who has the source code knows. " + howToSet)
	case token == DevToken:
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

// AuthInterceptors let a call through only when it carries the token.
func AuthInterceptors(token string) []grpc.ServerOption {
	return []grpc.ServerOption{
		grpc.UnaryInterceptor(func(ctx context.Context, req any, _ *grpc.UnaryServerInfo, h grpc.UnaryHandler) (any, error) {
			if !authorised(ctx, token) {
				return nil, errUnauthenticated
			}
			return h(ctx, req)
		}),
		grpc.StreamInterceptor(func(srv any, ss grpc.ServerStream, _ *grpc.StreamServerInfo, h grpc.StreamHandler) error {
			if !authorised(ss.Context(), token) {
				return errUnauthenticated
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
