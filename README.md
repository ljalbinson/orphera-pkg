# Orphera

Orphera is a lightweight configuration-management and orchestration tool: an
`orchestrator` CLI drives one or more `agent` processes over gRPC to install
packages, push files, manage network config, and provision hosts.

It is an early-stage, self-built alternative to Ansible-style tools —
built for a small, deliberately understood feature set rather than broad
platform coverage. Currently targets Debian/Ubuntu hosts (`apt`,
`systemd-networkd`).

**Status: early / pre-production.** No automated test coverage yet beyond
what's described in [Testing](#testing). Not hardened for use against
untrusted networks or adversarial input.

## Architecture

```
orchestrator (CLI)  --gRPC/TLS-->  agent (daemon, runs as root on managed host)
      |
      +-- also drives SSH directly for first-time agent bootstrap/teardown
```

- **`agent`** — a small daemon, one per managed host, listening on
  `50051`. Executes commands locally (`apt-get`, file writes,
  `networkctl reload`, `dpkg`, `shutdown`) and streams progress back.
- **`orchestrator`** — a CLI you run from your workstation or a control
  host. Reads a static inventory, dispatches commands to one or more
  agents in parallel, and renders their event streams to the console.
- **`common`** — shared protobuf-defined message/service types
  (`Protocol.proto`), compiled via `sbt-fs2-grpc` into fs2/Cats Effect
  streaming clients and server stubs.

All agent RPCs are server-streaming (or bidi, for file transfer) and
report progress via a shared `Event` type (`PROGRESS` / `OUTPUT` /
`RESULT`), so the console output for every command follows the same
shape.

## Requirements

**Build machine:**

- **JDK 21.** Developed and tested against Oracle GraalVM 21.0.9
  (`21.0.9+7-LTS`) — any standard JDK 21 distribution should compile
  and run the JVM assembly jars fine; GraalVM is only actually needed
  if you intend to attempt `native-image` (currently non-functional,
  see below).
- **sbt 1.10.7**, pinned via `project/build.properties`. This is a hard
  requirement, not a suggestion — the project does **not** build on
  sbt 2.x. `sbt-fs2-grpc` (and several of its transitive plugin
  dependencies) are not published for sbt 2's plugin toolchain as of
  this writing; attempting to build under sbt 2 fails at the
  dependency-resolution stage before any code compiles. If your
  machine has sbt installed globally at a different version, the
  `project/build.properties` pin overrides it for this project only —
  no global sbt downgrade needed.
- **`protoc`** is *not* a separate manual install — `sbt-protoc`
  (pulled in transitively via the `Fs2Grpc` sbt plugin) fetches a
  matching `protoc` binary automatically on first build. No action
  needed unless your network blocks Maven Central, in which case the
  first `sbt compile` will fail trying to download it.
- **`openssl`**, for certificate generation (`make certs`).
- **`dpkg-deb`**, for building the `.deb` package (`make deb`). This
  effectively means the `.deb` must be built on a Debian/Ubuntu-family
  machine (or a container thereof) — it is not cross-buildable from,
  e.g., macOS without a Linux toolchain available.
- Internet access to Maven Central / repo1.maven.org for dependency
  resolution on first build (and after any `build.sbt`/`plugins.sbt`
  change). Builds are otherwise fully offline once the local
  `~/.ivy2`/`~/.cache/coursier` caches are warm.

**Target (managed) hosts:**

- Debian/Ubuntu with `systemd` and `apt`.
- `systemd-networkd` specifically (not `NetworkManager`) if using
  `network-apply` — the agent shells out to `networkctl reload` and
  reads/writes `/etc/systemd/network/*.{network,netdev,link}`.
- A JRE (`default-jre-headless` or equivalent) — pulled in
  automatically as a `.deb` dependency; no manual install needed if
  installing via the package.
- Root privileges for the agent process itself (package management,
  file ownership changes, network reconfiguration, and reboot all
  require it) — the systemd unit runs it as `User=root`.

**Orchestrator/control host** (may be the same machine as the build
machine, or different):

- JDK 21, to run the orchestrator's assembly jar (or `sbt run` it
  directly from source).
