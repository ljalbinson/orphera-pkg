// SPDX-License-Identifier: Apache-2.0

import orphera.orchestrator.*
import orphera.orchestrator.ClusterPlaybookDsl.*

// Turns off automatic package upgrades on the Ubuntu test VMs.
//
// Why: unattended-upgrades runs for several minutes after a VM first boots (and
// daily after that). While it runs it holds the dpkg lock, so any Orphera
// package task fails or waits, and when it finishes needrestart restarts
// services, including orphera-agent, which kills whatever task the agent was
// running at that moment (exit 143). Seen on tst13 (302 pending packages).
//
// What it does on each node, without stopping an upgrade that is already
// running part-way (interrupting dpkg is worse than waiting):
//   - writes /etc/apt/apt.conf.d/20auto-upgrades with both periodic settings
//     at 0 (no automatic package-list updates, no unattended upgrades);
//   - stops, disables and masks apt-daily.timer, apt-daily-upgrade.timer and
//     unattended-upgrades.service, so none of them can start again;
//   - fails the stage if a timer is still active afterwards.
// If an upgrade is running when this is applied it says so; it finishes on its
// own, and the node is quiet afterwards.
//
// Updates are then only applied when you ask for them (the gen_dist_upgrade /
// apt-dist-upgrade playbooks, or `orphera install ... --update-cache`). The VM
// provisioning scripts (kvm_native_vm_provision.scala, kvm_zfs_vm_provision.scala)
// write the same setting at first boot, so new VMs never start an upgrade.
//
// Nodes: the Ubuntu test VMs. tst8 (Rocky) and the hypervisors gs1-gs3 and st0
// are left alone; add them to `nodes` if wanted. To undo on a node:
//   rm /etc/apt/apt.conf.d/20auto-upgrades
//   systemctl unmask apt-daily.timer apt-daily-upgrade.timer unattended-upgrades.service
//   systemctl enable --now apt-daily.timer apt-daily-upgrade.timer unattended-upgrades.service
// Safe to re-run.
object disable_unattended_upgrades extends OrpheraClusterPlaybook:

  private val nodes = List(
    "tst0", "tst1", "tst2", "tst3", "tst4", "tst5", "tst6", "tst7", "tst9",
    "tst10", "tst11", "tst12", "tst13", "tst14", "tst17", "tst18", "tst19"
  )

  private val disableScript =
    """set -e
      |mkdir -p /etc/apt/apt.conf.d
      |cat > /etc/apt/apt.conf.d/20auto-upgrades <<'CONF'
      |// Managed by Orphera (disable_unattended_upgrades.scala): no automatic
      |// package-list updates and no unattended upgrades.
      |APT::Periodic::Update-Package-Lists "0";
      |APT::Periodic::Unattended-Upgrade "0";
      |CONF
      |# Timers first, so nothing new starts; the service units are masked without
      |# --now so an upgrade already running is not interrupted part-way.
      |systemctl stop apt-daily.timer apt-daily-upgrade.timer 2>/dev/null || true
      |systemctl disable apt-daily.timer apt-daily-upgrade.timer 2>/dev/null || true
      |systemctl mask apt-daily.timer apt-daily-upgrade.timer unattended-upgrades.service 2>/dev/null || true
      |if pgrep -f '^/usr/bin/python3 /usr/bin/unattended-upgrade$' >/dev/null; then
      |  echo "NOTE: an unattended upgrade is still running; it will finish on its own (it may restart services, including orphera-agent)"
      |else
      |  echo "no upgrade running"
      |fi
      |echo "automatic upgrades disabled"""".stripMargin

  private val verifyScript =
    """FAIL=0
      |for t in apt-daily.timer apt-daily-upgrade.timer; do
      |  if systemctl is-active --quiet "$t"; then echo "$t is still active" >&2; FAIL=1; fi
      |done
      |grep -q 'Unattended-Upgrade "0"' /etc/apt/apt.conf.d/20auto-upgrades || { echo "20auto-upgrades not as expected" >&2; FAIL=1; }
      |[ "$FAIL" = "0" ] && echo "verified: timers inactive, periodic upgrades off"
      |exit $FAIL""".stripMargin

  val playbook: ClusterPlaybook =
    clusterPlaybook("disable-unattended-upgrades")(
      stage("disable-automatic-upgrades", nodes*)
        .task("turn off unattended-upgrades and the apt timers")(
          Task.RunCommand(List("sh", "-c", disableScript), timeoutSeconds = 60)
        )
        .task("verify")(
          Task.RunCommand(List("sh", "-c", verifyScript), timeoutSeconds = 30)
        )
        .build
    )
