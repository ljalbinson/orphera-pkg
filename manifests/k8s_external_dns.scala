// SPDX-License-Identifier: Apache-2.0

import orphera.orchestrator.*
import orphera.orchestrator.ClusterPlaybookDsl.*

// Kubernetes cluster, step 16: external-dns. Needs k8s_dns.scala (BIND on tst40
// and tst41 with the TSIG key external-dns) and k8s_gateway.scala. Run
// k8s_dns.scala again first: it now also lets the key transfer the zone.
//
// external-dns (Helm chart external-dns/external-dns, namespace external-dns, one
// pod on an infra node) watches HTTPRoutes (Gateway API) and Services and
// writes the hostnames it finds into the zone k8s.ljalbinson.com on tst40 by
// RFC 2136 dynamic update, signed with the key external-dns (the secret
// k8s/dns-tsig, the same value k8s_dns.scala derives the key from). An HTTPRoute
// for app.k8s.ljalbinson.com on the Gateway becomes `app A 10.10.3.100`; a Service
// of type LoadBalancer with the annotation
// external-dns.alpha.kubernetes.io/hostname gets its own address.
//
// Ownership: every record it writes gets a TXT record naming the owner
// (txtOwnerId orphera-k8s). With policy `sync` it deletes only records it owns,
// so `api` and the `*` wildcard, which k8s_dns.scala created, are never touched.
// The redirect route http-to-https (hostname *.k8s.ljalbinson.com) is annotated
// external-dns.alpha.kubernetes.io/controller=ignore so external-dns does not
// adopt the wildcard.
//
// The last stage creates an HTTPRoute with a unique hostname and checks that the
// A record and its TXT record appear on tst40 (and reach tst41), deletes the route
// and checks that both are removed, and that `api` and the wildcard are intact.
//
// Not compiled where this was written; the first run on scala0 is the first
// compile. The chart value names are from memory (extraArgs as a list, env,
// sources, domainFilters, policy, registry, txtOwnerId, interval); if the install
// complains, the output says which.
object k8s_external_dns extends OrpheraClusterPlaybook:

  private val node = "tst30"
  private val ns = "external-dns"
  private val stateDir = "/etc/kubernetes/orphera"
  private val kc = "kubectl --kubeconfig /etc/kubernetes/admin.conf"
  private val zone = "k8s.ljalbinson.com"
  private val primaryIp = "10.10.3.40"
  private val secondaryIp = "10.10.3.41"
  private val gatewayIp = "10.10.3.100"
  private val apiVip = "10.10.3.20"
  private val keyName = "external-dns"

  // The same value k8s_dns.scala puts in the BIND key file.
  private val tsigSecret =
    val digest = java.security.MessageDigest
      .getInstance("SHA-256")
      .digest(Secrets.get("k8s/dns-tsig").getBytes("UTF-8"))
    val k = java.util.Base64.getEncoder.encodeToString(digest)
    Secrets.register(k)
    k

  private val valuesScript =
    s"""set -e
       |$kc create namespace $ns --dry-run=client -o yaml | $kc apply -f -
       |$kc -n $ns create secret generic external-dns-tsig --from-literal=secret='$tsigSecret' --dry-run=client -o yaml | $kc apply -f -
       |umask 077
       |cat > $stateDir/external-dns-values.yaml <<'EOF'
       |provider:
       |  name: rfc2136
       |sources:
       |- gateway-httproute
       |- service
       |domainFilters:
       |- $zone
       |policy: sync
       |registry: txt
       |txtOwnerId: orphera-k8s
       |interval: 30s
       |env:
       |- name: EXTERNAL_DNS_RFC2136_TSIG_SECRET
       |  valueFrom:
       |    secretKeyRef:
       |      name: external-dns-tsig
       |      key: secret
       |extraArgs:
       |- --rfc2136-host=$primaryIp
       |- --rfc2136-port=53
       |- --rfc2136-zone=$zone
       |- --rfc2136-tsig-keyname=$keyName
       |- --rfc2136-tsig-secret-alg=hmac-sha256
       |- --rfc2136-tsig-axfr
       |nodeSelector:
       |  pool: infra
       |EOF
       |# the redirect route's wildcard hostname must stay under the hand-made record
       |$kc -n gateway annotate httproute http-to-https external-dns.alpha.kubernetes.io/controller=ignore --overwrite
       |echo "namespace, TSIG secret and Helm values written"
       |""".stripMargin

  private val installScript =
    s"""set -e
       |export KUBECONFIG=/etc/kubernetes/admin.conf
       |helm repo add external-dns https://kubernetes-sigs.github.io/external-dns/ --force-update >/dev/null
       |helm repo update >/dev/null
       |VER=$$(helm show chart external-dns/external-dns | awk '/^version:/{print $$2}')
       |[ -n "$$VER" ] || { echo "the chart external-dns/external-dns was not found; its repository may have moved" >&2; exit 1; }
       |echo "installing external-dns chart $$VER"
       |helm upgrade --install external-dns external-dns/external-dns --version "$$VER" --namespace $ns -f $stateDir/external-dns-values.yaml --wait --timeout 10m
       |$kc -n $ns get pods -o wide
       |""".stripMargin

  private val testScript =
    s"""set -e
       |FAIL=0
       |chk() {  # description, wanted, got
       |  if [ "$$2" = "$$3" ]; then echo "ok    $$1: $$3"; else echo "FAIL  $$1: wanted '$$2', got '$$3'" >&2; FAIL=1; fi
       |}
       |NAME=extdns-test-$$(date +%s)
       |HOST=$$NAME.$zone
       |$kc create namespace extdns-test --dry-run=client -o yaml | $kc apply -f -
       |cat <<EOF | $kc apply -f -
       |apiVersion: v1
       |kind: Service
       |metadata:
       |  name: none
       |  namespace: extdns-test
       |spec:
       |  ports:
       |  - port: 80
       |---
       |apiVersion: gateway.networking.k8s.io/v1
       |kind: HTTPRoute
       |metadata:
       |  name: $$NAME
       |  namespace: extdns-test
       |spec:
       |  parentRefs:
       |  - name: public
       |    namespace: gateway
       |    sectionName: https
       |  hostnames:
       |  - $$HOST
       |  rules:
       |  - backendRefs:
       |    - name: none
       |      port: 80
       |EOF
       |# The wildcard answers the A query for any name, so presence is judged by the
       |# ownership TXT record external-dns writes next to the A record.
       |TXT=""
       |for I in $$(seq 1 36); do
       |  TXT=$$(dig +short +norecurse @$primaryIp TXT a-$$HOST)
       |  [ -n "$$TXT" ] && break
       |  sleep 5
       |done
       |if echo "$$TXT" | grep -q orphera-k8s; then echo "ok    the ownership TXT record for $$HOST names orphera-k8s"; else
       |  echo "FAIL  no ownership TXT record for $$HOST on the primary: '$$TXT'" >&2; FAIL=1
       |  $kc -n extdns-test get httproute $$NAME -o jsonpath='{.status.parents[*].conditions}' >&2 || true
       |fi
       |chk "the hostname resolves to the Gateway on the primary" $gatewayIp "$$(dig +short +norecurse @$primaryIp A $$HOST)"
       |TXT2=""
       |for I in $$(seq 1 15); do TXT2=$$(dig +short +norecurse @$secondaryIp TXT a-$$HOST); [ -n "$$TXT2" ] && break; sleep 2; done
       |if echo "$$TXT2" | grep -q orphera-k8s; then echo "ok    the records reach the secondary"; else echo "FAIL  the secondary has no TXT record for $$HOST" >&2; FAIL=1; fi
       |$kc delete namespace extdns-test --wait=false >/dev/null
       |GONE="x"
       |for I in $$(seq 1 48); do
       |  GONE=$$(dig +short +norecurse @$primaryIp TXT a-$$HOST)
       |  [ -z "$$GONE" ] && break
       |  sleep 5
       |done
       |chk "the ownership record is removed after the route is deleted" "" "$$GONE"
       |chk "api is untouched" $apiVip "$$(dig +short +norecurse @$primaryIp A api.$zone)"
       |chk "the wildcard is untouched" $gatewayIp "$$(dig +short +norecurse @$primaryIp A never-registered.$zone)"
       |[ "$$FAIL" -eq 0 ] || { $kc -n $ns logs deploy/external-dns --tail=30 >&2 || true; exit 1; }
       |""".stripMargin

  val playbook: ClusterPlaybook =
    clusterPlaybook("k8s-external-dns")(
      stage("external-dns-config", node)
        .task("namespace, TSIG secret, Helm values")(
          Task.RunCommand(List("sh", "-c", valuesScript), timeoutSeconds = 120)
        )
        .build,
      stage("install-external-dns", node)
        .task("helm install external-dns")(
          Task.RunCommand(List("sh", "-c", installScript), timeoutSeconds = 800)
        )
        .build,
      stage("test-external-dns", node)
        .task("create and delete a route, watch the zone")(
          Task.RunCommand(List("sh", "-c", testScript), timeoutSeconds = 900)
        )
        .task("external-dns ready")(
          Task.Debug(
            s"external-dns writes HTTPRoute and annotated Service hostnames under $zone to $primaryIp, signed with the key $keyName; it owns only what it creates."
          )
        )
        .build
    )
