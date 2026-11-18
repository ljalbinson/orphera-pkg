# Typed actions: plan for step 4 of the access-control rollout

Status: proposed 2026-11-18. Nothing built. Follows `docs/ACCESS-CONTROL-DESIGN.md` (rollout step 4) and
`docs/APPLY-DESIGN.md`. Steps 1 to 3, path limits and `policy-info` are merged and verified on the lab.

## What it is for

Today `orphera apply` observes a node with one shell script and changes it with `Action.Shell` (a script string run by
`ExecuteCommand`) or `Action.Write` (a file through `CopyFile`). So a role that may `apply` must hold `run`, which is a
root shell, and `apply --check` needs `run` too. Typed actions replace the scripts with messages the agent interprets
itself. A role can then hold `apply` without `run`, and a viewer can run `--check`.

## What it does not give

It separates "may run an arbitrary script" from "may bring a node to a declared state". It does not make `apply` safe to
hand to anyone, because several kinds are privileged by nature: an `authorized_key` for root is a root login, a `file`
under `/etc/cron.d` or `/etc/sudoers.d` is a root shell, a package's maintainer scripts run as root, a `mount` or a
`user` with a shell likewise. Two things limit this, and the plan uses both:

1. The `paths` limit (built) applies to every typed action that names a path, not only `copy` and `fetch`, so an `apply`
   role can be confined to, say, `/etc/ceph/` and `/srv/`.
2. Kinds that give a login or run code (`authorized_key`, `user`, `package`, `service`, `mount`) are separate operations
   in the policy table, so a role can hold `apply-files` without them.

The design document should say this plainly where it now says the roles separate the two.

## Design

**Two new calls, not one.** The agent's access check works on the method name, so what a call may do must follow from its
name. `ApplyActions` carries typed actions only. `ApplyShell` carries the scripts that remain (a `command` resource) and
maps to the existing `run` operation. A description that uses a `command` resource therefore still needs `run`.
`ObserveNode` carries a fixed list of reads (stat, readlink, hash, package state, service state, getent, sysctl, module,
mount, the file's content for a diff) and maps to a read-only operation, so a viewer can run `--check`.

**Operations.** New policy operations: `observe`, `apply-files` (file, directory, symlink, line, sysctl, kernel module),
`apply-access` (authorized key, user, group, mount), `apply-software` (package, service). `apply` means all of them;
`all` still means everything. `orphera apply` needs `observe` for `--check` and the operations for the kinds in the
description for a real run, and says which are missing before it contacts a node (the early refusal already does this
for other commands).

**The rules stay in Scala.** The orchestrator observes, compares with the pure `compare` functions and produces a
`Difference` as today. Only the carrying out changes. To keep regular expressions out of the agent (the Scala and Go
engines differ in syntax), a line edit or an fstab edit is resolved in the orchestrator: it has the observed content, it
computes the new content, and it sends a `WriteFile` with the hash of the old content as a precondition. The agent writes
only if the file still has that hash, so the edit cannot overwrite a change made in between. This is also what makes the
Scala and Go agents behave the same.

**Capabilities.** `GetVersion` reports the typed calls the agent knows. An agent without them (the Scala agent, or a Go
agent before the change) gets the shell path, which needs `run`, so nothing breaks while nodes are upgraded one group at a
time. `policy-info` already shows who is on what.

**Go agent first.** The Go agent is the default for `deploy-agent`. The Scala agent keeps the shell path and is not given
the interpreter unless wanted; this halves the work and costs nothing the lab uses.

## Phases

Each phase is its own branch, is verified on the lab, and is merged before the next.

1. **Protocol, capabilities, `ObserveNode`.** (Built as: no separate capability list yet; an agent without the call answers
   UNIMPLEMENTED and the orchestrator falls back to its script.) The messages, the capability list in `GetVersion`, `ObserveNode` in the Go
   agent, the orchestrator reading it in place of the observe script, the `observe` operation. `--check` works for a
   viewer. Verify: every manifest's `--check` gives the same plan with the script and with `ObserveNode`.
2. **File kinds.** (Built as one call, `ApplyFiles`, with the operation `apply-files`; line edits are `ReplaceText` with a hash guard; fstab edits go with mounts in phase 3.) `ApplyActions` for directory, symlink, mode/owner/group, write with a hash precondition (which carries
   line and fstab edits), sysctl, kernel module; `paths` applied to each. `apply-files`. Verify with `k8s_node_prep`,
   `k8s_api_vip`, `k8s_kubeadm_config`, `mariadb_haproxy_keepalived`.
3. **Access kinds.** Authorized key, user, group, mount. Verify with `apply_demo_kinds.yaml` and its removal form.
4. **Software kinds.** Package and service. Verify with a manifest that installs, holds and starts something.
5. **Policy, tests, docs, rollout.** The operations in `access.yaml` and the policy files, the early refusal, the
   design document corrected, `policy-info` showing which nodes lack the calls, then a role that holds `apply-files`
   but not `run`, tested on tst0: it can apply a file description and is refused a `command` resource and `run`.

Tests throughout: one table of cases (JSON, in the repository) that the Go interpreter and the Scala reference both run,
so the two cannot drift; the policy cases of `AccessPolicyCheck` and `TestDecide`; the lab runs above.

## Before it starts

- The kinds from the declarative-apply work must have been run on the lab. Lab-verified so far: file, sysctl, kernel
  module and line edits (through the Kubernetes and MariaDB manifests). Authorized key and mount (`apply_demo_kinds.yaml`)
  are not recorded as run; do that first.
- Decision: whether this is wanted at all. If "may run" is the line that matters, steps 1 to 3 and path limits already
  give a viewer role, a role limited to files and packages, and per-node scoping, and typed actions add the `--check`
  for a viewer and the `apply`/`run` split at the cost of the five phases.

## Size

Phase 1 is the largest single piece (a protocol change, a new read path in the agent and the orchestrator). Phases 2 to 4
are each smaller and alike. Phase 5 is policy and documentation. Roughly: five branches, each built, then verified by
you on the lab as path limits were.

## Status

- Phase 3 (users, groups, authorized keys, mounts, fstab): built as `ApplyAccess` (operation `apply-access`), agent 0.1.215. The fstab text is computed in the orchestrator from the text `ObserveNode` returns for a mount and sent with the hash of what was read.
- Phase 4 (packages, services): built as `ApplySoftware` (operation `apply-software`), agent 0.1.216.
