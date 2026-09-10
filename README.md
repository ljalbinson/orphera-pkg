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
| `apply <manifest.yaml>` | Push a declarative set of files (see [Manifests](#manifests)) |
| `network-apply [--timeout 60]` | Apply pushed `.network`/`.netdev`/`.link` files with automatic rollback if not confirmed within the timeout — see [Network config safety](#network-config-safety) |
| `reboot [--delay 5]` | Trigger a host reboot |
| `deploy-agent <local.deb> [--remote-path]` | Push and install an updated agent `.deb` on a host **that already has an agent running** |
| `bootstrap <local.deb> --nodes ... [--ssh-user] [--ssh-key]` | First-time agent install via SSH, for a host with **no agent yet** |
| `teardown --nodes ... --yes [--purge]` | Uninstall the agent via SSH. Requires explicit `--nodes` and `--yes` — no fleet-wide default, given the host becomes unmanageable via gRPC afterward |

## Manifests

`apply <manifest.yaml>` pushes a declarative list of files instead of
one-off `copy` invocations:

```yaml
nodes:
  - web1
  - web2

files:
  - src: files/nginx.conf
    dest: /etc/nginx/nginx.conf
    owner: root
    group: root
    mode: "0644"
```

Files ending `.mustache` are rendered through
[Mustache](https://mustache.github.io/) before push, using a `vars` map
in the manifest entry — see `Templating.scala`. Everything else is
copied verbatim. The pre-check (content hash) runs against the
*rendered* output, so re-applying an unchanged manifest is a no-op.

There is no task ordering, dependency graph, or conditional logic
between manifest entries — each file is independent and may be pushed
in parallel. If you need "install X, then push its config, then
restart the service," that sequencing isn't modeled yet.

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

`certs` and `certs-clean` are intentionally **not** part of the default
`all` chain — cert (re)generation is a deliberate, infrequent action
with fleet-wide trust implications, not something that should run as a
side effect of a routine build.

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
- No task ordering or dependencies within or across manifests
- No per-node TLS certs (shared wildcard key across the fleet)
- No mutual TLS
- No path allowlisting on `copy`'s destination
- No secrets management
- No automated verification that `deploy-agent` actually succeeded
  (the RPC stream is expected to drop mid-call on a successful
  self-restart, so success can't be inferred from the call outcome
  alone — a follow-up version-check RPC would close this gap)
- Test coverage incomplete (see [Testing](#testing))
- Native-image build not working (see [A note on native-image](#a-note-on-native-image))
