// SPDX-License-Identifier: Apache-2.0

import orphera.orchestrator.*
import orphera.orchestrator.ClusterPlaybookDsl.*

// Kubernetes cluster, step 3: one stable address for the API server.
// haproxy and keepalived on the three control-plane nodes (tst30, tst31, tst32)
// hold 10.10.3.20 (DNS name api.k8s.ljalbinson.com) and forward TCP port 8443
// to port 6443 of the three kube-apiservers. kubeadm (k8s_cluster.scala) then
// uses api.k8s.ljalbinson.com:8443 as the control-plane endpoint, so a node can
// fail without any kubeconfig changing.
//
// The VIP's port is 8443, not 6443, because haproxy and the kube-apiserver
// run on the same three hosts: the API server listens on 0.0.0.0:6443, and
// on Linux a second bind of the same port on a specific address (the VIP)
// collides with a wildcard bind (the lesson of mariadb_haproxy_keepalived.scala,
// seen the other way round). haproxy binds the VIP address only, never a
// wildcard, and `net.ipv4.ip_nonlocal_bind=1` lets it bind the VIP on the nodes
// that do not hold it yet.
//
// Before kubeadm has run nothing listens on 6443, so every backend is down and
// the VIP answers connection-refused. That is expected at this step; the final
// check here only proves that one node holds the VIP and that the address
// answers ping.
//
// Not compiled where this was written; the first run on scala0 is the first
// compile.
object k8s_api_vip extends OrpheraClusterPlaybook:

  private val vip = "10.10.3.20"
  private val cpNodes = List("tst30", "tst31", "tst32")

  // A guard against a stray VRRP speaker; keepalived uses the first 8
  // characters only. Made on first use in the secret store.
  private val vrrpAuthPass =
    val v = Secrets.get("k8s/vrrp").take(8)
    Secrets.register(v)
    v

  private def priorityAndState(host: String): (Int, String) =
    host match
      case "tst30" => (101, "MASTER")
      case "tst31" => (100, "BACKUP")
      case "tst32" => (99, "BACKUP")
      case other   => throw new IllegalArgumentException(other)

  private def peers(host: String): List[String] = cpNodes.filterNot(_ == host)

  // Refuses to go on if a device outside the three nodes already answers ARP
  // for the VIP (see mariadb_haproxy_keepalived.scala: this happened twice).
  private val vipFreeScript =
    s"""IFACE=$$(ip -4 route show default | awk '{print $$5; exit}')
       |MACRE='[0-9a-f]{2}(:[0-9a-f]{2}){5}'
       |ALLOWED=$$(cat /sys/class/net/$$IFACE/address)
       |for PEER in {{nodes.tst31.cluster_ip}} {{nodes.tst32.cluster_ip}}; do
       |  ALLOWED="$$ALLOWED $$(arping -c 2 -w 4 -I $$IFACE $$PEER 2>/dev/null | grep -Eo "$$MACRE" | sort -u | xargs)"
       |done
       |FOUND=$$(arping -c 3 -w 6 -I $$IFACE $vip 2>/dev/null | grep -Eo "$$MACRE" | sort -u)
       |BAD=""
       |for M in $$FOUND; do
       |  case " $$ALLOWED " in *" $$M "*) ;; *) BAD="$$BAD $$M" ;; esac
       |done
       |if [ -n "$$BAD" ]; then
       |  echo "$vip is already answered by a device that is not tst30, tst31 or tst32 (MAC:$$BAD; the nodes' own MACs: $$ALLOWED). Remove or re-address that device, then re-run." >&2
       |  exit 1
       |fi
       |echo "$vip is not answered by anything outside the control-plane nodes (replies from: $${FOUND:-none})"
       |""".stripMargin

  private val haproxyScript =
    s"""cat > /etc/sysctl.d/99-orphera-k8s-vip.conf <<'EOF'
       |net.ipv4.ip_nonlocal_bind=1
       |EOF
       |sysctl -p /etc/sysctl.d/99-orphera-k8s-vip.conf >/dev/null
       |cat > /etc/haproxy/haproxy.cfg <<'EOF'
       |global
       |    daemon
       |    maxconn 2048
       |
       |defaults
       |    mode tcp
       |    timeout connect 5s
       |    timeout client 1h
       |    timeout server 1h
       |    timeout tunnel 1h
       |
       |# kube-apiserver health: a TLS handshake plus GET /livez would need the
       |# client CA; a plain TCP check is enough to take a dead node out.
       |listen kube_apiserver
       |    bind $vip:8443
       |    balance roundrobin
       |    default-server inter 3s fall 3 rise 2 on-marked-down shutdown-sessions
       |    server tst30 {{nodes.tst30.cluster_ip}}:6443 check
       |    server tst31 {{nodes.tst31.cluster_ip}}:6443 check
       |    server tst32 {{nodes.tst32.cluster_ip}}:6443 check
       |EOF
       |haproxy -c -f /etc/haproxy/haproxy.cfg
       |systemctl enable haproxy
       |systemctl restart haproxy
       |sleep 1
       |systemctl is-active --quiet haproxy || { echo "haproxy failed to start; see journalctl -xeu haproxy" >&2; exit 1; }
       |echo "haproxy forwards $vip:8443 to port 6443 of the three API servers"""".stripMargin

  private def keepalivedScript(host: String): String =
    val (priority, state) = priorityAndState(host)
    val peerLines =
      peers(host).map(p => s"        {{nodes.$p.cluster_ip}}").mkString("\n")
    s"""IFACE=$$(ip -4 route show default | awk '{print $$5; exit}')
       |cat > /etc/keepalived/keepalived.conf <<EOF
       |vrrp_script chk_haproxy {
       |    script "/usr/bin/pgrep -x haproxy"
       |    interval 2
       |    weight 4
       |}
       |
       |vrrp_instance K8S_API_VIP {
       |    state $state
       |    interface $$IFACE
       |    virtual_router_id 53
       |    priority $priority
       |    advert_int 1
       |    unicast_src_ip {{cluster_ip}}
       |    unicast_peer {
       |$peerLines
       |    }
       |    authentication {
       |        auth_type PASS
       |        auth_pass $vrrpAuthPass
       |    }
       |    virtual_ipaddress {
       |        $vip/24
       |    }
       |    track_script {
       |        chk_haproxy
       |    }
       |}
       |EOF
       |systemctl daemon-reload
       |systemctl enable keepalived
       |systemctl restart keepalived
       |sleep 2
       |systemctl is-active --quiet keepalived || { echo "keepalived failed to start; see journalctl -xeu keepalived" >&2; exit 1; }
       |echo "keepalived on $host: priority=$priority state=$state"""".stripMargin

  private val vipHeldScript = s"ip -4 addr show | grep -q '$vip/'"

  // Counts holders from tst30 by asking each node over ssh would need keys;
  // instead each node reports itself and the debug task states the rule.
  private val holderScript =
    s"""ip -4 addr show | grep -q '$vip/' && echo "$$(hostname) holds $vip" || echo "$$(hostname) does not hold $vip""""

  private val pingScript =
    s"""ping -c 2 -W 2 $vip >/dev/null && echo "$vip answers ping" || { echo "$vip does not answer ping" >&2; exit 1; }"""

  val playbook: ClusterPlaybook =
    clusterPlaybook("k8s-api-vip")(
      stage("install-haproxy-keepalived", cpNodes*)
        .task("install haproxy, keepalived, arping")(
          Task.Install(
            packages = List("haproxy", "keepalived", "iputils-arping"),
            updateCache = true
          )
        )
        .build,
      stage("check-vip-not-in-use", "tst30")
        .task(s"check that nothing outside the nodes answers for $vip")(
          Task.RunCommand(List("sh", "-c", vipFreeScript), timeoutSeconds = 60)
        )
        .build,
      stage("write-haproxy-config", cpNodes*)
        .task("write /etc/haproxy/haproxy.cfg and (re)start haproxy")(
          Task.RunCommand(List("sh", "-c", haproxyScript), timeoutSeconds = 60)
        )
        .build,
      stage("write-keepalived-tst30", "tst30")
        .task("write keepalived.conf and (re)start keepalived")(
          Task.RunCommand(
            List("sh", "-c", keepalivedScript("tst30")),
            timeoutSeconds = 60
          )
        )
        .build,
      stage("write-keepalived-tst31", "tst31")
        .task("write keepalived.conf and (re)start keepalived")(
          Task.RunCommand(
            List("sh", "-c", keepalivedScript("tst31")),
            timeoutSeconds = 60
          )
        )
        .build,
      stage("write-keepalived-tst32", "tst32")
        .task("write keepalived.conf and (re)start keepalived")(
          Task.RunCommand(
            List("sh", "-c", keepalivedScript("tst32")),
            timeoutSeconds = 60
          )
        )
        .build,
      stage("confirm-api-vip", "tst31")
        .waitFor(
          HealthCheck.Quorum(
            nodes = cpNodes,
            command = List("sh", "-c", vipHeldScript),
            requiredCount = 1,
            pollIntervalSeconds = 5,
            timeoutSeconds = 60
          )
        )
        .task("print which node holds the VIP")(
          Task.RunCommand(List("sh", "-c", holderScript))
        )
        .task(s"ping $vip")(
          Task.RunCommand(List("sh", "-c", pingScript))
        )
        .task("api vip ready")(
          Task.Debug(
            s"$vip (api.k8s.ljalbinson.com) is held by one control-plane node; haproxy forwards 8443 " +
              "to the API servers, which do not exist until k8s_cluster.scala runs."
          )
        )
        .build
    )
