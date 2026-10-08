// SPDX-License-Identifier: Apache-2.0

package orphera.orchestrator

import cats.effect.*
import cats.effect.std.Semaphore
import java.nio.file.{Files, Path, Paths, StandardCopyOption}
import scala.jdk.CollectionConverters.*

/** Per-task, per-node restart checkpointing for both PlaybookRunner and
  * ClusterPlaybookRunner.
  *
  * Motivating, real (not hypothetical) gap: a staged run — e.g.
  * cephadm_add_osds.scala's apply-osd-spec, adding OSDs on 5 separate
  * node:device pairs — that succeeds on 3 and fails on the 4th has no safe way
  * to resume. Naively re-running the whole playbook replays the
  * already-succeeded `ceph orch daemon add osd` commands, which are NOT
  * idempotent: the device already carries an OSD's LVM signature by then,
  * producing the exact "device has a signature" class of error this project
  * already hit and fixed once, in cephadm_teardown.scala, for an unrelated
  * reason (a leftover device-mapper entry). Checkpoint- skipping
  * already-completed tasks avoids replaying them at all, which is a stronger
  * guarantee than trying to make every possible `run_command` idempotent on the
  * target side.
  *
  * State lives in a local file on the control host
  * (`.orphera-state/<kind>-<playbook-name>.txt`) rather than on the target
  * agent — no new RPC, no new agent-side failure mode, for a first version of
  * this. Trade-off, deliberately accepted for now: resume only works from the
  * same control host and working directory that produced the checkpoint file;
  * add `.orphera-state/` to `.gitignore`.
  *
  * Format is deliberately plain text, not JSON/YAML: one "<node>\t<task name>"
  * line per completed pair, no library dependency beyond java.nio.file. Assumes
  * node and task names never contain a tab or newline — true everywhere else in
  * this codebase, since both are already rendered into single-line log output
  * (`s"[${node.name}] ${namedTask.name}: ..."`).
  *
  * Resume is opt-in (`--resume` / `ORPHERA_RESUME=true`), never automatic: an
  * ordinary run always starts this playbook's checkpoint state fresh (ignoring
  * and immediately overwriting any existing file for this playbook name), so a
  * later `--resume` picks up from *that* run's real progress, not some older,
  * possibly-stale one. Every task's completion is still recorded incrementally
  * during an ordinary (non-resume) run too, purely so a later `--resume` has
  * something accurate to resume from if this run fails partway.
  *
  * Known limitation, accepted for a first version rather than building
  * content-hash-based task identity: a task is identified only by (node name,
  * task name). Renaming, reordering, or duplicating a task name between runs
  * will confuse resumption — this is the same fragility Ansible's
  * `--start-at-task` has.
  */
object Checkpoint:

  private type State = Map[String, Set[String]]
  private val emptyState: State = Map.empty

  final class Handle private[Checkpoint] (
      file: Path,
      ref: Ref[IO, State],
      lock: Semaphore[IO]
  ):
    /** True only when this run was started with --resume AND (node, taskName)
      * was recorded as completed by a previous run's checkpoint file.
      */
    def isDone(node: String, taskName: String): IO[Boolean] =
      ref.get.map(_.getOrElse(node, Set.empty).contains(taskName))

    /** Records (node, taskName) as completed and flushes to disk immediately —
      * not batched — so a crash mid-run still leaves an accurate, resumable
      * checkpoint file. That immediacy is the entire point of the mechanism,
      * not an optimization to skip.
      */
    def markDone(node: String, taskName: String): IO[Unit] =
      lock.permit.use { _ =>
        ref
          .updateAndGet(s =>
            s + (node -> (s.getOrElse(node, Set.empty) + taskName))
          )
          .flatMap(writeAtomically)
      }

    private def writeAtomically(state: State): IO[Unit] =
      IO.blocking {
        val lines = state.toSeq.sortBy(_._1).flatMap { case (node, tasks) =>
          tasks.toSeq.sorted.map(task => s"$node\t$task")
        }
        val tmp = Files.createTempFile(
          file.getParent,
          file.getFileName.toString,
          ".tmp"
        )
        Files.write(tmp, lines.asJava)
        Files.move(
          tmp,
          file,
          StandardCopyOption.REPLACE_EXISTING,
          StandardCopyOption.ATOMIC_MOVE
        )
      }

  /** Loads (or initializes) checkpoint state for one playbook run. `kind`
    * ("playbook" / "cluster") distinguishes a flat playbook's state file from a
    * staged cluster-playbook's, so two playbooks that happen to share a name
    * across the two runners don't collide.
    *
    * `resume = false` (the default, and every existing call site's unchanged
    * behavior) starts from empty state and immediately overwrites any existing
    * file for this playbook — `isDone` will never return true, exactly matching
    * pre-checkpoint behavior. `resume = true` loads the previous run's recorded
    * completions first, so already-completed (node, task) pairs are skipped.
    */
  def load(kind: String, playbookName: String, resume: Boolean): IO[Handle] =
    for
      dir <- IO.blocking(Files.createDirectories(Paths.get(".orphera-state")))
      // A multi-config `cluster-playbook` run sets ORPHERA_RUN_TAG (the
      // config's name) so each parallel run keeps its own checkpoint, and
      // `--resume` resumes each config where it stopped.
      tag = sys.env
        .get("ORPHERA_RUN_TAG")
        .filter(_.nonEmpty)
        .map(t => "-" + sanitize(t))
        .getOrElse("")
      file = dir.resolve(s"$kind-${sanitize(playbookName)}$tag.txt")
      initial <- if resume then readExisting(file) else IO.pure(emptyState)
      ref <- Ref.of[IO, State](initial)
      lock <- Semaphore[IO](1)
      handle = new Handle(file, ref, lock)
      // Overwrite the on-disk file right away (even in the resume case,
      // where this just re-serializes what was loaded) so the file on
      // disk always reflects "this run's starting point" rather than
      // silently keeping stale content from an unrelated earlier run
      // that a future non-resume invocation might otherwise inherit.
      _ <- IO.blocking {
        val lines = initial.toSeq.sortBy(_._1).flatMap { case (node, tasks) =>
          tasks.toSeq.sorted.map(task => s"$node\t$task")
        }
        val tmp = Files.createTempFile(dir, file.getFileName.toString, ".tmp")
        Files.write(tmp, lines.asJava)
        Files.move(
          tmp,
          file,
          StandardCopyOption.REPLACE_EXISTING,
          StandardCopyOption.ATOMIC_MOVE
        )
      }
    yield handle

  private def readExisting(file: Path): IO[State] =
    IO.blocking(Files.exists(file)).flatMap { exists =>
      if !exists then IO.pure(emptyState)
      else
        IO.blocking(Files.readAllLines(file).asScala.toList).attempt.map {
          case Left(_)      => emptyState
          case Right(lines) =>
            lines
              .flatMap { line =>
                val idx = line.indexOf('\t')
                if idx < 0 then None
                else Some(line.substring(0, idx) -> line.substring(idx + 1))
              }
              .groupMap(_._1)(_._2)
              .view
              .mapValues(_.toSet)
              .toMap
        }
    }

  private def sanitize(name: String): String =
    name.replaceAll("[^a-zA-Z0-9._-]", "-")
