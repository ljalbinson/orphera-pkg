// SPDX-License-Identifier: Apache-2.0

package orphera.agent

import cats.effect.*
import cats.effect.std.Queue
import fs2.Stream
import io.grpc.Metadata
import orphera.common.*

class AgentServiceImpl(
    networkPending: Ref[IO, Map[String, Fiber[IO, Throwable, Unit]]]
) extends AgentFs2Grpc[IO, Metadata]:

  /** Runs `work` in a detached fiber and streams its queue to the caller until
    * a RESULT event. RESULT is the only thing that ends the stream, so a task
    * that raises before it has sent one would die silently inside the fiber and
    * leave the stream — and the orchestrator reading it — open forever (seen
    * with a failed `apt-get update`). Any such error is therefore turned into a
    * FAILED RESULT here. If `work` already sent its RESULT the extra event is
    * queued behind it and never read.
    */
  private def streamTask[A](
      work: Queue[IO, Event] => IO[A]
  ): Stream[IO, Event] =
    Stream.eval(Queue.unbounded[IO, Event]).flatMap { queue =>
      val guarded = work(queue).void.handleErrorWith { err =>
        queue.offer(
          Event(
            kind = Event.Kind.RESULT,
            message = s"FAILED: ${err.getMessage}",
            exitCode = 1,
            success = false
          )
        )
      }
      Stream.eval(guarded.start) >>
        Stream
          .fromQueueUnterminated(queue)
          .takeThrough(_.kind != Event.Kind.RESULT)
    }

  def install(request: InstallPackages, ctx: Metadata): Stream[IO, Event] =
    streamTask(queue => Dispatcher.dispatch(request, queue))

  def remove(request: RemovePackages, ctx: Metadata): Stream[IO, Event] =
    streamTask(queue => Dispatcher.dispatchRemove(request, queue))

  def runAutoRemove(request: AutoRemove, ctx: Metadata): Stream[IO, Event] =
    streamTask(queue => Dispatcher.dispatchAutoRemove(request, queue))

  def runDistUpgrade(request: DistUpgrade, ctx: Metadata): Stream[IO, Event] =
    streamTask(queue => Dispatcher.dispatchDistUpgrade(request, queue))

  def copyFile(
      request: Stream[IO, FileChunk],
      ctx: Metadata
  ): Stream[IO, Event] =
    streamTask(queue => FileTransfer.receive(request, queue))

  def checkFile(request: FileCheck, ctx: Metadata): IO[FileCheckResult] =
    FileTransfer.check(request)

  def reloadNetwork(
      request: NetworkReload,
      ctx: Metadata
  ): IO[NetworkReloadResult] =
    NetworkReloader.reload(request, networkPending)

  def confirmNetwork(request: NetworkConfirm, ctx: Metadata): IO[Empty] =
    NetworkReloader.confirm(request.backupId, networkPending) >> IO.pure(
      Empty()
    )

  def installDebPackage(request: InstallDeb, ctx: Metadata): Stream[IO, Event] =
    streamTask(queue => Dispatcher.dispatchInstallDeb(request, queue))

  def remoteFetchFile(request: FetchFile, ctx: Metadata): Stream[IO, FileData] =
    FileTransfer.send(request)

  def gatherFacts(request: FactsRequest, ctx: Metadata): IO[Facts] =
    FactGatherer.gather()

  def triggerReboot(request: RebootRequest, ctx: Metadata): IO[RebootAck] =
    Rebooter.trigger(request)

  def triggerShutdown(
      request: ShutdownRequest,
      ctx: Metadata
  ): IO[ShutdownAck] =
    Shutdowner.trigger(request)

  def getVersion(request: VersionRequest, ctx: Metadata): IO[VersionInfo] =
    IO.pure(VersionInfo(BuildInfo.version))

  def getUptime(request: UptimeRequest, ctx: Metadata): IO[UptimeInfo] =
    UptimeReader.read()

  def executeCommand(
      request: RunCommandRequest,
      ctx: Metadata
  ): Stream[IO, Event] =
    streamTask(queue => Dispatcher.dispatchRunCommand(request, queue))
