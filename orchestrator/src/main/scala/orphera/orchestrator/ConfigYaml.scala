// SPDX-License-Identifier: Apache-2.0

package orphera.orchestrator

import io.circe.*
import io.circe.yaml.parser
import java.nio.file.{Files, Paths}

/** One VM's sizing/network parameters, in the shape of the kayobe/
  * kttb-virt-ansible project's own per-VM config file — e.g.
  * `playbooks/config/testvm0-config.yml` there — NOT a format Orphera invented.
  * Lets a KVM-provisioning playbook (manifests/kvm_zfs_vm_provision.scala is
  * the motivating case) read real values instead of hardcoding one Scala val
  * per field per VM, while keeping this project's existing "playbooks are code,
  * not a generic templated role" convention intact: this only replaces the
  * VALUES a playbook's own vals would otherwise hardcode, not the playbook's
  * structure — a playbook still decides for itself which fields it uses and
  * how.
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

/** One additional NIC from the `nics:` list, in the same shape as nic0:
  * `bridge` is required; `nic` (guest name, default enp2s0, enp3s0, ...), `mtu`
  * (default 1500), and optionally `ipaddress` + `netmask` or `cidr` (a static
  * address; without one the interface is brought up layer-2 only, e.g. for a
  * Neutron provider network), `gateway`, `table` and `priority`. With `table` >
  * 0 the gateway and subnet route go into that routing table and a policy rule
  * "from <ipaddress> lookup <table> priority <priority>" is added, so replies
  * leave through this NIC without a second default route in the main table.
  * The MAC is derived from the hostname and the index, so it is stable across
  * re-provisioning and the guest can match the interface by MAC instead of
  * guessing its PCI slot.
  */
case class ExtraNic(
    nic: String,
    bridge: String,
    mtu: Int,
    mac: String,
    address: Option[String],
    prefix: Int,
    gateway: Option[String],
    table: Int,
    priority: Int
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
):
  /** The `nics:` entries as layer-2-only NICs, in order (index 0 is the second
    * NIC of the VM; nic0 is the first). An entry without `bridge` is an error,
    * not silently skipped.
    */
  def extraNics: List[ExtraNic] =
    nics.zipWithIndex.map { case (j, i) =>
      val c = j.hcursor
      val bridge = c.get[String]("bridge").getOrElse(
        throw new RuntimeException(
          s"nics: entry ${i + 1} has no 'bridge:' (expected '- bridge: <ovs bridge>')"
        )
      )
      val h = java.security.MessageDigest
        .getInstance("SHA-256")
        .digest(s"$hostname/nic${i + 1}".getBytes("UTF-8"))
      val mac = "52:54:00:" + h.take(3).map(b => f"${b & 0xff}%02x").mkString(":")
      def str(k: String): Option[String] =
        c.get[String](k).toOption.map(_.trim).filter(_.nonEmpty)
      val address = str("ipaddress")
      val fromCidr = str("cidr").flatMap(_.split("/").lastOption).flatMap(_.toIntOption)
      val fromMask = str("netmask").map(
        _.split("\\.").map(o => Integer.bitCount(o.trim.toInt)).sum
      )
      if address.isDefined && fromCidr.orElse(fromMask).isEmpty then
        throw new RuntimeException(
          s"nics: entry ${i + 1} has an ipaddress but neither 'cidr' nor 'netmask'"
        )
      ExtraNic(
        nic = str("nic").getOrElse(s"enp${i + 2}s0"),
        bridge = bridge,
        mtu = c.getOrElse[Int]("mtu")(1500).getOrElse(1500),
        mac = mac,
        address = address,
        prefix = fromCidr.orElse(fromMask).getOrElse(24),
        gateway = str("gateway"),
        table = c.getOrElse[Int]("table")(0).getOrElse(0),
        priority = c.getOrElse[Int]("priority")(0).getOrElse(0)
      )
    }

object ConfigYaml:

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

  /** `disks:`/`pdisks:` entries come in several shapes in real
    * kttb-virt-ansible files: a bare string or number (`- '64'`, `- 64`), or a
    * one-key map like `- disk: 128`. All are reduced to a string — the bare
    * value, or for a single-key map that key's value — rather than rejecting
    * the file; anything else keeps its compact JSON text so nothing is silently
    * dropped.
    */
  private def diskEntry(j: Json): String =
    j.asString
      .orElse(j.asNumber.map(_.toString))
      .orElse(
        j.asObject
          .filter(_.size == 1)
          .flatMap(_.values.headOption)
          .map(v => v.asString.getOrElse(v.noSpaces))
      )
      .getOrElse(j.noSpaces)

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
      disks <- c.getOrElse[List[Json]]("disks")(Nil).map(_.map(diskEntry))
      pdisks <- c.getOrElse[List[Json]]("pdisks")(Nil).map(_.map(diskEntry))
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
