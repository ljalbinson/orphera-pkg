# Orphera: access control (design)

Status: proposed 2026-11-17. Step 1 (identity) built and run on the lab 2026-11-18; step 2 (policy at the agent) built and run on tst0 2026-11-18. Differences from the text below: the policy is `access.yaml` beside the inventory, not `config/access.yaml` (that directory holds the VM configs, which the schema checks and the database tools read as such); `paths` is built (Go agent 0.1.212 or later; the Scala agent refuses a policy that has it); `policy-info` is built and reads the files over SSH, not through an agent call. Closes the first governance gap in the README's known gaps: "no limits on what an
authenticated caller may do".

## Summary

Today anyone who can reach an agent and holds its token (and, on nodes with mutual TLS, the orchestrator's client
certificate) may do everything, because `ExecuteCommand` is a root shell and the rest of the agent's calls are a subset of
what it can do. The audit log records what ran and where, but not who: every caller is "this control host".

The proposal has three parts:

1. **Identity.** Each operator gets a client certificate of their own, signed by the lab CA, with the operator's name and a
   role in it. Mutual TLS is already how a caller is recognised; this makes the certificate say who it is.
2. **Policy.** A file in git, `config/access.yaml`, says what each role may do, on which nodes, and a copy of the part that
   matters is placed on each agent over SSH, as certificates and the token are.
3. **Enforcement at the agent.** The agent reads the role from the caller's certificate and allows or refuses each call
   against its policy. The orchestrator checks the same policy first, so a refusal is clear and early, but the agent is
   what enforces it, because a caller with a certificate and a token can talk to an agent without going through the
   orchestrator at all.

A node with no policy file behaves exactly as today, so this can be rolled out a node at a time, as mutual TLS was.

## What can and cannot be controlled

The honest limit comes first, because it decides the shape of the roles.

- `run` is a root shell. A role that may run commands may do anything on that node. No list of allowed commands changes
  that, so there is no command allowlist in this design.
- **`apply` is `run` only for a `command` resource, and only on an agent that lacks the typed calls.** The apply engine
  used to read a node and change it by sending `sh -c` scripts through `ExecuteCommand`. It now reads with `ObserveNode`
  and changes with `ApplyFiles`, `ApplyAccess` and `ApplySoftware` (operations `observe`, `apply-files`, `apply-access`,
  `apply-software`), which the Go agent performs itself from a fixed list of actions, so a role can hold them without
  `run`. A `command` resource (and the guard on one) still runs a script, and so still needs `run`; an agent without a
  typed call (the Scala agent, a Go agent older than 0.1.216) is sent the script as before, which needs `run` too. Every
  apply also needs `facts`. `apply-software` installs packages, whose own scripts run as root, so it is the widest of the
  three: hold it as deliberately as `packages`.
- What can be separated now: read-only access (facts, version, uptime, file checks, fetch) from anything that changes a
  node; changing files and packages (`CopyFile`, `Install`, `Remove`, `InstallDebPackage`) from running commands; reboot
  and shutdown from both; which nodes a role can reach at all; and which paths copy and fetch may touch.

That already covers the cases that matter in a lab and most of a small production: a person who may look at everything
and change nothing, a person who may deploy files and packages to the Ceph nodes only, and the two or three people who
may do anything.

## Identity

`orphera issue-client-cert --operator alice --role deployer [--days 30]` makes `certs/operators/alice.crt` and `.key`,
signed by `certs/ca.key`, with the subject `CN=alice, OU=deployer`. The role is in the certificate, signed by the CA, so
a caller cannot claim another. The orchestrator uses `certs/operators/<name>` when `ORPHERA_OPERATOR=alice` (or
`--operator`) is set, and `certs/client.crt` as now otherwise.

- The existing `client.crt` keeps working and is given a role by the policy's `default_role` for certificates with no
  `OU` (initially `admin`, so nothing changes until someone changes the policy).
- Operator certificates are short-lived by default (30 days, `--days` to change), so leaving the team ends access
  without anyone having to remember to revoke. The shared 825-day lifetime stays for the orchestrator's own certificate.
- Revocation before expiry: the agent reads `/etc/orphera-agent/tls/revoked` (certificate serial numbers, one per line),
  placed by `orphera issue-policy`. A list rather than a CRL, since there is one CA and few certificates.
- Holding `certs/ca.key` is the root of all of this: whoever has it can sign a certificate for any role. It stays on the
  control host, and `orphera secrets` and the CA are the two things worth a hardware key (see Secrets, below).

