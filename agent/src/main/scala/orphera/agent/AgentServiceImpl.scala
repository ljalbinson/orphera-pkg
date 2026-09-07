package orphera.agent

import cats.effect.*
import cats.effect.std.Queue
import fs2.Stream
import io.grpc.Metadata
import orphera.common.*

class AgentServiceImpl extends AgentFs2Grpc[IO, Metadata]:

  def install(request: InstallPackages, ctx: Metadata): Stream[IO, Event] =
    Stream.eval(Queue.unbounded[IO, Event]).flatMap { queue =>
      Stream.eval(Dispatcher.dispatch(request, queue).start) >>
        Stream
          .fromQueueUnterminated(queue)
          .takeThrough(_.kind != Event.Kind.RESULT)
    }
