// SPDX-License-Identifier: Apache-2.0

import orphera.orchestrator.*
import orphera.orchestrator.ClusterPlaybookDsl.*

// The TLS certificate for Keycloak on tst26, from Keystone's test CA, and the check that Keystone on tst6 is set
// up to accept Keycloak's tokens. It was the first stages of keycloak_single_node.scala when that was a cluster
// playbook, and stays one, as dashboard_tls.scala does: the key is made on tst26, the certificate request goes to
// tst6 and is signed there with the CA key, and the certificate comes back, and a description (`orphera apply`)
// works on one node and cannot move a file from one node to another.
//
//   orphera cluster-playbook manifests/keycloak_tls.scala
//
// Apply it first of the three Keycloak parts: keycloak_tls.scala, then `orphera apply` of keycloak_database.scala
// and keycloak_single_node.scala, which checks for the files and fails with a message when they are missing.
// Run again, it changes nothing while the certificate is still good: the key is made once and stays; a new
// certificate is signed only when there is none, it is for another key, does not verify against the CA, lacks
// the name, or expires within 30 days.
//
// Stages:
//   - keystone-federation-ready (tst6): the vhost has the OIDC settings for this realm and keystone.conf has
//     openid. It fails, saying so, if the Keystone on tst6 was set up before that (run keystone_single_node.scala
//     again first).
//   - keycloak-tls-request (tst26): openssl, directories, key and certificate request.
//   - keycloak-tls-sign (tst6): signs the request with the test CA (only when the certificate is missing, is for
//     another key, or expires within 30 days).
//   - keycloak-tls-install (tst26): certificate and CA back, the chain Keycloak serves, the CA in the host's trust
//     store.
//
// Result on tst26, in /etc/keycloak/tls: server.key, server.csr, server.crt, ca.crt and server-chain.crt.
//
// Keep `node`, `fqdn`, `nodeIp` and the realm in step with keycloak_single_node.scala.
object keycloak_tls extends OrpheraClusterPlaybook:

  private val node = "tst26"
  private val fqdn = "tst26.ljalbinson.com"
  private val nodeIp = "10.10.5.38"
  private val httpsPort = 8443
  private val realm = "openstack"
  private val issuer = s"https://$fqdn:$httpsPort/realms/$realm"

  private val ksNode = "tst6"
  private val ksCaDir = "/etc/kolla/keystone-ca"
  private val ksConfDir = "/etc/kolla/keystone"

  private val confDir = "/etc/keycloak"
  private val tlsDir = "/etc/keycloak/tls"
  // On tst6: where the request and the signed certificate for this node live, beside the CA.
  private val signDir = s"$ksCaDir/issued/$node"

  private val hostPackages = List("openssl", "ca-certificates")

  private val waitAptIdleScript =
    """for i in $(seq 1 180); do
      |  if pgrep -f '^/usr/bin/python3 /usr/bin/unattended-upgrade$' >/dev/null || pgrep -x apt-get >/dev/null || pgrep -x apt >/dev/null || pgrep -x dpkg >/dev/null; then
      |    sleep 5
      |  else
      |    echo "apt and dpkg are idle"
      |    exit 0
      |  fi
      |done
      |echo "apt/dpkg still busy after 15 minutes (unattended-upgrades?)" >&2
      |exit 1""".stripMargin

  // ---- tst6: is Keystone ready for federation? ---------------------------

  private val federationReadyScript =
    s"""V=$ksConfDir/wsgi-keystone.conf
       |C=$ksConfDir/keystone.conf
       |[ -f "$$V" ] || { echo "$$V not found: Keystone is not set up on $ksNode (keystone_single_node.scala)" >&2; exit 1; }
       |if ! grep -q 'OIDCOAuthVerifyJwksUri "$issuer/protocol/openid-connect/certs"' "$$V"; then
       |  if grep -q 'OIDCOAuthVerifyJwksUri' "$$V"; then
       |    echo "Keystone's vhost points at another Keycloak realm than $issuer: make keycloakIssuer in keystone_single_node.scala agree, then run it again" >&2
       |  else
       |    echo "Keystone's vhost has no OIDC settings: run keystone_single_node.scala again first (it adds them), or its image lacks mod_auth_openidc (it warns when it does)" >&2
       |  fi
       |  exit 1
       |fi
       |grep -Eq '^methods *= *.*openid' "$$C" || { echo "openid is not in [auth] methods in $$C: run keystone_single_node.scala again first" >&2; exit 1; }
       |[ -f $ksCaDir/ca.key ] && [ -f $ksCaDir/ca.crt ] || { echo "the test CA is not in $ksCaDir" >&2; exit 1; }
       |echo "Keystone on $ksNode accepts Keycloak tokens from $issuer"""".stripMargin

  // ---- TLS: key and request on tst26, signature on tst6 --------------------

  // The key is made once and kept, so the certificate only changes when it has to.
  private val tlsRequestScript =
    s"""set -e
       |mkdir -p $confDir $tlsDir
       |chmod 750 $confDir $tlsDir
       |cd $tlsDir
       |if [ ! -f server.key ] || [ ! -f server.csr ]; then
       |  openssl req -newkey rsa:2048 -nodes -keyout server.key -out server.csr -subj "/CN=$fqdn"
       |fi
       |chown root:root server.key server.csr
       |chmod 640 server.key
       |chmod 644 server.csr
       |echo "key and certificate request ready in $tlsDir"""".stripMargin

  private val signDirScript =
    s"""set -e
       |[ -f $ksCaDir/ca.key ] || { echo "no CA key in $ksCaDir: keystone_single_node.scala makes it" >&2; exit 1; }
       |mkdir -p $signDir
       |chmod 700 $signDir
       |echo "$signDir ready"""".stripMargin

  // Signs only when needed: no certificate yet, a certificate for another key (the key on tst26 was remade), one
  // that does not verify against the CA, lacks the name, or expires within 30 days.
  private val signScript =
    s"""set -e
       |cd $signDir
       |openssl req -in server.csr -noout -verify
       |NEED=1
       |if [ -f server.crt ] \\
       |   && [ "$$(openssl x509 -in server.crt -noout -pubkey)" = "$$(openssl req -in server.csr -noout -pubkey)" ] \\
       |   && openssl verify -CAfile $ksCaDir/ca.crt server.crt >/dev/null 2>&1 \\
       |   && openssl x509 -in server.crt -noout -checkend 2592000 >/dev/null \\
       |   && openssl x509 -in server.crt -noout -ext subjectAltName | grep -q "DNS:$fqdn"; then
       |  NEED=0
       |fi
       |if [ "$$NEED" = "1" ]; then
       |  cat > server.ext <<EXT
       |subjectAltName=DNS:$fqdn,DNS:$node,IP:$nodeIp
       |basicConstraints=CA:FALSE
       |keyUsage=digitalSignature,keyEncipherment
       |extendedKeyUsage=serverAuth
       |EXT
       |  openssl x509 -req -in server.csr -CA $ksCaDir/ca.crt -CAkey $ksCaDir/ca.key -CAcreateserial -out server.crt -days 825 -sha256 -extfile server.ext
       |  echo "signed a new certificate for $fqdn"
       |else
       |  echo "the certificate for $fqdn is still good"
       |fi
       |cp $ksCaDir/ca.crt ca.crt
       |openssl x509 -in server.crt -noout -subject -enddate""".stripMargin

  // After the certificate and the CA came back: they must fit the key, the chain is what Keycloak serves (so a
  // client can take the CA from the handshake, as for Keystone), and the CA joins the host's trust store.
  private val tlsInstallScript =
    s"""set -e
       |cd $tlsDir
       |if [ "$$(openssl x509 -in server.crt -noout -pubkey)" != "$$(openssl pkey -in server.key -pubout)" ]; then
       |  echo "the certificate from $ksNode is not for the key in $tlsDir" >&2; exit 1
       |fi
       |openssl verify -CAfile ca.crt server.crt
       |cat server.crt ca.crt > server-chain.crt
       |chown root:root server.crt server-chain.crt ca.crt
       |chmod 644 server.crt ca.crt
       |chmod 640 server-chain.crt
       |cp ca.crt /usr/local/share/ca-certificates/orphera-test-ca.crt
       |update-ca-certificates
       |echo "TLS ready: $$(openssl x509 -in server.crt -noout -subject -enddate | tr '\\n' ' ')"""".stripMargin

  val playbook: ClusterPlaybook =
    clusterPlaybook("keycloak-tls")(
      stage("keystone-federation-ready", ksNode)
        .task("Keystone checks Keycloak tokens (vhost and keystone.conf)")(
          Task.RunCommand(List("sh", "-c", federationReadyScript))
        )
        .build,

      stage("keycloak-tls-request", node)
        .task("wait for apt and dpkg to be idle")(
          Task.RunCommand(
            List("sh", "-c", waitAptIdleScript),
            timeoutSeconds = 960
          )
        )
        .task("install openssl")(
          Task.Install(packages = hostPackages, updateCache = true)
        )
        .task("make the TLS key and certificate request")(
          Task
            .RunCommand(List("sh", "-c", tlsRequestScript), timeoutSeconds = 60)
        )
        .build,

      stage("keycloak-tls-sign", ksNode)
        .task(s"prepare $signDir")(
          Task.RunCommand(List("sh", "-c", signDirScript))
        )
        .task(s"fetch the certificate request from $node")(
          Task.DistributeFile(
            node,
            s"$tlsDir/server.csr",
            s"$signDir/server.csr",
            "root",
            "root",
            420 // 0644
          )
        )
        .task(s"sign the certificate for $fqdn with the test CA")(
          Task.RunCommand(List("sh", "-c", signScript), timeoutSeconds = 60)
        )
        .build,

      stage("keycloak-tls-install", node)
        .task(s"fetch the certificate from $ksNode")(
          Task.DistributeFile(
            ksNode,
            s"$signDir/server.crt",
            s"$tlsDir/server.crt",
            "root",
            "root",
            420
          )
        )
        .task(s"fetch the CA certificate from $ksNode")(
          Task.DistributeFile(
            ksNode,
            s"$signDir/ca.crt",
            s"$tlsDir/ca.crt",
            "root",
            "root",
            420
          )
        )
        .task("install the certificate chain and trust the CA")(
          Task.RunCommand(
            List("sh", "-c", tlsInstallScript),
            timeoutSeconds = 60
          )
        )
        .build
    )
