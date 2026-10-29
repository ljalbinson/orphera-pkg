// SPDX-License-Identifier: Apache-2.0

import orphera.orchestrator.*
import orphera.orchestrator.ClusterPlaybookDsl.*

// DRAFT. VirtualBMC on the hypervisors: an IPMI endpoint for each libvirt VM, so that a VM can be powered on, off,
// cycled and told to boot from the network with ordinary IPMI tools (ipmitool, and OpenStack Ironic's ipmi driver).
// This installs the daemon; utils/vbmc-vm.sh makes, starts and removes the endpoint of each VM.
//
// What it does on each hypervisor (default gs1, gs2, gs3, st0; VBMC_HOSTS=gs2,gs3 chooses others):
//   - packages: python3-venv, python3-libvirt (VirtualBMC needs the libvirt bindings; the venv uses the system
//     ones), ipmitool
//   - a virtualenv /opt/virtualbmc with `virtualbmc` from pip (VBMC_SPEC sets the requirement, default
//     `virtualbmc`; set PIP_INDEX_URL in the environment of the run to use a mirror)
//   - /etc/orphera-vbmc.conf (root only): VBMC_USER=admin and VBMC_PASSWORD from the secret `vbmc/password`.
//     utils/vbmc-vm.sh reads it on the hypervisor, so the password never travels in a command
//   - the unit orphera-vbmcd.service running `vbmcd --foreground` as root with HOME=/root (VirtualBMC keeps its
//     records in ~/.vbmc and starts again, on restart, the endpoints that were running)
//
// Limits of VirtualBMC to know about: IPMI 2.0 over UDP only, one UDP port per VM (utils/vbmc-vm.sh uses 6230 plus
// the VM's number); power, reset and boot device only, no serial-over-LAN, no sensors; a boot device change
// (`chassis bootdev pxe`) sets the boot order of the whole domain, so it takes the place of the per-disk boot order
// that virt-install wrote: set it back with `chassis bootdev disk`.
//
// Check: `utils/vbmc-vm.sh list`. Test: manifests/test_virtualbmc.sh. Undo: manifests/virtualbmc_teardown.scala.
// Not compiled where this was written; the first run on scala0 is the first compile.
object virtualbmc extends OrpheraClusterPlaybook:

  private def env(name: String, default: String): String =
    sys.env.get(name).map(_.trim).filter(_.nonEmpty).getOrElse(default)

  private val hosts: List[String] =
    env("VBMC_HOSTS", "gs1,gs2,gs3,st0").split(",").map(_.trim).filter(_.nonEmpty).toList
  private val spec = env("VBMC_SPEC", "virtualbmc")
  private val user = "admin"
  private val password = Secrets.secret("vbmc/password")

  private val waitAptIdleScript =
    """for i in $(seq 1 180); do
      |  if pgrep -f '^/usr/bin/python3 /usr/bin/unattended-upgrade$' >/dev/null || pgrep -x apt-get >/dev/null || pgrep -x apt >/dev/null || pgrep -x dpkg >/dev/null; then
      |    sleep 5
      |  else
      |    echo "apt and dpkg are idle"
      |    exit 0
      |  fi
      |done
      |echo "apt/dpkg still busy after 15 minutes (unattended-upgrades?)" >&2
      |exit 1""".stripMargin

  // --system-site-packages: python3-libvirt comes from apt, and building libvirt-python from source needs the
  // libvirt development files.
  private val venvScript =
    s"""set -e
       |if [ ! -x /opt/virtualbmc/bin/python ]; then
       |  python3 -m venv --system-site-packages /opt/virtualbmc
       |fi
       |/opt/virtualbmc/bin/pip install --upgrade '$spec'
       |/opt/virtualbmc/bin/python -c "import libvirt, virtualbmc; print('libvirt and virtualbmc import ok')"
       |echo "virtualbmc: $$(/opt/virtualbmc/bin/pip show virtualbmc | sed -n 's/^Version: //p')"""".stripMargin

  private val unit: String =
    """[Unit]
      |Description=VirtualBMC daemon (IPMI endpoints for libvirt VMs)
      |After=network-online.target libvirtd.service
      |Wants=network-online.target
      |
      |[Service]
      |Environment=HOME=/root
      |ExecStart=/opt/virtualbmc/bin/vbmcd --foreground
      |Restart=on-failure
      |RestartSec=5
      |
      |[Install]
      |WantedBy=multi-user.target
      |""".stripMargin

  private val startScript =
    """systemctl daemon-reload
      |systemctl enable --now orphera-vbmcd.service
      |for i in $(seq 1 20); do
      |  if HOME=/root /opt/virtualbmc/bin/vbmc list >/dev/null 2>&1; then echo "vbmcd answers"; exit 0; fi
      |  sleep 2
      |done
      |echo "vbmcd does not answer - see 'journalctl -u orphera-vbmcd'" >&2
      |exit 1""".stripMargin

  val playbook: ClusterPlaybook =
    clusterPlaybook("virtualbmc")(
      stage("install-virtualbmc", hosts*)
        .task("wait for apt and dpkg to be idle")(
          Task.RunCommand(List("sh", "-c", waitAptIdleScript), timeoutSeconds = 960)
        )
        .task("install the packages")(
          Task.Install(
            packages = List("python3-venv", "python3-libvirt", "ipmitool"),
            updateCache = true
          )
        )
        .task("install VirtualBMC into /opt/virtualbmc")(
          Task.RunCommand(List("sh", "-c", venvScript), timeoutSeconds = 600)
        )
        .task("write /etc/orphera-vbmc.conf")(
          Task.WriteFile(
            content = s"VBMC_USER=$user\nVBMC_PASSWORD='${password.reveal}'\n",
            dest = "/etc/orphera-vbmc.conf",
            owner = "root",
            group = "root",
            mode = 384 // 0600
          )
        )
        .task("write orphera-vbmcd.service")(
          Task.WriteFile(
            content = unit,
            dest = "/etc/systemd/system/orphera-vbmcd.service",
            owner = "root",
            group = "root",
            mode = 420 // 0644
          )
        )
        .task("start vbmcd")(
          Task.RunCommand(List("sh", "-c", startScript), timeoutSeconds = 120)
        )
        .build
    )
