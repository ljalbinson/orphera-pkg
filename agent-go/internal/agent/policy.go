// SPDX-License-Identifier: Apache-2.0

package agent

import (
	"context"
	"crypto/x509"
	"errors"
	"fmt"
	"log"
	"math/big"
	"os"
	"path/filepath"
	"sort"
	"strings"

	"google.golang.org/grpc/codes"
	"google.golang.org/grpc/status"
)

// The access policy of this node: /etc/orphera-agent/policy, made for the node
// by `orphera issue-policy` from config/access.yaml. The Scala agent reads the
// same file and applies the same table (common Policy.scala); keep the two the
// same.
//
//	default_role admin            # the role of a certificate with no role in it
//	role viewer facts,version     # operations, comma-separated
//	role deployer copy,fetch
//	paths deployer /etc/ceph/,/srv/   # copy, check-file and fetch only under these
//	role admin all
//
// A node with no policy file allows every call, as before. With one, a call is
// allowed only to a role that lists an operation containing it.

// PolicyPath and RevokedPath are relative to /etc/orphera-agent, the service's
// working directory.
const (
	PolicyPath  = "policy"
	RevokedPath = "revoked"
	allOps      = "all"
)

// Operations are the operations a policy may name, each a fixed set of agent
// calls (bare method names). A call in no operation is allowed only by "all".
var Operations = map[string][]string{
	"facts":          {"GatherFacts"},
	"version":        {"GetVersion"},
	"uptime":         {"GetUptime"},
	"thread-info":    {"GetThreadInfo"},
	"check-file":     {"CheckFile"},
	"observe":        {"ObserveNode"},
	"apply-files":    {"ApplyFiles"},
	"apply-access":   {"ApplyAccess"},
	"apply-software": {"ApplySoftware"},
	"fetch":          {"RemoteFetchFile"},
	"copy":           {"CopyFile", "CheckFile"},
	"packages":       {"Install", "Remove", "RunAutoRemove", "RunDistUpgrade", "InstallDebPackage"},
	"run":            {"ExecuteCommand"},
	"reboot":         {"TriggerReboot"},
	"shutdown":       {"TriggerShutdown"},
	"network":        {"ReloadNetwork", "ConfirmNetwork"},
}

// Policy is what each role may do on this node.
type Policy struct {
	DefaultRole string
	Roles       map[string]map[string]bool
	// Paths limits a role's CopyFile, CheckFile and RemoteFetchFile to these
	// prefixes. A role with no entry is not limited.
	Paths map[string][]string
}

func operationOf(method string) string {
	names := make([]string, 0, len(Operations))
	for name := range Operations {
		names = append(names, name)
	}
	sort.Strings(names)
	for _, name := range names {
		for _, m := range Operations[name] {
			if m == method {
				return name
			}
		}
	}
	return "(none)"
}

func opsAllow(ops map[string]bool, method string) bool {
	for op := range ops {
		for _, m := range Operations[op] {
			if m == method {
				return true
			}
		}
	}
	return false
}

// Refusal says why role may not make the call method (its bare name), or is ""
// when it may.
func (p *Policy) Refusal(role, method string) string {
	ops, ok := p.Roles[role]
	if !ok {
		return fmt.Sprintf("role '%s' is not in this node's access policy", role)
	}
	if ops[allOps] || opsAllow(ops, method) {
		return ""
	}
	names := make([]string, 0, len(ops))
	for op := range ops {
		names = append(names, op)
	}
	sort.Strings(names)
	return fmt.Sprintf("role '%s' may not make the call %s (operation %s); it may: %s",
		role, method, operationOf(method), strings.Join(names, ", "))
}

