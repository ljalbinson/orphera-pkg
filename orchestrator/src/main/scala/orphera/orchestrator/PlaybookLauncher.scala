// SPDX-License-Identifier: Apache-2.0

package orphera.orchestrator

import java.lang.reflect.InvocationTargetException

/** A playbook failed. Its message is the whole report: toString is just the
  * message and there is no stack trace to fill in, so when cats-effect's
  * IOApp prints the error that ended the program, the output is one line
  * instead of a Java backtrace.
  */
final class PlaybookFailed(message: String)
    extends RuntimeException(message, null, false, false):
  override def toString: String = message

/** Starts a compiled playbook script's `main` for orphera.scripting.Main.
  *
  * Errors that happen while the script starts (a --config file that does not
  * load, a missing --config) are thrown from the script object's initializer,
  * and the JVM would print them as ExceptionInInitializerError with a stack
  * trace. This prints `Error: <message>` instead and exits 1. Set
  * ORPHERA_DEBUG=1 to get the full stack trace back.
  */
object PlaybookLauncher:

  def main(args: Array[String]): Unit =
    args.toList match
      case className :: rest =>
        try
          val mainArgs: AnyRef = rest.toArray // one argument: the String[] that main takes
          Class
            .forName(className)
            .getMethod("main", classOf[Array[String]])
            .invoke(null, mainArgs)
          ()
        catch
          case t: Throwable =>
            val cause = reported(t)
            if sys.env.get("ORPHERA_DEBUG").contains("1") then
              cause.printStackTrace()
            else System.err.println(s"Error: ${describe(cause)}")
            sys.exit(1)
      case Nil =>
        System.err.println("Usage: PlaybookLauncher <class> [args...]")
        sys.exit(2)

  /** Reflection and class initialization wrap the real error; take it out. */
  private def reported(t: Throwable): Throwable =
    t match
      case e: InvocationTargetException if e.getCause != null =>
        reported(e.getCause)
      case e: ExceptionInInitializerError if e.getCause != null =>
        reported(e.getCause)
      case other => other

  private def describe(t: Throwable): String =
    Option(t.getMessage).filter(_.nonEmpty).getOrElse(t.getClass.getName)
