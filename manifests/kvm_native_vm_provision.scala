// SPDX-License-Identifier: Apache-2.0

import orphera.orchestrator.*
import orphera.orchestrator.ClusterPlaybookDsl.*

// Eleventh infrastructure exercise, and a genuinely different category
// from every other manifest in this project: every earlier exercise
// CONFIGURES SOFTWARE on a node that already exists and already runs
// Orphera's own agent (the gRPC service NodeClient talks to —
// AgentFs2Grpc, TLS + a shared token, per Orphera's actual execution
// model). This file is a translation of an Ansible play that instead
// PROVISIONS THE VM ITSELF on a libvirt/KVM hypervisor — a domain this
// project has never touched, and one Orphera's architecture isn't a
// natural fit for. Read this header before the playbook below; the gap
// matters more than the Scala.
//
// THE BIG STRUCTURAL MISMATCH, UP FRONT:
// Ansible is agentless — it SSHes into whatever host `delegate_to`
// names, including a target that was just created a moment ago, as
// long as it answers SSH. Orphera is NOT agentless — every
// Task.RunCommand/Task.Install/etc. call in this whole project goes
// through NodeClient to an Orphera agent already running and already
// trusted (cert in certs/ca.crt, shared token) on that specific node.
// That precondition is true for tst0-tst6 (however their images got
// the agent baked in — not something any manifest in this repo shows,
// it's already there by the time inventory.yaml references them) but
// is NOT true for:
//   1. The hypervisor itself — a genuinely new kind of node for this
//      project. It needs the Orphera agent installed and running
//      before ANY stage below can do anything, including the first
//      one. Nothing in this repo provisions that; it has to already
//      be true, the same unstated precondition tst0-tst6 already rely
//      on, just for one more host.
//   2. The brand-new VM this playbook creates. The Ansible original
//      doesn't need anything pre-installed on it — cloud-init runs,
//      the OS comes up, `wait_for_connection` just waits for sshd.
//      RESOLVED by a real run: the first version of confirm-vm-reachable
//      below used HealthCheck.Command (Orphera's own agent RPC) and
//      timed out every time, because cloud-init never installs an agent
//      that doesn't exist on the VM yet — a genuinely unresolvable
//      chicken-and-egg if this playbook tried to make the agent a
//      precondition of its own last stage. Fixed by adding a new
//      primitive, HealthCheck.Ssh (see Stage.scala), and using THAT
//      here instead: sshd is the one thing cloud-init actually
//      guarantees is up, via the same users/ssh_authorized_keys block
//      CloudConfigTemplate already renders. This stage deliberately
//      does not install the agent either — that stays a separate,
//      explicit `orphera bootstrap` step run by hand afterward, same
//      as every other node in this project's history.
//
// OTHER ANSIBLE CONSTRUCTS THAT DON'T HAVE A DIRECT EQUIVALENT:
//   - `register` + `when: result.stdout_lines` (the exists/snapshot/
//     destroy checks) -> no cross-task "capture this command's output,
//     branch a LATER task on it" primitive exists (Task.when only
//     matches FactCondition against gathered facts or values set via
//     Task.SetFact, not arbitrary command output). Folded into ONE
//     shell script with inline `if ... ; then ... ; fi` instead — the
//     same idiom this project's own ceph_performance_test.scala and
//     observability_extended.scala already use for "check, then act"
//     logic.
//   - `loop`/`with_items`/`with_indexed_items` (extra NICs, extra
//     disks, the loopback-partitioned data disks) -> no DSL-level
//     loop construct either; repetition is ordinary Scala collection
//     code building either multiple Stage/Task values or a single
//     shell script with its own `for` loop, same pattern
//     observability_extended.scala's `nodeExporterTargets.mkString`
//     already uses. This file only has ONE nic and ONE boot disk (no
//     extra `disks`/`ddisks`/`pdisks`) to keep that repetition out of
//     scope for a first translation — see "Deliberately out of
//     scope" below.
//   - `template:` (user-data/meta-data/network-config,
//     host_var.j2) -> Task.Copy(src, dest, ..., vars) IS a real
//     equivalent: PlaybookRunner resolves and Mustache-renders `src`
//     with `vars` before pushing it, same templating engine
//     (com.github.mustachejava) every `{{cluster_ip}}`-style
//     substitution elsewhere in this project already goes through.
//     Used below for the three cloud-init files.
//   - Ansible's own host_vars/*.yml generation (so LATER playbook runs
//     know about the new host) -> no equivalent at all. Orphera has no
//     task that edits the orchestrator's own local inventory.yaml from
//     inside a running playbook. The new VM has to be added to
//     inventory.yaml by hand after this playbook succeeds — exactly
//     the same manual step tst3/tst4/tst5/tst6 each went through
//     earlier in this project's history, just for a VM this playbook
//     created instead of one that already existed.
//
// DELIBERATELY OUT OF SCOPE for this first translation (all present in
// the original Ansible play):
//   - (`disks:` extra data disks ARE now created — see extraDisksGB.)
//     The `pdisks` extra-disk loop, including the
//     create-loopback-device / sfdisk / mkfs.ext4 / mount / write a
//     "purpose" file / unmount / losetup -d sequence used to label a
//     data disk before attaching it. Narrow, and multiplies the
//     "no loop primitive" translation problem several times over for
//     one VM-sizing feature — a real follow-up, not folded in here.
//   - A second ZFS filesystem/directory for bulk data (DFSNAME/DDIR) —
//     only the one working filesystem (EFSNAME/EDIR) a VM strictly
//     needs is created here.
//   - Snapshot handling beyond "delete the current snapshot if one
//     exists" (the original's own scope).
// NATIVE VARIANT: this is kvm_zfs_vm_provision.scala without ZFS. Everything
// else (config handling, cloud-init, virt-install, autostart, reachability
// check) is identical; only the per-VM storage differs: a plain directory
// ($ORPHERA_VM_DIR_ROOT, default /var/lib/libvirt/images/orphera)/<vm> instead of
// a ZFS filesystem tank/kvm/<hypervisor>/<vm> mounted under /exports/kvm.
// Destroying a VM removes that directory with rm -rf (no snapshots or
// recursive zfs destroy), so keep anything you want to retain elsewhere.
object kvm_native_vm_provision extends OrpheraClusterPlaybook:

  // `orphera cluster-playbook manifests/kvm_native_vm_provision.scala --config
  // config/testvm0-config.yaml` makes that file's parsed params available
  // here. `Left` (given but unparseable) fails the whole playbook loudly
  // rather than silently falling back — a typo'd/corrupt --config
  // should never result in provisioning against the WRONG VM's sizing by
  // accident. `None` (flag not given at all) is the one case every val
  // below still falls back to its prior hardcoded literal for, so this
  // file keeps working unchanged for anyone not using --config yet.
  private val config: Option[VmParams] =
    ConfigYaml.fromEnv() match
      case Some(Right(p))  => Some(p)
      case Some(Left(err)) =>
        throw new RuntimeException(
          s"--config given but failed to load: $err"
        )
      case None => None

  // NEW node, not yet in inventory.yaml — add it for real (host,
  // cluster_ip, and confirm the Orphera agent is actually reachable
  // there) before this playbook can run at all. Standing in for
  // Ansible's `{{ HYPERVISOR }}` (sourced from the VM's own
  // config/{{TGT}}-config.yml in the original; previously hardcoded here
  // the same way every other node list in this project is a Scala val —
  // now read from config when --config is given, same literal
  // "gs10" as a fallback otherwise. NOTE "gs10" differs from "gs1", the
  // hypervisor every real run of this playbook before --config
  // existed actually used successfully — carried over as the fallback
  // rather than silently corrected, same flag as before.
  private val hypervisorNode = config.map(_.hypervisor).getOrElse("gs10")

  // Standing in for the original's config/{{TGT}}-config.yml for ONE
  // concrete VM — Orphera playbooks are code per exercise, not a
  // generic parameterized role, same as every other manifest in this
  // project (ceph_observability.scala's node lists, wordpress_site.scala's
  // db credentials). A second VM now means a second --config YAML
  // file, not a second hardcoded object — the vals below just read
  // whichever file --config points at, falling back to testvm0's own
  // real values (from testvm0-config.yaml) when run without the flag.
  //
  // `pre_basic_packages`/`basic_packages` (qemu-guest-agent,
  // openvswitch-switch, htop, etc.) are parsed into config but NOT
  // wired in here yet — installing packages on testvm0 itself needs a
  // way to run commands there, and the only primitive that currently
  // reaches an uninventoried VM at all is the SSH liveness probe
  // (HealthCheck.Ssh), not a command-execution task — a real follow-up,
  // not folded in here. `host_type` IS wired in below (`--cpu` on
  // virt-install).
  private val vmName = config.map(_.hostname).getOrElse("testvm0")
  private val baseImage = config
    .map(_.os)
    .getOrElse(
      "noble-server-cloudimg-amd64.img"
    ) // under /var/lib/libvirt/boot/ on the hypervisor
  private val diskSizeGB = config.map(_.disk.toInt).getOrElse(32)
  private val swapSizeGB =
    2 // not present in testvm0-config.yaml at all; no config field to read
  // Extra data disks from `disks:` in --config — each entry is a size in GB
  // (`- disk: 128` or `- '128'`, see ConfigYaml.diskEntry). One qcow2 per
  // entry, `<vm>-data1.qcow2`, `<vm>-data2.qcow2`, ..., created blank and
  // attached after the swap disk. `pdisks:` (the original's loopback-
  // partitioned/labelled disks) is still not implemented; warned about below.
  private val extraDisksGB: List[Int] =
    config.map(_.disks).getOrElse(Nil).map { d =>
      scala.util
        .Try(d.trim.toInt)
        .toOption
        .filter(_ > 0)
        .getOrElse(
          throw new RuntimeException(
            s"disks: entry '$d' is not a positive size in GB"
          )
        )
    }
  if config.exists(_.pdisks.nonEmpty) then
    Console.err.println(
      "WARNING: pdisks: in --config is not implemented by kvm_native_vm_provision.scala and is ignored."
    )
  private val extraDiskFiles: List[(Int, String)] =
    extraDisksGB.zipWithIndex.map { case (gb, i) =>
      (gb, s"$vmName-data${i + 1}.qcow2")
    }
  private val memoryMB = config.map(_.memory.toInt).getOrElse(16384)
  private val vcpus = config.map(_.cpu.toInt).getOrElse(4)
  private val nicName = config.map(_.nic0.nic).getOrElse("enp1s0")
  private val nicBridge = config.map(_.nic0.bridge).getOrElse("br-net1")
  private val cpuMode = config.map(_.hostType).getOrElse("host-passthrough")
  private val domainname =
    config.map(_.domainname).getOrElse("ljalbinson.com")
  private val dns1 = config.map(_.dns1).getOrElse("192.168.1.70")
  private val dns2 = config.map(_.dns2).getOrElse("192.168.1.71")
  private val ipAddress =
    config.map(_.nic0.ipaddress).getOrElse("192.168.1.222")
  private val gateway = config.map(_.nic0.gateway).getOrElse("192.168.1.1")
  private val netmask =
    config.map(_.nic0.netmask).getOrElse("255.255.255.0")
  // Prefix length for the VLAN (netplan v2) template form — not
  // currently exercised (NetworkConfigTemplate.render is always called
  // with vlan = None below), but kept derived from nic0.cidr rather than
  // a bare literal, same as everything else above. "192.168.1.0/24" ->
  // 24; falls back to 24 outright if cidr is missing or malformed.
  private val ipMask = config
    .map(_.nic0.cidr)
    .flatMap(_.split("/").lastOption)
    .flatMap(_.toIntOption)
    .getOrElse(24)

  // Real run finding: this used to be a single hardcoded "ssh-rsa ...
  // localadmin@xh4" literal baked straight into CloudConfigTemplate's
  // authorized_keys — a key from some other, long-gone machine ("xh4"),
  // not anything actually present on whatever host runs this playbook.
  // HealthCheck.Ssh's own BatchMode=yes connection (and anyone manually
  // reproducing it, see SshDeployer.checkAlive's doc comment) tries
  // ssh's default identity files for the CURRENT operator/host — if that
  // doesn't match the hardcoded key, pubkey auth fails outright, no
  // matter how clean the network/host-key story is (confirmed by hand:
  // host-key checking passed, auth still failed). Reading the real
  // public key off disk here means whichever identity ssh actually ends
  // up using to CONNECT is the exact same one authorized to log in.
  // ORPHERA_SSH_PUBKEY_PATH overrides the path outright; otherwise tries
  // the two standard default pubkeys in order. Throws loudly rather than
  // silently falling back to the stale "xh4" key — a VM provisioned with
  // the wrong key baked in fails the exact same way all over again.
  private val sshPublicKey: String =
    val home = sys.props.getOrElse("user.home", "/root")
    val candidates = sys.env.get("ORPHERA_SSH_PUBKEY_PATH").toList ++
      List(s"$home/.ssh/id_ed25519.pub", s"$home/.ssh/id_rsa.pub")
    candidates
      .map(java.nio.file.Paths.get(_))
      .find(java.nio.file.Files.exists(_))
      .map(p => new String(java.nio.file.Files.readAllBytes(p)).trim)
      .getOrElse(
        throw new RuntimeException(
          "No SSH public key found for provisioning testvm0's authorized_keys " +
            s"(checked: ${candidates.mkString(", ")}). Set ORPHERA_SSH_PUBKEY_PATH " +
            "to a pubkey file, or generate one with `ssh-keygen -t ed25519`."
        )
      )

  // Mirrors EFSNAME/EDIR from the original (DFSNAME/DDIR — the second,
  // bulk-data filesystem — is out of scope here, see header comment).
  // Native variant: no ZFS. The VM's files live in a plain directory on the
  // hypervisor's own filesystem (default libvirt image area), created with
  // mkdir and removed with rm -rf. Set ORPHERA_VM_DIR_ROOT to use another
  // parent directory (for example a separate mount).
  private val dirRoot =
    sys.env.getOrElse("ORPHERA_VM_DIR_ROOT", "/var/lib/libvirt/images/orphera")
  private val workingDir = s"$dirRoot/$vmName"

  // Mirrors the original's exists/snapshot/destroy/undefine sequence —
  // one shell script with inline `if`/`grep` checks standing in for
  // Ansible's register-then-when-on-a-later-task chain (see header
  // comment). `virsh ... || true` tolerates "doesn't exist yet", same
  // as the original's `ignore_errors: true` on each of these.
  private val destroyExistingVmScript =
    s"""if virsh --quiet list --all --name | grep -qx '$vmName'; then
       |  if virsh --quiet snapshot-list $vmName 2>/dev/null | grep -q .; then
       |    virsh --quiet snapshot-delete $vmName --current || true
       |  fi
       |  virsh --quiet destroy $vmName 2>/dev/null || true
       |  virsh --quiet undefine $vmName || true
       |fi
       |rm -rf '$workingDir'
       |echo "'$vmName' destroyed/undefined if it existed; '$workingDir' removed if present"""".stripMargin

  private val createFilesystemScript =
    s"""mkdir -p '$workingDir'
       |chown localadmin:localadmin '$workingDir'
       |chmod 0755 '$workingDir'
       |echo "directory '$workingDir' ready"""".stripMargin

  // `cp --no-clobber` mirrors the original's `copy: ... force: false` —
  // never overwrite an image that's already there from a previous
  // partial run.
  private val extraDiskCreateLines =
    extraDiskFiles
      .map { case (gb, f) =>
        s"qemu-img create -q -f qcow2 $workingDir/$f ${gb}G"
      }
      .mkString("\n")

  private val extraDiskVirtInstallArgs =
    extraDiskFiles.map { case (_, f) =>
      s" --disk $workingDir/$f,format=qcow2,bus=scsi,serial=${serialOf(f)}"
    }.mkString

  // Every disk gets an explicit QEMU serial so its guest-side
  // /dev/disk/by-id name is fixed by us, not by libvirt's auto-assigned
  // drive alias (drive-scsi0-0-0-N), which depends on slot/order and moved
  // under tst0 once already. Inside the guest the path is
  // /dev/disk/by-id/scsi-0QEMU_QEMU_HARDDISK_<serial> (prefix per the
  // QEMU scsi-hd model; confirm once with `ls -l /dev/disk/by-id/`).
  private def serialOf(file: String): String = file.stripSuffix(".qcow2")
  private def byId(serial: String): String =
    s"/dev/disk/by-id/scsi-0QEMU_QEMU_HARDDISK_$serial"
  private val extraDiskByIds: List[String] =
    extraDiskFiles.map { case (_, f) => byId(serialOf(f)) }

  private val stageImageScript =
    s"""cp --no-clobber /var/lib/libvirt/boot/$baseImage $workingDir/$vmName.qcow2
       |qemu-img resize $workingDir/$vmName.qcow2 ${diskSizeGB}G
       |qemu-img create -q -f qcow2 $workingDir/$vmName-swap.qcow2 ${swapSizeGB}G
       |$extraDiskCreateLines
       |echo "base image staged and resized to ${diskSizeGB}G, ${swapSizeGB}G swap image created, ${extraDiskFiles.size} extra data disk(s) created"""".stripMargin

  // Task.Copy's Mustache rendering stands in for the original's
  // `template:` + .j2 files — these three source paths are plain local
  // template files (not shown here; write minimal cloud-init
  // user-data/meta-data/network-config content under
  // templates/cloud-init/ before running this), rendered with this
  // VM's own vars and pushed to the hypervisor exactly where mkisofs
  // (next script) expects to find them.
  //
  // UNCONFIRMED, flagged per the header comment: user-data needs its
  // own step that installs and starts Orphera's agent on first boot —
  // without that, confirm-vm-reachable below has nothing to talk to.
  // Not written here; this is the one piece a real cloud image +
  // agent-packaging decision has to settle first.
  private val cloudInitVars: Map[String, String] = Map(
    "hostname" -> vmName,
    "nicBridge" -> nicBridge
  )

  private val buildCidataIsoScript =
    s"""mkisofs -q -o $vmName-cidata.iso -V cidata -J -rock user-data meta-data network-config
       |echo "cidata ISO built"""".stripMargin

  // Mirrors the original's `EDISKS`/`EXTRA_NICS` string-building, just
  // with exactly one disk and one NIC instead of a loop over
  // `params.disks`/`params.nics` — see header comment on why a real
  // multi-disk/multi-nic version needs Scala-level repetition, not
  // more string concatenation here.
  // One line, deliberately — no `\` line-continuation inside this
  // triple-quoted literal. A backslash immediately before a newline in
  // a `s"""..."""` string is rejected by the Scala 3 compiler as an
  // invalid escape sequence, a real compile error already hit once in
  // this project (observability_stack.scala); the proven fix is to
  // never use one here, not to trust it works in a "safer-looking"
  // spot.
  // The libosinfo short-id for virt-install's --os-variant, from the base
  // image's name: Rocky-10-GenericCloud... -> rocky10, anything else keeps the
  // original ubuntu24.04. A hypervisor whose osinfo database does not know the
  // id (rocky10 needs a recent osinfo-db) falls back to `generic` at run time,
  // so an older host still defines the VM; the id only tunes device defaults.
  // virt-install is asked itself (`--osinfo list`), not osinfo-query, which
  // may be missing or read a different database (gs3: rocky10 rejected).
  // --machine q35 and the virtio-scsi controller are explicit because, with
  // the `generic` os-variant, virt-install picks i440fx and an LSI SCSI
  // controller: the NIC is then ens3 (not enp1s0) and a RHEL-family kernel,
  // which no longer has that driver, cannot find its root disk (seen on tst8).
  // Ubuntu variants already got both, so those VMs are unchanged.
  // The script runs under `set -e`: before, a failed virt-install was followed
  // by a successful `virsh autostart`/`echo`, so the stage reported success.
  private val osVariantWanted: String =
    """(?i)rocky-(\d+)""".r
      .findFirstMatchIn(baseImage)
      .map(m => s"rocky${m.group(1)}")
      .getOrElse("ubuntu24.04")

  private val defineAndStartVmScript =
    s"""set -e
       |want=$osVariantWanted
       |if virt-install --osinfo list 2>/dev/null | grep -qw -- "$$want"; then variant=$$want; else variant=generic; fi
       |echo "using --os-variant $$variant"
       |virt-install --connect qemu:///system --name $vmName --memory $memoryMB --vcpus $vcpus --cpu $cpuMode --disk $workingDir/$vmName.qcow2,format=qcow2,bus=scsi,serial=$vmName-root --disk $workingDir/$vmName-swap.qcow2,format=qcow2,bus=scsi,serial=$vmName-swap$extraDiskVirtInstallArgs --disk $workingDir/$vmName-cidata.iso,device=cdrom --network bridge=$nicBridge,model=virtio,virtualport_type=openvswitch --machine q35 --controller type=scsi,model=virtio-scsi --channel unix,target_type=virtio,name=org.qemu.guest_agent.0 --os-variant $$variant --import --noautoconsole
       |virsh --connect qemu:///system autostart $vmName
       |echo "$vmName defined, started, and set to autostart with the hypervisor"""".stripMargin

  val playbook: ClusterPlaybook =
    clusterPlaybook("kvm-native-vm-provision")(
      stage("destroy-existing-vm", hypervisorNode)
        .task(
          s"destroy/undefine $vmName and its directory if they already exist"
        )(
          Task.RunCommand(
            List("sh", "-c", destroyExistingVmScript),
            timeoutSeconds = 60
          )
        )
        .build,

      stage("create-vm-directory", hypervisorNode)
        .task(s"create $workingDir and set ownership")(
          Task.RunCommand(
            List("sh", "-c", createFilesystemScript),
            timeoutSeconds = 30
          )
        )
        .build,

      stage("stage-vm-image", hypervisorNode)
        .task("copy pristine base image, resize, create swap image")(
          Task.RunCommand(
            List("sh", "-c", stageImageScript),
            timeoutSeconds = 120
          )
        )
        .build,

      stage("write-cloud-init-config", hypervisorNode)
        .task("template user-data")(
          Task.WriteFile(
            content = CloudConfigTemplate.render(
              vmName,
              domainname,
              ipAddress,
              dns1,
              dns2,
              "localadmin",
              "passw0rd",
              sshPublicKey,
              "Europe/London"
            ),
            dest = s"$workingDir/user-data",
            owner = "localadmin",
            group = "localadmin",
            mode = 420
          )
        )
        .task("template meta-data")(
          Task.WriteFile(
            content = MetaConfigTemplate.render(),
            dest = s"$workingDir/meta-data",
            owner = "localadmin",
            group = "localadmin",
            mode = 420
          )
        )
        .task("template network-config")(
          Task.WriteFile(
            content = NetworkConfigTemplate.render(
              nicName,
              ipAddress,
              ipMask,
              netmask,
              gateway,
              dns1,
              dns2,
              domainname
            ),
            dest = s"$workingDir/network-config",
            owner = "localadmin",
            group = "localadmin",
            mode = 420
          )
        )
        .task("build the cidata ISO from the three rendered files")(
          Task.RunCommand(
            List("sh", "-c", s"cd $workingDir && $buildCidataIsoScript"),
            timeoutSeconds = 30
          )
        )
        .build,

      stage("define-and-start-vm", hypervisorNode)
        .task(s"virt-install $vmName, boot it, and mark it autostart")(
          Task.RunCommand(
            List("sh", "-c", defineAndStartVmScript),
            timeoutSeconds = 60
          )
        )
        .build,

      // Real run finding: the first version of this stage used
      // HealthCheck.Command, which polls over Orphera's own agent RPC —
      // but testvm0 can't possibly be running that agent yet (cloud-init's
      // user-data here never installs/starts one, see CloudConfigTemplate
      // below), so every poll failed the same way until the stage timed
      // out. Switched to HealthCheck.Ssh: the one thing a stock cloud
      // image's cloud-init guarantees is up once boot finishes is sshd,
      // via the same users/ssh_authorized_keys block already in
      // CloudConfigTemplate — so SSH, not the agent, is the honest
      // liveness signal at this point in the VM's life. Deliberately
      // does NOT install the agent itself; that stays a separate,
      // explicit `orphera bootstrap` step run by hand afterward, same as
      // every other node in this project's history (see the header
      // comment's point #2 on the agent-install gap, which this stage no
      // longer tries to paper over).
      // Real run finding #2: even with host given directly to
      // HealthCheck.Ssh, this stage still failed — with a DIFFERENT
      // error, "No matching nodes found in inventory", thrown before
      // waitFor is ever even looked at. ClusterPlaybookRunner.runStage
      // resolves a stage's OWN nodeNames (here, vmName — used to decide
      // where this stage's own tasks, i.e. the Task.Debug below, are
      // allowed to run) through Inventory.all unconditionally, as a
      // completely separate concern from waitFor's node resolution. That
      // lookup has nothing to do with the health check itself, but it
      // gates the whole stage, agent or no agent. Fixed by targeting this
      // stage's tasks at hypervisorNode instead (already in inventory,
      // already has the agent) — HealthCheck.Ssh's `host` field, not this
      // stage's nodeNames, is what actually controls where the liveness
      // check runs.
      stage("confirm-vm-reachable", hypervisorNode)
        .waitFor(
          HealthCheck.Ssh(
            onNode = vmName,
            host = ipAddress,
            sshUser =
              "localadmin", // matches the `users:` entry CloudConfigTemplate renders
            sshKeyPath =
              None, // fill in a path if the operator's default identity isn't the right key
            remoteCommand = "true",
            pollIntervalSeconds = 10,
            // Real run finding #3: sshd starts listening well before pubkey
            // auth actually works. On a real fresh boot, sshd logged
            // "Server listening" ~11s after boot, but the first successful
            // pubkey auth didn't happen until ~47s after THAT (~60s after
            // boot) — cloud-init's ssh/users module writes
            // ~/.ssh/authorized_keys late in its "Final" stage, well after
            // sshd itself comes up. The original 30s timeout (3 polls at
            // 10s) never reached that point and always timed out. Bumped to
            // 180s (18 polls) to leave real margin above the ~60s observed,
            // since a slower/loaded hypervisor could push this further.
            timeoutSeconds = 180
          )
        )
        .task(s"$vmName provisioned and reachable")(
          Task.Debug(
            s"$vmName is up and answering SSH. The Orphera agent is NOT installed yet — " +
              "run `orphera bootstrap --forget-host-key` against it by hand (the flag clears the stale known_hosts entry a rebuilt VM leaves behind) before targeting it from any other manifest." +
              (if extraDiskByIds.isEmpty then ""
               else
                 " Extra data disks (stable by-id paths for inventory.yaml " +
                   "osd_disks/zap_disks): " + extraDiskByIds.mkString(", "))
          )
        )
        .build
    )

