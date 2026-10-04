// SPDX-License-Identifier: Apache-2.0

import io.circe.*
import io.circe.yaml.parser
import java.nio.file.{Files, Paths}
import orphera.orchestrator.*
import orphera.orchestrator.ClusterPlaybookDsl.*

// Sets the DNS servers (and optional search domains) on a set of nodes,
// with every value read from a YAML config file:
//
//   orphera cluster-playbook manifests/set_dns.scala --config config/dns.yaml
//
// Config shape (see config/dns.yaml):
//   params:
//     nodes: [tst0, tst1]
//     dns_servers: [192.168.1.70, 192.168.1.71]
//     search_domains: [ljalbinson.com]
//
// dns_servers falls back to dns1/dns2, and search_domains to domainname,
// so a per-VM file (config/testvm0-config.yaml) also works as --config.
// nodes falls back to tst0-tst5.
//
// Mechanism: a systemd-resolved drop-in at
// /etc/systemd/resolved.conf.d/orphera-dns.conf, then a resolved restart.
// That sets the GLOBAL resolvers and works the same whether the node's
// network is netplan- or networkd-managed, without touching the link
// config NetworkReloader backs up and rolls back. Caveat: resolved still
// also uses any per-link DNS a link gets from DHCP or netplan
// `nameservers:` — this playbook adds global servers, it doesn't strip
// per-link ones.
//
// Unlike kvm_vm_provision.scala, there's no hardcoded fallback when
// --config is missing: the config file is the whole input here, so a
// missing or bad one fails before any node is touched.
object set_dns extends OrpheraClusterPlaybook:

  private case class DnsConfig(
      nodes: List[String],
      servers: List[String],
      searchDomains: List[String]
  )

  private val defaultNodes =
    List("tst0", "tst1", "tst2", "tst3", "tst4", "tst5")

  // Values end up in a config file and a shell command, so anything
  // that isn't plainly an IP literal / hostname is rejected outright.
  private val ipLiteral = "^[0-9A-Fa-f:.]+$".r
  private val domainName = "^[A-Za-z0-9.-]+$".r

  private def decode(c: HCursor): Either[String, DnsConfig] =
    def err(e: DecodingFailure) = e.getMessage
    for
      nodes <- c.getOrElse[List[String]]("nodes")(defaultNodes).left.map(err)
      listed <- c.get[Option[List[String]]]("dns_servers").left.map(err)
      dns1 <- c.get[Option[String]]("dns1").left.map(err)
      dns2 <- c.get[Option[String]]("dns2").left.map(err)
      servers = listed.getOrElse(List(dns1, dns2).flatten).filter(_.nonEmpty)
      search <- c.get[Option[List[String]]]("search_domains").left.map(err)
      domain <- c.get[Option[String]]("domainname").left.map(err)
      domains = search.getOrElse(domain.toList).filter(_.nonEmpty)
      _ <- Either.cond(nodes.nonEmpty, (), "no nodes given")
      _ <- Either.cond(
        servers.nonEmpty,
        (),
        "no DNS servers: set dns_servers (or dns1/dns2)"
      )
      _ <- servers
        .find(s =>
          !ipLiteral.matches(s) || !(s.contains('.') || s.contains(':'))
        )
        .toLeft(())
        .left
        .map(s => s"not an IP address: '$s'")
      _ <- domains
        .find(d => !domainName.matches(d))
        .toLeft(())
        .left
        .map(d => s"not a valid search domain: '$d'")
    yield DnsConfig(nodes, servers, domains)

  private val config: DnsConfig =
    val result =
      for
        path <- sys.env
          .get("ORPHERA_CONFIG")
          .toRight(
            "no --config given (e.g. --config config/dns.yaml)"
          )
        content <- scala.util
          .Try(Files.readString(Paths.get(path)))
          .toEither
          .left
          .map(e => s"can't read $path: ${e.getMessage}")
        json <- parser.parse(content).left.map(_.getMessage)
        params <- json.hcursor
          .get[Json]("params")
          .left
          .map(e => s"missing top-level 'params:' key (${e.getMessage})")
        cfg <- decode(params.hcursor)
      yield cfg
    result.fold(e => throw new RuntimeException(s"set_dns: $e"), identity)

  private val dropInDir = "/etc/systemd/resolved.conf.d"
  private val dropIn = s"$dropInDir/orphera-dns.conf"

  private val dropInContent =
    val domainsLine =
      if config.searchDomains.isEmpty then ""
      else s"Domains=${config.searchDomains.mkString(" ")}\n"
    s"""# Managed by Orphera (manifests/set_dns.scala) — edits are overwritten.
       |[Resolve]
       |DNS=${config.servers.mkString(" ")}
       |""".stripMargin + domainsLine

  // `resolvectl dns` prints the global servers on a "Global:" line;
  // fail the task unless every configured server is on it.
  private val verifyScript =
    val checks = config.servers
      .map(s =>
        s"""if ! echo "$$G" | grep -qwF '$s'; then echo 'missing: $s'; exit 1; fi"""
      )
      .mkString("\n")
    s"""G=$$(resolvectl dns | grep '^Global:')
       |echo "$$G"
       |$checks""".stripMargin

  val playbook: ClusterPlaybook =
    clusterPlaybook("set-dns")(
      stage("set-dns", config.nodes*)
        .task("create resolved drop-in directory")(
          Task.RunCommand(List("mkdir", "-p", dropInDir), timeoutSeconds = 30)
        )
        .task("write resolved DNS drop-in")(
          Task.WriteFile(
            content = dropInContent,
            dest = dropIn,
            owner = "root",
            group = "root",
            mode = Integer.parseInt("0644", 8)
          )
        )
        .task("restart systemd-resolved")(
          Task.RunCommand(
            List("systemctl", "restart", "systemd-resolved"),
            timeoutSeconds = 60
          )
        )
        .task("verify global DNS servers")(
          Task.RunCommand(List("sh", "-c", verifyScript), timeoutSeconds = 30)
        )
        .build
    )
