# Command steps: which could be typed actions

Status: proposed 2026-11-20. Nothing built. Follows `docs/TYPED-ACTIONS-PLAN.md`, whose phases 1 to 5 are built.

## Why

Typed actions split `apply` from `run`, but only for a description that has no `command` resource left: one `command`
still needs `run`, which is a root shell. Today 82 of the 84 apply descriptions have at least one. This document counts
the command steps in `manifests/`, sorts them by what could replace them, and proposes an order.

## What was counted

Every command step in `manifests/` at `08f5d24`:

| Form | Steps |
|---|---|
| `command(...)` resources in apply descriptions (`OrpheraApply`) | 387 |
| `Task.RunCommand` in Scala playbooks | 245 |
| `command:` / `shell:` in YAML playbooks | 52 |
| **Total** | **684** |

Each step was sorted by its label and the script it runs, by pattern, with a few corrections by hand. The counts are
good to a few steps either way, not exact. The list of every step, with its file, line, group and the proposed
replacement, is `docs/command-conversion-steps.csv`. The same, with a summary and counts by area, is the
workbook `docs/command-conversion.xlsx`.

## Summary

| Group | Steps | Share |
|---|---|---|
| 1. Existing kinds as they are | 70 | 10% |
| 2. Existing kinds with small additions | 82 | 12% |
| 3. New general kinds | 191 | 28% |
| 4. New product-specific kinds | 125 | 18% |
| 5. Checks that stay scripts | 52 | 8% |
| 6. Stays a command | 164 | 24% |

Groups 1 to 4, 468 steps (68%), could become typed actions. Groups 1 to 3 need nothing specific to a product and cover
343 (50%).

Effect on the `apply`/`run` split: after groups 1 to 3, 35 of the 84 apply descriptions have no `command` left; after
group 4, 53. The 33 that groups 1 to 3 free are mostly the `*_database.scala` and `*_rabbitmq.scala` descriptions,
`rabbitmq_single_node`, `kolla_cache` and some teardowns.

## 1. Existing kinds as they are (70)

| What | Steps | Replacement |
|---|---|---|
| Write a config file and (re)start: keepalived, haproxy, `prometheus.yml`, `60-galera.cnf`, the nginx site, `wp-config.php`, the etcd unit, admission and encryption config | 30 | `file`/`fileFrom` and `service`, the restart by `notifies` |
| Start, stop, enable services; turn off unattended-upgrades | 21 | `service` |
| Directories, `authorized_keys`, the rbd module, the apt and rpm cache client settings, accounts and groups | 19 | `directory`, `file`, `absentFile`, `authorizedKey`, `kernelModule`, `user`, `group` |

Almost all of these (55) are in Scala playbooks, as `Task.RunCommand` with a script that writes a file and restarts. They
become `Task.Apply` with a small plan, which also gives them `--check` and no restart when nothing changed.

Gap: a playbook step that restarts a service once, at a given point in a rolling upgrade (`restart mon on $host`,
`restart mariadb on $host`), is an action, not a state. `service` cannot say it. Either keep those as commands or add a
`restart` action to the playbook tasks (not to apply).

## 2. Existing kinds with small additions (82)

| Addition | Steps | Where |
|---|---|---|
| `absentDirectory(path, recursive = true)`: today it removes only an empty directory | 36 | the `remove X files, state and the helper` steps in every teardown, leftover config and data |
| A file whose content is copied from a path on the same node | 23 | `copy Keystone's CA to the X config`, the rndc key, the certificate chain, the management kubeconfig |
| `pkg` waits for the dpkg lock itself | 23 | `wait for apt and dpkg to be idle`, before package steps |

Each is a small change to an existing kind in the Go and Scala agents and the shared case table. The recursive delete
must stay inside the `paths` limit, like every typed action that names a path.

## 3. New general kinds (191)

