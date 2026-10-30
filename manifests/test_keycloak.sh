#!/usr/bin/env bash
# SPDX-License-Identifier: Apache-2.0
#
# DRAFT end-to-end test for keycloak_single_node.scala.
#
# Applies the playbook, then checks independently of its own health check:
#  - the unit orphera-keycloak is active on tst26,
#  - the management interface reports ready on 9000,
#  - from tst6, over HTTPS with the host's trust store only, the realm's discovery document names its issuer,
#  - the certificate tst26 presents verifies against the test CA and names tst26,
#  - plain HTTP (8080) is not served,
#  - a wrong admin password is refused by Keycloak (401),
#  - keycloak.conf is not readable by localadmin (uid 1000 on the host),
#  - the keycloak database has tables,
#  - Keystone refuses the federation path without a token (401),
#  - a federated login works end to end (Keycloak password grant -> Keystone token scoped to keycloak-demo),
#  - single sign-on: Horizon and Skyline each sign kcdemo in through Keycloak's login page (SKIP_SSO=1 skips),
#  - a second run of the playbook succeeds (idempotence), and the federated login still works afterwards.
#
# Prerequisites: Galera + VIP and Keystone up (keystone_single_node.scala run with federation, after this change);
# tst26 has an agent and quay.io (or the cache) is reachable from it.
# Run from scala0, in orphera-pkg:   manifests/test_keycloak.sh
# NO_APPLY=1 skips the playbook runs and only checks.
# Exit code: 0 if every assertion passes, 1 otherwise.

set -uo pipefail

NODE="tst26"
FQDN="tst26.ljalbinson.com"
KS_NODE="tst6"
KS_FQDN="tst6.ljalbinson.com"
KC="https://$FQDN:8443"
ISSUER="$KC/realms/openstack"
HELPER="/usr/local/sbin/orphera-keycloak-api"

PASS=0
FAIL=0

log() { printf '[%s] %s\n' "$(date +%H:%M:%S)" "$1"; }
run_on() { orphera run sh -c "$1" --nodes "${2:-$NODE}" --timeout "${RUN_TIMEOUT:-60}" 2>&1; }

assert_contains() {
  local desc="$1" needle="$2" haystack="$3"
  if printf '%s' "$haystack" | grep -qF -- "$needle"; then
    log "PASS: $desc"; PASS=$((PASS + 1))
  else
    log "FAIL: $desc (expected to find: $needle)"
    printf '%s\n' "$haystack" | sed 's/^/    /'
    FAIL=$((FAIL + 1))
  fi
}

if [ -z "${NO_APPLY:-}" ]; then
  log "install"
  if ! orphera cluster-playbook keycloak_single_node.scala; then
    log "FAIL: keycloak_single_node.scala did not complete"; exit 1
  fi
fi

OUT=$(run_on "echo state=\$(systemctl is-active orphera-keycloak)")
assert_contains "orphera-keycloak is active on $NODE" "state=active" "$OUT"

OUT=$(run_on "curl -s --cacert /etc/keycloak/tls/ca.crt --resolve $FQDN:9000:127.0.0.1 https://$FQDN:9000/health/ready")
assert_contains "the management interface reports ready" '"UP"' "$OUT"

OUT=$(run_on "curl -s $ISSUER/.well-known/openid-configuration" "$KS_NODE")
assert_contains "from $KS_NODE, the discovery document names the issuer (TLS verified by the host's trust store)" "\"issuer\":\"$ISSUER\"" "$OUT"

OUT=$(run_on "openssl s_client -connect $FQDN:8443 -servername $FQDN -CAfile /etc/kolla/keystone-ca/ca.crt -verify_hostname $FQDN </dev/null 2>/dev/null | grep 'Verify return code'" "$KS_NODE")
assert_contains "the certificate on 8443 verifies against the test CA and names $FQDN" "Verify return code: 0 (ok)" "$OUT"

OUT=$(run_on "curl -s -o /dev/null -w 'http=%{http_code}' --max-time 5 http://localhost:8080/")
assert_contains "plain HTTP on 8080 is not served" "http=000" "$OUT"

# Keycloak 26.8 answers a wrong password with 400 (seen on the lab), earlier releases with 401; either is a
# refusal as long as no token comes back. The body is printed so the error Keycloak gave is in the log.
OUT=$(run_on "curl -s -w ' http=%{http_code}' -d grant_type=password -d client_id=admin-cli -d username=admin -d password=wrong-password $KC/realms/master/protocol/openid-connect/token")
if printf '%s' "$OUT" | grep -qE 'http=40[01]' && ! printf '%s' "$OUT" | grep -q 'access_token'; then
  log "PASS: a wrong admin password is refused ($(printf '%s' "$OUT" | grep -oE '"error":"[^"]*"|http=[0-9]+' | tr '\n' ' '))"; PASS=$((PASS + 1))
else
  log "FAIL: a wrong admin password is not refused"; printf '%s\n' "$OUT" | sed 's/^/    /'; FAIL=$((FAIL + 1))
fi

OUT=$(run_on "runuser -u localadmin -- cat /etc/keycloak/keycloak.conf >/dev/null 2>&1 && echo readable || echo not-readable")
assert_contains "keycloak.conf is not readable by localadmin" "not-readable" "$OUT"

OUT=$(run_on "mariadb -N -e \"SELECT COUNT(*) FROM information_schema.tables WHERE table_schema='keycloak'\"" tst3)
if printf '%s\n' "$OUT" | grep -qE '(^|\] )[1-9][0-9]*$'; then
  log "PASS: the keycloak database has tables"; PASS=$((PASS + 1))
else
  log "FAIL: the keycloak database has no tables"; printf '%s\n' "$OUT" | sed 's/^/    /'; FAIL=$((FAIL + 1))
fi

OUT=$(run_on "curl -s -o /dev/null -w 'http=%{http_code}' -X POST https://$KS_FQDN:5000/v3/OS-FEDERATION/identity_providers/keycloak/protocols/openid/auth" "$KS_NODE")
assert_contains "Keystone refuses the federation path without a token" "http=401" "$OUT"

OUT=$(RUN_TIMEOUT=120 run_on "sudo $HELPER check")
assert_contains "a Keycloak user gets a scoped Keystone token" "federation ok" "$OUT"

# Single sign-on, as a browser does it (needs keystone, horizon and skyline playbooks run with WebSSO).
# SKIP_SSO=1 leaves these out.
if [ -z "${SKIP_SSO:-}" ]; then
  OUT=$(RUN_TIMEOUT=120 run_on "sudo $HELPER sso-check horizon")
  assert_contains "Horizon signs kcdemo in through Keycloak" "horizon sso ok" "$OUT"
  OUT=$(RUN_TIMEOUT=120 run_on "sudo $HELPER sso-check skyline")
  assert_contains "Skyline signs kcdemo in through Keycloak" "skyline sso ok" "$OUT"
fi

if [ -z "${NO_APPLY:-}" ]; then
  log "re-apply (idempotence)"
  if orphera cluster-playbook keycloak_single_node.scala; then
    log "PASS: second run completed"; PASS=$((PASS + 1))
  else
    log "FAIL: second run of keycloak_single_node.scala failed"; FAIL=$((FAIL + 1))
  fi
  OUT=$(RUN_TIMEOUT=120 run_on "sudo $HELPER check")
  assert_contains "the federated login still works after the second run" "federation ok" "$OUT"
fi

log "passed=$PASS failed=$FAIL"
[ "$FAIL" -eq 0 ]
