// SPDX-License-Identifier: Apache-2.0

import orphera.orchestrator.*
import orphera.orchestrator.ClusterPlaybookDsl.*

// The TLS certificate for the dashboards on tst13 (Horizon and Skyline), from Keystone's test CA. It was the
// first three stages of horizon_single_node.scala when that was a cluster playbook, and stays one: the key is
// made on tst13, the certificate request goes to tst6 and is signed there, and the certificate comes back, and
// a description (`orphera apply`) works on one node and cannot move a file from one node to another.
//
//   orphera cluster-playbook manifests/dashboard_tls.scala
//
// Apply it before horizon_single_node.scala, which checks for the files and fails with a message when they
// are missing. Run again, it changes nothing while the certificate is still good: the key is made once and
// stays; a new certificate is signed only when there is none, it is for another key, does not verify against
// the CA, lacks the name, or expires within 30 days. skyline_single_node.scala makes the same certificate
// with the same steps when it is missing.
//
// Result on tst13, in /etc/orphera/tls: server.key, server.csr, server.crt, ca.crt and server-chain.crt.
//
// Keep `node`, `fqdn` and `nodeIp` in step with horizon_single_node.scala.
object dashboard_tls extends OrpheraClusterPlaybook:

  private val node = "tst13"
  private val fqdn = "tst13.ljalbinson.com"
  private val nodeIp = "10.10.5.25"

  private val ksNode = "tst6"
  private val ksCaDir = "/etc/kolla/keystone-ca"
  private val tlsDir = "/etc/orphera/tls"
  private val signDir = s"$ksCaDir/issued/$node"

  private val tlsRequestScript =
    s"""set -e
       |mkdir -p $tlsDir
       |chmod 750 $tlsDir
       |cd $tlsDir
       |if [ ! -f server.key ] || [ ! -f server.csr ]; then
       |  openssl req -newkey rsa:2048 -nodes -keyout server.key -out server.csr -subj "/CN=$fqdn"
       |fi
       |chown root:root server.key server.csr
       |chmod 600 server.key
       |chmod 644 server.csr
       |echo "key and certificate request ready in $tlsDir"""".stripMargin

  // The CA key must be there (keystone_single_node.scala makes it); then the directory for the certificates
  // of this node, readable by root only.
  private val signDirPlan: ApplyPlan =
    import Resources.{command, directory}
    val caKey = command(
      s"check that the CA key exists in $ksCaDir",
      s"""echo "no CA key in $ksCaDir: keystone_single_node.scala makes it" >&2
         |exit 1""".stripMargin,
      unless = s"[ -f $ksCaDir/ca.key ]"
    )
    ApplyPlan.orFail(
      "dashboard-tls-signdir",
      List(
        caKey,
        directory(signDir, mode = "0700", owner = "root", group = "root")
          .after(caKey)
      )
    )

  // Signs only when needed: no certificate yet, one for another key, one that does not verify against the CA,
  // lacks the name, or expires within 30 days.
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

  private val tlsInstallScript =
    s"""set -e
       |cd $tlsDir
       |if [ "$$(openssl x509 -in server.crt -noout -pubkey)" != "$$(openssl pkey -in server.key -pubout)" ]; then
       |  echo "the certificate from $ksNode is not for the key in $tlsDir" >&2; exit 1
       |fi
       |openssl verify -CAfile ca.crt server.crt
       |cat server.crt ca.crt > server-chain.crt
       |chown root:root server.crt server-chain.crt ca.crt
       |chmod 644 server.crt ca.crt server-chain.crt
       |echo "TLS ready: $$(openssl x509 -in server.crt -noout -subject -enddate | tr '\\n' ' ')"""".stripMargin

  val playbook: ClusterPlaybook =
    clusterPlaybook("dashboard-tls")(
      stage("dashboard-tls-request", node)
        .task("make the TLS key and certificate request")(
          Task
            .RunCommand(List("sh", "-c", tlsRequestScript), timeoutSeconds = 60)
        )
        .build,
      stage("dashboard-tls-sign", ksNode)
        .task(s"prepare $signDir")(
          Task.Apply(signDirPlan)
        )
        .task(s"fetch the certificate request from $node")(
          Task.DistributeFile(
            node,
            s"$tlsDir/server.csr",
            s"$signDir/server.csr",
            "root",
            "root",
            420
          )
        )
        .task(s"sign the certificate for $fqdn with the test CA")(
          Task.RunCommand(List("sh", "-c", signScript), timeoutSeconds = 60)
        )
        .build,
      stage("dashboard-tls-install", node)
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
        .task("check the certificate and write the chain")(
          Task
            .RunCommand(List("sh", "-c", tlsInstallScript), timeoutSeconds = 60)
        )
        .build
    )
