#!/usr/bin/env bash
#
# End-to-end test for the single-node Keystone playbooks.
#
# Lifecycle: keystone_teardown -> keystone_single_node (Kolla container) ->
# keystone_single_node again (idempotence: existing CA, certificate and fernet
# keys must be reused),
# with independent checks run on the Keystone node over `orphera run` rather
# than trusting the playbook's own health check.
#
# Prerequisites: the Galera cluster and its VIP (mariadb_galera_cluster.scala,
# mariadb_haproxy_keepalived.scala) are healthy, and the agent is on NODE.
#
# Run from scala0, in orphera-pkg:   manifests/test_keystone.sh
# Exit code: 0 if every assertion passes, 1 otherwise.

set -uo pipefail

NODE="tst6"
FQDN="tst6.ljalbinson.com"
CA="/etc/kolla/keystone/ssl/ca.crt"   # world-readable copy of the test CA

PASS=0
FAIL=0

log() { printf '[%s] %s\n' "$(date +%H:%M:%S)" "$1"; }

# run_on <command>: run a shell command on NODE, print its output.
run_on() { orphera run sh -c "$1" --nodes "$NODE" 2>&1; }

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

assert_not_contains() {
  local desc="$1" needle="$2" haystack="$3"
  if printf '%s' "$haystack" | grep -qF -- "$needle"; then
    log "FAIL: $desc (found: $needle)"
    printf '%s\n' "$haystack" | sed 's/^/    /'
    FAIL=$((FAIL + 1))
  else
    log "PASS: $desc"; PASS=$((PASS + 1))
  fi
}

log "teardown (failures here are tolerated: nothing may be installed yet)"
orphera cluster-playbook manifests/keystone_teardown.scala || true

log "install"
if ! orphera cluster-playbook manifests/keystone_single_node.scala; then
  log "FAIL: keystone_single_node.scala did not complete"; exit 1
fi

# Independent checks, over TLS with the test CA.
OUT=$(run_on "curl -s --cacert $CA -o /dev/null -w 'http=%{http_code}' https://$FQDN:5000/v3/")
assert_contains "GET /v3/ over TLS returns 200" "http=200" "$OUT"

BODY='{"auth":{"identity":{"methods":["password"],"password":{"user":{"name":"admin","domain":{"name":"Default"},"password":"orphera-test-keystone-admin-password"}}},"scope":{"project":{"name":"admin","domain":{"name":"Default"}}}}}'
OUT=$(run_on "curl -s --cacert $CA -o /dev/null -w 'http=%{http_code}' -H 'Content-Type: application/json' -d '$BODY' https://$FQDN:5000/v3/auth/tokens")
assert_contains "password auth returns 201 with a scoped token" "http=201" "$OUT"

OUT=$(run_on "curl -s -o /dev/null -w 'http=%{http_code}' --max-time 5 http://$FQDN:5000/v3/")
assert_not_contains "plain HTTP on 5000 does not return the API" "http=200" "$OUT"

OUT=$(run_on "curl -s -o /dev/null -w 'http=%{http_code}' --max-time 5 --cacert /dev/null https://$FQDN:5000/v3/ ; echo rc=\$?")
assert_not_contains "TLS without the CA is rejected" "http=200" "$OUT"

# The Keystone node has no mariadb client; ask a Galera node (unix-socket root).
OUT=$(orphera run sh -c "sudo mariadb -N -e 'select count(*) from keystone.user'" --nodes tst4 2>&1)
assert_not_contains "keystone tables exist and replicated to tst4" "ERROR" "$OUT"
assert_contains "keystone.user has at least the admin row" "1" "$OUT"

OUT=$(run_on "systemctl is-active orphera-keystone; sudo podman ps")
assert_contains "systemd unit active and container running" "active" "$OUT"
assert_contains "container is the Kolla keystone image" "quay.io/openstack.kolla/keystone" "$OUT"

OUT=$(run_on "T=\$(sudo /usr/local/sbin/keystone-admin-token) && curl -s --cacert $CA -H \"X-Auth-Token: \$T\" https://$FQDN:5000/v3/users")
assert_contains "token authorises GET /v3/users and lists admin" '"name":"admin"' "$OUT"

# Idempotence: the CA fingerprint must not change on a second run, and the
# fernet keys must still be there.
FP1=$(run_on "openssl x509 -in $CA -noout -fingerprint -sha256")
log "re-apply (idempotence)"
if ! orphera cluster-playbook manifests/keystone_single_node.scala; then
  log "FAIL: second run of keystone_single_node.scala failed"; FAIL=$((FAIL + 1))
fi
FP2=$(run_on "openssl x509 -in $CA -noout -fingerprint -sha256")
if [ "$FP1" = "$FP2" ] && printf '%s' "$FP1" | grep -q Fingerprint; then
  log "PASS: CA unchanged by the second run"; PASS=$((PASS + 1))
else
  log "FAIL: CA changed between runs"; FAIL=$((FAIL + 1))
fi
OUT=$(run_on "sudo ls /var/lib/orphera/keystone/fernet-keys")
assert_contains "fernet keys still present" "0" "$OUT"

log "passed=$PASS failed=$FAIL"
[ "$FAIL" -eq 0 ]
