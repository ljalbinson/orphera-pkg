<!-- SPDX-License-Identifier: Apache-2.0 -->
# Tutorial: how an apply description is put together

This tutorial reads one of the OpenStack descriptions, `manifests/kolla_cache.scala`, from top to bottom
and explains each part: what it declares, why it is written the way it is, and what `orphera apply` does
with it. It is one of the simplest in the OpenStack set: one node, no secrets, eight resources. Most of
the other OpenStack descriptions use the same parts. Section 11 then follows a run through the
orchestrator and the agent: how the file is compiled and started, how the engine reads and compares, and
which agent call carries out each change.

You should know what a node, the inventory and the agent are ([GETTING_STARTED.md](GETTING_STARTED.md)).
The design behind `orphera apply` is in [APPLY-DESIGN.md](APPLY-DESIGN.md), and the reference table of
resource kinds is in the README under *Declarative apply: `OrpheraApply`*.

## What the description builds

tst9 (10.10.5.21) runs a pull-through cache of quay.io: the stock `registry:2` image in proxy mode, as a
podman container under systemd, on plain HTTP port 5000. The OpenStack nodes pull
`quay.io/openstack.kolla/*` through it (their mirror setting comes from `keystone_single_node.scala`).
The first pull of an image goes to quay.io and is kept on tst9; later pulls are served locally.

To get there the node needs, in order:

1. podman and curl installed;
2. the registry image pulled;
3. a data directory for the cached layers;
4. a systemd unit file that runs the container;
5. that unit enabled and running;
6. the registry answering on port 5000;
7. the Kolla images in `warmImages` pulled through the cache once, so they are stored before any node
   asks for them.

Each of these becomes one resource.

## Playbook or description

The file used to be a cluster playbook: a list of steps run in order every time. A description says
instead how the node should end up. `orphera apply` reads each resource on the node, compares it with what
is wanted, and changes only what differs. Three things follow:

- A second run changes nothing and reports `0 changed`.
- `--check` reads and compares only, and prints what would change.
- A failed run can be run again; it does only what is still different. There is no checkpoint to resume.

The commands are:

```
orphera apply manifests/kolla_cache.scala --check
orphera apply manifests/kolla_cache.scala
```

## 1. The header

```scala
import orphera.orchestrator.*
import orphera.orchestrator.kolla.*
```

The first import brings in `OrpheraApply`, `Node` and `Resources`. The second brings in the container
model (`Container`, `ExternalImage`, `Mount`, `Port` and the rest) and `Pull`, the shared retry helper for
image pulls.

The long comment that follows is part of the convention: what the description builds, how to run it, and
anything a reader needs to know (disk size, plain HTTP, the cache TTL). Every description in `manifests/`
starts this way.

## 2. The object and its nodes

```scala
object kolla_cache extends OrpheraApply:
  import Resources.*

  private val node = "tst9"
  private val port = 5000
  private val remote = "https://quay.io"
  private val dataDir = "/var/lib/orphera/kolla-cache"

  override def nodes: List[String] = List(node)
```

A description is a Scala `object` that extends `OrpheraApply`. The trait asks for two things:

- `nodes`: the nodes to apply to when the command line gives no `--nodes`. `--nodes` overrides it.
- `describe(node)`: the list of resources wanted on one node (section 6).

The object name is the description's name; it appears in the log and the run name (`apply-kolla_cache`).
`import Resources.*` brings in the functions that make resources: `file`, `directory`, `pkg`, `service`,
`command` and the rest.

Everything a reader might want to change (the node, port, upstream, data path) is a named value at the
top, not a literal buried in a script.

## 3. The container as data

