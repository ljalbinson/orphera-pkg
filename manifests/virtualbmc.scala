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
//   - /etc/orphera-vbmc.conf (root only): VBMC_USER=admin and VBMC_PASSWORD, the first 16 characters of the secret
//     `vbmc/password` (IPMI 2.0 passwords are at most 20 bytes, and the generated secret is longer).
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
    env("VBMC_HOSTS", "gs1,gs2,gs3,st0")
      .split(",")
      .map(_.trim)
      .filter(_.nonEmpty)
      .toList
  private val spec = env("VBMC_SPEC", "virtualbmc")
  private val ipmiUser = "admin"
  private val password = Secrets.secret("vbmc/password")

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

  // The configuration, the unit and the running daemon as one description. The unit file notifies the service,
  // so vbmcd is restarted only when the unit changed; the daemon is also enabled and running. The check
  // waits for vbmcd to answer, and only runs when it does not answer yet.
  private val vbmcdPlan: ApplyPlan =
    import Resources.*
    val conf = secretFile(
      "/etc/orphera-vbmc.conf",
      Secret(
        s"VBMC_USER=$ipmiUser\nVBMC_PASSWORD='${password.reveal.take(16)}'\n"
      ),
      mode = "0600",
      owner = "root",
      group = "root"
    )
    val vbmcd = service(
      "orphera-vbmcd.service",
      enabled = Some(true),
      running = Some(true)
    )
    val unitFile = file(
      "/etc/systemd/system/orphera-vbmcd.service",
      unit,
      mode = "0644",
      owner = "root",
      group = "root"
    ).notifies(vbmcd)
    val answers = command(
      "wait for vbmcd to answer",
      """for i in $(seq 1 20); do
        |  if HOME=/root /opt/virtualbmc/bin/vbmc list >/dev/null 2>&1; then echo "vbmcd answers"; exit 0; fi
        |  sleep 2
        |done
        |echo "vbmcd does not answer - see 'journalctl -u orphera-vbmcd'" >&2
        |exit 1""".stripMargin,
      unless = "HOME=/root /opt/virtualbmc/bin/vbmc list >/dev/null 2>&1"
    ).after(vbmcd)
    ApplyPlan.orFail("vbmcd", List(conf, unitFile, vbmcd, answers))

  // `pkg` waits for apt and dpkg to be idle itself (unattended-upgrades on a fresh VM).
  private val packagesPlan: ApplyPlan =
    import Resources.pkg
    ApplyPlan.orFail(
      "virtualbmc-packages",
      List("python3-venv", "python3-libvirt", "ipmitool")
        .map(pkg(_, updateCache = true))
    )

  val playbook: ClusterPlaybook =
    clusterPlaybook("virtualbmc")(
      stage("install-virtualbmc", hosts*)
        .task("install the packages")(Task.Apply(packagesPlan))
        .task("install VirtualBMC into /opt/virtualbmc")(
          Task.RunCommand(List("sh", "-c", venvScript), timeoutSeconds = 600)
        )
        .task("write the configuration and the unit; run vbmcd")(
          Task.Apply(vbmcdPlan)
        )
        .build
    )
