#!/usr/bin/env bash
# SPDX-License-Identifier: Apache-2.0
#
# Non-destructive test for WordPress on tst7 (wordpress_site.scala).
#
# Applies wordpress_site.scala (idempotent: creates the database and user if
# they are missing, installs nginx + php-fpm, writes wp-config.php) and then
# checks, independently of the playbook's own health check:
#   - the Galera cluster and the VIP are up (otherwise WordPress cannot work);
#   - the `wordpress` database and user exist on the cluster;
#   - nginx and php-fpm are active on tst7;
#   - the site answers over HTTP from here, shows WordPress and no
#     "Error establishing a database connection";
#   - the playbook is idempotent.
#
# It does NOT tear anything down. (The old test_wordpress.sh rebuilt Galera and
# wiped every database; that chain is now rebuild_galera_and_wordpress.sh.)
#
# Prerequisites: Galera + haproxy/keepalived applied; tst7 has an agent.
# Run from scala0, in orphera-pkg:   manifests/test_wordpress.sh
# NO_APPLY=1 manifests/test_wordpress.sh   checks only.
# Exit code: 0 if every assertion passes, 1 otherwise.

set -uo pipefail

SITE="http://tst7.ljalbinson.com/"
DB="wordpress"
DBUSER="wordpress_user"
VIP="10.10.5.100"

PASS=0
FAIL=0

log() { printf '[%s] %s\n' "$(date +%H:%M:%S)" "$1"; }

run_on() { orphera run sh -c "$2" --nodes "$1" 2>&1; }

assert_contains() {
  local desc="$1" needle="$2" haystack="$3"
  if printf '%s' "$haystack" | grep -qF -- "$needle"; then
    log "PASS: $desc"; PASS=$((PASS + 1))
  else
    log "FAIL: $desc (expected to find: $needle)"
    printf '%s\n' "$haystack" | sed 's/^/    /' | tail -n 15
    FAIL=$((FAIL + 1))
  fi
}

assert_absent() {
  local desc="$1" needle="$2" haystack="$3"
  if printf '%s' "$haystack" | grep -qiF -- "$needle"; then
    log "FAIL: $desc (found: $needle)"; FAIL=$((FAIL + 1))
  else
    log "PASS: $desc"; PASS=$((PASS + 1))
  fi
}

if [ -z "${NO_APPLY:-}" ]; then
  log "apply wordpress_site.scala"
  if ! orphera cluster-playbook wordpress_site.scala; then
    log "FAIL: wordpress_site.scala did not complete"; exit 1
  fi
fi

# ---- the database layer ------------------------------------------------------

OUT=$(run_on tst3 "mariadb -N -e \"SHOW STATUS LIKE 'wsrep_cluster_size'\"")
assert_contains "Galera cluster has 3 nodes" "wsrep_cluster_size	3" "$OUT"

OUT=$(run_on tst3,tst4,tst5 "ip -4 addr | grep -c $VIP")
assert_contains "the VIP $VIP is held by one of the Galera nodes" "] 1" "$OUT"

OUT=$(run_on tst3 "mariadb -N -e \"SHOW DATABASES LIKE '$DB'; SELECT user FROM mysql.user WHERE user='$DBUSER'\"")
assert_contains "database '$DB' exists" "$DB" "$OUT"
assert_contains "user '$DBUSER' exists" "$DBUSER" "$OUT"

# ---- the web tier ------------------------------------------------------------

OUT=$(run_on tst7 "systemctl is-active nginx php8.3-fpm")
assert_contains "nginx and php-fpm are active on tst7" "active" "$OUT"
assert_absent "neither service is failed or inactive" "inactive" "$OUT"

BODY=$(curl -s -L --max-time 30 -o - -w '\nHTTP_CODE=%{http_code}' "$SITE")
assert_contains "the site answers 200 from here (after redirects)" "HTTP_CODE=200" "$BODY"
assert_contains "the page is WordPress" "WordPress" "$BODY"
assert_absent "no database connection error on the page" "Error establishing a database connection" "$BODY"

# ---- idempotence -------------------------------------------------------------

if [ -z "${NO_APPLY:-}" ]; then
  log "re-apply (idempotence)"
  if orphera cluster-playbook wordpress_site.scala; then
    log "PASS: second run completed"; PASS=$((PASS + 1))
  else
    log "FAIL: second run failed"; FAIL=$((FAIL + 1))
  fi
  BODY=$(curl -s -L --max-time 30 -o - -w '\nHTTP_CODE=%{http_code}' "$SITE")
  assert_contains "the site still answers 200 after the second run" "HTTP_CODE=200" "$BODY"
fi

log "passed=$PASS failed=$FAIL"
[ "$FAIL" -eq 0 ]
