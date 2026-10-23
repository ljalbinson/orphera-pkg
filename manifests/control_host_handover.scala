// SPDX-License-Identifier: Apache-2.0

import java.nio.file.{Files, Path, Paths}
import orphera.orchestrator.*
import orphera.orchestrator.ClusterPlaybookDsl.*

// Gives a host built by control_host.scala what it needs to manage the fleet,
// copied from the machine running `orphera` (scala0):
//
//   certs/ca.crt, certs/ca.key            -> ~/orphera-pkg/certs/        (bootstrap, issue-certs, cert-info)
//   ~/.config/orphera/age.key, secrets.age -> ~/.config/orphera/         (orphera secrets, the agent token,
//                                                                          every playbook password)
//   ~/.ssh/id_ed25519 (else id_rsa) + .pub -> ~/.ssh/                     (bootstrap, teardown, the utils/
//                                                                          scripts, provisioning new VMs)
//
//   orphera cluster-playbook control_host_handover.scala --config tst15
//
// Then, as localadmin on the new host, it checks that `orphera` there can read
// the store and reach the agents, and sets up the OpenStack command-line
// clients (utils/openstack-client-setup.sh). CONTROL_HOST_SKIP_OPENSTACK=1
// leaves the clients out (Keystone down, say).
//
// Every file goes owner localadmin, mode 600 (the certificate and public key
// 644). Afterwards both hosts hold the same CA key, secret store and SSH key:
// the new host can do anything scala0 can. Secrets generated later on one host
// are not seen by the other until this is run again (it overwrites the copies
// on the new host from scala0's).
//
// Before anything is copied, every source file must exist; a missing one stops
// the playbook here, on scala0, with nothing sent.
object control_host_handover extends OrpheraClusterPlaybook:

  private val params: VmParams =
    ConfigYaml.fromEnv() match
      case Some(Right(p))  => p
      case Some(Left(err)) =>
        throw new RuntimeException(s"--config given but failed to load: $err")
      case None =>
        throw new RuntimeException(
          "control_host_handover requires --config <vm config> (e.g. --config tst15)"
        )

  private val host = params.hostname
  private val user = "localadmin"
  private val remoteHome = s"/home/$user"

  private val home = Paths.get(sys.props("user.home"))
  private val certsDir =
    Paths.get(sys.env.getOrElse("ORPHERA_CERTS_DIR", "certs")).toAbsolutePath
  private val ageKey =
    sys.env.get("ORPHERA_AGE_KEY").map((p: String) => Paths.get(p))
      .getOrElse(home.resolve(".config/orphera/age.key"))
  private val store =
    sys.env.get("ORPHERA_SECRETS").map((p: String) => Paths.get(p))
      .getOrElse(home.resolve(".config/orphera/secrets.age"))

  // The same order kvm_native_vm_provision.scala looks for the public key it
  // puts on new VMs, so the new host holds the key those VMs trust.
  private val sshKey: Path =
    List("id_ed25519", "id_rsa")
      .map(n => home.resolve(s".ssh/$n"))
      .find(p => Files.exists(p) && Files.exists(Paths.get(s"$p.pub")))
      .getOrElse(
        throw new RuntimeException(
          s"no SSH key pair in $home/.ssh (id_ed25519 or id_rsa, with its .pub)"
        )
      )

  private val sources =
    List(certsDir.resolve("ca.crt"), certsDir.resolve("ca.key"), ageKey, store, sshKey)
  sources.filterNot(Files.exists(_)) match
    case Nil     => ()
    case missing =>
      throw new RuntimeException(
        s"not found, nothing copied: ${missing.mkString(", ")}"
      )

  // The agents check ORPHERA_TOKEN. This host may take it from its own
  // environment rather than from the store (AgentToken: the environment wins),
  // and only the store is copied. Then the new host would hold a different
  // token, or none, and every agent would reject it; stop now instead.
  sys.env.get("ORPHERA_TOKEN").filter(_.nonEmpty).foreach { envToken =>
    val stored = SecretStore
      .default()
      .toOption
      .flatMap(_.get(AgentToken.StoreName).toOption.flatten)
    if !stored.contains(envToken) then
      throw new RuntimeException(
        s"this host's agent token comes from ORPHERA_TOKEN, and the secret store's " +
          s"${AgentToken.StoreName} is ${if stored.isEmpty then "not set" else "a different value"}; " +
          "the new host would be rejected by every agent. Put the token in the store first:\n" +
          s"""  printf '%s\\n' "$$ORPHERA_TOKEN" | orphera secrets set ${AgentToken.StoreName}\n""" +
          "then run this playbook again. Nothing was copied."
      )
  }

  private val mode600 = 384 // 0600
  private val mode644 = 420 // 0644
  private val keyName = sshKey.getFileName.toString

  private def copy(src: Path, dest: String, mode: Int): Task =
    Task.Copy(src.toString, dest, user, user, mode)

  private val dirsScript =
    s"""set -e
       |install -d -o $user -g $user -m 755 $remoteHome/orphera-pkg/certs
       |install -d -o $user -g $user -m 700 $remoteHome/.config $remoteHome/.config/orphera $remoteHome/.ssh
       |echo "directories ready"""".stripMargin

  private def asUser(script: String, timeout: Int): Task =
    Task.RunCommand(
      List("sudo", "-u", user, "-H", "bash", "-lc", script),
      timeoutSeconds = timeout
    )

  // `secrets list` proves the identity opens the store; `version` on this host
  // and one other proves the token and CA are right end to end. `version`
  // exits 0 even when a node refuses it, so its output is checked: every
  // `[node]` line must be a version number.
  private val checkScript =
    s"""set -e
       |cd ~/orphera-pkg
       |echo "secret names: $$(orphera secrets list | wc -l)"
       |out=$$(orphera version --nodes $host,tst0 2>&1)
       |echo "$$out"
       |bad=$$(echo "$$out" | grep '^\\[' | grep -v -E '^\\[[^]]+\\] [0-9]' || true)
       |if [ -n "$$bad" ]; then
       |  echo "a node did not answer with its version (token or CA wrong?)"
       |  exit 1
       |fi""".stripMargin

  private val skipOpenstack =
    sys.env.get("CONTROL_HOST_SKIP_OPENSTACK").contains("1")

  private val copies =
    stage("copy-ca-secrets-ssh-key", host)
      .task("create the destination directories")(
        Task.RunCommand(List("sh", "-c", dirsScript), timeoutSeconds = 30)
      )
      .task("CA certificate")(
        copy(certsDir.resolve("ca.crt"), s"$remoteHome/orphera-pkg/certs/ca.crt", mode644)
      )
      .task("CA key")(
        copy(certsDir.resolve("ca.key"), s"$remoteHome/orphera-pkg/certs/ca.key", mode600)
      )
      .task("age identity")(
        copy(ageKey, s"$remoteHome/.config/orphera/age.key", mode600)
      )
      .task("secret store")(
        copy(store, s"$remoteHome/.config/orphera/secrets.age", mode600)
      )
      .task(s"SSH key $keyName")(
        copy(sshKey, s"$remoteHome/.ssh/$keyName", mode600)
      )
      .task(s"SSH public key $keyName.pub")(
        copy(Paths.get(s"$sshKey.pub"), s"$remoteHome/.ssh/$keyName.pub", mode644)
      )
      .build

  private val check =
    stage("check-orphera-from-the-new-host", host)
      .task("orphera secrets list, orphera version")(asUser(checkScript, 120))
      .build

  private val openstack =
    if skipOpenstack then Nil
    else
      List(
        stage("openstack-clients", host)
          .task("utils/openstack-client-setup.sh")(
            asUser("cd ~/orphera-pkg && utils/openstack-client-setup.sh", 900)
          )
          .build
      )

  val playbook: ClusterPlaybook =
    clusterPlaybook("control-host-handover")((copies :: check :: openstack)*)
