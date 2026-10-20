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

5. Refresh the OpenStack client setup: the rebuilt Keystone has a new CA, so the old `~/.config/openstack/keystone-ca.crt`
   fails with `CERTIFICATE_VERIFY_FAILED`. Run `utils/openstack-client-setup.sh`, then source
   `~/.config/openstack/admin-openrc.sh` (it reads `keystone/admin` from the store when sourced).

6. Check that the old values no longer work. The old values are in history: `git show f6c1a35^:manifests/seed_test_secrets.sh`.
   Keystone: `OS_PASSWORD='<old>' openstack token issue` must fail with `HTTP 401`, and `openstack token issue` must work.
   Grafana: `curl -u admin:admin http://tst7.ljalbinson.com:3000/api/user` must give 401, and the stored
   `observability/grafana-admin` password 200. Ceph (the tools run in a container, so use `cephadm shell`):
   `ssh tst0 "sudo cephadm shell -- ceph -n client.glance --key='<old>' -s"` must end with `RADOS permission denied`, and the
   same with `$(orphera secrets get glance/ceph-key)` must print the cluster status; the same for `client.cinder`.

## Not covered

- `manifests/build_openstack_stack.sh` does not rotate anything. It is non-destructive and keeps existing services.
- The old CA keys and the old agent token are in history too. The CA and token were replaced on 2026-10-19; this
  script does not touch `orphera/agent-token`.
- No credential literal remains in the repository. The Galera SST value is `galera/sst` (the Scala playbook already used it;
  the stale YAML copy that held a literal was removed) and the VRRP password is `galera/vrrp` (keepalived uses its first 8
  characters). `make check-secrets` fails if one comes back.
- Verified on 2026-10-20 (docs/TESTS.md K34): the rebuild after the 2026-10-19 CA and token rotation worked, and `bootstrap`
  of a new node (tst20) issued its certificate from the current CA. The rebuild stopped once, at the end of the Galera front
  end, because another host held the VIP address (K35); `mariadb_haproxy_keepalived.scala` now checks for that first.
- The Ceph keys are generated as the older AES key type, as the originals were. Ceph reports `HEALTH_WARN` for that
  ("auth client entities with insecure key types"); `glance_single_node.scala` allows the type deliberately.
