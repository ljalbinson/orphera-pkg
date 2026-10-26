// SPDX-License-Identifier: Apache-2.0

import orphera.orchestrator.*
import orphera.orchestrator.ClusterPlaybookDsl.*

// DRAFT. Undoes kolla_build.scala on tst21: stops the registry, devpi and nginx, and removes the helper,
// the kolla-build and devpi installations, the unit files and the config. The data is kept unless
// KOLLA_BUILD_PURGE=yes is set in the environment of the run: the built images (/var/lib/orphera/kolla-registry),
// the source tarballs (/srv/kolla-sources) and the pip cache (/var/lib/orphera/devpi) are what takes hours to
// make again. podman, nginx and skopeo stay installed. Nodes keep their mirror setting for tst21; podman
// goes on to tst9 and quay.io when tst21 does not answer.
object kolla_build_teardown extends OrpheraClusterPlaybook:

  private val node = "tst21"
  private val purge = sys.env.get("KOLLA_BUILD_PURGE").contains("yes")

  private val teardownScript =
    s"""systemctl disable --now orphera-kolla-registry orphera-devpi 2>/dev/null || true
       |rm -f /etc/systemd/system/orphera-kolla-registry.service /etc/systemd/system/orphera-devpi.service
       |systemctl daemon-reload
       |if command -v podman >/dev/null 2>&1; then podman rm -f kolla-registry 2>/dev/null || true; fi
       |rm -f /etc/nginx/conf.d/orphera-kolla-sources.conf /etc/containers/registries.conf.d/orphera-kolla-registry.conf
       |systemctl reload nginx 2>/dev/null || true
       |rm -f /usr/local/sbin/orphera-kolla-build
       |rm -rf /opt/kolla-build /opt/devpi /etc/kolla-build
       |${
        if purge then
          "rm -rf /var/lib/orphera/kolla-registry /srv/kolla-sources /var/lib/orphera/devpi /var/lib/orphera/kolla-build\necho 'builder removed, data deleted'"
        else
          "echo 'builder removed; images, source tarballs and the pip cache kept (KOLLA_BUILD_PURGE=yes deletes them)'"
      }""".stripMargin

  val playbook: ClusterPlaybook =
    clusterPlaybook("kolla-build-teardown")(
      stage("teardown-kolla-build", node)
        .task("remove the Kolla builder")(
          Task
            .RunCommand(List("sh", "-c", teardownScript), timeoutSeconds = 300)
        )
        .build
    )
