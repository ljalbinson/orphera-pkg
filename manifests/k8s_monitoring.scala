// SPDX-License-Identifier: Apache-2.0

import orphera.orchestrator.*
import orphera.orchestrator.ClusterPlaybookDsl.*

// Kubernetes cluster, step 12: monitoring. Needs k8s_ceph_csi.scala (volumes),
// k8s_gateway.scala (the front door) and k8s_oidc.scala (the Keycloak realm
// `k8s`, whose helper this one does not use; it has its own).
//
// Installs the Helm chart kube-prometheus-stack as the release `kps` in the
// namespace `monitoring`: Prometheus Operator, Prometheus (20 GiB volume, 15
// days), Alertmanager (2 GiB), Grafana (5 GiB), node-exporter on every node,
// kube-state-metrics, and the stock rules and dashboards. Everything with state
// runs on the infra nodes (nodeSelector pool=infra) and its volumes are Ceph
// RBD volumes (StorageClass ceph-rbd).
//
// Grafana is published at https://grafana.k8s.ljalbinson.com through the Gateway
// (an HTTPRoute on the https listener). People sign in with Keycloak: a client
// `grafana` in the realm `k8s` (confidential, secret k8s/grafana-client) with the
// `groups` claim; the group decides the Grafana role (k8s-admins -> Admin,
// k8s-developers -> Editor, k8s-viewers -> Viewer; a user in none of them is
// refused). Grafana trusts Keycloak's certificate through a ConfigMap holding the
// root CA. A local `admin` account stays for emergencies, with the password in the
// secret k8s/grafana-admin. Prometheus and Alertmanager are NOT published; reach
// them with `kubectl -n monitoring port-forward`.
//
// Known gaps, on purpose for now:
//   - The etcd, scheduler and controller-manager metrics are scraped, which needs them
//     to listen on the node address (set at init by k8s_cluster.scala, or afterwards by
//     k8s_kubeadm_config.scala); until then those three targets show as down.
//   - Cilium and Hubble metrics are not enabled.
//   - Alertmanager has no receivers, so alerts show in its UI and in Grafana only.
//
// The last stage queries Prometheus (through the API server's service proxy):
// all 10 nodes report node-exporter metrics, the 3 API servers and the kubelets
// are up; and checks Grafana over HTTPS: the health endpoint says the database
// is ok, and /login/generic_oauth redirects to
// the Keycloak realm k8s.
//
// Not compiled where this was written; the first run on scala0 is the first
// compile. Chart value names are from memory of kube-prometheus-stack and Grafana's
// chart; if Helm reports an unknown value or a pod does not start, the output says
// which.
object k8s_monitoring extends OrpheraClusterPlaybook:

  private val node = "tst30"
  private val kcNode = "tst26"
  private val kcFqdn = "tst26.ljalbinson.com"
  private val kcPort = 8443
  private val kcRealm = "k8s"
  private val issuer = s"https://$kcFqdn:$kcPort/realms/$kcRealm"
  private val domain = "k8s.ljalbinson.com"
  private val grafanaHost = s"grafana.$domain"
  private val ns = "monitoring"
  private val stateDir = "/etc/kubernetes/orphera"
  private val kc = "kubectl --kubeconfig /etc/kubernetes/admin.conf"
  private val helper = "/usr/local/sbin/orphera-keycloak-grafana"

  private val kcAdminPassword = Secrets.secret("keycloak/admin")
  private val clientSecret = Secrets.secret("k8s/grafana-client")
  private val grafanaAdminPassword = Secrets.secret("k8s/grafana-admin")

  // ---- Keycloak client (runs on tst26) ----------------------------------

  private val helperPython =
    """#!/usr/bin/python3
import json, ssl, sys, urllib.error, urllib.parse, urllib.request

KC = 'https://@@FQDN@@:@@PORT@@'
REALM = '@@REALM@@'
CLIENT_ID = 'grafana'
CLIENT_SECRET = '@@SECRET@@'
ADMIN_PW = '@@ADMINPW@@'
ROOT = 'https://@@HOST@@'
CTX = ssl.create_default_context(cafile='/etc/keycloak/tls/ca.crt')
CTX.verify_flags &= ~getattr(ssl, 'VERIFY_X509_STRICT', 0)


def die(msg):
    sys.stderr.write(msg + '\n')
    sys.exit(1)


def call(method, url, body=None, headers=None, form=None):
    hdrs = dict(headers or {})
    data = None
    if form is not None:
        data = urllib.parse.urlencode(form).encode()
        hdrs['Content-Type'] = 'application/x-www-form-urlencoded'
    elif body is not None:
        data = json.dumps(body).encode()
        hdrs['Content-Type'] = 'application/json'
    elif method in ('POST', 'PUT', 'PATCH'):
        data = b''
    req = urllib.request.Request(url, data=data, method=method, headers=hdrs)
    try:
        r = urllib.request.urlopen(req, context=CTX, timeout=60)
        status, text = r.status, r.read().decode()
    except urllib.error.HTTPError as e:
        status, text = e.code, e.read().decode()
    try:
        parsed = json.loads(text) if text else {}
    except ValueError:
        parsed = {'raw': text[:300]}
    return status, parsed


s, p = call('POST', KC + '/realms/master/protocol/openid-connect/token',
            form={'grant_type': 'password', 'client_id': 'admin-cli', 'username': 'admin', 'password': ADMIN_PW})
if s != 200 or 'access_token' not in p:
    die('Keycloak admin token: HTTP %s %s' % (s, p))
H = {'Authorization': 'Bearer ' + p['access_token']}


def kc(method, path, body=None):
    return call(method, KC + '/admin/realms' + path, body, H)


s, p = kc('GET', '/' + REALM)
if s != 200:
    die('realm %s not found (HTTP %s); run k8s_oidc.scala first' % (REALM, s))
client = {
    'clientId': CLIENT_ID, 'name': 'Grafana', 'protocol': 'openid-connect', 'enabled': True,
    'publicClient': False, 'clientAuthenticatorType': 'client-secret', 'secret': CLIENT_SECRET,
    'standardFlowEnabled': True, 'directAccessGrantsEnabled': False, 'serviceAccountsEnabled': False,
    'rootUrl': ROOT, 'baseUrl': ROOT, 'redirectUris': [ROOT + '/login/generic_oauth'], 'webOrigins': [ROOT],
    'attributes': {'post.logout.redirect.uris': ROOT + '/*'},
}
s, found = kc('GET', '/%s/clients?clientId=%s' % (REALM, CLIENT_ID))
if s != 200:
    die('listing clients: HTTP %s %s' % (s, found))
if found:
    s, p = kc('PUT', '/%s/clients/%s' % (REALM, found[0]['id']), client)
    if s != 204:
        die('updating client: HTTP %s %s' % (s, p))
else:
    s, p = kc('POST', '/%s/clients' % REALM, client)
    if s != 201:
        die('creating client: HTTP %s %s' % (s, p))
    s, found = kc('GET', '/%s/clients?clientId=%s' % (REALM, CLIENT_ID))
cid = found[0]['id']
mapper = {'name': 'groups', 'protocol': 'openid-connect', 'protocolMapper': 'oidc-group-membership-mapper',
          'config': {'claim.name': 'groups', 'full.path': 'false', 'id.token.claim': 'true',
                     'access.token.claim': 'true', 'userinfo.token.claim': 'true'}}
s, mappers = kc('GET', '/%s/clients/%s/protocol-mappers/models' % (REALM, cid))
if s != 200:
    die('listing mappers: HTTP %s %s' % (s, mappers))
old = [m for m in mappers if m.get('name') == 'groups']
if old:
    s, p = kc('PUT', '/%s/clients/%s/protocol-mappers/models/%s' % (REALM, cid, old[0]['id']), dict(mapper, id=old[0]['id']))
    ok = 204
else:
    s, p = kc('POST', '/%s/clients/%s/protocol-mappers/models' % (REALM, cid), mapper)
    ok = 201
if s != ok:
    die('groups mapper: HTTP %s %s' % (s, p))
print('client %s in realm %s ready: redirect %s/login/generic_oauth' % (CLIENT_ID, REALM, ROOT))
"""

  private val helperText =
    helperPython
      .replace("@@FQDN@@", kcFqdn)
      .replace("@@PORT@@", kcPort.toString)
      .replace("@@REALM@@", kcRealm)
      .replace("@@SECRET@@", clientSecret.reveal)
      .replace("@@ADMINPW@@", kcAdminPassword.reveal)
      .replace("@@HOST@@", grafanaHost)

  private val clientScript =
    s"""set -e
       |umask 077
       |cat > $helper <<'PYEOF'
       |$helperText
       |PYEOF
       |chmod 700 $helper
       |$helper
       |""".stripMargin

  // ---- cluster side (tst30) ----------------------------------------------

  private val prepareScript =
    s"""set -e
       |[ -s $stateDir/ca.crt ] || { echo "$stateDir/ca.crt is missing; run k8s_cert_manager.scala first" >&2; exit 1; }
       |$kc create namespace $ns --dry-run=client -o yaml | $kc apply -f -
       |$kc -n $ns create configmap orphera-ca --from-file=ca.crt=$stateDir/ca.crt --dry-run=client -o yaml | $kc apply -f -
       |$kc -n $ns create secret generic grafana-oauth \\
       |  --from-literal=GF_AUTH_GENERIC_OAUTH_CLIENT_SECRET='${clientSecret.reveal}' \\
       |  --dry-run=client -o yaml | $kc apply -f -
       |umask 077
       |cat > $stateDir/monitoring-values.yaml <<EOF
       |fullnameOverride: kps
       |kubeEtcd:
       |  enabled: true
       |kubeScheduler:
       |  enabled: true
       |kubeControllerManager:
       |  enabled: true
       |kubeProxy:
       |  enabled: false
       |prometheus:
       |  prometheusSpec:
       |    nodeSelector:
       |      pool: infra
       |    retention: 15d
       |    serviceMonitorSelectorNilUsesHelmValues: false
       |    podMonitorSelectorNilUsesHelmValues: false
       |    storageSpec:
       |      volumeClaimTemplate:
       |        spec:
       |          storageClassName: ceph-rbd
       |          accessModes: [ReadWriteOnce]
       |          resources:
       |            requests:
       |              storage: 20Gi
       |alertmanager:
       |  alertmanagerSpec:
       |    nodeSelector:
       |      pool: infra
       |    storage:
       |      volumeClaimTemplate:
       |        spec:
       |          storageClassName: ceph-rbd
       |          accessModes: [ReadWriteOnce]
       |          resources:
       |            requests:
       |              storage: 2Gi
       |prometheusOperator:
       |  nodeSelector:
       |    pool: infra
       |kube-state-metrics:
       |  nodeSelector:
       |    pool: infra
       |grafana:
       |  fullnameOverride: grafana
       |  adminPassword: '${grafanaAdminPassword.reveal}'
       |  nodeSelector:
       |    pool: infra
       |  deploymentStrategy:
       |    type: Recreate
       |  persistence:
       |    enabled: true
       |    type: pvc
       |    storageClassName: ceph-rbd
       |    size: 5Gi
       |  envFromSecret: grafana-oauth
       |  extraConfigmapMounts:
       |  - name: orphera-ca
       |    mountPath: /etc/grafana/ca
       |    configMap: orphera-ca
       |    readOnly: true
       |  grafana.ini:
       |    server:
       |      root_url: https://$grafanaHost
       |    auth.generic_oauth:
       |      enabled: true
       |      name: Keycloak
       |      allow_sign_up: true
       |      client_id: grafana
       |      scopes: openid profile email
       |      auth_url: $issuer/protocol/openid-connect/auth
       |      token_url: $issuer/protocol/openid-connect/token
       |      api_url: $issuer/protocol/openid-connect/userinfo
       |      use_pkce: true
       |      login_attribute_path: preferred_username
       |      groups_attribute_path: groups
       |      role_attribute_path: "contains(groups[*], 'k8s-admins') && 'Admin' || contains(groups[*], 'k8s-developers') && 'Editor' || contains(groups[*], 'k8s-viewers') && 'Viewer'"
       |      role_attribute_strict: true
       |      tls_client_ca: /etc/grafana/ca/ca.crt
       |EOF
       |echo "namespace, CA ConfigMap, OAuth Secret and values written"
       |""".stripMargin

  private val installScript =
    s"""set -e
       |export KUBECONFIG=/etc/kubernetes/admin.conf
       |helm repo add prometheus-community https://prometheus-community.github.io/helm-charts --force-update >/dev/null
       |helm repo update >/dev/null
       |VER=$$(helm show chart prometheus-community/kube-prometheus-stack | awk '/^version:/{print $$2}')
       |echo "installing kube-prometheus-stack chart $$VER"
       |helm upgrade --install kps prometheus-community/kube-prometheus-stack --version "$$VER" \\
       |  --namespace $ns -f $stateDir/monitoring-values.yaml --wait --timeout 20m
       |$kc -n $ns get pods -o wide
       |echo "kube-prometheus-stack $$VER installed"
       |""".stripMargin

  private val routeScript =
    s"""set -e
       |cat <<EOF | $kc apply -f -
       |apiVersion: gateway.networking.k8s.io/v1
       |kind: HTTPRoute
       |metadata:
       |  name: grafana
       |  namespace: $ns
       |spec:
       |  parentRefs:
       |  - name: public
       |    namespace: gateway
       |    sectionName: https
       |  hostnames:
       |  - $grafanaHost
       |  rules:
       |  - backendRefs:
       |    - name: grafana
       |      port: 80
       |EOF
       |$kc -n $ns wait --for=jsonpath='{.status.parents[0].conditions[?(@.type=="Accepted")].status}'=True httproute/grafana --timeout=120s
       |""".stripMargin

  // Prometheus is queried through the API server's service proxy, so it need not be published.
  private val testScript =
    s"""set -e
       |q() {
       |  $kc get --raw "/api/v1/namespaces/$ns/services/kps-prometheus:9090/proxy/api/v1/query?query=$$(python3 -c 'import sys,urllib.parse; print(urllib.parse.quote(sys.argv[1]))' "$$1")" \\
       |    | python3 -c 'import sys,json; r=json.load(sys.stdin)["data"]["result"]; print(int(float(r[0]["value"][1])) if r else 0)'
       |}
       |want() {  # description, query, minimum
       |  GOT=0
       |  for I in $$(seq 1 40); do GOT=$$(q "$$2" 2>/dev/null || echo 0); [ "$$GOT" -ge "$$3" ] && break; sleep 5; done
       |  if [ "$$GOT" -ge "$$3" ]; then echo "ok    $$1: $$GOT"; else echo "FAIL  $$1: $$GOT (wanted at least $$3)" >&2; FAIL=1; fi
       |}
       |FAIL=0
       |want "nodes reporting node-exporter metrics" 'count(count by (instance) (node_uname_info))' 10
       |want "API servers up" 'count(up{job="apiserver"} == 1)' 3
       |want "kubelets up" 'count(up{job="kubelet"} == 1)' 10
       |want "kube-state-metrics up" 'count(up{job="kube-state-metrics"} == 1)' 1
       |echo "targets down right now: $$(q 'count(up == 0)')"
       |$kc get --raw "/api/v1/namespaces/$ns/services/kps-prometheus:9090/proxy/api/v1/query?query=up%3D%3D0" \\
       |  | python3 -c 'import sys,json; [print("  down: %s %s" % (r["metric"].get("job"), r["metric"].get("instance"))) for r in json.load(sys.stdin)["data"]["result"]]'
       |HEALTH=$$(curl -s --cacert $stateDir/ca.crt https://$grafanaHost/api/health | tr -d ' \\n')
       |echo "Grafana health: $$HEALTH"
       |case "$$HEALTH" in *'"database":"ok"'*) echo "ok    Grafana database ok" ;; *) echo "FAIL  Grafana health" >&2; FAIL=1 ;; esac
       |REDIR=$$(curl -s --cacert $stateDir/ca.crt -o /dev/null -w '%{redirect_url}' https://$grafanaHost/login/generic_oauth)
       |case "$$REDIR" in $issuer/protocol/openid-connect/auth*) echo "ok    /login/generic_oauth redirects to the Keycloak realm $kcRealm" ;; *) echo "FAIL  unexpected redirect: $$REDIR" >&2; FAIL=1 ;; esac
       |[ "$$FAIL" -eq 0 ] || exit 1
       |""".stripMargin

  val playbook: ClusterPlaybook =
    clusterPlaybook("k8s-monitoring")(
      stage("keycloak-grafana-client", kcNode)
        .task("create the Keycloak client grafana")(
          Task.RunCommand(List("sh", "-c", clientScript), timeoutSeconds = 120)
        )
        .build,
      stage("monitoring-config", node)
        .task("namespace, CA ConfigMap, OAuth Secret, Helm values")(
          Task.RunCommand(List("sh", "-c", prepareScript), timeoutSeconds = 120)
        )
        .build,
      stage("install-kube-prometheus-stack", node)
        .task("helm install kube-prometheus-stack")(
          Task
            .RunCommand(List("sh", "-c", installScript), timeoutSeconds = 1500)
        )
        .build,
      stage("publish-grafana", node)
        .task(s"HTTPRoute for $grafanaHost")(
          Task.RunCommand(List("sh", "-c", routeScript), timeoutSeconds = 180)
        )
        .build,
      stage("test-monitoring", node)
        .task("query Prometheus and request Grafana")(
          Task.RunCommand(List("sh", "-c", testScript), timeoutSeconds = 600)
        )
        .task("monitoring ready")(
          Task.Debug(
            s"kube-prometheus-stack is running in $ns on the infra nodes; Grafana at https://$grafanaHost signs in with Keycloak (realm $kcRealm)."
          )
        )
        .build
    )
