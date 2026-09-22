import orphera.orchestrator.*
import orphera.orchestrator.ClusterPlaybookDsl.*

object ceph_admin_keyring extends OrpheraClusterPlaybook:

  private val createAndRegisterScript =
    """ceph --name mon. --keyring /var/lib/ceph/mon/ceph-tst0/keyring \
      |  auth get-or-create client.admin \
      |  mon 'allow *' osd 'allow *' mds 'allow *' mgr 'allow *' \
      |  -o /etc/ceph/ceph.client.admin.keyring""".stripMargin

  val playbook: ClusterPlaybook =
    clusterPlaybook("ceph-admin-keyring")(

      stage("generate-and-register", "tst0")
        .task("create and register client.admin via mon. auth")(
          Task.RunCommand(List("sh", "-c", createAndRegisterScript))
        )
        .build,

      stage("distribute", "tst1", "tst4")
        .task("distribute admin keyring from tst0")(
          Task.DistributeFile("tst0", "/etc/ceph/ceph.client.admin.keyring", "/etc/ceph/ceph.client.admin.keyring", "root", "root", 384)
        )
        .build
    )
