# Observability lifecycle — HowTo

End-to-end recipe for standing up the Galera + HAProxy/Keepalived +
WordPress stack with full Prometheus/Grafana observability on top,
starting from virgin `tst0`–`tst6` VMs, and demoing it live. Confirmed
working start-to-finish on genuinely fresh VMs (not `--resume`) on
2026-10-02.

Needs `sbt orchestrator/assembly` and `sbt scripting/assembly` built
first if you haven't already.

## 1. Cold start, in order

```bash
orphera cluster-playbook manifests/mariadb_galera_cluster.scala
orphera cluster-playbook manifests/mariadb_haproxy_keepalived.scala
orphera cluster-playbook manifests/wordpress_site.scala        # optional — gives you something to point load at
orphera cluster-playbook manifests/observability_stack.scala
orphera cluster-playbook manifests/observability_extended.scala
```

Each playbook polls for its own readiness and fails loudly (with a
`journalctl` hint) rather than hanging silently, so if one of these
exits non-zero, read the error before re-running — it's telling you
exactly which service and which log to check.

### What each stage gives you

- **`mariadb_galera_cluster.scala`** — 3-node Galera cluster on
  `tst0`/`tst1`/`tst2`.
- **`mariadb_haproxy_keepalived.scala`** — HAProxy + Keepalived VIP
  (`10.10.5.100`) in front of the Galera cluster, on the same three
  nodes.
- **`wordpress_site.scala`** — a WordPress install behind the VIP, so
  there's real traffic to generate later. Skip it if you just want the
  DB/HAProxy layer.
- **`observability_stack.scala`** — Prometheus + Grafana on `tst6`,
  `node_exporter` on all seven nodes, a provisioned Prometheus
  datasource in Grafana with explicit admin credentials set on first
  boot.
- **`observability_extended.scala`** — adds HAProxy's native
  `/metrics` endpoint (`:8404`) on `tst0`–`tst2` and `mysqld_exporter`
  (`:9104`) reading each node's local Galera instance over its own
  socket, both scraped by the same Prometheus. 13 targets total when
  this finishes (6 node_exporter + 3 haproxy + 3 mysqld + 1
  Prometheus self-scrape).

A VM is only "virgin" if every node's stateful service data was
actually wiped — `/var/lib/grafana/grafana.db` in particular has been
seen to survive a claimed rebuild and quietly carry over an old admin
account. If `observability_stack.scala` behaves as though credentials
from a previous run are still active, check that file's mtime before
assuming the manifest is wrong.

## 2. Generate some load

Point traffic at the WordPress VIP so the dashboards have something
to show:

```bash
for i in $(seq 1 2000); do curl -s -o /dev/null http://10.10.5.100/; done
```

or run `orphera cluster-playbook manifests/iperf3_test.scala` once for
a network-throughput burst instead of/as well as HTTP traffic.

Let it run for a few minutes before looking at dashboards — one-shot
graphs over a few seconds of data aren't very demo-able.

## 3. Add dashboards in Grafana

1. Log into `http://tst6.ljalbinson.com:3000` with `admin` / `admin`
   (set explicitly by `observability_stack.scala` on first boot — if
   that's rejected, see the stale-`grafana.db` note above).
2. **Dashboards → New → Import**, and import each of these by ID
   (Prometheus datasource is already provisioned, so each import just
   needs the datasource field pointed at it):
   - **1860** — Node Exporter Full
   - **12693** — HAProxy 2
   - **7362** — MySQL Overview

That's the whole demo: Galera + HAProxy/Keepalived + WordPress
underneath, Prometheus scraping 13 targets, Grafana showing all three
community dashboards live.

## Known sharp edges

- **`--resume` doesn't know a checkpointed task's script changed.**
  If you fix a manifest after a task already succeeded under the old
  version, `--resume` will skip it and the fix won't land. Run from
  the top on a fresh VM (or clear the checkpoint) to pick up a fix.
- **Grafana's own brute-force lockout is self-inflicted if you poll
  `/api/datasources` with basic auth in a retry loop.** One failed
  attempt (e.g. during Grafana's own startup race) is enough to lock
  the account out for a while, and every subsequent correct attempt
  then also fails. `observability_stack.scala` avoids this by setting
  admin credentials explicitly in `grafana.ini` before first start and
  only checking the datasource once, outside any retry loop, after
  `/api/health` *and* the "Created default admin" log line both
  confirm readiness.
- **A package's own postinst can crash-loop a systemd unit through
  the default start-limit-burst before your configure script even
  runs.** `prometheus-mysqld-exporter` does this on a genuinely fresh
  install — it auto-starts with no config present, fails 5 times in
  under a second, and lands in a rate-limited failed state that a
  later plain `systemctl restart` won't clear. Fix is
  `systemctl reset-failed <unit>` immediately before
  enable/restart — already in `observability_extended.scala`.
- **`mysqld_exporter` 0.15.0 on Ubuntu 24.04 does not read
  `DATA_SOURCE_NAME`** — only `--config.my-cnf=<path>` with a
  `[client]`-style ini file. Already handled in
  `observability_extended.scala`, worth knowing if you extend it
  further.

See `CHANGELOG.md` for the full, dated list of real bugs hit and
fixed while building this out.
