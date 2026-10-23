# Changelog

All notable changes to this project are documented in this file.

The format is based on [Keep a Changelog](https://keepachangelog.com/en/1.1.0/).

## [Unreleased]

### Added
- `manifests/control_host.scala`: turns an empty test VM into a control and build host like scala0. It installs OpenJDK 21,
  sbt (scala-sbt apt repository), make, dpkg-dev, rpm, openssl, Go 1.22 with protobuf-compiler, protoc-gen-go v1.35.2,
  protoc-gen-go-grpc v1.5.1 and staticcheck 2024.1.1, age, mariadb-client, the Python modules the utils need, shellcheck
  and jq; clones the repository into `~localadmin/orphera-pkg` (only if it is not there); runs `make orpheracli` and the
  Go agent's `make`; then reports the versions. `--config tst15`.
- `manifests/control_host_handover.scala`: copies the CA (`certs/ca.crt`, `ca.key`), the age identity and secret store,
  and the SSH key pair from the machine running `orphera` to that host (all localadmin, mode 600 except the certificate
  and public key), checks `orphera secrets list` and `orphera version` from there, and sets up the OpenStack clients
  (`CONTROL_HOST_SKIP_OPENSTACK=1` leaves them out). Stops before copying anything if a source file is missing, or if the
  agent token comes from `ORPHERA_TOKEN` and the store's `orphera/agent-token` does not match it (the new host would be
  rejected by every agent). The check fails unless every node answers `orphera version` with a version.
- tst15 is the second control host: 4 CPUs and 8 GB in `config/tst15.yaml` (was 2 and 4 GB), for sbt builds. Built with
  both playbooks on 2026-10-23 (OpenStack clients skipped: OpenStack was down).
- README: [Building a second control host](README.md#building-a-second-control-host), the commands from an empty VM to a working
  second control host, what each playbook installs or copies, and what the two hosts do not share.

### Fixed — VM build scripts
- `mk-new-and-empty.sh` and `par-mk-new-and-empty.sh` take any node name (`scala1`, `gs2`), not only `tst...` names: a bare number
  still means `tst<number>`, and anything else is used as given. Before, `scala1` became `tstscala1`.

### Fixed — `orphera audit-log`
- A command that threw an error or was interrupted with Ctrl-C now writes its end record (exit 1 with the error message, or exit 130),
  instead of being listed as in progress for ever. Seen with `deploy-agent`, `bootstrap` and `dist-upgrade` on 2026-10-23.
- The start record now holds the process id and its start time, so a command with no end record shows as RUNNING while its process is
  alive and NO END RECORD once it is gone (killed with SIGKILL, say). Older records, which have neither, show NO END RECORD.
- The table no longer breaks up: a multi-line `run` script is put on one line, node lists are shown as runs (24 nodes become
  `tst0-7,tst9-20,gs1-3,st0`), Nodes is cut at 40 characters and Detail at 100, and the last column is not padded. `--full` shows both
  uncut. Durations of a second or more are in seconds (`89.2s`).
- Playbooks, which name no nodes on the command line, show `-` in Nodes instead of `?`.
- `deploy-agent` records `remote_path` only when `--remote-path` was given; the default shown before said `.deb` even for `.rpm` installs.

### Changed
- The agent package no longer contains `certs/ca.crt` (`/etc/orphera-agent/certs/ca.crt`). The agent never read it: it presents the
  certificate `bootstrap` or `issue-certs` put in `/etc/orphera-agent/tls`, and only the orchestrator verifies it against its own CA. The
  package is now the same for every site, so it can be published, and `make deb` / `make rpm` no longer need `make certs` first. An
  upgrade removes the old file; nothing else changes on the node.
- The option that names the agent package is now `--agent`, not `--file`, in `deploy-agent`, `bootstrap` and `cluster-playbook` (its
  `@bootstrap` step): `orphera deploy-agent --agent orphera-agent_0.1.203_amd64.deb`. `--file` is still accepted, but is no longer shown
  in the help, the completion or this documentation. The scripts `mk-new-and-empty.sh`, `par-mk-new-and-empty.sh` and
  `rebuild_openstack_from_scratch.sh` take `-a` for the same thing, and still take `-f`. `--content-file` of `write-file` is unchanged, as
  it names a file whose contents are sent.

### Fixed
- A missing or unreadable CA certificate is reported as such (`no CA certificate: /etc/orphera/certs/ca.crt does not exist (put ca.crt
  there, or set ORPHERA_CERTS_DIR ...)`) instead of the TLS library's "File does not contain valid certificates", which it also gives for
  a file that is not there.

## [0.1.203] - 2026-10-22

The agent is unchanged from 0.1.202: these are changes to the `orphera` command and its packaging.

### Fixed
- A playbook's relative `src` (`files/motd`, `files/ready-sentinel.txt`) is read from the current directory when it exists there, as
  before, else from `ORPHERA_ROOT`, which the packaged launcher sets to `/opt/orphera`. The packaged `WebBaseline`, `mini-cluster` and
  other playbooks that push files now work from any directory. An absolute path and a missing file behave as before.
- A file that cannot be read now says why (`cannot read /etc/orphera/inventory.yaml: no such file`, or `permission denied`) instead of repeating
  the path, and the warning about a missing inventory says how to set its location.

## [0.1.202] - 2026-10-22

The agent is unchanged from 0.1.201: these are changes to the `orphera` command, its packaging and the shell scripts.

### Added
- The `orphera` command can be installed as a package: `make cli-package` builds `orphera_<version>_all.deb`, a tarball
  `orphera-<version>.tar.gz` and, when `rpmbuild` is installed, `orphera-<version>-1.noarch.rpm`. The program goes in the read-only
  `/opt/orphera` (jars, playbooks, helper scripts, catalogues, schemas, docs, examples), with `/usr/bin/orphera` and bash completion.
  Site data lives in `/etc/orphera` (`inventory.yaml`, `config/`, `certs/`) and run state per user under
  `${XDG_STATE_HOME:-~/.local/state}/orphera`. The packaged launcher sets these as defaults; the `ORPHERA_*` variables and
  `-I`/`-c`/`-m` still override. `ORPHERA_SITE_DIR` moves the three site paths together.
- `make check-package` (also run by CI) stages the package with stand-in jars and checks the layout, the launcher and that the shell
  scripts pick up the same defaults. Needs no build.
- `rebuild_openstack_from_scratch.sh -f` resolves a relative path against the directory the script was started from.

### Changed
- `make release` also builds the command-line package (`make cli-package`).

### Fixed
- Running a `.scala` playbook no longer leaves its compiled classes behind: the `orphera-script-classes*` directory the scripting jar
  creates in the temp directory is now removed when the playbook ends, whether it compiled, failed or ran.

## [0.1.201] - 2026-10-22

The agent is unchanged from 0.1.200: these are changes to the `orphera` command, the shell scripts and the documentation.

### Added
- A `.scala` playbook is compiled against the installed orchestrator jar (`/usr/local/lib/orphera-orchestrator.jar`, or
  `ORPHERA_ORCHESTRATOR_JAR`), not only one under `orchestrator/target/`, so `orphera playbook x.scala` works with no build tree.
- `ORPHERA_CERTS_DIR` (default `certs`) says where `ca.crt` and `ca.key` are, and `ORPHERA_STATE_DIR` (default the current directory) where
  `.orphera-state`, `.orphera-logs`, `.orphera-audit` and `.orphera-build-logs` are written; `make certs` honours the first. The `orphera`
  launcher no longer exits when `~/orphera-pkg` does not exist: it runs from `$ORPHERA_HOME` if set, else `~/orphera-pkg` if present, else
  the current directory. With nothing set, behaviour is unchanged. A step towards running without the repository underneath.
- `.scala` playbooks no longer need a build tree: `make orpheracli` also installs the scripting jar as
  `/usr/local/lib/orphera-scripting.jar`, and `orphera` uses, in order, `ORPHERA_SCRIPTING_JAR`, that installed jar, and the newest
  `scripting/target/` jar as before. With no jar at all the error says how to get one. `InstallationCheck` covers the choice.
- `-m` / `--manifests <dir>` (and `ORPHERA_MANIFESTS`, default `manifests`) says where the playbooks are. A playbook given as a bare
  file name (`orphera -m /usr/share/orphera/manifests cluster-playbook gen_set_dns.scala`) is looked for in that directory first, then
  taken as it is; a name with a `/`, `@bootstrap` and compiled playbook names are never looked up, so existing command lines are
  unchanged. The option reaches the playbook scripts `orphera` starts as `ORPHERA_MANIFESTS`. A first step towards running without
  the repository underneath.
- `--exclude-nodes a,b` takes nodes out of what `--nodes` / `--node-groups` selected (`orphera dist-upgrade --node-groups mons
  --exclude-nodes tst1`). Without `--nodes` or `--node-groups` it means every node except those, for the
  commands where no `--nodes` already means every node (`deploy-agent ... --exclude-nodes tst8`); for `reboot`, `shutdown`,
  `teardown`, `bootstrap`, pipelines and playbooks it is an error, so they still never default to every node. A name that was
  not selected, or an exclusion that leaves nothing, is an error.

### Changed
- The shell scripts in `manifests/` name their playbooks by bare file name instead of `manifests/<file>`, so they follow
  `ORPHERA_MANIFESTS` (and `-m`), and `rebuild_openstack_from_scratch.sh` calls its sibling scripts relative to its own location. They need
  an `orphera` with the `-m` option. `make check-scripts` fails if a script names a playbook by its `manifests/` path again.
- `manifests/rebuild_openstack_from_scratch.sh --tests` now runs the closing checks (`clients`, `smoke`, the CirrOS `boot`) again after
  the test scripts. `test_keystone.sh` tears Keystone down (new database, new CA), so the checks made before the tests did not show the
  finished cluster works. Its `--help` text no longer cuts off at a fixed line.
- Tab completion: offers `--exclude-nodes` with every verb that takes `--nodes`, completes node names (from the inventory, `-I` and
  `ORPHERA_INVENTORY` honoured) after `--nodes` and `--exclude-nodes` as a comma-separated list that leaves out names already given,
  and completes the config names after `show-config`.

### Removed
- `build` (an old script that ran a 0.1.0-SNAPSHOT jar) and `manifests/ceph-mgr.scala` (a copy of `ceph_mgr.scala`), with their SPDX exemptions.

## [0.1.200] - 2026-10-21

### Added
- `orphera show-config <name>...` prints each VM config as `orphera` reads it, from the `-c` / `ORPHERA_CONFIG_DIR` location (files or
  `mariadb://`), one line per config, so the two sources can be compared with `diff`. Read-only; runs on this machine.
- `mariadb://` locations for `-I` and `-c` (`MariaDbSource.scala`, `DbDocuments.scala`, mariadb-java-client): `orphera` reads the
  inventory and the per-VM configs from the MariaDB tables, read-only, with the server certificate and host name always
  verified against `certs/ca.crt` (or `ORPHERA_DB_CA`) and the password from the secret `orphera/inventory-db-reader`, which is
  never created on first use. The files stay the source of truth. `DbDocumentsCheck` covers the locations and the conversion.
  Run against the real server: the inventory it reads equals `inventory.yaml`. `slf4j-nop` silences the driver's logging notice.
- `utils/sql-to-yaml.py`, the reverse of `yaml-to-sql.py`: reads the MariaDB tables (through the `mariadb` client, `SELECT`
  only, password from the secret store) and either checks them against `inventory.yaml` and `config/*.yaml`
  (`--compare`, data not text, one line per difference) or writes them as files (`--out DIR`). `utils/test-sql-roundtrip.py`
  (`make check-roundtrip`) loads the real files into a SQLite copy of the tables and reads them back; it fails if the
  exporter's column lists drift from `001-tables.sql`. Run against the real server: the database equals `inventory.yaml` and `config/*.yaml`.

### Changed
- The shell scripts (`build_openstack_stack.sh`, `mk-new-and-empty.sh`, `par-mk-new-and-empty.sh`,
  `rebuild_openstack_from_scratch.sh`, and the VM scripts through `vm-common.sh`) read the VM configs from
  `ORPHERA_CONFIG_DIR` and the inventory from `ORPHERA_INVENTORY` (plain path or `file:` URI; defaults unchanged) through the
  new `utils/orphera-sources.sh`, so a rebuild can run from a copy exported from the database. `make check-scripts`
  (`utils/test-shell-sources.sh`) checks it, with no lab, and fails if a script opens a fixed `config/` path again.

### Fixed
- `utils/sql-to-yaml.py` accepts `--mariadb "-h host ..."` (a value starting with `-`); the documentation names the login account `orphera_ro`, not the role `orphera_reader`, and says an account is needed per client host. Run against the real server: the database equals `inventory.yaml` and `config/`.
- `utils/yaml-to-sql.py` refuses a config with a non-empty `pdisks` instead of dropping it silently (no table holds it).

### Documentation
- `docs/TESTS.md` K34-K37: the credential replacement and lab rebuild, the second duplicate-VIP conflict, the shutdown that
  reached a protected hypervisor, and the agent-side guard on tst20. `docs/ROTATING-LAB-SECRETS.md` now records the verified
  procedure. `docs/MARIADB.md` lists what is missing before the database can be the source for a rebuild.

## [0.1.199] - 2026-10-20

### Security — no passwords or keys in the repository
- Removed `manifests/seed_test_secrets.sh`, which held the lab's service passwords and two Ceph client keys
  (`glance/ceph-key`, `cinder/ceph-key`) in plain text in a public repository. They are still in git history: treat them
  as published, and replace them in the lab (see below). A new deployment needs no seeding, as Orphera makes random
  values on first use; an existing one loads its values with `orphera secrets set`.
- `utils/openstack-client-setup.sh` no longer writes the Keystone admin password into the rc file or the script: sourcing
  the rc file reads `keystone/admin` from the secret store.
- Grafana's admin password is the secret `observability/grafana-admin` instead of `admin`, and `observability_stack.scala`
  sets it with `grafana cli admin reset-admin-password` so an existing Grafana agrees. `test_observability_stack.sh`
  reads it from the store.
- `utils/rotate-lab-secrets.sh` replaces the lab's service passwords and Ceph keys in the secret store (dry run by default,
  `--yes` to apply; backs up the store first; never touches `orphera/agent-token` or `orphera/inventory-db-*`). The running
  lab keeps the old values until it is rebuilt: see `docs/ROTATING-LAB-SECRETS.md`.

### Added
- Agent-side `never-reboot`. An agent refuses `TriggerReboot` and `TriggerShutdown` (`FAILED_PRECONDITION`, naming the file)
  while its host has `/etc/orphera-agent/never-reboot`, or the path in `ORPHERA_NEVER_REBOOT_FILE`, so a stale or other
  client cannot reboot or power off a protected host. Checked on each request, no restart. `utils/sync-never-reboot.py`
  creates the file on every host the inventory protects (dry run unless `--apply`; `--prune` removes it elsewhere). New
  `AgentGuardCheck` (9), run by `make test`; it exercises only the refusal, never the real reboot path. Needs the agent
  deployed first.
- `orphera version` first prints the build of the command itself: version, commit, commit date, and "+uncommitted changes"
  when the tree was dirty. It makes a stale installed jar visible: it was how a shutdown reached a protected hypervisor.

### Fixed
- `never-reboot` now also protects against `orphera shutdown`. A protected node (or a member of a protected group) is
  skipped with a message and the rest are shut down; `NodeClient.shutdown` refuses it before opening any connection. Until
  now the property stopped only reboots, so `orphera shutdown --nodes gs1 --yes` would have powered off a hypervisor that
  cannot be powered back on remotely. `InventoryCheck` has two new checks (12 in all). `orphera run -- shutdown` is still not
  guarded, as before.

### Changed
- SPDX headers on every tracked source file, not only `.scala`: 101 files gained a `SPDX-License-Identifier: Apache-2.0` line
  (Scala, shell, YAML playbooks, sbt, proto, Makefile, the systemd unit, the packaging scripts). Nothing else in them
  changed; the header goes after a shebang or a `yaml-language-server` modeline. Data, documentation and formats without
  comments (JSON, configs, certificates, package control files) are listed in `utils/spdx-exempt.txt` and covered by
  `LICENSE`. `utils/spdx.py --check` (`make check-spdx`, and a CI step) fails if a file lacks a header and is not exempt.
  `utils/add-spdx-headers.sh` still works and now calls it. The GitLab pipeline runs this check and the credential check too.

### Security
- Removed `manifests/mariadb_galera_cluster.yaml`, a stale YAML copy of the Galera playbook that held the SST password as a
  literal. It targeted tst0-tst2 (now the Ceph nodes) and nothing referred to it; `mariadb_galera_cluster.scala` is the
  maintained version and takes the value from the secret store (`galera/sst`). It stays in git history.
- `utils/check-no-secrets.sh` (`make check-secrets`) fails if a tracked file holds a lab password in the old style, a quoted
  cephx key or a PEM private key.

### Fixed
- `mariadb_haproxy_keepalived.scala` checks that nothing outside tst3-tst5 answers ARP for the VIP before it configures
  haproxy and keepalived (new stage `check-vip-not-in-use`, on tst3). During a rebuild another host held 10.10.5.100, some
  clients reached it, and the failure showed only at the last check as a refused connection. The check names the MAC and
  compares it with the three nodes' own, so re-running while a node legitimately holds the VIP passes.
- The VRRP password of that playbook is the secret `galera/vrrp` instead of a literal in the repository.
  `utils/rotate-lab-secrets.sh` includes it.

### Added
- `utils/start-vm.sh`, `utils/stop-vm.sh` and `utils/reboot-vm.sh` (which read `ORPHERA_CONFIG_DIR`, file locations only): start, shut down, or reboot from the hypervisor
  (`--soft`: `virsh reboot`; `--hard`: shut down, then start) a VM, finding the hypervisor in its `config/<vm>.yaml`. They share
  `utils/vm-common.sh`.

### Added — the inventory and configs as MariaDB tables
- `schemas/mariadb/001-tables.sql` and `002-roles.sql`: the inventory and the per-VM configs as tables, with the rules a
  JSON Schema cannot state (unique names, group members and hypervisors exist, no shared addresses) as keys and
  checks; and two roles, with no accounts or passwords. Needs MariaDB 10.10 or later. See `docs/MARIADB.md`.
- `utils/yaml-to-sql.py` writes the existing YAML files as SQL for those tables (checked first; one transaction;
  `--replace` to make the database match the files). Orphera cannot yet read from MariaDB: that needs a
  `mariadb:` reader. Tested against a SQLite translation of the tables, not on MariaDB.

### Added — `-I` and `-c`: the inventory and the config directory as URIs
- `orphera -I <uri> -c <uri> <command>`, or `ORPHERA_INVENTORY` and the new `ORPHERA_CONFIG_DIR`. The option wins over the
  variable, the variable over the default (`inventory.yaml`, `config`). A plain path or a `file:` URI is read today;
  `Sources.register` adds another scheme (a database, a dynamic connector) without touching the code that uses the
  inventory. An unsupported scheme is refused naming those there are.
- With `-c`, `--config tst7` means `<config-dir>/tst7.yaml`; a value with a `/`, a scheme or a `.yaml` ending is used as
  given, as before. Playbook scripts and pipeline steps started by `orphera` are given the same locations.
- New check program `LocationsCheck` (25), run by `make test`.

### Added — schemas for the inventory and the config files
- `schemas/inventory.schema.json`, `schemas/vm-config.schema.json` and `schemas/dns-config.schema.json` (JSON Schema
  2020-12), described in `docs/SCHEMAS.md`. Unknown keys, bad addresses and ports, and names with odd characters are
  errors; `x-` keys are allowed in a config. A database or connector source has to produce documents that validate
  against them.
- `utils/validate-schemas.py` (`make check-schemas`) checks the files against the schemas, then the rules a schema cannot
  state (unique names, group members exist, a VM's hypervisor is in the inventory, `cidr`/`netmask`/`gateway` agree,
  no duplicate addresses). Needs python3-jsonschema and python3-yaml. Orphera itself does not validate yet.

### Added — `never-reboot`
- `never-reboot: true` on an inventory node or group. `orphera reboot` (and `make reboot-all`) skips such nodes, and a
  `reboot` task fails on them, so a hypervisor can be protected from any reboot Orphera would start. New check program
  `InventoryCheck` (10), run by `make test`.

### Changed
- `make reboot-all` refuses to run unless `ORPHERA_ALLOW_DESTRUCTIVE=1` is set, as the destructive test scripts already do.

### Documentation
- README, `docs/TESTS.md` and `Software-Hierarchy` brought up to date: certificates and the agent token, Rocky/dnf, the
  secret store, check mode, `thread-info`, release flow and the `Makefile` targets.
- `make orpheracli` (and so `make release`) also installs `orphera-completion.bash` as `/etc/bash_completion.d/orphera`.
- The CLI help text lists `secrets`; the bash completion knows `secrets` and its subcommands.

## [0.1.198] - 2026-10-19

