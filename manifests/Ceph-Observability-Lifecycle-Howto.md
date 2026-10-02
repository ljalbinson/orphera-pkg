# Ceph observability + performance testing — HowTo

End-to-end recipe for getting a cephadm-managed Ceph cluster reporting
into the existing Grafana stack, then generating real load against it
so the dashboard actually moves. Builds on top of two lifecycles that
already exist in this repo — the cephadm Ceph lifecycle and the
Prometheus/Grafana observability stack — rather than replacing either.

**Status: drafted, not yet run against real infrastructure.**
`ceph_observability.scala` and `ceph_performance_test.scala` are new
and unconfirmed — treat every step below as the intended sequence, not
a proven-working one yet. Run it, and fix whatever the first real run
turns up the same way every other manifest in this repo got fixed:
read the error, check `journalctl`/`cephadm shell -- ceph -s`, patch
the script, rerun.

Needs `sbt orchestrator/assembly` and `sbt scripting/assembly` built
first if you haven't already.

## 1. Bring up the Ceph cluster

```bash
orphera cluster-playbook manifests/cephadm_install.scala
orphera cluster-playbook manifests/cephadm_add_mons.scala
orphera cluster-playbook manifests/cephadm_add_osds.scala
```

Gives you a 3-mon, 3-OSD cluster on `tst0`/`tst1`/`tst2` (the same
three nodes that also run Galera + HAProxy in this lab — reused, not
dedicated, same as every other node here). `cephadm_install.scala`
bootstraps with `--skip-dashboard --skip-monitoring-stack` deliberately
— cephadm's own built-in Prometheus/Grafana stack is skipped so Ceph's
metrics land in the shared tst6 stack below instead of a second,
parallel one.

Verify independently before moving on:
```bash
ssh tst0 "sudo cephadm shell -- ceph -s"
```
Expect `mon: 3 daemons, quorum tst0,tst1,tst2` and `osd: 3 osds: 3 up, 3 in`.
Or run `./test_ceph_lifecycle.sh` from `scala0` for the full automated
check (it tears down and rebuilds the cluster from scratch as part of
the test — don't run it against a cluster you want to keep).

## 2. Bring up the shared observability stack

```bash
orphera cluster-playbook manifests/observability_stack.scala
orphera cluster-playbook manifests/observability_extended.scala
```

Prometheus + Grafana on `tst6`, scraping `node_exporter` fleet-wide plus
HAProxy and `mysqld_exporter` on `tst0`–`tst2` — 13 targets. See
`Observability-Lifecycle-Howto.md` for the full detail on this stage
(including the Grafana first-boot/lockout gotchas already fixed there).
Skip this step only if it's already applied and healthy.

## 3. Wire Ceph into Grafana

```bash
orphera cluster-playbook manifests/ceph_observability.scala
```

Enables ceph mgr's built-in `prometheus` module on `tst0`
(`:9283/metrics` — no separate exporter package, unlike HAProxy/mysqld)
and adds it as a fifth Prometheus scrape job. 14 targets total when
this finishes.

Then in Grafana (`http://tst6.ljalbinson.com:3000`, `admin`/`admin`):
**Dashboards → New → Import**, ID **2842** ("Ceph - Cluster"), pointed
at the existing Prometheus datasource.

## 4. Generate load

```bash
orphera cluster-playbook manifests/ceph_performance_test.scala
```

Runs `rados bench` write/sequential-read/random-read passes (10s each)
against a disposable pool on `tst0`, then cleans the pool up again.
Prometheus scrapes every 15s, so the bandwidth/IOPS/latency spike
should show up on the Ceph dashboard within a scrape interval or two of
each pass starting. Rerun it any time you want another visible spike —
each run starts from a fresh pool.

That's the whole chain: a real cephadm cluster, reporting into the same
Grafana instance as everything else in this lab, with a repeatable way
to generate load for the demo.

## What each new piece assumes (unconfirmed until a real run)

- **`ceph_observability.scala`** assumes the mgr's `:9283` endpoint is
  reachable at `tst0`'s `{{cluster_ip}}` with no extra bind
  configuration (cephadm runs the mgr container with host networking)
  — the same kind of bind assumption that turned out wrong for HAProxy
  in `observability_extended.scala`, so check `curl` against it for
  real rather than trusting this note. It also assumes a single mgr on
  `tst0` with no standby — nothing in the cephadm chain here ever calls
  `ceph orch apply mgr` with a placement count, so that should hold,
  but if a second mgr ever gets placed this file's single-host target
  needs revisiting.
- **`ceph_performance_test.scala`** is RADOS-object-storage load only
  (`rados bench`) — no `rbd bench`/kernel block-device pass, which
  would need host-level `ceph-common` and keyring assumptions this
  file doesn't make. A natural follow-up once this is confirmed
  working.

See `CHANGELOG.md` for how this plays out once it's actually been run.
