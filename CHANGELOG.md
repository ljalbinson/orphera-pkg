# Changelog

All notable changes to this project are documented in this file.

The format is based on [Keep a Changelog](https://keepachangelog.com/en/1.1.0/).

## [Unreleased]

### Added

- **`reboot` command/RPC** (`TriggerReboot`/`RebootRequest`/`RebootAck`).
  Runs fully detached from the agent's own process via `systemd-run
  --no-block`, for the same reason as `deploy-agent`'s fix below —
  systemd's `KillMode=control-group` would otherwise kill the reboot
  command itself as a side effect of the agent's own service being
  torn down. Supports `--delay`, `--wait` (poll the agent afterward
  and report when it's reachable again, or time out), and
  `--wait-timeout`.
- **`version` command/RPC** (`GetVersion`/`VersionRequest`/`VersionInfo`).
  Agent version is baked in at compile time from the top-level
  `VERSION` file via an sbt source generator (`BuildInfo.scala`), so
  a running agent's reported version reflects what `VERSION` held at
  build time, not the current state of the source tree. Exists
  specifically to make `deploy-agent`'s otherwise-unconfirmable
  outcome checkable after the fact (full automatic wiring into
  `deploy-agent` designed but not yet implemented).
- **Playbooks**, a full ordered, multi-task execution model, superseding
  the earlier (never fully implemented) declarative file-manifest idea:
  - Shared `Task`/`NamedTask`/`Playbook`/`FactCondition` ADT
    (`Task.scala`), covering `install`, `remove`, `autoremove`, `copy`,
    `network_apply`, `reboot`.
  - **YAML front-end** (`PlaybookYaml.scala`) — ordered task lists with
    `when:` fact-based conditions, run via `playbook <file.yaml>`.
  - **`copy` task templating** — `.mustache` sources rendered via
    Mustache before push, merging the task's own `vars:` with facts
    gathered for that node (`facts.hostname`, `facts.os_id`,
    `facts.os_version`, `facts.architecture`). The idempotent
    pre-check hashes rendered output, so re-applying an unchanged
    playbook stays a no-op even across template-source churn.
  - **`PlaybookRunner`** — executes one node's task sequence strictly
    in order; different nodes run in parallel with each other. A
    failed task stops that node's remaining tasks without affecting
    other nodes. Facts are gathered once per node, up front, and
    reused for every `when:` check in that node's sequence.
  - **Scala DSL front-end** (`PlaybookDsl.scala`) — a fluent
    `.task(name)(task).when(condition).build` builder, as an
    alternative to YAML for cases wanting compile-time checking and
    real Scala composability. `OrpheraPlaybook` (`OrpheraPlaybook.scala`)
    lets a DSL playbook extend it to become its own independently
    runnable `IOApp` (`sbt "orchestrator/runMain <fully.qualified.Name>"`),
    in addition to being runnable by name via `playbook <name>` once
    registered in `PlaybookRegistry.scala`.
  - `-o DPkg::Lock::Timeout=60` added to every `apt-get`/`dpkg`
    invocation on the agent, after hitting real lock contention
    (background `unattended-upgrades` or a concurrent task) in
    practice against real hosts.

### Fixed

- **`deploy-agent` self-disruption.** `dpkg -i` invoked by the agent
  to upgrade itself was being killed mid-unpack by its own service
  restart, since it ran as a child process inside the agent's systemd
  cgroup and `KillMode=control-group` (the default) sends `SIGTERM` to
  the whole cgroup on stop/restart — not just the agent JVM. Fixed by
  launching the install via `systemd-run --no-block`, fully detached
  from the agent's cgroup. As a direct consequence, `deploy-agent`'s
  `RESULT` event now explicitly only ever means "the install was
  launched," not "the install completed" — see the `version` RPC
  above for the intended path to closing that gap properly.
- **`postinst` simplified** back to a plain synchronous
  `systemctl restart` once the above fix meant there was no longer a
  race between `dpkg -i` and the service restart for `postinst` to
  work around; an earlier `setsid`-based delayed-restart workaround
  was solving the wrong problem and has been removed.

### Known issues / gotchas hit this cycle

- **`wait` cannot be used as a field name** on any Scala case
  class/`enum case`/parameter in this codebase — it collides with
  `java.lang.Object`'s `final wait()` method. Hit independently in
  both `Task.Reboot` and `Cli.Command.Reboot`; both use
  `waitForReturn` instead.
- Several rounds of "a file or method given in an earlier message
  never actually got saved/added" recurred throughout this feature's
  development (missing `OrpheraPlaybook.scala`, missing
  `NodeClient.reboot`, missing `Orchestrator.reboot`, a missing
  `package orphera.orchestrator` declaration causing a whole-file
  cascade of unrelated-looking "not found" errors). No code change
  results from this beyond what's already listed above, but it's the
  same pattern flagged earlier in this changelog's history and remains
  the strongest existing argument for CI (see [0.1.0]'s Known
  limitations).

## [0.1.0] - Initial build

### Added

**Core transport and protocol**
- Migrated from raw TCP sockets with hand-rolled JSON to gRPC, using
  `sbt-fs2-grpc` for Cats-Effect/fs2-native client and server stubs.
- Shared `common` module with protobuf-defined message and service
  types (`Protocol.proto`).

**Security**
- TLS between orchestrator and agent, verified against a private CA.
- Wildcard server certificate (`*.<domain>`) shared across the agent
  fleet, plus `localhost`/`127.0.0.1` for local development.
- Shared-secret bearer token (`ORPHERA_TOKEN`) checked via a gRPC
  `ServerInterceptor` on every agent call, as a second, independent
  auth factor alongside TLS.
- Startup warning when the agent is running with the built-in default
  token rather than an explicitly configured one.

**Agent capabilities (RPCs)**
- `Install` — `apt-get install`, with optional cache update.
- `Remove` — `apt-get remove`/`purge`.
- `RunAutoRemove` — `apt-get autoremove`, with optional purge.
- `CopyFile` — streamed file push with owner/group/mode, atomic
  write (temp file + `ATOMIC_MOVE`) so a partial transfer is never
  visible at the destination path.
- `CheckFile` — content hash and attribute comparison, used to make
  file pushes idempotent (skip when nothing would change).
- `ReloadNetwork` / `ConfirmNetwork` — safe `systemd-networkd`
  config application with automatic local rollback if not confirmed
  within a timeout (protects against an agent losing connectivity
  from a bad network config).
- `TriggerReboot` — host reboot, reporting success back to the
  orchestrator before the connection is severed by the actual reboot.
- `InstallDebPackage` — agent self-upgrade via `dpkg -i`.

**Orchestrator CLI**
- `install`, `remove`, `autoremove`, `copy`, `reboot`, `network-apply`,
  `deploy-agent` commands, each supporting `--nodes host1,host2` to
  target a subset of inventory (defaulting to all nodes where
  applicable).
- `apply <manifest.yaml>` — declarative multi-file push from a YAML
  manifest.
- Mustache-based templating for `.mustache` files referenced in
  manifests, with per-entry variable substitution.
- `bootstrap` — SSH-based first-time agent install (no existing agent
  required), using non-interactive key auth.
- `teardown` — SSH-based agent removal/purge, requiring explicit
  `--nodes` (no fleet-wide default) and an enforced `--yes`
  confirmation flag.
- Static, code-defined inventory (`Inventory.all`).

**Packaging and deployment**
- Hand-built `.deb` package for the agent: systemd unit
  (`Restart=on-failure`, runs as root), `postinst`/`prerm` lifecycle
  scripts, `conffiles` protection for `agent.env`.
- `Makefile` with `clean`, `test`, `assembly`, `deb`, `verify`,
  `certs`, and `certs-clean` targets, chaining the full pipeline via
  `make`/`make all`.
- `openssl`-based CA and wildcard-certificate generation
  (`make certs DOMAIN=...`), with a safety guard against accidental
  regeneration of an already-existing CA.

**Documentation and project administration**
- README covering architecture, build requirements (with exact
  version pins), running instructions, TLS/cert setup, full CLI
  reference, manifest format, network-config safety design,
  provisioning/decommissioning flows, and known gaps.
- Apache License 2.0 adopted; `LICENSE` file and per-file
  `SPDX-License-Identifier` convention, with `add-spdx-headers.sh` to
  apply the tag across all Scala sources idempotently.
- Development-notes disclosure in the README describing the role of
  AI-assisted development on this project.

### Known limitations

- No dynamic/cloud inventory source — static and hand-edited only.
- No task ordering or dependencies within or across file manifests.
- Single shared wildcard private key across the entire agent fleet
  (a deliberate simplicity trade-off; revisit with per-node certs if
  it stops being acceptable).
- No mutual TLS — the agent authenticates the orchestrator via shared
  token only, not certificate identity.
- No path allowlisting on `CopyFile`'s destination.
- No secrets management for manifest-pushed files.
- No reliable automated confirmation that `deploy-agent` succeeded
  (the RPC stream is expected to drop on a successful self-restart).
- Native binary compilation via GraalVM `native-image` is not
  functional — `grpc-netty-shaded`'s static initialization conflicts
  with native-image's build-time initialization in ways not yet
  resolved. The JVM assembly jar is the supported deployment artifact.
- Automated test coverage is minimal; a test plan has been proposed
  but is largely not yet implemented.
- No CI pipeline yet.
