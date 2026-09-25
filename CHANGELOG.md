# Changelog

All notable changes to this project are documented in this file.

The format is based on [Keep a Changelog](https://keepachangelog.com/en/1.1.0/).

## [Unreleased]

### Ceph — manual mon/mgr/OSD bootstrap, hardening, and a second cephadm-based path

Built and debugged against real KVM VMs (`tst0`/`tst1`/`tst4`), not just
in theory. Two complete, working deployment paths now exist for
bringing up a Ceph cluster via Orphera:

**Manual path** (`manifests/ceph-*.yaml`) — `ceph-mon --mkfs`-based,
full control over every step: `ceph-mon-keyring.yaml` (mon secret,
generate-once/distribute-many), `ceph-mon-quorum.yaml` (fsid/ceph.conf/
monmap generation from live `cluster_ip` facts, `--mkfs`, systemd
start, quorum-of-N health gate), `ceph-admin-keyring.yaml`
(`client.admin` bootstrapped via an authenticated `mon.` connection —
`ceph auth import` over the mon admin socket does **not** work for
this, it must go through a real client auth path or the mon's own
local keyring), `ceph-mgr.yaml`, `ceph-health-fixes.yaml`
(clears `insecure global_id reclaim`/enables msgr2), `ceph-osd.yaml`
(bootstrap-osd keyring extraction — **not** `get-or-create`, since
`--mkfs` already auto-seeds standard bootstrap identities like
`client.bootstrap-osd`/`bootstrap-mds`/`bootstrap-rgw` and re-creating
one with different caps fails with `EINVAL`), and `ceph-teardown.yaml`
(full reset — deliberately leaves `/etc/ceph` itself in place, only
clears contents, since package reinstalls don't reliably recreate a
deleted directory). A `ceph-perf-test.yaml` benchmark playbook (`rados
bench` write/seq/rand across all mons concurrently) rounds out the set.
Every manual-path playbook exists in both YAML and Scala DSL
(`ceph_*.scala`) form, compiling to identical `ClusterPlaybook` values.

**cephadm path** (`manifests/cephadm_*.yaml`) — Ceph's own official
containerized orchestrator, as a genuinely separate deployment model
rather than a variant of the manual one; the two are not meant to be
run against the same hosts interchangeably. `cephadm_install.yaml`,
`cephadm_bootstrap.yaml` (mon **and** mgr both come up from one
`cephadm bootstrap` call — no separate mgr step needed, unlike the
manual path), `cephadm_add_mons.yaml` (SSH-key-based host registration
+ `ceph orch apply mon`, quorum confirmed via `ceph mon stat` rather
than per-node `mon_status`), `cephadm_add_osds.yaml` (supports optional
per-node `osd_devices` inventory/group vars — explicit
`ceph orch daemon add osd <host>:<dev1>,<dev2>` where set, falling
back to `ceph orch apply osd --all-available-devices` for any node
that doesn't specify), `cephadm_teardown.yaml`.

**Genuine deployment-blocker fixes hit along the way, not
theoretical:**
- `download.ceph.com` does not reliably publish a build for every
  Ubuntu codename (confirmed: no `noble` build under `debian-reef`) —
  switched to Ubuntu's own archive, which already carries a current,
  security-patched Ceph (`19.2.3`), pinned via `Task.Install`'s new
  `version` field (`pkg=version` apt pinning) and a `ceph_version`
  group var, rather than adding a third-party repo at all.
- `curl --remote-name` combined with `-o <path>` is contradictory and
  fails silently under `-fsSL` (no output, no error) — this class of
  silent-curl-failure is worth remembering generally, not just for
  cephadm.
- `cephadm.py` fetched as a raw single file (pre-`squid` style) fails
  with `ModuleNotFoundError: No module named 'cephadmlib'` on `squid`
  — the tool is packaged, not a standalone script, on current
  releases; install the distro `cephadm` package instead of
  hand-fetching the script from GitHub.
- `cephadm version` requires inspecting an actual Ceph container image
  and reports `UNKNOWN`/exits 1 before anything's been pulled — not a
  broken install; `cephadm --help` is the correct binary-readiness
  check instead.
- `cephadm bootstrap`'s dashboard-password flag differs by packaged
  version — this build has no noninteractive-autogenerate option and
  requires `--initial-dashboard-password` set explicitly (plus
  `--dashboard-password-noupdate` to skip the forced-change prompt);
  check `cephadm bootstrap --help` on the actual installed build
  rather than assuming flag availability.
- `ceph orch host add` on an additional host requires that host to
  independently pass cephadm's own `check-host` — including having a
  container runtime (`podman`) installed **on that host**, not just
  the bootstrap host. Missed once (`podman` only in
  `cephadm_install.yaml`'s prerequisites, not
  `cephadm_add_mons.yaml`'s) and produced a late, host-specific
  failure well into the sequence.

### Fixed — the real cause of the intermittent "binary not found immediately after install" failures

A long, mostly-wrong investigation (dpkg-lock timing, `PATH`, stray
manifest debugging leftovers, suspected concurrent invocations, fiber
synchronization) before finding the actual, causally-confirmed cause:
**on these KVM guests, a same-process `File.exists()` check reports a
just-installed binary present well before a freshly spawned, separate
process can reliably execute it** — most likely virtualized-disk
I/O/page-cache settling, since executing a binary requires the kernel
to fault in its pages from disk, a heavier operation than a metadata
existence check. A flat `sleep 15` before the dependent command proved
this causally. The real fix, in `AptInstaller.waitForBinariesVisible`:
spawn each installed binary as a real subprocess (`<path> --version`)
and confirm it actually executes, rather than checking file existence
from the JVM's own process — this exercises the same
separate-process-spawn path that was actually failing, instead of a
proxy that reported success while the real dependent task still
failed. `CommandRunner`'s `ProcessBuilder` also now sets an explicit
`PATH` (ruled out as the actual cause here, but a reasonable
robustness improvement to keep regardless, since `run_command`
previously inherited whatever `PATH` the agent's own systemd unit
environment happened to provide with no override).

The debugging process itself is worth remembering: several rounds
chased plausible-sounding theories (dpkg lock state, `PATH` contents,
LXC/overlayfs caching — later corrected once it was confirmed these
are KVM VMs, not containers) that direct evidence subsequently
disproved one at a time. The causal test (add a deliberate, blunt
delay; confirm it fixes the symptom; only then trust the theory) is
what actually converged on the real cause — worth reaching for that
kind of test earlier next time a fix based on inference alone doesn't
hold up under retest.

### Added — CLI and tooling

- `Task.Install` gained an optional `version` field (`packages`,
  `update_cache`, **`version`**), rendered through Mustache before
  use — enables `pkg=version` apt pinning driven by inventory/group
  vars (`{{ceph_version}}`), rather than always installing whatever's
  currently latest.
- `Task.DistributeFile` (`source_node`, `source_path`, `dest_path`,
  `owner`, `group`, `mode`) — fetches a file from one already-executed
  node and pushes it verbatim to others, entirely in memory on the
  orchestrator side (`NodeClient.fetchFileBytes`/`copyBytes`, no local
  temp file). The generate-once/distribute-identically pattern this
  enables is what makes the mon and admin keyrings — and any other
  cluster secret that must be byte-identical across nodes — correct.
- `HealthCheck.Quorum` (`nodes`, `command`, `requiredCount`,
  `poll_interval`, `timeout`) — a genuine quorum-of-N stage gate,
  alongside the existing single-node `Sentinel`/`Command` forms.
  Polls every listed node each round and proceeds once the healthy
  count reaches the threshold, without waiting on stragglers once
  quorum is already met.
- `ClusterPlaybookDsl`/`OrpheraClusterPlaybook` — the Scala DSL front-
  end extended to staged/cluster playbooks (previously DSL-only for
  flat `Playbook`s), mirroring `PlaybookDsl`/`OrpheraPlaybook`.
- A top-level `handleErrorWith` in `Main.run` — any exception escaping
  a CLI command handler now prints one clean `Error: <message>` line
  instead of a full JVM stack trace.
- A static bash completion script (`orphera-completion.bash`) covering
  every CLI verb and its known flags, with filesystem completion
  scoped appropriately (`.yaml`/`.yml`/`.scala` for `playbook`/
  `cluster-playbook`, `.deb` for `deploy-agent`/`bootstrap`).
  Deliberately does not attempt dynamic `--nodes` value completion
  against real inventory names, to avoid adding latency to every
  tab-press and because `inventory.yaml`'s path is itself configurable.

### Added (prior entries this cycle)

- **Cross-node coordination**, the structural piece flagged as missing
  when comparing Orphera against ceph-ansible-style multi-role
  deployments — up to this point every playbook treated nodes as
  fully independent:
  - **`ClusterContext`** — facts for every targeted node gathered
    once, up front, before any task runs on any node (previously
    per-node, lazy). This is what makes one node's template able to
    reference another node's data at all.
  - **`Stage`/`ClusterPlaybook`** (`Stage.scala`) and
    **`ClusterPlaybookRunner`** — an ordered list of named stages,
    each targeting its own node subset; nodes within one stage run in
    parallel, but the next stage never starts until the current one's
    tasks all succeed and its optional `wait_for` health gate passes.
    A failed or unhealthy stage aborts every remaining stage. Run via
    `cluster-playbook <file.yaml>` / `ClusterPlaybookYaml.scala`.
  - **`nodes.<name>.*` template/condition namespace** — every node's
    hostname/os_id/os_version/architecture, network-interface IPs
    (`ip_<interface-name>`, plus a best-effort `ip_secondary` for the
    common two-NIC case), inventory vars, group vars, and `set_fact`
    values are all merged per node under this one nested namespace, so
    `{{nodes.mon1.cluster_ip}}` resolves whichever of those actually
    set it, in a fixed precedence order (see Inventory below).
  - **`HealthCheck`** (`Stage.scala`) as a `wait_for` gate on a stage,
    in two forms: `Sentinel` (poll a remote file's content hash — the
    original, simpler form) and `Command` (poll an arbitrary remote
    command's exit code — see `run_command` below; this is what makes
    a real service-state check, e.g. `systemctl is-active nginx`,
    possible instead of only a file-hash proxy for one).
- **`Task.RunCommand`** / `ExecuteCommand` RPC — runs an arbitrary
  command on the agent, streaming stdout/stderr lines back as `Event`s
  and reporting the real exit code, with a configurable timeout
  (`process.waitFor(timeout, …)`, force-killing and reporting a
  `124` exit on expiry). CLI: `orphera run <command...> [--nodes] [--timeout]`,
  using `--` to separate flags from the target command's own
  arguments. YAML task key: `run_command`. This is the generic
  building block several earlier-flagged gaps depended on (real
  health checks in particular).
- **`Task.SetFact`** / **`Task.Debug`** / **`Task.DumpFacts`** —
  task-to-task (and, via stages, cross-node) data flow, previously a
  known gap:
  - `set_fact` stores a key/value per node for the rest of that run;
    its `value` is rendered through Mustache first if it contains
    `{{...}}`, so a later `set_fact`/template can build on an earlier
    one (`"{{base}}-{{facts.hostname}}"`).
  - `debug` prints a Mustache-rendered message — the direct way to
    inspect a specific fact/set_fact/template value without an
    external side effect.
  - `dump_facts` (`FactPrinter.scala`) pretty-prints the *entire*
    var tree a template would see for that node — own facts, every
    node's merged `nodes.*` entry — sorted and indented, for
    inspection when something isn't resolving as expected.
  - Cross-node set_fact reads (`{{nodes.other-node.some_key}}`) work
    via `SetFacts.snapshot`, a shared mutable per-run store — reliable
    across a stage boundary (the setting stage fully completes before
    the reading stage starts), but explicitly race-prone *within* one
    stage's parallel node set, since there's no ordering guarantee
    between nodes running concurrently.
- **Inventory externalized to `inventory.yaml`** (repo root, outside
  `orchestrator/src/`, loaded by `InventoryYaml.scala`; path
  overridable via `ORPHERA_INVENTORY`) — previously `Node`/`Group`
  definitions lived in Scala source under `orchestrator/src/`, meaning
  every inventory change needed a code edit and rebuild. A missing or
  malformed inventory file now fails loudly (a clear stderr warning
  and an empty inventory) rather than silently.
- **Per-node and group vars** — `Node.vars` (declared directly on a
  node) and `Group`/`Inventory.groupVarsFor` (declared once, inherited
  by every member node), both merged into the `nodes.<name>.*`
  namespace above. Precedence, lowest to highest: group vars → node's
  own vars → gathered facts → set-facts — a node-level var always
  overrides its group's default, and a live-gathered fact always wins
  over a static inventory value of the same name.
- **Run-time-compiled Scala DSL playbook scripts** — a new, separate
  `scripting` sbt module (`scripting/src/main/scala/orphera/scripting/Main.scala`)
  depends on `orchestrator` and bundles the Scala 3 compiler; invoking
  `orphera playbook somefile.scala` shells out to `scripting`'s own
  assembled jar, which compiles that standalone file against the
  already-built `orchestrator-assembly` jar and runs it. This
  deliberately keeps `scala3-compiler` out of `orchestrator`'s own
  runtime/jar entirely — `orchestrator` never depends on `scripting`.
  Standalone `.scala` playbook scripts must live outside every sbt
  module's source tree *and* outside the repo root itself (sbt treats
  a loose root-level `.scala` file as part of its own default build
  and will fail to compile it, since `orphera.orchestrator.*` isn't on
  that classpath) — `manifests/` is the established convention for
  these, alongside the `.yaml` playbooks already kept there.
- Registry-based compiled DSL playbooks (`PlaybookRegistry.scala`,
  `object <Name> extends OrpheraPlaybook`) remain the alternative for
  a playbook that should ship compiled into the orchestrator jar
  itself rather than compiled on demand — both forms produce the same
  `Playbook` value and run through the identical `PlaybookRunner`.
- `make orpheracli` — builds `orchestrator/assembly` and
  `scripting/assembly` together (both are required for the CLI's full
  feature set, since `.scala` playbook scripts need the latter) and
  prints, rather than runs, the `/usr/local/bin/orphera` install step.

### Fixed

- **`Cli.parseRunCommand`'s `--` handling double-appended every token
  after the separator** (`orphera run --nodes tst0 -- uptime -p` ran
  `uptime -p uptime -p`) — `--` now only marks "stop parsing flags"
  and does not itself append anything; the ordinary token-by-token
  recursion handles the rest.
- Several rounds of a now-familiar pattern recurred throughout this
  cycle's work: a method, case, or file described in one message
  didn't actually land before the next was built on top of it —
  `Task.Debug`/`Task.SetFact` missing from one of the two runners
  (each added independently, on different occasions), the group-vars
  consumption in `buildNestedNodeFacts` never actually calling
  `Inventory.groupVarsFor` despite `Group`/`groupVarsFor` existing
  correctly on the data side, `runScalaPlaybookScript`/`findLatestJar`
  referenced before being added to `Main.scala`. None of these are
  design problems — every fix was small once located — but this
  remains, by a wide margin, the single biggest source of wasted
  round-trips in this project's development, and is the standing case
  for CI (see [0.1.0]'s Known limitations) running `sbt clean compile test`
  on every push rather than relying on manual re-verification.
- **`.gitignore` was silently excluding `project/build.properties`,
  `project/plugins.sbt`, and `VERSION`** — none of them local/derived
  state, all essential, version-controlled build inputs. A broad
  `project/` ignore pattern caught the first two; `VERSION` was
  ignored outright. First surfaced as sbt falling back to a
  globally-installed sbt 2.x (incompatible with this project's
  plugins) after a fresh checkout/merge. Fixed to
  `project/target/` + `project/project/` only, and `VERSION` un-ignored.
- A cluster-work session separately overwrote `PlaybookRunner.scala`
  and `PlaybookYaml.scala` with what should have been new files
  (`ClusterPlaybookRunner.scala`, `ClusterPlaybookYaml.scala`) —
  caught via `sbt clean compile` reporting both objects missing
  despite the files existing on disk with content. Recovered by
  copying the misplaced content to the correct new filenames and
  restoring the originals.
- **`ClusterPlaybookRunner`'s stage health gate ran in the wrong
  order** on first implementation — a stage's own tasks ran to
  completion *before* its `wait_for` check, rather than being gated
  on it, defeating the entire purpose of the health check. Fixed so
  `wait_for` (if present) is evaluated first, and the stage's tasks
  only run once (and if) it passes.
- **`TaskYaml.scala`** extracted as a shared task/condition YAML
  decoder, consumed by both `PlaybookYaml` and `ClusterPlaybookYaml`,
  removing a near-complete duplication between the two that existed
  since the cluster front-end was first added.

### Known issues / not yet done

- Health checks only ever probe **one** designated node
  (`HealthCheck.onNode`) — there is no quorum-of-N gate (e.g. "wait
  until 2 of these 3 mons report healthy"), which a real multi-mon
  Ceph bootstrap would need.
- `Task.SetFact` has no way to target a specific node by name from
  within a shared, multi-node task list — the documented workaround
  is a per-node `when: hostname == "..."` guard on an otherwise
  identical task, repeated once per node (see inventory/group vars
  above for the better fit when the value is genuinely static:
  declare it directly in `inventory.yaml` instead).
- `run_command`'s health-check polling path
  (`ClusterPlaybookRunner.collectExitCode`) discards the command's
  stdout/stderr on every poll attempt to keep stage-progress logs
  readable — only usable as a pass/fail gate, not for inspecting
  output during polling.
- Real end-to-end Ceph mon bootstrap/quorum has not been attempted —
  the structural blockers identified when this thread of work started
  (no cross-node coordination, no fact sharing, no generic command
  execution) are now closed, but the actual Ceph-specific task
  sequence, secret/keyring distribution, and the quorum-of-N gate
  above remain unbuilt.

## [0.2.0]

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
- **`uptime` command/RPC** (`GetUptime`/`UptimeRequest`/`UptimeInfo`,
  reading `/proc/uptime`/`/proc/loadavg` directly rather than shelling
  out) — a concrete way to confirm a `reboot` genuinely rebooted the
  host, rather than only confirming the agent's gRPC service came
  back up.
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
