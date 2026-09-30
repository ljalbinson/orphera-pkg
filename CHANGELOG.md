# Changelog

All notable changes to this project are documented in this file.

The format is based on [Keep a Changelog](https://keepachangelog.com/en/1.1.0/).

## [Unreleased]

### Added — `manifests/etcd_cluster.scala`: 3-node etcd cluster, first cut at a non-Ceph framework stress test

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
  step at all. Worth revisiting once this has actually run: if it works
  cleanly, that's real evidence the existing cross-node templating
  already covers this shape of problem; it says nothing about the
  asymmetric case (Galera-style bootstrap, or etcd's own runtime
  membership changes), which is a different, harder shape not exercised
  here.
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
  `etcd.service` — it lands there as one long single line. Functionally
  identical (systemd doesn't care), but worth knowing before mistaking
  it for a bug later.
- Not yet run against real infrastructure — this is the DSL written and
  reasoned through, not a confirmed-working deployment. Next step is an
  actual `orphera cluster-playbook manifests/etcd_cluster.scala` run
  against tst0/tst1/tst2 to find out what the design got wrong.

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
