// SPDX-License-Identifier: Apache-2.0

import cats.effect.IO

import orphera.orchestrator.*

// Upgrades the cephadm cluster on tst0-tst2 to a new Ceph release (Squid 19.2
// to Tentacle 20.2, or a point release), in stages, checking the cluster
// between each: the managers, then the monitors, then the OSDs one host at a
// time, then everything else (MDS, RGW, crash, exporters, monitoring).
//
// cephadm does the work on each daemon (`ceph orch upgrade start` with
// --daemon-types and --hosts, a staged upgrade); this script decides what
// runs next from what the cluster says. Each stage waits until cephadm has
// finished it and the cluster is HEALTH_OK again before the next one starts,
// so a host whose OSDs do not come back stops the upgrade there. If cephadm
// pauses the upgrade (an image it cannot pull, a daemon that will not start),
// the script stops with cephadm's message.
//
// The image is resolved once, at the start, to its digest, and every stage
// uses that digest, so the cluster ends on one build even if the tag moves.
//
// Settings:
//   CEPH_IMAGE       image to upgrade to (default quay.io/ceph/ceph:v20, the
//                    newest Tentacle; name a release with e.g. :v20.2.4)
//   CEPH_ADMIN       node that runs the ceph commands (default tst0)
//   CEPH_ALLOW_WARN  yes: start from HEALTH_WARN, and between stages wait for
//                    every PG to be active+clean rather than for HEALTH_OK
//
// Run:   orphera playbook manifests/ceph_upgrade.scala
//        (--resume after a failure: the stages started are not started
//        again; the waits and checks run again)
//
// It only reads the cluster before the first stage starts: a run whose image
// every daemon already has changes nothing.
object ceph_upgrade extends OrpheraScript:

  private val image = sys.env.getOrElse("CEPH_IMAGE", "quay.io/ceph/ceph:v20")
  private val admin = sys.env.getOrElse("CEPH_ADMIN", "tst0")
  private val allowWarn = sys.env.get("CEPH_ALLOW_WARN").contains("yes")

  // `ceph` through the cephadm shell, without its "Inferring fsid" lines.
  //
  // status() prints four lines from `ceph orch upgrade status`: running
  // (yes/no), paused (yes/no), the target image, and what cephadm says. Squid
  // answers in JSON; Tentacle, with no upgrade running, answers in words
  // ("There are no upgrades in progress currently"), so the words are read too.
  // Anything else counts as still running, with the text as what it says.
  private val helpers =
    """fail() { echo "FAILED: $(hostname -s): $*"; exit 1; }
      |c() { cephadm shell -- ceph "$@" 2>/dev/null; }
      |status() {
      |  c orch upgrade status --format json | python3 -c '
      |import json, sys
      |t = sys.stdin.read()
      |try:
      |    s = json.loads(t)
      |except ValueError:
      |    words = " ".join(t.split())
      |    if "no upgrade" in words.lower():
      |        s = {"in_progress": False}
      |    else:
      |        s = {"in_progress": True, "message": "unreadable upgrade status: " + words[:200]}
      |if not isinstance(s, dict):
      |    s = {"in_progress": True, "message": "unreadable upgrade status: " + " ".join(t.split())[:200]}
      |print("yes" if s.get("in_progress") else "no")
      |print("yes" if s.get("is_paused") else "no")
      |print(s.get("target_image") or "")
      |print(" ".join(x for x in [s.get("progress") or "", (s.get("message") or "").replace("\n", " ")] if x))
      |'
      |}
      |""".stripMargin

  // Prints the target digest, the target version, and how many daemons need
  // the upgrade and how many have it, one per line.
  private val checkScript =
    helpers +
      """set -e
        |out=$(c orch upgrade check --image '@IMAGE@') || fail "ceph orch upgrade check --image @IMAGE@ failed: $out"
        |printf '%s' "$out" | python3 -c '
        |import json, sys
        |s = json.load(sys.stdin)
        |print(s.get("target_digest") or "")
        |print(s.get("target_version") or "")
        |print(len(s.get("needs_update") or {}))
        |print(len(s.get("up_to_date") or []))
        |'""".stripMargin.replace("@IMAGE@", image)

  // Prints whether an upgrade is in progress (yes/no) and its target image.
  private val statusScript =
    helpers + "status | sed -n '1p;3p'"

  private val healthScript = helpers + "c health"

  // How many manager daemons; cephadm needs a standby to upgrade them.
  private val mgrCountScript =
    helpers +
      """c orch ps --daemon-type mgr --format json | python3 -c '
        |import json, sys
        |print(len(json.load(sys.stdin)))
        |'""".stripMargin

  // The hosts with OSDs, one per line, in name order.
  private val osdHostsScript =
    helpers +
      """c orch ps --daemon-type osd --format json | python3 -c '
        |import json, sys
        |for h in sorted({d["hostname"] for d in json.load(sys.stdin)}):
        |    print(h)
        |'""".stripMargin

  // Waits until cephadm has finished the stage, then until the cluster is
  // healthy again, printing a line whenever what it is waiting for changes.
  private val waitScript =
    helpers +
      """set -e
        |sleep 10
        |last=""
        |while :; do
        |  info=$(status) || { sleep 15; continue; }
        |  running=$(printf '%s\n' "$info" | sed -n 1p)
        |  paused=$(printf '%s\n' "$info" | sed -n 2p)
        |  said=$(printf '%s\n' "$info" | sed -n 4p)
        |  [ "$paused" = yes ] && fail "cephadm paused the upgrade: $said (ceph health detail; then ceph orch upgrade resume)"
        |  [ "$running" = no ] && break
        |  [ "$said" != "$last" ] && echo "cephadm: ${said:-upgrading}"
        |  last=$said
        |  sleep 15
        |done
        |last=""
        |while :; do
        |  h=$(c health)
        |  case "$h" in HEALTH_OK*) echo "health: HEALTH_OK"; break ;; esac
        |  if [ "@ALLOW_WARN@" = yes ]; then
        |    p=$(c pg stat)
        |    if printf '%s\n' "$p" | grep -Eq '^([0-9]+) pgs: \1 active\+clean;'; then
        |      echo "health: $h; every PG active+clean ($p)"
        |      break
        |    fi
        |  fi
        |  [ "$h" != "$last" ] && echo "waiting for the cluster: $h"
        |  last=$h
        |  sleep 15
        |done
        |c versions | python3 -c '
        |import json, sys
        |for k, n in json.load(sys.stdin)["overall"].items():
        |    print("  %s: %d daemon(s)" % (k.split(" (")[0], n))
        |'""".stripMargin.replace("@ALLOW_WARN@", if allowWarn then "yes" else "no")

  private def lines(r: TaskExecutor.StepResult): List[String] =
    r.stdout.linesIterator.map(_.trim).toList

  private def fail(message: String): IO[Nothing] =
    IO.raiseError(new IllegalStateException(message))

  def script(using orphera: Orphera): IO[Unit] =
    val node = orphera.node(admin)

    // Starts one stage of the upgrade on `digest`, then waits for it.
    def stage(digest: String, label: String, limits: String): IO[Unit] =
      node.sh(
        s"start the upgrade: $label",
        s"$helpers set -e\nc orch upgrade start --image '$digest' $limits",
        timeout = 120
      ) >> node.sh(s"wait for the upgrade: $label", waitScript, timeout = 3600).void

    for
      check <- node.query("what needs upgrading", checkScript, 900)
      _ <- IO.raiseWhen(check.exitCode != 0)(
        new IllegalStateException(s"cannot check $image (see output above)")
      )
      (digest, version, needed, done) <- lines(check) match
        case d :: v :: n :: u :: _ if d.nonEmpty =>
          IO.pure((d, v, n.toIntOption.getOrElse(0), u.toIntOption.getOrElse(0)))
        case other => fail(s"unexpected answer from ceph orch upgrade check: ${other.mkString(" | ")}")
      _ <- IO.println(s"[$admin] target: $version ($digest); $needed daemon(s) to upgrade, $done already on it")
      _ <-
        if needed == 0 then IO.println(s"[$admin] every daemon is already on $version: nothing to do")
        else upgrade(node, digest, version, done, stage)
    yield ()

  private def upgrade(
      node: NodeRef,
      digest: String,
      version: String,
      done: Int,
      stage: (String, String, String) => IO[Unit]
  ): IO[Unit] =
    // The release name, e.g. "tentacle", from "ceph version 20.2.4 (...) tentacle (stable)".
    val release = version.split("\\s+").toList.reverse match
      case "(stable)" :: name :: _ => name
      case _                       => ""
    for
      status <- node.query("is an upgrade already running", statusScript)
      _ <- lines(status) match
        case "yes" :: target :: _ if target != digest =>
          fail(s"an upgrade to $target is already running: wait for it, or stop it (ceph orch upgrade stop)")
        case _ => IO.unit
      // A cluster part-way through this upgrade (a resume) is not HEALTH_OK; one not yet started must be.
      health <- node.query("cluster health", healthScript)
      h = health.stdout.trim
      _ <- IO.raiseUnless(done > 0 || h.startsWith("HEALTH_OK") || (allowWarn && h.startsWith("HEALTH_WARN")))(
        new IllegalStateException(
          s"the cluster is $h: fix it first (ceph health detail), or set CEPH_ALLOW_WARN=yes for a warning"
        )
      )
      mgrs <- node.query("how many managers", mgrCountScript)
      _ <- IO.raiseWhen(mgrs.stdout.trim.toIntOption.getOrElse(0) < 2)(
        new IllegalStateException(
          s"cephadm needs a standby manager to upgrade them; there are ${mgrs.stdout.trim}: ceph orch apply mgr 2"
        )
      )
      osdHosts <- node.query("hosts with OSDs", osdHostsScript).map(lines(_).filter(_.nonEmpty))
      _ <- IO.println(s"[${node.name}] stages: mgr, mon, OSDs on ${osdHosts.mkString(", ")}, then the rest")
      _ <- stage(digest, "managers", "--daemon-types mgr")
      _ <- stage(digest, "monitors", "--daemon-types mon")
      _ <- osdHosts.foldLeft(IO.unit)((before, host) =>
        before >> stage(digest, s"OSDs on $host", s"--daemon-types crash,osd --hosts $host")
      )
      _ <- stage(digest, "everything else", "")
      // cephadm sets require-osd-release when the last OSD is upgraded; check.
      dump <- node.query("require-osd-release", helpers + "c osd dump | sed -n 's/^require_osd_release //p'")
      _ <-
        if release.nonEmpty && dump.stdout.trim != release then
          node.sh(
            s"require-osd-release $release",
            s"$helpers set -e\nc osd require-osd-release $release",
            timeout = 120
          ).void
        else IO.unit
      _ <- IO.println(s"[${node.name}] upgraded to $version")
    yield ()