// PathRefusal says why role may not use path with copy, check-file or fetch,
// or is "" when it may: the role has no paths, or path is one of them or under
// one, after making it absolute, resolving "..", and following symbolic links
// (as isProtected does), so another name for the same place counts too.
func (p *Policy) PathRefusal(role, path string) string {
	prefixes := p.Paths[role]
	if len(prefixes) == 0 {
		return ""
	}
	resolved := resolvePath(path)
	sep := string(filepath.Separator)
	for _, pre := range prefixes {
		d := realPath(pre)
		if d == sep || resolved == d || strings.HasPrefix(resolved, d+sep) {
			return ""
		}
	}
	return fmt.Sprintf("role '%s' may copy, check and fetch files only under %s; %s is outside",
		role, strings.Join(prefixes, ", "), path)
}

// pathDeniedBy is nil when the caller with certificate cert may use path with
// copy, check-file or fetch, else the refusal to send. The interceptor has
// already refused a caller with no role or a policy that does not parse, so
// those are not refused again here.
func pathDeniedBy(files PolicyFiles, cert *x509.Certificate, path string) error {
	if !files.Present || cert == nil {
		return nil
	}
	p, err := ParsePolicy(files.Policy)
	if err != nil {
		return nil
	}
	role, err := RoleOf(cert, p.DefaultRole)
	if err != nil {
		return nil
	}
	why := p.PathRefusal(role, path)
	if why == "" {
		return nil
	}
	log.Printf("path %s as %s REFUSED: %s", DescribeCert(cert), role, why)
	return status.Error(codes.PermissionDenied, "refused: "+why)
}

// pathDenied is pathDeniedBy for the caller of ctx, by this node's policy.
func pathDenied(ctx context.Context, path string) error {
	return pathDeniedBy(ReadPolicyFiles(PolicyPath, RevokedPath), peerCertificate(ctx), path)
}

// ParsePolicy reads the file; an agent with a file it cannot parse refuses
// every call rather than allowing them.
func ParsePolicy(text string) (*Policy, error) {
	p := &Policy{Roles: map[string]map[string]bool{}, Paths: map[string][]string{}}
	for _, raw := range strings.Split(text, "\n") {
		line := raw
		if i := strings.Index(line, "#"); i >= 0 {
			line = line[:i]
		}
		fields := strings.Fields(line)
		if len(fields) == 0 {
			continue
		}
		switch {
		case len(fields) == 2 && fields[0] == "default_role" && validName.MatchString(fields[1]):
			p.DefaultRole = fields[1]
		case len(fields) == 3 && fields[0] == "role" && validName.MatchString(fields[1]):
			ops := map[string]bool{}
			for _, op := range strings.Split(fields[2], ",") {
				op = strings.TrimSpace(op)
				if op == "" {
					continue
				}
				if _, known := Operations[op]; !known && op != allOps {
					return nil, fmt.Errorf("role %s: unknown operation %s", fields[1], op)
				}
				ops[op] = true
			}
			if len(ops) == 0 {
				return nil, fmt.Errorf("role %s has no operations", fields[1])
			}
			p.Roles[fields[1]] = ops
		case len(fields) == 3 && fields[0] == "paths" && validName.MatchString(fields[1]):
			if _, ok := p.Roles[fields[1]]; !ok {
				return nil, fmt.Errorf("paths for role %s, which is not defined above it", fields[1])
			}
			if _, again := p.Paths[fields[1]]; again {
				return nil, fmt.Errorf("role %s has two paths lines", fields[1])
			}
			var prefixes []string
			for _, pre := range strings.Split(fields[2], ",") {
				pre = strings.TrimSpace(pre)
				if pre == "" {
					continue
				}
				if !strings.HasPrefix(pre, "/") {
					return nil, fmt.Errorf("role %s: the path %s is not absolute", fields[1], pre)
				}
				prefixes = append(prefixes, pre)
			}
			if len(prefixes) == 0 {
				return nil, fmt.Errorf("role %s has no paths", fields[1])
			}
			p.Paths[fields[1]] = prefixes
		default:
			return nil, fmt.Errorf("cannot read the policy line: %s", strings.TrimSpace(raw))
		}
	}
	if p.DefaultRole == "" {
		return nil, errors.New("the policy has no default_role line")
	}
	return p, nil
}

