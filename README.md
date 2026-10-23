# Orphera

Orphera is a distributed orchestration system for coordinating real,
multi-node infrastructure changes — not a package installer with a
prettier syntax. A central `orchestrator` directs persistent `agent`
daemons over authenticated gRPC/TLS, and can stage work across a fleet
with genuine cross-node awareness: one node's config can embed
another's address, a rollout can wait on a quorum of N healthy nodes
before proceeding, and a whole tier can gate on another tier's
readiness — the kind of coordination a flat list of per-host SSH tasks
can't express. It's been used to bring up a real 3-node Ceph cluster
end-to-end (mon quorum, mgr, OSDs), not just installed a few packages.

Where Ansible executes ad hoc, per-run scripts pushed over SSH, Orphera
runs long-lived agents that a coordinator drives directly, and gives you
three ways to describe what should happen: declarative YAML, a typed
Scala DSL with compile-time checking, or run-time-compiled Scala
scripts — all sharing the same staged, health-gated execution engine
underneath. It currently targets Debian/Ubuntu hosts (`apt`,
`systemd-networkd`) and Rocky/Red Hat-family hosts (`dnf`, NetworkManager), built for a small, deliberately understood feature
set rather than broad platform coverage first.

**Status: early / pre-production.** No automated test coverage yet beyond
what's described in [Testing](#testing). Not hardened for use against
untrusted networks or adversarial input.

## Index

