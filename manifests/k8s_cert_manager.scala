// SPDX-License-Identifier: Apache-2.0

import orphera.orchestrator.*
import orphera.orchestrator.ClusterPlaybookDsl.*

// Kubernetes cluster, step 6: certificates. Installs cert-manager and a
// ClusterIssuer `orphera-ca` that signs with a sub-CA of the lab's test CA
// ("Orphera Test CA", created by keystone_single_node.scala on tst6). Needs
// k8s_cilium.scala (the nodes must be Ready).
//
// The sub-CA's private key is made on tst30 and never leaves the cluster
// side: tst30 makes a key and a certificate request, tst6 signs the request
// with the test CA (valid 5 years, may sign only end-entity certificates:
// pathlen 0), and the signed certificate and the root certificate come back
// to tst30. The root CA's key does not go into the cluster. The key and
// certificate become the Secret `orphera-sub-ca` in the cert-manager
// namespace. Re-running keeps the existing key and certificate.
//
// Certificates issued this way are trusted by anything that trusts the test
// CA, which every lab node already does.
//
// The last stage asks for a certificate for test.k8s.ljalbinson.com, waits for
// it to be issued and verifies it against the root CA; the test objects are
// removed afterwards.
//
// Not compiled where this was written; the first run on scala0 is the first
// compile.
object k8s_cert_manager extends OrpheraClusterPlaybook:

  private val node = "tst30"
  private val caNode = "tst6"
  private val caDir = "/etc/kolla/keystone-ca"
  private val stateDir = "/etc/kubernetes/orphera"
  private val kc = "kubectl --kubeconfig /etc/kubernetes/admin.conf"

  private val csrScript =
    s"""set -e
       |cd $stateDir
       |if [ ! -f subca.key ]; then
       |  umask 077
       |  openssl genrsa -out subca.key 4096
       |fi
       |openssl req -new -key subca.key -out subca.csr -subj "/CN=Orphera Kubernetes Sub CA"
       |echo "sub-CA request written: $$(openssl req -in subca.csr -noout -subject)"
       |""".stripMargin

  private val signScript =
    s"""set -e
       |cd $caDir
       |if [ -f k8s-subca.crt ]; then echo "k8s-subca.crt already exists, kept"; exit 0; fi
       |cat > k8s-subca.ext <<'EXT'
       |basicConstraints = critical, CA:TRUE, pathlen:0
       |keyUsage = critical, keyCertSign, cRLSign
       |subjectKeyIdentifier = hash
       |authorityKeyIdentifier = keyid,issuer
       |EXT
       |openssl x509 -req -in k8s-subca.csr -CA ca.crt -CAkey ca.key -CAcreateserial \\
       |  -out k8s-subca.crt -days 1825 -extfile k8s-subca.ext
       |openssl verify -CAfile ca.crt k8s-subca.crt
       |""".stripMargin

  private val installScript =
    s"""set -e
       |export KUBECONFIG=/etc/kubernetes/admin.conf
       |helm repo add jetstack https://charts.jetstack.io --force-update >/dev/null
       |helm repo update >/dev/null
       |VER=$$(helm show chart jetstack/cert-manager | awk '/^version:/{print $$2}')
       |echo "installing cert-manager chart $$VER"
       |helm upgrade --install cert-manager jetstack/cert-manager --version "$$VER" \\
       |  --namespace cert-manager --create-namespace --set crds.enabled=true --wait --timeout 10m
       |echo "cert-manager $$VER installed"
       |""".stripMargin

  private val issuerScript =
    s"""set -e
       |cd $stateDir
       |openssl verify -CAfile ca.crt subca.crt
       |# the key must belong to the certificate
       |[ "$$(openssl x509 -in subca.crt -noout -pubkey | openssl sha256)" = "$$(openssl pkey -in subca.key -pubout | openssl sha256)" ] \\
       |  || { echo "subca.key does not match subca.crt; remove $caDir/k8s-subca.crt on $caNode and re-run" >&2; exit 1; }
       |cat subca.crt ca.crt > subca-chain.crt
       |$kc -n cert-manager create secret tls orphera-sub-ca --cert=subca-chain.crt --key=subca.key \\
       |  --dry-run=client -o yaml | $kc apply -f -
       |cat <<EOF | $kc apply -f -
       |apiVersion: cert-manager.io/v1
       |kind: ClusterIssuer
       |metadata:
       |  name: orphera-ca
       |spec:
       |  ca:
       |    secretName: orphera-sub-ca
       |EOF
       |$kc wait --for=condition=Ready clusterissuer/orphera-ca --timeout=120s
       |""".stripMargin

  private val testScript =
    s"""set -e
       |$kc create namespace cm-test --dry-run=client -o yaml | $kc apply -f -
       |cat <<EOF | $kc apply -f -
       |apiVersion: cert-manager.io/v1
       |kind: Certificate
       |metadata:
       |  name: test
       |  namespace: cm-test
       |spec:
       |  secretName: test-tls
       |  dnsNames:
       |  - test.k8s.ljalbinson.com
       |  issuerRef:
       |    name: orphera-ca
       |    kind: ClusterIssuer
       |EOF
       |$kc -n cm-test wait --for=condition=Ready certificate/test --timeout=180s
       |$kc -n cm-test get secret test-tls -o jsonpath='{.data.tls\\.crt}' | base64 -d > /tmp/cm-test.crt
       |$kc delete namespace cm-test --wait=false >/dev/null
       |cd $stateDir
       |openssl verify -CAfile ca.crt -untrusted subca.crt /tmp/cm-test.crt
       |openssl x509 -in /tmp/cm-test.crt -noout -subject -issuer -ext subjectAltName
       |rm -f /tmp/cm-test.crt
       |""".stripMargin

  val playbook: ClusterPlaybook =
    clusterPlaybook("k8s-cert-manager")(
      stage("sub-ca-request", node)
        .task("make the sub-CA key and request")(
          Task.RunCommand(List("sh", "-c", csrScript), timeoutSeconds = 120)
        )
        .build,
      stage("send-request", caNode)
        .task("copy the request to the CA host")(
          Task.DistributeFile(node, s"$stateDir/subca.csr", s"$caDir/k8s-subca.csr", "root", "root", 420)
        )
        .build,
      stage("sign-sub-ca", caNode)
        .task("sign the request with the test CA")(
          Task.RunCommand(List("sh", "-c", signScript), timeoutSeconds = 60)
        )
        .build,
      stage("fetch-certificates", node)
        .task("copy the signed sub-CA certificate back")(
          Task.DistributeFile(caNode, s"$caDir/k8s-subca.crt", s"$stateDir/subca.crt", "root", "root", 420)
        )
        .task("copy the root CA certificate")(
          Task.DistributeFile(caNode, s"$caDir/ca.crt", s"$stateDir/ca.crt", "root", "root", 420)
        )
        .build,
      stage("install-cert-manager", node)
        .task("helm install cert-manager")(
          Task.RunCommand(List("sh", "-c", installScript), timeoutSeconds = 900)
        )
        .build,
      stage("create-issuer", node)
        .task("create the sub-CA secret and the ClusterIssuer")(
          Task.RunCommand(List("sh", "-c", issuerScript), timeoutSeconds = 180)
        )
        .build,
      stage("test-certificate", node)
        .task("issue and verify a certificate")(
          Task.RunCommand(List("sh", "-c", testScript), timeoutSeconds = 300)
        )
        .task("cert-manager ready")(
          Task.Debug(
            "cert-manager is installed; ClusterIssuer orphera-ca signs with a sub-CA of the Orphera Test CA " +
              "and a test certificate verified against the root CA."
          )
        )
        .build
    )
