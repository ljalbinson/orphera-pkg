# Replacing the lab's published passwords and Ceph keys

The OpenStack lab's service passwords and two Ceph client keys were once in this public repository
(`manifests/seed_test_secrets.sh`, since removed). They remain in git history, so treat them as known to anyone.
This applies to the test lab only.

Orphera does not change a password on a running service. The new values reach the lab only by rebuilding it.

## Procedure

1. Dry run, which changes nothing:

       utils/rotate-lab-secrets.sh

2. Replace the values in the secret store (copies the store to `<store>.bak-<time>` first):

       utils/rotate-lab-secrets.sh --yes

   Service secrets are removed, and Orphera makes a new random value the next time a playbook asks for one.
   `glance/ceph-key` and `cinder/ceph-key` are set to newly generated cephx keys, because a cephx key has to be
   a valid key, not a random string.

3. Check the plan, then rebuild. **This destroys 13 VMs (tst0-tst6, tst10, tst13, tst14, tst17-tst19) and everything
   on them: the Ceph data, every OpenStack database, and every instance and image.**

       manifests/rebuild_openstack_from_scratch.sh --dry-run
       manifests/rebuild_openstack_from_scratch.sh --tests --wordpress

4. The rebuild does not touch tst7. Re-apply its monitoring so that it uses the new secrets:

       orphera cluster-playbook manifests/observability_stack.scala
       orphera cluster-playbook manifests/observability_extended.scala
       manifests/test_observability_stack.sh

5. Source `~/.config/openstack/admin-openrc.sh` afresh (it reads `keystone/admin` from the store when sourced).

6. Check that the old values no longer work, using the old Keystone admin password from the history.

## Not covered

- `manifests/build_openstack_stack.sh` does not rotate anything. It is non-destructive and keeps existing services.
- The old CA keys and the old agent token are in history too. The CA and token were replaced on 2026-10-19; this
  script does not touch `orphera/agent-token`.
- No credential literal remains in the repository. The Galera SST value is `galera/sst` (the Scala playbook already used it;
  the stale YAML copy that held a literal was removed) and the VRRP password is `galera/vrrp` (keepalived uses its first 8
  characters). `make check-secrets` fails if one comes back.
- A rebuild after the 2026-10-19 CA and token rotation has not been tested. `bootstrap` issues each node's certificate
  from the current CA, so it should work, but the first VM built is the thing to watch.