```scala
  private val cacheContainer = Container(
    name = ContainerName("kolla-cache"),
    image = ExternalImage("docker.io/library/registry:2"),
    mounts = List(Mount(dataDir, "/var/lib/registry", Access.ReadWrite)),
    env = List(
      "REGISTRY_PROXY_REMOTEURL" -> remote,
      "REGISTRY_PROXY_TTL" -> "8760h"
    ),
    network = Network.Published,
    ports = List(Port(port, 5000)),
    description = Some(
      "Kolla image pull-through cache (registry proxying quay.io) managed by Orphera"
    ),
    settleSeconds = 5
  )
  private val registryImage = cacheContainer.image.ref
```

The systemd unit is not written out by hand. `Container` (in `orchestrator/.../kolla/Container.scala`) is a
plain value that describes the container, and three of its members are used later:

- `cacheContainer.render`: the full text of the unit file. It is a pure function of the fields, so
  `ContainerCheck` can test it without a machine, and every OpenStack service gets the same unit layout.
- `cacheContainer.unitName`: `orphera-kolla-cache.service`.
- `cacheContainer.unitPath`: `/etc/systemd/system/orphera-kolla-cache.service`.

A few of the fields:

- `ExternalImage` because this is not a Kolla image. Kolla images use `Image(service, release, base)`,
  which builds `quay.io/openstack.kolla/<service>:<release>-<base>`.
- `Access.ReadWrite` mounts the host directory with `:z` (SELinux relabel, harmless where SELinux is off).
- `Network.Published` with a `Port`. The OpenStack services use the host network instead; the
  constructor refuses ports on the host network and the published network without ports, so that mistake
  fails when the file is loaded rather than on the node.
- `settleSeconds` is used by the container's `start` task in playbooks. The description does not use it:
  its own wait is the "registry answers" command in section 5.

## 4. Plain values for the rest

```scala
  private val tag = "2025.1-ubuntu-noble"
  private val warmImages = List("keystone")

  private val hostPackages = List("podman", "curl")
```

The tag must match the one in `keystone_single_node.scala`, as the comment above it says. `warmImages` is
the list to extend as more services use the cache.

## 5. The shell snippets: actions and their guards

A `command` resource has two pieces of shell: what to run, and a guard that says whether it needs to run.
The description keeps them as named strings, side by side, so each action can be read next to the test of
what it makes.

### Pulling the registry image

```scala
  private val pullRegistryScript =
    s"""${Pull.shellFunction(
        "the registry image name in kolla_cache.scala; the pull is from Docker Hub"
      )}
       |pull_retry $registryImage || exit 1
       |echo "registry image ready"""".stripMargin
```

`Pull.shellFunction` returns the text of a shell function, `pull_retry`, that runs `podman pull` up to
three times, 15 seconds apart, and on the last failure prints a message that includes the hint given here.
Every description that pulls an image uses it, so a registry outage looks the same everywhere.

The guard for this action is written inline later: `podman image exists $registryImage`.

### Waiting for the registry

```scala
  private val answers =
    s"""[ "$$(curl -s -o /dev/null -w '%{http_code}' http://localhost:$port/v2/)" = "200" ]"""
  private val waitScript =
    s"""for i in $$(seq 1 30); do
       |  if $answers; then echo "the registry answers on $port"; exit 0; fi
       |  sleep 2
       |done
       |echo "the registry does not answer on $port" >&2
       |exit 1""".stripMargin
```

`answers` is the test; `waitScript` repeats it for up to a minute. The same string is used for both, so the
action and the guard cannot drift apart.

A note on `$$`: these are Scala `s"..."` strings, where `$name` is Scala interpolation. `$$` writes a
literal `$`, so `$$(seq 1 30)` reaches the shell as `$(seq 1 30)`, while `$port` is filled in by Scala as
`5000`.

### Warming the cache

```scala
  private val warmScript =
    s"""set -e
       |${Pull.shellFunction("warmImages in kolla_cache.scala")}
       |for name in ${warmImages.mkString(" ")}; do
       |  pull_retry --tls-verify=false localhost:$port/openstack.kolla/$$name:$tag
       |  podman rmi -f localhost:$port/openstack.kolla/$$name:$tag
       |  echo "cached openstack.kolla/$$name:$tag"
       |done
       |curl -s http://localhost:$port/v2/_catalog
       |echo""".stripMargin
  private val warm =
    s"""C=$$(curl -s http://localhost:$port/v2/_catalog)
       |for name in ${warmImages.mkString(" ")}; do printf '%s' "$$C" | grep -q "openstack.kolla/$$name" || exit 1; done""".stripMargin
```