| Kind | Steps | What it replaces |
|---|---|---|
| `containerImage` (present or absent, pulled through a mirror) | 27 | `pull the X images`, `podman rmi` |
| `container` absent, with its unit | 22 | `stop and remove the X containers and units` in every teardown |
| `sqlDatabase`, `sqlUser`, grant (present or absent) | 35 | `the $dbName database and the $dbUser user on the Galera cluster` and its drop |
| `waitFor` (TCP port, or HTTP(S) status with a CA) | 34 | `X answers on its port`, `keystone answers over TLS` |
| Certificates: CA, key and signed certificate, chain, trusted CA, CA fetched from a TLS endpoint | 34 | `create the test CA`, `create the server certificate`, `sign the certificate`, `add the test CA to the system trust store`, `fetch Keystone's CA from its TLS handshake` |
| `rabbitmqVhost`, `rabbitmqUser` with permissions | 21 | the RabbitMQ vhost and user steps and their removal |
| `aptRepository` (key and source), and an archive kind (URL, checksum, unpack) | 18 | `add the pkgs.k8s.io repository`, Grafana and sbt repositories, `download and install etcd binaries`, helm, WordPress |

These are where most of the gain is, because each one replaces the same step repeated across many services: 25 image
pulls, 22 container teardowns, 31 database steps and 21 RabbitMQ steps in the apply descriptions alone.

Notes:
- The SQL and RabbitMQ kinds run against a service, not the node's files. They connect locally (the `mariadb` socket,
  `rabbitmqctl` in the container) as the scripts do now. They need their own policy operation, since creating a database
  user is not `apply-files`.
- A certificate's private key never leaves the node: the agent makes it, and the orchestrator compares only the
  certificate's subject, issuer and expiry.
- `waitFor` is a check with a retry and a timeout, not a state. It is still worth typing, because it needs only
  `observe` and it replaces 34 copies of the same `curl` loop.

## 4. New product-specific kinds (125)

| Kind | Steps |
|---|---|
| Keystone and other OpenStack entries: service, endpoint, user, role, domain, identity provider, flavor, image | 49 |
| Ceph: pool, cephx key, config option, orchestrator spec | 28 |
| Helm release, Kubernetes object | 26 |
| libvirt VM: defined, running, autostart | 15 |
| Open vSwitch bridge and port | 7 |

29 of the 49 OpenStack steps are `register X in Keystone` and `remove X from Keystone`, one per service description,
through the same `$helperPath register` and `unregisterScript` pattern, so one Keystone kind replaces all of them. These kinds talk to an API with credentials, so they belong in the
orchestrator or a helper container more than in the agent. Whether Orphera should carry product-specific kinds at all is
a separate decision; until then these stay commands with guards, as now.

## 5. Checks that stay scripts (52)

Steps that ask a service's API whether something is ready: `scheduler and volume services report up through the API`,
`the amphora provider is listed`, `confirm-quorum`, checks that an image carries a command. They could share a generic
check-with-retry, but they would still run a script, so they still need `run`.

## 6. Stays a command (164)

- Database migrations with their hash stamps (`db_sync`, `manage db upgrade`), 14.
- The Kolla `config.json` and Apache vhost, worked out from the image (see `keystone_single_node.scala`).
- Joining and bootstrapping clusters: `kubeadm init` and `join`, Galera SST, etcd membership, the Ceph monmap and
  keyrings.
- End-to-end tests: share, load balancer and S3 round trips, `rados bench`, network-policy tests.
- Status and report steps (`print member roster`), and the YAML test playbooks (`exit 0`, `exit 1`).

These are where a command is the right tool. The aim is not to remove them but to keep them out of descriptions that
otherwise need only typed actions, so that those descriptions can run without `run`.

## Order

1. Group 1, mostly playbooks: replace the write-and-restart scripts with `Task.Apply`. No agent change.
2. Group 2: recursive `absentDirectory`, a file copied from a path on the node, and the dpkg lock wait in `pkg`.
3. Group 3, in order of steps saved: `containerImage` and `container`, `sqlDatabase`/`sqlUser`, `waitFor`,
   `rabbitmqVhost`/`rabbitmqUser`, certificates, `aptRepository` and archive.
4. Group 4, only if product-specific kinds are wanted.

Each step as in the typed-actions plan: its own branch, the Go agent and the Scala agent together, cases in the shared
table, verified on the lab with the descriptions it converts (for example `glance_database.scala` and
`glance_teardown.scala` for the SQL and container kinds), then merged.
