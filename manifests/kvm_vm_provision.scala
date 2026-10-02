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
//      Orphera's confirm-vm-reachable stage below needs the NEW VM's
//      own Orphera agent to be running before it can check anything
//      the way every other health check in this project does — so
//      user-data (see writeCloudInitConfigScript) has to install and
//      start that agent itself during first boot. UNCONFIRMED here:
//      exactly how (a package? a binary fetched from somewhere
//      reachable during cloud-init? copied in via the cidata ISO
//      alongside user-data/meta-data?) — flagged rather than guessed,
//      the same way every other new mechanism in this project starts
//      out flagged until a real run proves it one way or the other.
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
//   - The `ddisks`/`pdisks` extra-disk loops, including the
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
object kvm_vm_provision extends OrpheraClusterPlaybook:

  // NEW node, not yet in inventory.yaml — add it for real (host,
  // cluster_ip, and confirm the Orphera agent is actually reachable
  // there) before this playbook can run at all. Standing in for
  // Ansible's `{{ HYPERVISOR }}` (sourced from the VM's own
  // config/{{TGT}}-config.yml in the original; hardcoded here the same
  // way every other node list in this project is a Scala val, not a
  // per-run parameter).
  private val hypervisorNode = "tst7"

  // Standing in for the original's config/{{TGT}}-config.yml for ONE
  // concrete VM — Orphera playbooks are code per exercise, not a
  // generic parameterized role, same as every other manifest in this
  // project (ceph_observability.scala's node lists, wordpress_site.scala's
  // db credentials). A second VM means a second object like this one
  // with its own vals, not a parameter to this one.
  private val vmName = "testvm0"
  private val baseImage = "noble-server-cloudimg-amd64.img" // under /var/lib/libvirt/boot/ on the hypervisor
  private val diskSizeGB = 20
  private val swapSizeGB = 2
  private val memoryMB = 4096
  private val vcpus = 2
  private val nicBridge = "br0"

  // Mirrors EFSNAME/EDIR from the original (DFSNAME/DDIR — the second,
  // bulk-data filesystem — is out of scope here, see header comment).
  private val workingFs = s"tank/kvm/$hypervisorNode/$vmName"
  private val workingDir = s"/exports/kvm/$hypervisorNode/$vmName"

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
       |zfs destroy -r '$workingFs' 2>/dev/null || true
       |echo "'$vmName' destroyed/undefined if it existed; '$workingFs' removed if present"""".stripMargin

  private val createFilesystemScript =
    s"""zfs create '$workingFs'
       |chown localadmin:localadmin '$workingDir'
       |chmod 0755 '$workingDir'
       |echo "zfs filesystem '$workingFs' mounted at '$workingDir'"""".stripMargin

  // `cp --no-clobber` mirrors the original's `copy: ... force: false` —
  // never overwrite an image that's already there from a previous
  // partial run.
  private val stageImageScript =
    s"""cp --no-clobber /var/lib/libvirt/boot/$baseImage $workingDir/$vmName.qcow2
       |qemu-img resize $workingDir/$vmName.qcow2 ${diskSizeGB}G
       |qemu-img create -q -f qcow2 $workingDir/$vmName-swap.qcow2 ${swapSizeGB}G
       |echo "base image staged and resized to ${diskSizeGB}G, ${swapSizeGB}G swap image created"""".stripMargin

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
  private val defineAndStartVmScript =
    s"""virt-install --connect qemu:///system --name $vmName --memory $memoryMB --vcpus $vcpus --disk $workingDir/$vmName.qcow2,format=qcow2,bus=scsi --disk $workingDir/$vmName-swap.qcow2,format=qcow2,bus=scsi --disk $workingDir/$vmName-cidata.iso,device=cdrom --network bridge=$nicBridge,model=virtio --os-variant ubuntu24.04 --import --noautoconsole
       |echo "$vmName defined and started"""".stripMargin

  val playbook: ClusterPlaybook =
    clusterPlaybook("kvm-vm-provision")(

      stage("destroy-existing-vm", hypervisorNode)
        .task(s"destroy/undefine $vmName and its zfs filesystem if they already exist")(
          Task.RunCommand(List("sh", "-c", destroyExistingVmScript), timeoutSeconds = 60)
        )
        .build,

      stage("create-vm-filesystem", hypervisorNode)
        .task(s"create $workingFs and set ownership")(
          Task.RunCommand(List("sh", "-c", createFilesystemScript), timeoutSeconds = 30)
        )
        .build,

      stage("stage-vm-image", hypervisorNode)
        .task("copy pristine base image, resize, create swap image")(
          Task.RunCommand(List("sh", "-c", stageImageScript), timeoutSeconds = 120)
        )
        .build,

      stage("write-cloud-init-config", hypervisorNode)
        .task("template user-data")(
          Task.Copy(
            src = "~/templates/cloud-init/user-data.j2",
            dest = s"$workingDir/user-data",
            owner = "localadmin",
            group = "localadmin",
            mode = 420, // 0644
            vars = cloudInitVars
          )
        )
        .task("template meta-data")(
          Task.Copy(
            src = "templates/cloud-init/meta-data.j2",
            dest = s"$workingDir/meta-data",
            owner = "localadmin",
            group = "localadmin",
            mode = 420,
            vars = cloudInitVars
          )
        )
        .task("template network-config")(
          Task.Copy(
            src = "templates/cloud-init/network-config.j2",
            dest = s"$workingDir/network-config",
            owner = "localadmin",
            group = "localadmin",
            mode = 420,
            vars = cloudInitVars
          )
        )
        .task("build the cidata ISO from the three rendered files")(
          Task.RunCommand(List("sh", "-c", s"cd $workingDir && $buildCidataIsoScript"), timeoutSeconds = 30)
        )
        .build,

      stage("define-and-start-vm", hypervisorNode)
        .task(s"virt-install $vmName and boot it")(
          Task.RunCommand(List("sh", "-c", defineAndStartVmScript), timeoutSeconds = 60)
        )
        .build,

      // UNCONFIRMED end-to-end, per the header comment: this only
      // works once user-data's agent-install step is real. Modeled on
      // the same "separate stage, waitFor runs before the stage's own
      // tasks" lesson every observability manifest in this project
      // already learned.
      stage("confirm-vm-reachable", vmName)
        .waitFor(
          HealthCheck.Command(
            onNode = vmName,
            command = List("sh", "-c", "echo alive"),
            pollIntervalSeconds = 10,
            timeoutSeconds = 300
          )
        )
        .task(s"$vmName provisioned and reachable")(
          Task.Debug(
            s"$vmName is up and answering Orphera's agent. Add it to inventory.yaml by hand " +
              "before targeting it from any other manifest — this playbook does not do that for you."
          )
        )
        .build
    )