Pulling through `localhost:5000` makes the registry fetch the image from quay.io and keep the layers; the
local podman copy is then deleted so tst9 does not hold the image twice. The guard `warm` asks the
registry's catalog whether every image in the list is there.

### What makes a good guard

Each guard tests the result of its action, not whether the action has been run before:

| Action | Guard (`unless`: skip when it exits 0) |
|---|---|
| pull the registry image | the image exists in podman |
| wait for the registry | the registry answers 200 on `/v2/` |
| pre-pull the Kolla images | the catalog lists every image in `warmImages` |

That is why a second run changes nothing, and why the description repairs things: if someone deletes the
image or the cache is emptied, the next run puts it back.

A command must have a guard (`onlyIf`, `unless` or `creates`) or be `onChangeOnly`. One with neither is
refused when the description is loaded, because it would run on every apply and the description would
never report `0 changed`.

## 6. `describe`: the resources and their order

```scala
  def describe(target: Node): List[ApplyResource] =
    val tools = hostPackages.map(p => pkg(p, updateCache = true))
    val podman = tools.head
    val pulled = command(
      "pull the registry image",
      pullRegistryScript,
      unless = s"podman image exists $registryImage",
      timeout = 600
    ).after(podman)
    val data = directory(dataDir)
    val unit = service(cacheContainer.unitName)
    val unitFile = file(
      cacheContainer.unitPath,
      cacheContainer.render,
      mode = "0644",
      owner = "root",
      group = "root"
    ).notifies(unit)
    val running = unit.after(pulled, data, unitFile)
    val answering = command(
      "the registry answers",
      waitScript,
      unless = answers,
      timeout = 90
    ).after(running, tools.last)
    val warmed = command(
      "pre-pull the Kolla images through the cache",
      warmScript,
      unless = warm,
      timeout = 3600
    ).after(answering, podman)
    tools ++ List(pulled, data, unitFile, running, answering, warmed)
```

`describe` is called once per node and returns a list. Nothing here touches the node: it only builds
values. This one does not use `target`, because it applies to one node; descriptions such as
`nova_compute.scala` read the node's name and inventory vars from it.

### The resources

| Resource id | Made by | Wanted state |
|---|---|---|
| `package[podman]`, `package[curl]` | `pkg(p, updateCache = true)` | installed (apt or dnf, whichever the node has); `apt-get update` first |
| `command[pull the registry image]` | `command(...)` | runs unless the image exists |
| `directory[/var/lib/orphera/kolla-cache]` | `directory(dataDir)` | a directory |
| `file[/etc/systemd/system/orphera-kolla-cache.service]` | `file(...)` | this text, 0644 root:root |
| `service[orphera-kolla-cache.service]` | `service(...)` | enabled and running (the defaults) |
| `command[the registry answers]` | `command(...)` | runs unless the registry answers |
| `command[pre-pull the Kolla images through the cache]` | `command(...)` | runs unless the catalog lists the images |

Every resource has an id of the form `kind[name]`, and the id must be unique on the node. A command's name
is its label, so labels are written as short, distinct phrases.

### Values, not steps

`pkg`, `command`, `file` and the rest return immutable values. `.after(...)` and `.notifies(...)` do not
change a resource; they return a new one with the link added. Links are recorded by id. Two consequences
show here:

- `unit` is made, then `running = unit.after(...)` makes a second value with the same id. Only `running`
  goes into the list. `unitFile.notifies(unit)` still finds it, because the link is the id
  `service[orphera-kolla-cache.service]`, not the Scala value.
