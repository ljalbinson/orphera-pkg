<!-- SPDX-License-Identifier: Apache-2.0 -->
# Tutorial: how an apply description is put together

This tutorial reads one of the OpenStack descriptions, `manifests/kolla_cache.scala`, from top to bottom
and explains each part: what it declares, why it is written the way it is, and what `orphera apply` does
with it. It is one of the simplest in the OpenStack set: one node, no secrets, eight resources. Most of
the other OpenStack descriptions use the same parts.

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

Before any node is contacted, the description is checked: duplicate ids, relative paths, bad modes, bad
package or unit names, a command with no guard, a link to an id that is not described, a notification of
something that cannot be notified, and cycles. Every problem is listed, and nothing runs.

Then, for each node:

1. **Observe.** One call reads the state of every resource: each path's type, mode, owner, group and hash,
   each package and unit, and each command's guard (guards run here, also in `--check`).
2. **Compare and change**, resource by resource in plan order. After the first change, each later
   resource is read again just before its turn, so the unit installed by a package, or created by the unit
   file, is seen.

On tst9, set up already, a second run ends with:

```
[tst9] 0 changed, 8 unchanged
```

and a guarded command that was not needed shows its guard, for example
`command[the registry answers]  unchanged (unless: true)`.

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
