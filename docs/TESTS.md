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
`dns=none` via `90-orphera-dns.conf`.  PASSED.  NOT YET CHECKED: that the file
survives a reboot (`orphera reboot --nodes tst8`, then `cat /etc/resolv.conf`).

### I7. `network-apply`, NetworkManager backend - PENDING
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
If a test VM becomes unreachable and the guest-agent channel exists:
`virsh domifaddr tst8 --source agent` on the hypervisor; the console now has a
root password (`passw0rd`, set by cloud-init).

### I8a. Ceph lifecycle on Ubuntu with the Rocky-branch code
    orphera deploy-agent --nodes tst0,tst1,tst2
    manifests/test_ceph_lifecycle.sh 2>&1 | tee /tmp/ceph-lifecycle.log
Expect: `.deb` chosen automatically, teardown -> install -> add-mons -> add-osds,
every independent check passes.  Reported OK by the user (cephadm part), 2026-10-09.
MariaDB/Galera regression: pending.

### I8. Ubuntu regression with the Rocky changes - PENDING (rest of it)
    git pull && make release
    orphera deploy-agent --nodes tst7
    orphera install tree --nodes tst7; echo "exit=$?"
    orphera network-apply --nodes tst7 --timeout 30
    ls /var/lib/orphera/network-backups/known-good     (on tst7)
Expect: the `.deb` is chosen automatically, install exit 0, network-apply
confirms, known-good holds the `.network` files.

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