- `tools.head` is podman and `tools.last` is curl, because of the order of `hostPackages`. If you reorder
  that list, update those two lines.

### Order

Resources are applied in the order of the returned list, with three adjustments:

- `r.after(a, b)` puts `r` after `a` and `b`, wherever they are in the list.
- A resource that is notified comes after everything that notifies it.
- A user comes after its groups, and a file or directory after its owner and group, when those are
  described (not used here).

The links here say:

- the pull needs podman;
- the service needs the image, the data directory and its unit file;
- the wait needs the service running and curl installed;
- the warm-up needs the registry answering and podman.

The list order already matches, so the links mostly document intent and protect it: if someone moves a
line, the order still holds. They also decide what happens when something fails (section 8).

### `notifies` on a service

`unitFile.notifies(unit)` means: when the unit file changes, restart the service, once, after everything
that notifies it. Two details:

- Before any start, stop or restart, apply runs `systemctl daemon-reload`, so a changed unit file is read.
- On the first run the service is not running, so it is started, not started and then restarted. The
  restart only happens on a later run that changes the unit file (a new port, a new TTL).

A notification can go to a `service` or to a `command` marked `onChangeOnly = true`. Anything else is
refused when the description is loaded.

## 7. What a run looks like

Before a node is contacted, the description is checked: duplicate ids, relative paths, bad modes, bad
package or unit names, a command with no guard, a link to an id that is not described, a notification of
something that cannot be notified, and cycles. Every problem is listed, and nothing runs.

Then, for each node:

1. **Observe.** The state of every resource is read at once: each path's type, mode, owner, group and
   hash, each package and unit, and each command's guard (guards run here, also in `--check`).
2. **Compare and change**, resource by resource in plan order. After the first change, each later
   resource is read again just before its turn, so the unit installed by a package, or created by the unit
   file, is seen.

Section 11 follows the same run through the code and the agent calls.

On tst9, set up already, a second run ends with:

```
[tst9] apply kolla_cache: 0 changed, 8 unchanged
```

and a guarded command that was not needed shows its guard, for example
`[tst9] apply kolla_cache: command[the registry answers]  unchanged (unless: true)`.

With `--check` on a fresh node, the packages, the pull, the directory, the unit file and the commands are
reported as `would change` or `would run`, and the service as `the unit does not exist yet`: nothing has
been written, so the unit is missing, and `--check` reports that instead of failing.

## 8. When something fails

If a resource fails, everything that comes after it by `after`, or is notified by it, is skipped with a
line naming the cause. Everything else still runs, and the node is reported as failed at the end.
`--stop-on-failure` stops at the first failure instead.

Here, if Docker Hub is unreachable and the pull fails three times:

- `service[...]` is skipped (it comes after the pull);
- `command[the registry answers]` is skipped (it comes after the service);
- `command[pre-pull ...]` is skipped (it comes after the wait);
- the packages, the directory and the unit file are still made.

Fix the cause and run it again: only the four that are still different are done.

## 9. The teardown

`manifests/kolla_cache_teardown.scala` undoes it with two commands. Its guards use `onlyIf` instead of
`unless`: run only while there is something to remove.

```scala
    val stopped = command(
      "stop and remove the Kolla cache container and unit",
      stopScript,
      onlyIf = unitExists,
      timeout = 120
    )
    val cleaned = command(
      "delete the cached images",
      dataScript,
      onlyIf = dataExists,
      timeout = 120
    ).after(stopped)
    List(stopped, cleaned)
```

It is written as commands, not as `absentFile` and a service with `running = Some(false)`, because
`absentDirectory` refuses a directory that is not empty and the cache directory holds the layers. podman
stays installed, and the nodes keep their mirror setting; with the cache gone, podman falls back to
quay.io.

## 10. The test

`manifests/test_kolla_cache.sh` applies the description and then checks the result independently of the
description's own guards, with `orphera run`:

- the unit is active;
- `/v2/` answers 200;
- the catalog lists `openstack.kolla/keystone`;
- the data directory is not empty;
- tst6, a node using the mirror, can pull the Keystone image;
- a second apply prints `0 changed`.

