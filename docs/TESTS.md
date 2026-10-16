# Orphera tests (A-G run for v0.1.163 on 2026-10-08, H after it, I on branch rocky-support 2026-10-09)

Run from `~/orphera-pkg` on scala0 after `git pull && make orpheracli`.
Test VMs: tst0-tst7 (tst0-tst2 have OSD data disks; tst7 is the spare) and
tst8 (Rocky 10, on gs3, 10.10.5.20; section I).

## A. Multi-config `cluster-playbook`

### A1. Parallel chain across configs (success)
    orphera cluster-playbook manifests/gen_set_dns.scala manifests/gen_packages.scala \
      --config config/tst0.yaml config/tst1.yaml --parallel 2
Expect: both configs OK, output prefixed `[tst0]`/`[tst1]`, logs in
`.orphera-build-logs/<time>/`, summary table, exit 0.

### A2. Deliberate failure
    orphera cluster-playbook manifests/gen_set_dns.scala manifests/gen_packages.scala \
      --config config/tst0.yaml config/nope.yaml --parallel 2 --quiet
    echo "exit=$?"
Expect: tst0 OK; nope FAILED "stopped at manifests/gen_set_dns.scala"; step 2
never started for nope; last lines of nope's log printed; exit=1.  PASSED.

### A3. Ctrl-C during a chain
    orphera cluster-playbook manifests/gen_dist_upgrade.scala \
      --config config/tst0.yaml config/tst1.yaml --parallel 2
    # Ctrl-C after ~20 s, then:
    ps -ef | grep -E 'orphera|gen_dist_upgrade' | grep -v grep
    ssh tst0 pgrep -a apt-get; ssh tst1 pgrep -a apt-get
Expect: prompt returns at once, no leftover orphera/java children. (An apt run
already started on a node is not cancelled; it finished before the next test.)  PASSED.

## B. `@bootstrap` chain step

### B1. Full 5-step build chain (agent deb auto-discovered)
    orphera cluster-playbook manifests/kvm_vm_provision.scala @bootstrap \
      manifests/gen_dist_upgrade.scala manifests/gen_set_dns.scala manifests/gen_packages.scala \
      --config config/tst*.yaml
Expect: all configs OK (about 11 min for 8 VMs).  PASSED.

### B2. Explicit agent package
    ... --file ./orphera-agent_0.1.163_amd64.deb
Expect: same result as B1.  PASSED.

## C. Agent failure handling

### C1. Failed `apt-get update` during install (hang fix) + exit code
    ssh tst7 "echo 'deb https://download.ceph.com/debian-squid noble main' | sudo tee /etc/apt/sources.list.d/ceph.list"
    orphera install tree --nodes tst7 --update-cache; echo "exit=$?"
    ssh tst7 "sudo rm /etc/apt/sources.list.d/ceph.list"
    orphera install tree --nodes tst7; echo "exit=$?"
Expect: first run prints the 404 / "does not have a Release file",
`exit=1 success=false`, no hang, shell exit=1; second run exit=0.  PASSED.

## D. Teardown

### D1. Leftover Ceph apt source removed
    for n in tst0 tst1 tst2; do ssh $n "echo 'deb https://download.ceph.com/debian-squid noble main' | sudo tee /etc/apt/sources.list.d/ceph.list >/dev/null"; done
    orphera cluster-playbook manifests/cephadm_teardown.scala
    for n in tst0 tst1 tst2; do echo "== $n"; ssh $n "ls /etc/apt/sources.list.d/; ls /etc/apt/keyrings/ceph.release.gpg /etc/apt/trusted.gpg.d/ceph.release.gpg 2>&1"; done
Expect: "Removing leftover Ceph apt source" per node; only ubuntu.sources left;
disks zapped via /dev/disk/by-id paths.  PASSED.
(Not exercised: the keyring-file removal, since no keyring existed.)

## E. Verbs and targeting

### E1. `shutdown`
    orphera shutdown --nodes tst7                    # must refuse without --yes
    orphera shutdown --nodes tst7 --yes --delay 5
    sleep 30; ssh tst7 true; echo "ssh=$?"           # 255
    orphera cluster-playbook manifests/kvm_vm_start.scala --config config/tst7.yaml
    sleep 40; ssh tst7 uptime                        # "up 0 min"
Expect as commented.  PASSED.

### E2. `--node-groups`
    orphera uptime --node-groups mons
    orphera install tree --node-groups mons; echo "exit=$?"
    orphera uptime --node-groups nosuchgroup
Expect: tst0-tst2 only, exit=0; unknown group gives
"Unknown node group(s): nosuchgroup. Defined in inventory: mons".  PASSED.
(Cosmetic: the full usage text is printed after that error.)

## F. VM configuration

### F1. Autostart on every hypervisor
    for h in gs1 gs2 gs3 st0; do echo "== $h"; ssh $h "virsh --connect qemu:///system list --all --autostart"; done
Expect: scala0 and tst0-tst7 all listed.  PASSED.

### F2. Stable by-id disk names
    for n in tst0 tst1 tst2; do echo "== $n"; ssh $n "ls -l /dev/disk/by-id/ | grep -E 'data[12]'"; done
Expect: scsi-0QEMU_QEMU_HARDDISK_<vm>-data1 and -data2 (data1 -> sdc, data2 -> sdb,
so sdX letters would have picked the wrong disk).  PASSED.
tst3-tst7: no data disks, no inventory change needed.

## G. Parallel VM build script
    manifests/par-mk-new-and-empty.sh -j 8 -q
Expect: all 8 VMs built, about 11 min.  PASSED (earlier session).

## H. `pipeline` verb (added after v0.1.163)

### H1. Working run
    orphera pipeline "install tree" --nodes tst0
Expect: one `[tst0]` prefix per line, step header `[1/1] install tree`,
summary `OK`, exit 0.  PASSED.

### H2. Refused step
    orphera pipeline "bootstrap" --nodes tst7
Expect: "'bootstrap' cannot be a pipeline step (allowed: ...)" before anything
runs.  PASSED.  (Printed the whole usage text afterwards; changed to a one-line
hint for bad arguments.)

### H3. Failing step
    orphera pipeline "install tree" "run false" "uptime" --nodes tst6,tst7 --quiet; echo "exit=$?"
Expect: step 2 fails on both nodes, step 3 never runs, summary shows both
FAILED "stopped at run false" with log paths and the last lines of each log,
exit=1; with --quiet only step headers and failures on the terminal.  PASSED.

## I. Rocky Linux support (branch `rocky-support`, 2026-10-09)

Test node: tst8 (Rocky 10, SELinux enforcing, NetworkManager, no firewalld
running), built from `config/tst8.yaml`. Needs `make rpm` (or `make release`
on a host with rpmbuild) for the agent `.rpm`.

### I1. Provision, bootstrap and upgrade in one chain
    orphera cluster-playbook manifests/kvm_vm_provision.scala @bootstrap manifests/gen_dist_upgrade.scala \
      --config config/tst8.yaml --ssh-user localadmin --forget-host-key
