// SPDX-License-Identifier: Apache-2.0

package orphera.orchestrator

import io.circe.*
import io.circe.yaml.parser
import java.nio.file.{Files, Paths}

/** One VM's sizing/network parameters, in the shape of the kayobe/
  * kttb-virt-ansible project's own per-VM config file — e.g.
  * `playbooks/config/testvm0-config.yml` there — NOT a format Orphera invented.
  * Lets a KVM-provisioning playbook (manifests/kvm_vm_provision.scala is the
  * motivating case) read real values instead of hardcoding one Scala val per
  * field per VM, while keeping this project's existing "playbooks are code, not
  * a generic templated role" convention intact: this only replaces the VALUES a
  * playbook's own vals would otherwise hardcode, not the playbook's structure —
  * a playbook still decides for itself which fields it uses and how.
  *
  * Top-level shape is `params: {...}` — one extra level of nesting versus
  * everything else this project parses from YAML (inventory.yaml,
  * cluster-playbook YAML), because that's how the real kttb-virt-ansible files
  * are actually shaped, not a convention chosen here.
  */
case class Nic0(
    nic: String,
    ipaddress: String,
    gateway: String,
    bridge: String,
    netmask: String,
    cidr: String = "",
    table: Int = 0,
    priority: Int = 0,
    mtu: Int = 1500
)

case class VmParams(
    hypervisor: String,
    hostname: String,
    domainname: String,
    os: String,
    disk: String,
    memory: String,
    cpu: String,
    dns1: String,
    dns2: String,
    hostType: String,
    nic0: Nic0,
    disks: List[String] = Nil,
    pdisks: List[String] = Nil,
    // Shape not pinned down yet — every real config file seen so far
    // (testvm0-config.yml included) has this empty; kept as raw Json
    // rather than guessed at, same "flag rather than invent" convention
    // as this project's other genuinely-unconfirmed pieces.
    nics: List[Json] = Nil,
    preBasicPackages: List[String] = Nil,
    basicPackages: List[String] = Nil
)

object VmConfigYaml:

  private def decodeNic0(c: HCursor): Either[DecodingFailure, Nic0] =
    for
      nic <- c.get[String]("nic")
      ipaddress <- c.get[String]("ipaddress")
      gateway <- c.get[String]("gateway")
      bridge <- c.get[String]("bridge")
      netmask <- c.get[String]("netmask")
      cidr <- c.getOrElse[String]("cidr")("")
      table <- c.getOrElse[Int]("table")(0)
      priority <- c.getOrElse[Int]("priority")(0)
      mtu <- c.getOrElse[Int]("mtu")(1500)
    yield Nic0(
      nic,
      ipaddress,
      gateway,
      bridge,
      netmask,
      cidr,
      table,
      priority,
      mtu
    )

  private def decodeParams(c: HCursor): Either[DecodingFailure, VmParams] =
    for
      hypervisor <- c.get[String]("hypervisor")
      hostname <- c.get[String]("hostname")
      domainname <- c.get[String]("domainname")
      os <- c.get[String]("os")
      disk <- c.get[String]("disk")
      memory <- c.get[String]("memory")
      cpu <- c.get[String]("cpu")
      dns1 <- c.get[String]("dns1")
      dns2 <- c.get[String]("dns2")
      hostType <- c.get[String]("host_type")
      nic0Json <- c.get[Json]("nic0")
      nic0 <- decodeNic0(nic0Json.hcursor)
      disks <- c.getOrElse[List[String]]("disks")(Nil)
      pdisks <- c.getOrElse[List[String]]("pdisks")(Nil)
      nics <- c.getOrElse[List[Json]]("nics")(Nil)
      preBasicPackages <- c.getOrElse[List[String]]("pre_basic_packages")(Nil)
      basicPackages <- c.getOrElse[List[String]]("basic_packages")(Nil)
    yield VmParams(
      hypervisor,
      hostname,
      domainname,
      os,
      disk,
      memory,
      cpu,
      dns1,
      dns2,
      hostType,
      nic0,
      disks,
      pdisks,
      nics,
      preBasicPackages,
      basicPackages
    )

  def load(path: String): Either[String, VmParams] =
    for
      content <- scala.util
        .Try(Files.readString(Paths.get(path)))
        .toEither
        .left
        .map(_.getMessage)
      json <- parser.parse(content).left.map(_.getMessage)
      paramsJson <- json.hcursor
        .get[Json]("params")
        .left
        .map(e => s"missing top-level 'params:' key (${e.getMessage})")
      params <- decodeParams(paramsJson.hcursor).left.map(_.getMessage)
    yield params

  /** The hand-off a playbook actually calls. See this file's header comment:
    * IOApp.Simple's `run: IO[Unit]` has no access to process args, so
    * ORPHERA_CONFIG is how `orphera cluster-playbook somefile.scala --config
    * path/to/testvm0-config.yml` reaches the running script at all — same
    * hand-off OrpheraClusterPlaybook already uses for ORPHERA_RESUME (see
    * Main.scala's runScalaPlaybookScript, which sets both). None means --config
    * was never given; Some(Left(...)) means it was given but failed to load or
    * parse. Which to do about either case (fall back to hardcoded defaults?
    * fail the whole playbook?) is left to the calling playbook — this module
    * has no opinion on it.
    */
  def fromEnv(): Option[Either[String, VmParams]] =
    sys.env.get("ORPHERA_CONFIG").map(load)