The token stays. It is a second factor, not an identity: it identifies nothing, but a stolen certificate alone no longer
gets in.

## Policy

`config/access.yaml`, in git with the inventory, so a change to who may do what is reviewed like any other change:

```yaml
default_role: admin          # for a certificate with no role in it
roles:
  viewer:
    allow: [facts, version, uptime, thread-info, check-file, fetch]
  deployer:
    allow: [facts, version, uptime, check-file, fetch, copy, packages]
    nodes: [group:ceph, group:lab-web]
    paths: ["/etc/ceph/", "/etc/nginx/", "/srv/"]      # copy and fetch only under these
  rebooter:
    allow: [facts, version, uptime, reboot, shutdown]
    nodes: [group:ceph]
  admin:
    allow: all                                          # includes run
```

- `allow` names operations, each a fixed set of agent calls: `copy` is `CopyFile` and `CheckFile`; `packages` is
  `Install`, `Remove`, `RunAutoRemove`, `RunDistUpgrade`, `InstallDebPackage`; `run` is `ExecuteCommand`; `reboot`,
  `shutdown`, `network` are what they say. `all` is every call, `run` included. A call in no listed operation is refused,
  so a new call added to the agent later is refused to every role but `admin` until someone lists it.
- `nodes` limits the role to named nodes or `group:` names from the inventory (the same groups `--node-groups` uses). It is
  checked by the orchestrator, which knows the groups, and written to each agent as "this role may act here or not": an
  agent holds only the roles that apply to it.
- `paths` limits `copy` and `fetch` to prefixes, resolved the way `ProtectedPaths` resolves them (absolute, `..` removed,
  links followed). `/etc/orphera-agent/` stays refused to every role, `admin` included, as now.
- A role that is not listed, or a certificate whose role is not in the policy, is refused. An agent whose policy file does
  not parse refuses everything and says why in its journal, rather than falling back to allowing everything.

## Enforcement

At the agent, in the interceptor that already checks the token (`AuthInterceptor` in Scala; the equivalent in the Go
agent, which already uses `RequireAndVerifyClientCert`):

1. After the TLS handshake the caller's verified certificate is available (`SSLSession.getPeerCertificates` in grpc-java,
   `credentials.TLSInfo` in grpc-go). Read `CN` and `OU`; check the serial against `revoked`.
2. Look the role up in `/etc/orphera-agent/policy.yaml`; map the call's method name to its operation; refuse with
   `PERMISSION_DENIED` and a message naming the role, the operation and what the role may do.
3. For `CopyFile`, `CheckFile` and `RemoteFetchFile`, check the path against the role's `paths` as well.
4. Log one line to the journal per call: operator, role, method, allowed or refused. Refusals are the ones that matter.

A node with a policy file must also have mutual TLS on (the agent refuses to start with a policy and no `tls/ca.crt`),
since without a client certificate there is no role to check. That is the one coupling to the mutual-TLS rollout: turn it
on for a node first, then give the node a policy.

`orphera issue-policy [--nodes ...] --ssh-user localadmin` reads `config/access.yaml`, works out each node's roles, and
places `policy.yaml` and `revoked` in `/etc/orphera-agent/` over SSH, as `issue-certs` does, restarting the agent only if
the files changed. `orphera policy-info [--nodes ...]` asks each agent which policy it holds (a new, read-only call
open to any role) so a node left on an older policy shows.

## The orchestrator's side

- Before a run, the orchestrator reads `config/access.yaml` and the operator's role, and refuses what the role cannot do
  with the same message the agent would give, before any node is touched: `alice (deployer) may not run commands;
  manifests/x.scala uses run on tst3`. For a Scala manifest this check is partial (the commands are only known when the
  manifest runs), so the agent's refusal stays the real one, and the orchestrator reports it as the reason a task failed.
- `--check` runs with the read calls only (`facts` and `observe`), so a viewer given those can run `orphera apply --check`
  and see what would change on every node. That is the safest use of the apply design for people who must not change
  anything. A description with a `command` resource that has a guard (`only_if`, `unless`, `creates`) also needs `run` to
  check, since the guard is a script.
- The audit log gains `operator` and `role` on every record. Where there was only "someone on this host", `orphera
  log-summary` can show who did what.

## Other things that need access control

- **Secrets.** `orphera secrets` has one store and every recipient reads all of it. Splitting it by scope (`lab`,
  `openstack`, `ceph`, each with its own recipients) means a role that deploys Ceph need not hold the key that reads the
  OpenStack passwords. The policy names which scopes a role may read, enforced by which age identity the operator holds,
  not by orphera trusting itself. A separate piece of work; it depends on this one only for the idea of roles.