object CloudConfigTemplate {

  def render(
      hostname: String,
      domainname: String,
      ipAddress: String,
      dns1: String,
      dns2: String,
      username: String,
      password: String,
      sshKey: String,
      timezone: String = "Europe/London"
  ): String =
    s"""#cloud-config
       |# password: passw0rd
       |# chpasswd: { expire: False }
       |# ssh_pwauth: True
       |# Install my public ssh key to the first user-defined user configured
       |# in cloud.cfg in the template (which is centos for CentOS cloud images)
       |preserve_hostname: False
       |hostname: $hostname
       |fqdn: $hostname.$domainname
       |
       |# Users
       |users:
       |    - default
       |    - name: $username
       |      groups:
       |        - wheel
       |      shell: /bin/bash
       |      lock_passwd: false
       |      sudo:
       |        - ALL=(ALL) NOPASSWD:ALL
       |      ssh-authorized-keys:
       |        - $sshKey
       |
       |chpasswd:
       |  list:
       |    - "$username:$password"
       |    - "root:$password"
       |  expire: false
       |
       |# Configure where output will go
       |output:
       |  all: ">> /var/log/cloud-init.log"
       |
       |# configure interaction with ssh server
       |ssh_genkeytypes: ['ed25519', 'rsa']
       |
       |# Install my public ssh key to the first user-defined user configured
       |# in cloud.cfg in the template (which is centos for CentOS cloud images)
       |ssh_authorized_keys:
       |  - $sshKey
       |
       |# set timezone for VM
       |timezone: $timezone
       |
       |# Enter host in /etc/hosts
       |write_files:
       |  - path: /etc/hosts
       |    content: |
       |      127.0.0.1 localhost localhost.localdomain localhost4 localhost4.localdomain4
       |      ::1 localhost localhost.localdomain localhost6 localhost6.localdomain6
       |      $ipAddress $hostname.$domainname $hostname
       |  - path: /etc/resolv.conf
       |    content: |
       |      nameserver $dns1
       |      nameserver $dns2
       |      search $domainname
       |
       |# Remove cloud-init
       |runcmd:
       |  - echo "Hi there"
       |
       |# EOF
       |""".stripMargin
}