### Added — `orphera thread-info`
- `orphera thread-info [--nodes ...] [--detail] [--reset-peak]` reports each agent process's threads: the OS thread count,
  the Java threads, the highest Java thread count since the agent started (the JVM's own peak, so it needs no sampling),
  CPUs, resident memory and heap. `--detail` adds the Java thread states and the threads grouped by name, from
  `/proc/self/task/*/comm`, which includes the JVM's own GC and compiler threads. `--reset-peak` starts the peak again
  after reading it. New gRPC call `GetThreadInfo`; an agent older than the orchestrator answers `unsupported` (the CLI
  maps `UNIMPLEMENTED` for this) and is fixed by `deploy-agent`. Needs agent 0.1.198 or later. Allowed as a pipeline step,
  not audited (read-only), added to the bash completion. New check program `ThreadInfoCheck` (16), run by `make test`.

## [0.1.197] - 2026-10-19

### Security — an agent refuses to start without a real token
- With `ORPHERA_TOKEN` unset (the packaged `agent.env` leaves it commented out) an agent accepted the built-in
  `dev-only-insecure-token`, which is in the source. It now logs why and exits, both when the token is unset or empty and
  when it is the built-in one (`Auth.requireToken`). The README's development example (`ORPHERA_TOKEN="dev-secret"`) still works.
  systemd retries every five seconds, as it does for a missing certificate. Before upgrading a node, make sure it has a token
  (`grep -c '^ORPHERA_TOKEN=' /etc/orphera-agent/agent.env` prints 1). `packaging/agent.env` is deliberately unchanged: it is a
  conffile, and changing its text would make `dpkg` ask about a conflict on every node that has a token.
- `bootstrap` and `deploy-agent` stop before touching any node when the orchestrator has only the built-in token
  (`AgentToken.requireConfigured`), since the node would be left unable to start.
- The agent compares the token without stopping at the first difference (`Auth.accepts`). `Auth.SharedToken` is gone; a
  `ORPHERA_TOKEN` set to the empty string no longer makes the orchestrator send an empty token.

### Changed — `version` and `uptime` say `unauthenticated` for a rejected token
- A node that answered and rejected the token now shows `unauthenticated: the node rejected this orchestrator's token`,
  and one that did not answer shows `unreachable: <gRPC status>`, as before. `uptime` used to print a bare `unreachable` and
  hide the reason. The gRPC status code decides (`NodeError`), not the message text.

### Security — the agent token comes from the secret store (`AgentToken.scala`, `utils/set-agent-token.sh`)
- The orchestrator sent, and the agents accepted, the built-in `dev-only-insecure-token` whenever `ORPHERA_TOKEN` was not set, and
  the packaged `agent.env` leaves it unset. The orchestrator now takes the token from `ORPHERA_TOKEN`, else from the secret store
  (`orphera secrets generate orphera/agent-token`), and warns on stderr when it has to use the built-in one.
  `utils/set-agent-token.sh <node>...` writes the stored token to each agent's `agent.env` (over ssh stdin, mode 600) and restarts
  the agent, for nodes that are already installed.
- `orphera bootstrap` now writes the same token (`AgentToken.current`) into the node's `agent.env`. It had used only the
  `ORPHERA_TOKEN` environment variable or the built-in default, so with the token in the secret store a bootstrapped node would
  have been given a token the orchestrator does not send, and every call to it would have failed.

## [0.1.196] - 2026-10-19

### Removed — the shared wildcard key is no longer in the agent package
- The `.deb` and `.rpm` carry only `certs/ca.crt`. Upgrading removes the old `certs/server.{crt,key}` from each node;
  agents use their own certificate in `/etc/orphera-agent/tls/` (`orphera cert-info` shows which certificate each presents).
- An agent with no certificate in `tls/` does not start and says why. `deploy-agent` refuses to upgrade such a node,
  because the new agent could not start and the RPC would be the only way back in: run `orphera issue-certs` for it first.
- `bootstrap` always issues the node its certificate, and stops at once if `certs/ca.key` is missing.
- `make certs` now makes only the CA (no `DOMAIN` needed). `make certs-server` and `buildcert`, which made the wildcard,
  are gone, as is the committed `certs/server.crt`. Package staging starts from an empty tree, so a file dropped from the
  package cannot survive from an earlier build.

### Added — `orphera cert-info`
- Shows the certificate each agent presents, read from the TLS handshake: per-node or wildcard, the names it covers,
  expiry and days left, serial and SHA-256 fingerprint. Flags a certificate that does not name the node's host, is not
  signed by `certs/ca.crt`, has expired or expires within 30 days, and exits non-zero if any node has a problem.

### Added — a certificate of its own for each agent (`orphera issue-certs`)
- Every agent package carried the same wildcard key, so one node's key would open them all. `orphera issue-certs --nodes ...`
  has each node generate its own key in `/etc/orphera-agent/tls/` (it never leaves the node), signs the node's request with
  `certs/ca.key` for exactly its inventory host, and installs the certificate once it is checked against the key. A failure
  changes nothing. `bootstrap` now does this too when `certs/ca.key` is present.
- The agent uses `tls/` when it holds a certificate and key and falls back to the packaged wildcard otherwise; its startup
  line names the certificate in use. Needs the new agent package. The wildcard leaves the package in a later release.
- `teardown --purge` on Ubuntu now also removes `/etc/orphera-agent/tls`.

### Fixed — `orphera bootstrap` gives the agent this orchestrator's token
- `agent.env` is a config file the package never overwrites, so a node kept whatever token it first had (often the built-in
  default) and every call failed with `UNAUTHENTICATED` once `ORPHERA_TOKEN` was set on the orchestrator. Bootstrap now
  writes `ORPHERA_TOKEN` into the node's `agent.env` (other lines kept, file mode 0600) and restarts the agent. The token is
  sent over ssh's stdin, not on a command line.

### Fixed — `orphera bootstrap` on dnf hosts redeploys a package of the same version
- `dnf install` skipped an rpm whose version and release were already installed ("Nothing to do"), so a rebuilt package at
  an unchanged `VERSION` (for example with new certificates) never reached Rocky nodes. Bootstrap now runs `dnf reinstall`
  in that case. `dpkg -i` already reinstalled, so Ubuntu nodes were not affected.

### Added — `make certs`, `make certs-server`, `make certs-clean`
- The README described `make certs` and `make certs-clean`, but the Makefile had no such targets. `make certs DOMAIN=...` makes
  a CA and a wildcard server certificate and refuses if a CA already exists; `make certs-server DOMAIN=...` reissues only the
  server pair; `make certs-clean` deletes them after you type `delete`.

### Fixed — `buildcert` no longer leaves a key that does not match its certificate
- It had no `#!` line, so `./buildcert` ran under `sh` (dash on Ubuntu), which wrote a new `server.key` and then failed on the
  signing line, leaving the old `server.crt`. It also wrote the new key before checking that `ca.crt` and `ca.key` match.
  It now runs under bash (also as `sh buildcert`), checks the CA pair first, and moves the new key and certificate into place
  only after the certificate is signed and verified, so a failure changes nothing. Takes the domain as an argument
  (default `ljalbinson.com`).

### Fixed — `orphera` runs the orchestrator `make orpheracli` installed last
- The launcher ran whichever `/usr/local/lib/orchestrator-assembly-*.jar` had the highest version, so a stray higher-numbered
  jar from an earlier `make release` silently won over a fresh build. `make orpheracli` now installs to one fixed name,
  `/usr/local/lib/orphera-orchestrator.jar`, and installs the launcher too. The old versioned jars are no longer used and
  can be deleted.

## [0.1.195] - 2026-10-19

_This release also covers 0.1.192 to 0.1.194, which were never released separately._

### Added — scalafix and wartremover in the build
- `sbt-scalafix` 0.14.9 with `.scalafix.conf` (OrganizeImports, RemoveUnused, DisableSyntax, LeakingImplicitClassVal,
  NoValInForComprehension, RedundantSyntax). `make lint` checks, `make fix` applies them and then runs `make fmt`.
  CI runs `scalafixAll --check` between compile and test.
- `sbt-wartremover` 3.6.1: casts, `return`, partial `Option`/`Try`/`Either`/collection calls and a few others are compile
  errors in main code (not tests). Generated gRPC and BuildInfo sources are excluded.
- `-Wunused:all` is on, so with `-Werror` an unused import or parameter fails the build; `sbt scalafixAll` removes most.
- `FactPrinter` and `AgeCipher` no longer use `asInstanceOf` or `List.head`, which the new checks reject.

### Security — private keys out of the repository, and no command lines in the agent's log
- `certs/ca.key`, `certs/server.key`, `certs/ca.srl` and `certs/server.csr` were committed (the README said they were ignored).
  They are no longer tracked and `certs/*.key`, `*.srl` and `*.csr` are in `.gitignore`. They remain in earlier commits, so
  any CA or server key made before this change must be replaced: `make certs`, rebuild the agent package, `orphera deploy-agent`.
- The agent no longer writes `[DEBUG-CMD]` lines: they put every command's arguments, passwords included, in the node's journal.
  Needs an agent rebuild and redeploy to take effect.

### Changed — every playbook holds its passwords as `Secret`, not `String`
- Keystone, Glance, Cinder, Nova, Neutron, Nova compute, Horizon, Galera, haproxy, WordPress and extended observability use
  `Secrets.secret(...)` and write `.reveal` where a password goes into a command or a file. No password prints by accident any more.
  `Secrets.get` (a `String`) remains for code that needs one.

### Added — `Secret`, a value that does not print (`Secret.scala`, `Secrets.secret`)
- `Secrets.secret("placement/db")` is like `Secrets.get` but returns a `Secret`: its `toString`, string interpolation and any log
  line give `<redacted>`, and the text is available only as `.reveal`, so each place a playbook puts a password into a command or a
  file is visible in the source. Forgetting `.reveal` writes `<redacted>` where the password goes, and the service refuses it,
  rather than leaking it. `placement_single_node.scala` is the pilot; the other manifests still use `Secrets.get` (a `String`).

### Changed — the WordPress and extended observability playbooks read their passwords from the secret store
- The WordPress database password (`wordpress/db`) and the MySQL exporter password (`observability/mysqld-exporter`) are
  `Secrets.get(...)` and hidden from the output. `manifests/seed_test_secrets.sh` seeds the lab's current values.
  Run end to end on a rebuilt tst7: `test_wordpress.sh` 11 passed, `test_observability_stack.sh` 13 passed, no password in either log.

### Changed — the Galera and haproxy playbooks read their passwords from the secret store
- The Galera SST password (`galera/sst`) and the clustercheck password (`galera/clustercheck`) are `Secrets.get(...)`, hidden from
  the output, and `test_vip_failover.sh` reads the clustercheck password from the store. A cluster built earlier keeps the
  old values in its database users: run `manifests/seed_test_secrets.sh` (lab) or `orphera secrets set` with the values in use
  before applying these playbooks to it. `mariadb_galera_cluster.yaml` is unchanged: it shows the YAML-only form.

### Changed — the Horizon and Nova compute playbooks read their passwords from the secret store
- Horizon's admin password and `SECRET_KEY`, and Nova compute's admin, service and RabbitMQ passwords, are
  `Secrets.get(...)`, the same names the control-plane playbooks use. These two did not hide their passwords from the
  output before; they now do.

### Changed — the Keystone, Glance, Cinder, Nova and Neutron playbooks read their passwords from the secret store
- Database, service, admin, RabbitMQ and Ceph key values are `Secrets.get("<service>/<name>")` (for example `glance/db`,
  `keystone/admin`, `cinder/ceph-key`), not literals in the manifest. A name with no value is made at random on first use,
  so a new deployment needs no setup; the lab's existing values come from `manifests/seed_test_secrets.sh`. Values are
  hidden from the output as soon as they are read.

### Added — encrypted secret store (`SecretStore.scala`, `orphera secrets ...`)
- A playbook asks for a password by name, `Secrets.get("placement/db")`. The value is made at random on first use, kept in one
  file encrypted with age (`~/.config/orphera/secrets.age`, identity in `~/.config/orphera/age.key`; `$ORPHERA_SECRETS` and
  `$ORPHERA_AGE_KEY` override), and hidden from the console and run log. `orphera secrets init|list|generate|set|get|rm`.
  Needs the `age` package on the machine that runs `orphera`. `placement_single_node.scala` is the first user; the lab's
  existing passwords are loaded with `manifests/seed_test_secrets.sh`. Phase 2 will make a secret a type that cannot be printed.

### Changed — typed configuration files in the Keystone, Glance, Cinder, Nova and Neutron playbooks
- The config files these playbooks write (`keystone.conf`, `glance-api.conf`, `cinder.conf`, `nova.conf`, and Neutron's
  `neutron.conf`, `ml2_conf.ini`, `openvswitch_agent.ini`, `dhcp_agent.ini`) are `ConfFile` values checked against
  `catalogues/<project>-2025.1-ubuntu-noble.json`, the image's own option list, when the manifest is built, and written (or
  checked with `ORPHERA_CHECK`-style `Resource.check`) from that one value. Their passwords are hidden from the output.
  `[keystone_authtoken]` and the backend groups `[ceph]` (Cinder, Glance) and `[service_user]` (Nova) are open sections.
  Not converted: Nova compute's `nova.conf` and `neutron.conf`, which take the host's name and address at run time.
- `Catalogues.load`, `ConfFile.text` (values as text, checked as the option's type reads it), and
  `manifests/slim_catalogue.py` (drops the help text from a dump: 11 MB down to 1.3 MB for six projects).

### Fixed — secrets no longer appear in check output or the run log
- A file check (`Resource.check`) no longer puts the expected text, passwords included, into the command it logs. It sends a
  SHA-256 of the whole file; only when that differs does it show a diff, with the values of secret keys replaced by
  `<redacted>` on both sides, and it says so when only a secret value differs. A `ConfFile` takes its secret keys from the
  catalogue (21 options in the Placement image) and from names such as `password`, `secret`, `token`.
- `Secrets.register` marks a value to hide. Everything received from a node passes through `Secrets.mask`, so a registered
  value echoed in a `Running: ...` line or in command output shows as `<redacted>` on the console and in the run log.
  `Resource.write` and `check` register their secret values; `placement_single_node.scala` registers its three passwords.
  Still visible: the node's own agent log (it logs every command it runs) and the arguments of a running command on the node.

### Added — check mode in `placement_single_node.scala`
- `ORPHERA_CHECK=1 orphera cluster-playbook manifests/placement_single_node.scala` changes nothing and fails with a diff if the
  `orphera-placement-api` unit on tst13 differs from what the playbook writes. Uses `Resource[Container]`.

### Changed — checked names and ports in `Container`
- `ContainerName` and `TcpPort` are opaque types (`kolla/Ids.scala`): a container name must be lowercase letters, digits and
  dashes, a port 1 to 65535, and neither can be built otherwise. `Container(name = ContainerName("nova-compute"), ...)`;
  `Port(8080, 80)` still takes plain numbers and checks them.

### Fixed — image pulls retry
- A pull from the Kolla cache or quay.io is tried three times, 15 s apart (`kolla/Pull.scala`), in every playbook that pulls
  images, and the error message now quotes podman's error instead of only pointing at the `Image(...)` values. A single
  HTTP 500 on one blob had stopped a full rebuild at the compute stage.

### Added — typed configuration files, spike (`orchestrator/.../conf/Conf.scala`)
- `Catalogue` (the options a service declares: section, name, type, default, allowed values, old names) and `ConfFile`
  (values checked against it). Unknown sections and keys, deprecated names, wrong types, out-of-range ports and values
  outside an allowed list are refused with a suggestion. A `ConfFile` is a `Resource`, so it has `write` and `check`.
  `manifests/dump_oslo_options.sh` dumps a Kolla image's options as JSON to build the real catalogue from. Not yet used by
  any manifest; `make test` runs `ConfCheck`.
- `OsloJson.load` turns that JSON into a `Catalogue` (types, defaults, allowed values, secrets, old names). `CatalogueReport`
  prints what a real dump contains.

### Changed — `placement_single_node.scala` builds placement.conf from the image's own option list
- Every section and key of `placement.conf` is checked against `catalogues/placement-api-2025.1-ubuntu-noble.json` (the
  image's `oslo-config-generator` output) when the manifest is built; an unknown key, a wrong type or a value outside an
  allowed list stops the run before any node is touched. `ORPHERA_CHECK=1` now also checks the file on the node.

### Added — `Resource` type class, prototype (`orchestrator/.../Resource.scala`)
- `Resource[A]` describes a file the playbook writes (path and exact content). `x.write` and `x.check` come from the one
  description; `check` changes nothing and fails with a diff if the node's file differs, or with MISSING if absent.
  `Container` has an instance for its systemd unit. Used by `placement_single_node.scala` check mode.

## [0.1.191] - 2026-10-14

### Added — typed Kolla containers (`orchestrator/.../kolla/Container.scala`)
- A `Container` value (name, `Image` of service, `Release`, `Base` and registry, `Mount`s with
  `ReadOnly`/`ReadWrite` access, environment, stop and start timings) renders the podman arguments,
  the systemd unit and the start-and-check task, so a wrong release, mount flag or unit name is a
  compile or construction error instead of a broken string found on a node. `ContainerCheck` covers
  the rendering. Converted so far: `placement_single_node.scala`, `glance_single_node.scala`,
  `nova_single_node.scala` (four containers), `neutron_single_node.scala` (server plus the two
  privileged agents), `keystone_single_node.scala` and `cinder_single_node.scala` (three services plus
  RabbitMQ) `horizon_single_node.scala` and `kolla_cache.scala` (a published-port registry, via the new `Network.Published` and `Port`) and `nova_compute.scala` (nova-compute and the Open vSwitch agent on each compute node; `Container` gained `wants`). To cover them `Container` gained `privileged`, `hostPid`, `capabilities`, `after`,
  `preStart`, an optional unit `description`, three more mount modes and `ExternalImage` for images
  that are not Kolla images. A failed start now also prints the last 40 journal lines.

### Added — `manifests/rebuild_openstack_from_scratch.sh` (not yet run)
- Destroys and re-creates the 13 OpenStack VMs (tst0-tst6, tst10, tst13, tst14, tst17-tst19), then runs
  the master build script; optional `--tests` and `--wordpress`. `--dry-run` prints the plan; the
  destructive run asks you to type `rebuild` unless `--yes`. Caches, tst7, tst8 and the hypervisors are
  not touched.
- Fixed: `mk-new-and-empty.sh` and `par-mk-new-and-empty.sh` still called `kvm_vm_provision.scala`, which
  was renamed to `kvm_zfs_vm_provision.scala` when the native variant was added, so they stopped at the
  first step. They now pick the ZFS or native playbook per hypervisor (dataset `tank/kvm/<hypervisor>`
  present or not; `PROVISION=zfs|native` overrides; no guess when the hypervisor does not answer).
### Fixed — Nova and Neutron register their Keystone user before the services start
- On a freshly built Keystone, `nova-conductor` (and the scheduler) authenticate as `nova` at
  startup and exited with HTTP 401 because the user was only created in the task after the start,
  so "start the Nova services" failed. `nova_single_node.scala` and `neutron_single_node.scala` now
  register in Keystone first and start the units after.
### Fixed — the `prep` step of `build_openstack_stack.sh` only touches the stack's own nodes
- `prep` ran `disable_unattended_upgrades.scala` on all 17 Ubuntu test VMs, including tst7, tst9,
  tst11 and tst12, which the stack does not create, so it failed when they were off or absent.
  The playbook now accepts `--config FILE` with an optional `nodes:` list (default unchanged when
  run on its own), and the master script passes the nodes it builds on (plus tst9 and tst11 with
  `--with-caches`).
- Preflight prints the `virsh start` command for each node that does not answer.

### Added — Galera cold start: `utils/galera-cold-start.sh`
- After a power-off of the whole fleet MariaDB is down on tst3-tst5 and every OpenStack service
  returns 500. The script reads each node's last committed position (`grastate.dat`, or
  `mariadbd --wsrep-recover` after an unclean stop), bootstraps from the node with the highest
  seqno, starts the others and waits for size 3. It restarts only the stopped nodes when part of the
  cluster is up, does nothing when Galera is healthy or not built yet, and refuses to guess when no
  node reports a position or the cluster UUIDs differ (`--bootstrap <node>` overrides, `--check`
  reports only). `build_openstack_stack.sh` runs it in its preflight. A shell script, not a playbook,
  because a playbook cannot pass one node's output to a decision about another (no capture of
  command output; `Task.SetFact` takes literals). Passed on the fleet (2026-10-13) after a clean stop and after a SIGKILL of
  all three nodes. The crash test found that systemd restarts a killed mariadb after 5 s, which raced
  with the first version and produced a split brain; the script now stops such units first (bounded),
  treats a node stuck after a failed join as down, and refuses to act on a split brain.

### Fixed — the CirrOS boot test no longer relies on the guest console for the DHCP lease
- The console cannot show the lease: CirrOS prints its network debug a second after dhcpcd starts and
  syslog never reaches the console, so a lease granted a moment later is missing. A cold-started stack
  failed the test although the guest had its address (the instance answered ARP for it and the OVS
  counters showed the DHCP exchange). `orphera-compute-api boot-test` now waits for the login prompt
  and for the Neutron port to go ACTIVE and prints `instance ready: ip=.. mac=.. network=..`;
  new `utils/check-guest-dhcp.sh` proves the lease from tst19 (the guest answers ARP for its address in
  the DHCP namespace). `test_nova_compute.sh` and `build_openstack_stack.sh` boot with `--keep`, run
  that check, then `boot-cleanup`. Needs `nova_compute.scala` re-applied to redeploy the helper.

### Changed — master build script covers all of OpenStack (steps compute to boot passed on the existing stack, 2026-10-13)
- `manifests/build_openstack_stack.sh`: new last step `boot` (downloads CirrOS if needed, boots an
  instance through the compute helper, checks the guest took its DHCP address, and
  deletes everything; `CIRROS_IMAGE=/path` avoids the download); the network create/delete check in
  `smoke` is skipped, not failed, when a flat provider network already exists; the header no longer
  says there is no compute node. Together with the earlier `compute` step the plan is now
  `prep ceph galera keystone cinder glance placement nova neutron compute horizon clients smoke boot`.
- `disable_unattended_upgrades.scala`: tst17 and tst18 added to the node list, so the `prep` step
  covers the compute nodes too.

### Added — nova-compute on tst17 and tst18 (passed 24/24, 2026-10-12)
- `manifests/nova_compute.scala`: nova-compute (Kolla image, podman/systemd) and the Neutron Open
  vSwitch agent on both compute VMs on gs2. libvirt and QEMU come from the host (apt); the
  container reaches libvirtd through /run/libvirt and shares /var/lib/nova at the same path.
  `virt_type = kvm` with `host-passthrough` (gs2 is AMD with nested KVM on); the playbook stops
  with an explanation when a node has no /dev/kvm. The second NIC `enp2s0` is turned into an
  address-less port of `br-provider`, as on tst19, with the same bridge mapping `provider`.
  Config drive is forced (no metadata agent). Per-node host name and IP are filled in at install
  time. A `nova-manage cell_v2 discover_hosts` stage on tst14 maps the new hosts, and a helper
  `orphera-compute-api` (compute-up, ovs-up, hypervisors, boot-test) is installed on each node.
- `manifests/nova_compute_teardown.scala`, `manifests/test_nova_compute.sh`: the test checks the
  units, /dev/kvm, libvirt, the NIC and bridge, both services and agents through the APIs, both
  hypervisors, and then boots a CirrOS instance (image upload, flat provider network with DHCP on
  10.10.6.0/24 pool .200-.220, config drive, console login prompt and a DHCP lease), deleting
  everything afterwards; `SKIP_BOOT=1` skips the boot.
- `manifests/build_openstack_stack.sh`: new step `compute` between `neutron` and `horizon`.
- `utils/trace-dhcp.sh`: captures DHCP on the provider NIC of tst17, tst18 and tst19 while a test
  instance boots, to show where packets get lost.
- `nova_single_node.scala`: `[glance] cafile` in nova.conf. Without it the Nova API cannot get a
  token for the Glance call during a server create (Keystone's self-signed CA is not trusted by
  that session) and returns HTTP 500 (DiscoveryFailure). Re-apply Nova once on tst14.

### Added — Neutron on tst19 (passed 18/18 on tst19, 2026-10-12)
- `manifests/neutron_single_node.scala`: neutron-server, neutron-openvswitch-agent and
  neutron-dhcp-agent from the Kolla images under podman/systemd on tst19, for flat provider
  networks only (ML2 with the openvswitch mechanism driver, no tenant networks, no L3, no
  metadata agent). Database `neutron` on Galera; its own RabbitMQ vhost/user on tst10; registered
  in Keystone (user `neutron`, service type `network`, 3 endpoints). The host runs Open vSwitch
  (apt): bridge `br-provider` with tst19's second NIC `enp2s0` as its port, mapped to the physical
  network `provider`. The playbook first removes the address, the table-2 route and the policy rule
  that provisioning had put on `enp2s0` (netplan edited, cloud-init network config disabled), so
  the VM does not need re-provisioning; tst19 stays reachable on 10.10.5.31. Agents run privileged
  with the OVS socket directory, /run/netns (shared mount) and /lib/modules.
- `manifests/neutron_teardown.scala`, `manifests/test_neutron.sh` (units, NIC has no address and is
  on br-provider, API and 401, agents alive, flat network + subnet create/delete, catalog, Nova and
  Glance still fine, idempotent re-apply).
- `nova_single_node.scala`: `[neutron]` section (service user `neutron`) in nova.conf. Re-apply
  Nova once after Neutron so a running Nova gets it.
- `config/tst19.yaml`: the second NIC is now layer 2 only (no address, gateway, table or priority),
  so a re-provisioned tst19 matches what the Neutron playbook leaves behind. `build_openstack_stack.sh`:
  new `neutron` step after `nova`; `tst19` added to the preflight and to
  `disable_unattended_upgrades.scala`; the smoke test also creates and deletes a provider network.
- Worked as written on the first run, including the idempotent re-apply: the `neutron-server`
  command, `rootwrap.conf` and the privileged agent containers needed no change. Not yet checked:
  a real instance on the provider network (needs nova-compute), and the `[neutron]` section in
  nova.conf on a running tst14 (re-apply `nova_single_node.scala`).

### Changed — Horizon opens on its default page again
- Removed the `user_home` override: with Neutron registered the Project Overview and Network pages
  load (checked in a browser), so Horizon's own default landing page works. Re-apply the playbook
  (or run `test_horizon.sh`) to take effect.

### Changed (superseded above) — Horizon lands on Project > Instances
- With Nova registered the Project dashboard works (Project > Compute checked in a browser), but its
  Overview page, which Horizon opens by default, returns a 500 (`Invalid service catalogue:
  network`) until Neutron is registered. `horizon_single_node.scala` therefore sets `user_home` to
  `/project/instances/` (replacing the earlier `/identity/` override). Drop it once Neutron exists.
  Re-apply the playbook (or run `test_horizon.sh`) to take effect.

### Added — extra NICs for new VMs (passed on tst19 with the ZFS script, 2026-10-12)
- `--config` files may list additional NICs under `nics:`, in the same shape as `nic0`
  (`config/tst19.yaml` is the specimen: `br-net106`, `10.10.6.31/24`, gateway, `table: 2`,
  `priority: 100`, `mtu: 9000`). `bridge` is required; `nic` (default enp2s0, enp3s0, ...), `mtu`,
  `ipaddress` + `cidr`/`netmask`, `gateway`, `table`, `priority` are optional. Without an
  `ipaddress` the interface is brought up with no address (layer 2 only).
- `kvm_native_vm_provision.scala` and `kvm_zfs_vm_provision.scala`: one extra `--network` argument
  per entry to `virt-install` (virtio, openvswitch virtualport, a fixed MAC derived from hostname
  and position, so it survives re-provisioning), and, when a VM has extra NICs, the cloud-init
  `network-config` switches to the netplan v2 form: nic0 as before, each extra NIC matched by
  its MAC and renamed. With `table` > 0 the subnet route and the gateway go into that table and a
  `from <address>` rule at `priority` is added, so there is no second default route in the main
  table. VMs without extra NICs get exactly the original v1 `network-config`.
- The destroy stage of both scripts now also removes Open vSwitch `vnet*` ports that have no
  device behind them. A leftover port record on another bridge made virt-install fail with
  "vnet8 is actually attached to bridge br-net105" (seen on st0 provisioning tst19).
- `ConfigYaml.scala` (orchestrator): `ExtraNic`, `VmParams.extraNics`. Needs a recompile.
- The hypervisor side of `mtu` is not set: a 9000 MTU only works if `br-net106` and its uplink
  already carry it.
- Checked on tst19 (`kvm_zfs_vm_provision.scala`): `enp1s0` 10.10.5.31/24, `enp2s0` 10.10.6.31/24
  with MTU 9000 on the fixed MAC, `from 10.10.6.31 lookup 2` rule, table 2 with the default via
  10.10.6.1. `kvm_native_vm_provision.scala` has the same code but has not been run.
- Existing VMs are untouched; adding a NIC to a defined VM means re-provisioning it (these scripts
  destroy and recreate) or `virsh attach-interface` by hand.

### Added — Nova control plane on tst14 (passed 19/19 on tst14, 2026-10-12)
- `manifests/nova_single_node.scala`: nova-api (Apache + mod_wsgi, 8774), nova-scheduler,
  nova-conductor and nova-novncproxy (6080) from the Kolla images under podman/systemd on tst14.
  Databases `nova_api`, `nova` and `nova_cell0` on Galera; its own RabbitMQ vhost `nova` and user
  `nova` on the broker Cinder runs on tst10; registered in Keystone (user `nova` in project
  `service`, service type `compute`, three endpoints). `nova-manage` runs `api_db sync`, maps cell0,
  creates cell1 and runs `db sync`, each step skipped when already done. Waits for apt to be idle
  first (see `disable_unattended_upgrades.scala`). A helper `orphera-nova-api` provides
  register/unregister/catalog/services/services-up/flavor-list/flavor-create/flavor-delete.
- `manifests/nova_teardown.scala`: Keystone cleanup, containers/units/config/state, the three
  databases and user, and the RabbitMQ vhost and user (the broker and Cinder are untouched).
- `manifests/test_nova.sh`: four units active, version document, 401 without a token, scheduler and
  conductor "up" through the API, flavor create/read/delete, cell0 and cell1 mapped, catalog,
  noVNC page, Placement and Glance reachable from tst14, idempotent re-apply; `NO_APPLY=1`.
- `build_openstack_stack.sh`: new `nova` step between `placement` and `horizon`; tst14 added to the
  preflight; the smoke test also creates and deletes a flavor. `disable_unattended_upgrades.scala`
  now includes tst14.
- No nova-compute and no Neutron: the API, scheduler and conductor work, but a server cannot be
  booted yet. Horizon's Project dashboard should now find a compute service in the catalog.
- The first run stopped on the RabbitMQ step: Orphera renders `{{...}}` in commands as a template,
  so `podman ps --format '{{.Names}}'` arrived empty on tst10. The check now filters by name
  (`--filter 'name=^rabbitmq$'`). No `{{ }}` belongs in a command unless a template is wanted.
  After that fix the playbook, the WSGI script, `api-paste.ini` and the noVNC page all worked as
  written, and the second run was idempotent.

## [0.1.184] - 2026-10-12

### Added — Horizon on tst13 (passed 7/7 on tst13, 2026-10-12)
- `manifests/horizon_single_node.scala`: the Horizon dashboard from the Kolla image under
  podman/systemd on tst13 (shared with Glance and Placement), Apache + mod_wsgi on 8080, plain
  HTTP, `http://tst13.ljalbinson.com:8080/`. No database (signed-cookie sessions), nothing to
  register in Keystone; `local_settings.py` points at Keystone over TLS with the test CA. Writes a
  small helper `orphera-horizon-check` that signs in as admin like a browser does.
