// SPDX-License-Identifier: Apache-2.0

package agent

import (
	"crypto/x509"
	"crypto/x509/pkix"
	"math/big"
	"os"
	"path/filepath"
	"strings"
	"testing"

	"google.golang.org/grpc/codes"
	"google.golang.org/grpc/status"
)

const testPolicy = `# a policy
default_role admin
role viewer facts,version,uptime,thread-info,check-file,fetch
role deployer facts,copy,packages   # no run
role admin all
`

func operatorCert(name, role string, serial int64) *x509.Certificate {
	return &x509.Certificate{
		SerialNumber: big.NewInt(serial),
		Subject:      pkix.Name{CommonName: name, OrganizationalUnit: []string{role}},
	}
}

func orchestratorCert(serial int64) *x509.Certificate {
	return &x509.Certificate{
		SerialNumber: big.NewInt(serial),
		Subject:      pkix.Name{CommonName: "orphera-orchestrator@scala0"},
	}
}

func files(policy, revoked string) PolicyFiles {
	return PolicyFiles{Present: true, Policy: policy, Revoked: revoked}
}

func TestPolicyParse(t *testing.T) {
	p, err := ParsePolicy(testPolicy)
	if err != nil {
		t.Fatal(err)
	}
	if p.DefaultRole != "admin" || len(p.Roles) != 3 {
		t.Errorf("parsed %+v", p)
	}
	for _, bad := range []string{
		"role viewer facts\n",                      // no default_role
		"default_role admin\nrole v nonsense\n",    // unknown operation
		"default_role admin\nrole v\n",             // no operations
		"default_role admin\nwhat is this\n",       // not a line
		"default_role Admin\nrole admin all\n",     // invalid name
		"default_role admin\nrole v facts extra\n", // too many fields
		"",
	} {
		if _, err := ParsePolicy(bad); err == nil {
			t.Errorf("accepted %q", bad)
		}
	}
}

func TestDecide(t *testing.T) {
	cases := []struct {
		name   string
		files  PolicyFiles
		cert   *x509.Certificate
		method string
		allow  bool
		why    string
	}{
		{"no policy allows everything", PolicyFiles{}, operatorCert("alice", "viewer", 1), "ExecuteCommand", true, ""},
		{"no policy, no certificate", PolicyFiles{}, nil, "ExecuteCommand", true, ""},
		{"viewer may read", files(testPolicy, ""), operatorCert("alice", "viewer", 1), "GatherFacts", true, ""},
		{"viewer may not run", files(testPolicy, ""), operatorCert("alice", "viewer", 1), "ExecuteCommand", false, "may not make the call ExecuteCommand (operation run)"},
		{"viewer may not copy", files(testPolicy, ""), operatorCert("alice", "viewer", 1), "CopyFile", false, "operation copy"},
		{"deployer may copy and check", files(testPolicy, ""), operatorCert("bob", "deployer", 2), "CheckFile", true, ""},
		{"deployer may install", files(testPolicy, ""), operatorCert("bob", "deployer", 2), "Install", true, ""},
		{"deployer may not reboot", files(testPolicy, ""), operatorCert("bob", "deployer", 2), "TriggerReboot", false, "operation reboot"},
		{"admin may do anything", files(testPolicy, ""), operatorCert("carol", "admin", 3), "ExecuteCommand", true, ""},
		{"admin may make a call no operation lists", files(testPolicy, ""), operatorCert("carol", "admin", 3), "SomethingNew", true, ""},
		{"others may not make a call no operation lists", files(testPolicy, ""), operatorCert("bob", "deployer", 2), "SomethingNew", false, "operation (none)"},
		{"a role not in the policy", files(testPolicy, ""), operatorCert("dave", "auditor", 4), "GatherFacts", false, "not in this node's access policy"},
		{"the orchestrator's own certificate has the default role", files(testPolicy, ""), orchestratorCert(5), "ExecuteCommand", true, ""},
		{"no certificate, so no role", files(testPolicy, ""), nil, "GatherFacts", false, "no client certificate"},
		{"revoked serial", files(testPolicy, "# gone\n3\n"), operatorCert("carol", "admin", 3), "GatherFacts", false, "revoked"},
		{"revoked serial in hex with a prefix", files(testPolicy, "0x0A\n"), operatorCert("carol", "admin", 10), "GatherFacts", false, "revoked"},
		{"another serial is not revoked", files(testPolicy, "3\n"), operatorCert("carol", "admin", 4), "GatherFacts", true, ""},
		{"an unparseable policy refuses everything", files("rubbish\n", ""), operatorCert("carol", "admin", 3), "GatherFacts", false, "cannot be read"},
		{"an unreadable policy refuses everything", PolicyFiles{Present: true}, operatorCert("carol", "admin", 3), "GatherFacts", false, "cannot be read"},
		{"an OU that is no valid role is refused, not given the default", files(testPolicy, ""),
			&x509.Certificate{SerialNumber: big.NewInt(6), Subject: pkix.Name{CommonName: "mallory", OrganizationalUnit: []string{"Not A Role"}}}, "GatherFacts", false, "no valid operator and role"},
	}
	for _, c := range cases {
		d := Decide(c.files, c.cert, c.method)
		if d.Allow != c.allow {
			t.Errorf("%s: allow=%v (%s), want %v", c.name, d.Allow, d.Why, c.allow)
			continue
		}
		if c.why != "" && !strings.Contains(d.Why, c.why) {
			t.Errorf("%s: reason %q does not contain %q", c.name, d.Why, c.why)
		}
	}
}

func TestDecideNamesTheCaller(t *testing.T) {
	d := Decide(files(testPolicy, ""), operatorCert("alice", "viewer", 1), "GatherFacts")
	if d.Who != "operator=alice role=viewer as viewer" {
		t.Errorf("who: %q", d.Who)
	}
}

