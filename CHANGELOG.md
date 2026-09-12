# Changelog

All notable changes to this project are documented in this file.

The format is based on [Keep a Changelog](https://keepachangelog.com/en/1.1.0/).

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