The last check is the one every description's test has: it proves the guards are right. A guard that tests
the wrong thing shows up there as a command that runs every time.

`NO_APPLY=1 manifests/test_kolla_cache.sh` runs the checks only.

## 11. How it is executed underneath

Sections 1 to 10 describe what the description says. This section follows
`orphera apply manifests/kolla_cache.scala` through the code that runs it, from the command line to the
agent on tst9. Paths are relative to the repository; `orchestrator/.../` is
`orchestrator/src/main/scala/orphera/orchestrator/`.

The whole chain, in outline:

```
orphera apply kolla_cache.scala                      Main (orchestrator JVM)
  └─ java orphera.scripting.Main kolla_cache.scala   compiles the file
       └─ java PlaybookLauncher kolla_cache          runs the compiled object
            └─ OrpheraApply.run → OrpheraApply.runOn → Orphera.run
                 └─ each node, in parallel:
                      Apply.plan(describe(node))     check the description, work out the order
                      NodeRef.applyGrouped           facts, run log, output
                      TaskExecutor.applyOn           build a NodeTarget for the node
                      Apply.run                      the engine: read, compare, change
                        └─ NodeTarget → NodeClient   one gRPC call per read or change
                             └─ agent on tst9        ObserveNode, ExecuteCommand, ApplyFiles, ApplySoftware
```

### 11.1 The command line

`Cli.parse` (`orchestrator/.../Cli.scala`) turns `apply <path> [--check] [--nodes a,b] [--stop-on-failure]`
into `Command.Apply`. `Locations.resolveManifest` then looks for a bare file name such as
`kolla_cache.scala` in the manifests directory, so `orphera apply kolla_cache.scala` works from anywhere.

Before the command is dispatched, `Main` does three things that apply to every command that calls agents:

- checks the operator (`CurrentOperator`);
- with `ORPHERA_OPERATOR` set and an `access.yaml` beside the inventory, refuses the command early if the
  operator's role does not allow it (`AccessPolicy.early`). A Scala description cannot be read in
  advance, so it is taken to need `run`;
- records the start and end of the command in the audit log (`AuditLog`).

A `.yaml` description is loaded and applied in the same JVM (`ApplyYaml`, then `OrpheraApply.runOn`). A
`.scala` description is code, so `runScalaPlaybookScript` in `Main.scala` starts a second JVM for it and
passes the options as environment variables:

| Option | Variable |
|---|---|
| `--check` | `ORPHERA_CHECK=true` |
| `--stop-on-failure` | `ORPHERA_STOP_ON_FAILURE=true` |
| `--nodes a,b` | `ORPHERA_APPLY_NODES=a,b` |

The child's exit code becomes the exit code of `orphera apply`.

### 11.2 Compiling and starting the description

`orphera.scripting.Main` (`scripting/src/main/scala/orphera/scripting/Main.scala`) compiles the file with
the Scala 3 compiler (`dotty.tools.dotc.Main`), with the orchestrator jar on the classpath, into a
temporary directory. A compile error stops the run here with `compilation of ... failed`, before any node
is contacted. The temporary directory is removed when the run ends.

It then starts `PlaybookLauncher` with the class name taken from the file name: `kolla_cache.scala` runs
the object `kolla_cache`. **The object name must match the file name.** `PlaybookLauncher` calls the
object's `main` by reflection. `OrpheraApply` extends Cats Effect's `IOApp.Simple`, so `main` runs the
trait's `run`. An error is printed as one line (`Error: ...`); set `ORPHERA_DEBUG=1` for the stack trace.

### 11.3 The run

`OrpheraApply.run` (`orchestrator/.../OrpheraApply.scala`) reads the three variables and takes the nodes
from `ORPHERA_APPLY_NODES`, or else from the description's `nodes`. `OrpheraApply.runOn` prints
`Applying 'kolla_cache' to tst9` and calls `Orphera.run` (`orchestrator/.../OrpheraScript.scala`), the same
context that scripts use. It:

