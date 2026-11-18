// SPDX-License-Identifier: Apache-2.0

package agent

import (
	"crypto/x509"
	"crypto/x509/pkix"
	"strings"
	"testing"
)

func TestDescribeCert(t *testing.T) {
	op := &x509.Certificate{Subject: pkix.Name{CommonName: "alice", OrganizationalUnit: []string{"deployer"}}}
	if got := DescribeCert(op); got != "operator=alice role=deployer" {
		t.Errorf("operator certificate: got %q", got)
	}
	orch := &x509.Certificate{Subject: pkix.Name{CommonName: "orphera-orchestrator@scala0"}}
	if got := DescribeCert(orch); !strings.HasPrefix(got, "client=") || !strings.Contains(got, "orphera-orchestrator@scala0") {
		t.Errorf("orchestrator certificate: got %q", got)
	}
	bad := &x509.Certificate{Subject: pkix.Name{CommonName: "Alice B", OrganizationalUnit: []string{"deployer"}}}
	if got := DescribeCert(bad); !strings.HasPrefix(got, "client=") {
		t.Errorf("a name that is not valid must not be taken as an operator: got %q", got)
	}
	two := &x509.Certificate{Subject: pkix.Name{CommonName: "alice", OrganizationalUnit: []string{"deployer", "admin"}}}
	if got := DescribeCert(two); !strings.HasPrefix(got, "client=") {
		t.Errorf("two roles must not be taken as one: got %q", got)
	}
}
