# Orphera tests run for v0.1.163 (all passed, 2026-10-08)

Run from `~/orphera-pkg` on scala0 after `git pull && make orpheracli`.
Test VMs: tst0-tst7 (tst0-tst2 have OSD data disks; tst7 is the spare).

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

## Not tested
- Reboot of a hypervisor to confirm VMs autostart on their own.
- `--forget-host-key` in practice (scala0 does not use known_hosts for these hosts).
- Galera and VIP-failover tests.