- `manifests/horizon_teardown.scala`: removes the container, unit, config and helper; Glance and
  Placement are untouched.
- `manifests/test_horizon.sh`: unit active, root leads to the login page, a static file is
  served, a real admin login succeeds, the dashboard answers from scala0, Glance and Placement
  still answer, idempotent re-apply; `NO_APPLY=1`.
- `build_openstack_stack.sh`: new `horizon` step after `placement`.
- Found on the first runs: with no compute service in the Keystone catalog the Project dashboard's
  Overview panel is hidden, so Horizon's default landing page `/project/` is a 403 right after a
  successful login. `local_settings.py` therefore sets `user_home` to `/identity/` (the Identity
  dashboard needs only Keystone; checked in a browser). `/identity/projects/` is not a valid path.
  Revisit when Nova is registered, then drop the override.
- The `csrftoken`/`sessionid` cookies are explicitly non-Secure (plain HTTP), with
  `CSRF_TRUSTED_ORIGINS` set. On a login failure `orphera-horizon-check` prints the final URL, the
  cookie flags and the tail of Horizon's access and error logs.

### Added — turn off unattended upgrades (branch `no-auto-upgrades`, DRAFT, not yet run)
- Cause: on a freshly provisioned VM `unattended-upgrades` holds the dpkg lock for minutes and,
  when it finishes, needrestart restarts services including `orphera-agent`, killing the running
  task (exit 143). Seen on tst13 (302 pending packages) with the Placement re-apply and Horizon.
- `manifests/disable_unattended_upgrades.scala`: on the Ubuntu test VMs (tst0-tst7, tst9-tst13)
  writes `/etc/apt/apt.conf.d/20auto-upgrades` with both periodic settings at 0, and stops,
  disables and masks `apt-daily.timer`, `apt-daily-upgrade.timer` and `unattended-upgrades.service`
  (without interrupting an upgrade already running), then verifies. Revert steps are in its header.
- `kvm_native_vm_provision.scala` and `kvm_zfs_vm_provision.scala`: the cloud-init user-data now
  writes the same `20auto-upgrades` and masks the units on first boot, so new VMs never start one.
- `build_openstack_stack.sh`: new first step `prep` applies the playbook.

## [0.1.178] - 2026-10-11

### Changed — apt/dpkg lock wait raised from 60 s to 10 minutes
- `AptInstaller` (the agent) and `SshDeployer` now pass `-o DPkg::Lock::Timeout=600` instead of
  `60`, held in one constant in `AptInstaller`. On a freshly provisioned VM `unattended-upgrades`
  can hold the dpkg lock for several minutes (seen on tst13: an upgrade of 302 packages made a
  playbook's Install task fail with apt exit 100 after 60 s). Takes effect on a node once its agent
  is rebuilt and redeployed.

### Added — Placement on tst13 (branch `placement`; test_placement.sh passed 2026-10-11)
- `manifests/placement_single_node.scala`: placement-api from the Kolla image under podman/systemd
  on tst13 (shared with Glance; `node`/`fqdn`/`nodeIp` at the top move it), Apache + mod_wsgi on
  8780, plain HTTP. Creates the `placement` database on Galera, writes `placement.conf`, runs
  `placement-manage db sync`, registers service `placement` with three endpoints and user
  `placement` in Keystone. No Ceph, no message bus.
- `manifests/placement_teardown.scala`: unregisters from Keystone, removes the container, unit,
  config and state, drops the database; Glance on the node is untouched.
- `manifests/test_placement.sh`: unit active, version document 200, unauthenticated request 401,
  admin-token resource provider create/read/delete, catalog lists `placement`, Glance still
  answers, idempotent re-apply; `NO_APPLY=1`.
- `build_openstack_stack.sh`: new `placement` step after `glance`, plus a resource provider in
  the smoke test.
- `utils/openstack-client-setup.sh` also installs `osc-placement`, so `openstack resource provider
  list` works from scala0.
- Passed on the first run on scala0: all functional checks, and the idempotent re-apply once
  `unattended-upgrades` on tst13 had released the dpkg lock (the agent waits only 60 s; the first
  re-apply failed with apt exit 100 while an upgrade of 302 packages held it - environmental, see
  "apt/dpkg lock contention" in the README).

## [0.1.177] - 2026-10-11

### Fixed — Cinder: create a volume from a qcow2 image failed (`FailedToDropPrivileges`)
- `openstack volume create --image <qcow2 image>` went to `error`: the RBD driver can only clone
  raw images, so Cinder downloads other formats and inspects/converts them through its privsep
  helper, which cannot start in a podman container without CAP_SYS_ADMIN, CAP_NET_ADMIN and
  CAP_DAC_READ_SEARCH ("Privsep daemon failed to start"). `cinder_single_node.scala` now adds those
  capabilities to the orphera-cinder-volume unit only. Re-apply the playbook to pick it up.
  A raw image (`qemu-img convert -O raw`) skips the download and is cloned copy-on-write.
  Re-tested on scala0 (2026-10-11): the volume created from the image reached `available`.

### Added — master build script
- `manifests/build_openstack_stack.sh`: empty VMs with an agent to a working Ceph + Galera +
  Keystone + Cinder + Glance stack with the clients set up on scala0, in dependency order.
  Apply-only (no teardowns, so it cannot wipe a database), preflight that every node answers,
  an independent check after each step, `--from STEP` / `--only STEP` to resume, `--with-caches`
  for the apt and Kolla caches, `--list`, a final smoke test (real volume and image, confirmed as
  RBD images, then deleted) and a log in /tmp. Not yet run end to end.

### Added — Glance on tst13 (branch `glance`; test_glance.sh passed 11/11 on 2026-10-11)
- `manifests/glance_single_node.scala`: glance-api from the Kolla image under podman/systemd on
  tst13, plain HTTP on 9292, images stored as RBD images in a new Ceph pool `images` with a
  `client.glance` cephx user (fixed test key, same convention and cipher compromise as Cinder).
  Creates the `glance` database on Galera, writes `glance-api.conf` (multi-store `ceph:rbd`,
  `show_image_direct_url` on for later Cinder clone-from-image), runs `glance-manage db_sync`,
  and registers service `image` with three endpoints and user `glance` in Keystone. No RabbitMQ.
- `manifests/glance_teardown.scala`: unregisters from Keystone, removes the container, unit,
  config and state, drops the database, deletes the `images` pool and `client.glance`.
- `manifests/test_glance.sh`: unit active, API answers, image upload reaches `active` and exists
  as an RBD image, deletes cleanly, catalog lists `image`, idempotent re-apply; `NO_APPLY=1`.
- inventory.yaml: tst13 role comment; README fleet table and run order.
- Passed on the first run of the playbook on scala0 (after Keystone and Cinder were rebuilt
  following a Galera wipe by an earlier `test_mariadb_galera.sh` run): glance-api starts with the
  plain `glance-api` command, the RBD store works, and the second run is idempotent.
- Note: `test_mariadb_galera.sh` and `rebuild_galera_and_wordpress.sh` tear Galera down and wipe
  every database (Keystone, Cinder, Glance); rebuild order afterwards is test_keystone.sh,
  test_cinder.sh, openstack-client-setup.sh, test_glance.sh.

### Added — `orphera run --quiet`
- `orphera run <command> --nodes ... --quiet` prints only the command's own output (still
  prefixed `[node]`, so several nodes stay readable) and drops the `Running: ...` and
  `exit=0 success=true` lines. A failing command is not silent: its
  `[node] exit=N success=false` line (with any `FAILED: ...` reason) is still printed and the
  exit code is non-zero. Like `--nodes` and `--timeout` the flag is recognised anywhere in the
  arguments, so a remote command that needs its own literal `--quiet` cannot be run with it.
  Changes: `ConsoleRenderer.renderQuiet`, `Command.RunCommand.quiet`, `parseRunCommand`,
  `Orchestrator.executeCommand`, the usage text and bash completion. Built and tried on scala0
  (2026-10-11): output only, `[node]` prefixes kept, a failing command prints its exit line and
  exits non-zero.

## [0.1.173] - 2026-10-11

### Changed — test_wordpress.sh is now a non-destructive test
- The old `manifests/test_wordpress.sh` tore down and rebuilt Galera before applying WordPress,
  which wiped every database on the cluster (Keystone's and Cinder's included: Keystone then
  answered HTTP 500 and `cinder_single_node.scala` failed to register). It is now
  `manifests/rebuild_galera_and_wordpress.sh`, with a warning header. The new `test_wordpress.sh`
  applies `wordpress_site.scala` only and checks the Galera cluster and VIP, the `wordpress`
  database and user, nginx and php-fpm, and that the site answers 200 over HTTP and shows no
  database connection error; also checks idempotence. `NO_APPLY=1` checks without applying.
  Passed on the test fleet (2026-10-11).

## [0.1.172] - 2026-10-11

### Changed — test fleet roles reassigned
- tst0-tst2 Ceph only; MariaDB Galera + haproxy + keepalived moved to tst3-tst5
  (VIP unchanged); Keystone on tst6; WordPress and Prometheus/Grafana on tst7.
  `mariadb_*`, `wordpress_*`, `observability_*`, `keystone_*`, `cinder_*`, their
  tests and `inventory.yaml` are retargeted; see `docs/TESTS.md` section L for
  the move-over order. `etcd_grow_cluster.scala` is marked as not runnable
  against tst3/tst4 any more.

### Changed — `cephadm_install.scala` allows the legacy cephx cipher
- After bootstrap it runs `ceph mon set auth_allowed_ciphers aes,aes256k` (only
  on Ceph releases that have the option), so clients with older Ceph libraries,
  such as the OpenStack Kolla images, can authenticate against a patched
  (19.2.6+) cluster. A test-cluster compromise; enforce `aes256k` once the
  clients are upgraded.

### Changed — VM provisioning split into ZFS and native variants (native variant run on gs3)
- `manifests/kvm_vm_provision.scala` renamed to `kvm_zfs_vm_provision.scala` (the
  object and playbook name follow; behaviour unchanged). References in
  `kvm_vm_start.scala`, README, inventory and the CLI help were updated; older
  CHANGELOG entries keep the old name.
- New `manifests/kvm_native_vm_provision.scala`: the same playbook without ZFS.
  Each VM's files go in a plain directory, `/var/lib/libvirt/images/orphera/<vm>`
  by default (`ORPHERA_VM_DIR_ROOT` changes the parent), created with `mkdir` and
  removed with `rm -rf` on re-provisioning. Run on gs3 for tst13 (10.10.5.25): all six
  stages completed first time and the VM answered SSH ~10 s after start.

### Added — test_observability_stack.sh (ran clean, 2026-10-11)
- `manifests/test_observability_stack.sh` tests the Prometheus/Grafana stack on tst7
  end to end from the machine running it: applies `observability_stack.scala` and
  `observability_extended.scala`, then checks over HTTP that every target is up per job
  (node_exporter 7, haproxy 3, mysqld 3, prometheus 1), that real data arrives (CPU metrics
  from all 7 nodes, `wsrep_cluster_size` 3 on each Galera node, `mysql_up`, haproxy metrics),
  that Grafana is healthy and its Prometheus datasource answers a query through Grafana's own
  proxy API, and that re-applying both playbooks is idempotent. `NO_APPLY=1` checks without
  applying. (`test_observability.sh`, the run-log test, is separate and passed 26/26.)

### Fixed (symptom) — apt cache: concurrent Release-file requests make apt hang
- Cause of the hung `apt update` on tst5 and tst1: with many nodes asking for the same stale
  `InRelease` at once (any playbook installing on several nodes), apt-cacher-ng 3.7.4 logs
  `storage error [Checked size beyond EOF] ... File exists` / `Failure to move file ... out of
  the way` and drops one client's connection; apt then waits on a half-closed socket. The
  pipelining/timeout entry below did not fix it.
- Tried and reverted: `DontCacheRequested: /(In)?Release([.]gpg)?$` makes apt-cacher-ng answer
  `400 Bad Request` for every Release file, which broke `apt update` on all clients. Do not use.
- Now set: `VfileUseRangeOps: 0` in the drop-in (the documented way to stop Range-based
  revalidation of volatile files; the error is a size check during that revalidation).
  `test_apt_cache.sh` has a check that runs `apt-get update` on all ten clients at once and
  asserts none hang and no new storage errors are logged. First run with the fix (2026-10-11,
  cache freshly rebuilt): all 10 `apt update` runs completed, none hung, but the cache still
  logged 4 storage errors, so the race is not removed (test 13 passed, 1 failed). Whether the
  hang itself is gone needs repeated runs; see docs/TESTS.md K3.
- Five further rounds (Release files deleted from the cache each time, ten clients updating at
  once) all finished on all ten clients: 60 clean runs in total, where roughly 1 in 10 hung
  before. The cache still logs `Failure to move file ... File exists` (it renames the stale
  file to a name made from the current second, so same-second requests collide); apt recovers
  with the client settings, so the test now reports this as a warning.

### Fixed — apt clients could hang on a half-closed connection to the apt cache (did not help; see above)
- `apt_cache_clients.scala` now also writes `Acquire::http::Pipeline-Depth "0"`,
  `Acquire::http::Timeout "30"` and `Acquire::Retries "3"`. Seen on tst5: `apt-get update`
  stuck for 7 minutes, both sockets to tst11:3142 in CLOSE-WAIT, while the cache itself was
  healthy. Re-apply `apt_cache_clients.scala` to pick it up.

### Added — rpm package cache on tst12 (test_rpm_cache.sh 11/11)
- `manifests/rpm_cache.scala` (+ `rpm_cache_teardown.scala`, `test_rpm_cache.sh`):
  nginx caching mirror on a new node, tst12 (10.10.5.24), port 8080, for the
  Rocky and EPEL download hosts (verified HTTPS upstream; 30-day lifetime for
  files, 5 minutes for repomd.xml; 80 GB limit). `manifests/rpm_cache_clients.scala`
  writes a dnf repository file on tst8 with the cache first and the official
  Rocky host as the second baseurl, and sets the stock Rocky repo files aside
  (`*.orphera-off`; the teardown restores them). New `config/tst12.yaml` and
  inventory entry. Passed on the test fleet first time: `tree` was a MISS then a HIT in
  the cache log, and `dnf makecache` worked with nginx stopped (official-host fallback).

### Added — apt package cache on tst11 (test_apt_cache.sh 12/12)
- `manifests/apt_cache.scala` (+ `apt_cache_teardown.scala`, `test_apt_cache.sh`):
  apt-cacher-ng on a new node, tst11 (10.10.5.23), caching plain-HTTP Ubuntu
  repositories; it refreshes its own package indexes through the cache once up.
  `manifests/apt_cache_clients.scala` points apt on the Ubuntu test nodes at it
  with a proxy auto-detect script (`/usr/local/sbin/orphera-apt-proxy`), so a
  node uses the cache when it answers and fetches directly when it does not;
  HTTPS repositories are always fetched directly. New `config/tst11.yaml` (Ubuntu
  VM, 120 GB disk) and inventory entry. The test downloads a package from two
  nodes and checks the cache's own log for one fetch and two deliveries, then
  stops the cache to prove the fallback. Passed on the test fleet.

### Added — OpenStack command-line clients on scala0
- `utils/openstack-client-setup.sh`: creates a virtualenv with
  `python-openstackclient` outside the repository (`~/.venvs/openstack`), fetches
  Keystone's test CA (ssh, falling back to the TLS handshake) and writes an rc
  file (`~/.config/openstack/admin-openrc.sh`, mode 600). `source` it, then
  `openstack token issue`, `openstack volume list` and so on;
  `deactivate-openstack` undoes it. The credentials are the project's test
  values. Idempotent; checks itself by issuing a token. Passed on scala0 against the
  real fleet (needs the python3-venv package).

### Added — OpenStack Keystone, Kolla cache and Cinder from Kolla images (run on the test fleet 2026-10-10)

- `manifests/keystone_single_node.scala`: Keystone on one node (default tst7)
  using the Galera cluster behind the 10.10.5.100 VIP as its database. Runs the
  Kolla `keystone` container image under podman and a systemd unit; Orphera
  writes what kolla-ansible would (`keystone.conf`, the Apache vhost, Kolla
  `config.json`, with the WSGI script path and Apache flavour detected from the
  image), a private test CA and server certificate (reused on re-runs, CA added
  to the node's trust store), and runs `db_sync`, fernet/credential setup and
  `bootstrap` in one-off containers. API on https://<node>:5000/v3/. Confirms
  with a TLS health check and a real admin token. Written to work on Ubuntu or
  Rocky hosts. Passed on tst6 (Ubuntu): `test_keystone.sh` 11/11.
- `manifests/kolla_cache.scala` (+ `kolla_cache_teardown.scala`): a Kolla image
  cache on a new node, tst9 (10.10.5.21): the stock `registry:2` image as a
  pull-through mirror of quay.io under podman/systemd, with the Keystone image
  pre-pulled. `keystone_single_node.scala` points podman at it as a mirror and
  falls back to quay.io. New `config/tst9.yaml` (Ubuntu VM, 120 GB disk) and
  inventory entry. Passed on tst9; tst6 pulls through it.
- `manifests/cinder_single_node.scala` (+ `cinder_teardown.scala`,
  `test_cinder.sh`): Cinder on a new node, tst10 (10.10.5.22), from Kolla images
  under podman/systemd (cinder-api, cinder-scheduler, cinder-volume) with a
  RabbitMQ container, volumes stored as RBD images in the existing Ceph cluster
  (pool `volumes`, cephx user `client.cinder` with a fixed test key), database
  on Galera, identity from Keystone (the CA is taken from Keystone's TLS
  handshake). Registers the service and endpoints in Keystone through a small
  stdlib-Python helper. Keystone now serves leaf + CA (`server-chain.crt`).
  New `config/tst10.yaml` and inventory entry. Passed on tst10: `test_cinder.sh`
  12/12 (a volume becomes a real RBD image and is deleted cleanly). Note: the
  playbook runs `ceph mon set auth_allowed_ciphers aes,aes256k` because patched
  Ceph (19.2.6+) accepts only the new `aes256k` cipher by default and the Kolla
  images' Ceph 19.2.3 client only knows `aes`. This is a test-cluster
  compromise to remove once the images carry a newer client.
- `manifests/keystone_teardown.scala` and `manifests/test_keystone.sh`
  (lifecycle, independent checks, idempotence).

## [0.1.171] - 2026-10-09

### Added (branch `rocky-support`) — Rocky Linux / Red Hat-family hosts

Orphera now manages Rocky (tested on Rocky 10), and by design Alma, RHEL and
Fedora, alongside Debian/Ubuntu, with no extra command-line options: each
node's package manager and network backend are detected on the node.

- **Agent package tasks.** The agent picks apt or dnf by what the host has
  (`/usr/bin/apt-get`, else `/usr/bin/dnf`); a host with neither gets an
  immediate FAILED result. `DnfInstaller` / `RpmInstaller` implement install,
  remove, autoremove, dist-upgrade and install-package (`InstallDebPackage`
  RPC). `dist-upgrade` maps to `dnf upgrade`, `--update-cache` to a metadata
  refresh, `purge` is ignored and `name=version` becomes `name-version`. The
  process helpers moved from `AptInstaller` into `PackageSteps` unchanged.
- **RPM package.** `make rpm` builds `orphera-agent-<version>-1.noarch.rpm`
  from the same staged tree as the `.deb` (spec: `packaging/orphera-agent.spec.template`;
  needs `rpmbuild`). It requires `java-headless`, labels `run.sh` `bin_t` for
  SELinux and opens 50051/tcp in firewalld when they are present. `make release`
  now also builds it when `rpmbuild` is installed and skips it otherwise.
- **Mixed fleets.** `bootstrap`, `@bootstrap`, `teardown` and `deploy-agent`
  take the `.deb` and/or `.rpm` (`--file` picks one by extension; with no
  `--file` the newest of each is found). `bootstrap` and `teardown` ask the host
  over SSH which package manager it has; `deploy-agent` uses the node's
  reported OS. A failed package-manager probe now reports the ssh output and
  user (a missing `--ssh-user` shows as "Permission denied" for root).
- **`network-apply` on NetworkManager.** On a host with `nmcli` and no
  `networkctl` the agent backs up and restores
  `/etc/NetworkManager/system-connections/*.nmconnection`, applies with
  `nmcli connection reload` then `up` per profile. systemd-networkd hosts are
  unchanged.
- **`gen_set_dns.scala`** tells NetworkManager `dns=none`
  (`/etc/NetworkManager/conf.d/90-orphera-dns.conf`) before writing a static
  `/etc/resolv.conf` on hosts without systemd-resolved. `gen_dist_upgrade.scala`
  no longer says "apt" in its task label.
- **`kvm_vm_provision.scala`.** Derives `--os-variant` from the image name
  (`Rocky-10-...` -> `rocky10`, otherwise `ubuntu24.04`) and falls back to
  `generic` where `virt-install --osinfo list` lacks it. The define-and-start
  script runs under `set -e` (a failed virt-install used to report success).
  It passes `--machine q35`, a `virtio-scsi` controller (with the `generic`
  variant virt-install picks i440fx and an LSI controller, which a Rocky kernel
  cannot boot from) and the qemu guest-agent channel. The cloud-init template
  now also sets root's console password (same as localadmin's) and the
  `lock_passwd` typo (`oock_passwd`) is fixed.
- **Test node `tst8`** (Rocky 10, 10.10.5.20, on gs3): `config/tst8.yaml` and
  an inventory entry. Note that `config/tst*.yaml` globs now include it.
- **Tested on tst8:** provision, `@bootstrap` with the rpm, `dist-upgrade`
  (dnf), `install` and its failure exit code, `deploy-agent` upgrade from an
  rpm, `gen_set_dns`, SELinux enforcing. Ubuntu regression: cephadm
  lifecycle passes with this code. See `docs/TESTS.md` section I. Not yet
  exercised: `network-apply` on either backend since the rollback fix below,
  the firewalld rule, the manifests that still use Ubuntu package names (ceph,
  etcd, galera, observability, wordpress).

### Fixed — `network-apply` rollback never undid the change (both backends)

The backup was taken when `network-apply` ran, but the new files are pushed
(`copy` / `write-file`) before that, so the backup already contained the
change and a rollback restored the same broken config. The agent now keeps a
known-good snapshot (`/var/lib/orphera/network-backups/known-good`), taken the
first time the agent starts and refreshed after every confirmed apply, and
rolls back to that. Found on tst8 when a deliberately wrong address stayed in
place after the timeout. Needs the agent redeployed; the snapshot is taken from
whatever config exists when the new agent first starts, so make sure it is good
at that moment.

### Fixed — CLI output

- A failed RESULT's reason (`FAILED: Cannot run program ...`) is now printed
  before `exit=1 success=false`; before, a spawn failure looked like a command
  that merely returned non-zero.
- Multi-config `cluster-playbook`: the `@bootstrap` step's lines were printed
  as `[tst8] [tst8] ...`; the label is no longer repeated.

### Fixed — agent: a task that raises before sending a RESULT no longer hangs the stream

The agent ends each task's response stream only on a RESULT event, and runs
the task in a detached fiber, so an exception raised before the task had sent
one died silently in the fiber and left the orchestrator waiting forever (first
seen as the failed-`apt-get update` hang, fixed earlier for `install` and
`dist-upgrade` only). `AgentServiceImpl.streamTask` now wraps all seven
streaming handlers (install, remove, autoremove, dist-upgrade, copy-file,
install-deb, run-command): any error becomes a `FAILED` RESULT. Needs the agent
redeployed to take effect.

### Added — `pipeline` verb: a chain of verbs on each node, nodes in parallel

    orphera pipeline "install tree" "dist-upgrade" "reboot --wait" --node-groups mons

Each step is one quoted verb line (`install`, `remove`, `autoremove`,
`dist-upgrade`, `copy`, `write-file`, `network-apply`, `deploy-agent`, `run`,
`fetch`, `facts`, `version`, `uptime`, `reboot`, `shutdown`). Every node runs
the steps in order and a failed step stops that node only; the nodes run in
parallel (`--parallel N`, default min(4, nodes)). Targets must be given with
`--nodes` or `--node-groups`, and a step may not carry its own. Output is
prefixed `[node]`, saved to `.orphera-build-logs/<time>/<node>.log`, and ends
with the same summary table as multi-config `cluster-playbook`; exit 1 if any
node failed. `--quiet` shows only step headers and failures. Each step runs as
a child `orphera` process, so it is audited like a hand-typed command and
Ctrl-C kills it. Steps are validated before anything runs.

Also: a child's output that already starts with `[label]` is no longer prefixed
a second time (removes the doubled `[tst1] [tst1]` in `cluster-playbook`), and
`--quiet` now also shows `success=false` lines.

## [0.1.163] - 2026-10-08

### Fixed — ad-hoc verbs exit non-zero when a node reports failure

`install`, `remove`, `dist-upgrade`, `run`, `copy` and the other verbs that
act on nodes printed `success=false` for a failed task but still exited 0.
`ConsoleRenderer` now counts unsuccessful RESULT events and the CLI exits 1 if
any node failed, so scripts and `cluster-playbook` chains can rely on `$?`.

### Added — `@bootstrap` chain step and `manifests/gen_dist_upgrade.scala`

So one `cluster-playbook` command can take a VM from nothing to configured:
```bash
orphera cluster-playbook manifests/kvm_vm_provision.scala @bootstrap \
  manifests/gen_set_dns.scala manifests/gen_dist_upgrade.scala manifests/gen_packages.scala \
  --ssh-user localadmin --forget-host-key --config config/tst*.yaml --parallel 8 --quiet
```
- `@bootstrap` is a built-in step, not a file: it installs the agent on the config's
  `hostname` node (which must already be in `inventory.yaml`) over SSH, through the same
  code as `orphera bootstrap`. Its options are new `cluster-playbook` flags with
  `bootstrap`'s defaults: `--ssh-user` (default root), `--ssh-key`, `--file` (default:
  newest `orphera-agent_*_amd64.deb` here), `--forget-host-key`. It needs `--config`,
  works in a single run and in a parallel multi-config run, and its output goes to the
  config's prefixed output and log like a playbook's. A failure stops that config's chain.
  Any other `@name` is rejected up front.
- `manifests/gen_dist_upgrade.scala` (playbook `gen-dist-upgrade`): `apt-get update` and
  `dist-upgrade` on the config's `hostname` node, via `Task.DistUpgrade`.