- fails at once if no agent token is configured;
- starts a run log, `.orphera-logs/script-apply-kolla_cache-<yyyyMMdd-HHmmss>.jsonl` under the state
  directory, with `run_start` and `run_end` events, and a `task_start` and `task_end` event per node; the
  `task_end` lists every resource and its outcome;
- has no checkpoint. A description is not resumed; running it again is the resume.

`orphera.nodes(...).each(...)` runs the nodes with `parTraverse`, all at once. For each node,
`Apply.plan(name, describe(node))` is evaluated first. This is where your `describe` is called, and where
`ApplyPlan.apply` (`orchestrator/.../Apply.scala`) checks the list and sorts it into the plan order
(section 6). A mistake there fails the node before it is contacted.

With more than one node, each node's lines are held back and printed together when it finishes, under a
lock, so lines from different nodes do not interleave. A last line totals the nodes.

### 11.4 One node

`NodeRef.applyGrouped` (`OrpheraScript.scala`) gathers the node's facts first (the agent's `GatherFacts`
call, cached for the run). The facts are template variables for `fileFrom` with a `.mustache` source;
`kolla_cache` has none, but the call is made for every description. It then calls
`TaskExecutor.applyOn`, which wraps the node in a `NodeTarget` and calls the engine, `Apply.run`.

`Apply.run` knows nothing about gRPC. It talks to the node only through the `ApplyTarget` trait in
`Apply.scala`:

| Method | What it does |
|---|---|
| `observe(resources)` | read the state of a set of resources |
| `readText(path)` | read a file's text, for a diff |
| `writeText(...)` | write a file's text, guarded by the hash that was read |
| `typed(label, changes, fallback, timeout)` | make file changes |
| `software(...)` | install packages, control units |
| `access(...)` | users, groups, keys, mounts |
| `shell(label, script, timeout)` | run a shell script |

`NodeTarget` (in `TaskExecutor.scala`) implements it for a real node. The checks in `ApplyCheck` implement
it with a fake node, so the engine's rules are tested with no machine. This is the split agreed in
`docs/TYPED-ACTIONS-PLAN.md`: the rules stay pure Scala in the orchestrator, and the agent only carries out
a fixed list of actions.

### 11.5 The engine

`Apply.run` does this for one node:

1. **Wanted contents.** Each file's wanted text is worked out once: the text given, a secret's value, or
   a local source file (rendered if it is `.mustache`).
2. **One read of everything.** `target.observe` is called once with every resource that can be read. A
   command without a guard is not read; it can only be `onChangeOnly`.
3. **Each resource, in plan order:**
   - If a resource it comes after, or one that notifies it, failed or was skipped, it is skipped. With
     `--stop-on-failure`, everything after the first failure is skipped.
   - An `onChangeOnly` command that nothing notified in this run is `not notified`.
   - If something has already changed on this node in this run (and this is not `--check`), the resource
     is read again on its own, since the first read may be out of date.
   - A guard that says the command is not needed gives `unchanged (unless: true)` or
     `unchanged (onlyIf: false)`.
   - Otherwise `compare` takes the wanted and the observed state and returns a `Difference`: the changes
     as words (`created`, `content`, `mode 0600 -> 0644`, `started`), the diff lines for a file, and the
     **actions** that would make them true.
   - In `--check` the result is `would change` or `would run`, and the actions are dropped. Otherwise the
     actions are carried out in order, and the result is `changed`, `ran` or `FAILED`.
4. **The report.** The summary line is printed, and the `ApplyReport` is returned. If any resource failed,
   `applyGrouped` raises, and the node is reported as failed.

The actions are data, not code that runs on the spot:

