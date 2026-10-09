// SPDX-License-Identifier: Apache-2.0

import io.circe.*
import io.circe.yaml.parser
import java.nio.file.{Files, Paths}
import orphera.orchestrator.*
import orphera.orchestrator.ClusterPlaybookDsl.*

// Sets the DNS servers (and optional search domains) on a set of nodes,
// with every value read from a YAML config file:
//
//   orphera cluster-playbook manifests/gen_set_dns.scala --config config/dns.yaml
//
// Config shape (see config/dns.yaml):
//   params:
//     nodes: [tst0, tst1]
//     dns_servers: [192.168.1.70, 192.168.1.71]
//     search_domains: [ljalbinson.com]
//     round_robin: true      # optional, default false — see below
//
// dns_servers falls back to dns1/dns2, and search_domains to domainname,
// so a per-VM file (config/testvm0-config.yaml) also works as --config.
// nodes falls back to the config's `hostname` (a per-VM file such as
// config/tst0.yaml then targets just that VM), and only if that is absent
// too, to tst0-tst5.
//
// Mechanism, chosen per node at run time:
//   - systemd-resolved present: a drop-in at
//     /etc/systemd/resolved.conf.d/orphera-dns.conf, then a resolved
//     restart. That sets the GLOBAL resolvers and works the same whether
//     the node's network is netplan- or networkd-managed, without touching
//     the link config NetworkReloader backs up and rolls back. Caveat:
//     resolved still also uses any per-link DNS a link gets from DHCP or
//     netplan `nameservers:` — this adds global servers, it doesn't strip
//     per-link ones.
//   - resolved missing or masked (tst0 has no unit at all: plain static
//     /etc/resolv.conf, networkd only, no DHCP client; gs2/gs3 have it
//     masked): a static /etc/resolv.conf is written instead. "Usable" is
//     decided by the unit's LoadState being `loaded` — `systemctl cat`
//     was tried first and wrongly says yes for a masked unit. Both found
//     by real runs: the first version assumed resolved everywhere and
//     failed on tst0 ("Unit systemd-resolved.service not found"), the
//     second on gs2/gs3 ("...is masked").
//     On NetworkManager hosts (Rocky/RHEL, no resolved by default) the static
//     branch also writes /etc/NetworkManager/conf.d/90-orphera-dns.conf
//     (dns=none) and reloads NetworkManager, or NM rewrites resolv.conf.
//
// round_robin: true switches mechanism. systemd-resolved has no rotate
// option — it sticks to one upstream server and only fails over — so
// instead the node gets a static /etc/resolv.conf (replacing the
// resolved stub symlink, if there is one) with `options rotate`, which
// makes glibc spread queries across the listed servers. Costs, on those nodes:
// resolved's stub, caching and per-link DNS are bypassed for anything
// that reads resolv.conf, and a dead server costs a resolver timeout
// each time it's picked. glibc only honours 3 nameservers, so
// round_robin needs 2-3 dns_servers and rejects anything else rather
// than silently ignoring the rest. Running again WITHOUT round_robin
// puts the stub symlink back, but only if the file still carries
// Orphera's marker line — a hand-written resolv.conf is left alone.
//
// Unlike kvm_vm_provision.scala, there's no hardcoded fallback when
// --config is missing: the config file is the whole input here, so a
// missing or bad one fails before any node is touched.
object gen_set_dns extends OrpheraClusterPlaybook:

  private case class DnsConfig(
      nodes: List[String],
      servers: List[String],
      searchDomains: List[String],
      roundRobin: Boolean
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
      listedNodes <- c.get[Option[List[String]]]("nodes").left.map(err)
      hostname <- c.get[Option[String]]("hostname").left.map(err)
      // `nodes:` wins; else a per-VM config's own `hostname:` (so
      // `--config config/tst0.yaml` targets tst0 only); else the default list.
      nodes = listedNodes
        .orElse(hostname.filter(_.nonEmpty).map(List(_)))
        .getOrElse(defaultNodes)
      listed <- c.get[Option[List[String]]]("dns_servers").left.map(err)
      dns1 <- c.get[Option[String]]("dns1").left.map(err)
      dns2 <- c.get[Option[String]]("dns2").left.map(err)
      servers = listed.getOrElse(List(dns1, dns2).flatten).filter(_.nonEmpty)
      search <- c.get[Option[List[String]]]("search_domains").left.map(err)
      domain <- c.get[Option[String]]("domainname").left.map(err)
      domains = search.getOrElse(domain.toList).filter(_.nonEmpty)
      roundRobin <- c.getOrElse[Boolean]("round_robin")(false).left.map(err)
      _ <- Either.cond(nodes.nonEmpty, (), "no nodes given")
      _ <- Either.cond(
        servers.nonEmpty,
        (),
        "no DNS servers: set dns_servers (or dns1/dns2)"
      )
      _ <- Either.cond(
        !roundRobin || (servers.size >= 2 && servers.size <= 3),
        (),
        s"round_robin needs 2-3 DNS servers (glibc ignores any beyond 3), got ${servers.size}"
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
    yield DnsConfig(nodes, servers, domains, roundRobin)

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
    result.fold(e => throw new RuntimeException(s"gen_set_dns: $e"), identity)

  private val dropInDir = "/etc/systemd/resolved.conf.d"
  private val dropIn = s"$dropInDir/orphera-dns.conf"

  private val dropInContent =
    val domainsLine =
      if config.searchDomains.isEmpty then ""
      else s"Domains=${config.searchDomains.mkString(" ")}\n"
    s"""# Managed by Orphera (manifests/gen_set_dns.scala) — edits are overwritten.
       |[Resolve]
       |DNS=${config.servers.mkString(" ")}
       |""".stripMargin + domainsLine

  private val managedMarker =
    "# Managed by Orphera (manifests/gen_set_dns.scala)"
  // Files written before the rename carry the old name; still recognise them.
  private val legacyMarker = "# Managed by Orphera (manifests/set_dns.scala)"

  private val resolvConf = "/etc/resolv.conf"
  private val stubResolvConf = "/run/systemd/resolve/stub-resolv.conf"

  // Static resolv.conf. `search` only when domains exist; `options rotate`
  // only for round_robin. Trailing newline stripped because it's embedded
  // in a heredoc below.
  private val staticContent: String =
    val nameservers = config.servers.map(s => s"nameserver $s\n").mkString
    val searchLine =
      if config.searchDomains.isEmpty then ""
      else s"search ${config.searchDomains.mkString(" ")}\n"
    val rotateLine = if config.roundRobin then "options rotate\n" else ""
    (s"$managedMarker — edits are overwritten.\n" +
      nameservers + searchLine + rotateLine).stripSuffix("\n")

  private val dropInText: String = dropInContent.stripSuffix("\n")

  // WriteFile can't be used for the apply step: which file gets written
  // depends on whether the NODE has systemd-resolved, which is only known
  // at run time. So one script does the detection and the write, with
  // the same temp-file-then-rename the agent's own file writes use. Values
  // are validated IP literals / hostnames (see decode), so embedding them
  // is safe; contents go in quoted heredocs, so nothing is expanded.
  private val writeFileFn =
    """write_file() {
      |  tmp="$1.orphera-tmp"
      |  cat > "$tmp"
      |  chown root:root "$tmp"
      |  chmod 0644 "$tmp"
      |  mv -f "$tmp" "$1"
      |}""".stripMargin

  // Writes the static file, then checks it: a regular file (not the
  // resolved stub symlink), every server present, rotate present when asked.
  private val staticBranch: String =
    val serverChecks = config.servers
      .map(s =>
        s"""grep -qxF 'nameserver $s' $resolvConf || { echo 'missing: $s'; exit 1; }"""
      )
      .mkString("\n")
    val rotateCheck =
      if config.roundRobin then
        s"""grep -qxF 'options rotate' $resolvConf || { echo 'options rotate missing'; exit 1; }"""
      else ""
    s"""if [ -x /usr/bin/nmcli ] && systemctl is-active --quiet NetworkManager; then
       |  echo "NetworkManager active: telling it not to manage $resolvConf"
       |  mkdir -p /etc/NetworkManager/conf.d
       |  write_file /etc/NetworkManager/conf.d/90-orphera-dns.conf <<'ORPHERA_EOF'
       |$managedMarker
       |[main]
       |dns=none
       |ORPHERA_EOF
       |  systemctl reload NetworkManager
       |fi
       |write_file $resolvConf <<'ORPHERA_EOF'
       |$staticContent
       |ORPHERA_EOF
       |cat $resolvConf
       |if [ -L $resolvConf ]; then echo '$resolvConf is still a symlink'; exit 1; fi
       |$serverChecks
       |$rotateCheck""".stripMargin

  // resolved path: put the stub symlink back if a round_robin run
  // replaced it (only when the file carries our marker — a hand-written
  // resolv.conf is left alone), write the drop-in, restart, and check
  // `resolvectl dns`'s "Global:" line has every server.
  private val resolvedBranch: String =
    val checks = config.servers
      .map(s =>
        s"""echo "$$G" | grep -qwF '$s' || { echo 'missing: $s'; exit 1; }"""
      )
      .mkString("\n")
    s"""echo "systemd-resolved present: using drop-in"
       |if [ ! -L $resolvConf ] && grep -qF -e '$managedMarker' -e '$legacyMarker' $resolvConf 2>/dev/null; then
       |  ln -sf $stubResolvConf $resolvConf
       |  echo "restored $resolvConf -> $stubResolvConf"
       |fi
       |mkdir -p $dropInDir
       |write_file $dropIn <<'ORPHERA_EOF'
       |$dropInText
       |ORPHERA_EOF
       |systemctl restart systemd-resolved
       |G=$$(resolvectl dns | grep '^Global:' || true)
       |echo "$$G"
       |$checks""".stripMargin

  private val applyScript: String =
    if config.roundRobin then
      // Static file regardless of resolved; drop any drop-in left by a
      // previous non-round_robin run.
      s"""set -e
         |$writeFileFn
         |rm -f $dropIn
         |$staticBranch""".stripMargin
    else
      s"""set -e
         |$writeFileFn
         |if [ "$$(systemctl show -p LoadState --value systemd-resolved.service 2>/dev/null)" = "loaded" ]; then
         |$resolvedBranch
         |else
         |echo "no systemd-resolved on this node: writing static $resolvConf"
         |$staticBranch
         |fi""".stripMargin

  val playbook: ClusterPlaybook =
    clusterPlaybook("gen-set-dns")(
      stage("gen-set-dns", config.nodes*)
        .task("apply DNS config (resolved drop-in, or static resolv.conf)")(
          Task.RunCommand(List("sh", "-c", applyScript), timeoutSeconds = 90)
        )
        .build
    )
