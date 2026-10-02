object NetworkConfig {

  /** VLAN settings for nic0. When given, the netplan v2 form is used. */
  final case class Vlan(name: String, id: Int, mtu: Int)

  def render(
      nic: String,
      ipAddress: String,
      ipMask: Int,          // prefix length, used by the VLAN (v2) form
      netmask: String,      // dotted netmask, used by the plain (v1) form
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
           |     name: ens3
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
