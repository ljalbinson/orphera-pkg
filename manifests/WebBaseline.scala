// SPDX-License-Identifier: Apache-2.0

package orphera.orchestrator

import orphera.orchestrator.PlaybookDsl.*

object WebBaseline extends OrpheraPlaybook:

  val playbook: Playbook =
    PlaybookDsl
      .playbook("web-baseline", "tst0", "tst1", "tst2")
      .task("install curl")(
        Task.Install(packages = List("curl"), updateCache = true)
      )
      .task("push motd")(
        Task.Copy(
          src = "files/motd",
          dest = "/etc/motd",
          owner = "root",
          group = "root",
          mode = 420
        )
      )
      .task("remove telnet if present")(
        Task.Remove(packages = List("telnet"), purge = true)
      )
      .when("os_id" === "ubuntu")
      .task("clean up unused packages")(
        Task.AutoRemove(purge = true)
      )