- SSH client (`ssh`, `scp`) with non-interactive key-based auth
  configured to target hosts, for `bootstrap`/`teardown` specifically.
  Password or passphrase-prompting auth will hang rather than fail
  cleanly — see [Provisioning a new host](#provisioning-a-new-host).

**Known-working combination**, as actually built and run over the
course of this project: Ubuntu (build/target hosts), Oracle GraalVM
21.0.9, sbt 1.10.7, Scala 3.8.4. Other JDK 21 distributions and recent
Debian/Ubuntu releases are expected to work but haven't been
specifically verified.

## Building

```bash
sbt clean compile      # compiles common (protobuf codegen), agent, orchestrator
sbt test               # runs unit tests (see Testing)
sbt agent/assembly      # produces agent/target/scala-3.8.4/agent-assembly-<version>-SNAPSHOT.jar
sbt orchestrator/assembly
```

Or, for the full pipeline (test → assembly → `.deb`):

```bash
make            # clean, test, assembly, deb
make deb        # just stage + build the .deb (implies assembly)
make verify     # build and print package contents/metadata
```

See [Makefile targets](#makefile-targets) below for the full list.

### A note on native-image

We evaluated compiling the agent/orchestrator to native binaries via
GraalVM `native-image` for faster startup and no JRE dependency. This is
**not currently working** — `grpc-netty-shaded`'s static initialization
order conflicts with native-image's build-time class initialization in
ways that persist even after explicitly marking the relevant classes
`--initialize-at-run-time`; the binary can build "successfully" and then
crash at runtime on the first real TLS handshake (missing reflection
metadata for internal Netty classes).

The `.deb` package therefore ships the JVM assembly jar and depends on
`default-jre-headless`. Revisiting native-image would mean either (a)
generating full reflection/resource config via GraalVM's tracing agent
run against a real end-to-end exercise of every RPC path, or (b)
swapping the transport away from `grpc-netty-shaded` to a native-image-
friendly alternative (e.g. `http4s-grpc`). Neither has been attempted
yet.

## Certificates

Orphera uses TLS between orchestrator and agent, verified against a
private CA, plus a shared-secret token (`ORPHERA_TOKEN`) checked on
every RPC as a second, independent auth factor.

Generate a CA and a wildcard server certificate for your domain:

```bash
make certs DOMAIN=yourdomain.com
```

This produces `certs/ca.{crt,key}` and `certs/server.{crt,key}`. The
server cert is a wildcard (`*.yourdomain.com`) plus `localhost` /
`127.0.0.1`, shared across every agent — **all agents currently use the
same private key.** This is a deliberate simplicity/security trade-off:
it makes the fleet trivial to extend (any new host under the domain
just works, no per-host cert issuance) at the cost of a single shared
secret whose compromise affects every agent at once. Revisit with
per-node certs (same CA, distinct key per host) if that trade-off stops
being acceptable — see inline notes in `Makefile`/`NodeClient.scala`.

**Important:** wildcard SANs only match one DNS label under the domain
and never match raw IP literals. Nodes must be addressed by a DNS name
under the wildcard's domain in `Inventory.all` — an IP-addressed node
will fail TLS hostname verification.

`certs/*.key` is git-ignored; never commit private keys.

## Running (development)

```bash
# terminal 1 — start an agent locally
export ORPHERA_TOKEN="dev-secret"
sbt "agent/run"

# terminal 2 — drive it
export ORPHERA_TOKEN="dev-secret"
sbt "orchestrator/run install curl vim"
```

`Compile / run / fork := true` is set on both projects — required so
cats-effect's `IOApp` runtime and Netty's threads get a real, separate
JVM rather than sharing sbt's own, which otherwise causes spurious
mid-request `RST_STREAM CANCEL` errors.

## Inventory

Hosts are currently defined in code, in
`orchestrator/src/main/scala/orphera/orchestrator/inventory.scala`:

```scala
object Inventory:
  val all =
    List(
      Node("web1", "web1.yourdomain.com"),
      Node("local-agent", "localhost")
    )
```

There is no dynamic inventory source (cloud provider API, etc.) —
static and hand-edited only, for now.

## CLI reference

Run `sbt "orchestrator/run help"` for the live version of this. Every
command accepts `--nodes host1,host2` to target a subset of inventory;
omitted, it targets everything (**except** `teardown`, which requires
explicit targeting — see below).

| Command | Purpose |
|---|---|
| `install <pkg...> [--update-cache]` | `apt-get install` |
| `remove <pkg...> [--purge]` | `apt-get remove`/`purge` |
| `autoremove [--purge]` | `apt-get autoremove` |
| `copy <local> <remote> [--owner] [--group] [--mode]` | Push a file, with an idempotent pre-check (content hash + owner/group/mode) so unchanged files are skipped |
| `network-apply [--timeout 60]` | Apply pushed `.network`/`.netdev`/`.link` files with automatic rollback if not confirmed within the timeout — see [Network config safety](#network-config-safety) |
| `reboot [--delay 5] [--wait] [--wait-timeout 300]` | Reboot a host. Runs fully detached from the agent's own process (`systemd-run`), for the same reason as `deploy-agent` below — the agent's own systemd unit would otherwise be killed by the reboot before it can schedule it. `--wait` polls the agent afterward and reports when it's reachable again (or times out) — see [Known gaps](#known-gaps--not-yet-built) for exactly what "reachable" does and doesn't confirm |
| `version [--nodes ...]` | Report each agent's running version, baked in at build time from the `VERSION` file — see [Versioning](#versioning) |
| `deploy-agent <local.deb> [--remote-path]` | Push and install an updated agent `.deb` on a host **that already has an agent running**. The install runs fully detached from the agent's own process (`systemd-run`), since the agent's own service restart would otherwise kill its own upgrade mid-unpack — see [Known gaps](#known-gaps--not-yet-built) for why success still can't be confirmed from the RPC alone |
| `bootstrap <local.deb> --nodes ... [--ssh-user] [--ssh-key]` | First-time agent install via SSH, for a host with **no agent yet** |
| `teardown --nodes ... --yes [--purge]` | Uninstall the agent via SSH. Requires explicit `--nodes` and `--yes` — no fleet-wide default, given the host becomes unmanageable via gRPC afterward |
| `fetch <remote-path> [--out ./dir]` | Pull a file back from one or more agents into a local directory, one file per node (named `<node>-<filename>`) |
| `facts [--nodes ...]` | Gather and print basic host facts (OS, kernel, CPU/memory, disks, interfaces) from one or more agents |
| `playbook <file.yaml \| compiled-name>` | Run an ordered, multi-task playbook, from either a YAML file or a compiled Scala DSL playbook by registered name — see [Playbooks](#playbooks) |

There is no `apply <manifest.yaml>` command — the standalone
declarative-file-manifest idea (independent, parallel file pushes, no
task ordering) was designed but superseded before being built;
[Playbooks](#playbooks) is the actual, implemented mechanism for
declarative, ordered multi-step execution, including file pushes as
one task type among several.

Every CLI command above (`install`, `remove`, `autoremove`, `copy`,
`network-apply`, `reboot`) currently has its **own direct dispatch
path** in `Main.scala`/`Orchestrator.scala`, separate from
`PlaybookRunner`. A design where single CLI commands are internally
expressed as one-task playbooks — so there's exactly one execution
engine instead of two — was discussed but is **not yet implemented**;
this is worth doing as a follow-up, since right now a fix or feature
added to `PlaybookRunner` (e.g. lock-contention retry behavior) does
not automatically apply to the direct CLI path, and vice versa.

## Playbooks

A playbook is an ordered list of tasks, run per node — unlike a
manifest's files (which are independent and pushed in parallel),
tasks within one node's playbook run strictly in sequence, so "install
nginx, then push its config, then remove the old package" is
expressible directly. Different nodes still run their full task
sequences in parallel with each other.

```yaml
name: web-server-baseline

nodes:
  - web1
  - web2

tasks:
  - name: install nginx
    install:
      packages: [nginx]
      update_cache: true

  - name: push nginx config
    copy:
      src: files/nginx.conf
      dest: /etc/nginx/nginx.conf
      owner: root
      group: root
      mode: "0644"

  - name: remove old apache
    remove:
      packages: [apache2]
      purge: true
    when: os_id == "ubuntu"
```

Run with:

```bash
sbt "orchestrator/run playbook manifests/web-server-baseline.yaml"
```

**Available task types:** `install`, `remove`, `autoremove`, `copy`,
`network_apply`, `reboot` — each maps directly onto the corresponding
CLI command's underlying RPC, so anything the CLI can do (except
`bootstrap`/`teardown`/`deploy-agent`/`facts`/`version`, which are
intentionally excluded — see below), a playbook task can do as one
step in a sequence. The `reboot` task's fields mirror the CLI command:
`delay`, `wait`, `wait_timeout` in YAML.

**`copy` templating:** if `src` ends in `.mustache`, it is rendered via
[Mustache](https://mustache.github.io/) before being pushed, using the
task's own `vars:` map merged with facts gathered for that node
(`facts.hostname`, `facts.os_id`, `facts.os_version`,
`facts.architecture` — note this is a different, wider naming
convention than the `when:` condition keys below; not yet normalized
to match). Non-`.mustache` sources are pushed verbatim. The idempotent
pre-check (content hash) runs against the *rendered* output, so
re-applying an unchanged playbook is a no-op even if the template
source itself changed in a way that doesn't affect the rendered
result.

**`when:` conditions** are simple `key == "value"` / `key != "value"`
checks against facts gathered from the node immediately before its
task sequence runs (see [Fact gathering](#fact-gathering)) — not a
general expression language. Supported keys currently:
`os_id`, `os_version`, `arch`, `hostname`.

**Failure behavior:** if a task fails, that node's remaining tasks are
skipped, but other nodes' playbooks continue independently — one
node's failure doesn't block or slow down the rest of the fleet.
Confirmed in practice: `apt-get`/`dpkg` lock contention (see below)
produces exactly this behavior — one node's `remove`/`autoremove` task
failing with exit 100 does not affect other nodes' progress.

**Facts-unavailable behavior:** if fact-gathering itself fails for a
node (agent unreachable, etc.), any task on that node with a `when:`
clause is conservatively skipped rather than causing the whole node's
playbook to abort; tasks with no condition still run normally. Worth
being aware of if a task's `when:` is silently never satisfied —
check whether facts are actually being retrieved from that node before
assuming the condition itself is wrong.

**`apt`/`dpkg` lock contention:** every `apt-get`/`dpkg` invocation on
the agent passes `-o DPkg::Lock::Timeout=60`, so a task that hits the
lock (background unattended-upgrades, another concurrent Orphera task,
etc.) waits up to 60 seconds and retries automatically rather than
failing immediately with exit 100. This was added after hitting the
failure directly in practice — see [Known gaps](#known-gaps--not-yet-built)
for the retry/backoff work this doesn't yet fully cover.

### Scala DSL front-end

As an alternative to YAML, a playbook can be written directly in
Scala and compiled as part of the `orchestrator` build:

```scala
// SPDX-License-Identifier: Apache-2.0

package orphera.orchestrator

import orphera.orchestrator.PlaybookDsl.*

object WebBaseline extends OrpheraPlaybook:

  val playbook: Playbook =
    PlaybookDsl.playbook("web-baseline", "tst0", "tst1", "tst2")
      .task("install curl")(Task.Install(packages = List("curl"), updateCache = true))
      .task("push motd")(
        Task.Copy(src = "files/motd", dest = "/etc/motd", owner = "root", group = "root", mode = 420)
      )
      .task("remove telnet if present")(Task.Remove(packages = List("telnet"), purge = true))
      .when("os_id" === "ubuntu")
      .build
```

Every DSL playbook needs an entry in `PlaybookRegistry.scala`'s `all`
map (`"web-baseline" -> WebBaseline.playbook`) to be reachable by name
— this is currently a manual, easy-to-forget step with no build-time
check that a defined playbook is actually registered.

Run a registered DSL playbook the same way as a YAML one, by name
instead of file path:

```bash
sbt "orchestrator/run playbook web-baseline"
```

Or, since `extends OrpheraPlaybook` makes it a real, independent
`IOApp`, run it directly — bypassing the CLI and registry entirely:

```bash
sbt "orchestrator/runMain orphera.orchestrator.WebBaseline"
```

`.task(name)(task)` and `.when(condition)` (attaching a condition to
the immediately preceding task) are the only two builder methods —
this is a plain, chainable builder, not a monadic/for-comprehension
structure. `===`/`=!=` (used in `.when(...)`) are extension methods
defined in `PlaybookDsl` and need `import orphera.orchestrator.PlaybookDsl.*`
in scope to resolve — a real, easy-to-hit compile error
(`value === is not a member of String`) if that import is missing.

**Not yet implemented:**
- Task-to-task data flow (using one task's result in a later task)
- Handlers/triggers (Ansible-style "restart only if config changed")
- Includes/imports across playbook files
- A monadic/for-comprehension style for the Scala DSL (`for _ <- run(...)
  yield ()`), as an alternative to the current fluent `.task(...)`
  chain — designed, not implemented; the fluent builder above is what
  actually exists and works
- Any compile-time check that a DSL playbook extending `OrpheraPlaybook`
  is also registered in `PlaybookRegistry` — currently a silent gap if
  forgotten, not a build error
- Per-task retry/backoff beyond the built-in `apt`/`dpkg` lock timeout
  above — a task that fails for another transient reason still stops
  that node's remaining tasks rather than retrying
- Direct CLI commands (`install`, `remove`, etc.) do **not** currently
  run through this same `PlaybookRunner` execution engine — see the
  note in [CLI reference](#cli-reference)

## Network config safety

`network-apply` exists because a bad `.network` file can permanently
sever the connection you're managing a host over. The design:

1. Agent backs up current `/etc/systemd/network/*.{network,netdev,link}`
   before applying anything.
2. Agent reloads (`networkctl reload`) and arms a rollback timer,
   entirely locally — it does not depend on the orchestrator being
   reachable to trigger the rollback.
3. Orchestrator waits briefly, probes connectivity itself, and only
   sends an explicit confirm if the probe succeeds.
4. If confirm never arrives (because the reload broke connectivity),
   the agent's own timer restores the backup and reloads again.

This protects against **immediate, detectable** breakage on **this**
host. It does not protect against config that's valid and confirms
successfully but causes problems elsewhere (a different host's
routing, an issue that only appears later) — test on non-critical
hosts first.

## Fact gathering

`facts [--nodes ...]` retrieves basic host facts via a unary RPC:
hostname, OS id/version, kernel version, architecture, CPU count,
memory total, disk mounts (with total/available space), and network
interfaces (name, IP addresses, MAC).

```bash
sbt "orchestrator/run facts --nodes web1"
```

Facts are always read live from the agent — there is no caching or
TTL on the agent side. `playbook` runs gather facts once per node,
at the start of that node's task sequence, and reuse the result for
every `when:` check in that sequence; they are not re-fetched per
task, and are not persisted anywhere beyond the single orchestrator
invocation that gathered them.

## File retrieval

`fetch <remote-path> [--out ./dir] [--nodes ...]` pulls a file back
from one or more agents, writing `<node-name>-<filename>` into the
output directory (default: current directory) per node, so fetching
the same path from multiple nodes doesn't collide.

```bash
sbt "orchestrator/run fetch /etc/nginx/nginx.conf --out /tmp/fetched --nodes web1,web2"
```

There is currently no path restriction on either `fetch` or `copy` —
an authenticated orchestrator can read or write any path the agent's
root user can reach. See [Known gaps](#known-gaps--not-yet-built).

## Versioning

Each built agent binary carries its own version number, generated at
compile time from the top-level `VERSION` file (the same file
`make release` bumps — see [Makefile targets](#makefile-targets)) via
an sbt source generator that writes `agent/.../BuildInfo.scala`. This
means the version reported by a running agent reflects whatever
`VERSION` held when that specific `.deb`/jar was built — not
necessarily what's in `VERSION` on your current dev machine, if you
haven't rebuilt and redeployed since the last bump.

```bash
sbt "orchestrator/run version --nodes tst0"
```

This exists specifically to make `deploy-agent`'s otherwise-ambiguous
outcome checkable after the fact — see
[Known gaps](#known-gaps--not-yet-built) for the current state of
wiring version verification directly into `deploy-agent` itself
(designed, not yet fully implemented as of this writing).

## Authentication and security notes

- **Transport:** TLS, agent cert verified against a private CA
  (`certs/ca.crt`) on the orchestrator side.
- **Application auth:** a shared bearer token (`ORPHERA_TOKEN`),
  checked via gRPC interceptor on every call. Must match between every
  agent and every orchestrator invocation.
- **Not implemented:** mutual TLS (agent doesn't verify orchestrator
  identity beyond the shared token), per-destination-path
  restrictions on `copy` (an authenticated orchestrator can currently
  write to any path the agent's root user can reach), audit logging,
  and secrets management for manifest files (don't commit secrets into
  `files/`).

## Provisioning a new host

```bash
make certs DOMAIN=yourdomain.com   # once, if not already done
make deb                            # build the agent .deb

sbt "orchestrator/run bootstrap orphera-agent_0.1.0_amd64.deb \
  --nodes newhost --ssh-user root --ssh-key ~/.ssh/id_ed25519"
```

`bootstrap` requires non-interactive SSH key auth (`BatchMode=yes`) —
an unlocked key or agent-forwarded key, no passphrase prompts, and if
`--ssh-user` isn't root, passwordless `sudo` for `dpkg`. Once bootstrap
succeeds and the systemd service is running, all future updates go
through `deploy-agent` (gRPC), not `bootstrap` again.

## Decommissioning a host

```bash
sbt "orchestrator/run teardown --nodes oldhost --yes --purge"
```

After teardown, the host is no longer reachable via gRPC — re-run
`bootstrap` if it needs to be managed again. `Inventory.all` is not
auto-updated; remove the entry manually.

## Makefile targets

| Target | Does |
|---|---|
| `make` / `make all` | clean → test → assembly → deb |
| `make clean` | `sbt clean`, removes `pkg/` and any built `.deb` |
| `make test` | `sbt test` |
| `make assembly` | `sbt agent/assembly` |
| `make deb` | stages package contents, runs `dpkg-deb --build` |
| `make verify` | builds, then prints `.deb` contents/metadata |
| `make certs DOMAIN=...` | generates CA + wildcard server cert. Refuses to run if `certs/ca.crt` already exists |
| `make certs-clean` | deletes `certs/` — interactive confirmation required; invalidates every already-deployed agent's trust |
| `make release` | bumps the patch version in `VERSION`, then runs the full clean/test/assembly/deb chain at the new version |

`certs`, `certs-clean`, and `release` are intentionally **not** part of
the default `all` chain. Cert (re)generation is a deliberate,
infrequent action with fleet-wide trust implications; version bumping
is likewise something that should be an explicit choice, not a side
effect of every routine rebuild while iterating. Plain `make`/`make
all` rebuilds at whatever version `VERSION` currently holds, with no
change to it.

The version lives in a single `VERSION` file at the repo root, read by
`build.sbt` (`ThisBuild / version := IO.read(file("VERSION")).trim`)
so the jar filename, `.deb` filename, and `DEBIAN/control` version all
stay in sync automatically. Minor/major bumps are manual — edit
`VERSION` directly when a batch of patch releases constitutes an
actual feature milestone; `make release` only ever increments the
patch number.

## Testing

Test infrastructure (munit + munit-cats-effect) and a proposed test
plan exist but are only partially implemented as of this writing.
Priority areas, roughly in order of value already identified:

1. `Cli` argument parsing (pure, no I/O — highest value for effort)
2. `FileTransfer` permission bit conversion and hash/attribute-mismatch
   logic (pure)
3. `AptInstaller`/`DebInstaller` process execution, once extracted
   behind a fake `CommandRunner` so tests don't require root or a real
   Debian host
4. `NetworkReloader` confirm/rollback race behavior, using
   `cats-effect-testkit`'s `TestControl` for deterministic virtual-time
   testing rather than real sleeps
5. In-process gRPC integration tests (auth interceptor accept/reject,
   full event-stream shape for each RPC)

See prior design discussion for the full proposed test list; not all
of it is reflected in code yet.

## Development notes

This project was developed with AI pair-programming assistance (Claude)
for code generation, debugging, and drafting documentation. Architecture
decisions, design trade-offs (e.g. the wildcard-cert approach, the
SSH-based bootstrap/teardown split from gRPC-based day-to-day management,
the network-config rollback design), review, and testing direction are
the author's.

## Known gaps / not yet built

- No dynamic/cloud inventory source
- No standalone declarative file-manifest mechanism (`apply`) or task
  ordering within one — playbooks are the actual implemented mechanism
  for ordered, multi-step execution, see [Playbooks](#playbooks)
- No per-node TLS certs (shared wildcard key across the fleet)
- No mutual TLS
- No path allowlisting on `copy` **or `fetch`** — an authenticated
  orchestrator can write or read any path the agent's root user can
  reach
- No secrets management
- No automated verification that `deploy-agent` actually succeeded.
  The install itself runs fully detached from the agent's own process
  (via `systemd-run --no-block`), specifically because the agent's own
  service restart otherwise kills the `dpkg -i` it just spawned
  mid-unpack (systemd's default `KillMode=control-group` sends
  `SIGTERM` to every process in the service's cgroup, including its
  own children) — this was hit and fixed in practice, not theoretical.
  `success = true` on `deploy-agent`'s `RESULT` event still only ever
  means "the install was launched," never "the install completed
  successfully." The `version` RPC (see [Versioning](#versioning)) now
  exists specifically to close this gap by polling the agent
  afterward and comparing against the expected version, but that
  polling loop is designed and not yet wired into `deploy-agent`
  itself as of this writing
- Direct CLI commands (`install`, `remove`, `autoremove`, `copy`,
  `network-apply`, `reboot`) each have their own dispatch path in
  `Main.scala`/`Orchestrator.scala`, separate from `PlaybookRunner` —
  a design where they're unified (CLI commands as one-task playbooks,
  one execution engine) was discussed but not implemented. Practical
  consequence: the `DPkg::Lock::Timeout` fix and any future
  `PlaybookRunner`-level improvement (retries, etc.) apply to
  `playbook` runs but not to direct CLI invocations, unless ported to
  both places separately
- No task-to-task data flow, handlers/triggers, or playbook
  includes/imports
- No monadic/for-comprehension style for the Scala DSL, as an
  alternative to the current fluent `.task(...).when(...)` builder —
  designed, not implemented, see [Playbooks](#playbooks)
- No compile-time check that a DSL playbook is actually registered in
  `PlaybookRegistry` — a defined-but-unregistered playbook fails
  silently at the CLI (though it's still directly runnable via
  `runMain`, since `OrpheraPlaybook` doesn't depend on the registry)
- No per-task retry/backoff in `PlaybookRunner` beyond the built-in
  `apt`/`dpkg` lock-contention timeout — other transient failures
  still stop a node's remaining tasks rather than retrying
- `reboot --wait` confirms only that the agent's gRPC service answers
  again — a real, useful signal, but distinct from confirming the
  whole host finished a normal boot sequence
- Fact keys usable in `when:` conditions are a small fixed set
  (`os_id`, `os_version`, `arch`, `hostname`) — not the full `Facts`
  schema, and use a different naming convention than the `facts.*`
  keys exposed to `copy` task templating (not yet normalized)
- Test coverage incomplete (see [Testing](#testing))
- Native-image build not working (see [A note on native-image](#a-note-on-native-image))
- `-Werror`/`-Wconf` are now enabled in `build.sbt`, which has already
  caught at least one real bug (a `Command.Fetch` case parsed by `Cli`
  but never handled in `Main.scala`) as a hard compile failure instead
  of a silent runtime crash. No CI pipeline exists yet to run this
  automatically on every push, which remains the main way build
  breakages have been caught late in this project's history rather
  than immediately.
- **`wait` cannot be used as a case class/`enum case`/parameter name
  anywhere in this codebase.** `java.lang.Object` declares a `final`
  `wait()` method, inherited by every class; a Scala class or enum
  case with a field literally named `wait` fails to compile
  (`error overriding method wait in class Object`). Hit independently
  in both `Task.Reboot` and `Cli.Command.Reboot` while building the
  reboot feature — both use `waitForReturn` instead. Worth remembering
  before naming any future field `wait` anywhere in this project.
