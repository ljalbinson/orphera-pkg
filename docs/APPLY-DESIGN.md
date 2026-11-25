# Orphera: declarative apply (design)

Status: step 1 built 2026-11-12 (core, files, directories, symlinks, commands, check mode, `orphera
apply`, `Task.Apply`, `node.apply`; the types are `ApplyResource` and `ApplyOutcome`, since `Resource` and
`Outcome` were taken), with the two cache-client descriptions of step 3 (run on the lab, and since
2026-11-13 they have replaced the cluster playbooks under the same names); step 2 (packages, services,
users, groups, service restart on notification, reading a resource again after a change) built
2026-11-13; step 4 (YAML descriptions, `ApplyYaml`) built 2026-11-13. Proposed
2026-11-12. Closes the "No standalone declarative file-manifest mechanism (`apply`)"
entry in the README's known gaps, and most of "check mode covers Placement only".

## Summary

Orphera has two ways to change nodes today. A **playbook** is a fixed list of steps run in order. A
**script** (`OrpheraScript`, `docs/SCRIPT-DSL-DESIGN.md`) is steps whose next move depends on what the last
one returned. Both say *what to do*. Much of what the manifests do is really *how a node should be*: this
file with this content, this package installed, this service enabled and running, and a restart if the
file changed. Written as steps, that is a shell heredoc that rewrites the file every run, and "would
anything change?" cannot be answered without running it.

`apply` is the third mode: a description of the desired state of a node, as a set of **resources**.
Orphera reads what is on the node, works out what differs, and changes only that, in a sound order.
Running it again on a node that is already right changes nothing and says so. Because reading and
changing are separate steps for every resource, **check mode comes for free**: `--check` reads and
compares, shows what would change (with a diff for files), and changes nothing.

It is not a replacement for playbooks or scripts. A description can be applied on its own, as one task
in a playbook, or from a script (`node.apply(...)`), so a script can bring a node to a known state and
then decide what to do next.

## Before and after

`rpm_cache_clients.scala` today: one shell step that renames the stock repository files, writes the
cache repository file with a heredoc, and clears dnf's metadata, every time.

```scala
stage("point-dnf-at-the-cache", clients*)
  .task("write the cache repository file and set the stock repos aside")(
    Task.RunCommand(List("sh", "-c", setupScript), timeoutSeconds = 180)   // mv, cat > file <<REPO, dnf clean
  )
```

As a description:

```scala
object rpm_cache_clients extends OrpheraApply:
  def nodes = List("tst8")

  def describe(node: NodeFacts) =
    val setAside = command(
      "set aside the stock Rocky repository files",
      run = """for f in /etc/yum.repos.d/rocky*.repo; do mv "$f" "$f.orphera-off"; done""",
      onlyIf = "ls /etc/yum.repos.d/rocky*.repo"
    )
    val clean = command("clear dnf's metadata", run = "dnf clean metadata -q", onChangeOnly = true)
    val repo = file("/etc/yum.repos.d/orphera-rpm-cache.repo", content = repoFile, mode = "0644")
      .after(setAside)
      .notifies(clean)
    List(setAside, repo, clean)
```

`orphera apply manifests/rpm_cache_clients.scala --check` on a node that is already set up:

```
[tst8] command[set aside the stock Rocky repository files]  unchanged (onlyIf: no match)
[tst8] file[/etc/yum.repos.d/orphera-rpm-cache.repo]      unchanged
[tst8] command[clear dnf's metadata]                       not notified
[tst8] 0 to change, 3 unchanged
```

and on one where the cache host has moved:

```
[tst8] file[/etc/yum.repos.d/orphera-rpm-cache.repo]  would change: content
[tst8]   --- /etc/yum.repos.d/orphera-rpm-cache.repo (now)
[tst8]   +++ /etc/yum.repos.d/orphera-rpm-cache.repo (wanted)
[tst8]   -baseurl=http://tst12.ljalbinson.com:8080/rocky/$releasever/BaseOS/$basearch/os/
[tst8]   +baseurl=http://tst13.ljalbinson.com:8080/rocky/$releasever/BaseOS/$basearch/os/
[tst8] command[clear dnf's metadata]  would run (notified by file[/etc/yum.repos.d/orphera-rpm-cache.repo])
[tst8] 2 to change, 1 unchanged (check: nothing changed)
```

## The model

A **resource** is one thing on one node, named by its kind and a name that is unique on the node:
`file[/etc/chrony.conf]`, `package[chrony]`, `service[chronyd]`. Every kind has the same three steps:

1. **observe**: read the current state from the node (nothing changes);
2. **compare**: the differences between that and the wanted state, as a list a person can read;
3. **change**: make exactly those differences true.

Each resource is `present` (the default) or `absent`. Only what is described is managed: a description
never removes a file, package or user just because it is not listed. That keeps a description safe to
write for part of a node.

### Resource kinds, first version

| Kind | Name | Wanted state | Observed with |
|---|---|---|---|
| `file` | path | content (text, a template rendered with the node's facts, or a local source file), mode, owner, group | the agent's existing `CheckFile` (hash, owner, group, mode); the current content only when a diff is shown |
| `directory` | path | mode, owner, group | `stat` |
| `symlink` | path | target | `readlink` |
| `package` | name | installed (any version, or a version), or absent | `dpkg-query` / `rpm -q`, by the node's `os_id` fact (Debian and Ubuntu, or Rocky and the RHEL family) |
| `service` | unit | enabled or not, running or not | `systemctl is-enabled`, `is-active` |
| `user`, `group` | name | uid/gid, groups, home, shell, system or not | `getent` |
| `sysctl` | key | value, now and persisted | `sysctl -n`, the `sysctl.d` file |
| `kernel_module` | name | loaded, and listed to load at boot | `/sys/module`, the `modules-load.d` file |
| `line` | path and line | a line present (replacing the first match of a pattern) or absent | `awk` counts of equal and matching lines |
| `authorized_key` | user and key | a public key present or absent | `awk` on the user's `authorized_keys` |
| `mount` | path | the fstab entry, mounted or not | `/etc/fstab`, `mountpoint` |
| `command` | a label | a shell command, guarded by `onlyIf` / `unless` (a command whose exit code decides) or `creates` (a path), or run only when notified (`onChangeOnly`) | runs the guard |

`command` is the way out for anything without a kind of its own, but it must say when it is needed: an
unguarded command would run every time and make the description never settle, so a command with no
guard and not `onChangeOnly` is refused when the description is built.

`gate` is a `command` that runs nothing: it only asks the node a question (`onlyIf`, `unless` or `creates`,
as for any command). A gate that answers yes is `unchanged`; one that answers no is `skipped`, and so is
every resource made `after` it (and whatever those notify), on that node only. This is how one description
holds two ways of doing a thing and lets each node take the one that fits it (`gen_set_dns.scala`: a
systemd-resolved drop-in on a node that has resolved, a static `resolv.conf` on one that does not). A skipped
resource is not a failure; the summary counts it (`3 skipped`).

`waitForHttp` and `waitForPort` describe a wait instead of a script: the URL (or host and port), the status
codes that count as an answer (`status = List(200, 300)`), the CA that signs it (`ca`), text the body must hold
(`contains`), how long to wait (`timeout`, in seconds, polled every `interval`) and where to look when it does
not come up (`hint`). They are a `command` underneath, so they report like one: `unchanged (unless: true)` when
the endpoint already answers, `ran` after a wait, and a failure that names the URL and carries the hint when the
time is up. Nothing is changed on the node. The generated shell is run against a local server by `ApplyCheck`.

`pullImages(label, images, hint, timeout)` does the same for container images: the references to have on the node, a hint for where to look when one is wrong, and the time to allow. The guard is `podman image exists` for each reference; the run pulls only the missing ones, with the retry in `kolla.Pull`.

`absentContainerUnits(List(unit -> container))` is the removal of services that run in containers: stop and disable each unit, remove its file, remove the container if podman has it, and reload systemd once if a file went. It returns the resources in order; what must follow takes them all with `.after(stopped*)`.

`sqlDatabase(label, databases, user, password, via)` and `absentSqlDatabase(label, databases, user)` describe MariaDB databases and their owner (and their removal) for a node where the `mariadb` client logs in as root. The guard of `sqlDatabase` is a real login as the user through `via`, so a dropped database or a changed password is put right by running it again; `absentSqlDatabase` runs when a database or the user exists, and also when MariaDB cannot be queried, so that a database that is down is not taken for "nothing to remove".

`rabbitAccount(label, vhost, user, password)` and `absentRabbitAccount(label, vhost, user)` do the same for RabbitMQ: a vhost and its user with full permissions (and their removal), on the broker that runs as the `rabbitmq` container on the node. The guard is the vhost, a real authentication with the password and the permissions, so a rotated password is put right by running it again.

`serverCa(label, host, port, caFile, trust, hint)` fetches the CA of a TLS server the way the services did by script: the last certificate in the chain the server presents, accepted on first use, which must be self-signed and verify the server's certificate. The guard compares the certificates only, since the text a handshake prints after them differs every time; `update-ca-certificates` also puts the CA in `/etc/ssl/certs/ca-certificates.crt`, which the containers mount.

`aptRepository(label, keyUrl, keyring, sourcesFile, repository, offers, update)` puts a repository's key in a keyring of its own and a one-line sources file beside it (`signed-by`, not `apt-key`). The guard is the keyring and the exact line, so a changed repository (a new Kubernetes minor, say) is rewritten by running it again; the key itself is not fetched again while the keyring is there.

`download(label, url, path, magic, sha256, skipIf)` and `archiveFiles(label, url, files, mode)` fetch what a step needs from the network. The download is made beside its destination and checked before it is moved, so an interrupted or wrong download never looks like the real file; the guard is the checked file itself. `archiveFiles` does not check a version: it is `unchanged` while its targets exist, so a new release means removing the old files or naming them differently.

`keystoneEntries(...)` and `absentKeystoneEntries(...)` make the Keystone entries of a service through a generated Python helper on the node (standard library, the Keystone REST API with the admin password and the CA), because Keystone is reached over the network from the node that runs the service, not from the orchestrator. The helper holds the whole description, so its check, ensure and remove always agree. It is written as a root-only file like the other helpers, and the description lives there, not in a command line, so no password appears in a command or its log. `services` may be empty for a service that only has a user (Ceilometer, Skyline); a service with two users (Telemetry) calls it once per user. A service that owns a Keystone domain (Heat's stack domain, Magnum's trustee domain) passes `domain = Some(KeystoneDomain(...))`: the helper then also makes the domain, its admin user and the admin role on the domain, its check needs that admin to get a token and hold the role, and its removal deletes every user and project the service made in the domain before it disables and deletes the domain. A service with several services in the catalogue (Heat's `orchestration` and `cloudformation`) lists them all.

`cephPool(...)` and `cephUser(...)` (and `absentCephPool`, `absentCephUser`) describe a Ceph pool and a cephx user for a node that has `cephadm`, through `cephadm shell`. The pool is initialised for RBD (or another application) when it is made, and left alone when it exists. The user has a fixed whole cephx key and its capabilities; the check compares the key in Ceph and each capability line, so a rotated key or a changed capability is put right by running it again. An old `aes` key makes the step allow `aes` on the monitors first (CVE-2025-30156), as `CephKeys.allowCiphersFor` always did. The removal steps run when the pool or user is listed or when Ceph cannot be queried, so a cluster that is down fails with its own message.

Files with secret content (`Secret` values) never show their content: the diff line says
`content (secret: not shown)`.

## Order and notifications

- **Declaration order** is the default order on a node: a list is applied top to bottom, which is what a
  reader expects.
- **`after(r)`** (`requires` in YAML) says a resource needs another one first, wherever it stands in the
  list. A resource whose prerequisite failed is skipped, and says so.
- **`notifies(r)`** says that when this resource changes, `r` runs (a `command` with `onChangeOnly`) or
  restarts (a `service`), once, after everything it depends on, however many resources notify it. This is
  the handler mechanism of playbooks (`notify:`), applied to resources.

The order is worked out when the description is built, before any node is touched: a reference to a
resource that is not in the description, a name used twice on a node, and a cycle are all errors
there, with the resources named.

## Where it is used

- **Command line:** `orphera apply <file.scala|file.yaml> [--nodes ...] [--check]`. Nodes come from the
  description's `nodes` (as in `rpm_cache_clients`) or from `--nodes` / `--node-groups`, as for `run`.
  Nodes are applied in parallel; resources within a node in order. The exit code is 0 when every node
  ended as described (or, with `--check`, every node could be read), and non-zero when something failed.
- **In a playbook:** `Task.Apply(description)` is one task; it counts as changed if any resource
  changed, so `notify:` and `when:` on it work as for any task.
- **In a script:** `node.apply(description)` returns an `ApplyReport` (per resource: unchanged,
  changed, skipped, failed, with the differences), so a script can act on it:
  `node.apply(chrony).flatMap(r => IO.whenA(r.changed("file[/etc/chrony.conf]"))(...))`.

### Scala first, YAML after

The description is a Scala value (`List[Resource]`), built with the functions above (`file`, `package`,
`service`, ...), so it can use loops, functions and the inventory, and references between resources are
values, checked by the compiler. YAML reads into the same values, for simple cases:

```yaml
name: rpm-cache-clients
nodes: [tst8]
resources:
  - command: set aside the stock Rocky repository files
    run: for f in /etc/yum.repos.d/rocky*.repo; do mv "$f" "$f.orphera-off"; done
    only_if: ls /etc/yum.repos.d/rocky*.repo
  - file: /etc/yum.repos.d/orphera-rpm-cache.repo
    source: files/orphera-rpm-cache.repo.mustache
    mode: "0644"
    requires: ["command[set aside the stock Rocky repository files]"]
    notify: ["command[clear dnf's metadata]"]
  - command: clear dnf's metadata
    run: dnf clean metadata -q
    on_change_only: true
```

## How it runs

On each node:

1. **Observe everything first**, in one call where possible: one shell command reports the state of every
   non-file resource (`stat`, `readlink`, `dpkg-query`, `systemctl`, `getent`, the guards), and files use
   `CheckFile`. A node with forty resources costs a handful of round trips, not eighty.
2. **Compare**, and print the plan.
3. **Change** in order (check mode stops before this): each change uses an existing task where there is
   one (`WriteFile` / `Copy` for files, `Install` / `Remove` for packages), so permissions, `ProtectedPaths`
   and the run log behave as they do now. Then the notified resources, once each.
4. **Report** per resource, and a summary line.

**When a resource fails**, the ones that depend on it (by `after` or by notification) are skipped, the
others still run, and the node is reported failed at the end. That leaves the node as close to the
description as possible and names everything that did not happen. A strict mode (`--stop-on-failure`)
stops the node at the first failure instead.

**A notification that could not be delivered is kept.** A file that changed has been written; if what it
notifies (a service, an `onChangeOnly` command) is skipped or fails because something before it failed, the
next run would see the file unchanged and never notify. So the run records the resources still owed a
notification on the node (`/var/lib/orphera/apply-pending/<description name>`, one resource id per line;
nothing is written when nothing is owed), says so, and the next run delivers them as if the file had just
changed (`notified by an earlier run`), then clears the record. `--check` shows the delivery and writes
nothing.

**No checkpoints.** A description is its own resume: running it again does only what is still
different. The run log (`apply-<name>.jsonl`, read by `log-summary`) records each resource's outcome.

## What it gives up, and the alternatives

- **No purging.** A description does not remove what it does not mention. Removing things is explicit
  (`absent`). Puppet-style purging of whole directories is easy to get wrong and can come later, per
  directory, if needed.
- **One node at a time.** A description is per node; there is no ordering across nodes ("tst3 before
  tst4"). That is what playbook stages and scripts are for, and both can call `apply`.
- **Observing by commands.** The first version reads state with shell commands through `run`, not new
  agent calls: no agent change, and it works with both agents today. If observing becomes slow (many
  files), a batched `Observe` call in the agents is the obvious next step.
- **Alternatives considered.** (a) Making playbook tasks idempotent one by one: every task would need its
  own "is it already so?" logic, and the order would still be fixed steps. (b) A separate tool (Puppet,
  Salt states): a second language and agent, which is what Orphera exists to avoid. (c) Templates of
  whole files only (`copy` with `.mustache`): covers files but not packages, services or users, and has no
  plan or diff.

## Plan and decisions

1. **Core and files** (the bulk of the work): `Resource`, observe/compare/change, order and notifications
   with build-time checks, `file`, `directory`, `symlink`, `command`; check mode and the plan output;
   `orphera apply <file.scala>`; `Task.Apply` and `node.apply`. Checks (`ApplyCheck`) against a fake node,
   in `make test`.
2. **Packages, services, users, groups**, by the node's `os_id` (the Debian and RHEL families).
3. **Proof:** `rpm_cache_clients.scala` and `apt_cache_clients.scala` as descriptions (small, mostly files,
   with a notified command), run on the lab with `--check` first, then for real; the old versions stay
   until they have.
4. **YAML**, reading into the same values.

Decisions, with a recommendation each:

- **Name:** `apply` on the command line, `OrpheraApply` for a description object, `Resource` for its
  parts. Recommended.
- **On a failure:** skip what depends on it, carry on with the rest, fail the node at the end
  (`--stop-on-failure` for strict). Recommended, over stopping the node at once by default.
- **YAML:** after the Scala API and the proof (step 4), not in the first version, so that the model is
  settled by real use before a second syntax is fixed. Recommended.
- **First conversions:** the rpm and apt cache clients. Recommended: small, real, and each has the
  file-then-notify shape that shows the difference from a heredoc.
- **Observation:** shell commands through `run` first, agent calls later only if needed. Recommended.
