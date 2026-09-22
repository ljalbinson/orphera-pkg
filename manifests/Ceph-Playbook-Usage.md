# Ceph mon cluster — playbook usage

Four playbooks bring up (or tear down) a 3-node Ceph mon quorum on
`tst0`, `tst1`, `tst4`. Run in order.

## Cold start

```bash
orphera cluster-playbook manifests/ceph-mon-keyring.yaml
orphera cluster-playbook manifests/ceph-mon-quorum.yaml
orphera cluster-playbook manifests/ceph-admin-keyring.yaml
```

Verify:
```bash
ssh tst0 "sudo ceph -s"
```
Expect `mon: 3 daemons, quorum tst0,tst1,tst4`. `HEALTH_WARN` is normal
at this point (no OSDs, insecure global_id reclaim, msgr2 not enabled)
— none of that blocks the mons being up and reachable.

## What each playbook does

- **`ceph-mon-keyring.yaml`** — installs `ceph-common` (pinned to
  `{{ceph_version}}` from `inventory.yaml`), generates the mon cluster
  secret once on `tst0`, distributes it verbatim to `tst1`/`tst4`.
- **`ceph-mon-quorum.yaml`** — installs `ceph-mon`/`ceph-base`,
  generates `ceph.conf`/monmap from each node's `cluster_ip` (real
  addresses, not `download.ceph.com` — that repo doesn't publish a
  `noble` build, don't reintroduce it), distributes both, runs
  `--mkfs` on all three, starts the daemons, polls for 2-of-3 quorum.
- **`ceph-admin-keyring.yaml`** — creates and registers `client.admin`
  via an authenticated `mon.` connection on `tst0`, distributes the
  resulting keyring to `tst1`/`tst4`. Must run *after* quorum is up —
  it needs a live, authenticated connection to the running cluster,
  not just local files.
- **`ceph-teardown.yaml`** — stops the mon service, wipes mon data and
  every `/etc/ceph/*` file, purges `ceph-mon`/`ceph-base`/`ceph-common`
  plus their autoremoved dependencies, removes `/var/lib/ceph`,
  `/var/log/ceph`, `/var/run/ceph`. Leaves the `/etc/ceph` directory
  itself in place (only clears its contents) — package reinstalls
  don't reliably recreate a deleted directory.

## Full reset

```bash
orphera cluster-playbook manifests/ceph-teardown.yaml
orphera cluster-playbook manifests/ceph-mon-keyring.yaml
orphera cluster-playbook manifests/ceph-mon-quorum.yaml
orphera cluster-playbook manifests/ceph-admin-keyring.yaml
```

## If a run fails partway through

Don't just rerun the same playbook — check state first. Failures
usually leave one host inconsistent with the other two (partially
initialized mon data, a stopped-but-not-purged package, a missing
`/etc/ceph`). Rerunning against a half-broken host tends to compound
the problem rather than fix it.

```bash
for h in tst0 tst1 tst4; do
  ssh $h "sudo systemctl is-active ceph-mon@$h"
  ssh $h "sudo ls -la /etc/ceph /var/lib/ceph/mon/ 2>&1"
done
```

If any host is genuinely broken and you can't tell why, the reliable
path is a full teardown + rebuild rather than trying to patch one
node back into a state matching the other two.

## Known assumptions baked into these playbooks

- `hostname -s` on each host returns exactly `tst0`/`tst1`/`tst4` —
  the mon ID, monmap entries, and systemd unit names all depend on
  this matching.
- Mons run v1 messenger protocol only (port `6789`) — `mon_status`
  confirms this; the v2/`3300` connection attempts you'll see in
  verbose client logs are expected noise, not a problem.
- `inventory.yaml`'s `cluster_ip` for each node is correct and
  reachable between all three hosts — a stale or wrong IP here breaks
  everything downstream (`ceph.conf`, monmap, daemon startup) and
  won't surface as a clear error until `mkfs-and-start`.
