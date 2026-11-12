# Orphera: a monadic Scala DSL (design)

2026-11-12. Decisions taken on 2026-11-12: all four as recommended (see *Plan and decisions*).

## Summary

I recommend adding a second way to write a Scala playbook: a script in plain Cats Effect `IO`, where each step on a
node returns its result as a value. Run-time values (a command's output, a gathered fact, a value read on one node) can
then be used with ordinary `if`, `match` and string interpolation, instead of `register`, `set_fact`, `{{...}}` and
`.when(...)`.

Today a Scala playbook is data: the fluent builder makes a `Playbook` or `ClusterPlaybook` before any node is contacted,
so anything learned during the run has to be passed through `register`/`set_fact` and read back with `.when` or
`{{nodes.tst0.key}}`. The proposed `OrpheraScript` is behaviour: it runs step by step, so the value is in hand when the
next step is written.

It sits beside the fluent DSL, does not replace it, and runs on the same task executor, so output, run log, retries,
change detection, mutual TLS and the protected paths all behave the same. What it gives up is a complete list of tasks
before the run starts (see *What it gives up*).

## Before and after

**1. A decision made on the node (from `lustre_cluster.scala`).** Today the answer to "is a reboot needed" is
registered as a set fact and read back by a `.when` in the next stage:

```scala
stage("boot-kernel", allNodes*)
  .task("default to the Lustre kernel and lock the packages")(sh(bootKernelScript, 300))
  .task("is a reboot needed")(sh(needsRebootScript, register = "lustre_reboot"))
  .build,
stage("reboot", allNodes*)
  .task("reboot into the Lustre kernel")(Task.Reboot(waitForReturn = true, waitTimeoutSeconds = 600))
  .when("lustre_reboot" === "yes")
  .build,
stage("lnet", allNodes*)
  .task("LNet on tcp0")(sh(lnetScript, 120))
  .build
```

As a script, the answer is a value:

```scala
object lustre_boot extends OrpheraScript:
  def script(using orphera: Orphera): IO[Unit] =
    val nodes = orphera.nodes(allNodes*)
    for
      _ <- nodes.each(_.sh("default to the Lustre kernel and lock the packages", bootKernelScript, timeout = 300))
      _ <- nodes.each { node =>
        node.query("is a reboot needed", needsRebootScript).flatMap { answer =>
          if answer.stdout.trim == "yes" then node.reboot("reboot into the Lustre kernel", waitTimeout = 600).void
          else IO.unit
        }
      }
      _ <- nodes.each(_.sh("LNet on tcp0", lnetScript, timeout = 120))
    yield ()
```

Each `nodes.each` is a barrier, as a stage is now: every node finishes it before any node starts the next.

**2. A value read on one node and used on others.** Today it goes through `register` and a template:

```scala
stage("read the key", "tst0")
  .task("read the admin key")(Task.RunCommand(List("ceph", "auth", "get-key", "client.admin"), register = "admin_key"))
  .build,
stage("install the key", osds*)
  .task("install the admin keyring")(
    Task.WriteFile("[client.admin]\n  key = {{nodes.tst0.admin_key}}\n", "/etc/ceph/ceph.client.admin.keyring", mode = 384))
  .build
```

As a script:

```scala
for
  key <- mon.query("read the admin key", List("ceph", "auth", "get-key", "client.admin"))
           .map(r => orphera.secret(r.stdout.trim))
  _   <- osds.each(_.writeFile("install the admin keyring",
           s"[client.admin]\n  key = ${key.reveal}\n", "/etc/ceph/ceph.client.admin.keyring", mode = "0600"))
yield ()
```

The compiler checks the names, `orphera.secret` keeps the key out of the output and logs, and there is no string such as
`nodes.tst0.admin_key` to get wrong.

## The API

A script is an `object` that extends `OrpheraScript` and defines `script`. It is run as Scala playbooks are now,
`orphera playbook manifests/lustre_boot.scala`, with `--resume` and `--config` working the same way.

```scala
trait OrpheraScript extends IOApp.Simple:
  def script(using orphera: Orphera): IO[Unit]
```

| Name | What it is |
| --- | --- |
| `Orphera` | The run's context: `nodes(names*)`, `group("lustre")` and `node(name)` from the inventory (unknown names fail at the start), `secret(value)`, `config` (as `ConfigYaml.fromEnv()`), and the run's name for the run log and checkpoint |
| `NodeRef` | One node. Its steps: `run(name, command)`, `sh(name, script)`, `query(name, command)`, `install`, `remove`, `copy`, `writeFile`, `reboot`, `networkApply`, and `facts` (gathered on first use, then kept for the run) |
| `Nodes` | Several nodes. `each(f)` runs `f` on all of them in parallel and returns when all are done; it fails after all have finished if any failed, naming them. `first`, `filter`, `map`, `toList` as for any list |
| `StepResult` | What a step returns: `changed`, `exitCode`, `output` (the lines), `stdout` (them joined) |

Every step takes its name first, as a task does now; it is what the output, run log and checkpoint show. Optional
parameters as on today's tasks: `timeout`, `retry = Retry(3, 10)`, owner, group and mode (as an octal string, `"0644"`).

A step that fails raises an error, so the rest of that node's `for` does not run, as a failed task stops a node's tasks
now. Ordinary Cats Effect works as expected: `handleErrorWith` to carry on after a failure, `parTraverse` for
parallelism other than `each`, `IO.whenA`, `traverse_` over a list of disks.

## How it runs

Every step is a one-task call into `TaskExecutor`, the executor that commands, playbooks and cluster playbooks already
share. Nothing about running a task is written twice.

1. `node.install("tools", List("tree"))` builds the same `NamedTask` a playbook would, `Task.Install(List("tree"))`.
2. `TaskExecutor` runs it on that node: output lines, retries, the `changed` decision, the run log's `task_start` and
   `task_end`, and the checkpoint, exactly as for a playbook task.
3. The events it saw become the `StepResult`, so the script has the exit code, the output and `changed`.

What that gives, with no new code:

- **Output** reads as a playbook's: `[tst0] tools: ...`.
- **Run log**: one `run_start`/`run_end` for the script and the usual per-step events, so `log-summary` and `audit-log`
  show it.
- **Retries**: `retry = Retry(3)` on any step.
- **Handlers** need no mechanism: `r <- node.copy(...)` then `_ <- IO.whenA(r.changed)(node.run("restart nginx", ...))`.
  To restart once after several changes, collect the results and test `exists(_.changed)`.
- **Health checks** become a small helper, `waitUntil(name, timeout, every)(io)`, which polls any `IO[Boolean]` and, on
  timeout, reports the last attempt as `wait_for` now does.

Two differences from a playbook:

- `{{...}}` in a step's strings is sent as typed: a script uses Scala interpolation. A `.mustache` file given to `copy`
  is still rendered, with its `vars` and this node's facts. This needs `TaskExecutor`'s single `templating` switch split
  into one for strings and one for `.mustache` files.
- `when:` is not needed and not offered: write `if`.

## Resume and checkpoints

With `--resume`, a step already done on a node is skipped, as a task is now. A script raises one problem a playbook does
not: a skipped step has no result, and later code may need it.

The proposal splits steps in two:

| Kind | Steps | Checkpointed | On `--resume` |
| --- | --- | --- | --- |
| Change | `run`, `sh`, `install`, `remove`, `copy`, `writeFile`, `reboot`, `networkApply` | Yes | Skipped if done; returns a `StepResult` marked `skipped`, with no output |
| Query | `query`, `facts` | No | Always runs again, so its value is current |

The rule for authors is then simple: **read values with `query`**, and use a change's result only for `changed` and
`exitCode` in the same run. A query must not change the node; the name is the promise, and the docs say so.

The checkpoint key stays node + step name, so a step name must be unique on a node within a run. In a loop the name
carries the item (`s"zap $disk"`). A second change step with a name already used on that node fails at once, naming it,
instead of being skipped by mistake on a resume.

The alternative, storing each change's output in the checkpoint file so it can be returned on resume, would put command
output (and so possibly secrets) on disk, and it returns stale values. I recommend against it.

## What it gives up, and the alternatives

**No complete plan before the run.** A fluent playbook is a value: every task is known before a node is contacted, and a
future `orphera plan` could list them. A script decides its later steps from earlier results, so only running it shows
them all. That is the price of using values; it is why the fluent DSL stays for playbooks whose steps do not depend on
what is found.

**No check mode yet.** A dry run would need each change step to report "would change" without changing anything.
Because every change goes through `TaskExecutor`, that can be added later in one place (copy and write_file already
compare hashes; apt has `-s`), with queries still run for real. It is not in this proposal.

**Authors need a little Cats Effect.** `for`, `flatMap`, `IO.unit` and `each` cover most scripts, as in the examples.
The README would carry a short primer.

Alternatives considered:

| Approach | For | Against |
| --- | --- | --- |
| **Plain `IO` + an `Orphera` context (proposed)** | Ordinary Scala and Cats Effect; errors, parallelism and loops need no new combinators; least to learn and to build | No plan or dry run of the whole script; tests need a fake `Orphera`, which the context being a trait allows |
| Free monad: steps as data, run by an interpreter | Could print the steps up to the first value-dependent one, and test without agents | Authors use our combinators, not `IO`; parallelism, errors and resource safety re-built; more to learn and to maintain |
| Tagless final: `def script[F[_]: Orchestration]` | Test interpreters, and possibly a check-mode interpreter | Type parameters and type-class constraints in every playbook, for an audience writing infrastructure, not libraries |

The proposed design keeps one door open to the others: `Orphera` and `NodeRef` are traits, so a fake implementation can
run a script in a check program with canned results, which gives most of the testing benefit of the other two.

## Plan and decisions

Three steps, each merged and tried on the lab before the next:

1. **Core.** `OrpheraScript`, `Orphera`, `NodeRef`, `Nodes.each` and `StepResult`, running each step through
   `TaskExecutor` (which then returns the events it saw, not only `changed`), and the templating switch split. Checks run
   scripts against a fake runner, as the handler checks do now. `manifests/script_demo.scala` tries it on tst0 and
   tst1, and CI compiles it.
2. **Resume and the rest.** Changes checkpointed, queries not, duplicate step names refused; `retry` on every step;
   `orphera.secret`; `waitUntil`. A README section beside the fluent DSL's, with the primer.
3. **A real playbook.** Convert one existing manifest as the proof that it reads better, and keep the fluent version
   until the script has run cleanly on the lab.

The fluent DSL, YAML playbooks and cluster playbooks are unchanged throughout.

Decisions (taken 2026-11-12, all as recommended):

| # | Question | Decision |
| --- | --- | --- |
| 1 | Names: `OrpheraScript` with `def script`, or something else? | `OrpheraScript` |
| 2 | On `--resume`, a done change is skipped with no output and queries run again (with the rule "read values with `query`"), or change outputs are stored in the checkpoint? | Skip, no stored output |
| 3 | When a node fails inside `each`: stop the script after that `each`, as a failed stage does, or carry on with the nodes that succeeded? | Stop, and name the nodes |
| 4 | Which manifest to convert in step 3? | `lustre_cluster.scala`, which has the reboot decision above |