- **Devices.** `orphera ilo` and `orphera os10` use the device's own credentials from the secret store, so access is
  whoever can read those secrets. They gain nothing from the agent policy; scoped secrets are their control.
- **The control host.** Whoever can log in to scala0 as the user that owns `certs/` and `.orphera-secrets` has everything
  above. Operators should have their own accounts there, with their own `certs/operators/<name>` readable only by them
  (`ORPHERA_CERTS_DIR` already allows this), and `ca.key` readable by an administrator only. The design assumes that and
  does not enforce it.

## Rollout

1. **Identity only (no enforcement).** `issue-client-cert --operator/--role`; the orchestrator presents it; the agent logs
   operator and role per call and allows everything; the audit log records the operator. This gives "who" with no risk,
   and shows what each person actually does before anyone writes a policy from guesses.
2. **Policy at the agent.** The interceptor, `config/access.yaml`, `issue-policy`, `policy-info`, `revoked`. Rolled out a
   few nodes at a time, with a role that is deliberately too wide at first, tightened from the journal.
3. **Orchestrator checks.** Early, clear refusals; `viewer` and `--check` working end to end.
4. **Apply without run: typed actions.** Built, in five phases (see `docs/TYPED-ACTIONS-PLAN.md`) as `ObserveNode`,
   `ApplyFiles`, `ApplyAccess` and `ApplySoftware` rather than the single `ApplyActions` call described next, so that each
   is its own policy operation. The original design follows. The
   orchestrator keeps the engine as it is: it observes (read-only calls), compares with the pure `compare` functions, and
   produces a `Difference`. What changes is how a `Difference` is carried out. Today its actions are `Write` (a file) and
   `Shell` (a script run by `ExecuteCommand`). They become a typed vocabulary the agent interprets itself: write a file with
   this content, owner and mode; make a directory or link; set a sysctl; load a module; add, replace or remove a line in
   a file; add or remove an authorized key; edit an fstab entry; mount or unmount; install or remove a package; start,
   stop, enable or restart a service; create or change a user or group. Each is a message in a new `ApplyActions` call;
   `Shell` stays for the few things no typed action covers (a guarded `command` resource, odd package steps), as an
   action of its own that needs the `run` permission. The observe step is likewise a fixed set of reads (`ObserveNode`)
   instead of a script, so a viewer can run `--check` without `run`.

   Why this and not an engine in the agent: the rules (what counts as unchanged, how a line is replaced, what a mount
   change does) stay in one place, in pure Scala, and are not written a second time in Go. The agent becomes an
   interpreter of a small instruction set, which is easier to keep identical in the Scala and Go agents, and to test with
   one table of cases. It also gives access control its separation: a role can hold `apply` (the typed actions) without
   `run`, because the agent performs only those actions and not an arbitrary script. The cost is a second step in the
   migration: each kind needs its typed action and an interpreter in each agent before the `Shell` form can go, and a
   node whose agent lacks the call keeps the shell path (the agent reports its capabilities in `GetVersion`).
   A side benefit is that actions as data replace string-built shell, which is where quoting mistakes live.

Tests: the agent's guard check (`AgentGuardCheck`) gains the policy cases (a role allowed, a role refused, a path outside
the role's prefixes, a revoked serial, an unparseable policy, a certificate with no role); the Go agent's unit tests the
same; `ClientCert` the subject. Everything is first checked with `--dry-run`-style local runs against a stand-in agent,
then on two lab nodes.

## What this gives up

- A role with `run` is root on the node. This design makes "who may be root where" explicit and recorded; it does not
  make root safe.
- Short-lived operator certificates mean renewing every 30 days. A person who forgets is locked out until the control
  host administrator issues a new one. That is the intent.
- Two agents (Scala and Go) must enforce the same policy the same way. The policy file is small and the method names are
  fixed, so the Go agent's check can be written from the same table and tested with the same cases.
- A policy that is too tight in practice is the usual failure. The journal line per refusal is there so the policy can be
  corrected from what people tried to do.

## Decisions needed

1. Roles as in the example (viewer, deployer, rebooter, admin), or different? Are per-node-group limits enough, or are
   there operations a role needs per node (say, reboot on tst0 but not tst1)?
2. 30 days for operator certificates, or longer?
3. Is step 1 (identity and logging only) worth shipping on its own first? I would, for the audit log's sake.
4. Is "apply without run" (step 4, typed actions) wanted, or is "may run" the line that matters?