- Internals: `Orchestrator.bootstrapAgent` takes an optional `onLine` sink (default
  unchanged); `Command.RunClusterPlaybook` gained a `BootstrapOpts` field.
- **Not compiled or run** (no Scala compiler here, build uses `-Werror`); shell
  completion updated and exercised against a stub.

### Added — `cluster-playbook` runs its chain for several `--config` files in parallel

`orphera cluster-playbook a.scala b.scala --config config/tst*.yaml [--parallel N] [--quiet]`
runs the whole chain (in order, stopping at the first failure) once per config, the
configs in parallel — at most `--parallel` at a time, default min(4, number of configs).
- `--config` takes several files (values run up to the next `--flag`, so a shell glob
  works; comma-separated lists and repeating the flag work too). With zero or one config
  nothing changes.
- A failure stops that config's chain only; the others carry on. Each config's output is
  prefixed `[tst3]`, appended to `.orphera-build-logs/<time>/<config>.log`, and the run
  ends with a per-config summary (status, time, where it stopped, log path, last lines
  of failed logs). Exit status is non-zero if any config failed. `--quiet` limits the
  terminal to stage headers, failures and the summary. Ctrl-C kills every child
  process tree.
- `.scala` playbooks only in this mode (a YAML playbook cannot read a config). Config
  file names (without extension) must be distinct, since they label the output.
- Each child gets `ORPHERA_RUN_TAG=<config name>`; `RunLog` and `Checkpoint` add it to
  their file names (`...-<timestamp>-<tag>.jsonl`, `...-<playbook>-<tag>.txt`) so
  parallel runs of the same playbook no longer share a file, and `--resume` resumes each
  config where it stopped. `log-summary <playbook>` still finds tagged logs. Untagged
  runs are named as before.
- Audit log records every config and the parallelism. Shell completion offers
  `--parallel`/`--quiet` and completes files after each `--config` value.
- **Not compiled or run**: this was written without access to a Scala compiler (and the
  build uses `-Werror`); only the shell completion was exercised. Needs `make` and a
  test with the playbook chain before relying on it. `bootstrap` and `dist-upgrade` are
  not playbooks, so they still need `par-mk-new-and-empty.sh`.

### Changed — `par-mk-new-and-empty.sh`: lock only the host-key removal; hypervisor lock off by default

Measured on scala0 (2026-10-08, 8 nodes): provisioning all eight VMs at once, two
per hypervisor, took 35-45 s each (a lone provision is about 33 s) with no
failures, so the per-hypervisor lock is now opt-in (`-H`; `-L` is accepted and
ignored). That alone did not help — a full run still took 16m37s — because the
whole of `bootstrap` was serialized on the `known_hosts` lock (60-90 s per node,
tst7 waited about 9.5 minutes). Only `ssh-keygen -R`, which rewrites
`~/.ssh/known_hosts`, needs the lock, so the script now runs that itself
(host read from `inventory.yaml`) under the lock and runs `bootstrap` unlocked,
without `--forget-host-key`; bootstrap's own ssh only appends the new key. A node
with no `host:` in `inventory.yaml` falls back to the old behaviour (bootstrap
`--forget-host-key`, locked). A concurrent rewrite can at worst drop another
node's just-appended key, which `accept-new` re-adds on the next connection.
Exercised against stubs only (overlapping bootstraps, locked `ssh-keygen`,
fallback); not yet run on the real nodes.

### Added — `manifests/par-mk-new-and-empty.sh`: parallel version of mk-new-and-empty.sh

Builds each node's pipeline (provision, bootstrap, DNS, dist-upgrade, packages) as
its own background job, `-j N` at a time (default 4; `-j 1` is sequential with
plain output). Options: `-f <deb>`, `-q` (banners and summary only on the
terminal), optional node arguments. Output is prefixed `[tst3]`, and every node's
full output is saved in `.orphera-build-logs/<time>/<node>.log`. A failed step
stops that node only; the summary lists status and time per node and the tail of
each failed log, and the exit code is non-zero if any node failed. Ctrl-C stops
all running nodes.
Two steps are serialized with `flock`: provisioning (one VM at a time per
hypervisor, read from the config) and `bootstrap` (it edits `~/.ssh/known_hosts`).
Launches are staggered by about a second so same-playbook run logs
(`.orphera-logs/<kind>-<playbook>-<second>.jsonl`) don't share a file. Don't combine
with `--resume`: checkpoint state is per playbook name. `mk-new-and-empty.sh`
itself is unchanged. Exercised only against a stubbed `orphera` (concurrency,
lock queueing, failure isolation, `-q`, `-j 1`, stop on TERM).

### Changed — `manifests/mk-new-and-empty.sh`

- The agent package is now the newest `orphera-agent_*_amd64.deb` in the current
  directory (or `-f <file>`), failing clearly if none exists; the old
  `orphera-agent_0.1.???_amd64.deb` glob passed the literal pattern, or several
  files, to `--file` when there was no single match.
- `bootstrap` gets `--forget-host-key`, since a rebuilt VM keeps its name but has a
  new SSH host key.
- A failing step now stops that node (the old script carried on, e.g. running
  `dist-upgrade` against a VM that never got an agent); the script moves to the
  next node, lists the failures at the end and exits non-zero.
- Optional node arguments (`3`, `tst3`) instead of always building tst0-tst7; every
  `config/<node>.yaml` is checked up front, before any VM is created.
- Timestamped step headers, printed in green when stdout is a colour-capable
  terminal (off when piped, with `NO_COLOR` set, or `TERM=dumb`). Exercised only
  against a stubbed `orphera`.

### Fixed — `cephadm_teardown.scala` removes the leftover download.ceph.com apt source

`cephadm add-repo` leaves `/etc/apt/sources.list.d/ceph.list` (and a signing key)
behind and `cephadm rm-cluster` doesn't touch them. On noble the `debian-squid`
repo has no Release file, so afterwards every `apt-get update` on that host
failed — which hung the MariaDB Galera install stage on tst0. The teardown's
cluster-removal step now deletes any `sources.list.d` file that references
`download.ceph.com`, plus the `ceph.release.gpg` key, only when such a source was
found. Installed packages are left alone. Not run against a host yet;
`cephadm_teardown.yaml` (the older YAML playbook) is unchanged.

### Fixed — agent hung when `apt-get update` failed during an install