func TestReadPolicyFiles(t *testing.T) {
	dir := t.TempDir()
	policy := filepath.Join(dir, "policy")
	revoked := filepath.Join(dir, "revoked")
	if f := ReadPolicyFiles(policy, revoked); f.Present {
		t.Error("no file, but a policy is present")
	}
	if err := os.WriteFile(policy, []byte(testPolicy), 0o644); err != nil {
		t.Fatal(err)
	}
	if f := ReadPolicyFiles(policy, revoked); !f.Present || f.Policy != testPolicy || f.Revoked != "" {
		t.Errorf("policy without a revoked file: %+v", f)
	}
	if err := os.WriteFile(revoked, []byte("3\n"), 0o644); err != nil {
		t.Fatal(err)
	}
	if f := ReadPolicyFiles(policy, revoked); f.Revoked != "3\n" {
		t.Errorf("revoked: %+v", f)
	}
}

func TestBareMethod(t *testing.T) {
	if got := bareMethod("/orphera.common.Agent/Install"); got != "Install" {
		t.Errorf("got %q", got)
	}
}

func TestPolicyPaths(t *testing.T) {
	root := t.TempDir()
	ok := filepath.Join(root, "ok")
	other := filepath.Join(root, "other")
	for _, d := range []string{ok, other} {
		if err := os.MkdirAll(d, 0o755); err != nil {
			t.Fatal(err)
		}
	}
	// A link inside the allowed directory that leads out of it.
	if err := os.Symlink(other, filepath.Join(ok, "escape")); err != nil {
		t.Fatal(err)
	}
	text := "default_role admin\nrole deployer copy,fetch\npaths deployer " + ok + "/,/srv/\nrole admin all\n"
	p, err := ParsePolicy(text)
	if err != nil {
		t.Fatal(err)
	}
	if got := p.Paths["deployer"]; len(got) != 2 || got[0] != ok+"/" || got[1] != "/srv/" {
		t.Errorf("paths: %v", got)
	}
	cases := []struct {
		name, role, path string
		refused          bool
	}{
		{"a new file inside", "deployer", filepath.Join(ok, "a.conf"), false},
		{"the directory itself", "deployer", ok, false},
		{"the second prefix", "deployer", "/srv/site/index.html", false},
		{"outside", "deployer", filepath.Join(other, "a.conf"), true},
		{"out by ..", "deployer", ok + "/../other/a.conf", true},
		{"out by a link", "deployer", filepath.Join(ok, "escape", "a.conf"), true},
		{"a sibling that starts with the same letters", "deployer", ok + "-sibling/a.conf", true},
		{"a relative path is the agent's own directory", "deployer", "a.conf", true},
		{"a role without paths is not limited", "admin", "/etc/shadow", false},
		{"a role not in the policy has none", "nobody", "/etc/shadow", false},
	}
	for _, c := range cases {
		why := p.PathRefusal(c.role, c.path)
		if (why != "") != c.refused {
			t.Errorf("%s: refusal %q, want refused=%v", c.name, why, c.refused)
		}
		if c.refused && !strings.Contains(why, "role 'deployer' may copy, check and fetch files only under") {
			t.Errorf("%s: reason %q", c.name, why)
		}
	}
	root2, err := ParsePolicy("default_role admin\nrole r copy\npaths r /\n")
	if err != nil {
		t.Fatal(err)
	}
	if why := root2.PathRefusal("r", "/etc/anything"); why != "" {
		t.Errorf("the prefix / limits nothing, but: %s", why)
	}
}

func TestPolicyPathsParse(t *testing.T) {
	for _, bad := range []string{
		"default_role admin\npaths a /x/\n",                           // no such role
		"default_role admin\npaths a /x/\nrole a copy\n",              // before its role
		"default_role admin\nrole a copy\npaths a x/\n",               // not absolute
		"default_role admin\nrole a copy\npaths a ,\n",                // nothing in it
		"default_role admin\nrole a copy\npaths a /x/\npaths a /y/\n", // twice
		"default_role admin\nrole a copy\npaths a /x/ extra\n",        // too many fields
		"default_role admin\nrole a copy\npaths Not-A-Role /x/\n",     // invalid name
	} {
		if _, err := ParsePolicy(bad); err == nil {
			t.Errorf("accepted %q", bad)
		}
	}
}

func TestPathDeniedBy(t *testing.T) {
	dir := t.TempDir()
	text := "default_role admin\nrole viewer fetch\npaths viewer " + dir + "/\nrole admin all\n"
	viewer := operatorCert("alice", "viewer", 1)
	inside := filepath.Join(dir, "a")
	outside := filepath.Join(filepath.Dir(dir), "elsewhere")
	if err := pathDeniedBy(files(text, ""), viewer, inside); err != nil {
		t.Errorf("inside refused: %v", err)
	}
	err := pathDeniedBy(files(text, ""), viewer, outside)
	if status.Code(err) != codes.PermissionDenied || !strings.Contains(err.Error(), "refused: role 'viewer'") {
		t.Errorf("outside: %v", err)
	}
	if err := pathDeniedBy(files(text, ""), orchestratorCert(2), outside); err != nil {
		t.Errorf("the default role is not limited: %v", err)
	}
	if err := pathDeniedBy(PolicyFiles{}, viewer, outside); err != nil {
		t.Errorf("no policy, no limit: %v", err)
	}
	if err := pathDeniedBy(files("rubbish\n", ""), viewer, outside); err != nil {
		t.Errorf("an unparseable policy is refused by the interceptor, not here: %v", err)
	}
	if err := pathDeniedBy(files(text, ""), nil, outside); err != nil {
		t.Errorf("no certificate is refused by the interceptor, not here: %v", err)
	}
}