object NetworkConfigTemplate {

  /** VLAN settings for nic0. When given, the netplan v2 form is used. */
  final case class Vlan(name: String, id: Int, mtu: Int)

  def render(
      nic: String,
      ipAddress: String,
      ipMask: Int, // prefix length, used by the VLAN (v2) form
      netmask: String, // dotted netmask, used by the plain (v1) form
      gateway: String,
      dns1: String,
      dns2: String,
      domainname: String,
      vlan: Option[Vlan] = None
  ): String =
    vlan match {
      case Some(v) =>
        s"""network:
           |  version: 2
           |  ethernets:
           |    $nic:
           |      dhcp4: false
           |  vlans:
           |    ${v.name}:
           |      link: "$nic"
           |      id: ${v.id}
           |      mtu: ${v.mtu}
           |      optional: true
           |      addresses:
           |      - $ipAddress/$ipMask
           |      nameservers:
           |        addresses:
           |        - $dns1
           |        - $dns2
           |        search:
           |        - $domainname
           |      routes:
           |      - to: default
           |        via: $gateway
           |        metric: 100
           |""".stripMargin

      case None =>
        s"""version: 1
           |config:
           |   - type: physical
           |     name: $nic
           |     subnets:
           |        - type: static
           |          address: $ipAddress
           |          netmask: $netmask
           |          gateway: $gateway
           |   - type: nameserver
           |     address:
           |        - $dns1
           |        - $dns2
           |     search:
           |        - $domainname
           |""".stripMargin
    }
}

object MetaConfigTemplate {

  def render(): String =
    s"""# instance-id: iid-local24
       |local-hostname: cloudimg
       |""".stripMargin

}