`AptInstaller.install` chained `apt-get update` (via `runNoResult`, which raises
on a non-zero exit) before the install without catching the raise. The error died
in the detached fiber, no RESULT event was queued, and the orchestrator's stream
never ended — `Task.Install` on that node hung forever (seen on tst0 after a
`download.ceph.com/debian-squid noble` source 404'd). It now emits a FAILED
RESULT with the error, as `distUpgrade` already did. Not compiled or run here;
needs `make` and an agent redeploy on the affected nodes.

### Changed — `inventory.yaml`: tst0, tst1 and tst2 data disks use serial-based by-id paths

`osd_disks` / `zap_disks` for tst0, tst1 and tst2 now name the fixed QEMU serials
that `kvm_vm_provision.scala` assigns (`/dev/disk/by-id/scsi-0QEMU_QEMU_HARDDISK_<vm>-data1`
and `-data2`) instead of the old `drive-scsi0-0-0-N` names, which depended on
attach order. tst0 was confirmed against `ls -l /dev/disk/by-id` on the VM; tst1
and tst2 follow the same naming but must have been (re)provisioned with the
serials for the paths to exist. tst3/tst4 and the other nodes are unchanged.

### Fixed — shellcheck findings in `manifests/test_vip_failover.sh`

Three SC2029 and a set of SC2317 reports, all info-level:
- `holds_vip` no longer builds a remote `grep` command: it fetches `ip -4 addr
  show` over ssh and greps locally (also avoids `ssh | grep -q` under `pipefail`
  failing on SIGPIPE when grep exits early). Matching is now a fixed-string
  `$VIP/` instead of a regex where the dots matched anything.
- `ssh_cmd` and `vip_reachable` genuinely do send caller-built / script-constant
  strings to the node, so those carry a `shellcheck disable=SC2029` with the
  reason beside it.
- `cleanup` (trap) and the `check_moved_on` / `check_reclaimed` predicates (called
  via `"$check_fn"`) were flagged unreachable (SC2317) only because shellcheck
  can't follow indirect calls; annotated, and the predicates are now defined once
  at top level instead of three times inline.
- Dropped a stale comment saying `test_mariadb_galera.sh` still used the wrong
  SSH user.
Syntax-checked, and the new `holds_vip` logic was exercised against a stubbed
`ssh` (VIP present / absent / node down); shellcheck itself and the real drill
have not been rerun.

### Fixed — `manifests/test_mariadb_galera.sh` shellcheck SC2029

`mariadb_query` built its remote command as `sudo mariadb -N -e "$sql"`, which
expands `$sql` on the client and has the remote shell re-parse it (so a double
quote, backtick or `$` in a statement would break or expand on the node).
The SQL is now piped to `sudo mariadb -N` on stdin. Same output format (batch
mode); shellcheck is clean for that line. Not run against the cluster yet.

### Changed — `vm_packages.scala` renamed to `gen_packages.scala`

`manifests/vm_packages.scala` is now `manifests/gen_packages.scala` (object and
playbook name `gen-packages` follow, as with `gen_set_dns.scala`); earlier entries
use the new name. Run it as `orphera cluster-playbook manifests/gen_packages.scala
--config config/tst0.yaml`. Old `--resume` checkpoints for `vm-packages` don't
carry over. Not compiled or run yet.

### Fixed — agent's apt post-check executed every installed binary (halted tst0)

After an install, `AptInstaller.waitForBinariesVisible` listed each package's
files with `dpkg -L` and **ran every `/usr/bin` and `/usr/sbin` binary with no
arguments** to check it was "ready". For a package that ships `halt`,
`poweroff`, `reboot` or `shutdown` that halts or reboots the host: installing
(or even re-running install on) `molly-guard` — whose wrappers a non-SSH caller
like the agent passes straight through — halted tst0 at the end of the run,
twice (2026-10-07 13:23:29 and ~13:50 BST), with libvirt still reporting the VM
as `running`. It now only waits for the file to exist and be executable.
Affects every apt-based task, not just `gen_packages`. **Needs the agent rebuilt
and redeployed** (`make`, then `deploy-agent`); until a node runs the fixed
agent, do not install `molly-guard`, `systemd-sysv` or anything else shipping
those commands on it. Not compiled or run yet.

### Changed — `kvm_vm_start.scala` resets a "running" VM that is actually dead

libvirt reports a halted or hung guest as `running`, so the playbook used to say
"already running" and then time out on SSH (tst0, 2026-10-07: guest halted at
13:23:29 BST by something outside Orphera's audit log, still `running`). If the
domain is `running` but answers neither ping nor TCP 22 from the hypervisor for
24s, the playbook now hard-resets it (`virsh reset`) and continues to the SSH
wait. A VM it starts itself is never reset. Cause of that halt not found:
ruled out the audit log, shell history, gs1's journal and the cloud-init config.
Not compiled or run yet.

### Changed — `set_dns.scala` renamed to `gen_set_dns.scala`

`manifests/set_dns.scala` is now `manifests/gen_set_dns.scala` (object, playbook
name `gen-set-dns`, error prefix and the marker comment it writes all follow);
earlier entries below use the new name. Run it as
`orphera cluster-playbook manifests/gen_set_dns.scala --config config/dns.yaml`.
Resolv.conf files already written under the old marker
(`# Managed by Orphera (manifests/set_dns.scala)`) are still recognised when a
later run without `round_robin` restores the resolved stub. The playbook name
change means an old `--resume` checkpoint for `set-dns` won't carry over.
Not compiled or run yet.

### Changed — `gen_set_dns.scala` takes its target node from the config's `hostname`

With a per-VM config (`--config config/tst0.yaml`) the playbook ignored the file's
`hostname:` and fell through to the default list tst0-tst5, applying DNS to six
nodes. The target is now `nodes:` if present, otherwise the config's `hostname:`
(one node), and only with neither the old tst0-tst5 default. `config/dns.yaml`
(which has `nodes:`) behaves as before. Manifest only — `git pull` is enough.
Not compiled or run yet.

### Added — `manifests/gen_packages.scala`

`orphera cluster-playbook manifests/gen_packages.scala --config config/tst0.yaml`
installs `pre_basic_packages` then `basic_packages` from the per-VM config on
that VM (the config's `hostname`, as an inventory node), finally wiring up the two
lists `VmParams` has been parsing without using. Missing or empty lists are
skipped (both empty: a message and nothing else); blank and duplicate entries are
dropped, and a package in both lists is installed once, in the `pre` stage;
`apt-get update` runs once. The VM needs the Orphera agent and an `inventory.yaml`
entry first (`orphera bootstrap --forget-host-key`). `--config` is required. Not
compiled or run yet.

### Added — `manifests/kvm_vm_start.scala`

`orphera cluster-playbook manifests/kvm_vm_start.scala --config config/tst0.yaml`
starts the VM named by the config's `hostname` on the hypervisor named by its
`hypervisor`, using the same per-VM config file as `kvm_vm_provision.scala`
(also reads `nic0.ipaddress` to wait for SSH). `--config` is required, with no
hardcoded fallbacks. Idempotent: running is left alone, paused is resumed,
shut-off is started, an undefined domain fails (it never creates one). Waits up
to 180s for SSH on the guest. The counterpart to `orphera shutdown` for VMs.
Not compiled or run yet.

### Added — `orphera shutdown`

`orphera shutdown --nodes tst0,tst1 --yes [--delay 5]` (or `--node-groups`)
powers the hosts off. New `TriggerShutdown`/`ShutdownRequest`/`ShutdownAck` RPC;
the agent's `Shutdowner` runs `shutdown -P now` after the delay, detached via
`systemd-run` (same approach as `Rebooter`). Unlike most verbs it never defaults
to "all nodes": it refuses without explicit targets and without `--yes`, since
there is no remote way to power a host back on. Success means the agent
acknowledged scheduling it, not that the host has gone down. Audit-logged;
usage, completion and README updated. Needs `make orpheracli` **and** an agent
redeploy (protocol change). Not compiled or run yet. No playbook `shutdown:`
task yet.

### Changed — `kvm_vm_provision.scala` sets the VM to autostart

After `virt-install` the same task runs `virsh --connect qemu:///system
autostart <vm>`, so a VM this playbook creates comes back on its own when the
hypervisor reboots. Idempotent (re-running on an existing domain is harmless; the
playbook already destroys and recreates it). Applies to newly provisioned VMs; an
existing one needs `virsh autostart <vm>` once by hand. Not compiled or run yet.

## [v0.1.156] - 2026-10-06 — development baseline

First git tag (`v0.1.156`, commit `3dfb438`, matching `VERSION` 0.1.156 and
`orphera-agent_0.1.156_amd64.deb`). Marks a state that compiles; everything
listed below up to the previous entry was included in it, and not every item
has been exercised on real nodes (those say "not compiled or run yet", which
predates this tag compiling). Later changes go under [Unreleased] until the next
tag.

### Changed — `kvm_vm_provision.scala` gives every disk a fixed QEMU serial

`virt-install` now passes `serial=<vm>-root`, `<vm>-swap` and `<vm>-dataN` for
each disk, so the guest's `/dev/disk/by-id/scsi-0QEMU_QEMU_HARDDISK_<serial>`
path is chosen by us rather than derived from libvirt's slot-dependent
`drive-scsi0-0-0-N` alias (which moved once already). The final message prints
the by-id path of each extra data disk, ready for `osd_disks`/`zap_disks` in
`inventory.yaml`. Applies to newly provisioned VMs only. The exact by-id prefix
is not yet confirmed on real hardware — check once with `ls -l /dev/disk/by-id/`.
Not compiled or run yet.

### Fixed — `kvm_vm_provision.scala` ignored `disks:` from `--config`

The extra disks listed under `disks:` (e.g. `- disk: 128`) were parsed but never
created, so tst0 came up with only the root disk and swap. Each entry (a size in
GB) now becomes a blank qcow2 `<vm>-dataN.qcow2` in the VM's directory, created
in the stage-vm-image step and attached by `virt-install` after the swap disk.
A non-numeric or non-positive entry fails the run up front. `pdisks:` is still
not implemented and now prints a warning instead of being silently ignored.
Applies on the next provision; an already-built VM is not changed. Not compiled
or run yet.

### Changed — `VmConfigYaml` renamed to `ConfigYaml`

`VmConfigYaml.scala` / `object VmConfigYaml` is now `ConfigYaml`, since `--config`
is no longer VM-specific. Same API (`ConfigYaml.fromEnv()`, `load`); the
`VmParams`/`Nic0` case classes keep their names. References in the CLI help,
`kvm_vm_provision.scala`, README and earlier changelog entries were updated to
match. Manifests that call `VmConfigYaml.fromEnv()` must switch to
`ConfigYaml.fromEnv()`. Not compiled yet.

### Fixed — `--config` rejected `disks: [- disk: 128]`

`ConfigYaml` decoded `disks`/`pdisks` as `List[String]`, so a real config
with `disks: [{disk: 128}]` failed with "wrong type, expecting string". Entries
may now be a string, a number, or a one-key map (`disk: 128`), each reduced to
its value as a string. Needs `make orpheracli`. Not compiled or run yet.

### Added — `--node-groups` targeting

`--node-groups g1,g2` on any command that takes `--nodes`: expands to the
members of those `inventory.yaml` `groups:` entries (union with any `--nodes`,
duplicates dropped). Unknown group names fail with the list of defined groups.
Implemented as an argument rewrite in `Cli.expandNodeGroups` ahead of the
per-command parsers, so no command needed changes and the audit log shows the
expanded node list. Usage text, README and bash completion (group names read
from `inventory.yaml`) updated. Not compiled or run yet.

### Added — `orphera bootstrap --forget-host-key`

Opt-in flag that runs `ssh-keygen -R <host>` locally before bootstrap's first
SSH step, so a rebuilt VM (new host keys, reused IP/hostname) no longer fails
with "REMOTE HOST IDENTIFICATION HAS CHANGED". Without the flag a changed key
still stops the bootstrap. Recorded in the audit log as `forget_host_key`;
added to the completion file, usage text, and `kvm_vm_provision.scala`'s closing
message. Not compiled or run yet.

### Changed — `bootstrap` installs a JRE first

`orphera bootstrap` now runs `apt-get update` and `apt-get install -y
default-jre` over SSH before copying the agent `.deb` over and `dpkg -i`-ing
it. The package `Depends:` on `default-jre-headless (>= 17)`, but `dpkg -i`
doesn't resolve dependencies, so on a fresh node with no Java it left the
agent unconfigured. Both steps use the agent's apt options with
`DEBIAN_FRONTEND=noninteractive` (`SshDeployer.aptGet`); a failure at either
aborts that node's bootstrap before anything is pushed. Not compiled or run
yet.

### Fixed — `manifests/gen_set_dns.scala` treated a masked systemd-resolved as present

The per-node detection added for tst0 used `systemctl cat systemd-resolved.service`,
which succeeds for a *masked* unit. On gs2 and gs3 (resolved masked, static
resolv.conf) the script took the resolved path and failed at the restart
("Unit systemd-resolved.service is masked"); gs1, with resolved running,
worked. Detection now checks `systemctl show -p LoadState --value`, which is
`loaded` only for a usable unit (`masked`/`not-found` otherwise), so masked and
missing both take the static-resolv.conf path. Not compiled or run yet.

### Fixed — `manifests/gen_set_dns.scala` failed on nodes without systemd-resolved

A real run against tst0 failed at `systemctl restart systemd-resolved`
("Unit systemd-resolved.service not found"): tst0 has a plain static
`/etc/resolv.conf`, networkd only, and no resolved — the playbook had assumed
resolved everywhere. The apply step is now one script that detects resolved
per node at run time: with resolved it writes the drop-in and restarts it as
before; without it, it writes a static `/etc/resolv.conf` (same Orphera marker
line, `options rotate` only under `round_robin`) and checks it. WriteFile can't
express this since the target file depends on the node, so the script does its
own temp-file-then-rename write. `round_robin` is unchanged in effect (static
file always, drop-in removed). Verified only by rendering the generated shell
and running it locally against a scratch path, not by compiling or running the
playbook.

### Added — `dist-upgrade` CLI command

`orphera dist-upgrade [--nodes host1,host2] [--no-update-cache]` runs the new
`RunDistUpgrade` RPC (`apt-get update` unless `--no-update-cache`, then
`dist-upgrade`, keeping existing config files) on the targeted nodes in
parallel, same shape as `autoremove`. Unlike `install`'s opt-in
`--update-cache`, the update defaults on, matching `Task.DistUpgrade`. Audited
like the other mutating package commands (`dist-upgrade` with `nodes` and
`update_cache` fields), and added to bash completion. Not compiled or run yet.

### Added — `round_robin` option for `manifests/gen_set_dns.scala`

`round_robin: true` in the `--config` file (default `false`) spreads DNS
queries across the configured servers. systemd-resolved, which the playbook
normally drives via a drop-in, has no rotate option — it sticks to one server
and only fails over — so this mode writes a static `/etc/resolv.conf`
(replacing the resolved stub symlink) with glibc's `options rotate` instead,
and removes the resolved drop-in. Requires 2-3 `dns_servers` (glibc ignores
anything beyond 3; the playbook rejects other counts rather than silently
dropping some). Running again without `round_robin` restores the stub symlink,
but only when the file still carries Orphera's marker line — a hand-written
resolv.conf is left alone. Trade-off: on those nodes resolved's stub, caching
and per-link DNS are bypassed for anything reading resolv.conf.
Not compiled or run yet.

### Added — `dist_upgrade` task (`Task.DistUpgrade`)

A native `apt-get dist-upgrade` task, for playbooks that previously had to
hand-roll it through `run_command` (with `DEBIAN_FRONTEND`, apt lock timeout
and conffile options all spelled out per use — see the old
`manifests/apt-dist-upgrade.yaml`). `Task.DistUpgrade(updateCache = true)` /
YAML `dist_upgrade:` with `update_cache:` (default `true`). New `DistUpgrade`
proto message and `RunDistUpgrade` RPC; agent side is `AptInstaller.distUpgrade`
(runs `apt-get update` first when `updateCache`, then `dist-upgrade -y` with
`--force-confdef`/`--force-confold`). A failed update emits a FAILED result
event rather than raising inside the detached fiber — raising there would
leave the orchestrator's stream (which only ends on a RESULT) hanging.
Also exposed as the `dist-upgrade` CLI command (see below). Requires rebuilding and
redeploying the agent, since the new RPC is agent-side.

### Added — `--config` (per-VM YAML config files for `.scala` scripts)

A new `playbook`/`cluster-playbook` flag, `--config <path>`, for
reading real per-VM sizing/network values into a `.scala` script
instead of hardcoding one Scala val per field per VM. Motivated by
`manifests/kvm_vm_provision.scala`, which previously hardcoded
memory/cpu/disk/nic values and, once a real `testvm0-config.yml` was
seen, turned out to not match it in several places (wrong NIC name,
placeholder sizing).

New file `ConfigYaml.scala` parses the kayobe/kttb-virt-ansible
project's own per-VM config file shape (`params:` block with
`hypervisor`/`hostname`/`disk`/`memory`/`cpu`/`dns1`/`dns2`/
`host_type`/a nested `nic0:`/etc. — not a format invented here) into
`VmParams`/`Nic0` case classes. `ConfigYaml.fromEnv()` is what a
script calls — reads the path from `ORPHERA_CONFIG`, the same
environment-variable hand-off `--resume`/`ORPHERA_RESUME` already uses,
since `IOApp.Simple`'s `run: IO[Unit]` has no access to process args at
all.

`Command.RunPlaybook`/`RunClusterPlaybook` (`Cli.scala`) both gained a
`config: Option[String]` field; `Main.scala`'s `runScalaPlaybookScript`
sets `ORPHERA_CONFIG` on the subprocess when given. Has no effect on
a `.yaml` file or a compiled, registered playbook — neither has any
code of its own that could read the env var — `orphera` prints a note
rather than silently ignoring the flag in that case.

`kvm_vm_provision.scala` is now wired to `ConfigYaml.fromEnv()` end
to end — `hypervisorNode`, `vmName`, `baseImage`, `diskSizeGB`,
`memoryMB`, `vcpus`, `nicName`, `nicBridge`, `cpuMode`, `domainname`,
`dns1`/`dns2`, `ipAddress`, `gateway`, and `netmask` all read from the
parsed config when `--config` is given, falling back to testvm0's
own prior hardcoded values otherwise. `pre_basic_packages`/
`basic_packages` are parsed but still not wired in anywhere — no
command-execution primitive reaches an uninventoried node yet.

### Fixed — `confirm-vm-reachable` (`manifests/kvm_vm_provision.scala`) failing for three separate, real reasons

Three distinct bugs, each found by a real run against `gs1`/`testvm0`,
each of which independently made this stage fail:

1. **30s timeout too tight.** A real fresh boot's sshd started
   listening ~11s after boot, but the first successful pubkey auth
   didn't happen until ~47s after THAT (~60s after boot) —
   cloud-init's users/ssh module writes `authorized_keys` late in its
   "Final" stage, well after sshd itself comes up. Bumped
   `HealthCheck.Ssh`'s `timeoutSeconds` from 30 to 180.
2. **Stale host key on the IP, mistaken for a timeout.** `testvm0` is
   destroyed and recreated at the same IP across runs by design, and
   cloud-init regenerates a fresh host key every boot. Once the
   operator's own `known_hosts` recorded a key for that IP from an
   earlier run, every later run hit "REMOTE HOST IDENTIFICATION HAS
   CHANGED" / "Host key verification failed" on every poll —
   `SshDeployer.checkAlive`'s `.attempt.map(_.isRight)` swallowed the
   real reason, so it looked identical to a plain timeout. Fixed by
   adding a `persistHostKey` flag to `SshDeployer.sshRun`/
   `sshBaseArgs`; `checkAlive` now passes `persistHostKey = false`,
   adding `-o UserKnownHostsFile=/dev/null` for this one path — correct
   specifically because `HealthCheck.Ssh` exists only for a VM whose
   host key is expected to change across runs. `bootstrap`/`teardown`/
   `scp` (real, persistent nodes) are unaffected. `checkAlive` also no
   longer discards the real per-poll ssh output — it's now printed on
   each failed poll instead of silently swallowed.
3. **Hardcoded, stale authorized key.** `CloudConfigTemplate`'s
   cloud-init user-data baked in one hardcoded public-key literal
   (from an unrelated, long-gone machine) as the sole key in
   `testvm0`'s `authorized_keys`, regardless of what identity the
   operator/orphera actually connects with — so pubkey auth could fail
   even with the above two fixed. `kvm_vm_provision.scala` now reads a
   real public key off disk at provision time
   (`ORPHERA_SSH_PUBKEY_PATH` env var, else `~/.ssh/id_ed25519.pub`,
   else `~/.ssh/id_rsa.pub`) instead, throwing loudly if none is found
   rather than silently falling back to the stale key.

### Added — `HealthCheck.Ssh` (SSH-based health check)

A new `wait_for` health check variant that polls a node over plain SSH
(`SshDeployer.checkAlive`) instead of Orphera's own agent RPC. Added
after a real run of `manifests/kvm_vm_provision.scala` showed its
`confirm-vm-reachable` stage timing out every time: that stage used
`HealthCheck.Command`, which goes through `NodeClient` to an Orphera
agent — but a brand-new VM provisioned by that same playbook can't
possibly be running the agent yet, since cloud-init never installs
one. SSH (via the VM's own `ssh_authorized_keys`, set by cloud-init) is
the one thing a stock cloud image guarantees is reachable once boot
finishes, so it's the only honest liveness probe at that point in a
VM's life. Deliberately does not install or start the agent itself —
that stays a separate, explicit `orphera bootstrap` step run by hand
afterward.

Takes its own `host` directly rather than an `on_node:` resolved
through inventory.yaml like every other HealthCheck variant — on
further request, since the whole point is checking a VM nothing has
registered anywhere yet; requiring it to already be in inventory.yaml
first would have defeated that. `onNode` is kept as a display-only
label. A synthetic, never-looked-up `Node(onNode, host)` is built on
the fly in `ClusterPlaybookRunner.waitForHealthy` so the existing
`pollHealthy`/`checkOnce`/`SshDeployer.checkAlive` plumbing — all
written in terms of `Node` — didn't need its own parallel path. YAML:
`ssh_user:` (required to select this variant) + `host:` (required),
optional `ssh_key_path:`/`remote_command:` (default `"true"`).

### Added — `write-file` CLI command

A direct CLI command for the `write_file` task added just before this:
`orphera write-file <remote-path> (--content <string> | --content-file <local-path>) [--owner] [--group] [--mode] [--nodes ...]`.
Same dispatch-path shape as every other direct command (`Command.WriteFile`
in `Cli.scala` -> `Orchestrator.writeFile` -> `NodeClient.copyBytes`, the
same underlying call `copy` and `distribute_file` already use). Audited
like `copy` (mutating) — but the audit trail deliberately records only
whether the content came from `--content` or `--content-file` and the
file path in the latter case, never the literal string itself, since a
written string could easily be a credential.

### Added — `write_file` task (`Task.WriteFile` / YAML `write_file`)

A new base operation: writes a literal string straight to a file on
the agent, with no local source file required on the orchestrator's
own disk first — unlike `copy`, whose `src` has to name a file that
already exists there. Needed because most manifests in `manifests/`
currently reach for a `run_command` shell heredoc
(`cat > file <<EOF ... EOF`) every time they want to push an
already-in-hand string (a rendered config block, a small generated
script) rather than something that belongs in its own template file —
`haproxy.cfg`'s BEGIN/END-marker blocks, `prometheus.yml`, `grafana.ini`'s
appended credentials section, and `/etc/prometheus-mysqld-exporter.cnf`
are all examples of exactly that pattern across
`observability_stack.scala`/`observability_extended.scala`.

No new agent-side capability or proto change was needed — it reuses
the same `CopyFile` RPC / `FileChunk` streaming `copy` already uses
(`NodeClient.copyBytes`, which `Task.DistributeFile` already calls
too), just with the bytes coming from a rendered Scala/YAML string
instead of a node-to-node file fetch or a local path. `content` is
Mustache-rendered first if it contains `{{...}}`, the same treatment
`set_fact`/`debug`/`run_command` tokens already get, so
`{{nodes.tst0.cluster_ip}}`-style cross-node references work here too.

Not yet used by any manifest in this repo — existing heredoc-based
scripts weren't converted over as part of this change, to keep it a
pure addition rather than a mixed add-and-refactor commit.

### Added — `manifests/ceph_observability.scala` and `manifests/ceph_performance_test.scala`

Drafted, not yet run against real infrastructure.

`ceph_observability.scala` wires Ceph's own metrics into the existing
tst6 Prometheus/Grafana stack (`observability_stack.scala` /
`observability_extended.scala`) rather than standing up a second
monitoring stack: enables ceph mgr's built-in `prometheus` module
(`:9283/metrics`, no separate exporter package needed) on tst0, adds it
as a fifth Prometheus scrape job (14 targets total), and points at
Grafana dashboard ID 2842 ("Ceph - Cluster") to import. Flags the mgr
host's metrics-port reachability and single-mgr-no-failover assumptions
as unconfirmed, same as every other new mechanism in this project on
first draft.

`ceph_performance_test.scala` is an Orphera-playbook equivalent of the
existing `cephperf.sh`/`cephadmperf.sh` scripts — same `rados bench`
write/seq/rand workload against a disposable pool, but with real
timeouts, a check that each pass's "Bandwidth (MB/sec):" summary line
actually appeared (not just that the command exited 0), and automatic
pool cleanup so repeated runs start clean. Deliberately RADOS-only for
this first pass — no `rbd bench`/kernel client pass, which would need
host-level `ceph-common` and keyring assumptions this file doesn't make.
Meant to be run alongside `ceph_observability.scala` so the load shows
up as a real spike in Grafana.

### Confirmed — both observability manifests now run clean end-to-end on genuinely fresh VMs

After today's six real-run fixes across `observability_stack.scala` and
`observability_extended.scala` (Grafana's brute-force lockout, two
separate Grafana first-boot races, a wrong health-check address, the
mysqld_exporter config mechanism, and its package's own start-limit
lockout), a full run from genuinely fresh VMs — not `--resume`, not a
VM with leftover state from a prior run — passed every stage in both
files on the first try. Final state: 13 Prometheus targets (6
node_exporter, 3 haproxy, 3 mysqld_exporter, Prometheus itself), all
up, Grafana wired to the Prometheus datasource with no lockout.

Also worth remembering from today, not a code fix: most of the "still
failing after a rebuild" confusion mid-session turned out to be tst6's
`/var/lib/grafana/grafana.db` surviving from a much earlier run despite
repeated "virgin VM" claims — worth checking whatever rebuild process
is in use actually reaches every node's stateful service data, not just
reprovisions the OS.

### Fixed — `manifests/observability_extended.scala`: mysqld_exporter blocked by its own package's auto-start failures

Real run, genuinely fresh package install this time (earlier passing
runs had the package already installed from prior testing, which
skipped this entirely): `systemctl restart` reported success, but
journalctl kept showing the OLD "no user specified in section or
parent" / ".my.cnf" error the config-mechanism fix was supposed to have
already resolved — even though the files this script writes were
confirmed correct (`systemctl show`'s `ExecStart` had the right
`--config.my-cnf` flag).

Root cause: the package's own postinst auto-starts the service with no
config present yet (the same auto-start-on-install gotcha already
documented for nginx/php8.3-fpm/node_exporter/grafana-server elsewhere
in this project), and the binary's immediate exit plus systemd's
`Restart=on-failure` burns through the default 5-failures-per-10s
start-limit-burst in under a second, all before this script even runs.
This script's own `systemctl restart` afterward was then silently
blocked by that still-active rate limit ("Start request repeated too
quickly") and never actually launched the process with the correct
config — confirmed by `systemctl reset-failed` + a fresh restart
working immediately.

Fixed by running `systemctl reset-failed prometheus-mysqld-exporter`
immediately before enabling/restarting, so this script's own restart
is never starting from a rate-limited state it didn't create.

### Fixed — `manifests/observability_stack.scala`: compile error from a backslash line-continuation

The previous fix's `if` condition used a trailing `\` to continue onto a
second line inside a `s"""..."""` stripMargin string. Real compile
error: Scala 3 rejects a `\` immediately before a newline inside a
triple-quoted string as an invalid escape sequence — true even though
triple-quoted literals otherwise ignore backslash escapes entirely (the
established convention elsewhere in this project). Fixed by putting the
whole condition on one line instead of relying on shell line
continuation.

### Fixed — `manifests/observability_stack.scala`: a second Grafana race, uncovered by fixing the first one

Real run on a genuinely fresh `grafana.db`: no lockout this time, but
the one-shot datasource check still failed on its single attempt.
Confirmed via journalctl: `/api/health` can return success before
Grafana's own admin-account bootstrap migration ("Created default
admin") actually finishes — the retried auth check removed in the
lockout fix had been accidentally covering for this underlying race all
along. Fixed by having `provisionDatasourceScript`'s own poll (which
never authenticates, so it's safe to retry) wait for both `/api/health`
AND a "Created default admin" journal line before declaring ready, so
the one real login attempt downstream is no longer a coin flip.

Noted limitation: on a VM whose `grafana.db` survived from an earlier
run instead of a true fresh install, "Created default admin" is already
in journal history from that earlier run, so this check can't
distinguish "stale admin, wrong password" from "fresh admin, right
password". Hit for real mid-session: a "virgin" VM rebuild left tst6's
`/var/lib/grafana/grafana.db` intact from the previous day, so today's
credential fix never got the chance to apply until the file was wiped
by hand. Worth checking that a VM rebuild actually clears stateful
service data directories, not just reprovisions the OS.

### Fixed — `manifests/observability_extended.scala`: mysqld_exporter startup race on tst0

Real run, after the `--config.my-cnf` fix above: tst1/tst2 both passed
cleanly, tst0 alone failed with the same config. `journalctl` showed the
service was genuinely healthy (`Listening on [::]:9104`, scrapers
enabled, no errors) — a timing race, not a real fault: `systemctl
restart` returns once the process starts, but the exporter's own HTTP
listener binds a moment after that, the same class of race already
fixed for php-fpm/nginx and prometheus-node-exporter/grafana-server
elsewhere in this project, just not yet guarded here. Fixed with the
same short bounded poll (10 attempts, 1s apart) rather than a fixed
sleep — mysqld_exporter's scrape is synchronous per-request, so
`mysql_up` appears on the very first request once the listener is
actually up.

### Fixed — `manifests/observability_extended.scala`: mysqld_exporter never reads DATA_SOURCE_NAME on Ubuntu 24.04

Real run, after the haproxy fix above: `prometheus-mysqld-exporter`
reported active, but `/metrics` never showed `mysql_up`. Confirmed via
`journalctl -u prometheus-mysqld-exporter`: Ubuntu 24.04's packaged
exporter (0.15.0) doesn't read `DATA_SOURCE_NAME` at all — the flagged,
unconfirmed assumption in this file's header comment, now confirmed
wrong (`no user specified in section or parent` / `Error parsing host
config file=.my.cnf err="no configuration found"`). Its only
connection-config mechanism is `--config.my-cnf=<path>`, an ini-style
`[client]` file. The `EnvironmentFile`/`ARGS` mechanism itself, also
flagged as an assumption, was confirmed correct via a real
`systemctl cat` — only the variable inside that file was wrong.

Fixed by writing `/etc/prometheus-mysqld-exporter.cnf` (`0640
root:prometheus`, deliberately not under `/etc/prometheus/`, which only
exists on tst6) with a `[client]` section, and setting
`ARGS=--config.my-cnf=/etc/prometheus-mysqld-exporter.cnf` instead.

Also confirmed correct on this same run: haproxy's native Prometheus
exporter module IS compiled into Ubuntu 24.04's packaged haproxy
2.8 — the other flagged assumption in this file, no longer unconfirmed.

### Fixed — `manifests/observability_extended.scala`: haproxy metrics check probed the wrong address

Real run, first stage, all three nodes: `haproxy -c` passed and the
service restarted active, but the health check still failed —
`:8404/metrics didn't return haproxy_* series`. Not a race, a genuine
bug: the frontend is deliberately bound to `{{cluster_ip}}` specifically
(not the wildcard, per this file's own header comment on the colocated-
services bind lesson), but the check itself curled `localhost:8404` —
loopback, a different address than the one haproxy is actually
listening on. Fixed by curling `{{cluster_ip}}:8404` instead, matching
the bind.

### Noted — `--resume` checkpoints don't know their own script changed

Real-run discovery while rolling out the Grafana credential fix below: a
`--resume`'d run skips any task already checkpointed successful, with no
way to tell that the task's own script content changed since that
checkpoint was written. Concretely: `install-grafana`'s
`provisionDatasourceScript` task had already succeeded (and been
checkpointed) using the OLD script, before the `grafana.ini`
`admin_user`/`admin_password` fix below existed — so resuming after
pulling the fix skipped straight past it, and the new credential lines
never actually landed on the real VM. Not a bug in the fix itself, but a
sharp edge in `--resume` worth knowing: after changing a task that's
already checkpointed, either don't use `--resume` (rerun clean on a
genuinely fresh VM) or apply that one task's change by hand before
resuming, same as this project's own manual workaround for it. No code
change proposed for this yet — flagging it here since it's the kind of
gotcha worth not re-discovering blind next time.

### Fixed — `manifests/observability_stack.scala`: Grafana locked itself out of its own admin account on a real fresh-install run

After the earlier Grafana-startup poll fix (see entry further below), a
second real failure surfaced one stage later: `confirm-observability-healthy`
timed out at 60s even though Prometheus and Grafana were both genuinely
healthy.

Root cause, confirmed via real `journalctl -u grafana-server` output: the
health check polled `/api/datasources` with basic auth (`admin`/`admin`)
every 5 seconds as part of the retried script. A single early failure —
whether from the assumed-but-unconfirmed default credential being wrong,
or a startup race on a brand-new `grafana.db` — was enough to trigger
Grafana's own brute-force login protection ("too many consecutive
incorrect login attempts... login for user temporarily blocked"), which
then rejected every subsequent attempt regardless of correctness, for
several minutes — well past the stage's own 60s timeout. The repeated
polling was the amplifier, not a one-off flake.

Fixed two ways:
- `admin_user`/`admin_password` are now set explicitly in `grafana.ini`
  (idempotently, via the same BEGIN/END-marker `sed` pattern
  `observability_extended.scala` uses for haproxy.cfg) before
  grafana-server's first-ever start, replacing the undocumented-default
  assumption with a deterministic value — only effective on a true
  first start, same real Grafana behavior noted inline.
- The authenticated `/api/datasources` check was moved out of the
  retried health-check script entirely, into its own one-shot task that
  runs once after `confirm-observability-healthy`'s `waitFor` already
  passed. It can no longer hammer Grafana's login endpoint into locking
  itself out, and a genuinely wrong credential now surfaces as one clean
  401 instead of a masked, several-minutes-long self-inflicted lockout.
- Unblocking an already-locked real VM needs `grafana-cli
  admin reset-admin-password <password>` (operates on the DB directly,
  bypassing the login-API lockout) — config changes alone don't
  retroactively fix an admin account that already exists.

### Added — `manifests/observability_extended.scala`: haproxy + Galera metrics, closing the gap observability_stack.scala left open

Picks up the two scrape targets `observability_stack.scala`'s own header
comment explicitly scoped out of the first pass. Assumes that file is
already applied and healthy; does not repeat its stages.

- haproxy's own native Prometheus-format stats endpoint
  (`http-request use-service prometheus-exporter`), added via a small,
  idempotently-appended frontend block (BEGIN/END marker comments, `sed`
  to remove any previous block before every append) on tst0/tst1/tst2 —
  appended rather than a full rewrite, since this file doesn't own
  `mariadb_haproxy_keepalived.scala`'s haproxy.cfg and can't risk
  clobbering it. Bound to `{{cluster_ip}}:8404` specifically, not the
  wildcard — the same colocated-services bind lesson
  `mariadb_haproxy_keepalived.scala` already paid for once.
- `prometheus-mysqld-exporter` on tst0/tst1/tst2, one per node, each
  reading its own local mysqld over a unix socket for Galera/wsrep
  metrics — a dedicated, minimally-privileged `exporter`@`localhost`
  MariaDB user (PROCESS + REPLICATION CLIENT + SELECT, mysqld_exporter's
  own recommended grant set), created once on tst0 and replicated to
  tst1/tst2 by Galera, same pattern as `clustercheckUser`.
- `prometheus.yml` on tst6 rewritten with two new scrape jobs (`haproxy`,
  `mysqld`) alongside the existing `node_exporter`/`prometheus` jobs —
  13 targets total once this file is applied.
- Two flagged, unconfirmed assumptions (consistent with this project's
  practice of calling out guesses rather than hiding them): that Ubuntu's
  packaged haproxy 2.8 has the `prometheus-exporter` service compiled in
  (should fail loudly via `haproxy -c` if not), and that the Debian
  `prometheus-mysqld-exporter` package reads its connection string from
  `/etc/default/prometheus-mysqld-exporter`'s `DATA_SOURCE_NAME` (should
  fail loudly via the exporter not starting, or `/metrics` missing
  `mysql_up`, if not).
- **Not yet run.**

### Added — `manifests/observability_stack.scala`: Prometheus + Grafana scraping the whole fleet (tst0-tst6)

Seventh infrastructure exercise, and a genuinely different category from
everything before it: not another clustered service or an app on top of
one, but a cross-cutting exercise reaching across the entire fleet
(tst0-tst5 — six nodes spanning three unrelated earlier exercises) at
once, rather than building one more self-contained thing. New node tst6
(`10.10.5.18`, added to `inventory.yaml`) hosts Prometheus + Grafana,
deliberately not reusing tst5 — losing tst5 would mean losing WordPress
AND the ability to see anything was wrong, at the same time.

- `prometheus-node-exporter` installed on all six existing nodes
  (tst0-tst5) via plain apt — the one exercise so far needing zero
  per-node customization (no IPs, priorities, or peer lists to template
  in; it just exports whatever it finds on that host).
- Prometheus's `scrape_configs` built from the real inventory data (the
  same `{{nodes.<name>.cluster_ip}}` templating every other manifest in
  this project uses), looped across all six targets plus itself.
- **A genuinely new technique for this project**: Grafana has no official
  Ubuntu/Debian package at all, so installing it means adding Grafana's
  own apt repository (GPG key + a `signed-by` sources.list.d entry, the
  current non-deprecated approach, not `apt-key add`) before
  `apt-get install` can find it — written as a `RunCommand` script since
  `Task.Install` has no concept of "add this repo first". Every other
  package in this project so far came from Ubuntu's own repos.
- Grafana is pre-wired with a Prometheus datasource via its own
  provisioning directory (`/etc/grafana/provisioning/datasources/`,
  read only at startup) rather than left for someone to click through in
  the UI.
- Applies this session's now-established lessons proactively rather than
  waiting to hit them again: `systemctl restart`, not just
  `enable --now`, for both `prometheus-node-exporter` and
  `grafana-server` (the auto-start-on-install race from
  `wordpress_site.scala`'s php8.3-fpm bug); and the health check lives in
  its own `confirm-observability-healthy` stage, not tacked onto
  `install-grafana` (the stage-ordering bug from that same file).
- Confirm stage doesn't just check "is Prometheus/Grafana up": it checks
  Prometheus's own `/api/v1/targets` reports every node_exporter target
  healthy (0 down, all up) and Grafana's own `/api/datasources` reports
  the Prometheus datasource actually provisioned — same "prove the real
  thing" reasoning as `test_mariadb_galera.sh`'s replicated-row check.
- Grafana's default `admin`/`admin` login is assumed to still work
  against its API (not just the UI, which forces a password change) on a
  truly fresh install — unconfirmed, flagged the same way
  `mariadb_galera_cluster.scala` flags its own unix-socket-root
  assumption: an easy, loud 401 to spot if wrong, not a silent wrong
  result.
- Scope, deliberately: node-level fleet metrics only for this first pass.
  Not included: haproxy's own native Prometheus-format stats endpoint, or
  a mysqld_exporter for Galera-specific wsrep metrics — both natural
  follow-ups once this base layer is confirmed working.
- **Confirmed working end-to-end** after the fix below: all four stages
  passed on a real run — node_exporter on all six nodes, Prometheus
  scraping all six targets plus itself, Grafana up with the Prometheus
  datasource provisioned, `confirm-observability-healthy` passing in ~10s.

### Fixed — `manifests/observability_stack.scala`: Grafana health check failed on every fresh install

`provisionDatasourceScript`'s health check slept a fixed 3 seconds before a
single `curl -sf http://localhost:3000/api/health` attempt, then gave up.
On the real first run this failed every time, even though Grafana was
never actually broken.

Root cause, confirmed from real diagnostics (`systemctl status`,
`journalctl -xeu grafana-server`, `curl -v`, all pasted by the user): a
fresh `grafana-server` start kicks off a background download-and-install
of its ~13 bundled datasource plugins (elasticsearch, prometheus, mysql,
postgres, zipkin, and more), which took upward of 50 seconds — while
`systemctl is-active` reported the service active almost immediately
(it's listening on :3000 well before it's actually finished starting up).
3 seconds was never going to be enough.

Fixed by replacing the fixed sleep with a genuine poll: up to 30 attempts
at 3s apart (90s total) against `/api/health`, same "retry the real
thing, don't guess a sleep duration" principle `etcd_grow_cluster.scala`'s
`registerScript` and `test_mariadb_galera.sh`'s `poll_for_marker` already
use elsewhere in this project.

### Added — `manifests/test_vip_failover.sh`: chaos/failover drill for mariadb_haproxy_keepalived.scala

Closes a gap flagged (but never built) since that file's own CHANGELOG
entry: every earlier confirmation only ever proved the VIP works while
nothing is broken. This deliberately breaks something and watches the
cluster recover, against whatever's already running on tst0/tst1/tst2
(no teardown/rebuild).

- Stops haproxy (not keepalived) on whichever node currently holds the
  VIP — the realistic failure this setup is actually designed to survive
  via keepalived.conf's own `vrrp_script chk_haproxy` (`pgrep -x haproxy`,
  weight 4), not a simulated full node outage.
- Polls (bounded, not a blind sleep) for the VIP to move to a different
  node, confirms the original holder genuinely released it (catching a
  split-brain double-hold as its own distinct failure), and confirms the
  VIP is still reachable through the new holder — proving clients kept
  working through the handover, not just that an address moved.
- Restarts haproxy on the original node and asserts failback is
  deterministic, not just likely: keepalived.conf sets no `nopreempt`,
  and priorities are fixed (tst0=101 > tst1=100 > tst2=99), so the VIP
  must return to tst0 specifically once every node's haproxy is healthy
  again, whichever node was actually stopped.
- A `trap cleanup EXIT` always restarts haproxy on whatever node this
  script stopped it on, whether the run passes, fails, or is interrupted
  — a chaos test that can leave the cluster broken on exit is worse than
  not running it.
- Connectivity checks run from tst5 when reachable (a genuinely external
  client, matching the configuration this whole exercise serves) rather
  than from one of the three backend nodes, falling back to a backend
  node if tst5 isn't available so this doesn't hard-depend on
  `wordpress_site.scala`.
- **Real bug found and fixed along the way, in two existing test
  scripts, not this new one**: `test_mariadb_galera.sh` and
  `test_ceph_lifecycle.sh` both hardcoded `SSH_USER="ubuntu"`, an
  assumption that was apparently never actually confirmed against real
  infra — this session's own terminal transcripts against
  tst0/tst1/tst2/tst5 consistently showed `localadmin@tst0:~$` prompts.
  Both corrected to `localadmin`; neither script had been run for real
  before now, so this would have been the first thing to fail on an
  actual run of either.
- Not yet run.

### Fixed — `manifests/wordpress_site.scala`: health check rejected a perfectly healthy fresh install

Real run against tst5, after the php8.3-fpm restart fix above: the
database connection was fine and both services were stable, but
`curl http://localhost/` came back `302`, not `200` — `siteHealthScript`
treated that as a failure. The 302 is WordPress's own normal, correct
behavior on a fresh install with no tables yet: `wp-settings.php`
redirects `/` to `/wp-admin/install.php` to run the setup wizard. The
check only ever accepted a bare 200 on `/`, so it rejected a perfectly
healthy fresh install.

- Fixed by following the redirect (`curl -sL`) before judging the final
  page's status and body — a genuine DB-connection failure still won't
  produce a clean 200 after following redirects, so the existing
  "Error establishing a database connection" body check still holds.
- **Confirmed working** — with this fix applied, `wordpress_site.scala`
  ran end-to-end against tst0 (database) and tst5 (WordPress) with no
  further failures. Three real bugs found and fixed in sequence to get
  here (the health-check stage-ordering bug, the php8.3-fpm restart race,
  and this 302 check), each only visible once the previous one was
  actually fixed — same pattern `mariadb_haproxy_keepalived.scala`'s own
  three-bug chain followed earlier this session.

### Fixed — `manifests/wordpress_site.scala`: php8.3-fpm now gets an explicit restart, not just `enable --now`

Real run against tst5: once the health-check stage split (above) was
fixed, the first real re-run instead hit a transient startup race —
nginx logged a burst of "Address already in use" errors and php8.3-fpm
logged "Another FPM instance seems to already listen on
/run/php/php8.3-fpm.sock", both self-resolving within a few seconds, but
not reliably inside confirm-wordpress-healthy's 60s window. Root cause:
a fresh `nginx`/`php8.3-fpm` package install auto-starts both services as
a side effect of their own postinst scripts (the same class of gotcha
`mariadb_galera_cluster.scala`'s header comment already documents for
mariadb-server) — and `systemctl enable --now php8.3-fpm` only starts a
unit if it isn't already active, so it doesn't force a clean restart the
way `systemctl restart` does, leaving php8.3-fpm's final state dependent
on whatever timing the package's own auto-start left it in.

- `startServicesScript` now runs `systemctl restart php8.3-fpm` right
  after `enable --now`, the same explicit-restart pattern already used
  for nginx — both services now go through one guaranteed clean
  stop-then-start regardless of the auto-start race. Confirmed the
  database connection itself was never the problem on this run (`mariadb
  -h 10.10.5.100 ... SELECT 1` from tst5 succeeded throughout) — this was
  purely a tst5-local service-startup timing issue.

### Fixed — `manifests/wordpress_site.scala`: health check must be its own stage, not tacked onto install-wordpress

Real first run against tst5: `install-wordpress`'s health check timed out
after 60s every time, with no task output for that stage printed at all —
because `ClusterPlaybookRunner.runStage` checks a stage's `waitFor` BEFORE
running that stage's own tasks, not after (confirmed by reading the actual
runner, not assumed). The `siteHealthScript` curl check was sharing a stage
with the install/download/config/start tasks that actually bring the site
up, so it was being polled against tst5 before nginx or php8.3-fpm were
even installed.

