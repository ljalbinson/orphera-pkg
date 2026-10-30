// SPDX-License-Identifier: Apache-2.0

import orphera.orchestrator.*
import orphera.orchestrator.ClusterPlaybookDsl.*

// DRAFT. Undoes keycloak_single_node.scala: removes the Keystone side of the federation (protocol, identity provider,
// mapping, and domain `keycloak` with its project, group and federated users) through the helper if it is still
// there, stops and removes the Keycloak container, its unit, configuration, key and certificate and the helper on
// tst26, removes the signed certificate and request kept beside the CA on tst6, and drops the keycloak database and
// user. podman, the image, the registry mirror file and the trusted test CA stay on tst26, as do the secrets
// (keycloak/db, keycloak/admin, keycloak/keystone-client, keycloak/demo-user). Keystone's OIDC vhost settings belong
// to keystone_single_node.scala and stay; with no identity provider the federation path answers 404. Keep node and
// the names in step with keycloak_single_node.scala. Safe to re-run when nothing is installed.
object keycloak_teardown extends OrpheraClusterPlaybook:

  private val node = "tst26"
  private val ksNode = "tst6"
  private val dbName = "keycloak"
  private val dbUser = "keycloak"

  private val nodeScript =
    """if [ -x /usr/local/sbin/orphera-keycloak-api ]; then
      |  /usr/local/sbin/orphera-keycloak-api unfederate || echo "keystone cleanup failed (continuing)"
      |fi
      |systemctl disable --now orphera-keycloak 2>/dev/null || true
      |rm -f /etc/systemd/system/orphera-keycloak.service
      |systemctl daemon-reload
      |if command -v podman >/dev/null 2>&1; then
      |  podman rm -f keycloak 2>/dev/null || true
      |fi
      |rm -rf /etc/keycloak
      |rm -f /usr/local/sbin/orphera-keycloak-api /tmp/kc-ready.json
      |echo "keycloak container, unit, configuration, TLS files and helper removed"""".stripMargin

  private val signDirScript =
    s"""rm -rf /etc/kolla/keystone-ca/issued/$node
       |echo "removed the certificate kept for $node"""".stripMargin

  private val dropDatabaseScript =
    s"""mariadb -N -e "DROP DATABASE IF EXISTS $dbName; DROP USER IF EXISTS '$dbUser'@'%'; FLUSH PRIVILEGES;""""

  val playbook: ClusterPlaybook =
    clusterPlaybook("keycloak-teardown")(
      stage("teardown-keycloak-node", node)
        .task("remove the federation from Keystone, the container, unit and files")(
          Task.RunCommand(List("sh", "-c", nodeScript), timeoutSeconds = 240)
        )
        .build,

      stage("teardown-keycloak-certificate", ksNode)
        .task(s"remove the certificate signed for $node")(
          Task.RunCommand(List("sh", "-c", signDirScript))
        )
        .build,

      stage("drop-keycloak-database", "tst3")
        .task(s"drop $dbName database and $dbUser on the Galera cluster")(
          Task.RunCommand(List("sh", "-c", dropDatabaseScript))
        )
        .build
    )