func normaliseSerial(hex string) string {
	n, ok := new(big.Int).SetString(strings.TrimPrefix(hex, "0x"), 16)
	if !ok {
		return hex
	}
	return n.Text(16)
}

// ParseRevoked reads a revoked file: serial numbers in hexadecimal, one per
// line, "#" starts a comment.
func ParseRevoked(text string) map[string]bool {
	out := map[string]bool{}
	for _, raw := range strings.Split(text, "\n") {
		line := raw
		if i := strings.Index(line, "#"); i >= 0 {
			line = line[:i]
		}
		line = strings.ToLower(strings.TrimSpace(line))
		if line != "" {
			out[normaliseSerial(line)] = true
		}
	}
	return out
}

// RoleOf is the role a caller's certificate gives: the OU of an operator
// certificate; defaultRole for one with no OU at all (the orchestrator's own).
// One with an OU that is not a valid operator and role has none: refused.
func RoleOf(c *x509.Certificate, defaultRole string) (string, error) {
	if len(c.Subject.OrganizationalUnit) == 1 && validName.MatchString(c.Subject.CommonName) &&
		validName.MatchString(c.Subject.OrganizationalUnit[0]) {
		return c.Subject.OrganizationalUnit[0], nil
	}
	if len(c.Subject.OrganizationalUnit) > 0 {
		return "", fmt.Errorf("the client certificate %s names no valid operator and role", c.Subject.String())
	}
	return defaultRole, nil
}

// Decision is whether a call may go ahead, and who made it.
type Decision struct {
	Allow bool
	Who   string
	Why   string
}

// PolicyFiles are the policy and the revoked list as text. Present is false
// when the node has no policy (every call is then allowed, as before).
type PolicyFiles struct {
	Present bool
	Policy  string
	Revoked string
}

// ReadPolicyFiles reads them. A policy that is there but cannot be read is
// returned as an empty text, which does not parse, so every call is refused
// rather than allowed.
func ReadPolicyFiles(policyPath, revokedPath string) PolicyFiles {
	if _, err := os.Stat(policyPath); err != nil {
		if os.IsNotExist(err) {
			return PolicyFiles{}
		}
		return PolicyFiles{Present: true}
	}
	policy, err := os.ReadFile(policyPath)
	if err != nil {
		return PolicyFiles{Present: true}
	}
	revoked, err := os.ReadFile(revokedPath)
	if err != nil && !os.IsNotExist(err) {
		return PolicyFiles{Present: true}
	}
	return PolicyFiles{Present: true, Policy: string(policy), Revoked: string(revoked)}
}

// Decide says whether the call method (its bare name) may go ahead.
func Decide(files PolicyFiles, cert *x509.Certificate, method string) Decision {
	who := "no client certificate"
	if cert != nil {
		who = DescribeCert(cert)
	}
	if !files.Present {
		return Decision{Allow: true, Who: who}
	}
	p, err := ParsePolicy(files.Policy)
	if err != nil {
		return Decision{Who: who, Why: fmt.Sprintf("this node's access policy cannot be read (%v); every call is refused until it is fixed", err)}
	}
	if cert == nil {
		return Decision{Who: who, Why: "no client certificate, so no role"}
	}
	if ParseRevoked(files.Revoked)[cert.SerialNumber.Text(16)] {
		return Decision{Who: who, Why: "the client certificate has been revoked"}
	}
	role, err := RoleOf(cert, p.DefaultRole)
	if err != nil {
		return Decision{Who: who, Why: err.Error()}
	}
	who += " as " + role
	if why := p.Refusal(role, method); why != "" {
		return Decision{Who: who, Why: why}
	}
	return Decision{Allow: true, Who: who}
}