- Split into a separate `confirm-wordpress-healthy` stage (`waitFor` + the
  "wordpress site confirmed" debug task), run strictly after
  `install-wordpress` completes — same shape
  `mariadb_galera_cluster.scala`'s own `confirm-cluster-healthy` stage
  already uses, which is exactly why this was easy to get wrong here
  without noticing until a real run caught it.

### Added — `manifests/wordpress_teardown.scala`: tears down wordpress_site.scala's database and tst5 install

Two stages, mirroring `wordpress_site.scala`'s own asymmetric shape:
`drop-wordpress-database` (tst0 — `DROP DATABASE`/`DROP USER IF EXISTS`,
replicated to tst1/tst2 by Galera the same way the original `CREATE` was)
and `teardown-tst5` (stops nginx/php8.3-fpm, purges them and the PHP
extensions, autoremoves now-unneeded dependencies, then explicitly removes
`/var/www/wordpress` and the nginx site config/symlink — neither is
package-owned, so purging nginx alone wouldn't reliably clean them up).
Same full-purge convention as `mariadb_galera_teardown.scala` (fast apt
packages, tst5 exists solely for this one exercise), not
`cephadm_teardown.scala`'s leave-the-packages-in-place approach. Not yet
run.

### Added — `manifests/wordpress_site.scala`: single-node WordPress on tst5, database on the existing Galera cluster via the HAProxy VIP — confirmed working (three real bugs found and fixed along the way, see entries above)

Sixth infrastructure exercise, and the first one that's an ordinary app
built on top of already-working infrastructure rather than infrastructure
itself: a single nginx + php8.3-fpm + WordPress node (tst5, new —
`10.10.5.17`, added to `inventory.yaml`, deliberately not one of
tst0/tst1/tst2) using `mariadb_galera_cluster.scala` +
`mariadb_haproxy_keepalived.scala`'s already-built 3-node Galera cluster as
its database, addressed only through the floating IP (`10.10.5.100:3306`,
the write listener, never the round-robin read listener — WordPress uses
one connection for both reads and writes throughout, not just at install
time).

- Two stages: `create-wordpress-database` (tst0 only — creates the
  `wordpress` database and `wordpress_user`@`%` locally via the same
  trusted unix-socket-root assumption `mariadb_galera_cluster.scala`
  itself depends on; Galera's synchronous replication makes this visible
  on tst1/tst2 immediately, no need to repeat it there) and
  `install-wordpress` (tst5 only).
- **Real gap found by reading `ClusterPlaybookRunner.scala`'s actual
  `Task.Copy` handling before using it, not assumed**: the cluster-playbook
  runner discards `Task.Copy`'s `vars` field entirely
  (`case Task.Copy(src, dest, owner, group, mode, _) =>`) — `.mustache`
  template rendering only works in the flat `PlaybookRunner.scala`, not
  `ClusterPlaybookRunner.scala`. Using `Task.Copy` with a `.mustache`
  template for wp-config.php or the nginx site config here would have
  silently shipped the literal `{{placeholders}}` unrendered. Written
  instead as `RunCommand` heredoc scripts with values already
  Scala-interpolated at build time, the same convention every other
  config file in this project already uses.
- `'wordpress_user'@'%'`, not a specific host: WordPress's traffic reaches
  whichever real node HAProxy is routing to as a plain TCP connection from
  that node's own address, not from tst5 directly — the identical reason
  `mariadb_haproxy_keepalived.scala`'s own clustercheck user needed `@'%'`
  rather than `@'localhost'`.
- Applies this session's own masked-failure lesson from
  `mariadb_haproxy_keepalived.scala`'s haproxy bug proactively: `nginx -t`
  is checked before reloading, and both nginx's and php8.3-fpm's actual
  active state are checked after starting, failing loudly rather than
  silently if either isn't running.
- Confirm stage doesn't just check HTTP 200: also greps the response body
  for "Error establishing a database connection", since a DB-connectivity
  failure through the VIP often still renders as a 200 OK page — same
  "prove the real thing, don't trust a status flag alone" reasoning as
  `test_mariadb_galera.sh`'s replicated-row check.
- Written and first delivered with no local Scala compiler available to
  check it against (unlike every other manifest so far this session) — see
  the three "Fixed" entries above for what a real run against tst0/tst5
  actually found: a stage-ordering bug in the health check, a php8.3-fpm
  startup race, and a 302-vs-200 check that rejected a healthy fresh
  install. **Confirmed working end-to-end** once all three were fixed.
- No YAML front-end yet — a natural follow-up now this is confirmed
  working, same order every other manifest this session followed.

### Added — `manifests/mariadb_galera_cluster.yaml`: YAML front-end for `mariadb_galera_cluster.scala`, and a real bug its first run found (fixed in both files)

Same five stages (`install-mariadb`, `write-galera-config`, `bootstrap-tst0`,
`join-others`, `confirm-cluster-healthy`), same scripts, same
`HealthCheck.Quorum`, translated against the real confirmed YAML schema
(`install`, `run_command` with block-scalar `|` command bodies, `wait_for`
with `required`/`poll_interval`/`timeout`, `debug`) rather than assumed —
checked against the actual `ClusterPlaybookYaml.scala`/`TaskYaml.scala`
parser source and cross-referenced against a real manifest already in the
repo (`manifests/ceph-mon-quorum.yaml`). Validated with
`python3 -c "import yaml; yaml.safe_load(...)"` before delivery.

- **One real, honest difference from the Scala DSL, stated in the file's
  own header comment**: the Scala version defines
  `sstPassword`/`clusterName`/`galeraPeers`/`wsrepClusterSizeScript` once as
  `val`s and reuses them across multiple scripts. YAML has no equivalent for
  reusing a text *fragment* embedded inside a larger multi-line block scalar
  (an anchor/alias can only substitute a whole node, not splice into the
  middle of a string) — so the `wsrep_cluster_size` lookup one-liner and the
  SST password are just typed out again everywhere they're needed, with
  nothing stopping the copies drifting apart by hand-editing one and not the
  others.
- **First real run, against tst0/tst1/tst2** — `install-mariadb` and
  `write-galera-config` both completed cleanly (confirmed the per-node
  `{{cluster_ip}}` templating rendered correctly: tst0=10.10.5.12,
  tst1=10.10.5.13, tst2=10.10.5.14, `bind-address` set to each node's own
  IP), then failed at `bootstrap-tst0` with a generic systemd failure and a
  downstream `ERROR 2002 (HY000): Can't connect to local server through
  socket '/run/mysqld/mysqld.sock' (111)` — and, same masking shape as the
  haproxy `systemctl restart` bug above, the script's own
  `echo "Bootstrapped a new Galera cluster on this node"` still printed even
  though `galera_new_cluster` had genuinely failed two lines earlier.
  - **Root cause, from the real `journalctl -xeu mariadb.service` output**:
    `[ERROR] WSREP: It may not be safe to bootstrap the cluster from this
    node. It was not the last one to leave the cluster and may not contain
    all the updates`, with `/var/lib/mysql/grastate.dat` showing
    `safe_to_bootstrap: 0` and `seqno: -1` — Galera's own safety check
    against bootstrapping from a copy of the data it can't prove is
    current, tripped here by leftover state on tst0 from an earlier test
    run that was never cleanly torn down first (not a fresh install: the
    `install-mariadb` stage reported packages "already the newest version"
    rather than newly installing them).
  - **Fixed in both `mariadb_galera_cluster.scala`'s `bootstrapScript` and
    this file's `bootstrap-tst0` task** (identical logic, since the two
    front-ends share the same underlying script): if `grastate.dat` shows
    `safe_to_bootstrap: 0`, rewrite it to `1` before calling
    `galera_new_cluster`, and check `galera_new_cluster`'s own exit code
    directly rather than letting a failure fall through to the unconditional
    `echo`. Documented in both files as a **test-cluster-only shortcut**: a
    real production cluster should instead run `mysqld --wsrep-recover` on
    every node and bootstrap from whichever one reports the highest seqno,
    since forcing the wrong node can silently discard committed data. Safe
    here specifically because this playbook always designates tst0 as the
    sole bootstrap node by design, and `mariadb_galera_teardown.scala`'s job
    is to wipe `/var/lib/mysql` clean between runs anyway — so a stale
    `safe_to_bootstrap: 0` on tst0 only ever means "this node's own leftover
    data from last time," never "some other node has newer data being
    discarded."
  - Not yet re-run against tst0/tst1/tst2 with the fix applied.

### Added — `manifests/mariadb_haproxy_keepalived.scala`: front the Galera cluster with a floating IP, confirmed working against tst0/tst1/tst2 (three real bugs found and fixed along the way)