- [Architecture](#architecture)
- [Requirements](#requirements)
- [Building](#building)
- [Certificates](#certificates)
- [Running (development)](#running-development)
- [Inventory](#inventory)
- [CLI reference](#cli-reference)
- [Playbooks](#playbooks)
- [Cross-node coordination and staged playbooks](#cross-node-coordination-and-staged-playbooks)
- [Cross-node data and vars](#cross-node-data-and-vars)
- [Network config safety](#network-config-safety)
- [Fact gathering](#fact-gathering)
- [File retrieval](#file-retrieval)
- [Restartability](#restartability)
- [Observability](#observability)
- [Versioning](#versioning)
- [Authentication and security notes](#authentication-and-security-notes)
- [Secrets](#secrets)
- [Typed configuration files and check mode](#typed-configuration-files-and-check-mode)
- [Provisioning a new host](#provisioning-a-new-host)
- [Decommissioning a host](#decommissioning-a-host)
- [Installing the command line](#installing-the-command-line)
- [Makefile targets](#makefile-targets)
- [Testing](#testing)
- [Development notes](#development-notes)
- [Example: a real Ceph cluster deployment](#example-a-real-ceph-cluster-deployment)
- [Example: OpenStack Keystone and Cinder from Kolla images](#example-openstack-keystone-and-cinder-from-kolla-images)
- [Example: parallel network throughput test (iperf3)](#example-parallel-network-throughput-test-iperf3)
- [Known gaps / not yet built](#known-gaps--not-yet-built)

## Architecture

```
orchestrator (CLI)  --gRPC/TLS-->  agent (daemon, runs as root on managed host)
      |
      +-- also drives SSH directly for first-time agent bootstrap/teardown
```

- **`agent`** — a small daemon, one per managed host, listening on
  `50051`. Executes commands locally (`apt-get` or `dnf`, file writes,
  `networkctl reload` or `nmcli`, `dpkg` or `rpm`, `shutdown`) and streams progress back.
  There are two implementations of it: `agent/` in Scala, which the
  top-level build packages, and `agent-go/` in Go (see [Go agent](#go-agent)).
- **`orchestrator`** — a CLI you run from your workstation or a control
  host. Reads a static inventory, dispatches commands to one or more
  agents in parallel, and renders their event streams to the console.
- **`common`** — shared protobuf-defined message/service types
  (`Protocol.proto`), compiled via `sbt-fs2-grpc` into fs2/Cats Effect
  streaming clients and server stubs.

The agent RPCs that change a host are server-streaming (or bidi, for
file transfer) and report progress via a shared `Event` type
(`PROGRESS` / `OUTPUT` / `RESULT`), so the console output for every
command follows the same shape. The read-only queries (`facts`,
`version`, `uptime`, `thread-info`) are plain request/response calls.

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
- **`rpmbuild`** (optional; Ubuntu: `sudo apt install rpm`), for the Rocky/Red Hat
  `.rpm` package (`make rpm`). `make release` builds it when present and skips it
  otherwise.
- **`dpkg-deb`**, for building the `.deb` package (`make deb`). This
  effectively means the `.deb` must be built on a Debian/Ubuntu-family
  machine (or a container thereof) — it is not cross-buildable from,
  e.g., macOS without a Linux toolchain available.
- Internet access to Maven Central / repo1.maven.org for dependency
  resolution on first build (and after any `build.sbt`/`plugins.sbt`
  change). Builds are otherwise fully offline once the local
  `~/.ivy2`/`~/.cache/coursier` caches are warm.

**Target (managed) hosts:**

- Debian/Ubuntu with `systemd` and `apt`, or a Red Hat-family host (tested: Rocky 10)
  with `systemd` and `dnf`. The agent chooses the package manager on the host, so
  one command can serve a mixed fleet.
- `systemd-networkd` or `NetworkManager` if using `network-apply`: the agent
  uses networkd (`networkctl reload`, `/etc/systemd/network/*.{network,netdev,link}`)
  unless the host has `nmcli` and no `networkctl` (Rocky/RHEL), in which case it
  uses NetworkManager keyfiles.
- A JRE (`default-jre-headless` or equivalent) — pulled in
  automatically as a `.deb` dependency (`java-headless` for the `.rpm`); no manual install needed if
  installing via the package.
- Root privileges for the agent process itself (package management,
  file ownership changes, network reconfiguration, and reboot all
  require it) — the systemd unit runs it as `User=root`.

**Orchestrator/control host** (may be the same machine as the build
machine, or different):

- JDK 21, to run the orchestrator's assembly jar (or `sbt run` it
  directly from source).
- [`age`](https://age-encryption.org) (Ubuntu: `sudo apt install age`,
  which also provides `age-keygen`), for the encrypted secret store used
  by `orphera secrets` and by playbooks that need a password. Nothing
  else needs it; see [Secrets](#secrets).
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
yet. The Go agent below is the other route to an agent with no JRE.

### Go agent

`agent-go/` is a second implementation of the agent, in Go. It serves the
same gRPC protocol (generated from the same `Protocol.proto`) on the same
port, with the same token, certificates and never-reboot marker, so the
orchestrator works with either. It builds to a single static binary with no
Java runtime; `orphera thread-info` shows it at about 15 MB resident and 8
threads, against roughly 170 to 200 MB and 30 threads for the JVM agent.

It has its own Makefile and is not part of the sbt build or `make release`:

```bash
cd agent-go
make            # lint (gofmt, go vet, staticcheck), tests, binaries
make deb        # orphera-agent_<version>+go_amd64.deb
make rpm        # orphera-agent-<version>+go-1.x86_64.rpm
cd ..
orphera deploy-agent --agent agent-go/orphera-agent_<version>+go_amd64.deb --nodes tst0
```

The packages are the same `orphera-agent` package as the Scala ones, with the
Go binary in place of the jar, so either replaces the other with
`deploy-agent`. Their version is `VERSION` with `+go` added, which the agent
also reports, so `orphera version` shows which agent a node runs.

`make release` builds only the Scala packages, and `deploy-agent` without
`--agent` picks those, so nodes running the Go agent go back to the Scala one
at the next release unless the Go package is passed with `--agent`.

[`agent-go/README.md`](agent-go/README.md) has the details: layout,
differences from the Scala agent, how its facts match the JVM's, and trying
it on a node by hand.

### Versions and tags

`VERSION` is bumped by `make release` (`make bump-patch`), which does not
commit the change or tag it: commit `VERSION` with a message such as
`Release 0.1.198`, and tag by hand (`git tag -a v0.1.198 -m "Release
0.1.198"`, then `git push origin v0.1.198`) when a version is worth
marking. Tags exist only for some versions (the first is `v0.1.156`, a
development baseline that compiles), so to find
the code a given `.deb` was built from, look for its `Release <version>`
commit with `git log --grep "Release 0.1.198"`. See
[CHANGELOG.md](CHANGELOG.md) for what each version contains.

## Certificates

Orphera uses TLS between orchestrator and agent, verified against a
private CA, plus a shared-secret token (`ORPHERA_TOKEN`) checked on
every RPC as a second, independent auth factor.

Make the CA once:

```bash
make certs
```

This produces `certs/ca.{crt,key}`. The orchestrator trusts `ca.crt`;
`ca.key` signs each node's own certificate and must stay private.

### Per-node certificates

Every agent has a certificate and key of its own; the agent package ships
none. `orphera bootstrap` issues one to each node it installs, and
`orphera issue-certs [--nodes ...] --ssh-user localadmin` issues (or
renews) them for nodes already running. Over SSH, each node generates a
new key in `/etc/orphera-agent/tls/` (mode 0600; the key never leaves the
node) and sends back a signing request; the orchestrator signs it with
`certs/ca.key`, naming exactly the node's inventory `host` (a DNS name or
an IP address), and sends the certificate back. The node checks that the
certificate matches the new key before replacing anything and restarting
the agent, so a failure leaves the current pair in place. Certificates
are valid for 825 days; rerun `issue-certs` to renew.

No package owns `tls/`, so installing or upgrading the agent never
removes it. An agent with no certificate in `tls/` will not start, and
says so in its journal; `deploy-agent` refuses to upgrade such a node,
since the upgraded agent could not start and there would be no SSH
session to recover with — run `issue-certs` for it first.

`orphera cert-info [--nodes ...]` shows which certificate each agent
actually presents, read from the TLS handshake (no SSH or token needed):

```
[tst8] per-node tst8.ljalbinson.com  expires 2029-01-21 (825d)  serial 5f3a9c001122  sha256 AB:CD:...
```

A line ending `!! ...` names a problem: the certificate does not name the
node's host, is not signed by `certs/ca.crt`, has expired, or expires
within 30 days. The command exits non-zero if any node has a problem or
cannot be reached.

`certs/*.key`, `certs/*.srl` and `certs/*.csr` are git-ignored; never
commit private keys. The orchestrator reads `ca.crt` from `ORPHERA_CERTS_DIR`
(default `certs`, relative to where `orphera` runs): the `orphera` wrapper (`utils/orphera`) changes into
`$ORPHERA_HOME` (default `~/orphera-pkg`, if that exists) first, whatever directory you
run it from. The agent reads its own certificate and key from
`/etc/orphera-agent/tls/`.

### Replacing the CA

Do this if the CA key has been committed, copied somewhere it should not
be, or otherwise exposed. Every node's certificate was signed by the old
CA, so every node needs a new one, and the orchestrator trusts one CA at a
time:

1. Move the old directory away (`mv certs ~/certs-old`) and run
   `make certs`. Keep `certs/ca.key` private.
2. `orphera issue-certs --ssh-user <user>` (all nodes, or `--nodes ...`).
   It works over SSH, so it does not need the old certificate to be
   trusted, which is why it is used and not a gRPC command. Do one node
   first: `orphera version --nodes <node>` fails before it has a
   certificate from the new CA and succeeds afterwards.
3. `orphera cert-info` should show every node's certificate as signed by
   the new CA. A node that was down during the rollout keeps its old
   certificate and cannot be reached until `issue-certs` has been run for
   it. `bootstrap` also issues a certificate, for nodes being installed
   or reinstalled.
4. To confirm the old CA is rejected, make a directory that holds only the
   old `certs/ca.crt` and the inventory, and run from it:

   ```bash
   mkdir -p /tmp/catest/certs
   cp inventory.yaml /tmp/catest/
   cp ~/certs-old/ca.crt /tmp/catest/certs/
   ORPHERA_HOME=/tmp/catest orphera version --nodes <node>
   ```

   It must print `unreachable`. `ORPHERA_HOME` has to be a directory
   because the `orphera` wrapper changes into it before it starts, so
   running the command from another directory proves nothing.
5. Delete the old directory, including backups.

Git history keeps any key that was ever committed, so a key that has
been public is replaced, not just removed from the tree.

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

Hosts, groups, and their variables are defined in `inventory.yaml` at
the repo root, loaded once at startup by `InventoryYaml.scala`. Its
location is a path or a URI, chosen with `-I <uri>` before the command or
`ORPHERA_INVENTORY` (see [Where the inventory and configs are](#where-the-inventory-and-configs-are)):

```yaml
nodes:
  - name: tst0
    host: tst0.yourdomain.com
    vars:
      cluster_ip: "10.0.5.11"
      ceph_role: mon-leader

  - name: tst1
    host: tst1.yourdomain.com
    vars:
      cluster_ip: "10.0.5.12"

groups:
  - name: mons
    members: [tst0, tst1]
    vars:
      ceph_role: mon
```

**`never-reboot: true`** on a node, or on a group (which covers its members), marks a host that must not be
rebooted, such as a hypervisor:

```yaml
nodes:
  - name: gs1
    host: gs1.yourdomain.com
    never-reboot: true
groups:
  - name: hypervisors
    members: [gs2, gs3]
    never-reboot: true
```

`orphera reboot` and `orphera shutdown` skip such nodes and say so, and still act on the others. A `reboot` task in a
playbook, or a `reboot` pipeline step, fails on such a node with a message naming the property. Only the value `true`
or `false` is accepted. The name says reboot, but it also stops `orphera shutdown`: a host that must stay up must not be
powered off either. It guards Orphera's own reboot and shutdown paths: it does not stop a command that reboots or powers
off the host itself (run with `orphera run -- shutdown`), nor the VM scripts in `utils/`, which act on a guest, not the
host the property is usually set on.

The inventory is read by the `orphera` command, so only a current `orphera` obeys it: an older build sends the shutdown
straight to the host. For a host that must stay up, the agent can refuse as well. An agent whose host has the file
`/etc/orphera-agent/never-reboot` (or the path in `ORPHERA_NEVER_REBOOT_FILE`) answers a reboot or shutdown request with
`FAILED_PRECONDITION`, whichever `orphera` sent it. It checks the file on every request, so creating or removing it
needs no restart. `utils/sync-never-reboot.py` creates the file on every host the inventory protects (same rule: the
node, or a group it is in); it shows what it would do unless given `--apply`, and `--prune` also removes the file from
hosts that are no longer protected. The agent must be a build that has the check: deploy it first, then run the script.
An older agent ignores the file.

`orphera version` prints the build of `orphera` itself first (version, commit, commit date, and whether the tree had
uncommitted changes), then each node's agent version. Compare the commit with `git log -1` in the checkout to see whether the
installed jar is older than the code.

A missing or malformed `inventory.yaml` fails loudly — a clear stderr
warning at startup, then an empty inventory (so every subsequent "no
matching nodes" error traces back to it) — rather than failing
silently.

**Node and group vars** are available in every playbook task's
templates and `when:`-style lookups, nested under
`nodes.<node-name>.*` alongside gathered facts and `set_fact` values
— see [Cross-node data and vars](#cross-node-data-and-vars) for the
full merge and precedence rules.

**Targeting groups.** Any command that takes `--nodes` also takes
`--node-groups g1,g2`, which expands to the members of those `groups:`
entries. Combined with `--nodes` it is a union (duplicates dropped).
An unknown group name is an error listing the defined groups, rather
than quietly targeting fewer nodes. The expansion happens before the
command is parsed, so it also works for `bootstrap` and `teardown`
(which require explicit targeting) and the audit log records the
resulting node list:

```
orphera dist-upgrade --node-groups mons
orphera run --node-groups mons --nodes tst5 -- uptime
```

**Leaving nodes out.** `--exclude-nodes a,b` takes nodes out of what `--nodes` and `--node-groups` selected, so a group
can be used with some members left out:

```
orphera dist-upgrade --node-groups mons --exclude-nodes tst1
orphera reboot --node-groups ceph --exclude-nodes tst0 --yes
```

With neither `--nodes` nor `--node-groups` it means every node except those, for the commands where no `--nodes` already
means every node (`install`, `remove`, `autoremove`, `dist-upgrade`, `copy`, `write-file`, `network-apply`, `deploy-agent`,
`run`, `fetch`, `facts`, `version`, `uptime`, `thread-info`):

```
orphera deploy-agent --agent orphera-agent_0.1.200_amd64.deb --exclude-nodes tst8
```

For `reboot`, `shutdown`, `teardown`, `bootstrap`, a pipeline and the playbook commands it is an error, so leaving one node
out can never point them at the whole fleet. A name that was not selected is an error (a typo would otherwise leave the node in), as is an exclusion that leaves no nodes. It is applied with
the group expansion, so the audit log records the nodes that were actually targeted. A pipeline's steps cannot give it;
put it on the pipeline itself.

There is no dynamic inventory source (cloud provider API, etc.) —
static and hand-edited only, for now.

## CLI reference

### Where the inventory, configs and playbooks are

Three global options go **before** the command:

| Option | Environment variable | Default | What it locates |
|---|---|---|---|
| `-I`, `--inventory <uri>` | `ORPHERA_INVENTORY` | `inventory.yaml` | the inventory |
| `-c`, `--config-dir <uri>` | `ORPHERA_CONFIG_DIR` | `config` | the directory of per-VM config files |
| `-m`, `--manifests <dir>` | `ORPHERA_MANIFESTS` | `manifests` | the directory of playbooks |

```
orphera -I file:///etc/orphera/inventory.yaml -c file:///etc/orphera/config version
orphera -c /srv/vms cluster-playbook manifests/gen_set_dns.scala --config tst7
orphera -m /usr/share/orphera/manifests cluster-playbook gen_set_dns.scala gen_packages.scala --config tst7
```

A playbook given as a bare file name ending in `.scala`, `.yaml` or `.yml` is looked for in the manifests directory first and,
if it is not there, taken as it is (so a file in the current directory still works). A name with a `/`
(`manifests/gen_set_dns.scala`, `/srv/pb/x.yaml`), the `@bootstrap` step and the name of a compiled playbook are never looked
up, so every command line that worked before still means the same. The manifests directory is a plain path or a `file:`
URI: a `.scala` playbook is compiled from a file.

The option wins over the environment variable, which wins over the default. A location is a plain path or a URI;
`file:` URIs (`file:///path`), plain paths and `mariadb://` can be read. A `mariadb://user@host/database/inventory` or
`.../database/config` location reads the tables read-only, over TLS verified against `certs/ca.crt`, with the password from the
secret `orphera/inventory-db-reader` ([docs/MARIADB.md](docs/MARIADB.md)). Another source is added in one place
(`Sources.register`, `Locations.scala`) and nothing that reads the inventory changes. A scheme with no reader is
refused with the list of those there are. Relative paths are relative to `ORPHERA_HOME`, as the launcher changes into
it first. The options also reach the playbook scripts and pipeline steps that `orphera` starts. They go before the
command because after it `-c` would be ambiguous (`orphera run sh -c ...`). The files have JSON Schemas, in
[`schemas/`](schemas/) and described in [docs/SCHEMAS.md](docs/SCHEMAS.md); `make check-schemas` checks them.

**The shell scripts follow the environment variables, not the options.** `manifests/build_openstack_stack.sh`,
`mk-new-and-empty.sh`, `par-mk-new-and-empty.sh`, `rebuild_openstack_from_scratch.sh` and the VM scripts in `utils/`
find `config/<vm>.yaml` and `inventory.yaml` through `utils/orphera-sources.sh`, which reads `ORPHERA_CONFIG_DIR` and
`ORPHERA_INVENTORY` (a plain path or a `file:` URI; any other scheme is refused with a message). They do not read `-I` and
`-c`, because those are options of the `orphera` command, so set the variables, which `orphera` itself also reads. For a
database, export it first (`utils/sql-to-yaml.py --out DIR`) and point both variables at `DIR`. `make check-scripts` checks
this without a lab. See [docs/MARIADB.md](docs/MARIADB.md).

The scripts name playbooks by bare file name (`orphera cluster-playbook gen_set_dns.scala`), not `manifests/gen_set_dns.scala`, so
`orphera` finds them in `-m` / `ORPHERA_MANIFESTS` (default `manifests`), and a script run with `ORPHERA_MANIFESTS=/opt/orphera/manifests`
applies the playbooks from there. The scripts call each other relative to their own location. They need an `orphera` that has the
`-m` option: 0.1.201 or later (0.1.200 does not have it).

With `-c` set, `--config tst7` means `<config-dir>/tst7.yaml`. A `--config` value that has a `/`, a scheme or a
`.yaml`/`.yml` ending (`config/tst7.yaml`, `/x/y.yaml`) is used exactly as given, as before.

**Running without a checkout of the repository.** `orphera` needs five things, each with a place you can set:

| What | Setting | Default |
|---|---|---|
| inventory | `-I` / `ORPHERA_INVENTORY` | `inventory.yaml` |
| per-VM configs | `-c` / `ORPHERA_CONFIG_DIR` | `config` |
| playbooks | `-m` / `ORPHERA_MANIFESTS` | `manifests` |
| the CA certificate and key (`ca.crt`, `ca.key`) | `ORPHERA_CERTS_DIR` | `certs` |
| run state: `.orphera-state`, `.orphera-logs`, `.orphera-audit`, `.orphera-build-logs` | `ORPHERA_STATE_DIR` | the current directory |

The jars that compile and run `.scala` playbooks are installed by `make orpheracli` as `/usr/local/lib/orphera-scripting.jar`
and `/usr/local/lib/orphera-orchestrator.jar` (`ORPHERA_SCRIPTING_JAR` and `ORPHERA_ORCHESTRATOR_JAR` name others; each falls
back to the build tree under `scripting/target/` or `orchestrator/target/`). The `orphera` launcher runs from `$ORPHERA_HOME` if that is set (it must exist), else
from `~/orphera-pkg` if that exists, else from the current directory, so on a machine with no checkout the relative defaults
above apply to wherever you run it, and the settings move them. Playbook scripts started by `orphera` inherit the
environment variables. `make certs` honours `ORPHERA_CERTS_DIR` too.

Run `sbt "orchestrator/run help"` for the live version of this. Every
command accepts `--nodes host1,host2` (and/or `--node-groups g1,g2`) to target a
subset of inventory;
omitted, it targets everything (**except** `teardown`, which requires
explicit targeting — see below).

Verbs that act on nodes (`install`, `remove`, `dist-upgrade`, `run`, `copy`, `write-file`, ...) exit non-zero if any node reports `success=false`, so `$?` can be relied on in scripts.

| Command | Purpose |
|---|---|
| `install <pkg...> [--update-cache]` | `apt-get install` (`dnf install` on Red Hat-family hosts) |
| `remove <pkg...> [--purge]` | `apt-get remove`/`purge` (`dnf remove`; `--purge` ignored) |
| `autoremove [--purge]` | `apt-get autoremove` (`dnf autoremove`) |
| `dist-upgrade [--no-update-cache]` | `apt-get update` then `apt-get dist-upgrade`, keeping existing config files and never prompting; `--no-update-cache` skips the update. On Red Hat-family hosts it is `dnf upgrade` (with a metadata refresh unless `--no-update-cache`). Also available as the `dist_upgrade` playbook task |
| `copy <local> <remote> [--owner] [--group] [--mode]` | Push a file, with an idempotent pre-check (content hash + owner/group/mode) so unchanged files are skipped |
| `write-file <remote> (--content <string> \| --content-file <local>) [--owner] [--group] [--mode]` | Write a literal string straight to a file on the agent — no local source file required first, unlike `copy`. `--content-file` is for when the string is awkward to pass inline (multi-line, shell-quoting-sensitive); it still just reads that local file and sends its bytes, same mechanism as `copy` |
| `network-apply [--timeout 60]` | Apply pushed `.network`/`.netdev`/`.link` files (or, on NetworkManager hosts, `.nmconnection` keyfiles) with automatic rollback if not confirmed within the timeout — see [Network config safety](#network-config-safety) |
| `reboot [--delay 5] [--wait] [--wait-timeout 300]` | Reboot a host. Runs fully detached from the agent's own process (`systemd-run`), for the same reason as `deploy-agent` below — the agent's own systemd unit would otherwise be killed by the reboot before it can schedule it. `--wait` polls the agent afterward and reports when it's reachable again (or times out) — see [Known gaps](#known-gaps--not-yet-built) for exactly what "reachable" does and doesn't confirm |
| `version [--nodes ...]` | Print the build of `orphera` itself first (version, commit, commit date, "+uncommitted changes" if the tree was dirty), then each agent's running version, baked in at build time from the `VERSION` file — see [Versioning](#versioning) |
| `deploy-agent [--agent <local.deb\|.rpm>] [--remote-path]` | Push and install an updated agent package (`.deb` or `.rpm`, chosen per node; found automatically when `--agent` is omitted) on a host **that already has an agent running**. It refuses to start if the orchestrator has no agent token configured, since an upgraded agent would not start without one, and `deploy-agent` could not repair it. The install runs fully detached from the agent's own process (`systemd-run`), since the agent's own service restart would otherwise kill its own upgrade mid-unpack, so the RPC call that launches it can't itself confirm completion — instead, each node is polled afterward via the `version` RPC (60s default timeout) until it reports the `.deb`'s own version or the timeout elapses; the command's exit code and printed summary reflect that confirmation, not just that the install was launched — see [Versioning](#versioning) |
| `bootstrap [--agent <local.deb\|.rpm>] --nodes ... [--ssh-user] [--ssh-key] [--forget-host-key]` | First-time agent install via SSH, for a host with **no agent yet**. The host's package manager decides whether the `.deb` or `.rpm` is used. `--ssh-user` defaults to `root`; the cloud images used here block root login, so pass `--ssh-user localadmin`. It then issues the node its own certificate (as `issue-certs`; needs `certs/ca.key`) and sets `ORPHERA_TOKEN` in the node's `/etc/orphera-agent/agent.env` to this orchestrator's token (keeping any other lines), restarting the agent. It stops before touching any node if the CA key is missing or if the orchestrator has no agent token configured, because an agent without a token does not start. On a dnf host a package of the same version and release is reinstalled rather than skipped, so a rebuild at an unchanged `VERSION` (new certificates, say) still reaches the node |
| `teardown --nodes ... --yes [--purge]` | Uninstall the agent via SSH. Requires explicit `--nodes` and `--yes` — no fleet-wide default, given the host becomes unmanageable via gRPC afterward. `--purge` also removes the agent's configuration, including its certificate and key in `/etc/orphera-agent/tls` |
| `fetch <remote-path> [--out ./dir]` | Pull a file back from one or more agents into a local directory, one file per node (named `<node>-<filename>`) |
| `facts [--nodes ...]` | Gather and print basic host facts (OS, kernel, CPU/memory, disks, interfaces) from one or more agents |
| `shutdown --nodes ... --yes [--delay 5]` | Power hosts **off** (`shutdown -P now`, detached via `systemd-run` like `reboot`). Refuses to run without `--yes` or without explicit `--nodes`/`--node-groups` (never defaults to everything), because nothing here can power a host back on. Returns once the agent acknowledges scheduling it — it does not confirm the host actually went down |
| `uptime [--nodes ...]` | Report each agent's host uptime and 1-minute load average — the concrete way to confirm a `reboot` genuinely rebooted the host, not just that the agent came back |
| `cert-info [--nodes ...]` | Show the TLS certificate each agent presents, read from the TLS handshake (no SSH or token needed): per-node or wildcard, the names it covers, whether it names the host and is signed by `certs/ca.crt`, expiry and days left, serial, SHA-256 fingerprint. Exits non-zero if any node has a problem or cannot be reached — see [Per-node certificates](#per-node-certificates) |
| `issue-certs [--nodes ...] [--ssh-user] [--ssh-key]` | Give each node a TLS certificate of its own over SSH: the node generates its key (it never leaves the node), the orchestrator signs the request with `certs/ca.key`, and the agent is restarted. A failure changes nothing. Also the way to renew (825 days) or to re-issue after replacing the CA — see [Replacing the CA](#replacing-the-ca) |
| `audit-log [--limit 20]` | List recent invocations of mutating commands (install, copy, bootstrap, teardown, playbooks, ...) from `.orphera-audit/audit.jsonl`: the command, its nodes, outcome and duration. Read-only commands are not recorded, and there is no per-operator identity — only that someone holding the token ran it from this host. The file rotates by size |
| `thread-info [--nodes ...] [--detail] [--reset-peak]` | Report each agent process's thread count: all OS threads, the Java threads and the most there have been since the agent started, CPUs, resident memory and heap. `--detail` adds the Java thread states and the threads grouped by name (`ioapp-compute`, `grpc-default-ex`, `GC Thread`, ...); `--reset-peak` starts the peak again after reading it, so the next call shows the highest count for one piece of work. An agent older than the orchestrator answers `unsupported` |
| `pipeline <step>... (--nodes ... \| --node-groups ...) [--parallel N] [--quiet]` | Run a chain of verbs on each node, the nodes in parallel. Each step is one quoted verb line, e.g. `orphera pipeline "install tree" "dist-upgrade" "reboot --wait" --node-groups mons`. Steps run in order per node and a failure stops that node only; output is prefixed `[node]`, logged to `.orphera-build-logs/<time>/<node>.log`, and followed by a summary table; exit 1 if any node failed. A step may be any node verb (`install`, `remove`, `autoremove`, `dist-upgrade`, `copy`, `write-file`, `network-apply`, `deploy-agent`, `run`, `fetch`, `facts`, `version`, `uptime`, `thread-info`, `reboot`, `shutdown`) but may not give its own `--nodes` |
| `run <command...> [--nodes] [--timeout 60] [--quiet]` | Run an arbitrary command on one or more agents, streaming stdout/stderr and reporting the real exit code. `--quiet` prints only the command's output (still prefixed `[node]`); a failure still prints its exit line and sets the exit code. Use `--` before the command to separate it from `run`'s own flags, e.g. `orphera run --nodes tst0 -- systemctl status nginx` |
| `playbook <file.yaml \| file.scala \| compiled-name> [--resume] [--config <path>]` | Run an ordered, multi-task playbook — from a YAML file, a run-time-compiled Scala script, or a compiled DSL playbook by registered name — see [Playbooks](#playbooks). `--resume` skips tasks already completed in a previous run — see [Restartability](#restartability). `--config` points a `.scala` script at a per-VM YAML config file (`ConfigYaml.fromEnv()`, see [Per-VM config files](#per-vm-config-files---config)); has no effect on a `.yaml`/compiled playbook, which has no code of its own to read it |
| `cluster-playbook <file.yaml \| file.scala> [<file2> ...] [--resume] [--config <path>]` | Run a staged, cross-node-coordinated playbook — see [Cross-node coordination](#cross-node-coordination-and-staged-playbooks). Same `--resume`/`--config` support as `playbook`, applied independently to each file. One or more files run in the order given, stopping at the first one that fails — a shorthand for a fixed sequence (e.g. tear down, then rebuild) without a wrapper script |
| `log-summary <playbook-name \| file.jsonl>` | Print a human-readable table (per-task status/duration, failures called out) from a run's `.orphera-logs` output. A playbook name resolves to that playbook's most recently modified log file — see [Observability](#observability) |
| `secrets init \| list \| generate <name> \| set <name> \| get <name> \| rm <name>` | Manage the encrypted secret store on the machine that runs `orphera` — see [Secrets](#secrets). Does not contact any node. `set` reads the value from one line of standard input; `get` prints the value, so use it only where that is wanted |

There is no `apply <manifest.yaml>` command — the standalone
declarative-file-manifest idea (independent, parallel file pushes, no
task ordering) was designed but superseded before being built;
[Playbooks](#playbooks) is the actual, implemented mechanism for
declarative, ordered multi-step execution, including file pushes as
one task type among several.

Every CLI command above (`install`, `remove`, `autoremove`, `dist-upgrade`, `copy`,
`write-file`, `network-apply`, `reboot`) currently has its **own direct dispatch
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

**Available task types:** `install`, `remove`, `autoremove`,
`dist_upgrade`, `copy`, `write_file`, `network_apply`, `reboot`,
`run_command`, `set_fact`, `debug`, `dump_facts` — most of the first
seven map directly onto the corresponding CLI command's underlying RPC
(`dist_upgrade` ↔ `dist-upgrade`), so anything the CLI can
do (except `bootstrap`/`teardown`/`deploy-agent`/`facts`/`version`,
which are intentionally excluded — see below), a playbook task can do
as one step in a sequence. The `reboot` task's fields mirror the CLI
command: `delay`, `wait`, `wait_timeout` in YAML.

- `dist_upgrade` (`update_cache:`, default `true`) runs
  `apt-get dist-upgrade` on the agent, preceded by `apt-get update`
  unless `update_cache: false`. Existing config files are kept
  (`--force-confdef`/`--force-confold`) rather than stalling on a
  conffile prompt. In Scala: `Task.DistUpgrade(updateCache = true)`.
  Follow it with `autoremove` to clear packages the upgrade orphaned.

- `write_file` (`content:`, `dest:`, `owner:`, `group:`, `mode:`)
  writes a literal string straight to a file on the agent — no source
  file has to exist on the orchestrator's own disk first, unlike
  `copy`. `content` is rendered through Mustache first if it contains
  `{{...}}`, same as `set_fact`/`debug`. Reuses the same underlying
  file-transfer RPC `copy` does; it's the task-level equivalent of the
  `cat > file <<EOF ... EOF` heredoc pattern most `run_command` scripts
  in `manifests/` reach for today — useful when the content is a
  short, already-in-hand string (a rendered config block, say) rather
  than something that belongs in its own template file on disk.

- `run_command` runs an arbitrary command on the agent (`command:` as
  a YAML list, `timeout:` in seconds), streaming stdout/stderr and
  reporting the real exit code — no shell interpretation (pipes,
  redirects) unless the command list itself is `["sh", "-c", "..."]`.
- `set_fact` (`key:`, `value:`) stores a value for the rest of that
  node's run. `value` is rendered through Mustache first if it
  contains `{{...}}`, so a later task can build on an earlier
  `set_fact` or on a real fact.
- `debug` (`message:`) prints a Mustache-rendered message — for
  inspecting one specific value.
- `dump_facts` (no fields — write it as `dump_facts: {}`) pretty-prints
  the entire var tree available to that node's templates: its own
  `facts.*`, and every targeted node's merged `nodes.<name>.*` entry.
  The most direct way to check why a `{{...}}` reference isn't
  resolving as expected.

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
`os_id`, `os_version`, `arch`, `hostname`, plus any key already set
via an earlier `set_fact` on that same node.

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
the agent passes `-o DPkg::Lock::Timeout=600`, so a task that hits the
lock (background unattended-upgrades, another concurrent Orphera task,
etc.) waits up to 10 minutes and retries automatically rather than
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
orphera playbook web-baseline
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

### Run-time-compiled Scala scripts

A third front-end: `orphera playbook somefile.scala` compiles a
**standalone** `.scala` file at command time and runs it, without
needing it registered anywhere or built into the `orchestrator` jar
first. This is handled by a separate `scripting` sbt module
(`scripting/src/main/scala/orphera/scripting/Main.scala`), which
depends on `orchestrator` and bundles the Scala 3 compiler; `orphera`
shells out to `scripting`'s own assembled jar, which compiles the
script against the already-built `orchestrator-assembly` jar. This
keeps `scala3-compiler` entirely out of `orchestrator`'s own
runtime/jar — `orchestrator` has no dependency on `scripting`, only
the reverse.

```scala
import orphera.orchestrator.*
import orphera.orchestrator.PlaybookDsl.*

object arithmetic_demo extends OrpheraPlaybook:

  private val basePort = 9000
  private val replicas = 3
  private val targetPort = basePort + replicas * 10

  val playbook: Playbook =
    PlaybookDsl
      .playbook("arithmetic-demo", "tst0")
      .task("show computed port")(Task.Debug(s"computed target_port = $targetPort"))
      .build
```

```bash
sbt orchestrator/assembly
sbt scripting/assembly
orphera playbook manifests/arithmetic_demo.scala
```

Two hard requirements, both real, both hit in practice:

- **The object name must exactly match the script's filename** —
  `arithmetic_demo.scala` must define `object arithmetic_demo`.
- **The script must live outside every sbt module's source tree, and
  outside the repo root itself.** A loose `.scala` file at the repo
  root gets silently picked up by sbt's own default build as part of
  its own source set — `sbt test`/`sbt compile` will then fail trying
  to compile it there, with no `orphera.orchestrator.*` on that
  classpath, and no connection to the actual `orphera playbook`
  command at all. `manifests/` is the established place for these
  scripts, alongside the `.yaml` playbooks already kept there.

Ordinary Scala arithmetic/computation works directly (`basePort +
replicas * 10` above is genuine, not templated) — but only using
values known when the script's `object` initializes (literals,
environment variables). It cannot incorporate a value gathered from a
live agent (a `Fact`, or another task's `set_fact` result), since
that data only exists later, inside `PlaybookRunner.run`, after the
script's `Playbook` value has already been built.

**Not yet implemented:**
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

## Cross-node coordination and staged playbooks

A flat `playbook` treats every target node as fully independent — no
node can see another's data, and there's no ordering between nodes.
`cluster-playbook <file.yaml>` is a second, staged execution model
for when that's not enough — e.g. mon hosts needing each other's IP
addresses to render a shared config file, or a dependent service that
must not start installing until an earlier tier is confirmed healthy.

```yaml
name: mini-cluster

stages:
  - name: primary
    nodes: [tst0]
    tasks:
      - name: write readiness sentinel
        copy:
          src: files/ready-sentinel.txt
          dest: /tmp/orphera-ready
          owner: root
          group: root
          mode: "0644"

  - name: secondaries
    nodes: [tst1, tst4, tst5]
    wait_for:
      on_node: tst0
      sentinel_path: /tmp/orphera-ready
      expected_sha256: "<sha256 of files/ready-sentinel.txt>"
      timeout: 60
    tasks:
      - name: install curl
        install:
          packages: [curl]
```

```bash
orphera cluster-playbook manifests/mini-cluster.yaml
```

`cluster-playbook` also accepts more than one file, run in the order
given:

```bash
orphera cluster-playbook manifests/etcd_teardown.scala manifests/etcd_cluster.scala
```

This runs the whole sequence in one invocation rather than one command
per file — useful for a fixed sequence you find yourself typing
repeatedly (tear down, then rebuild; or rebuild, then a follow-up
exercise). It stops at the first playbook that fails, the same way a
single playbook stops at its first failed stage; playbooks before the
failure are unaffected, and `--resume` (if given) applies independently
to each one via its own checkpoint file, not to the sequence as a whole.

**Stages run strictly in order.** Nodes *within* one stage run fully
in parallel with each other, same as a flat playbook — but the next
stage does not start until every node in the current stage has
finished its tasks successfully *and* the stage's optional `wait_for`
health check (if any) has passed. A failed task, or a health check
that times out, aborts every remaining stage; other already-completed
stages are unaffected.

**`wait_for` health checks** come in two forms, both polling one
designated node (`on_node:`) at `poll_interval` seconds until
`timeout`:
- **Sentinel** (`sentinel_path:`, `expected_sha256:`) — waits for a
  remote file's content hash to match, reusing the same idempotent
  hash-check machinery `copy` uses. Simple, cheap, but only ever a
  proxy for real readiness (something else has to actually write that
  file with that exact content).
- **Command** (`command:` as a YAML list) — waits for an arbitrary
  remote command to exit `0`, e.g. `["systemctl", "is-active",
  "--quiet", "nginx"]`. This is a real service-state check, not a
  proxy — use it whenever the target has a meaningful way to report
  its own readiness.
- **Ssh** (`host:`, `ssh_user:`, optional `ssh_key_path:`/`remote_command:`,
  default `true`) — like Command, but polls over plain SSH instead of
  Orphera's own agent RPC, and against `host` directly rather than an
  `on_node:` looked up in inventory.yaml. The one case for this:
  confirming a brand-new VM (e.g. one `virt-install`ed by a playbook —
  see `manifests/kvm_zfs_vm_provision.scala`) is alive, when by definition
  it can't be running the Orphera agent yet and usually isn't in
  inventory.yaml yet either. Deliberately does not install or start
  the agent itself; that stays a separate, explicit `orphera bootstrap`
  step afterward. Deliberately does **not** pin or persist the remote
  host key (`UserKnownHostsFile=/dev/null`) — this check exists only
  for a VM expected to be destroyed and recreated at the same address
  across runs, and cloud-init regenerates a fresh host key on every
  boot; pinning it would make every run after the first fail with
  "REMOTE HOST IDENTIFICATION HAS CHANGED" instead of actually probing
  liveness. A failed poll's real ssh output (host-key/auth/connection
  errors, not just "not ready yet") is printed, not swallowed.

Only single-node `Sentinel`/`Command`/`Ssh` checks probe one designated node.
`HealthCheck.Quorum` (`nodes`, `command`, `requiredCount`) is a genuine
quorum-of-N gate — polls every listed node each round and proceeds
once the healthy count reaches the threshold, without waiting on
stragglers. Used in practice to bring up a real 3-node Ceph mon
cluster — see [Example: a real Ceph cluster deployment](#example-a-real-ceph-cluster-deployment).

## Cross-node data and vars

Every playbook task (in both the flat and staged/cluster runners) has
access to a `nodes.<node-name>.*` namespace in its templates and
`set_fact` values, covering **every node targeted by that run** — not
just the node the task happens to be executing on. This is what lets
one node's config template embed another node's address.

For a given node, `nodes.<that-node>.*` is the merge of four sources,
in this precedence order (each layer overrides the one before it on a
key collision):

1. **Group vars** — from every `Group` (declared in `inventory.yaml`)
   the node is a member of, merged in group-declaration order.
2. **The node's own inventory vars** — its `vars:` block in
   `inventory.yaml`. Always wins over a group default of the same key.
3. **Gathered facts** — `hostname`, `os_id`, `os_version`,
   `architecture`, and network-interface IPs (`ip_<interface-name>`
   for each interface by name, plus a best-effort `ip_secondary` —
   whichever non-loopback interface enumerates second, which is
   convenient for a quick look but not guaranteed stable; prefer
   `ip_<name>` for anything you're actually building config around).
4. **`set_fact` values** — set by a task that already ran on that
   node in this run. Always wins over a same-named fact or inventory
   var.

A node's own facts are additionally available unqualified, as
`facts.hostname` etc. (not `nodes.<own-name>.hostname`) — both forms
work for a node referencing itself; only `nodes.<name>.*` works for
referencing a *different* node.

**Cross-node `set_fact` reads are only reliable across a stage
boundary.** Within one stage, nodes run in parallel with no ordering
guarantee — if node B's template reads `nodes.A.some_key` while A's
`set_fact` task hasn't run yet, the key is simply absent (the
containing Mustache section renders as skipped, same graceful
degradation as a node whose facts failed to gather). Put the setter
in an earlier *stage* and the reader in a later one to get a real
ordering guarantee.

## Network config safety

`network-apply` exists because a bad `.network` file can permanently
sever the connection you're managing a host over. The design:

1. The agent keeps a *known-good* snapshot of the network config
   (`/etc/systemd/network/*.{network,netdev,link}`, or on NetworkManager
   hosts `/etc/NetworkManager/system-connections/*.nmconnection`). It is
   taken the first time the agent starts, before anything is pushed, and
   refreshed after every confirmed apply. (Files are pushed with `copy` /
   `write-file` *before* `network-apply`, so a backup taken at apply time
   would already contain the change.)
2. Agent reloads (`networkctl reload`, or `nmcli connection reload` + `up`)
   and arms a rollback timer, entirely locally — it does not depend on the
   orchestrator being reachable to trigger the rollback.
3. Orchestrator waits briefly, probes connectivity itself, and only
   sends an explicit confirm if the probe succeeds; the agent then makes
   the applied config the new known-good snapshot.
4. If confirm never arrives (because the reload broke connectivity),
   the agent's own timer restores the known-good snapshot and reloads again.

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

## Restartability

`playbook`/`cluster-playbook` both accept `--resume`, which skips any
task already recorded as completed by a previous run of that same
playbook, instead of replaying it. This exists because many tasks are
**not** safely re-runnable once partially applied — `ceph orch daemon
add osd host:/dev/sdb`, for instance, fails on a second attempt because
the device already carries an OSD's LVM signature by then. Without
`--resume`, a staged rollout that fails 4 hosts into a 5-host stage has
no safe way to continue except a full teardown and restart.

State is a local JSON file on the control host
(`.orphera-state/<kind>-<playbook-name>.json`, gitignored), tracking
completion per `(node, task name)` pair, written atomically after every
task. An ordinary run (no `--resume`) always starts fresh and
overwrites this file — existing behavior is unchanged unless you pass
the flag. `--resume` loads the previous run's completions and skips
anything already marked done, printing `skipped (already completed in
a previous run — resuming)` in its place.

```bash
orphera cluster-playbook manifests/cephadm_add_osds.scala   # fails partway through
orphera cluster-playbook manifests/cephadm_add_osds.scala --resume   # picks up where it left off
```

**Known limitations:**
- A task is identified only by `(node name, task name)` — renaming,
  reordering, or duplicating a task name between runs will confuse
  resumption (the same fragility Ansible's `--start-at-task` has).
- State lives on the control host, not the target agent — resume only
  works from the same host and working directory that produced the
  checkpoint file.

**Verified, not just compiled:** `manifests/test_restartability.sh`
(flat `playbook`, `--resume` as a CLI flag) and
`manifests/test_restartability_cluster.sh` (staged `cluster-playbook`,
run as a `.scala` script — the actual path `cephadm_add_osds.scala`
uses — exercising `ORPHERA_RESUME` via the real subprocess mechanism
rather than assuming it from the flat-playbook test alone) both force a
genuine partial failure against real hosts, then assert the checkpoint
file, a second non-`--resume` run's unchanged behavior, and a
`--resume` run's actual skip-and-continue — see either script's header
comment for the exact mechanism.

## Per-VM config files (`--config`)

`playbook`/`cluster-playbook` also accept `--config <path>`, for a
`.scala` script that needs real per-VM sizing/network values instead of
hardcoded Scala vals — the motivating case is
`manifests/kvm_zfs_vm_provision.scala`, which provisions a brand-new KVM VM
and previously had to hardcode memory/cpu/disk/nic values one Scala val
at a time per VM.

`--config` doesn't change what gets compiled or run — same as
`--resume`, it's handed to the running script as an environment
variable (`ORPHERA_CONFIG`), since `OrpheraPlaybook`/
`OrpheraClusterPlaybook` extend `IOApp.Simple`, whose `run: IO[Unit]`
has no access to process args at all. A script reads it with:

```scala
ConfigYaml.fromEnv() match
  case Some(Right(params)) => // --config was given and parsed; params: VmParams
  case Some(Left(err))     => // --config was given but failed to load/parse
  case None                 => // --config wasn't given at all
```

The file itself is **not** an Orphera-invented format — it's the same
shape as the kayobe/kttb-virt-ansible project's own per-VM config files
(`playbooks/config/<hostname>-config.yml` there), a top-level `params:`
block with `hypervisor`, `hostname`, `domainname`, `os`, `disk`,
`memory`, `cpu`, `dns1`/`dns2`, `host_type`, a nested `nic0:` block
(`nic`, `ipaddress`, `gateway`, `bridge`, `netmask`, `cidr`, `table`,
`priority`, `mtu`), and `disks`/`pdisks`/`nics`/`pre_basic_packages`/
`basic_packages` lists — parsed into `VmParams`/`Nic0` case classes by
`ConfigYaml.scala`. Has no effect on a `.yaml` or compiled playbook,
which has no code of its own to read an env var; `orphera` prints a
note rather than silently ignoring the flag in that case.

```bash
orphera cluster-playbook manifests/kvm_zfs_vm_provision.scala --config config/testvm0-config.yml
```

`kvm_zfs_vm_provision.scala` is wired to `ConfigYaml.fromEnv()` end to
end — every sizing/network val (`hypervisorNode`, `vmName`, `baseImage`,
`diskSizeGB`, `memoryMB`, `vcpus`, `nicName`, `nicBridge`, `cpuMode`,
`domainname`, `dns1`/`dns2`, `ipAddress`, `gateway`, `netmask`) reads
from the parsed config when `--config` is given, falling back to
testvm0's own real hardcoded values (from `config/testvm0-config.yaml`)
when it isn't. `pre_basic_packages`/`basic_packages` are not used by provisioning (a VM
with no agent yet can't be told to install anything); `gen_packages.scala`
installs them once the VM has been bootstrapped.

Each `disks:` entry (a size in GB, as a bare number, a string, or a one-key
`- disk: 128` map) becomes a blank `<vm>-dataN.qcow2` attached to the VM; `pdisks`
is ignored with a warning. Every disk gets a fixed QEMU serial (`<vm>-root`,
`<vm>-swap`, `<vm>-dataN`), so inside the guest it appears at the stable path
`/dev/disk/by-id/scsi-0QEMU_QEMU_HARDDISK_<serial>`, which provisioning prints
for use in `inventory.yaml` (`osd_disks`/`zap_disks`). The VM is also marked
**autostart** on the hypervisor. After provisioning, run
`orphera bootstrap --forget-host-key` to clear a stale `known_hosts` entry for a
rebuilt host.

There are two provisioning scripts, with identical behaviour apart from storage:
`manifests/kvm_zfs_vm_provision.scala` keeps each VM in its own ZFS filesystem
(`tank/kvm/<hypervisor>/<vm>`, mounted under `/exports/kvm`), and
`manifests/kvm_native_vm_provision.scala` uses a plain directory
(`/var/lib/libvirt/images/orphera/<vm>`, or under `$ORPHERA_VM_DIR_ROOT`) on any
hypervisor, with no ZFS needed. Both take the same `--config` file.

`manifests/kvm_vm_start.scala` takes the same file and starts that VM on its
`hypervisor` (paused is resumed, an undefined domain fails; a domain libvirt
calls running but that is unreachable for ~24 s is hard-reset, since a halted guest
still reports `running`) and waits for SSH; `--config` is mandatory there, with no fallbacks:

```bash
orphera cluster-playbook manifests/kvm_vm_start.scala --config config/tst0.yaml
```

`manifests/gen_packages.scala` installs the config's `pre_basic_packages`, then
`basic_packages`, on that VM once it has the agent and an `inventory.yaml`
entry (either list may be absent or empty):

```bash
orphera cluster-playbook manifests/gen_packages.scala --config config/tst0.yaml
```

`manifests/gen_set_dns.scala` writes the resolver config on the node named by
the config's `nodes:` list, else its `hostname:` (with no config at all it falls
back to tst0-tst5):

```bash
orphera cluster-playbook manifests/gen_set_dns.scala --config config/tst0.yaml
```

### One chain, many configs, in parallel

`cluster-playbook` already runs several playbooks in order. Give `--config` several
files and it runs that whole chain once per config, the configs in parallel:

```bash
orphera cluster-playbook manifests/kvm_zfs_vm_provision.scala manifests/gen_set_dns.scala \
  manifests/gen_packages.scala --config config/tst*.yaml --parallel 8 --quiet
```

`--parallel N` limits how many configs run at once (default: 4, or fewer if there are
fewer configs); `--quiet` shows only stage headers, failures and the summary on the
terminal. Output is prefixed with the config's name (`[tst3]`) and saved in
`.orphera-build-logs/<time>/<config>.log`. A failure stops that config's chain only; the
run ends with a summary table and exits non-zero if any config failed. Only `.scala`
playbooks (a YAML playbook cannot read the config), and config file names must be
distinct. Parallel runs get their own run-log and checkpoint files (the config's name is
added to them), so `--resume` resumes each config where it stopped. `bootstrap` is not a playbook, so the chain has a
built-in step for it: `@bootstrap` installs the agent on the config's `hostname` node over SSH
(it must be in `inventory.yaml`), using the `--ssh-user` (default root), `--ssh-key`, `--agent`
and `--forget-host-key` options that `orphera bootstrap` has. `gen_dist_upgrade.scala` covers
`dist-upgrade`, so a whole build is one command:

```bash
orphera cluster-playbook manifests/kvm_zfs_vm_provision.scala @bootstrap \
  manifests/gen_set_dns.scala manifests/gen_dist_upgrade.scala manifests/gen_packages.scala \
  --ssh-user localadmin --forget-host-key --config config/tst*.yaml --parallel 8 --quiet
```

The shell script below does the same with more locking and a different output layout.

### Building a set of new VMs end to end

`manifests/mk-new-and-empty.sh` chains the steps above for tst0-tst7 (or the nodes
named on the command line): provision, `bootstrap --forget-host-key`,
`gen_set_dns.scala`, `dist-upgrade`, `gen_packages.scala`. A failed step stops
that node and the script moves on, listing failures and exiting non-zero.
`manifests/par-mk-new-and-empty.sh` does the same with the nodes built in
parallel (`-j N`, default 4; `-q` for banners only; `-a <deb>`; `-H` to
provision one VM at a time per hypervisor): output is prefixed per node and
saved under `.orphera-build-logs/<time>/<node>.log`. Only the removal of a node's
old SSH host key (`ssh-keygen -R`, which rewrites `known_hosts`) is serialized;
everything else, including the rest of `bootstrap`, runs in parallel. Ctrl-C
stops every node. Don't use `--resume` with it —
checkpoint state is per playbook name. Both pick the newest
`orphera-agent_*_amd64.deb` in the current directory unless given `-a`.

Both scripts choose the provisioning playbook per hypervisor: `kvm_zfs_vm_provision.scala` when the
dataset `tank/kvm/<hypervisor>` exists there, otherwise `kvm_native_vm_provision.scala`
(`PROVISION=zfs|native` overrides it for every node; a hypervisor that does not answer stops that node
rather than guessing).

`manifests/rebuild_openstack_from_scratch.sh` is the whole OpenStack stack from nothing: it destroys
and re-creates the 13 OpenStack VMs (tst0-tst6, tst10, tst13, tst14, tst17-tst19) with
`par-mk-new-and-empty.sh`, then runs `build_openstack_stack.sh`. `--dry-run` shows the plan,
`--yes` skips the "type rebuild" confirmation, `--tests` runs the per-service test scripts afterwards,
then the closing checks again (CLI, smoke objects, a CirrOS boot), because `test_keystone.sh` tears Keystone down, and
 `--wordpress` rebuilds WordPress on tst7, whose database lives on Galera. It leaves the caches
(tst9, tst11, tst12), tst7, tst8 and the hypervisors alone.

The one value this file does **not** read from `--config` is the SSH
key authorized on the new VM — there's no field for that in the
kttb-virt-ansible config shape. Instead it reads a real public key off
disk at provision time (`ORPHERA_SSH_PUBKEY_PATH` env var if set, else
`~/.ssh/id_ed25519.pub`, else `~/.ssh/id_rsa.pub`) and bakes that into
cloud-init's `authorized_keys`, rather than a hardcoded literal — so
whichever identity actually connects (interactively, or via
`HealthCheck.Ssh` above) is the one the new VM trusts.

## Observability

`playbook`/`cluster-playbook` both write one JSON Lines file per run —
`.orphera-logs/<kind>-<playbook-name>-<yyyyMMdd-HHmmss>.jsonl` —
alongside the existing human-readable console output, not instead of
it. Task/stage lifecycle only: `run_start`/`run_end`,
`stage_start`/`stage_end` (cluster-playbook), `task_start`, `task_end`
(`success`, `duration_ms`, `error` on failure), `task_skipped`
(`reason`: `condition_not_met` or `resume_checkpoint`). Not every
streamed command-output line — the console already has that.

```bash
$ orphera cluster-playbook manifests/cephadm_add_osds.scala
...
$ tail -3 .orphera-logs/cluster-cephadm-add-osds-20260928-145112.jsonl
{"event":"task_end","stage":"apply-osd-spec","node":"tst1","task":"add osd on tst1:/dev/sdb","success":false,"duration_ms":3182,"error":"Remote command reported failure (see output above)","ts":"2026-09-28T14:51:15.482Z"}
{"event":"stage_end","stage":"apply-osd-spec","success":false,"duration_ms":9107,"ts":"2026-09-28T14:51:15.483Z"}
{"event":"run_end","playbook":"cephadm-add-osds","success":false,"duration_ms":9110,"ts":"2026-09-28T14:51:15.483Z"}
```

Always on, no flag — it only ever appends a separate file and never
touches stdout, so anything that already greps console output (or the
existing test scripts) is unaffected.

**`orphera log-summary <playbook-name | file.jsonl>`** renders a run's
log as a table instead of leaving you to read the raw JSON Lines —
one row per task (stage, if the log has one, node, task, status,
duration), failures with their error message inline, and a run-level
header/totals line. A playbook name resolves to that playbook's most
recently modified log file under `.orphera-logs/` (a direct `.jsonl`
path also works); if a file happens to hold more than one run's events
(two runs launched inside the same second, since `RunLog` filenames
only have one-second resolution), only the most recent run's events
are shown.

```bash
$ orphera log-summary cephadm-add-osds
Run: cephadm-add-osds  (3 targets)
Log file: .orphera-logs/cluster-cephadm-add-osds-20260928-145112.jsonl
Status: FAILED   Duration: 9110ms

Stage            Node   Task                                 Status  Duration
---------------  -----  -----------------------------------  ------  --------
apply-osd-spec   tst0   add osd on tst0:/dev/disk/by-id/...   OK      3421ms
apply-osd-spec   tst1   add osd on tst1:/dev/sdb              FAILED  3182ms
    error: Remote command reported failure (see output above)

1 succeeded, 1 failed, 0 skipped
```

No new dependency: it parses `.jsonl` with a small hand-rolled reader
tailored to exactly what `RunLog.toJson` emits (same reasoning as
`RunLog`/`Checkpoint` — no `circe-parser` available in this module),
not a general-purpose JSON library.

**Verified, not just compiled:** `manifests/test_observability.sh`
parses the actual `.orphera-logs/*.jsonl` output of a real run against
tst0 (reusing `restartability_test.yaml`'s existing 3-run
no-`--resume`/no-`--resume`/`--resume` cycle) and asserts the structured
events themselves — correct `run_start`/`run_end`, `task_start`/
`task_end` with the right `success`/`duration_ms`/`error`, and
`task_skipped` with `reason=resume_checkpoint` on the resumed run — not
just that a file gets written. `manifests/observability_condition_test.scala`
is a second, small fixture with a `when:`-gated task whose condition can
never be true, added because the restartability fixture never produces
a `reason=condition_not_met` skip; it confirms that reason is logged
correctly and that a condition skip (unlike a task failure) lets the
rest of the run continue and still succeed.

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
outcome checkable, and `deploy-agent` now polls it automatically:
after pushing and installing the `.deb` on every target node, it polls
each node's `version` RPC (every 5s, 60s default timeout) until it
reports the version baked into that same `.deb` — read once via
`dpkg-deb -f <path> Package Version`, so the expected version can
never drift from what's actually being pushed. A node's install is
only reported confirmed once its agent is reachable again and running
that exact version; the command's own exit code is non-zero if any
node isn't confirmed within the timeout, and it prints which ones.

## Authentication and security notes

- **Transport:** TLS, agent cert verified against a private CA
  (`certs/ca.crt`) on the orchestrator side.
- **Application auth:** a shared bearer token, checked via gRPC
  interceptor on every call. Every agent and every orchestrator
  invocation must hold the same token. The agent reads it from
  `ORPHERA_TOKEN` (in `/etc/orphera-agent/agent.env`, which the package
  ships with the line commented out). **An agent will not start without
  a token of its own, or with the built-in development token** (which is
  in the source code and protects nothing): it logs why and exits, and
  systemd retries every five seconds until the token is set. The
  orchestrator takes its token from `ORPHERA_TOKEN`, else from the
  secret store entry `orphera/agent-token` (`AgentToken.scala`); with
  neither it still falls back to the built-in token, with a warning on
  stderr, so that it can reach an agent that has not been fixed yet, but
  `bootstrap` and `deploy-agent` refuse to run in that state, because
  they would leave a node that cannot start. Set a real one:

  ```bash
  orphera secrets generate orphera/agent-token     # once
  utils/set-agent-token.sh tst0 tst1 ...           # every node, over SSH
  ```

  `utils/set-agent-token.sh` sends the token on SSH's standard input
  (never on a command line), writes it to `agent.env` (root only, mode
  600, other lines kept) and restarts the agent. The package keeps a
  changed `agent.env` when it is upgraded. `bootstrap` writes the same
  token to a node it installs; the script is for nodes that are already
  installed.
- **What a failed call looks like:** `version`, `uptime` and `thread-info`
  print `unauthenticated` when the node answered and rejected this
  orchestrator's token (fix it with `utils/set-agent-token.sh`),
  `unsupported` (thread-info) when the agent is too old to know the call, and
  `unreachable: <status>` when nothing usable came back (no route, no
  listener, a certificate the orchestrator does not trust). Other
  commands still show the raw gRPC error.
- **Agent logs:** the agent no longer writes the arguments of the
  commands it runs to its journal. A command's arguments are still
  visible in the process list on the node while it runs, so a command
  that embeds a password exposes it for that time; `Resource.checkTask`
  avoids this for file checks by sending a hash.
- **Secrets in playbooks:** see [Secrets](#secrets).
- **Not implemented:** mutual TLS (agent doesn't verify orchestrator
  identity beyond the shared token), per-destination-path
  restrictions on `copy` (an authenticated orchestrator can currently
  write to any path the agent's root user can reach) and per-node
  tokens.

## Secrets

Passwords and keys that a playbook needs are kept in one encrypted file on
the machine that runs `orphera`, not in the manifests. The file is
encrypted with [age](https://age-encryption.org) (`sudo apt install age`
on Ubuntu); it holds a JSON object of name to value, is written through a
temporary file with mode 600, and is replaced atomically.

```bash
orphera secrets init                      # once: makes the age identity
orphera secrets list                      # names only
orphera secrets generate placement/db     # 24 random letters and digits
printf '%s\n' 'value' | orphera secrets set placement/db
orphera secrets get placement/db          # prints the value
orphera secrets rm placement/db
```

| Where | Default | Override |
|---|---|---|
| encrypted store | `~/.config/orphera/secrets.age` | `ORPHERA_SECRETS` |
| age identity | `~/.config/orphera/age.key` | `ORPHERA_AGE_KEY` |

**Back up both files.** Without the identity the store cannot be read, and
every generated password is lost with it. Names are letters, digits, `_`,
`.`, `/` and `-`, starting with a letter or digit; by convention
`<service>/<what>`.

In a playbook:

```scala
private val dbPassword = Secrets.secret("placement/db")        // a Secret
...
s"... IDENTIFIED BY '${dbPassword.reveal}' ..."
```

- `Secrets.secret(name)` returns the stored value, **making a random one
  and storing it the first time the name is asked for**. A new deployment
  therefore needs no setup. An existing deployment does: seed the store
  with the passwords already in use *before* applying a playbook that
  reads from it, or the playbook will generate new values that do not
  match the database users and service accounts that exist. For the test
  lab, put the values in use into the store with `orphera secrets set`.
- A `Secret` does not print: `toString`, string interpolation and any log
  line give `<redacted>`, and the text is available only through
  `.reveal`, so each place a secret goes into a command or a file is
  visible in the source (`grep -n reveal manifests/`). Forgetting
  `.reveal` writes `<redacted>` where the password should be and the
  service refuses it, instead of leaking it. `Secrets.get(name)` returns a
  plain `String` for code that needs one.
- Every value read this way is also registered for masking: every event
  that arrives from a node passes through `Secrets.mask`, so a value that
  a command echoes shows as `<redacted>` on the console and in the
  `.orphera-logs` run log. Values shorter than six characters are not
  masked.
- The bundled playbooks use these names: `keystone/admin`, `keystone/db`,
  `placement/db`, `placement/service`, `glance/db`, `glance/service`,
  `glance/ceph-key`, `cinder/db`, `cinder/service`, `cinder/rabbit`,
  `cinder/ceph-key`, `nova/db`, `nova/service`, `nova/rabbit`,
  `neutron/db`, `neutron/service`, `neutron/rabbit`, `horizon/secret-key`,
  `galera/sst`, `galera/clustercheck`, `wordpress/db`,
  `observability/mysqld-exporter`, and `orphera/agent-token` (see
  [Authentication](#authentication-and-security-notes)).

What this does not do: masking hides a value from the orchestrator's own
output only. A command that contains a password still has it in its
arguments on the node while it runs. The store is a single file on one
machine, there is no rotation command, and the Ceph and database
passwords are not changed on the nodes when a stored value changes.

## Typed configuration files and check mode

OpenStack services read INI files with hundreds of options, and a
misspelt one is silently ignored. Instead of a heredoc, a playbook can
describe such a file as a `ConfFile`, checked against the list of options
the image itself declares:

- `catalogues/<project>-2025.1-ubuntu-noble.json` is the output of
  `oslo-config-generator --format json` run inside the Kolla image
  (`manifests/dump_oslo_options.sh <image> [tag] [outdir]`, then
  `manifests/slim_catalogue.py in out` to drop the help text). Catalogues
  exist for placement-api, keystone, glance, cinder, nova and neutron.
  `ORPHERA_CATALOGUES` names another directory.
- `Catalogues.load("glance", Set("ceph"))` loads one; `ConfFile.text(path,
  catalogue)(("section", "key", "value"), ...)` builds a file from
  settings and **throws when the manifest is built**, before any task
  runs, listing every error: an unknown section or option (with
  suggestions), an old name that has been renamed, or a value of the
  wrong type. Option names with `-` and `_` are the same, as in oslo.
- Some sections take options the image does not list, such as the
  authentication-plugin options in `[keystone_authtoken]`. These are
  *open sections* (`keystone_authtoken` always; `ceph` for Glance and
  Cinder; `service_user` for Nova). They accept other names, but refuse
  one that is within two edits of a known option, so a typo is still
  caught.
- Typed so far: Placement, Keystone, Glance, Cinder and Nova (control
  plane) and Neutron's four files. Nova compute's files are not, because
  they take the host's name and address at run time.
- Options marked secret in the catalogue, and keys named like `password`,
  `secret` or `token`, are treated as secret values: they are never shown
  in a drift diff.

**Check mode.** The same description gives a task that writes the file and
a task that checks it (the `Resource` type class). For the Placement
playbook:

```bash
ORPHERA_CHECK=1 orphera cluster-playbook manifests/placement_single_node.scala
```

changes nothing. Each checked file is compared by SHA-256 on the node;
`in sync` exits 0, a difference prints a unified diff and exits 1, a
missing file exits 2. Secret values are replaced by `<redacted>` on both
sides, and the output says when only a secret value differs. Only the
Placement unit and `placement.conf` are checked so far.

## Provisioning a new host

```bash
make certs                                     # once: the CA
orphera secrets init                           # once: the secret store
orphera secrets generate orphera/agent-token   # once: the token every agent accepts
make release                                   # builds the .deb, and the .rpm when rpmbuild is installed

orphera bootstrap --nodes newhost --ssh-user localadmin --ssh-key ~/.ssh/id_ed25519
```

Besides installing the package, `bootstrap` gives the node a certificate of
its own (see [Per-node certificates](#per-node-certificates)) and this
orchestrator's token (see [Authentication and security
notes](#authentication-and-security-notes)). Without a certificate or a
token the agent does not start, so `bootstrap` stops before touching the
node when `certs/ca.key` is missing or no token is configured.

`bootstrap` requires non-interactive SSH key auth (`BatchMode=yes`) —
an unlocked key or agent-forwarded key, no passphrase prompts, and if
`--ssh-user` isn't root, passwordless `sudo` for `dpkg` (`dnf` on Red Hat-family hosts). Once bootstrap
succeeds and the systemd service is running, all future updates go
through `deploy-agent` (gRPC), not `bootstrap` again.

`cephadm_teardown.scala` also removes a leftover `download.ceph.com` apt source
(left behind by `cephadm add-repo`; on noble its 404 makes every later
`apt-get update` fail on that host). If `apt-get update` does fail during a
`Task.Install`, the agent now ends the task with a FAILED result and the apt
error rather than leaving the run waiting.

Before pushing the `.deb`, `bootstrap` runs `apt-get update` and
`apt-get install -y default-jre` on each node over SSH. The agent needs
a Java runtime (the package `Depends:` on `default-jre-headless >= 17`),
and `dpkg -i` doesn't resolve dependencies — on a fresh node it would
leave the agent unconfigured. If either step fails, that node's
bootstrap stops there. Needs the same passwordless `sudo` as the
`dpkg` step, and the node must be able to reach its apt mirrors.

On a Red Hat-family host `bootstrap` copies the `.rpm`, checks its package name
with `rpm -qp`, and runs `sudo dnf -y install` on it; dnf pulls in the Java
runtime itself (`java-headless`), so there is no separate Java step. Which
branch runs is decided by asking the host over SSH whether it has `apt-get` or
`dnf`.

`--forget-host-key` runs `ssh-keygen -R <host>` against your own
`known_hosts` before connecting. Use it when the node was just rebuilt (cloud-init
regenerates host keys on every boot, so a reused IP/hostname otherwise fails
with "REMOTE HOST IDENTIFICATION HAS CHANGED"). It is opt-in: without it a
changed host key still stops the bootstrap. The new key is then trusted on first
connect (`StrictHostKeyChecking=accept-new`).

## Decommissioning a host

```bash
sbt "orchestrator/run teardown --nodes oldhost --yes --purge"
```

After teardown, the host is no longer reachable via gRPC — re-run
`bootstrap` if it needs to be managed again (it issues a new certificate and
sets the token again). `Inventory.all` is not
auto-updated; remove the entry manually.

## Installing the command line

`make cli-package` builds a package of the `orphera` command, separate from the agent package. It needs the same tools as
`make release` (`sbt`, `dpkg-deb`; `rpmbuild` for the `.rpm`).

| Where | What |
|---|---|
| `/opt/orphera` | the program, read-only: `bin/`, `lib/` (both jars), `manifests/`, `utils/`, `catalogues/`, `schemas/`, `files/`, `docs/`, `examples/` |
| `/usr/bin/orphera` | link to `/opt/orphera/bin/orphera`; bash completion is in `/etc/bash_completion.d/orphera` |
| `/etc/orphera` | your site: `inventory.yaml`, `config/` (per-VM configs) and `certs/` (`ca.crt`, `ca.key`). The package creates the directories empty and never overwrites what is in them |
| `~/.local/state/orphera` | run state per user (`.orphera-state`, `.orphera-logs`, `.orphera-audit`, `.orphera-build-logs`); `$XDG_STATE_HOME` moves it |

```sh
sudo apt install ./orphera_<version>_all.deb         # or: sudo dnf install ./orphera-<version>-1.noarch.rpm
sudo cp /opt/orphera/examples/inventory.yaml /etc/orphera/      # starting points: edit both
sudo cp -r /opt/orphera/examples/config/. /etc/orphera/config/
sudoedit /etc/orphera/inventory.yaml
orphera version
```

The packaged launcher sets `ORPHERA_INVENTORY`, `ORPHERA_CONFIG_DIR`, `ORPHERA_CERTS_DIR`, `ORPHERA_MANIFESTS`, `ORPHERA_CATALOGUES`,
`ORPHERA_STATE_DIR` and the two jar variables to those places only when they are not already set, so the environment, `-I`, `-c` and
`-m` all still win. Set `ORPHERA_SITE_DIR` to move the three site paths together. The shell scripts in `/opt/orphera/manifests`
read the same defaults, and `orphera` is on their `PATH`.

The tarball unpacks as `orphera-<version>/`. Without root, unpack it anywhere, set `ORPHERA_SITE_DIR` to a directory of your own, and
run `<dir>/bin/orphera`.

A file a playbook names with a relative path (`src = "files/motd"`) is read from the current directory when it is there, as before,
and otherwise from `$ORPHERA_ROOT` (`/opt/orphera` in the package), so the packaged playbooks work from any directory.

Known limit: `rebuild_openstack_from_scratch.sh` needs the agent `.deb` named with `-a` (or in the directory you run it from), as the
command-line package does not contain it.

## Makefile targets

| Target | Does |
|---|---|
| `make` / `make all` | clean → test → assembly → deb |
| `make clean` | `sbt clean`, removes `pkg/` and any built `.deb` / `.rpm` |
| `make test` | `sbt test`, then the check programs `ContainerCheck`, `ConfCheck`, `SecretsCheck`, `ThreadInfoCheck`, `InventoryCheck`, `LocationsCheck`, `InstallationCheck` and `DbDocumentsCheck` (set `CHECK_VERBOSE=1` to print every passing check) |
| `make assembly` | `sbt agent/assembly` |
| `make deb` | stages package contents, runs `dpkg-deb --build` |
| `make rpm` | stages the same contents, runs `rpmbuild` to produce `orphera-agent-<version>-1.noarch.rpm` for Rocky/Red Hat hosts. Needs `rpmbuild` |
| `make verify` | builds, then prints `.deb` contents/metadata |
| `make certs` | generates the CA (`certs/ca.{crt,key}`) that signs each node's certificate. Refuses to run if `certs/ca.crt` already exists |
| `orphera issue-certs --nodes ...` | issues each node its own certificate over SSH (see *Per-node certificates*) |
| `make certs-clean` | deletes the CA in `certs/` — you must type `delete` to confirm; every node then needs a certificate from a new CA (`make certs`, `orphera issue-certs`) |
| `make orpheracli` | builds the orchestrator and the scripting jar and installs them as `/usr/local/lib/orphera-orchestrator.jar` and `/usr/local/lib/orphera-scripting.jar`, with the `orphera` launcher in `/usr/local/bin` and the bash completion in `/etc/bash_completion.d/orphera` (effective in new shells) |
| `make release` | bumps the patch version in `VERSION`, then runs the full clean/test/assembly/deb chain (plus the `.rpm` when `rpmbuild` is installed) and the command-line package (`make cli-package`) at the new version, and installs the new orchestrator on this machine as `make orpheracli` does. It does not commit `VERSION` or tag; see [Versions and tags](#versions-and-tags) |
| `make fmt` | `scalafmt` over the sources, then over `manifests/` (`make fmt-manifests`) |
| `make lint` | the `scalafix` rules in `.scalafix.conf` in check mode; CI runs the same check |
| `make fix` | applies the `scalafix` rules, then runs `make fmt` |
| `make fmt-manifests` | `scalafmt` over the files in `manifests/` only |
| `make scripting` | `sbt scripting/assembly`: the jar that compiles and runs `.scala` playbooks at run time (`make orpheracli` builds and installs it too) |
| `make cli-package` | builds the `orphera` command-line package (see [Installing the command line](#installing-the-command-line)): `.deb` and `.tar.gz`, plus `.rpm` when `rpmbuild` is installed. Individual targets: `cli-stage`, `cli-deb`, `cli-rpm`, `cli-tar` |
| `make check-package` | stages the command-line package with stand-in jars and checks its layout and launcher; needs no build |
| `make lines` | counts the lines of Scala source |
| `make reload` | `sbt reload` |
| `make check-versions`, `make check-uptimes` | `orphera version` / `orphera uptime` over the whole inventory, through `sbt` |
| `make deploy-agents` | `deploy-agent` of `orphera-agent_$(VERSION)_amd64.deb` to the whole inventory |
| `make reboot-all` | **reboots every node in the inventory**, hypervisors included; refuses to run unless `ORPHERA_ALLOW_DESTRUCTIVE=1` is set |

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

**What runs today.** `make test` runs `sbt test` and four check programs
that need no cluster: `ContainerCheck` (49 checks: typed containers and
`Resource`), `ConfCheck` (49: typed configuration files and catalogues),
`SecretsCheck` (44: the secret store, `Secret`, masking, the agent
token and when an agent will start, how a failed call is labelled; the
checks that use real `age` are skipped when it is not installed) and
`ThreadInfoCheck` (16: `thread-info`'s counting, report and command
line), `InventoryCheck` (12: reading `never-reboot`, which nodes it protects, and that `shutdown` refuses and skips them) and `LocationsCheck`
(25: `-I`/`-c`, the environment variables, config names, reading through a URI) and `DbDocumentsCheck` (the `mariadb://` locations and the rows-to-documents conversion, with no database) and `AgentGuardCheck` (9: the agent refuses a reboot and a
shutdown on a host with the marker; it never calls the unprotected path, which would really reboot the test machine). Each exits non-zero on a failure.

The scripts `manifests/test_*.sh` apply a playbook to the lab and check the
result independently of the playbook's own health check. They print a
summary line, either `passed=N failed=M` or `=== Results: N passed, M
failed ===`; the exit code is the result. Run them from the repository
root on the machine that runs `orphera`. The log of each run is a good
place to check that no password was printed:
`grep -cE 'orphera-test-[a-z-]*-(password|key)' <log>` should print 0.

**`manifests/test_mariadb_galera.sh` is destructive.** It tears the Galera
cluster down first, so every database on it (Keystone's, Glance's and so
on) is lost, and haproxy and keepalived are purged. It refuses to run
unless `ORPHERA_ALLOW_DESTRUCTIVE=1` is set. To rebuild the stack
afterwards, run `manifests/build_openstack_stack.sh --from galera`; that
script only applies playbooks and tears nothing down. The records of what
was run, and what it found, are in `docs/TESTS.md`.

**Not yet done.** Test infrastructure (munit + munit-cats-effect) and a
proposed test plan exist but are only partially implemented. Priority
areas, roughly in order of value already identified:

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
decisions, design trade-offs (e.g. the move from a shared wildcard
certificate to per-node ones, the
SSH-based bootstrap/teardown split from gRPC-based day-to-day management,
the network-config rollback design), review, and testing direction are
the author's.

## Example: a real Ceph cluster deployment

`manifests/ceph-*.yaml`/`ceph_*.scala` and `manifests/cephadm_*.yaml`
are two complete, independently-working example playbook sets that
bring up a real 3-node Ceph cluster (mon quorum, mgr, OSDs) — built as
both a genuine capability test for staged/cross-node coordination and
as the most substantial worked example in this repo. See
`manifests/ceph-playbooks-usage.md` for the full run sequence,
teardown, and known host/environment assumptions.

**Manual path** (`ceph-*`) uses `ceph-mon --mkfs` directly — full
control, more steps, a real demonstration of `Task.DistributeFile`
(generate a secret once, push it identically everywhere) and
`HealthCheck.Quorum` (proceed once 2-of-3 mons report healthy, not
waiting on a straggler).

**cephadm path** (`cephadm_*`) drives Ceph's own official containerized
orchestrator instead — `cephadm bootstrap` brings up mon and mgr
together in one step; additional hosts are registered via SSH key
distribution and `ceph orch host add`; OSDs can be placed on specific,
per-node devices (`osd_devices` inventory/group var) or left to
`--all-available-devices`.

The two paths are not meant to be run against the same hosts — pick
one per cluster.

## Example: OpenStack Keystone and Cinder from Kolla images

A larger worked example that builds a small OpenStack control plane on the test
fleet out of the pieces above. It uses the upstream **Kolla container images**
(`quay.io/openstack.kolla/<service>:2025.1-ubuntu-noble`) under rootful `podman`
and `systemd`, not distribution packages: Orphera writes what kolla-ansible
would (service config, Apache vhost, Kolla `config.json`), runs the one-off
`*-manage` commands in containers, and manages the long-running containers as
`orphera-<name>.service` units.

Fleet layout used by these manifests (see `inventory.yaml`):

| Nodes | Role |
|---|---|
| tst0-tst2 | Ceph (cephadm) |
| tst3-tst5 | MariaDB Galera + haproxy + keepalived, VIP 10.10.5.100 |
| tst6 | Keystone (TLS, test CA) |
| tst7 | WordPress, Prometheus/Grafana |
| tst9 | Kolla image cache (`registry:2` pull-through proxy of quay.io) |
| tst10 | Cinder (API, scheduler, volume, RabbitMQ) |
| tst11 | apt cache (apt-cacher-ng) for the Ubuntu nodes |
| tst12 | rpm cache (nginx caching mirror of Rocky/EPEL) for the Rocky nodes |
| tst13 | Glance (glance-api, images in Ceph pool `images`) Placement (port 8780) and Horizon (port 8080) |
| tst19 | Neutron (server, Open vSwitch + DHCP agents) for flat provider networks on its second NIC (br-net106, no address) |
| tst14 | Nova control plane (API on 8774, scheduler, conductor, noVNC proxy on 6080); RabbitMQ vhost `nova` on tst10 |
| tst17, tst18 | Compute nodes (nova-compute on KVM, Neutron Open vSwitch agent; second NIC is the provider port); VMs on gs2 with nested KVM |

Run order, each with its own teardown and an end-to-end test that checks the
result independently of the playbook's own health checks:

    orphera cluster-playbook manifests/cephadm_install.scala  # ... then mons and OSDs
    orphera cluster-playbook manifests/mariadb_galera_cluster.scala
    orphera cluster-playbook manifests/mariadb_haproxy_keepalived.scala
    orphera cluster-playbook manifests/kolla_cache.scala        # optional, speeds up pulls
    manifests/test_keystone.sh                                  # applies keystone_single_node.scala
    manifests/test_cinder.sh                                    # applies cinder_single_node.scala
    manifests/test_glance.sh                                    # applies glance_single_node.scala
    manifests/test_placement.sh                                 # applies placement_single_node.scala
    manifests/test_nova.sh                                      # applies nova_single_node.scala
    manifests/test_neutron.sh                                   # applies neutron_single_node.scala
    manifests/test_nova_compute.sh                              # applies nova_compute.scala, boots a CirrOS instance
    manifests/test_horizon.sh                                   # applies horizon_single_node.scala
    utils/openstack-client-setup.sh                             # CLI on the machine you run orphera from

To build the whole stack in one go from freshly provisioned VMs that only have the agent, run
`manifests/build_openstack_stack.sh` (Ceph, Galera + VIP, Keystone, Cinder, Glance, Placement, Nova, Neutron, the compute nodes, Horizon, the clients
on scala0, then a smoke test that creates and deletes a real volume, image, flavor and network, and finally boots a CirrOS instance on KVM, checks its DHCP lease and deletes it). It only applies
playbooks, never tears anything down, checks each step independently before the next, and on a
failure prints the `--from STEP` command to resume. `--with-caches` builds the apt and Kolla
caches first; `--list` shows the plan.

Separately, `manifests/apt_cache.scala` builds an apt package cache
(apt-cacher-ng on tst11) and `manifests/apt_cache_clients.scala` points the
Ubuntu nodes at it. Nodes check the cache with a small auto-detect script and
fetch directly when it is down, so nothing depends on it. `test_apt_cache.sh`
proves a package is fetched from the mirror once and served from the cache to a
second node.

Likewise `manifests/rpm_cache.scala` builds an rpm package cache on tst12: an
nginx caching mirror (port 8080) of the Rocky and EPEL download hosts. A mirror
rather than a proxy, because Rocky repositories are fetched over HTTPS, which a
plain proxy cannot cache. `manifests/rpm_cache_clients.scala` gives the Rocky
node (tst8) a repository file listing the cache first and the official host
second, so dnf falls back to the internet when tst12 is down; package signatures
are still checked. `test_rpm_cache.sh` proves a package is fetched once (MISS) and
then served from the cache (HIT) and that dnf works with the cache stopped.

`test_mariadb_galera.sh` and `test_vip_failover.sh` cover the database layer
(including a forced VIP failover). `test_cinder.sh` creates a real volume and
checks that it appears, and is removed, as an RBD image in the Ceph `volumes`
pool. `test_glance.sh` does the same for an image in the `images` pool.

`utils/openstack-client-setup.sh` creates a virtualenv with
`python-openstackclient` outside the repository (`~/.venvs/openstack`), fetches
Keystone's test CA, and writes `~/.config/openstack/admin-openrc.sh`:

    source ~/.config/openstack/admin-openrc.sh
    openstack token issue
    openstack volume service list
    deactivate-openstack

Worth knowing:

- **Test credentials are hard-coded** in the manifests (admin, database,
  RabbitMQ, the Ceph `client.cinder` key) by project convention. This is test
  infrastructure; do not reuse any of it for anything real.
- **Ceph cipher compromise.** Ceph 19.2.6 and later (CVE-2025-30156) accepts
  only the new `aes256k` cephx cipher by default, but the Kolla images carry
  Ubuntu's Ceph 19.2.3 client, which only knows the older `aes`. The
  `cephadm_install.scala`, `cinder_single_node.scala` and `glance_single_node.scala` playbooks therefore run
  `ceph mon set auth_allowed_ciphers aes,aes256k`. Once the clients are upgraded,
  enforce `aes256k` only (`ceph mon set auth_allowed_ciphers aes256k`) and
  rotate `client.cinder` and `client.glance`.
- **Kolla specifics learned the hard way:** `keystone-manage fernet_setup`
  switches to the image's `keystone` user, so the host key directories must be
  owned by that uid; the image's Apache has `mod_ssl` but does not load it;
  `kolla_start` adds `-DFOREGROUND` itself; and services configured with
  `log_dir` log only to files unless `use_stderr = true` is also set.
- **Glance** (tst13) is a single `glance-api` container with the RBD store; it needs no
  message bus. Its teardown also removes it from Keystone.
- **Placement** shares tst13 with Glance (a small stateless Apache + mod_wsgi API with its own
  database); `test_placement.sh` creates and deletes a resource provider.
- **Horizon** (the dashboard) is at `http://tst13.ljalbinson.com:8080/` after
  `manifests/test_horizon.sh`; sign in as `admin` (domain Default) with the Keystone admin
  password. It keeps no database; sessions are signed cookies. The test performs a real login.
  The Project dashboard needs a compute service in the Keystone catalog, so it works once Nova
  (`test_nova.sh`) is registered, and Overview and Network need Neutron (`test_neutron.sh`).
- **Nova** (control plane only) is at `http://tst14.ljalbinson.com:8774` after
  `manifests/test_nova.sh`: API (Apache + mod_wsgi), scheduler, conductor and the noVNC proxy, with
  databases `nova_api`, `nova`, `nova_cell0` on Galera and its own vhost on the RabbitMQ on tst10.
  Servers are booted on the compute nodes below; the Nova test itself only creates and deletes a
  flavor. nova.conf carries `[glance] cafile` (Keystone's CA), so after upgrading from an older
  copy re-apply `nova_single_node.scala` once, or server creates fail with HTTP 500.
- **Neutron** (provider networks only) is at `http://tst19.ljalbinson.com:9696` after
  `manifests/test_neutron.sh`: neutron-server, the Open vSwitch agent and the DHCP agent on one
  node, with ML2 flat networks on the physical network `provider` (OVS bridge `br-provider`,
  whose port is tst19's second NIC; the playbook removes the address that NIC had). No L3 agent,
  no tenant networks, no metadata agent: instances get DHCP on a provider network and can use
  config drive. After applying it, re-apply `nova_single_node.scala` once so Nova's config gets the
  `[neutron]` section. A real network: `openstack network create --provider-network-type flat
  --provider-physical-network provider --share provider`, then a subnet on it.
- **Compute nodes** tst17 and tst18 (`manifests/test_nova_compute.sh`): nova-compute with
  `virt_type = kvm` (needs nested virtualization on gs2 and `host_type: host-passthrough`),
  libvirt/QEMU from apt, and the Neutron Open vSwitch agent; the second NIC becomes the provider
  port as on tst19. Instances use a config drive (no metadata agent). The test boots a CirrOS
  instance on a temporary flat network with DHCP (10.10.6.0/24, pool .200-.220) and removes it.
  The node helper `orphera-compute-api` (`compute-up`, `ovs-up`, `hypervisors`, `boot-test`,
  `boot-cleanup`) is installed on both nodes; `KEEP_BOOT=1` leaves the test instance behind for
  inspection. Teardown: `nova_compute_teardown.scala`.
- **Utilities** in `utils/`: `list-vms.sh [hv...]` lists every VM on each hypervisor with state,
  vCPUs and memory; `start-vm.sh <vm>...` starts VMs, finding each one's hypervisor and domain name in
  its `config/<vm>.yaml` (a shut-off VM is started, a paused one resumed, a running one left alone; it
  does not wait for the guest, `manifests/kvm_vm_start.scala` does); `stop-vm.sh [--wait s] [--force] <vm>...`
  shuts a VM down with `virsh shutdown` and waits until it is off (`--force` powers it off if it has not
  shut down in time); `reboot-vm.sh [--soft | --hard] <vm>...` reboots a VM from the hypervisor, for a
  guest whose agent or SSH is not answering (`--soft`, the default, is `virsh reboot`; `--hard` shuts the VM down,
  waits, and starts it again, with `--wait` and `--force` as for `stop-vm.sh`);
  `trace-dhcp.sh` captures DHCP on the provider NICs of tst17, tst18 and tst19
  while a test instance boots, to show where packets are lost; `check-guest-dhcp.sh <ip> <mac>
  <network-id>` proves a guest took its address (it answers ARP from the DHCP namespace on tst19;
  the guest console cannot show a late lease); `galera-cold-start.sh` brings the Galera cluster back
  after every node was stopped, bootstrapping from the node with the highest committed seqno
  (`--check` to see what it would do, `--bootstrap <node>` to override). The master build script
  runs it in its preflight.

## Example: parallel network throughput test (iperf3)

`manifests/iperf3_test.yaml` / `manifests/iperf3_test.scala` — a
smaller, non-Ceph example: tst1 and tst2 run `iperf3` clients
simultaneously against a self-hosted `iperf3` server on tst0 (star
topology, not full mesh between every pair). Demonstrates staged
node-subset targeting (install on all three, start the server on tst0
only, run clients on tst1+tst2 only) and genuinely parallel execution
within a stage.

Two things worth knowing if adapting this to a different target:

- A default `iperf3 -s` instance only serves one client at a time, so
  the server stage starts one instance per client, each on its own
  port — otherwise the second client just queues behind the first.
- The server is started with `systemd-run --unit=<name> --collect --
  <command>`, not `nohup ... &`. Backgrounding a never-exiting daemon
  with `nohup` inside a `RunCommand` task hangs the task until its own
  timeout kills it (`RunCommand` waits for the process tree/output to
  fully close, which a daemon that's meant to run forever never does)
  — `systemd-run` hands the process to systemd and returns immediately
  instead. This is a general pattern, not iperf3-specific: reach for
  `systemd-run` any time a playbook needs to start something meant to
  outlive the task that started it.

## Known gaps / not yet built

- `when:` conditions can only check the *executing* node's own facts —
  there is no way to branch on a *different* node's fact/var inside a
  `when:` key (only inside `{{...}}` template substitution, which does
  support `nodes.<name>.*`). The `cephadm_add_osds.yaml` per-node
  device selection works around this by templating a shell script that
  does its own `if`/`test` branching, rather than using `when:` at all.
- No dynamic/cloud inventory source
- No standalone declarative file-manifest mechanism (`apply`) or task
  ordering within one — playbooks are the actual implemented mechanism
  for ordered, multi-step execution, see [Playbooks](#playbooks)
- No mutual TLS
- No path allowlisting on `copy` **or `fetch`** — an authenticated
  orchestrator can write or read any path the agent's root user can
  reach
- Secrets: one encrypted file on the orchestrator machine, no rotation,
  and values are visible in a command's arguments on the node while it
  runs (see [Secrets](#secrets)). The orchestrator still falls back to
  the built-in development token (with a warning) when none is
  configured; agents refuse to run with it. All commands other than
  `version`, `uptime` and `thread-info` show a rejected token as a raw
  gRPC error
- Typed configuration (see [Typed configuration files and check
  mode](#typed-configuration-files-and-check-mode)) covers the OpenStack
  control plane only; check mode covers Placement only; the
  authentication-plugin options of `[keystone_authtoken]` are not in any
  catalogue
- ~~No automated verification that `deploy-agent` actually
  succeeded~~ — **closed**: `deploy-agent` now polls each node's
  `version` RPC after installing (see [Versioning](#versioning)) and
  its exit code/summary reflect actual confirmed-running-the-new-version,
  not just "the detached install was launched." The install itself
  still has to run fully detached from the agent's own process (via
  `systemd-run --no-block`), because the agent's own service restart
  otherwise kills the `dpkg -i` it just spawned mid-unpack (systemd's
  default `KillMode=control-group` sends `SIGTERM` to every process in
  the service's cgroup, including its own children) — this was hit and
  fixed in practice, not theoretical — so the RPC call that launches
  the install still can't itself wait for it to finish; polling
  afterward is how that gets closed instead. `bootstrap` (SSH-based,
  no existing agent to poll against) isn't covered by this — its own
  success is still only as good as the SSH session's exit code
- Direct CLI commands (`install`, `remove`, `autoremove`, `copy`,
  `write-file`, `network-apply`, `reboot`, `run`) each have their own dispatch path
  in `Main.scala`/`Orchestrator.scala`, separate from `PlaybookRunner`
  — a design where they're unified (CLI commands as one-task
  playbooks, one execution engine) was discussed but not implemented.
  Practical consequence: the `DPkg::Lock::Timeout` fix and any future
  `PlaybookRunner`-level improvement (retries, etc.) apply to
  `playbook` runs but not to direct CLI invocations, unless ported to
  both places separately
- `set_fact` has no way to target a specific node by name from a task
  list shared across many nodes — the workaround is a per-node
  `when: hostname == "..."` guard repeated once per node. For a
  genuinely static value, declaring it directly in `inventory.yaml`
  (node or group vars) is usually the better fit — see
  [Inventory](#inventory) and [Cross-node data and vars](#cross-node-data-and-vars)
- No handlers/triggers or playbook includes/imports
- No monadic/for-comprehension style for the Scala DSL, as an
  alternative to the current fluent `.task(...).when(...)` builder —
  designed, not implemented, see [Playbooks](#playbooks)
- No compile-time check that a DSL playbook is actually registered in
  `PlaybookRegistry` — a defined-but-unregistered playbook fails
  silently at the CLI (though it's still directly runnable via
  `runMain`, since `OrpheraPlaybook` doesn't depend on the registry)
- No per-task retry/backoff in either runner beyond the built-in
  `apt`/`dpkg` lock-contention timeout — other transient failures
  still stop a node's remaining tasks rather than retrying
- `reboot --wait` confirms only that the agent's gRPC service answers
  again — `uptime` (see [CLI reference](#cli-reference)) is the way to
  confirm the host actually rebooted, rather than just that the agent
  process came back
- Fact keys usable in `when:` conditions are a small fixed set
  (`os_id`, `os_version`, `arch`, `hostname`, plus anything already
  `set_fact`-ed on that node) — not the full `Facts` schema, and use a
  different naming convention than the `facts.*`/`nodes.*.*` keys
  exposed to templating (not yet normalized)
- `run_command`'s health-check polling path discards the command's
  stdout/stderr on every poll attempt (to keep stage-progress logs
  readable) — usable as a pass/fail gate only, not for inspecting
  output while polling
- Test coverage incomplete (see [Testing](#testing))
- Native-image build not working (see [A note on native-image](#a-note-on-native-image))
- `-Werror`/`-Wconf` are now enabled in `build.sbt`, which has already
  caught at least one real bug (a `Command.Fetch` case parsed by `Cli`
  but never handled in `Main.scala`) as a hard compile failure instead
  of a silent runtime crash. CI (`.github/workflows/ci.yml`,
  `.gitlab-ci.yml`) runs `sbt clean compile "scalafixAll --check" test` on
  every push, which catches compile and lint failures. It does not run the
  check programs (`make test` does) or any of the lab tests under
  `manifests/test_*.sh`, which need the cluster.
- **`wait` cannot be used as a case class/`enum case`/parameter name
  anywhere in this codebase.** `java.lang.Object` declares a `final`
  `wait()` method, inherited by every class; a Scala class or enum
  case with a field literally named `wait` fails to compile
  (`error overriding method wait in class Object`). Hit independently
  in both `Task.Reboot` and `Cli.Command.Reboot` while building the
  reboot feature — both use `waitForReturn` instead. Worth remembering
  before naming any future field `wait` anywhere in this project.
