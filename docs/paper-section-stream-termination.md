# Stream termination in an agent-based orchestrator

*Draft section for the Orphera paper. Status of each claim is marked in [brackets] where it is not yet verified.*

## The protocol

An Orphera agent exposes each long-running task (package install, dist-upgrade, file copy, command execution) as a server-streaming gRPC call. The agent starts the task in a detached background fiber. The fiber writes events into an unbounded queue: `PROGRESS` for stage changes, `OUTPUT` for each line the underlying process prints, and finally one `RESULT` event carrying the exit code and a success flag. The gRPC response is the queue read back as a stream, and it ends at the first `RESULT` event. The orchestrator relies on this: it reads events, renders them, and treats the arrival of `RESULT` as the end of the task.

The design has one consequence worth stating plainly: **`RESULT` is the only thing that ends the stream.** The stream has no other end condition, no timeout and no heartbeat. Correctness of the whole system therefore depends on every code path in every task eventually enqueuing exactly one `RESULT`, at the right moment.

## Failure mode 1: the missing RESULT (a hang)

A package install first runs `apt-get update` and then `apt-get install`. The update step was implemented by a helper that reports failure by raising an exception, because it was designed to be one non-final step in a longer task. On one test node a leftover third-party apt source returned HTTP 404, so `apt-get update` exited non-zero and the helper raised.

The exception was raised inside the detached fiber. A fiber's error is not propagated to whoever started it; it simply terminates the fiber. Nothing caught it, so no `RESULT` was ever enqueued. The orchestrator, correctly following the protocol, waited for a `RESULT` that could never arrive. There was no error on either side: the agent was healthy and idle, and the orchestrator was blocked on a read. The symptom, observed during a MariaDB Galera deployment, was a playbook that stopped printing and never finished.

This failure is silent by construction. Output that had already been streamed (the apt error text) was visible to the operator, but the call itself never completed, so a multi-stage playbook could not continue, retry or report failure.

## Failure mode 2: the premature RESULT (false success)

The opposite error had been found earlier. If an intermediate step, such as the cache update, enqueues its own `RESULT` on success, the stream closes at that point. The orchestrator moves on and reports the task as successful while the real work (the install) is still running in the background, chained after the update. The visible symptom was an install that reported success while the package was not yet present, which looked like a timing race but was structural: the protocol's single end marker had been emitted too early. The fix was to give intermediate steps a variant that never enqueues `RESULT`, leaving exactly one terminal event per task.

Together the two failures show the shape of the problem. A single end-of-stream marker must be emitted exactly once: zero times hangs the caller, and early emission lies to it.

## The fix

For the hang, the first fix was local: the install task now catches the failure of its update step and enqueues a `FAILED` `RESULT` itself, as the dist-upgrade task already did. This repaired the observed case but left the class of bug in place. Any other handler that could raise before sending a `RESULT` (for example, a process that cannot be started) had the same exposure.

The general fix moves the guarantee out of the individual tasks and into the single place that starts them. A helper wraps every streaming handler: it runs the task, and if the task fails with an exception it enqueues a `FAILED` `RESULT` carrying the error message. If the task had already sent its `RESULT`, the extra event is queued behind it and is never read, because the stream ends at the first. The seven streaming handlers (install, remove, autoremove, dist-upgrade, copy-file, install-deb, run-command) now share this one path, so a new handler written in the same style inherits the guarantee. [Not yet exercised on a deployed agent.]

## What remains

The wrapper handles tasks that fail. It does not handle a task that finishes normally without ever sending a `RESULT`, and a blanket fallback for that case risks cutting short handlers that deliberately pass the `RESULT` to a nested background fiber. The orchestrator also has no no-output timeout; one would turn any remaining protocol bug into a reported error instead of a hang, but a total-time limit would be wrong for legitimately long tasks such as a dist-upgrade, so it would need to measure silence, not duration. [Design decision open.]

## Lessons

1. **Make the terminal event structural, not conventional.** Where a protocol has a single end marker, enforce "exactly once" in one place (here, the wrapper that starts the task) instead of trusting each task author.
2. **Never let an error die in a detached fiber.** Concurrency primitives that detach work also detach its failures; the caller must install a handler where the work is started.
3. **Distinguish step helpers from task helpers.** A helper that ends a stream and one that does not need different names and different types, so that using the wrong one is a compile error and not a latent bug.
4. **Prefer a failure the operator can see.** Both failures were the system saying nothing; the fix turns both into explicit `FAILED` results with the original message.