Fifth infrastructure exercise: fronts `mariadb_galera_cluster.scala`'s
3-node cluster with haproxy + keepalived, colocated on tst0/tst1/tst2 (the
user's own choice when asked, over dedicated LB nodes), giving clients one
stable address (`10.10.5.100`, user-provided) with automatic failover
instead of needing to know which of tst0/tst1/tst2 to talk to. Researched
against a real guide before writing any of this, not assumed:
[computingforgeeks.com's MariaDB Galera + HAProxy + Keepalived guide](https://computingforgeeks.com/mariadb-high-availability-galera-haproxy-keepalived/).

- **Health check**: a small custom HTTP-over-raw-socket script
  (`galera-clustercheck`), exposed on `:9200` via a systemd socket unit
  (`Accept=yes` — one instance per connection, standing in for the
  xinetd-based approach some guides use; xinetd isn't a default Ubuntu
  24.04 dependency, systemd socket units always are). Checks
  `wsrep_local_state = 4` (Synced) specifically, not just `wsrep_ready` —
  a real, necessary distinction from `mariadb_galera_cluster.scala`'s own
  health check: a node can be `wsrep_ready` while still catching up via
  SST/IST, and haproxy routing a write there would be a correctness bug,
  not just a slow query.
- **Two haproxy listeners**, symmetric across all three nodes (same
  `{{nodes.<name>.cluster_ip}}` templating as Galera's own config, no
  `DistributeFile` needed): `:3306` (write) pins all traffic to tst0 with
  tst1/tst2 as `backup` — avoiding Galera's multi-writer
  certification-conflict problem under normal operation while still
  failing over if tst0 goes down; `:3307` (read) round-robins across all
  three, each gated on its own `:9200` Synced check.
- **A real port-binding conflict, fixed in `mariadb_galera_cluster.scala`
  itself rather than worked around here**: haproxy needs to bind `*:3306`
  on each node to serve both that node's own address and the floating VIP
  once it holds it — but that file's `bind-address=0.0.0.0` meant mysqld
  was already claiming every address on `:3306`, including the VIP the
  instant keepalived added it. Narrowed to `{{cluster_ip}}` — see that
  file's own entry above and updated header comment.
- **keepalived**, unlike haproxy's config, is genuinely asymmetric
  (priority/state differ per node — tst0 priority 101/`MASTER`, tst1 100,
  tst2 99, all `BACKUP` except tst0), so it's three separate per-host
  stages rather than one shared stage, same pattern as
  `mariadb_galera_cluster.scala`'s `bootstrap-tst0`/`join-others` split.
  `interface` is detected at runtime (`ip -4 route show default`) rather
  than hardcoded as e.g. `eth0` — guessing an OS device name instead of
  confirming it is exactly the class of bug behind this project's real
  `/dev/sdX` incident (see CHANGELOG history below), no reason to repeat
  it for a NIC name.
- **Honest limitation, stated in the file itself**: the confirm stage's
  `HealthCheck.Quorum(requiredCount = 1)` against "does any node hold the
  VIP" is a reasonable fit for `HealthCheck.Quorum`'s shape but does NOT
  detect a split-brain double-VIP (two nodes both reporting true would
  still pass) — a real anti-split-brain check would need to count VIP
  holders in one pass, which the per-node polling design here doesn't
  support as written.
- Final confirm stage also proves the whole path end-to-end — connects
  through the VIP itself (`mariadb -h 10.10.5.100 ...`, reusing the
  clustercheck user rather than adding a second test credential) from
  tst1, not trusting each piece's own status output in isolation.
- **First real run, and a real bug it caught**: `install-haproxy-keepalived`
  through `write-keepalived-tst2` all completed and reported success — but
  `write-haproxy-config`'s own output, read closely, showed `Job for
  haproxy.service failed because the control process exited with error
  code` on all three nodes, and the script's final `echo` still exited 0
  regardless, so the task reported `success=true` anyway. The
  `confirm-ha-frontend` stage's VIP-holder check still passed (keepalived
  had no problem electing tst0), but the actual connectivity task then
  failed for the real reason: `ERROR 2002 ... Can't connect to server on
  '10.10.5.100'` — nothing was listening, because haproxy wasn't actually
  running anywhere.
  - **Root cause**: the live cluster had been built *before* the
    bind-address fix above went into `mariadb_galera_cluster.scala` —
    mysqld was still bound to `0.0.0.0:3306` from its last real start, so
    haproxy's own `*:3306` bind collided with it. Simply re-running
    `mariadb_galera_cluster.scala` would rewrite `60-galera.cnf`'s
    content but NOT apply it: `bootstrapScript`/`joinScript`'s own
    idempotency check sees an already-healthy `wsrep_cluster_size` and
    skips restarting mariadb — a real, previously unrecognized gap
    between "config file matches what's intended" and "the running
    process has actually picked it up."
  - **Fixed two ways.** `haproxyConfigScript` now checks
    `systemctl is-active` after restarting and `exit 1` if it isn't, so a
    real failure is never silently reported as success again — this was
    the more important fix, since it's what let the first bug hide long
    enough to surface as a confusing downstream connection error instead
    of a clear "haproxy didn't start" at the point it actually happened.
    **`manifests/mariadb_galera_rolling_restart.scala`** (new) applies a
    config change like this one to an already-running cluster safely —
    restarting mariadb one node at a time, waiting for each to rejoin
    (`wsrep_ready=ON`, `wsrep_cluster_size=3`) before moving to the next,
    so the cluster never drops below 2-of-3 quorum mid-restart. Needs to
    be run once against the live tst0/tst1/tst2 cluster before re-running
    `mariadb_haproxy_keepalived.scala`, to actually apply the
    already-shipped bind-address fix.
  - **Second real run, and the real fix — the first diagnosis above was
    wrong.** With the masking bug fixed, the real `journalctl` output came
    back: `cannot bind socket (Address already in use) for [0.0.0.0:3306]`
    — but `ss` confirmed mysqld was already correctly bound to only
    `10.10.5.12:3306` (the bind-address fix HAD applied), not the
    wildcard. So narrowing mysqld's bind, while independently reasonable,
    was never what actually fixes this: on Linux, a wildcard bind
    (`0.0.0.0:PORT`) conflicts with ANY already-bound specific address on
    the same port — the wildcard side has to stop being wildcard, no
    matter how narrowly the other process binds. This is a genuine gap in
    the reference guide this file follows: it runs haproxy/keepalived on
    dedicated LB nodes, never colocated with a Galera backend on the same
    host, so this exact collision never comes up for it — colocating (the
    user's own choice when asked, over dedicated LB nodes) is what
    surfaces it here.
  - **Properly fixed**: `haproxyConfigScript` now binds the VIP address
    specifically (`10.10.5.100:3306`/`:3307`), never a wildcard, with
    `net.ipv4.ip_nonlocal_bind=1` set first (researched, not assumed —
    [cyberciti.biz's explainer](https://www.cyberciti.biz/faq/linux-bind-ip-that-doesnt-exist-with-net-ipv4-ip_nonlocal_bind/))
    so HAProxy can hold that bind on the two backup nodes too, before
    keepalived has ever attached the VIP to their interface — without it,
    they'd instead fail with "Cannot assign requested address." This also
    means clients only ever reach HAProxy via the VIP, which was the
    intended design anyway, rather than incidentally also being reachable
    on each node's own address.
  - **Third real run — the VIP-specific bind fix worked.** `install-haproxy-keepalived`
    through `write-keepalived-tst2` all completed cleanly this time, no
    bind errors anywhere, and `confirm-ha-frontend`'s VIP-holder check
    found tst0 correctly. The connectivity task then hit a *different*
    real failure, one layer further in: `ERROR 1698 (28000): Access
    denied for user 'clustercheck'@'10.10.5.12'` — proof the TCP
    connection genuinely reached a real mysqld through HAProxy (an auth
    rejection, not a timeout or connection-refused), so the bind fix
    itself is confirmed working.
  - **Root cause**: `createClustercheckUserScript` only ever granted
    `'clustercheck'@'localhost'`. MariaDB's `'localhost'` host-match is
    special-cased to genuine unix-socket connections — it never matches a
    TCP connection, even one originating from the same machine. The local
    `:9200` health-check script connects via socket, so `@'localhost'`
    correctly covers that path (which is why haproxy's own httpchk had
    been passing and routing traffic to tst0 in the first place) — but a
    client going through HAProxy arrives at mysqld as a real TCP
    connection, and HAProxy's plain `mode tcp` doesn't preserve the
    original client address for the backend leg, so mysqld saw it as
    coming from tst0's own address, which `'localhost'` doesn't match at
    all.
  - **Fixed** by also granting `'clustercheck'@'%'` under the same
    username — both grants are needed for the two genuinely different
    connection paths (local socket for health checks, proxied TCP for
    everything else), not a redundant belt-and-suspenders pair. Applies
    cleanly on a re-run against the already-partially-created user, since
    `CREATE USER IF NOT EXISTS` just adds the missing `@'%'` grant.
  - **Fourth real run — confirmed working end-to-end.** With the `@'%'`
    grant added, `confirm-ha-frontend` completed clean: tst0 holds the
    VIP, and `mariadb -h 10.10.5.100 -P 3306 ... "SELECT CONCAT('served
    by ', @@hostname)"` run from tst1 came back `served by tst0` —
    proving the full path (client -> VIP -> HAProxy -> Galera backend)
    works, not just each piece's own status output in isolation. Three
    real, independent bugs were found and fixed in sequence to get here
    (a masked restart failure, a wildcard-vs-specific-address bind
    conflict, and a `'localhost'`-only auth grant that TCP connections
    can't match), each only visible once the previous one was actually
    fixed — worth keeping in the record as a genuine example of what
    "not yet run" turning into "confirmed working" actually took, rather
    than compressing it into a single clean success. Not yet exercised:
    an actual failover (killing tst0's haproxy/keepalived and confirming
    the VIP migrates and traffic keeps flowing) — see the regression-test
    idea below, still not built.

### Added — `manifests/test_mariadb_galera.sh`: end-to-end regression test for the MariaDB Galera playbooks

Same shape as `test_ceph_lifecycle.sh`: runs the full lifecycle
(`mariadb_galera_teardown.scala` → `mariadb_galera_cluster.scala`) through
`orphera cluster-playbook`, then verifies the result independently over
SSH rather than trusting the playbook's own `confirm-cluster-healthy`
`HealthCheck` — same "don't let the thing being tested also grade itself"
reasoning.

- Checks `wsrep_cluster_size`/`wsrep_cluster_status`/`wsrep_ready`
  independently on **each** of tst0/tst1/tst2 (not just one node's
  report), the same per-node convention the playbook's own
  `HealthCheck.Quorum` uses, queried directly rather than trusted secondhand.
- Goes one step further than status flags: writes a uniquely-marked row on
  tst0, then polls tst1 and tst2 (bounded retry, not a blind sleep — same
  principle as `etcd_grow_cluster.scala`'s `registerScript`) until that
  exact row shows up, proving actual synchronous replication happened
  rather than three nodes that each merely believe they're in a healthy
  Primary component. Cleans up the test database afterward (best-effort,
  not asserted — `CREATE ... IF NOT EXISTS` makes a leftover from a failed
  run harmless to a later one).
- Same SSH convention as `test_ceph_lifecycle.sh`: connects as the
  `ubuntu` user (root SSH is blocked by cloud-init on these images) and
  runs `mariadb`/`sudo` on the far side — which also makes this the first
  real confirmation of `mariadb_galera_cluster.scala`'s own documented
  `unix_socket`-root-auth assumption, on all three nodes rather than just
  the one node the playbook itself queries.
- **Not yet run** — written and reasoned through, same as the two
  playbooks it exercises, but not yet executed against tst0/tst1/tst2.

### Fixed — `orphera-completion.bash`: `playbook`/`cluster-playbook` path completion now finds `manifests/` without typing it first

Real friction, not theoretical: every playbook in this project lives under
`manifests/`, but completion only ever matched names relative to the
current directory — and couldn't even tab-complete into `manifests/`
itself, since it's a directory rather than a `.yaml`/`.yml`/`.scala` file
and the existing extension filter (`-X`) excluded it too. So
`orphera playbook <TAB>` found nothing until `manifests/` was typed out by
hand first.

- New `_orphera_playbook_path_completions` helper additionally searches
  `manifests/` directly for anything matching the current prefix (skipped
  once the word being completed already names a directory, i.e. contains a
  `/`, since ordinary completion already handles that correctly) — so
  `orphera playbook etcd<TAB>` now finds `manifests/etcd_cluster.scala`
  etc. directly.
- Also now offers `--resume` itself once the current word starts with
  `--`, which this completion never did before (`cluster-playbook a.scala
  b.scala --<TAB>` now suggests it).
- `cluster-playbook`'s multiple-playbook-files support (above) already
  worked with this completion by coincidence — every non-flag position
  fell through to the same path-completion branch regardless of `prev` —
  and still does, now finding `manifests/` for every path in the list, not
  just the first.

### Updated — `manifests/mariadb_galera_teardown.scala`: now also tears down the haproxy/keepalived VIP frontend (not yet run against the combined state)

Originally only covered `mariadb_galera_cluster.scala`; extended once
`mariadb_haproxy_keepalived.scala` (above) existed and had real state on
tst0/tst1/tst2 to clean up — one combined teardown rather than two, since
the HA frontend can't meaningfully outlive the Galera cluster it fronts.
Deliberately different from `cephadm_teardown.scala`, the closest existing
precedent for a package-installed (not raw-binary) thing: cephadm/podman
are heavy and slow to reinstall, so that file leaves the packages in place
and only cleans up cluster state. `mariadb-server`, `haproxy` and
`keepalived` are all normal, fast apt packages, so this file fully purges
all of them (`Task.Remove(purge = true)`) rather than just stopping
services — giving every future build run a genuinely clean slate, instead
of tearing down into a half-cleaned state that would behave differently
from a true first run.

- One stage **per host**, same reasoning as `etcd_teardown.scala`: a
  cluster-playbook run aborts entirely on the first stage that fails, so
  per-host stages mean one broken node only blocks the teardown stages
  that run *after* it.
- Purge removes the packages and their own conffiles — including
  `/etc/haproxy/haproxy.cfg` and `/etc/keepalived/keepalived.conf`, since
  `mariadb_haproxy_keepalived.scala` only ever overwrote the *content* of
  those package-owned conffiles, never created new ones — but not:
  `/etc/mysql/mariadb.conf.d/60-galera.cnf` (never package-owned), `/var/lib/mysql`
  (Ubuntu's postrm only removes it on purge under interactive debconf,
  which this project never uses), or the four files
  `mariadb_haproxy_keepalived.scala` wrote directly and that no package
  owns at all: `/usr/local/bin/galera-clustercheck`, the
  `clustercheck.socket`/`clustercheck@.service` systemd units, and
  `/etc/sysctl.d/99-orphera-haproxy-vip.conf`. All of these are removed
  explicitly rather than trusted to purge's own behavior, plus a
  belt-and-suspenders `ip addr del` for the VIP itself in case
  keepalived's own shutdown didn't get to it in time, and resetting
  `net.ipv4.ip_nonlocal_bind` back to its default (`0`) at runtime, not
  just removing the sysctl file that sets it at boot.
- New `Task.AutoRemove(purge = true)` step cleans up the now-unneeded
  dependencies haproxy/keepalived pulled in (`ipvsadm`, `liblua5.4-0`,
  `libsnmp-base`, `libsnmp40t64`), rather than leaving them behind as
  orphaned packages.
- **Not yet run against the combined haproxy/keepalived + Galera state** —
  the original mariadb-only version of this file was never run either
  (the cluster was torn down manually / not at all before this session's
  HA-frontend work built directly on top of it), so this is genuinely
  untested, not just extended.

### Added — `manifests/mariadb_galera_cluster.scala`: 3-node MariaDB Galera cluster on tst0/tst1/tst2 (not yet run)

Fourth infrastructure exercise, and the one the original Keystone/Galera
side-discussion pointed at — built directly, same reasoning as picking
etcd first: isolate the orchestration questions from OpenStack's own
install-complexity, which has nothing to do with Orphera. Targets
tst0/tst1/tst2, the same three nodes `etcd_cluster.scala` used — reusing
them for a clean before/after was a deliberate choice, per the user's own
answer when asked, not a technical requirement. `manifests/etcd_teardown.scala`
should be run against those three nodes first; nothing in this file checks
for or removes etcd itself.

- A genuinely different bootstrap **shape** from both earlier cluster
  exercises, worth naming explicitly: `etcd_cluster.scala` is symmetric
  config *and* symmetric start (every node's `--initial-cluster` lists the
  same peers, all three start at once). `ceph_mon_quorum.scala` is
  asymmetric config *and* asymmetric start (fsid/monmap generated once on
  tst0, then distributed). This file is symmetric **config** (every node's
  `wsrep_cluster_address` lists the identical three-peer `gcomm://` list,
  rendered via the same `{{nodes.<name>.cluster_ip}}` templating as
  `etcd_cluster.scala` — no `Task.DistributeFile` needed) but asymmetric
  **start order**: exactly one node (tst0) must run `galera_new_cluster`
  (bootstraps a brand-new cluster, ignoring the peer list) strictly before
  the other two do a normal `systemctl start mariadb`, which makes them
  State-Snapshot-Transfer-join from whichever peer is reachable. Enforced
  as separate stages (`bootstrap-tst0` then `join-others`), not tasks
  within one stage, for the same parallel-tasks-within-a-stage reason
  documented in `etcd_member_rejoin.scala`.
- Config written to a new `/etc/mysql/mariadb.conf.d/60-galera.cnf` file
  rather than editing the packaged `50-server.cnf` — loads later via
  `!includedir`, so its `bind-address=0.0.0.0` correctly overrides the
  package default of `127.0.0.1`, which would otherwise block both
  cross-node replication and this playbook's own health-check queries.
- **A real gotcha, reasoned through before ever running this, not found by
  trial and error**: installing `mariadb-server` auto-starts a standalone
  (non-Galera, `wsrep_on=OFF`) instance as a side effect of the package's
  own postinst, before this playbook writes any Galera config at all. A
  naive "is mariadb already active?" idempotency check would mistake that
  for "already bootstrapped." Guarded against by checking
  `wsrep_cluster_size` specifically (empty/zero when wsrep is off) instead
  of service-active state, and by unconditionally `systemctl stop mariadb`
  before writing config and (re)starting either way, on every node.
- Health check, like `etcd_cluster.scala`'s, is per-node rather than
  trusting one node's cluster-wide view: each of the three nodes must
  independently report `wsrep_cluster_size=3`, `wsrep_cluster_status=Primary`
  (this node's own view of quorum, as opposed to a partitioned
  non-Primary component), and `wsrep_ready=ON` (this node's own readiness
  to serve queries — a node mid-SST can already see `cluster_size=3` while
  still catching up, so size alone isn't sufficient).
- **Caught before ever running this, not by trial and error**: an early
  draft of the config-writing script took the node's own name as a Scala
  function parameter, rendered via a nonexistent `{{node_name}}` template
  placeholder. Spawned an Explore subagent against the real DSL/runner
  source to check, prompted directly by having gotten bitten by a similar
  wrong assumption earlier in this project's etcd work — confirmed Orphera
  has no built-in `{{node_name}}`/`{{name}}` template var; `{{cluster_ip}}`
  only works because it's a literal per-node var in `inventory.yaml`. Fixed
  by resolving the node's own name shell-side via `$(hostname -s)`, the
  same convention `ceph_mon_quorum.scala`/`etcd_cluster.scala` already use
  — with the same assumption (hostname matches inventory name) those files
  make.
- **Correction, made while building `mariadb_haproxy_keepalived.scala`
  below:** `bind-address=0.0.0.0` (reasoned at the time as needed for
  "cross-node replication and this playbook's own client connections")
  overstated the requirement — Galera's own replication traffic never
  goes through mysqld's bind-address at all (it uses `wsrep_node_address`
  on separate ports 4567/4568/4444), and this playbook's own health
  queries run locally via unix socket, unaffected by bind-address either
  way. Narrowed to `{{cluster_ip}}` (this node's own static address only)
  once haproxy needed to bind `*:3306` on the same host to serve both
  that address and a floating VIP — two processes can't both bind the
  same address:port, and the wildcard bind was claiming the VIP the
  instant keepalived added it to the interface, ahead of haproxy.
- Root auth: Ubuntu's `mariadb-server` ships root with `unix_socket` auth
  by default, so every `mariadb -N -e "..."` call here (run as the OS root
  user, same as every other privileged command in this project) needs no
  `-u`/`-p`. Flagged as an assumption to confirm on first real run — if
  wrong, it's an easy, loud connection failure, not a silent wrong result.
- SST (State Snapshot Transfer) auth uses a hardcoded `sst_user`/test
  password, created idempotently (`CREATE USER IF NOT EXISTS`) as part of
  the bootstrap step — documented explicitly as a test-only credential,
  not a pattern to reuse anywhere real secrets matter (this project has no
  secrets management at all).
- **Not yet run** — written and reasoned through, including the
  package-auto-start gotcha and the root-auth assumption above, but not
  yet exercised against tst0/tst1/tst2.

### Added — audit log rotation

`.orphera-audit/audit.jsonl` no longer grows forever — closes the gap
flagged in its own doc comment since it was first added. Rotated by
size, classic logrotate-style numbering: `audit.jsonl` is always the
current file; `audit.jsonl.1` is the most recently rotated-out file, up
to `audit.jsonl.5` (both the 5MB-ish threshold and the 5-file retention
are simple fixed constants in `AuditLog.scala`, not configurable —
deliberately, for a small ops tool's audit trail rather than a
production log-shipping pipeline).

- Checked once per `append` call (`AuditLog.rotateIfNeeded`), before
  writing that call's line — a cheap `Files.size` check on the common
  path where no rotation is needed.
- **The real design question this raised**: a `command_start`/
  `command_end` pair is written as two separate `append` calls, one
  command's duration apart — so rotation genuinely can fall between the
  two, splitting one invocation's pair across `audit.jsonl` and
  `audit.jsonl.1`. Rather than trying to prevent that (not really
  possible without knowing a command's duration in advance), `orphera
  audit-log` was changed to read every audit file it finds — current
  plus all rotated ones, oldest to newest (`LogSummary.allAuditFiles`)
  — so correlation by `invocation_id` still works across a rotation
  boundary. Rotation bounds the size of any one file on disk; it was
  never meant to bound the correlation window, and now doesn't
  accidentally do so.
- `audit-log`'s header line now says `(+ N rotated file(s))` when more
  than the current file was read, so it's visible when a report spans a
  rotation.
- Not yet run against a file that's actually grown past the threshold
  (5MB of real audit lines is a lot to generate by hand) — the rotation
  and multi-file-read logic haven't been exercised against a real
  rotation event yet, only reasoned through.

### Added — `cluster-playbook` accepts multiple playbook files in one invocation

`orphera cluster-playbook a.scala b.scala c.scala` now runs each in
order, stopping at the first one that fails — same "don't press on past
a failure" convention as a single playbook's own stages, applied one
level up. Saves typing a separate invocation per file for a fixed
sequence (e.g. `etcd_teardown.scala` then `etcd_cluster.scala`) without
needing a wrapper shell script.

- `Command.RunClusterPlaybook`'s `path: String` is now `paths: List[String]`
  — CLI parsing takes every leading non-flag argument as a path, so
  `--resume` (the only flag) can still only appear once, at the end,
  applied independently to each playbook via its own checkpoint file,
  not to the sequence as a whole.
- Prints a `[N/M] <path>` header before each playbook, but only when more
  than one path is given — a normal single-playbook invocation's output
  is byte-for-byte unchanged.
- Audit log's `path` field for this command now records all the given
  paths, comma-separated, rather than just one.
- Not yet run against real infrastructure — untested, but a low-risk
  change: single-path invocations take the same code path as before
  (`total == 1` skips the new header), so this shouldn't affect anything
  already working.

### Added — `manifests/etcd_teardown.scala`: clean up everything the etcd exercises left behind (not yet run)

Tears down what `etcd_cluster.scala`, `etcd_member_rejoin.scala`, and
`etcd_grow_cluster.scala` installed: stops and disables the `etcd`
service, removes its unit file, wipes `/var/lib/etcd` and `/etc/etcd`,
removes the `etcd`/`etcdctl` binaries, and removes the `etcd` system
user. Much simpler than `cephadm_teardown.scala` — no LVM/device-mapper
state or raw disks involved, just a service, two directories, two
binaries and one user.

- One stage **per host** (`teardown-tst0`, `teardown-tst1`, ...), not one
  shared stage across all five nodes — deliberate, because a
  cluster-playbook run aborts entirely on the first stage that fails
  (confirmed by the real `etcd_grow_cluster.scala` run against tst4's
  unreachable agent). Per-host stages mean one unreachable node only
  blocks whatever teardown stage runs *after* it, not the ones before it.
- `tst4` is deliberately last in the host list for exactly that reason:
  its agent is currently unreachable and it never got past
  `etcd_grow_cluster.scala`'s first install task, so there's nothing on
  it to tear down anyway — every other host's cleanup completes before a
  still-unreachable tst4 is even attempted.
- Every command is written to tolerate partial/missing state (`|| true`,
  `2>/dev/null` throughout) — safe against a fully-built cluster, a
  partially-grown one (tst3's current state: binaries installed, never
  configured or started), or a host that never had etcd at all.
- **Not yet run.**

### Added — `manifests/etcd_cluster.scala`: 3-node etcd cluster, confirmed working against tst0/tst1/tst2

Follow-up to a side discussion on what a Keystone+Galera deployment would
teach about Orphera's orchestration model versus what it's actually worth
in effort: etcd was picked instead as a synthetic test that isolates the
same architectural questions (cross-node config templating, quorum
waiting, an operation scoped to one node rather than all of them) without
OpenStack's packaging/service-catalog overhead — see the reasoning
captured in this file's own header comment.

- Installs etcd from the official static Go binary release
  (`etcd-io/etcd`, pinned to `v3.5.21`) rather than a distro package —
  deliberately, so the exercise's effort goes into orchestration, not
  install-complexity.
- Bootstrap is symmetric, unlike `ceph_mon_quorum.scala`'s hand-rolled
  mon cluster: etcd's `--initial-cluster` just needs every peer's name
  and address, which is knowable upfront from inventory `cluster_ip`
  vars alone, so it's rendered identically on all three nodes via
  `{{nodes.<name>.cluster_ip}}` templating with **zero**
  `Task.DistributeFile` calls — no generate-once-on-one-node-then-push
  step at all. **Confirmed**: ran clean on the first attempt against
  tst0/tst1/tst2, all three nodes reaching `started` with no retries —
  real evidence the existing cross-node templating already covers this
  shape of problem on its own. Says nothing about the asymmetric case
  (Galera-style bootstrap, or etcd's own runtime membership changes),
  which is a different, harder shape not exercised here.
- `confirm-quorum`'s `HealthCheck.Quorum` here checks three independent
  per-node endpoints (`etcdctl endpoint health` against each node's own
  loopback client port, `requiredCount = 3`) rather than one node
  reporting a cluster-wide view like the Ceph examples' mon-quorum
  checks (`requiredCount = 1` against `ceph mon stat`/`mon_status`) —
  first real use of `HealthCheck.Quorum` in that shape.
- Documented in the file itself, not just here: an unquoted heredoc
  (`<<EOF`) silently drops every `\<newline>` it contains (same rule as
  inside a double-quoted string), so the `ExecStart=` line's
  readability-motivated `\` continuations never reach the written
  `etcd.service` — it lands there as one long single line. Confirmed
  harmless in the real run: `enable --now etcd` reported success and
  the quorum check passed, so the collapsed single-line `ExecStart`
  parsed and ran correctly.
- First real run (2026-09-30): all three stages completed with no
  retries needed, `Type=notify` didn't trip a systemd startup timeout,
  and `confirm-quorum` reached 3/3 in ~0s — all three nodes were
  already healthy by the check's first poll. Nothing in this design
  needed correcting; the open questions above (asymmetric bootstrap,
  runtime membership changes) remain the interesting unexercised
  ground for a follow-up.

### Added — `manifests/etcd_member_rejoin.scala`: remove/wipe/rejoin tst2 against a live cluster (not yet run)

Direct follow-up to `etcd_cluster.scala`, targeting the asymmetric
membership-change case that file's own commentary flagged as unexercised.
Assumes the healthy 3-node cluster `etcd_cluster.scala` builds already
exists; removes tst2 from it, wipes its data dir, then rejoins it under a
fresh member identity and confirms quorum is restored to 3/3.

- Exercises etcd's strict two-step membership-change ordering: `etcdctl
  member add` must be run against an *already-healthy existing* member
  (tst0) to register the rejoining node's identity in the cluster's
  membership list **before** the rejoining node (tst2) is allowed to
  start with `--initial-cluster-state existing` — starting it first just
  fails, since as far as the live cluster is concerned that peer doesn't
  exist yet.
- That ordering is expressed as two separate stages
  (`register-new-identity` on tst0, then `rejoin-tst2` on tst2), not two
  tasks in one stage — tasks within a single stage run in **parallel**
  across that stage's nodes (`ClusterPlaybookRunner.runStageTasks`), so
  combining them would race the two steps against each other, which is
  exactly the failure this split exists to avoid. Stages, unlike tasks
  within one stage, run strictly sequentially.
- `remove-member` and `register-new-identity` are both written
  idempotently (grep the current membership list first, act only if
  needed) — same "check first, act only if needed" convention used
  throughout the codebase — so a partially-completed run can be safely
  re-run from the top.
- The rejoin unit file is identical to `etcd_cluster.scala`'s, with
  exactly one change: `--initial-cluster-state existing` instead of
  `new` — `new` is only valid for a member starting cluster formation
  from scratch, and etcd refuses to start with it once other members
  (tst0, tst1) already exist.
- Confirms success the same way `etcd_cluster.scala` does
  (`HealthCheck.Quorum`, `requiredCount = 3`), then prints the roster
  again and prompts comparing tst2's member ID against the "before"
  roster — a removed-and-re-added member gets a fresh ID even though its
  name and address are unchanged, so an unchanged ID would mean this
  wasn't a real rejoin.
- Honest scope limit, stated in the file itself: tst2's peer address is
  still known statically from inventory the whole time, same as
  `etcd_cluster.scala` — so this does **not** exercise a harder gap
  identified alongside it (capturing a task's *output* on one node —
  e.g. a freshly assigned member ID, or a brand-new node's address
  nobody typed into inventory — and feeding it into a task on a
  different node). What this does prove, once run, is the narrower but
  still real "stage B must not start until stage A has truly finished
  on a different node" ordering requirement, plus whether a
  wipe-and-rejoin under a fresh identity actually works end-to-end
  against real infrastructure.
  **Correction, added while building `etcd_grow_cluster.scala` below:**
  the "Orphera has no mechanism for that" line originally here overstated
  the gap — checked against the real source this time rather than
  assumed. `Task.SetFact` writes into a shared, run-scoped store
  (`SetFacts`), and a later stage on any node *can* read another node's
  fact via `{{nodes.<name>.<key>}}`. The real, still-open gap is
  narrower: there's no mechanism to capture a `RunCommand`'s stdout
  (e.g. `member add`'s own printed member ID) into a fact automatically
  — `SetFact`'s value must be a literal or template expression, not
  "whatever the last command printed." Neither this file nor
  `etcd_grow_cluster.scala` exercises that narrower gap, since every
  address either uses comes from inventory, not from anything computed
  at runtime.
- **Not yet run** — written and reasoned through, but not yet exercised
  against tst0/tst1/tst2. Unlike `etcd_cluster.scala`'s entry above,
  nothing here is confirmed by a real pass yet.

### Added — `manifests/etcd_grow_cluster.scala` + `inventory.yaml`: grow the etcd cluster from 3 to 5 nodes with tst3/tst4, confirmed working against tst0-tst4

Third etcd exercise. Adds `tst3` (`10.10.5.15`) and `tst4` (`10.10.5.16`)
to `inventory.yaml` and grows the live cluster built by
`etcd_cluster.scala` to 5 members by registering and starting each one in
turn — a genuinely new member joining, not a rejoin of one that was
already part of the cluster's history like `etcd_member_rejoin.scala`.

- Adds tst3 and tst4 with `cluster_ip` inventory vars only — no
  `osd_disks`/`zap_disks`, since neither has been used for a Ceph
  exercise yet and nothing is guessed at (see the tst0 incident note in
  `inventory.yaml` for why guessing disk paths instead of confirming them
  bit before).
- Grows one member at a time — register tst3, start it, confirm 4/4
  healthy, *then* register tst4, start it, confirm 5/5 — rather than
  adding both together, following etcd's own operational guidance:
  changing membership by more than one node at once risks the cluster
  needing more simultaneous votes than are actually available, even when
  the arithmetic looks fine on paper.
- Same register-then-join two-stage split as `etcd_member_rejoin.scala`,
  applied twice (once per new node): `register-tst3`/`join-tst3`, then
  `register-tst4`/`join-tst4`, each pair on different nodes for the same
  parallel-tasks-within-a-stage reason.
- tst0/tst1/tst2's own unit files are never touched or restarted —
  `--initial-cluster` only governs a node's *own* first bootstrap against
  an empty data dir, so the existing three nodes don't care that the
  cluster's membership grew around them.
- Corrects an overstated claim in `etcd_member_rejoin.scala`'s header
  comment about cross-node output-passing being entirely unsupported —
  see the correction added to that file and to its entry above.
- **Caught in real use, fixed same day**: the first real attempt to run
  this failed at compile time — `manifests/etcd_grow_cluster.scala:94:38
  invalid escape '\<newline>' not one of [\b, \t, \n, \f, \r, \\, \", \',
  \uxxxx]`. Root cause: `joinScript` needs `$`-interpolation
  (`$name`/`$clusterIp`/`$initialCluster`) so it was written as
  `s"""..."""`, but unlike a bare, non-interpolated `"""..."""` (fully
  raw — what `etcd_cluster.scala`'s `configureAndStartScript` relies on
  for its own `\`-continued `ExecStart` line), an *interpolated*
  triple-quoted string still validates escape sequences the same as a
  normal string — a lone `\` before a newline isn't one of the
  recognized escapes, so it fails to compile. Fixed by switching
  `joinScript` to `raw"""..."""`, which keeps `$`-interpolation but skips
  escape validation entirely, same as the plain string. `registerScript`
  and `installEtcdScript` were unaffected — neither contains a literal
  `\`, only `$$`/`${...}`/`$name`-style interpolation, which every
  interpolator (`s`, `raw`) handles identically.
- **Caught in real use, fixed same day (second real attempt)**: with the
  compile error above fixed, the first end-to-end attempt got tst3 joined
  and `confirm-4-healthy` reported 4/4 in ~0s, but `register-tst4` failed
  immediately after with `rpc error: ... etcdserver: unhealthy cluster`.
  Traced properly rather than left as a guess:
  - etcd's `--strict-reconfig-check` (on by default) refuses a membership
    change unless it can confirm enough of the cluster is healthy to
    survive it — per etcd's own runtime-configuration docs, "etcd rejects
    reconfiguration requests if the number of started members will be
    less than a quorum of the reconfigured cluster." The specific
    server-side check producing the "unhealthy cluster" error string is
    `mayAddMember`'s `isConnectedToQuorumAfterAddingNewMemberSince` — it
    checks whether the node handling the request (tst0) currently has a
    *live peer-transport (rafthttp) connection* to a quorum of the
    cluster the change would create, counting the brand-new member as
    unavailable by definition.
  - That's a materially different signal from this file's own
    `HealthCheck.Quorum`, which only asks each node to answer its *own*
    local `etcdctl endpoint health` over loopback — tst3 could answer
    that within milliseconds of starting. Whether tst0 (the leader,
    handling `member add`) had already re-settled its own outbound
    rafthttp connection to tst3 well enough for etcd's internal check to
    count it as "connected" is a separate, slightly slower thing, with no
    CLI-visible signal — neither `member list` nor `endpoint health`
    exposes it.
  - An unmodified second run succeeded outright — the extra real time
    between attempts (re-running the install stage's package checks etc.)
    was, on its own, enough for that gap to close, which is exactly what
    a transport-reconnection race predicts, and rules out a deeper or
    permanent problem.
  - No etcd-published constant for how long this actually takes turned
    up (checked the docs and several real-world GitHub issues hitting
    the same error string).
  - **Revised same day**: the first fix was a blind `sleep 10` task
    between `confirm-4-healthy` and `register-tst4` — a fixed cost paid
    on every run whether or not it was actually needed, and still just a
    guess at the settle time. Replaced with a bounded retry (6 attempts,
    5s apart, 30s max) around the `member add` call itself, inside
    `registerScript`, so it retries the actual operation that's failing
    instead of guessing how long to wait before trying once. This also
    means registering tst3 (nothing to settle, since it's the first
    addition to an already-stable cluster) now costs zero added delay,
    where the blind sleep would have paid the same fixed cost for both
    registrations regardless of need. Still not a *guarantee* — 30s is
    itself an arbitrary ceiling, not a derived value — but it only waits
    as long as the real failure persists, rather than a fixed amount
    either way. A more rigorous fix would poll something that actually
    reflects rafthttp connection state, which `etcdctl` doesn't currently
    expose in scriptable form.
- **Confirmed** (2026-09-30, after the two fixes above): grew tst0/tst1/tst2
  to a full 5-node cluster (tst3, then tst4) end-to-end, `confirm-5-healthy`
  reaching 5/5 in ~0s.

### Fixed — `deploy-agent` now confirms the install actually completed, instead of just that it was launched

Closes a gap the README has flagged since the `version` RPC was added:
`deploy-agent`'s install runs fully detached from the agent's own
process (`systemd-run --no-block`), because the agent's own service
restart otherwise kills the `dpkg -i` it just spawned mid-unpack — so
the RPC call that launches the install can never itself wait for it to
finish. `success = true` on the install's `RESULT` event only ever
meant "the detached install was launched," never "the agent is now
running the new version," and nothing closed that gap until now.

- **`NodeClient.pollForVersion`** (new) — same shape as the existing
  `pollUntilBack` (used by `reboot --wait`): polls `getVersion` every
  5s until it reports the expected version or a timeout elapses,
  tolerating the few seconds of unreachability around the agent's own
  restart rather than treating them as failure.
- **`Orchestrator.deployDeb`** — now pushes and installs, then polls
  every target node via `pollForVersion` (60s default timeout) before
  returning, and returns `Map[String, Boolean]` (per-node confirmed)
  instead of `IO[Unit]`.
- **`Orchestrator.requireOrpheraAgentPackage`** — already ran
  `dpkg-deb -f <path> Package` to verify the `.deb` being pushed is
  actually `orphera-agent`; now also reads `Version` in the same call
  and returns it, so `deployDeb` has an expected version to poll for
  that can never drift from what's actually being installed — it's
  read from the exact file being pushed, not typed in separately
  anywhere.
  **Caught in real use, fixed same day**: the first version of this
  assumed `dpkg-deb -f <path> Package Version` prints two bare,
  unlabeled values like a single-field query does — it doesn't.
  Asking for more than one field switches `dpkg-deb` to labeled
  `Field: value` lines instead, so every real `.deb` was being rejected
  with "its Package field is 'Package: orphera-agent', not
  'orphera-agent'" — a correct package always reported as wrong.
  Fixed by parsing `Field: value` pairs into a map instead of assuming
  bare positional values. Second bug caught right after, from the same
  real run: a missing/typo'd `.deb` path also came back through
  `dpkg-deb exited 2`, which the code treated identically to "file
  exists but isn't a valid .deb" — "could not read its package
  metadata — is this a valid .deb?" for a file that was never there at
  all. Now checked explicitly with `Files.exists` before ever invoking
  `dpkg-deb`, so a bad path says "No such file" instead of implying a
  corrupt one; the remaining `dpkg-deb`-failed case now also echoes
  its actual output rather than just its exit code, for whatever's
  left that isn't a missing file or a wrong package.
- **`Main.scala`**'s `deploy-agent` dispatch no longer goes through
  `withTargets` (which always reports `ExitCode.Success` once its
  action runs, regardless of what that action actually did — fine for
  commands with no real per-node pass/fail signal, wrong for this
  one). It now prints a `N/M node(s) confirmed running the new
  version` summary naming any unconfirmed nodes, and the process exits
  non-zero if any node wasn't confirmed within the timeout.
- Deliberately unchanged: `bootstrap` (SSH-based first-time install, no
  existing agent to poll against — its own success is still only as
  good as the SSH session's exit code) and the agent-side
  `DebInstaller.verifyPackageName` check (a separate, independent
  package-name check — the real trust boundary on that path, per its
  own doc comment — not affected by this).
- README's CLI reference, Versioning section, and Known gaps entry for
  this updated to match (the gap entry struck through rather than
  deleted, to keep the "hit and fixed in practice" context around the
  detached-install design intact).

### Added — command audit trail (`.orphera-audit/audit.jsonl`) and `orphera audit-log` to view it

New `AuditLog.scala`: every mutating CLI invocation (`install`,
`remove`, `autoremove`, `copy`, `network-apply`, `deploy-agent`,
`bootstrap`, `teardown`, `playbook`, `reboot`, `cluster-playbook`,
`run`) now gets a `command_start`/`command_end` pair appended to
`.orphera-audit/audit.jsonl`, correlated by a random `invocation_id`,
recording what ran, against which nodes, when, and whether it
succeeded. Read-only/informational commands (`facts`, `version`,
`uptime`, `log-summary`, `audit-log`, `help`, `fetch`) are
deliberately excluded — this is a trail of changes, not a general
access log.

- `Main.scala`'s `dispatch` wraps only the commands
  `AuditLog.isAuditable` marks true — everything else goes straight to
  `dispatchCommand` unaudited. `args` gets parsed twice on the audited
  path (once to classify, once inside `dispatchCommand` to actually
  run), a deliberate trade to avoid restructuring the existing,
  already-verified command match.
- Own small JSON-Lines writer (`AuditLog.toJson`) rather than reusing
  `RunLog`'s — that one's tuned for one file per playbook run; this is
  one flat, always-growing file across every mutating command,
  including ones with nothing to do with playbooks at all. Deliberately
  a duplicate of `RunLog.toJson`'s encoding rather than a shared
  extraction, to avoid touching that already-tested file for this.
- Best-effort: a failure to write to the audit log never fails the
  command it's auditing (logged as a warning instead).
- Real limitation, worth being upfront about: no per-operator identity,
  only the shared `ORPHERA_TOKEN` — this records WHAT ran, WHEN,
  against WHICH nodes, and its OUTCOME, not reliably WHO ran it beyond
  "someone with the token from this control host." Also unrotated in
  this first version — `audit.jsonl` grows forever.
- **`orphera audit-log [--limit N]`** (new `Command.ShowAuditLog`,
  default `--limit 20`) — renders the trail as a table (time, command,
  nodes, status, duration, and any command-specific detail fields) by
  correlating `command_start`/`command_end` pairs from the file.
  Implemented in `LogSummary.scala` alongside `log-summary` rather
  than a third parallel JSON parser, since `AuditLog.toJson` emits the
  same flat-object grammar `RunLog.toJson` does — `log-summary` covers
  one playbook run's task-level detail, `audit-log` covers the flat
  trail across every mutating command ever run here. An invocation
  with no matching `command_end` (still running, or the process was
  killed before it could record one) is shown as `IN PROGRESS / NO END
  RECORD` rather than silently dropped.
- Bash completion (`orphera-completion.bash`) updated for both new
  verbs — `log-summary` (previously missing from the verb list
  entirely) and `audit-log`. Also fixed, found in the process:
  `deploy-agent` and `bootstrap` completion both still treated the
  `.deb` path as a bare positional argument, completing it right
  after the verb — stale since both commands moved that path behind
  `--file` (auto-discovered when omitted); `--file` was missing from
  both entirely. Both now complete `--file <path.deb>` correctly.

### Added — `manifests/test_observability.sh`: regression test for `RunLog.scala`'s structured event log

Verifies the `.orphera-logs/*.jsonl` output itself, not just the console
lines — the gap left after the logging feature below shipped without a
test of its own.

- Reuses `restartability_test.yaml`'s existing 3-run cycle (no
  `--resume` / no `--resume` / `--resume`) and, on top of the
  console-output assertions `test_restartability.sh` already makes,
  parses each run's own log segment and checks the structured events
  line up with what actually happened: exactly one `run_start`
  (`playbook`, `target_count`) and `run_end` (`success`, numeric
  `duration_ms`) per run; `task_start`/`task_end` with correct
  `success`/`duration_ms`/`error`; `task_skipped`
  (`reason=resume_checkpoint`) on the resumed run, with no
  `task_start` for that task; and that a task never reached after a
  failure (task3, in run 1/2) doesn't appear in the log at all.
- **`manifests/observability_condition_test.scala`** — a small new
  flat-`Playbook` fixture (built against the real `PlaybookDsl`, not a
  guessed YAML `when:` schema), added because `restartability_test.yaml`
  never exercises a `when:`-gated task and so never produces a
  `task_skipped`/`reason=condition_not_met` event. Its second task is
  gated on a fact condition that can never be true
  (`"facts.os_id" === "this-os-id-does-not-exist"`), which proves that
  reason code is emitted correctly, the gated task is never
  `task_start`'d, and — unlike a genuine task failure — execution
  carries on to the next task and the run still succeeds overall.
- Each run's log segment is located by finding the most-recently-modified
  file matching that manifest's log glob and reading from its *last*
  `run_start` to end of file, rather than assuming one file equals one
  run — `RunLog` filenames only have one-second resolution, so two runs
  launched inside the same second land in, and get appended to, the
  same file.
- **Bug caught and fixed while building this**: the first version piped
  each run's assertion output through a bash function
  (`... | apply_verdicts`) to tally pass/fail counts — every stage of a
  bash pipeline runs in its own subshell, so that function's counter
  increments were silently discarded the moment the pipe closed, and
  the script reported far fewer passes than it actually ran (5 instead
  of 25, in the first real run against tst0). Fixed by capturing each
  block's output via command substitution and feeding it to the
  tallying function through a herestring instead, which runs in the
  current shell.

### Added — `manifests/iperf3_test.yaml` / `manifests/iperf3_test.scala`: parallel network throughput test

New example manifests, in both front-ends, for a real non-Ceph use case:
tst1 and tst2 run `iperf3` clients simultaneously against a self-hosted
`iperf3` server on tst0 (star topology — tst0 isn't its own client,
and this isn't full mesh between every pair). Demonstrates staged
node-subset targeting (install on all three, start the server on tst0
only, run clients on tst1+tst2 only) and genuinely parallel task
execution within the client stage.

- A default `iperf3 -s` instance only serves one client connection at a
  time, so tst0 runs one server unit per client, each on its own port,
  rather than one shared server the two clients would otherwise queue
  behind.
- Per-node port selection is done with a shell `case` on the node's own
  hostname inside the task command — neither DSL front-end supports
  per-node task parameterization within a single stage/node-list, so
  the differentiation has to live in the command itself.

### Fixed — starting a long-lived daemon from a `RunCommand` task with `nohup ... &` hangs until timeout, then gets SIGTERM-killed

Found while building the iperf3 test above: starting the `iperf3`
server with `nohup iperf3 -s ... &` inside a `RunCommand` task
consistently failed with `exit=143` (SIGTERM) and no output from the
script at all — not a task-specific bug, a general hazard of
backgrounding a never-exiting process from inside an orchestration
task. `RunCommand` waits for the command's process tree/output to
fully close before considering the task done; a `nohup ... &` daemon
that's meant to run forever never lets that happen, so eventually the
task's own timeout fires and kills the whole thing, server included.

- Fixed by using `systemd-run --unit=<name> --collect -- <command>`
  instead: it hands the process off to systemd immediately and
  returns, so the task genuinely finishes right away and the daemon's
  lifecycle is no longer tied to the task's own process tracking.
  General pattern, not specific to iperf3 — worth reaching for
  `systemd-run` (or an installed systemd unit) any time a playbook
  needs to start something that's meant to keep running after the task
  that started it has finished.

### Changed — OSD disk selection moved into the shared inventory, out of two independently-hardcoded manifests

Direct follow-up to the by-id fix below: the first pass at that fix put
a hardcoded `Map[String, List[String]]` of by-id paths in
`cephadm_teardown.scala` AND a separately hardcoded copy in
`cephadm_add_osds.scala` — two independently-maintained lists, which is
exactly the shape of bug that caused the original `/dev/sdX` incident
(two things that need to agree, silently drifting apart). One of the
two by-id entries for tst0 was in fact wrong in the first pass, in only
one of the two files.

- Per-host disk paths now live in `manifests/inventory.yaml`, using
  Orphera's existing `Node.vars` mechanism (already used for
  `cluster_ip`, `ceph_role`, etc.) — two new comma-separated vars,
  `osd_disks` (what `cephadm_add_osds.scala` turns into OSDs) and
  `zap_disks` (the full set `cephadm_teardown.scala` wipes, deliberately
  broader than `osd_disks` for the same "broad zap" reasoning as
  before).
- Added `Inventory.csvVar(nodeName, key)` — reads a comma-separated
  list-valued var off a single node. Kept generic (not disk-specific)
  since `Node.vars` is otherwise String-valued only and any future var
  could reasonably want to be a list. Also added `Inventory.varsFor`,
  a single node's own vars (existing `groupVarsFor` only covered group
  vars).
- Both `cephadm_teardown.scala` and `cephadm_add_osds.scala` now read
  device lists via `Inventory.csvVar(host, ...)` instead of a literal
  `Map`, so there is exactly one place per host's disks are declared.
- `test_ceph_lifecycle.sh`'s `EXPECTED_OSD_COUNT` — already fixed once
  today to stop being a hand-maintained constant, by grepping by-id
  string literals out of `cephadm_add_osds.scala` — broke again the
  moment device selection moved into `inventory.yaml`'s comma-joined
  vars (a line-count grep can't see multiple devices on one YAML line).
  Replaced that static analysis entirely: it now reads the real number
  out of `cephadm_add_osds.scala`'s own run output (its confirm-osds
  stage's "All N specified OSD device(s) are up and in." message, which
  is `osdDevices.values.map(_.size).sum` computed live), rather than
  restating the config from any file a second time.

### Fixed — `test_ceph_lifecycle.sh`'s `EXPECTED_OSD_COUNT` was a stale hand-maintained constant

Surfaced immediately after the by-id device-identification fix below:
with disk identification actually correct, `cephadm_add_osds.scala`
created all 4 of its intended OSDs (2 on tst0 + 1 each on tst1/tst2) for
what may be the first time, and the test script failed 3 assertions —
not a regression, but its own `EXPECTED_OSD_COUNT=3` constant having
been wrong all along (masked by the disk-identification bug silently
capping tst0 at 1 successful OSD in every prior run, which made "3"
look correct by accident).

- `EXPECTED_OSD_COUNT` is no longer a hand-maintained constant. It's
  derived at test-run time by counting `/dev/disk/by-id/` entries
  directly in `cephadm_add_osds.scala`, so there's one source of truth
  and this can't silently drift out of sync with the manifest again.

### Fixed — `cephadm_add_osds.scala`/`cephadm_teardown.scala` identified disks by unstable `/dev/sdX` letters

Live incident on tst0 (2026-09-28), traced back from a `ceph orch daemon
add osd tst0:/dev/sdb` failure ("Device /dev/sdb has partitions"):
`/dev/sdX` letters are assigned by kernel enumeration order at boot and
are **not** guaranteed stable across a reboot. A reboot moved tst0's OS
disk onto `/dev/sdb` — the exact device `osdDevices`/`devicesToZap` had
hardcoded as a spare OSD disk — so `cephadm_add_osds.scala` was
attempting to add the running root filesystem's own disk as an OSD.
`ceph-volume`'s "has partitions" refusal is what actually prevented
damage here; it was luck that the disk was rejected before anything
destructive ran against it, not a property either playbook was designed
to guarantee. `cephadm_teardown.scala`'s zap script ran against the same
disk on every teardown for the same reason — harmless only because a
mounted filesystem generally refuses `wipefs`/`dd` without `-f`.

- Both manifests now identify disks by their `/dev/disk/by-id/...` path
  (tied to the underlying QEMU drive's SCSI address, fixed at
  VM-definition time) instead of `/dev/sdX`. This is also Ceph's own
  recommended practice for DriveGroup device specs, for the same reason.
  Per-host by-id mappings were confirmed via `lsblk` + `ls
  /dev/disk/by-id/` against all three hosts post-incident — note the
  mapping is **not** the same across hosts (each host's OS disk happens
  to sit at a different SCSI address).
- `cephadm_teardown.scala`'s zap script also gained a mounted-device
  guard (`lsblk -rno MOUNTPOINT`) that refuses to touch a device if it
  or any of its partitions is currently mounted, independent of whether
  the by-id device list is correct — defense in depth against this same
  class of bug recurring, rather than relying solely on getting the
  device identification right.
- **Correction, same day**: the first version of this fix mistranscribed
  tst0's own by-id mapping (swapped which SCSI slot was the OS disk vs.
  a data disk), so `tst0`'s entries pointed at the OS disk again under
  its by-id path instead of `/dev/sdb`. `ceph-volume` rejected it the
  same way for the same reason, and the mounted-device guard above is
  what should have kept the intervening teardown run from touching it.
  Corrected `tst0`'s two data-disk entries to the right SCSI slots.
  tst1/tst2's mappings were verified correct throughout.

### Fixed — `cephadm_teardown.scala` leaving a stale GPT backup header, resurfacing after a host reboot

Found via a real `cephadm-add-osds` run failing with `Device /dev/sdb
has partitions` on a host that had been torn down and — as far as any
command run at the time could tell — cleanly zapped.

- `zapDevicesScript`'s device wipe zeroed only the first 10MB of each
  disk. GPT keeps a second, backup partition table at the *end* of the
  disk by design, specifically so it survives damage to the front —
  zeroing just the front removes the primary header (so nothing sees
  partitions right after teardown) but leaves the backup header
  intact. The next full rescan of the disk from scratch — a host
  reboot, in particular — finds that backup GPT table and reconstructs
  `/dev/sdb1` etc., so a later `add osd` run fails even though nothing
  touched the disk in between the teardown and the failure.
- Fixed by running `sgdisk --zap-all` first (destroys both the primary
  and backup GPT structures, plus any MBR), and by additionally
  zeroing the last 10MB of the disk, not just the first. `wipefs`/`dd`
  (front)/`partprobe` stay as belt-and-suspenders for anything
  `sgdisk` doesn't recognize.

### Added — structured, machine-parseable event logging (`RunLog.scala`)

The other half of the gap flagged alongside restartability: output has
only ever been human-readable console lines
(`[tst2] install cephadm prerequisites: ...`), with nothing a script or
dashboard could parse without screen-scraping.

- Both `PlaybookRunner` and `ClusterPlaybookRunner` now write one JSON
  Lines file per run — `.orphera-logs/<kind>-<playbook-name>-<yyyyMMdd-HHmmss>.jsonl`
  — alongside (not instead of) the existing console output. Nothing
  about current behavior changes; this is purely additive.
- **Task/stage lifecycle only, deliberately** — `run_start`/`run_end`,
  `stage_start`/`stage_end` (cluster-playbook only), `task_start`,
  `task_end` (with `success`, `duration_ms`, and `error` on failure),
  and `task_skipped` (with `reason`: `condition_not_met` or
  `resume_checkpoint`). Not every streamed `PROGRESS`/`OUTPUT` line —
  that would make log files large for little benefit, since the
  console output (and, for Ceph specifically, `ceph -s` itself) already
  covers "what did this command print."
- **No new dependency**, same reasoning as `Checkpoint.scala`: this
  project's `orchestrator` module doesn't have `circe-parser`
  available, and a flat, dozen-or-so-key event object doesn't need a
  general JSON library. `RunLog.toJson` is a deliberately minimal,
  non-general encoder (flat objects, String/Boolean/Int/Long/Double/
  Option values only) — sufficient for every event this file emits.
- Always-on, no flag — it only ever appends a separate file and never
  touches stdout, so there was nothing to gate behind an opt-in.

### Added — `--resume`: checkpoint-based restart for `playbook`/`cluster-playbook`

Addresses a real, previously-open gap: a staged run (e.g.
`cephadm_add_osds.scala`'s `apply-osd-spec`, adding OSDs across 5
separate `host:device` pairs) that succeeds on some and fails on
another had no safe way to continue — re-running the whole playbook
replays the already-succeeded `ceph orch daemon add osd` commands,
which are not idempotent (the device already carries an OSD's LVM
signature by then, producing the same "device has a signature" class
of error this project already hit and fixed once in
`cephadm_teardown.scala`, for an unrelated reason).

- **`Checkpoint.scala`** — per-(node, task-name) completion tracking,
  shared by both `PlaybookRunner` and `ClusterPlaybookRunner`. State is
  a local JSON file on the control host (`.orphera-state/<kind>-<name>.json`,
  gitignored — add it to `.gitignore` if not already there), written
  atomically (temp file + `ATOMIC_MOVE`, matching `CopyFile`'s existing
  convention) and flushed after every single task completion, not
  batched, since a crash mid-run leaving an accurate checkpoint is the
  entire point.
- **Opt-in, not automatic.** An ordinary run (no `--resume`) always
  starts this playbook's checkpoint state fresh and immediately
  overwrites any existing file for that name — behavior for every
  existing invocation is unchanged. Passing `--resume` loads the
  previous run's recorded completions first and skips any (node, task)
  already marked done, printing `skipped (already completed in a
  previous run — resuming)` in place of running it. Every run — resumed
  or not — still records completions as it goes, so a *subsequent*
  `--resume` always has accurate state to work from.
- **`orphera playbook <file.yaml> --resume`** /
  **`orphera cluster-playbook <file.yaml> --resume`** — wired through
  `Cli.scala`/`Main.scala` for the YAML and registered-DSL-name paths.
- **The `.scala`-script path needed a different mechanism.** The actual
  motivating case (`cephadm_add_osds.scala`) runs via
  `runScalaPlaybookScript`, a separate `java` subprocess running an
  `OrpheraPlaybook`/`OrpheraClusterPlaybook` object — both
  `IOApp.Simple`, whose `run: IO[Unit]` has no access to process args at
  all. Rather than changing that trait's shape (which would ripple into
  `scripting/Main.scala`'s arg-forwarding, not touched here), `--resume`
  is passed to that subprocess as an `ORPHERA_RESUME=true` environment
  variable instead, which both traits read directly. Same checkpoint
  mechanism underneath either way.
- **Known limitation, accepted for a first version:** a task is
  identified only by `(node name, task name)` — renaming, reordering,
  or duplicating a task name between runs will confuse resumption. Same
  fragility as Ansible's `--start-at-task`; a content-hash-based task
  identity was considered and deferred as more machinery than this
  first version needs.
- **Also deliberately deferred:** state lives on the control host, not
  the target agent — resume only works from the same host/working
  directory that produced the checkpoint file. Revisit with an
  agent-side RPC if that portability becomes a real requirement.
- **`Checkpoint.scala` rewritten to drop a circe dependency that
  doesn't exist here.** The first version used `io.circe.parser.decode`
  to read the checkpoint file back, which failed to compile
  (`value parser is not a member of io.circe`) — `circe-parser` isn't
  actually a dependency of the `orchestrator` module (only whatever
  `circe-yaml` pulls in for `PlaybookYaml.scala`). Rather than guess at
  another circe API or add a new dependency blind, the format was
  dropped to plain tab-separated text (`<node>\t<task name>` per line)
  — zero library dependency beyond `java.nio.file`, appropriate for
  what is genuinely a small set of `(node, task)` pairs.
- **Verified end-to-end against real hosts, not just compiled.** Two
  new regression tests, both built the same way as this cycle's other
  test scripts: force a genuine partial failure deterministically (a
  task that fails until a marker file exists on the target), then prove
  three things — the checkpoint file records exactly the tasks that
  actually succeeded; a second run *without* `--resume` re-executes
  everything (default behavior provably unchanged); a run *with*
  `--resume` skips the already-completed task and actually executes the
  rest through to success.
  - `manifests/test_restartability.sh` (+ `restartability_test.yaml`)
    — the flat-`playbook`/YAML path, `--resume` as a direct CLI flag.
    Run against real hosts: **8/8 assertions passed.**
  - `manifests/test_restartability_cluster.sh` (+
    `restartability_cluster_test.scala`) — the staged
    `cluster-playbook`/`.scala`-script path, exercising `ORPHERA_RESUME`
    via the actual subprocess mechanism rather than trusting that the
    flat-playbook test's pass implies the cluster path also works. This
    is the path `cephadm_add_osds.scala` (the original motivating case)
    actually uses.

### Fixed — `orphera playbook`/`orphera cluster-playbook` always exiting 0, even on a genuine failure

Root cause, in both `PlaybookRunner.run` and `ClusterPlaybookRunner.run`:
a failed task, or a stage that failed/never became healthy, was only
ever **printed** (`"... FAILED — ..."` / `"... aborting remaining
stages."`) and then the enclosing `IO` completed normally as
`IO[Unit]` — nothing raised, nothing signaled failure upward. `Main.scala`'s
dispatch for both commands compounded this by hardcoding
`>> IO.pure(ExitCode.Success)` after calling `run(...)`, so the process
exit code was unconditionally 0 regardless of what actually happened.
This is exactly the behavior `test_ceph_lifecycle.sh`'s `run_playbook`
helper had to work around by grepping output for `"aborting remaining
stages"` instead of trusting `$?` — that workaround is no longer
strictly necessary, though it's harmless to leave in place.

Fixed by changing both runners' signatures from `IO[Unit]` to
`IO[Boolean]` (did everything succeed), threading that signal up
through every branch that previously only printed on failure
(`PlaybookRunner.runTasks`'s `Left(err)` case; `PlaybookRunner.run`'s
`parTraverse_` → `parTraverse` + `.forall(identity)`, since
`parTraverse_` discards per-node results entirely;
`ClusterPlaybookRunner.runStages`'s both branches), and updating
`Main.scala`'s two call sites to map that boolean to a real
`ExitCode.Success`/`ExitCode.Error` instead of hardcoding success.

Missed on the first pass and caught by the compiler on the next build:
**`OrpheraPlaybook`/`OrpheraClusterPlaybook`** — the traits that let a
DSL playbook run as its own standalone `IOApp.Simple` (the
`orphera playbook`/`cluster-playbook somefile.scala` path, via
`runScalaPlaybookScript`'s separate `java` process + `waitFor()`) also
call `PlaybookRunner.run`/`ClusterPlaybookRunner.run` directly and
require `IO[Unit]`, not `IO[Boolean]` — `sbt compile` failed with two
`Found: IO[Boolean] / Required: IO[Unit]` errors at
`OrpheraClusterPlaybook.scala:18` and `OrpheraPlaybook.scala:12` once
the runner signatures above changed. Since `IOApp.Simple` only exits
the process nonzero when its `IO` actually raises, both traits now
`flatMap` the runner's boolean and `IO.raiseError` on `false` rather
than trying to return it directly — this is also what makes the
standalone-script path's exit code correct, not just the ordinary
`Main.scala` dispatch path.

**Regression test added:** `manifests/test_exit_code_regression.sh`,
asserting `$?` directly (not grepping output, unlike
`test_ceph_lifecycle.sh`'s existing workaround for this exact bug) —
a deliberately-failing task/stage and a deliberately-succeeding control
for both the flat-playbook and staged-cluster-playbook paths
(`exit_code_{fail,pass}.yaml`, `cluster_exit_code_{fail,pass}.yaml`),
so the test can't pass by coincidence.

### Fixed — `cephadm_teardown.scala` leaving orphaned LVM/device-mapper state across repeated runs

Found via repeated end-to-end runs of `test_ceph_lifecycle.sh` against
the same VMs, not in theory. Three separate, causally-confirmed bugs,
each masking the next until the previous one was fixed:

1. **Fsid auto-inference broke on a host carrying more than one prior
   cluster's state.** The original teardown only picked
   `cephadm ls`'s first-listed fsid (`ls[0]["fsid"]`) and called
   `cephadm rm-cluster` for just that one, leaving any other
   `/var/lib/ceph/<fsid>` directory in place. A later `cephadm
   bootstrap`/`cephadm shell` on that host then failed outright with
   `Cannot infer an fsid, one must be specified`, since cephadm refuses
   to guess when more than one candidate directory exists. Fixed by
   enumerating **every** fsid `cephadm ls` reports and calling
   `rm-cluster --fsid <fsid> --force` for each, followed by an
   unconditional `rm -rf /var/lib/ceph/* /etc/ceph/* /var/log/ceph/*
   /var/run/ceph/*` sweep so no orphaned fsid directory (e.g. from a
   crashed/partial earlier run with nothing left to report it) can
   survive to confuse the next inference.
2. **A daemon can still be running, and holding a device open, while
   `cephadm ls` reports zero daemons for that host.** Confirmed
   directly: `apply-osd-spec` failed on a host that `cephadm ls` (and
   therefore the teardown's own rm-cluster loop) had just reported as
   having no daemons at all, with the exact same "device has a
   signature" / "Refusing to zap the mapper device" error a live OSD
   produces. Most likely cause: an earlier cleanup's directory sweep
   (bug 1's fix, or an even earlier ad hoc one) deleted a daemon's
   `/var/lib/ceph/<fsid>/<daemon>` metadata without stopping its
   container first, orphaning a still-running podman container that
   `cephadm ls` can no longer enumerate (it reads that metadata to know
   what exists) — permanently, since every future teardown's own
   metadata-based view has the same blind spot. Fixed by no longer
   relying on `cephadm ls`/`rm-cluster` as the only way containers get
   stopped: teardown now runs `podman stop -a; podman rm -fa`
   unconditionally, first, on every host — safe here since these are
   single-purpose Ceph test nodes with no other podman workload to
   protect.
3. **A device-mapper LV can outlive the LVM metadata that created it.**
   Even after fix 2, a *different* host still failed the same way on
   one specific disk, with the zap log showing the other disk's VG/LV
   being genuinely removed but silence for this one — meaning `pvs -o
   vg_name <dev>` found no VG to report, so the existing zap loop's
   `for vg in $(pvs ...)` simply skipped that device, while the kernel
   still had an active `/dev/mapper/ceph--<uuid>-osd--block--<uuid>`
   entry for it. Root cause: once a device's on-disk LVM signature has
   been wiped (by a prior, incomplete zap), `pvs`/`vgs` can no longer
   see the VG/LV, but device-mapper's own table entry is independent of
   that on-disk metadata and persists until explicitly torn down with
   `dmsetup remove` — no process needs to hold it open. Fixed with a
   host-wide `dmsetup ls | grep '^ceph-' | xargs -n1 dmsetup remove -f`
   sweep, run unconditionally ahead of and independent of the
   `pvs`-based per-device loop, since dm names aren't tied to a
   specific `/dev/sdX` path the way the existing loop is keyed.

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

A long investigation (dpkg-lock timing, `PATH`, stray manifest
debugging leftovers, suspected concurrent invocations, virtualized
disk I/O/page-cache settling) before finding the two actual,
causally-confirmed bugs — neither of which was a timing race at all:

1. **The gRPC stream closed before the real install had finished.**
   `AgentServiceImpl`'s `install` RPC streams events with
   `takeThrough(_.kind != Event.Kind.RESULT)` — the stream ends the
   instant a `RESULT` event is queued. `AptInstaller.install`'s
   optional `apt-get update` sub-step (run when `updateCache: true`)
   was implemented using the same shared `run()` helper as every other
   command, which unconditionally emits a terminal `RESULT` event at
   the end of *any* process it runs. So when `updateCache: true`, that
   `RESULT` closed the stream right after `apt-get update` finished —
   the orchestrator saw `success=true` and moved on to the next task,
   while the real `apt-get install` (chained afterwards with `>>` on
   the same fiber) was still running invisibly in the background.
   Fixed by adding `runNoResult` — identical process-execution logic to
   `run`, but it never emits a `RESULT` event (raising instead on a
   non-zero exit) — and using it for the `update` sub-step, so only the
   install itself, via the (also new) `runInstallWithPostCheck`, is
   allowed to close the stream.
2. **The post-install binary-readiness probe false-failed on binaries
   that don't support `--version`.** Once the stream-closing bug above
   was fixed, a second, independent bug surfaced: the readiness check
   in `waitForBinariesVisible` validated each installed binary by
   spawning `<path> --version` and requiring exit code `0` or `1`. Two
   binaries shipped by `ceph-common`/`ceph-base` — `/usr/bin/crushdiff`
   and `/usr/bin/ceph-crash` — don't accept `--version` at all (it's an
   unrecognized argument for either), so they failed this probe every
   time regardless of timing, correctly triggering the newly-added
   failure path and reporting the whole install as failed. Fixed by
   replacing the `--version` probe with a plain launchability check:
   confirm the file exists, is executable, and a process can actually
   be started from it (stdin from `/dev/null`, output discarded, a
   2-second timeout, treating a successful *start* — not any
   particular exit behavior — as "ready").

Two of my own bytecode-based verification checks produced false
negatives while confirming these fixes were actually deployed:
`grep -c` against `javap` output for a closure-captured local variable
name (`allFailed`) and for a string-interpolation literal
(`"Command failed with exit"`) both returned 0 on correctly-deployed
code, because Scala mangles local names in nested lambdas and splits
interpolated strings across separate bytecode constant-pool fragments.
Checking for an intact **method name** instead (`javap -p | grep
runNoResult`) is the reliable way to confirm a given fix is present in
a deployed jar.

The debugging process itself is worth remembering: several rounds
chased plausible-sounding theories (dpkg lock state, `PATH` contents,
LXC/overlayfs caching — later corrected once it was confirmed these
are KVM VMs, not containers — and virtualized-disk I/O settling) that
turned out to be unrelated to either real bug. Both were found only by
reading the actual RPC streaming and process-execution code path
end-to-end, not by adding timing workarounds.

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