Expect: VM defined (q35, virtio-scsi), SSH up in about 20 s, the `.rpm` chosen
automatically and installed with `java-21-openjdk-headless`, then `dnf upgrade`
runs and ends `exit=0 success=true`.  PASSED.
(Before the q35 + virtio-scsi change the `generic` os-variant gave i440fx and an
LSI controller, and the guest dropped to an emergency shell.  Rocky 10 needs
host-passthrough for x86-64-v3.)

### I2. Verbs on a dnf host
    orphera version --nodes tst8
    orphera install tree --nodes tst8; echo "exit=$?"
    orphera install nosuchpkg --nodes tst8; echo "exit=$?"
Expect: version shown; install exit 0; bad package prints dnf's "No match" and
`success=false`, exit 1, no hang.  PASSED.

### I3. Agent under SELinux
    orphera run sh -c "rpm -q tree; getenforce; systemctl is-active firewalld; ss -ltn | grep 50051" --nodes tst8
Expect: Enforcing, agent listening on 50051.  PASSED.  (firewalld was inactive,
so the rpm's firewall-cmd step is not exercised yet.)

### I4. Spawn failure is explained
    orphera run nosuchcmd --nodes tst8; echo "exit=$?"
Expect: `FAILED: Cannot run program "nosuchcmd"...`, `success=false`, exit 1.  PASSED.
(`run` takes an argv with no shell: use `run sh -c "..."` for pipelines.)

### I5. `deploy-agent` upgrade with the rpm
    make rpm
    orphera deploy-agent --nodes tst8
Expect: rpm copied, detached install, "Confirmed running <new version>".  PASSED
(0.1.166 -> 0.1.167).

### I6. DNS on a NetworkManager host
    orphera cluster-playbook manifests/gen_set_dns.scala --config config/tst8.yaml
    orphera run sh -c "cat /etc/resolv.conf; ls /etc/NetworkManager/conf.d" --nodes tst8
Expect: no systemd-resolved, so a static `/etc/resolv.conf`; NetworkManager told
`dns=none` via `90-orphera-dns.conf`.  PASSED, including after `orphera reboot`
(static file and conf.d drop-in both survive; agent starts on boot; IP persists),
2026-10-09.  Note: a rebuilt VM has no DNS drop-in until the manifest is applied,
so apply it before the reboot check.

### I7. `network-apply`, NetworkManager backend - PASSED (tests A and B)
Profile `cloud-init enp1s0` in
`/etc/NetworkManager/system-connections/cloud-init-enp1s0.nmconnection`.
Needs the agent built with the known-good snapshot fix (commit 7007394).
Before deploying, make sure the file has the correct `address1=10.10.5.20/...`:
the agent snapshots whatever is there when it first starts.
Check: `ls /var/lib/orphera/network-backups/known-good` on the node.

A. Additive change, confirmed:

    orphera run sh -c "sudo cp -p /etc/NetworkManager/system-connections/cloud-init-enp1s0.nmconnection /root/nm-orig.bak && sudo sed -i '/^address1=/a address2=10.10.5.120/24' /etc/NetworkManager/system-connections/cloud-init-enp1s0.nmconnection" --nodes tst8
    orphera network-apply --nodes tst8 --timeout 30
    orphera run sh -c "ip -4 addr show enp1s0" --nodes tst8
Expect: "Confirmed - connectivity OK"; both 10.10.5.20 and 10.10.5.120 present.
Then restore the original file from `/root/nm-orig.bak` (`nmcli connection reload`,
`nmcli connection up 'cloud-init enp1s0'`).

B. Rollback:

    orphera run sh -c "sudo sed -i 's#^address1=10.10.5.20/#address1=10.10.5.99/#' /etc/NetworkManager/system-connections/cloud-init-enp1s0.nmconnection" --nodes tst8
    orphera network-apply --nodes tst8 --timeout 20
    sleep 40
    orphera version --nodes tst8
Expect: connectivity check fails, no confirm; about 20 s later the agent restores
the known-good file and tst8 answers on 10.10.5.20 again.
FAILED the first time (the node stayed on .99): the per-apply backup was taken
after the new file was pushed, so rollback restored the same broken file.  Fixed
by rolling back to a known-good snapshot (taken at first agent start and after
each confirmed apply).  Applies to the networkd backend too.
PASSED on tst8 (agent 0.1.171, 2026-10-09): the agent journal shows the bad
profile activated, then a second activation exactly 20 s later (the watchdog
restoring known-good); node back on 10.10.5.20 with the original `address1`.
Test A PASSED (address2 added, both addresses live, known-good refreshed with
address2, original restored and re-confirmed).  Known gap: `network-apply` prints only
"Reload applied, backup <id>" when the connectivity check fails; it does not say
that the confirm was skipped and a rollback will follow.
If a test VM becomes unreachable and the guest-agent channel exists:
`virsh domifaddr tst8 --source agent` on the hypervisor; the console now has a
root password (`passw0rd`, set by cloud-init).

I7b. Same two tests on the networkd backend (Ubuntu tst7, agent 0.1.171), PASSED
2026-10-09.  netplan's file lives in /run/systemd/network, so the test used files
in /etc/systemd/network: A) `50-orphera-test.netdev` (dummy `orph0`) +
`.network` (10.99.0.1/24), `network-apply --timeout 30`: interface created,
`known-good` refreshed with both files.  B) `05-orphera-bad.network` matching
enp1s0 with Address=10.10.5.98/24 (sorts before netplan's 10-, so it wins),
`network-apply --timeout 20`, `sleep 40`: networkd journal shows enp1s0
reconfigured from the bad file at 20:35:52 and from netplan's file again at
20:36:12 (watchdog restoring known-good); node back on 10.10.5.19.  Cleanup:
remove the files, `ip link del orph0`, `network-apply` (known-good empty again).

### I8a. Ceph lifecycle on Ubuntu with the Rocky-branch code
    orphera deploy-agent --nodes tst0,tst1,tst2
    manifests/test_ceph_lifecycle.sh 2>&1 | tee /tmp/ceph-lifecycle.log
Expect: `.deb` chosen automatically, teardown -> install -> add-mons -> add-osds,
every independent check passes.  Reported OK by the user (cephadm part), 2026-10-09.
MariaDB/Galera regression: pending.

### I8. Ubuntu regression with the Rocky changes - PASSED
    git pull && make release
    orphera deploy-agent --nodes tst7
    orphera install tree --nodes tst7; echo "exit=$?"
    orphera network-apply --nodes tst7 --timeout 30
    ls /var/lib/orphera/network-backups/known-good     (on tst7)
Expect: the `.deb` is chosen automatically, install exit 0, network-apply
confirms, known-good holds the `.network` files.
Result 2026-10-09 (tst7, agent 0.1.171): `install tree` exit 0 via apt; `install
nosuchpkgxyz` showed apt's E: line, node exit=100 success=false, CLI exit 1;
network-apply covered by I7b.

## J. MariaDB / WordPress regression and two environment faults (2026-10-09)

### J1. `wordpress_site.scala` on tst5 (Galera + haproxy/keepalived VIP)
    orphera cluster-playbook manifests/wordpress_site.scala
Expect: database and `wordpress_user` created on tst0, nginx + php-fpm installed
on tst5, WordPress downloaded, `wp-config.php` pointing at `10.10.5.100:3306`,
health check passes.  PASSED (reported by the user) after the two faults below
were fixed; Galera `wsrep_cluster_size` 3 and haproxy/keepalived active on
tst0-tst2 when checked.

Two faults found while getting there; neither was an Orphera bug:

- **gs3 storage.** First run failed on tst5 with `Input/output error` during
  `apt` ("FAILED - UNAVAILABLE: Network closed"; perl modules missing). `zpool
  status` on gs3 showed checksum errors on all four raidz1 drives (14-17 each)
  and 2 permanent data errors, although `zpool list` said ONLINE. The pool was
  rebuilt on new disks and the install then completed. If checksum errors reappear
  on the new pool, suspect what the disks share (HBA `mpt3sas`, cabling, memory),
  not the drives. Check `zpool status -v tank` after a scrub.
- **Duplicate IP for the VIP.** The health check timed out with HTTP 500
  ("Database Error"): from tst5 `10.10.5.100:3306` was refused. `arping` from
  tst5 showed two MACs answering for the VIP, tst0's (`52:54:00:49:99:02`) and a
  device outside our four hypervisors (`52:54:00:2c:c2:f8`), which answered first.
  scala0 sits behind the router (192.168.1.x), so its SSH to the VIP still reached
  tst0 and hid the conflict. Fixed by removing the address from the other device.
  Diagnosis commands: `arping -I enp1s0 -c 4 10.10.5.100` from a host on the
  segment; `ovs-appctl fdb/show br-net105`; pinning a MAC with `ip neigh replace
  ... nud permanent` needs `ip neigh del` afterwards (`flush` does not remove it).
  Idea, not built: have `mariadb_haproxy_keepalived.scala` check for a duplicate
  VIP address (`arping -D`) before starting keepalived.

### J2. Ceph lifecycle on Ubuntu with the Rocky-branch code
See I8a.  PASSED.

## Not tested
- Reboot of a hypervisor to confirm VMs autostart on their own.
- `--forget-host-key` in practice (scala0 does not use known_hosts for these hosts).
- Galera and VIP-failover tests.
- Rocky: firewalld rule from the rpm (firewalld was not running on tst8).
- Manifests other than gen_set_dns, gen_dist_upgrade, gen_packages and kvm_vm_provision on Rocky (ceph, etcd, galera, observability, wordpress are still Ubuntu-specific).

## K. Keystone (Kolla image under podman), single node with TLS - PASSED on tst6 (2026-10-10)
Written without a Scala toolchain, so the first run on scala0 is also the first
compile.  Prerequisites: Galera + VIP healthy (J, now on tst3-tst5, see L), agent on tst6.

    git pull && git checkout keystone
    orphera cluster-playbook manifests/keystone_single_node.scala
    manifests/test_keystone.sh 2>&1 | tee /tmp/keystone.log
Expect: all stages complete, `GET /v3/` over TLS returns 200, password auth
returns 201, plain HTTP and TLS without the CA do not return the API, the
`keystone` database exists on tst4, the `orphera-keystone` unit is active with
a `keystone` container on the Kolla image, a token lists the admin user, and a
second run keeps the same CA and fernet keys.
Most likely to need a fix on the first run: the image path/tag (the pull task
prints a hint; `registry`/`release`/`baseTag` are at the top of the manifest),
the SSL module being present in the image's Apache, and Kolla's `config.json`
behaviour (check `journalctl -u orphera-keystone` and /var/log/kolla/keystone).
Teardown: `orphera cluster-playbook manifests/keystone_teardown.scala`.

Result (2026-10-10): image `quay.io/openstack.kolla/keystone:2025.1-ubuntu-noble`
pulls from quay.io as assumed (the tst9 cache did not exist yet, so podman fell back).
Four fixes were needed on the way, all now in the manifest:
1. `fernet_setup` switches to the image's keystone uid before writing keys, so the
   host key directories must be chowned to that uid (root in the container is not
   enough; `--user root` alone did not help).
2. Keystone logged only to a file (`log_dir`), hiding errors; `use_stderr = true` added.
3. The image's Apache has mod_ssl but does not load it: `LoadModule ssl_module` added.
4. `kolla_start` appends `-DFOREGROUND` itself, so config.json's command is plain
   `apache2` / `httpd`.
Final run: 10 of 11 checks passed; the 11th was a test bug (Keystone prints
`"name": "admin"` with a space), fixed in test_keystone.sh. Second run kept the same CA
and fernet keys.

### K0. Kolla image cache on tst9 - PASSED (2026-10-10): tst6 pulls through the mirror (166 cache hits logged)
tst9 (10.10.5.21) is created by the user from `config/tst9.yaml` (check the
hypervisor: st0 is assumed), then:

    orphera @bootstrap ... tst9   (as for the other Ubuntu nodes)
    orphera cluster-playbook manifests/kolla_cache.scala
    orphera run sh -c "curl -s http://localhost:5000/v2/_catalog; sudo du -sh /var/lib/orphera/kolla-cache" --nodes tst9
Expect: the catalog lists `openstack.kolla/keystone` after the warm-up, and the
data directory is non-empty.  Then run K (keystone): its pull should come from
tst9 (`journalctl -u orphera-keystone` is not enough; check tst9's registry log:
`sudo podman logs kolla-cache | tail`).  Not tested: whether quay.io works as
the proxied remote for every Kolla repository (the main risk), and whether the
mirror path mapping `quay.io/openstack.kolla/x` -> `tst9:5000/openstack.kolla/x`
is what podman sends.

### K1. Cinder on tst10 - PASSED, 12/12 (2026-10-10), after the Ceph cipher fix below
Finding: the cluster runs a patched Ceph (19.2.6+, CVE-2025-30156) whose monitors
accept only the new `aes256k` cephx cipher by default, and `ceph -s` warned
`AUTH_INSECURE_CLIENT_KEY_TYPE` for the hand-made `aes` key. The Kolla cinder-volume
image carries Ubuntu's Ceph 19.2.3 client, which only knows `aes`. Result:
`RADOS permission denied` on connect, volumes ending in `error` (and the first test run's
`available` volume had no RBD image). Fix in cinder_single_node.scala: the Ceph stage runs
`ceph mon set auth_allowed_ciphers aes,aes256k` (test-cluster compromise, as OSSN-0108
recommends while clients are older). Re-run result: all 12 checks passed (volume created as an RBD image in pool `volumes`, deleted cleanly, second run idempotent). Remove the setting once the Kolla images ship Ceph >= 19.2.6:
`ceph mon set auth_allowed_ciphers aes256k`, then rotate client.cinder.
Needs: Galera + VIP, Ceph with OSDs, Keystone (K) re-applied so it serves the
CA chain, tst10 created from `config/tst10.yaml` and bootstrapped.

    orphera cluster-playbook manifests/keystone_single_node.scala   (picks up server-chain.crt)
    manifests/test_cinder.sh 2>&1 | tee /tmp/cinder.log
Expect: four units active, scheduler and volume `up` through the API, a 1 GB
volume created and visible as `volume-<id>` in `rbd ls volumes` on Ceph, deleted
cleanly, second run succeeds.
Likeliest first-run problems: the Kolla image names/tags and config.json
behaviour for cinder-api/scheduler/volume; `cephadm shell --mount` placing the
keyring at /mnt/cinder.keyring; RabbitMQ not reachable by the services at
10.10.5.22:5672; the Ceph pool replication size vs the OSD layout.
Teardown (destroys the `volumes` pool): `orphera cluster-playbook manifests/cinder_teardown.scala`.

### K3. apt cache on tst11 - 12/12 on 2026-10-10; with the concurrency check added (2026-10-11): 13 passed, 1 failed
The failing check: after `VfileUseRangeOps: 0`, ten simultaneous `apt update` runs all completed
(no hang) but the cache still logged 4 storage errors. Followed up: five more rounds (Release files
deleted from the cache first) all completed on all ten clients, so the hang is not reproduced; the
errors are apt-cacher-ng renaming a stale Release file to a per-second name, which collides for
same-second requests. `test_apt_cache.sh` now reports them as a warning, not a failure.

Needs the tst11 VM (`config/tst11.yaml`, hypervisor st0 assumed) with an agent, and
`tst11.ljalbinson.com` resolvable from the clients.

    git pull
    manifests/test_apt_cache.sh 2>&1 | tee /tmp/apt_cache.log
Expect: the cache answers on port 3142 from tst0; the client config uses the
auto-detect script and it prints `http://tst11.ljalbinson.com:3142`; the package
`figlet` downloaded from tst0 and then tst1 appears once as fetched into the cache and
twice as delivered (the cache's `apt-cacher.log`, `I` and `O` lines - this reading of the
log format is from memory and is the first thing to check if that assertion fails); the
`.deb` is stored under /var/cache/apt-cacher-ng; with the cache stopped the script
prints `DIRECT` and `apt-get update` on a client still succeeds; a second run of both
playbooks succeeds. Teardown: `orphera cluster-playbook manifests/apt_cache_teardown.scala`.
Most likely to need a fix on the first run: the drop-in config not being read (the
playbook's tunnelling check fails with a proxy refusal), and Scala string escaping in the
new manifests (first compile).

### K4. rpm cache on tst12 - PASSED, 11/11 (2026-10-11)
Needs the tst12 VM (`config/tst12.yaml`, Ubuntu, hypervisor st0 assumed) with an agent,
`tst12.ljalbinson.com` resolvable from tst8, and tst8 (Rocky 10) up.

    git pull
    manifests/test_rpm_cache.sh 2>&1 | tee /tmp/rpm_cache.log
Expect: `/healthz` answers on port 8080 from tst8; `dnf repolist` on tst8 shows the
`orphera-*` repositories and the stock Rocky repo files are renamed `*.orphera-off`;
`tree` downloaded twice (client copy cleaned between) shows one MISS then one HIT in
`/var/log/nginx/rpm-cache.log` on tst12; with nginx stopped `dnf makecache` still
succeeds via the official host; a second run of both playbooks succeeds.
Teardown: `orphera cluster-playbook manifests/rpm_cache_teardown.scala`.
Result: passed first time, no fixes needed. (Had it failed, the likeliest causes were: the Rocky 10 upstream path or repo file names
(check the warm-up stage's HTTP codes), nginx resolving through 127.0.0.53, `tree` already
installed or not in the enabled repos (the test removes it first), the log-count regex,
and Scala string escaping.)

### K5. Prometheus and Grafana on tst7 - PASSED (2026-10-11; reported as running without error, pass count not recorded)
Replaces the old step 4 gap in section L: nothing tested the monitoring stack itself.

    git pull
    manifests/test_observability_stack.sh 2>&1 | tee /tmp/observability_stack.log
    NO_APPLY=1 manifests/test_observability_stack.sh   # check only, apply nothing
Expect: prometheus healthy; targets up per job `haproxy=3/3 mysqld=3/3 node_exporter=7/7
prometheus=1/1` (14 in all); CPU metrics from 7 nodes; `wsrep_cluster_size` 3 on all three
Galera nodes; `mysql_up` 3; haproxy metrics from 3; Grafana database ok; a query through
Grafana's Prometheus datasource sees 14 targets; second run of both playbooks completes
with every target up. Needs ports 9090 and 3000 on tst7 reachable from scala0.
Most likely to need a fix on the first run: an exporter on a Galera node that lost its
database user in the rebuild (the extended playbook recreates it), the haproxy metric
name pattern, and Grafana's datasource proxy path for the installed version.

### K2. OpenStack clients on scala0 - PASSED (2026-10-10): token issued, cinder-scheduler and cinder-volume@ceph up (needed python3.12-venv installed first)
    utils/openstack-client-setup.sh
    source ~/.config/openstack/admin-openrc.sh
    openstack token issue && openstack service list && openstack volume service list
Expect: the script ends with "OK - the clients can authenticate"; `service list` shows
keystone and cinderv3; `volume service list` shows cinder-scheduler and
cinder-volume@ceph both up. Needs python3-venv on scala0 and tst6 resolvable from there.

### K6. Glance on tst13 - PASSED, 11/11 (2026-10-11), first run (branch `glance`)
    manifests/test_glance.sh 2>&1 | tee /tmp/glance.log
Expect: orphera-glance-api active; API answers on 9292; image list through Keystone; a test
image uploads, becomes `active`, appears as an RBD image in pool `images`, deletes and
disappears from the pool; the catalog lists `image`; second run completes. Prerequisites:
Galera + VIP, Ceph with OSDs, Keystone, agent on tst13. An earlier attempt failed at Keystone
registration with HTTP 500 because Galera had been wiped (keystone and cinder databases gone);
fixed by rerunning test_keystone.sh, test_cinder.sh and openstack-client-setup.sh. Teardown: `orphera cluster-playbook manifests/glance_teardown.scala`.

### K7. Cinder volume from a Glance image - PASSED (2026-10-11), after the privsep capability fix
    openstack image create my-image --file ubuntu.img --disk-format qcow2 --container-format bare --public
    openstack volume create --image my-image --size 5 test-from-image
A qcow2 image first failed (`FailedToDropPrivileges`); after `cinder_single_node.scala` gave
cinder-volume the SYS_ADMIN, NET_ADMIN and DAC_READ_SEARCH capabilities the volume reached
`available`. Raw images are cloned copy-on-write instead of downloaded.

### K8. Placement on tst13 - PASSED (2026-10-11), first run (branch `placement`)
    manifests/test_placement.sh 2>&1 | tee /tmp/placement.log
Expect: orphera-placement-api active; version document 200 on 8780; 401 without a token; a test
resource provider is created, read back, listed and deleted; the catalog lists `placement`;
Glance on tst13 still answers; second run completes. The first re-apply hit the dpkg lock
(unattended-upgrades on a fresh VM, apt exit 100 after the agent's 60 s wait); rerunning the
playbook once the upgrade finished completed. Prerequisites: Galera + VIP, Keystone,
agent on tst13. Teardown: `orphera cluster-playbook manifests/placement_teardown.scala`.

### K9. Horizon on tst13 - PASSED (2026-10-12), 7/7 (branch `horizon`)
    manifests/test_horizon.sh 2>&1 | tee /tmp/horizon.log
Expect: orphera-horizon active; root leads to the login page; a static file is served; admin
signs in; http://tst13.ljalbinson.com:8080/auth/login/ answers 200 from
scala0; Glance (9292) and Placement (8780) still answer; second run completes. Two things showed
up on the way: the install was killed (exit 143) when unattended-upgrades finished and
needrestart restarted the agent (see `disable_unattended_upgrades.scala`), and with no compute
service in the catalog the default landing page /project/ is a 403; with Nova registered it is a
500 instead (the Overview page needs a network service), so `user_home` was `/project/instances/`
until Neutron existed; with Neutron registered the override is gone and the default page works.
Prerequisite:
Keystone, agent on tst13. Teardown:
`orphera cluster-playbook manifests/horizon_teardown.scala`.

### K10. Nova control plane on tst14 - PASSED (2026-10-12), 19/19 (branch `nova`)
    manifests/test_nova.sh 2>&1 | tee /tmp/nova.log
Expect: the four orphera-nova-* units active; the version document on 8774; 401 without a token;
nova-scheduler and nova-conductor listed up by the API (RabbitMQ vhost `nova` on tst10 and the
Galera databases both work); a flavor created, read back and deleted; cell0 and cell1 mapped; the
catalog lists `compute`; the noVNC page answers on 6080; Placement (8780) and Glance (9292) on
tst13 answer from tst14; second run completes. The first run stopped on a `{{.Names}}` in a
command, which Orphera rendered as an empty template; fixed, then passed. Not yet checked: that
Horizon's Project dashboard stops returning a 403 now that the catalog has `compute`.
Prerequisites: Galera + VIP, Keystone, RabbitMQ on tst10 (Cinder), Placement, agent on tst14.
Teardown: `orphera cluster-playbook manifests/nova_teardown.scala` (not yet run).

### K11. Neutron on tst19 - PASSED (2026-10-12), 18/18, first run (branch `neutron`)
    manifests/test_neutron.sh 2>&1 | tee /tmp/neutron.log
Expect: the three orphera-neutron-* units active; enp2s0 has no IPv4 address and is a port of
br-provider while enp1s0 keeps 10.10.5.31; the version document on 9696; 401 without a token; the
Open vSwitch and DHCP agents alive through the API (RabbitMQ vhost `neutron` and Galera work); a
flat provider network plus a subnet created, read back and deleted; the catalog lists `network`;
Nova (tst14) and Glance (tst13) still fine; second run completes. Afterwards re-apply
`nova_single_node.scala` (adds `[neutron]` to nova.conf) and reload Horizon: Project > Overview and
Network should stop returning errors; then the `user_home` override can be dropped.
Prerequisites: Galera + VIP, Keystone, RabbitMQ on tst10, Nova, agent on tst19. Still to do: the
Nova re-apply and the Horizon check above.
Teardown: `orphera cluster-playbook manifests/neutron_teardown.scala` (not run; does not restore
the address enp2s0 had).

### K12. nova-compute on tst17 and tst18 - PASSED (2026-10-12), 24/24 (branch `nova-compute`)
    manifests/test_nova_compute.sh 2>&1 | tee /tmp/nova-compute.log
Expect: on each node orphera-nova-compute, orphera-neutron-openvswitch-agent and libvirtd active,
/dev/kvm present, libvirt answers, enp2s0 has no IPv4 address and is a port of br-provider while
enp1s0 keeps its address; both nova-compute services up and both OVS agents alive through the APIs;
tst17 and tst18 listed as hypervisors; Nova and Neutron still healthy; a CirrOS instance boots
(image uploaded to Glance, flat provider network with DHCP, config drive), its console shows the
login prompt, its Neutron port goes ACTIVE and it answers ARP from the DHCP namespace on tst19
for an address in 10.10.6.200-220 (`utils/check-guest-dhcp.sh`; the console cannot show the lease),
and everything is deleted again; second run completes. `SKIP_BOOT=1` skips the boot, `CIRROS_IMAGE=/path` avoids the download.
Prerequisites: Nova (tst14) and Neutron (tst19) healthy, no flat network left on `provider`, agents
on tst17 and tst18, nested KVM on gs2 and host-passthrough on the VMs.
Needed several fixes on the way, all in the playbook now: the libvirt socket (mode 0777, polkit off, a
host user with the container nova uid 42436 so libvirtd can resolve the caller), `[glance] cafile` in
nova.conf (the server create returned 500 DiscoveryFailure without it; re-apply Nova), os-vif over the
OVS loopback manager `ptcp:6640` (the unix socket is root-only), `/dev` mounted into the container
(console ptys). `utils/trace-dhcp.sh` captures DHCP on the three provider NICs when a lease is missing.
Teardown: `orphera cluster-playbook manifests/nova_compute_teardown.scala` (not run).
The lease check was changed on 2026-10-13: after a cold start of the fleet the console-based check
failed although the guest held its address (DHCP counters and ARP showed it). Not re-run as a whole;
see K13 for the run that proved the new check.

### K13. Master script on the existing stack, steps compute to boot - PASSED (2026-10-13), 9/9
    manifests/build_openstack_stack.sh --from compute
After a full power-off of the fleet. Cold-start notes: Galera had to be bootstrapped by hand
(`safe_to_bootstrap`, `galera_new_cluster` on one node, then start the others); no playbook does this
yet. Passed: compute, horizon, clients, smoke (network create/delete skipped because a flat network
already exists) and boot (CirrOS took 10.10.6.214 and was deleted). Not yet run: the other steps in
one go, and a from-scratch rebuild.

### K14. Galera cold start - PASSED (2026-10-13): clean stop and crash (SIGKILL) paths
    utils/galera-cold-start.sh --check      # reports what it would do
    utils/galera-cold-start.sh              # does it
Run 1, clean stop: `systemctl stop mariadb` on tst3-tst5, then the script: all three at seqno 19388,
bootstrapped tst3, joined tst4 and tst5, size 3 in 17 s.
Run 2, crash: `systemctl kill --kill-whom=main -s KILL mariadb` on the three nodes (systemd restarts
a killed mariadb after 5 s: `Restart=on-abort`). The first version of the script raced with those
restarts and ended in a split brain (tst3 alone, size 1; tst4 and tst5 together, size 2) because
`systemctl start` on a unit that is already running joins nothing, yet printed "joined". Found and
fixed: units that are starting, stopping or failed are stopped first (bounded: `--no-block`, 10 s,
then SIGKILL of the main process, since mariadb.service allows 900 s and a node looking for peers
ignores SIGTERM); a node left running with no cluster after a failed join (state Initialized, size 0)
is treated as down; running nodes that disagree about the cluster size are reported as a split brain
and left alone. Final run after the crash: three real positions (19732, recovered from the InnoDB log
by `mariadbd --wsrep-recover`), bootstrap, size 3 in 21 s.
Recovering the split by hand (tst3 was ahead by about 230 transactions, discarded): on the node of the
losing side `systemctl stop mariadb; rm /var/lib/mysql/grastate.dat; systemctl start mariadb`. The
first attempt failed with an IST protocol error after the SST (node stays active in state Initialized);
`systemctl restart mariadb` then joined it.
Not yet run: one node stopped while two run (it should only start that one); the preflight call from
`build_openstack_stack.sh` after a full power-off. The refusal cases (no position, differing UUIDs) and
`--bootstrap` were run against a fake `orphera` only.

### K15. OpenStack from scratch - PASSED (2026-10-14), 27/27 on the resumed build
    manifests/rebuild_openstack_from_scratch.sh        # step 1: the 13 VMs destroyed and recreated
    manifests/build_openstack_stack.sh --from prep     # resumed; finished in 1744 s, passed=27 failed=0
Step 1 (parallel VM rebuild: bootstrap, DNS, dist-upgrade, packages) completed on the 13 nodes. Step 2
stopped in `prep` the first time because the checkout in use still had the old
`disable_unattended_upgrades.scala` (it hit tst7, tst9, tst11 and tst12); after `git checkout main &&
git pull` and an orphera rebuild, the master script was resumed from `prep` and ran end to end: Ceph,
Galera, Keystone, Cinder, Glance, Placement, Nova, Neutron, compute, Horizon, clients, smoke checks and
the CirrOS boot (10.10.6.205, deleted). Not run in this pass: `--tests`, `--wordpress`, and the master
script with `--with-caches`. The rebuild script itself was therefore only run to the end of step 1; its
own step 2 call has not been seen to finish.
### K17. Master script, `prep` then `ceph` to `nova` to `boot` on a rebuilt stack - PASSED (2026-10-14), 14/14
    manifests/build_openstack_stack.sh --from prep   (resumed with --from ceph, then --from nova)
Run in pieces because of three faults, all fixed on main: `prep` hit tst7, tst9, tst11 and tst12, which
the stack does not create (now only the stack's own nodes, via `--config` `nodes:`); `playbook()` used
`$1` after `shift`; Nova's conductor exited with HTTP 401 because the `nova` Keystone user was created
after the services started (Nova and Neutron now register first). The last pass (nova to boot) took
734 s, ended passed=14 failed=0, and CirrOS booted on KVM, took 10.10.6.216 and was deleted.

### K16. Typed Kolla container (`kolla/Container.scala`), placement on tst13 - PASSED (2026-10-14)
    sbt "orchestrator/Test/runMain orphera.orchestrator.kolla.ContainerCheck"
    manifests/test_placement.sh                        # passed=10 failed=0, second run included
`placement_single_node.scala` now builds its image reference, podman arguments, systemd unit and start
check from a `Container` value (`Release`, `Base`, `Image`, `Mount` with `Access`) instead of strings.
The branch compiled under `-Werror` on scala0, and the placement playbook ran twice against tst13:
unit written, service started, registered in Keystone, resource providers listed (tst17 and tst18).
Glance was converted next (branch `typed-glance`, 2026-10-14): `manifests/test_glance.sh` passed=11
failed=0 with the second run completing. Nova followed (branch `typed-nova`): four containers from one helper, `manifests/test_nova.sh` passed=19
failed=0 with the second run completing; the scheduler and conductor reported in. Neutron followed (branch `typed-neutron`): the server and the two privileged agents, with `Container` gaining
`privileged`, `hostPid`, `after`, `preStart` and three mount modes; `manifests/test_neutron.sh` passed=18
failed=0 with the second run completing and both agents alive. Keystone followed (branch `typed-keystone`): `manifests/test_keystone.sh` passed=11 failed=0, CA unchanged
and fernet keys still present after the second run; a failed start now also prints the journal. Cinder followed (branch `typed-cinder`): the API, scheduler and volume services with their
three capabilities, and RabbitMQ as an `ExternalImage`; `manifests/test_cinder.sh` passed=12 failed=0, scheduler and
volume up after the second run. The first attempt failed to compile because my conversion deleted values the
playbook uses; redone from `main`. Horizon followed (branch `typed-horizon`): `manifests/test_horizon.sh` passed=7 failed=0 with the admin
sign-in working. Its first run failed only because `test_keystone.sh` had wiped Keystone (see K18). The Kolla cache followed (branch
`typed-kolla-cache`): the registry as an `ExternalImage` publishing port 5000 (`Network.Published`), run on a new tst9
(moved to gs3; tst7, tst9, tst11 and tst12 did not exist on any hypervisor). `orphera cluster-playbook
manifests/kolla_cache.scala` completed all stages, the keystone image was pulled through the cache and the
catalog listed `openstack.kolla/keystone`. Finally `nova_compute.scala` (branch `typed-compute`; `Container` gained `wants`):
`manifests/test_nova_compute.sh` passed=25 failed=0, nova-compute and the OVS agent up on tst17 and tst18, 2 hypervisors.
Every Kolla playbook now uses the typed `Container`.

### K18. `test_keystone.sh` tears Keystone down first (lesson from 2026-10-14)
Of the per-service test scripts only `test_keystone.sh` starts with a teardown playbook; the others run
their playbook twice. The Keystone teardown drops the keystone database and the test CA, which unregisters
every other OpenStack service and leaves the other services and the CLI on scala0 with a CA that no longer
matches (CLI: "self-signed certificate in certificate chain"; Horizon: 403 Unauthorized after login).
Running it on the typed-keystone branch broke the stack this way; recovery is
`manifests/build_openstack_stack.sh --from keystone` (run 2026-10-14: passed=19 failed=0 in 820 s, CirrOS
booted on 10.10.6.213). Run it on a stack you can rebuild.

### K19. Rebuild from scratch on `main`, build and all eight test scripts - PASSED (2026-10-15)
`manifests/rebuild_openstack_from_scratch.sh --tests` on `main`, every playbook on the typed container, in one run
with no manual resume: the build finished in 1404 s, passed=27 failed=0 (smoke checks, CirrOS booted on KVM and took
DHCP address 10.10.6.216), then the test scripts, all PASS: keystone (118 s), cinder (164 s), glance (91 s), placement
(70 s), nova (198 s), neutron (225 s), nova_compute (297 s), horizon (76 s). Whole run 4010 s (about 67 minutes).
`--wordpress` was not used (tst7 not rebuilt). `test_keystone.sh` ran first, as the script orders it, and the later
scripts recovered: each re-runs its own playbook, which refetches Keystone's CA and re-registers its service (see K18).
Earlier the same day a first attempt stopped at the compute stage when tst18's pull of the `nova-compute` image got HTTP
500 from the Kolla cache on tst9, which had failed to fetch one blob from `cdn01.quay.io`; the resume with
`build_openstack_stack.sh --from compute` passed 10/10 (267 s). Image pulls have no retry, and the failure message
points at the `Image(...)` values; both worth fixing.

### K20. Image pull retry (`kolla/Pull.scala`) - PASSED (2026-10-15)
Every playbook that pulls images now uses `pull_retry` (three tries, 15 s apart, podman's error shown, then a message
naming the `Image(...)` to check). `ContainerCheck` 40/40, including four checks that run the generated shell function
against a fake `podman` (works first time; fails twice then works; always fails, gives up after three with the message;
unsafe hint text rejected). On nodes: `manifests/test_placement.sh` passed=10 (single image) and
`manifests/test_cinder.sh` passed=12 (loop of four images, RabbitMQ from Docker Hub), each pull succeeding on the first
try. The retry path itself has not been exercised on a node because no pull failed during the runs.

### K21. `ContainerName` and `TcpPort` as opaque types (`kolla/Ids.scala`) - PASSED (2026-10-15)
Container names and port numbers are checked when built: a name must be lowercase letters, digits and dashes, a port
1 to 65535. `ContainerCheck` 42/42. The first compile failed: the `Port.apply` taking plain numbers had the same erasure
as the one taking `TcpPort` (an opaque `Int` is an `Int` at run time); fixed with `@targetName("fromNumbers")`. On nodes:
`manifests/test_placement.sh` passed=10 (literal name) and `manifests/test_nova.sh` passed=19 (name from a variable,
four containers in one playbook). The other manifests (cinder, glance, horizon, keystone, neutron, compute, Kolla
cache) were converted by script and checked by a search for every `Container(` construction; they are compiled by their
next run.

## L. Node reassignment (2026-10-10) - steps 1-3 and 5 done; step 4: run-log test passed, Prometheus/Grafana stack on tst7 NOT YET RUN
New roles: tst0-tst2 Ceph; tst3-tst5 Galera + haproxy + keepalived (VIP
10.10.5.100); tst6 Keystone; tst7 WordPress and Prometheus/Grafana; tst8 Rocky;
tst9 Kolla cache; tst10 Cinder; tst11 apt cache; tst12 rpm cache (K4, passed).  The manifests, tests and inventory comments were
retargeted; the results recorded in J were obtained on the OLD layout (Galera on
tst0-tst2, WordPress on tst5, monitoring on tst6).

Moving over, in this order (the old VIP must go before the new one comes up, or
two hosts will answer for 10.10.5.100 again):

1. Tear down the old layout with the OLD manifests, from a second checkout of `main`
   (the retargeted teardowns would look at the new nodes):

       git worktree add ../orphera-old main
       cd ../orphera-old
       orphera cluster-playbook manifests/wordpress_teardown.scala
       orphera cluster-playbook manifests/mariadb_galera_teardown.scala
       orphera cluster-playbook manifests/etcd_teardown.scala      (optional: clears etcd on tst0-tst4)
       orphera run sh -c "sudo systemctl disable --now prometheus grafana-server prometheus-node-exporter" --nodes tst6
       cd ../orphera-pkg
   (`main` still has the old node names; the inventory is unchanged for tst0-tst2.)
2. Bring up the new Galera on tst3-tst5 and the VIP:

       orphera cluster-playbook manifests/mariadb_galera_cluster.scala
       orphera cluster-playbook manifests/mariadb_haproxy_keepalived.scala
       manifests/test_mariadb_galera.sh
       manifests/test_vip_failover.sh
3. WordPress on tst7: `orphera cluster-playbook manifests/wordpress_site.scala`.
4. Observability: `manifests/test_observability.sh` (the structured run log; it runs against
   tst0 and is independent of the monitoring node - PASSED 26/26, 2026-10-11), and on tst7
   `observability_stack.scala` / `observability_extended.scala` (monitoring node
   now tst7; scrape targets tst0-tst6).
5. Keystone on tst6 (K) and Cinder on tst10 (K1), which use the new Galera.
Result, steps 1-2 (2026-10-10): Galera rebuilt on tst3-tst5, `test_mariadb_galera.sh`
11 passed; haproxy+keepalived applied (VIP on tst3, priority 101/100/99);
`test_vip_failover.sh` 6 passed, 0 failed (VIP moved tst3 -> tst4 in about 5s after
haproxy was stopped on tst3, reachable throughout, tst3 reclaimed it about 5s after
haproxy came back). The first drill attempt failed only because the probe host tst7
had no mariadb client; the drill now uses tst7 only when it has one, otherwise a
Galera node that does not hold the VIP.

Result, steps 3 and 5 (2026-10-10): WordPress applied on tst7 (reported running; its
test script was not run). Keystone on tst6 (K) 11/11 and Cinder on tst10 (K1) 12/12 both
pass on the new Galera/VIP. Also added and passed that day: the Kolla cache on tst9 (K0),
OpenStack clients on scala0 (K2) and the apt cache on tst11 (K3, 12/12).
Also passed 2026-10-11: K4 rpm cache (11/11) and `test_observability.sh` (26/26; it checks the
JSON run log on tst0, not the Prometheus/Grafana stack).
Step 4's monitoring stack passed (K5). Also 2026-10-11: `cephadm_install.scala` re-run completed ok (its cephx-cipher task included), and Keystone (K) and Cinder (K1) were rebuilt and passed again after an accidental Galera wipe by the old `test_wordpress.sh` (now `rebuild_galera_and_wordpress.sh`; the new non-destructive `test_wordpress.sh` PASSED and the site is reachable).
Nothing else remains from section L.

Expect: each of the tests above passes as before.  Not changed: the Ceph
manifests (tst0-tst2).  `etcd_grow_cluster.scala` still names tst3/tst4 as the
members it adds; those are Galera nodes now, so that test is retired until it is
pointed at other nodes.

### K22. `Resource` type class and check mode (`Resource.scala`) - PASSED (2026-10-15)
One description of a file on a node gives both the task that writes it and the task that checks it. `ContainerCheck` 49/49
(42 plus 7 for `Resource`); compiled first time after the merge with `main`. On tst13, `ORPHERA_CHECK=1 orphera
cluster-playbook manifests/placement_single_node.scala` printed `in sync` for the unit as written; with `# drift` appended
it failed with a unified diff and exit 1; with the unit moved away it failed with `MISSING` and exit 2. The normal run
then restored the unit and Placement came back healthy (2 resource providers). Only the placement-api unit is checked so
far.

### K23. Typed configuration files from the image's option list (`conf/`) - PASSED (2026-10-15)
`placement.conf` is a `ConfFile` checked against `catalogues/placement-api-2025.1-ubuntu-noble.json`, the output of
`oslo-config-generator --format json` run in the image (`manifests/dump_oslo_options.sh`; 447 options in 33 sections).
`ConfCheck` 33/33. `manifests/test_placement.sh` passed=10 with the typed file, and the re-apply passed. `ORPHERA_CHECK=1`
reported the unit and `placement.conf` in sync. A misspelt `www_authenticate_url` stopped the build with a suggestion before
any task ran. Found by running it: oslo accepts `-` and `_` alike (`log-dir`); one option can come from two namespaces;
`[keystone_authtoken]` also takes auth-plugin options (`auth_url`, `username`, `password`) that the dump does not contain,
so it is an open section, which refuses near misses of known options but accepts other names (a typo in a plugin key more
than two edits from any known name is not caught); and the plugin key `auth_url` is one edit from the old name `auth_uri`,
so old names are not compared. Not done: dumping the keystoneauth1 plugin options, other services' catalogues, secrets
hidden in the logged check script and drift diff (the task log prints the whole script, password included).

### K24. Secrets kept out of check output and the run log (`Resource.checkTask`, `Secrets`) - PASSED on a node (2026-10-15)
A file check sends a SHA-256 of the expected file, not its text; on a mismatch it diffs with secret values replaced by
`<redacted>` on both sides, and says when only a secret value differs. On tst13, `ORPHERA_CHECK=1` with the Placement service
password changed on the node failed with "a secret value differs (values are not shown)", and neither password appeared in
the logged command or the output. `manifests/test_placement.sh` passed=10 with masking on; its log held none of the three
passwords or the changed one, and six `<redacted>` lines where they were echoed before (database creation, the helper,
registration). A search for the loose pattern `orphera-test` finds six harmless lines (the CA file name, the helper's
provider name, in two runs). Unit checks (`ConfCheck`, `ContainerCheck`) were not reported at the time of the merge.
Not covered: the node's agent logs every command it runs (`[DEBUG-CMD]` in `CommandRunner.scala`), and a running command's
arguments are visible on the node while it runs; commands that embed a password are still in both.

### K25. Encrypted secret store (`SecretStore.scala`, `orphera secrets`) - PASSED (2026-10-16)
`age` installed on scala0, `orphera secrets init`, then `manifests/seed_test_secrets.sh` seeded 18 names. `SecretsCheck`
25/25 after one compile fix (the test compared `Either[String, Option[String]]` with `Either[String, String]`).
`manifests/test_placement.sh` passed=10 reading its three passwords from the store; `~/.config/orphera/age.key` and
`secrets.age` are mode 600. The loose pattern `orphera-test` printed 6 in the log (harmless names, as in K24); the precise
pattern `orphera-test-[a-z-]*-(password|key)` printed 0 and `<redacted>` appeared 6 times. Not covered: a generated
password on an existing deployment (it does not match the database users that exist; seed first).

### K26. Typed configuration for Keystone, Glance, Cinder, Nova and Neutron - PASSED (2026-10-16)
`ConfCheck` 49/49, after one fix found by running the playbooks: glance and cinder write a `[ceph]` section that is not in
their catalogues, and an open section had to be accepted even when the image does not declare it at all. Playbook tests:
keystone 11, glance 11, cinder 12, placement 10, nova 19, neutron 18, all `failed=0`.

### K27. Passwords from the store in every playbook - PASSED (2026-10-16)
Keystone, Glance, Cinder, Nova, Neutron, Nova compute, Horizon, Galera, haproxy, WordPress and extended observability read
their passwords with `Secrets.get`/`Secrets.secret`. After the OpenStack conversions: keystone 11, glance 11, cinder 12,
placement 10, nova 19, neutron 18, nova_compute 25, horizon 7, all `failed=0`, no password pattern in any log.
`build_openstack_stack.sh --only galera` passed=4 and `test_vip_failover.sh` 6/6 for the Galera and haproxy conversions.

### K28. `Secret`, a value that does not print (`Secret.scala`) - PASSED (2026-10-16)
`SecretsCheck` 31/31 (35 once the agent token checks were added); Placement passed=10 with the type, and the eight OpenStack
playbooks above passed again after every manifest was converted (`.reveal` at each use). A site missed in the conversion
would be a compile error; none was.

### K29. `test_mariadb_galera.sh` wiped the Galera cluster - RECORDED (2026-10-16)
The test runs `mariadb_galera_teardown` first (purges MariaDB, haproxy and keepalived, removes `/var/lib/mysql`). It was run as a
check of the Galera password change on the belief that it only applied playbooks; every OpenStack database and the VIP
were lost. `manifests/build_openstack_stack.sh --from galera` rebuilt everything: passed=23 failed=0 in 724 s, including a
CirrOS instance booted on KVM with a DHCP lease. The script now says it is destructive and refuses to run without
`ORPHERA_ALLOW_DESTRUCTIVE=1`. Lesson: read a test's header before running it on the lab.

### K30. tst7 rebuilt; WordPress and Prometheus/Grafana - PASSED (2026-10-16)
tst7 was not in the fleet. `manifests/mk-new-and-empty.sh 7` recreated it.
`test_wordpress.sh` passed=11 and `test_observability_stack.sh` passed=13, with no password pattern in either log. This ran
the `wordpress/db` and `observability/mysqld-exporter` conversions.

### K31. Credentials: agent token and CA replaced - IN PROGRESS (2026-10-16)
Found: the agents accepted the built-in development token (no `ORPHERA_TOKEN` was set anywhere), and the CA and server
private keys had been committed to a public repository. Done: `orphera/agent-token` generated; `utils/set-agent-token.sh`
set it on 19 of 24 nodes (tst8, tst11, tst12, tst15 and tst16 were not running); `orphera version --nodes tst7` works with
the stored token and fails with the built-in one. A new CA and server certificate were made with `make certs`
(`openssl verify` OK) and 16 of the 19 reachable nodes (tst0-tst7, tst9, tst10, tst13, tst14, tst17-tst19, st0) report
agent 0.1.195 with them. Not yet: gs1, gs2 and gs3 report `unreachable` (gs1's package system is broken by a failed dkms
build of the Mellanox OFED module for kernel 6.8.0-147, which stops `apt-get install default-jre`; the agent can be
installed there with `dpkg -i`); the old-CA rejection test, run with `ORPHERA_HOME` pointing at a directory that holds the
old `certs/ca.crt`, which should print `unreachable` for a node with the new certificate; the five down nodes; deleting
the old keys. A first attempt at that test used the current directory and proved nothing, because the `orphera` wrapper
changes into `$ORPHERA_HOME` before it starts.
