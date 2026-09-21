# Why Orphera

## The problem

Ansible is the default choice for host orchestration, but it carries
real, well-known friction: YAML-as-a-programming-language (loops,
conditionals, and now logic bolted onto a config format via Jinja2),
a dynamically-typed module interface where a typo in a task's
parameters surfaces at runtime on a remote host rather than at
authoring time, SSH+Python as the transport (works, but slow to fan
out and awkward to reason about failure modes precisely), and a
task/module boundary that makes genuinely new primitives (a new RPC,
a new kind of check) a heavier lift than it should be.

## The bet

Build the same category of tool — push config, install packages,
coordinate multi-host state — on a foundation that gets type safety
and structured communication for free: a typed RPC schema (protobuf)
over gRPC/TLS, executed by a small Scala/Cats-Effect agent per host,
driven by an orchestrator that can validate a playbook's shape
*before* touching a single machine, not after.

## What that bet actually bought

- **A single schema is the source of truth for every operation.**
  Every capability — install, remove, copy, reboot, run an arbitrary
  command, gather facts — is a protobuf message and RPC, not a
  loosely-typed dict passed to a Python module. Adding a new
  capability means extending one schema and implementing it once per
  language (agent, orchestrator), and the compiler catches a mismatch
  immediately rather than at runtime on a production host.

- **Two playbook front-ends over one execution engine, not two
  competing tools.** A YAML front-end for the common, easily-reviewed
  case; a Scala DSL — including run-time-compiled standalone scripts —
  for cases that genuinely need real composability (loops generating
  tasks, arithmetic, shared helper functions) without reaching for
  Jinja2-in-YAML, which is broadly considered one of Ansible's weaker
  design choices. Both compile down to the same `Playbook`/`Task`
  value and run through the same engine, so a fix or feature never
  has to be built twice.

- **Correctness caught before deployment, not during it.** `-Werror`
  turns a non-exhaustive match on the `Task`/`Command` ADTs into a
  compile failure — concretely, this caught a missing CLI handler and
  a missing playbook-runner case during this project's own
  development, before either shipped. An idempotent pre-check
  (content hashing) on every file push means a playbook is safe to
  re-run without re-triggering work that already happened, the same
  guarantee Ansible aims for but achieved here by construction rather
  than by module-author discipline.

- **Explicit failure-mode design, not inherited defaults.** The
  agent's own self-upgrade path (`deploy-agent`) was found, in
  practice, to kill itself mid-install because of how systemd's
  cgroup semantics interact with a service upgrading its own binary —
  and the fix (`systemd-run --no-block`, detached from the agent's
  own process tree) is now a documented, deliberate part of the
  design, not an accident nobody noticed. The network-config safety
  mechanism (local rollback timer, independent of the orchestrator's
  reachability) exists because "a bad config can permanently sever
  the connection managing the host" is exactly the kind of failure a
  naive push-and-hope tool doesn't protect against.

- **Cross-node coordination as a first-class concept, not a
  workaround.** Staged execution, cross-node fact/variable sharing,
  and quorum-of-N health gating exist specifically because a
  meaningful fraction of real infrastructure — anything clustered
  (Ceph, etcd, any leader-election system) — isn't just "push the
  same config to N independent boxes." Getting this right was treated
  as core scope, not an edge case bolted on later.

## What this project is honestly not

It's early, self-built, and not trying to out-feature Ansible across
its huge surface area (no dynamic cloud inventory, no module
ecosystem, no roles/handlers yet, thin test coverage, no CI as of
this writing). The right way to describe it: **a focused exploration
of what a typed, RPC-native, coordination-aware orchestration tool
looks like when the design decisions are made deliberately and the
failure modes are treated as first-class**, not a drop-in Ansible
replacement for arbitrary production use today.

## Why it was worth building anyway

Two separate payoffs, distinct from "does it beat Ansible":

1. **As a tool for its actual target case** — small, well-understood
   fleets where the operator wants precise control and strong
   guarantees over breadth of platform support — the type-safety and
   explicit-failure-mode properties above are genuinely valuable, not
   just theoretically nice.

2. **As a demonstration of what those design choices cost and buy**,
   concretely: every "we hit this exact class of bug" note in the
   changelog (a symbol collision protoc won't resolve, a systemd
   cgroup-kill interaction, a `.gitignore` silently excluding
   essential build state) is a real, specific lesson about building
   this kind of system correctly — the kind of knowledge that's cheap
   to read about and expensive to actually earn.

See [`README.md`](README.md) for what's actually built and how to use
it, and [`CHANGELOG.md`](CHANGELOG.md) for the detailed history of
what was added, fixed, and learned along the way.
