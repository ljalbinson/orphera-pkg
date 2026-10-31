// SPDX-License-Identifier: Apache-2.0

import orphera.orchestrator.*
import orphera.orchestrator.ClusterPlaybookDsl.*

// Kubernetes cluster, step 17: Argo CD. Needs k8s_gateway.scala (the front door),
// k8s_oidc.scala (the Keycloak realm `k8s`) and k8s_external_dns.scala (the
// hostname's record appears by itself; the wildcard would cover it anyway).
//
// Installs the Helm chart argo/argo-cd as the release `argocd` in the namespace
// `argocd`, all pods on the infra nodes, and publishes it at
// https://argocd.k8s.ljalbinson.com through the Gateway (an HTTPRoute on the https
// listener; the Gateway ends TLS, so argocd-server runs in --insecure mode). Dex
// and notifications are off; the redis image comes from docker.io, because the
// chart's default (public.ecr.aws) is not behind the registry mirrors.
//
// People sign in with Keycloak directly: a client `argocd` in the realm `k8s`
// (confidential, secret k8s/argocd-client) with the `groups` claim. The group decides
// the Argo CD role: k8s-admins -> admin, k8s-developers -> view and sync
// applications, k8s-viewers -> read-only; a user in none of them has no access.
// The groups reach Argo CD through the client's mapper (a claim in the ID token); the
// realm has no client scope called `groups`, so Argo CD must not request one.
// Argo CD trusts Keycloak's certificate through rootCA in its OIDC settings. The
// local `admin` account stays for emergencies; its initial password is in the
// Secret argocd-initial-admin-secret in the namespace argocd.
//
// The last stage checks over HTTPS: /healthz, the settings show the Keycloak OIDC
// configuration, /auth/login redirects to the Keycloak realm, and the local admin can
// log in to the API. Then it creates an Application that deploys k8s/argocd-test
// from this project's GitHub repository (a single ConfigMap, branch main if the
// directory is already there, otherwise the branch k8s-argocd), waits for Synced and
// Healthy, reads it through the API, deletes it and checks the ConfigMap is pruned.
//
// A real browser login through Keycloak is not tested here.
//
// Not compiled where this was written; the first run on scala0 is the first
// compile. Chart value names are from memory of the argo-cd chart; if Helm reports
// an unknown value or a pod does not start, the output says which.
object k8s_argocd extends OrpheraClusterPlaybook:

  private val node = "tst30"
  private val kcNode = "tst26"
  private val kcFqdn = "tst26.ljalbinson.com"
  private val kcPort = 8443
  private val kcRealm = "k8s"
  private val issuer = s"https://$kcFqdn:$kcPort/realms/$kcRealm"
  private val domain = "k8s.ljalbinson.com"
  private val argoHost = s"argocd.$domain"
  private val ns = "argocd"
  private val stateDir = "/etc/kubernetes/orphera"
  private val kc = "kubectl --kubeconfig /etc/kubernetes/admin.conf"
  private val helper = "/usr/local/sbin/orphera-keycloak-argocd"
  private val repo = "https://github.com/ljalbinson/orphera-pkg.git"

  private val kcAdminPassword = Secrets.secret("keycloak/admin")
  private val clientSecret = Secrets.secret("k8s/argocd-client")

  // ---- Keycloak client (runs on tst26) ----------------------------------

  private val helperPython =
    """#!/usr/bin/python3
import json, ssl, sys, urllib.error, urllib.parse, urllib.request

KC = 'https://@@FQDN@@:@@PORT@@'
REALM = '@@REALM@@'
CLIENT_ID = 'argocd'
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
    'clientId': CLIENT_ID, 'name': 'Argo CD', 'protocol': 'openid-connect', 'enabled': True,
    'publicClient': False, 'clientAuthenticatorType': 'client-secret', 'secret': CLIENT_SECRET,
    'standardFlowEnabled': True, 'directAccessGrantsEnabled': False, 'serviceAccountsEnabled': False,
    'rootUrl': ROOT, 'baseUrl': ROOT + '/applications',
    'redirectUris': [ROOT + '/auth/callback', 'http://localhost:8085/auth/callback'],
    'webOrigins': [ROOT],
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
print('client %s in realm %s ready: redirect %s/auth/callback' % (CLIENT_ID, REALM, ROOT))
"""

  private val helperText =
    helperPython
      .replace("@@FQDN@@", kcFqdn)
      .replace("@@PORT@@", kcPort.toString)
      .replace("@@REALM@@", kcRealm)
      .replace("@@SECRET@@", clientSecret.reveal)
      .replace("@@ADMINPW@@", kcAdminPassword.reveal)
      .replace("@@HOST@@", argoHost)

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

  // The values file is written in three pieces so the CA certificate can be
  // inserted, indented, as the OIDC rootCA.
  private val valuesScript =
    s"""set -e
       |[ -s $stateDir/ca.crt ] || { echo "$stateDir/ca.crt is missing; run k8s_cert_manager.scala first" >&2; exit 1; }
       |$kc create namespace $ns --dry-run=client -o yaml | $kc apply -f -
       |umask 077
       |{
       |cat <<'EOF'
       |global:
       |  domain: $argoHost
       |  nodeSelector:
       |    pool: infra
       |dex:
       |  enabled: false
       |notifications:
       |  enabled: false
       |redis:
       |  image:
       |    repository: docker.io/library/redis
       |configs:
       |  params:
       |    server.insecure: true
       |  cm:
       |    url: https://$argoHost
       |    oidc.config: |
       |      name: Keycloak
       |      issuer: $issuer
       |      clientID: argocd
       |      clientSecret: $$oidc.keycloak.clientSecret
       |      requestedScopes: ["openid", "profile", "email"]
       |      rootCA: |
       |EOF
       |sed 's/^/        /' $stateDir/ca.crt
       |cat <<'EOF'
       |  rbac:
       |    policy.default: ""
       |    scopes: "[groups]"
       |    policy.csv: |
       |      p, role:developer, applications, get, */*, allow
       |      p, role:developer, applications, sync, */*, allow
       |      p, role:developer, logs, get, */*, allow
       |      p, role:developer, projects, get, *, allow
       |      p, role:developer, repositories, get, *, allow
       |      p, role:developer, clusters, get, *, allow
       |      g, k8s-admins, role:admin
       |      g, k8s-developers, role:developer
       |      g, k8s-viewers, role:readonly
       |  secret:
       |    extra:
       |      oidc.keycloak.clientSecret: "${clientSecret.reveal}"
       |EOF
       |} > $stateDir/argocd-values.yaml
       |echo "namespace and Helm values written"
       |""".stripMargin

  private val installScript =
    s"""set -e
       |export KUBECONFIG=/etc/kubernetes/admin.conf
       |helm repo add argo https://argoproj.github.io/argo-helm --force-update >/dev/null
       |helm repo update >/dev/null
       |VER=$$(helm show chart argo/argo-cd | awk '/^version:/{print $$2}')
       |[ -n "$$VER" ] || { echo "the chart argo/argo-cd was not found; its repository may have moved" >&2; exit 1; }
       |echo "installing argo-cd chart $$VER"
       |helm upgrade --install argocd argo/argo-cd --version "$$VER" --namespace $ns -f $stateDir/argocd-values.yaml --wait --timeout 15m
       |$kc -n $ns get pods -o wide
       |""".stripMargin

  private val routeScript =
    s"""set -e
       |cat <<EOF | $kc apply -f -
       |apiVersion: gateway.networking.k8s.io/v1
       |kind: HTTPRoute
       |metadata:
       |  name: argocd
       |  namespace: $ns
       |spec:
       |  parentRefs:
       |  - name: public
       |    namespace: gateway
       |    sectionName: https
       |  hostnames:
       |  - $argoHost
       |  rules:
       |  - backendRefs:
       |    - name: argocd-server
       |      port: 80
       |EOF
       |$kc -n $ns wait --for=jsonpath='{.status.parents[0].conditions[?(@.type=="Accepted")].status}'=True httproute/argocd --timeout=120s
       |""".stripMargin

  private val testScript =
    s"""set -e
       |FAIL=0
       |CURL="curl -s -m 10 --cacert $stateDir/ca.crt"
       |chk() {  # description, wanted, got
       |  if [ "$$2" = "$$3" ]; then echo "ok    $$1: $$3"; else echo "FAIL  $$1: wanted '$$2', got '$$3'" >&2; FAIL=1; fi
       |}
       |CODE=""
       |for I in $$(seq 1 30); do
       |  CODE=$$($$CURL -o /dev/null -w '%{http_code}' https://$argoHost/healthz || true)
       |  [ "$$CODE" = "200" ] && break
       |  sleep 2
       |done
       |chk "https://$argoHost/healthz" 200 "$$CODE"
       |OIDC=$$($$CURL https://$argoHost/api/v1/settings | python3 -c 'import sys,json; o=json.load(sys.stdin).get("oidcConfig",{}); print(o.get("name","") + " " + o.get("issuer",""))' 2>/dev/null || true)
       |chk "the settings show the Keycloak OIDC configuration" "Keycloak $issuer" "$$OIDC"
       |REDIR=$$($$CURL -o /dev/null -w '%{redirect_url}' "https://$argoHost/auth/login?return_url=https%3A%2F%2F$argoHost%2Fapplications" || true)
       |case "$$REDIR" in $issuer/protocol/openid-connect/auth*) echo "ok    /auth/login redirects to the Keycloak realm $kcRealm" ;; *) echo "FAIL  unexpected login redirect: '$$REDIR'" >&2; FAIL=1 ;; esac
       |PW=$$($kc -n $ns get secret argocd-initial-admin-secret -o jsonpath='{.data.password}' | base64 -d)
       |TOKEN=$$(printf '{"username":"admin","password":"%s"}' "$$PW" | $$CURL -H 'Content-Type: application/json' -d @- https://$argoHost/api/v1/session | python3 -c 'import sys,json; print(json.load(sys.stdin).get("token",""))' 2>/dev/null || true)
       |if [ -n "$$TOKEN" ]; then echo "ok    the local admin can log in to the API"; else echo "FAIL  the local admin could not log in to the API" >&2; FAIL=1; fi
       |# an Application that deploys one ConfigMap from this project's repository
       |REV=main
       |RAWCODE=$$($$CURL -o /dev/null -w '%{http_code}' https://raw.githubusercontent.com/ljalbinson/orphera-pkg/main/k8s/argocd-test/configmap.yaml || true)
       |[ "$$RAWCODE" = "200" ] || REV=k8s-argocd
       |echo "the test Application uses revision $$REV"
       |cat <<EOF | $kc apply -f -
       |apiVersion: argoproj.io/v1alpha1
       |kind: Application
       |metadata:
       |  name: argocd-selftest
       |  namespace: $ns
       |  finalizers:
       |  - resources-finalizer.argocd.argoproj.io
       |spec:
       |  project: default
       |  source:
       |    repoURL: $repo
       |    targetRevision: $$REV
       |    path: k8s/argocd-test
       |  destination:
       |    server: https://kubernetes.default.svc
       |    namespace: argocd-test
       |  syncPolicy:
       |    automated:
       |      prune: true
       |      selfHeal: true
       |    syncOptions:
       |    - CreateNamespace=true
       |EOF
       |ST=""
       |for I in $$(seq 1 60); do
       |  ST=$$($kc -n $ns get application argocd-selftest -o jsonpath='{.status.sync.status}/{.status.health.status}' 2>/dev/null || true)
       |  [ "$$ST" = "Synced/Healthy" ] && break
       |  sleep 5
       |done
       |chk "the test Application is Synced and Healthy" "Synced/Healthy" "$$ST"
       |[ "$$ST" = "Synced/Healthy" ] || $kc -n $ns get application argocd-selftest -o jsonpath='{.status.conditions}' >&2 || true
       |chk "the ConfigMap was deployed" orphera-selftest "$$($kc -n argocd-test get configmap orphera-selftest -o jsonpath='{.metadata.name}' 2>/dev/null || true)"
       |APISTATE=$$($$CURL -H "Authorization: Bearer $$TOKEN" https://$argoHost/api/v1/applications/argocd-selftest | python3 -c 'import sys,json; a=json.load(sys.stdin); print(a["status"]["sync"]["status"])' 2>/dev/null || true)
       |chk "the API reports the Application" Synced "$$APISTATE"
       |$kc -n $ns delete application argocd-selftest --wait=true --timeout=180s >/dev/null || true
       |GONE="x"
       |for I in $$(seq 1 30); do
       |  GONE=$$($kc -n argocd-test get configmap orphera-selftest -o name 2>/dev/null || true)
       |  [ -z "$$GONE" ] && break
       |  sleep 3
       |done
       |chk "the ConfigMap is removed with the Application" "" "$$GONE"
       |$kc delete namespace argocd-test --wait=false >/dev/null 2>&1 || true
       |[ "$$FAIL" -eq 0 ] || exit 1
       |""".stripMargin

  val playbook: ClusterPlaybook =
    clusterPlaybook("k8s-argocd")(
      stage("keycloak-argocd-client", kcNode)
        .task("create the Keycloak client argocd")(
          Task.RunCommand(List("sh", "-c", clientScript), timeoutSeconds = 120)
        )
        .build,
      stage("argocd-config", node)
        .task("namespace and Helm values")(
          Task.RunCommand(List("sh", "-c", valuesScript), timeoutSeconds = 120)
        )
        .build,
      stage("install-argocd", node)
        .task("helm install argo-cd")(
          Task
            .RunCommand(List("sh", "-c", installScript), timeoutSeconds = 1200)
        )
        .build,
      stage("publish-argocd", node)
        .task(s"HTTPRoute for $argoHost")(
          Task.RunCommand(List("sh", "-c", routeScript), timeoutSeconds = 180)
        )
        .build,
      stage("test-argocd", node)
        .task("check the server and sync a test Application")(
          Task.RunCommand(List("sh", "-c", testScript), timeoutSeconds = 900)
        )
        .task("argocd ready")(
          Task.Debug(
            s"Argo CD is running in $ns on the infra nodes; https://$argoHost signs in with Keycloak (realm $kcRealm: k8s-admins admin, k8s-developers view and sync, k8s-viewers read-only)."
          )
        )
        .build
    )
