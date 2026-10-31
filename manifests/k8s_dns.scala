// SPDX-License-Identifier: Apache-2.0

import orphera.orchestrator.*
import orphera.orchestrator.ClusterPlaybookDsl.*

// Kubernetes cluster, step 15: DNS servers for the zone k8s.ljalbinson.com.
// BIND on tst40 (primary, 10.10.3.40) and tst41 (secondary, 10.10.3.41). They
// are authoritative only (no recursion). The zone is dynamic: external-dns
// (k8s_external_dns.scala) adds and removes records with RFC 2136 updates
// signed by the TSIG key `external-dns` (hmac-sha256, from the secret
// k8s/dns-tsig). Nobody else may update it. The secondary copies the zone from
// the primary (NOTIFY, then zone transfer to 10.10.3.41 and to holders of the
// key; external-dns lists the zone with a signed transfer).
//
// The zone starts with the SOA, the two NS records (tst40.ljalbinson.com and
// tst41.ljalbinson.com, which live in the parent zone, so no glue is needed),
// and the two records the cluster already depends on:
//   api.k8s.ljalbinson.com   A  10.10.3.20   (the API server VIP)
//   *.k8s.ljalbinson.com     A  10.10.3.100  (the Gateway)
// The zone file is only created when it does not exist: once BIND runs the zone,
// its journal holds the dynamic changes and the file is BIND's to rewrite.
//
// Zone files are under /var/lib/bind (primary) and /var/cache/bind (secondary),
// the directories the Ubuntu AppArmor profile lets named write; a zone in /etc/bind
// failed on dns2 with "managed-keys.bind.jnl: permission denied".
//
// THE PARENT ZONE MUST DELEGATE. Until ljalbinson.com (dns1, dns2) has
//   k8s   IN NS tst40.ljalbinson.com.
//   k8s   IN NS tst41.ljalbinson.com.
// and A records for tst40 and tst41, nobody asks these servers, and the records
// api.k8s and *.k8s that were added to the parent zone by hand must be removed from it
// (they sit below the zone cut). Add the delegation AFTER this playbook has
// passed, so the new servers already answer when the cut is made.
// A lab resolver that has `forwarders` set (dns2, dns3) sends names below the
// delegation to the forwarders, which answer NXDOMAIN. Each such resolver also
// needs, in named.conf.local:
//   zone "k8s.ljalbinson.com" { type forward; forward only;
//                               forwarders { 10.10.3.40; 10.10.3.41; }; };
//
// The last stage checks both servers directly: the seed records, a signed update
// accepted, an unsigned one refused, the secondary receiving the change.
//
// Not compiled where this was written; the first run on scala0 is the first
// compile.
object k8s_dns extends OrpheraClusterPlaybook:

  private val primary = "tst40"
  private val secondary = "tst41"
  private val primaryIp = "10.10.3.40"
  private val secondaryIp = "10.10.3.41"
  private val zone = "k8s.ljalbinson.com"
  private val keyName = "external-dns"
  private val keyFile = "/etc/bind/external-dns.key"
  private val apiVip = "10.10.3.20"
  private val gatewayIp = "10.10.3.100"

  // base64 of 32 bytes: a valid hmac-sha256 secret.
  private val tsigSecret =
    val digest = java.security.MessageDigest
      .getInstance("SHA-256")
      .digest(Secrets.get("k8s/dns-tsig").getBytes("UTF-8"))
    val k = java.util.Base64.getEncoder.encodeToString(digest)
    Secrets.register(k)
    k

  private val optionsScript =
    """cat > /etc/bind/named.conf.options <<'EOF'
      |options {
      |    directory "/var/cache/bind";
      |    listen-on { any; };
      |    listen-on-v6 { none; };
      |    allow-query { any; };
      |    recursion no;
      |    allow-transfer { none; };
      |    dnssec-validation no;
      |};
      |EOF""".stripMargin

  private val primaryScript =
    s"""set -e
       |$optionsScript
       |umask 027
       |cat > $keyFile <<'EOF'
       |key "$keyName" {
       |    algorithm hmac-sha256;
       |    secret "$tsigSecret";
       |};
       |EOF
       |chown root:bind $keyFile
       |chmod 640 $keyFile
       |cat > /etc/bind/named.conf.local <<'EOF'
       |include "$keyFile";
       |zone "$zone" {
       |    type master;
       |    file "/var/lib/bind/db.$zone";
       |    allow-update { key "$keyName"; };
       |    allow-transfer { $secondaryIp; key "$keyName"; };
       |    also-notify { $secondaryIp; };
       |    notify yes;
       |};
       |EOF
       |if [ ! -f /var/lib/bind/db.$zone ]; then
       |  cat > /var/lib/bind/db.$zone <<'EOF'
       |$$TTL 300
       |@   IN SOA $primary.ljalbinson.com. hostmaster.ljalbinson.com. ( 1 3600 600 1209600 300 )
       |    IN NS  $primary.ljalbinson.com.
       |    IN NS  $secondary.ljalbinson.com.
       |api IN A   $apiVip
       |*   IN A   $gatewayIp
       |EOF
       |  chown bind:bind /var/lib/bind/db.$zone
       |  echo "zone file created"
       |else
       |  echo "zone file exists, kept"
       |fi
       |named-checkconf
       |named-checkzone $zone /var/lib/bind/db.$zone
       |systemctl enable named >/dev/null 2>&1 || true
       |systemctl restart named
       |sleep 2
       |systemctl is-active --quiet named || { echo "named failed to start; see journalctl -xeu named" >&2; exit 1; }
       |echo "$$(hostname): BIND primary for $zone running"
       |""".stripMargin

  private val secondaryScript =
    s"""set -e
       |$optionsScript
       |cat > /etc/bind/named.conf.local <<'EOF'
       |zone "$zone" {
       |    type slave;
       |    masters { $primaryIp; };
       |    file "/var/cache/bind/db.$zone";
       |    allow-transfer { none; };
       |};
       |EOF
       |named-checkconf
       |systemctl enable named >/dev/null 2>&1 || true
       |systemctl restart named
       |sleep 2
       |systemctl is-active --quiet named || { echo "named failed to start; see journalctl -xeu named" >&2; exit 1; }
       |for I in $$(seq 1 30); do
       |  [ -n "$$(dig +short +norecurse @127.0.0.1 SOA $zone)" ] && break
       |  sleep 2
       |done
       |[ -n "$$(dig +short +norecurse @127.0.0.1 SOA $zone)" ] || { echo "the secondary has not received the zone from $primaryIp" >&2; exit 1; }
       |echo "$$(hostname): BIND secondary for $zone running, zone received"
       |""".stripMargin

  private val testScript =
    s"""set -e
       |FAIL=0
       |chk() {  # description, wanted, got
       |  if [ "$$2" = "$$3" ]; then echo "ok    $$1: $$3"; else echo "FAIL  $$1: wanted '$$2', got '$$3'" >&2; FAIL=1; fi
       |}
       |for S in $primaryIp $secondaryIp; do
       |  chk "api.$zone at $$S" $apiVip "$$(dig +short +norecurse @$$S api.$zone)"
       |  chk "a name under the wildcard at $$S" $gatewayIp "$$(dig +short +norecurse @$$S anything.$zone)"
       |  chk "authoritative answer (aa) at $$S" 1 "$$(dig +norecurse @$$S SOA $zone | grep -c 'flags:.* aa')"
       |done
       |# external-dns lists the zone with a signed zone transfer
       |AXFR=$$(dig +noall +answer +comments -k $keyFile @$primaryIp AXFR $zone 2>&1 || true)
       |chk "a signed zone transfer works (SOA twice)" 2 "$$(echo "$$AXFR" | grep -cw SOA || true)"
       |[ "$$(echo "$$AXFR" | grep -cw SOA || true)" = 2 ] || echo "$$AXFR" >&2
       |# a signed update is accepted
       |printf 'server $primaryIp\\nzone $zone\\nupdate add orphera-test.$zone 60 TXT "ok"\\nsend\\n' | nsupdate -k $keyFile
       |GOT=""
       |for I in $$(seq 1 15); do GOT=$$(dig +short +norecurse @$secondaryIp TXT orphera-test.$zone); [ -n "$$GOT" ] && break; sleep 1; done
       |chk "a signed update reaches the secondary" '"ok"' "$$GOT"
       |# an unsigned one is refused
       |REFUSED=$$(printf 'server $primaryIp\\nzone $zone\\nupdate add orphera-unsigned.$zone 60 TXT "no"\\nsend\\n' | nsupdate 2>&1 || true)
       |case "$$REFUSED" in *REFUSED*|*failed*) echo "ok    an unsigned update is refused" ;; *) echo "FAIL  an unsigned update was not refused: $$REFUSED" >&2; FAIL=1 ;; esac
       |chk "the unsigned record does not exist" "" "$$(dig +short +norecurse @$primaryIp TXT orphera-unsigned.$zone)"
       |printf 'server $primaryIp\\nzone $zone\\nupdate delete orphera-test.$zone TXT\\nsend\\n' | nsupdate -k $keyFile
       |[ "$$FAIL" -eq 0 ] || exit 1
       |""".stripMargin

  val playbook: ClusterPlaybook =
    clusterPlaybook("k8s-dns")(
      stage("install-bind", primary, secondary)
        .task("install bind9")(
          Task.Install(
            packages = List("bind9", "bind9-utils", "dnsutils"),
            updateCache = true
          )
        )
        .build,
      stage("configure-primary", primary)
        .task("zone, TSIG key, named")(
          Task.RunCommand(List("sh", "-c", primaryScript), timeoutSeconds = 120)
        )
        .build,
      stage("configure-secondary", secondary)
        .task("secondary zone, named")(
          Task
            .RunCommand(List("sh", "-c", secondaryScript), timeoutSeconds = 120)
        )
        .build,
      stage("test-dns", primary)
        .task("query both servers, update the zone")(
          Task.RunCommand(List("sh", "-c", testScript), timeoutSeconds = 120)
        )
        .task("dns ready")(
          Task.Debug(
            s"BIND answers for $zone on $primaryIp (primary) and $secondaryIp (secondary); external-dns may update it with the key $keyName. " +
              s"Next: add the delegation to ljalbinson.com (k NS tst40.ljalbinson. / tst41.ljalbinson.) and remove api.k8s and *.k8s from the parent zone."
          )
        )
        .build
    )