| Action | Carried out by |
|---|---|
| `Action.Write(path, text, owner, group, mode, expectedSha, overwrite)` | `target.writeText` |
| `Action.Typed(fileChanges, fallbackScript)` | `target.typed` |
| `Action.Software(softwareChanges, fallbackScript)` | `target.software` |
| `Action.Access(accessChanges, fallbackScript)` | `target.access` |
| `Action.Shell(script)` | `target.shell` (a `command` resource's `run`) |

Each typed action carries the shell script that makes the same change. That script is used only on an
agent that does not have the typed call.

For a service, the actions are `systemctl daemon-reload` and then `enable`, `start` or `restart` as needed
(the `ReloadUnits` and `ControlUnit` changes); this is where the reload in section 6 comes from.

### 11.6 From actions to agent calls

`NodeTarget` turns each read and action into a call to the agent through `NodeClient`
(`orchestrator/.../NodeClient.scala`). The calls are defined in `common/src/main/protobuf/Protocol.proto`:

| Agent call | Used for | Policy operation |
|---|---|---|
| `GatherFacts` | the node's facts (11.4) | `facts` |
| `ObserveNode` | reading paths, packages, units and the other kinds, with no script | `observe` |
| `ExecuteCommand` | a command's guard; a command's `run`; reading a file's text for a diff; any fallback script | `run` |
| `ApplyFiles` | directories, mode/owner/group, removing, links, sysctl, modules, a file's text | `apply-files` |
| `ApplySoftware` | packages and systemd units | `apply-software` |
| `ApplyAccess` | users, groups, keys, mounts (not used here) | `apply-access` |

Some details:

- **The guards are one script.** All guards that need reading are put into one shell script by
  `Apply.observeScript`, with a marker line before each, and sent in one `ExecuteCommand` (`sh -c`,
  120 s). `Apply.parseObserved` splits the output by marker. A guard's own output is thrown away; only
  its exit status counts.
- **A diff is read only when needed.** `readText` is a `head -c 262144` of the file, sent only when a
  file's hash differs from the wanted text's hash and the file is not secret.
- **Fallback.** If the agent answers `UNIMPLEMENTED` to `ObserveNode`, `ApplyFiles`, `ApplySoftware` or
  `ApplyAccess`, the same read or change is done with the script through `ExecuteCommand`. That answer
  comes before anything is changed, so an older agent gets the same result by the older route.
- **Timeouts.** A command uses its own `timeout`; a package 900 s; anything else 120 s.
- **Each call is its own connection.** `NodeClient` opens a gRPC channel over TLS to the node's host and
  port (50051 by default), trusting the Orphera CA, presents the client certificate if the control host
  has one (mutual TLS), and sends the agent token in the call's metadata.

### 11.7 On the agent

The Go agent is the default (`agent-go/internal/agent/`). The Scala agent implements the same calls from
0.1.218 (`agent/src/main/scala/orphera/agent/`: `Observe`, `TypedFiles`, `TypedSoftware`, `Fixed`).

Every call passes the agent's interceptors first (`auth.go`, `policy.go`): the token must match, and on a
node with a policy file the role in the caller's certificate must allow the call's operation. Each call is
logged on the node as allowed or `REFUSED`. A refused call returns `PERMISSION_DENIED`, and the resource
fails with that message.

Then, by call:

- **`ObserveNode`** (`observe.go`): reads directly, with no shell. A path is `lstat`ed and, if it is a
  regular file, hashed with SHA-256; a package is asked of dpkg or rpm; a unit of
  `systemctl is-enabled` and `is-active`. A protected path, such as the agent's own directory, is refused.
- **`ExecuteCommand`** (`command.go`): runs the program and its arguments directly (here always
  `sh -c <script>`), with a fixed `PATH`, as root. Output comes back line by line as a stream of events.
  At the timeout the process is killed and the result is exit 124, `Timed out after Ns`.
- **`ApplyFiles`** (`applyfiles.go`): for a file's text, `ReplaceText` refuses if the file's hash is no
  longer the one the orchestrator read (`the file has changed since it was read`), or, for a new file, if
  a file has appeared. It writes a temporary file `.orphera-edit-*` beside the target, syncs it, sets the
  owner and mode, and renames it over the target, so the file is never half written. `SetAttributes`
  follows for the wanted mode, owner and group.
- **`ApplySoftware`** (`applysoftware.go`): checks each package and unit name, then runs `apt-get update`
  (for `updateCache`) and `apt-get install` with a lock timeout, or `dnf install`, and `systemctl` for
  units, without a shell. It stops at the first failure and names it.

### 11.8 kolla_cache on a fresh tst9, call by call

Putting it together, a first run on a tst9 with nothing on it makes these calls:

| Step | Agent calls |
|---|---|
| start | `GatherFacts` |
| first read | `ObserveNode` (podman, curl, the directory, the unit file, the unit); `ExecuteCommand` (the guard script for the three commands) |
| `package[podman]` | `ApplySoftware`: `apt-get update`, install podman |
| `package[curl]` | `ObserveNode` (read again); `ApplySoftware`: install curl |
| `command[pull the registry image]` | `ExecuteCommand` (its guard, again); `ExecuteCommand` (the pull script, 600 s) |
| `directory[/var/lib/orphera/kolla-cache]` | `ObserveNode`; `ApplyFiles`: make the directory |
| `file[.../orphera-kolla-cache.service]` | `ObserveNode`; `ApplyFiles`: `ReplaceText` (no file expected), then `SetAttributes` 0644 root:root |
| `service[orphera-kolla-cache.service]` | `ObserveNode`; `ApplySoftware`: daemon-reload, enable, start |
| `command[the registry answers]` | `ExecuteCommand` (its guard, again); `ExecuteCommand` (the wait script, 90 s) |
| `command[pre-pull the Kolla images ...]` | `ExecuteCommand` (its guard, again); `ExecuteCommand` (the warm script, 3600 s) |

The second read of each guard matters on this run. At the first read the registry was not running, so
`command[the registry answers]` was needed. By its turn the service has started, and the guard is run
again: if the registry already answers, the command is `unchanged`; if not, the wait script runs. Without
the second read, the engine would act on state from before the packages were installed.

A second run on the same node changes nothing, so nothing is read twice. It makes three calls:
`GatherFacts`, one `ObserveNode` and one `ExecuteCommand` for the guards. That last call is why applying
this description needs `run` even when nothing changes: its guards are shell. At the agent, a description
with no `command` resources needs only `facts`, `observe`, `apply-files` and `apply-software` (and
`apply-access` for users, groups, keys and mounts). The early refusal in 11.1 still takes any Scala
description to need `run`, because it cannot read one in advance; a `.yaml` description is read, and is
checked for the calls its kinds need.

## Exercises

1. **Add an image to warm.** Add `"glance-api"` to `warmImages` and run `--check`. Only
   `command[pre-pull ...]` would run, because its guard now finds an image missing. The script pulls the
   whole list again; images already cached are served from tst9 and finish quickly.
2. **Change the TTL.** Change `REGISTRY_PROXY_TTL` and run `--check`. The unit file shows a diff, and the
   service would be restarted, notified by the file. Nothing else changes.
3. **Break a guard.** Give the wait command `unless = "false"` and apply twice. The second run reports
   `1 changed`, and the test's idempotence check fails. Put it back.
4. **Break the plan.** Remove `.after(podman)` from the warm-up and add `.after(warmed)` to the pull. The
   description is refused before any node is contacted, naming the resources that wait for each other.

## Where to go next

- `manifests/placement_database.scala` is smaller still: one guarded command whose guard is a real login
  as the service user.
- `manifests/rabbitmq_single_node.scala` has a container on the host network, and its unit file is a
  `secretFile` (content never shown in a plan or diff) because it holds the broker's password.
- `manifests/placement_single_node.scala` is a full OpenStack service: per-service Kolla directories,
  config files that notify only their own service, a database migration guarded by a stamp, and the
  Keystone registration through the helper.
