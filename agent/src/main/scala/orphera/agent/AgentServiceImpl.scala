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

  def install(request: InstallPackages, ctx: Metadata): Stream[IO, Event] =
    Stream.eval(Queue.unbounded[IO, Event]).flatMap { queue =>
      Stream.eval(Dispatcher.dispatch(request, queue).start) >>
        Stream
          .fromQueueUnterminated(queue)
          .takeThrough(_.kind != Event.Kind.RESULT)
    }

  def remove(request: RemovePackages, ctx: Metadata): Stream[IO, Event] =
    Stream.eval(Queue.unbounded[IO, Event]).flatMap { queue =>
      Stream.eval(Dispatcher.dispatchRemove(request, queue).start) >>
        Stream
          .fromQueueUnterminated(queue)
          .takeThrough(_.kind != Event.Kind.RESULT)
    }

  def runAutoRemove(request: AutoRemove, ctx: Metadata): Stream[IO, Event] =
    Stream.eval(Queue.unbounded[IO, Event]).flatMap { queue =>
      Stream.eval(Dispatcher.dispatchAutoRemove(request, queue).start) >>
        Stream
          .fromQueueUnterminated(queue)
          .takeThrough(_.kind != Event.Kind.RESULT)
    }

  def copyFile(
      request: Stream[IO, FileChunk],
      ctx: Metadata
  ): Stream[IO, Event] =
    Stream.eval(Queue.unbounded[IO, Event]).flatMap { queue =>
      Stream.eval(FileTransfer.receive(request, queue).start) >>
        Stream
          .fromQueueUnterminated(queue)
          .takeThrough(_.kind != Event.Kind.RESULT)
    }

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
    Stream.eval(Queue.unbounded[IO, Event]).flatMap { queue =>
      Stream.eval(Dispatcher.dispatchInstallDeb(request, queue).start) >>
        Stream
          .fromQueueUnterminated(queue)
          .takeThrough(_.kind != Event.Kind.RESULT)
    }

  def remoteFetchFile(request: FetchFile, ctx: Metadata): Stream[IO, FileData] =
    FileTransfer.send(request)

  def gatherFacts(request: FactsRequest, ctx: Metadata): IO[Facts] =
    FactGatherer.gather()

  def triggerReboot(request: RebootRequest, ctx: Metadata): IO[RebootAck] =
    Rebooter.trigger(request)

  def getVersion(request: VersionRequest, ctx: Metadata): IO[VersionInfo] =
    IO.pure(VersionInfo(BuildInfo.version))

  def getUptime(request: UptimeRequest, ctx: Metadata): IO[UptimeInfo] =
    UptimeReader.read()

  def executeCommand(
      request: RunCommandRequest,
      ctx: Metadata
  ): Stream[IO, Event] =
    Stream.eval(Queue.unbounded[IO, Event]).flatMap { queue =>
      Stream.eval(Dispatcher.dispatchRunCommand(request, queue).start) >>
        Stream
          .fromQueueUnterminated(queue)
          .takeThrough(_.kind != Event.Kind.RESULT)
    }
