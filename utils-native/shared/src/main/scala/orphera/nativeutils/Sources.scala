// SPDX-License-Identifier: Apache-2.0

package orphera.nativeutils

import java.nio.charset.StandardCharsets
import java.nio.file.{Files, Path, Paths}

import scala.jdk.CollectionConverters.*
import scala.util.Try

/** What utils/orphera-sources.sh and utils/vm-common.sh give the shell
  * scripts: where they are, the environment (with an installation's site
  * defaults), the config directory and inventory file, `config_value`,
  * `hypervisor_list` and `vm_lookup`.
  *
  * @param root
  *   where relative paths, given or made here, count from and where commands
  *   run: the checkout for vmctl (vmctl.sh does `cd` there), the working
  *   directory for move-vm (move-vm.sh does not)
  * @param utilsDir
  *   the directory of the executable, utils/ (where the shell versions are)
  * @param env
  *   the environment for the commands run and for the variables read here
  */
final class Sources(val root: Path, val utilsDir: Path, val env: Map[String, String]):

  def get(name: String): String = env.getOrElse(name, "")

  def path(p: String): Path = root.resolve(p)

  def isFile(p: String): Boolean = p.nonEmpty && Files.isRegularFile(path(p))

  /** `config_value <file> <key>`: the value of the first `  <key>:` line (a
    * top-level key under `params:`), without a trailing comment or quotes;
    * empty if there is none or the file cannot be read.
    */
  def configValue(file: String, key: String): String =
    val prefix = s"  $key:"
    Try(new String(Files.readAllBytes(path(file)), StandardCharsets.UTF_8)).toOption
      .flatMap(_.split("\n", -1).find(_.startsWith(prefix)))
      .map(
        _.substring(prefix.length)
          .replaceFirst("^[ \t]*", "")
          .replaceFirst("[ \t]*#.*$", "")
          .replaceAll("^[\"']|[\"']$", "")
      )
      .getOrElse("")

  /** `config_dir`: ORPHERA_CONFIG_DIR as a path or a file: URI, else config. */
  def configDir: Either[String, String] =
    val d0 = env.get("ORPHERA_CONFIG_DIR").filter(_.nonEmpty).getOrElse("config")
    val d = d0.stripPrefix("file://")
    if d.contains("://") then Left(s"ORPHERA_CONFIG_DIR is $d: these scripts read files only")
    else Right(d.stripSuffix("/"))

  /** `inventory_file`: ORPHERA_INVENTORY as a path or a file: URI, else
    * inventory.yaml.
    */
  def inventoryFile: Either[String, String] =
    val f = env.get("ORPHERA_INVENTORY").filter(_.nonEmpty).getOrElse("inventory.yaml").stripPrefix("file://")
    if f.contains("://") then Left(s"ORPHERA_INVENTORY is $f: these scripts read files only")
    else Right(f)

  /** `usage`: the comment block at the top of utils/<script>
    * (`sed -n '2,/^$/p' | sed 's/^# \{0,1\}//'`), or `fallback` if it cannot be
    * read.
    */
  def usage(script: String, fallback: String): Unit =
    Try(Files.readAllBytes(utilsDir.resolve(script))).toOption match
      case Some(bytes) =>
        val lines = new String(bytes, StandardCharsets.UTF_8).split("\n", -1).toVector.drop(1)
        val (head, rest) = lines.span(_.nonEmpty)
        (head ++ rest.take(1)).foreach(l => Out.out(l.replaceFirst("^# ?", "")))
      case None => Out.out(fallback)

  /** `hypervisor_list`: the members of the inventory group `hypervisors`, else
    * HYPERVISORS, else gs1 gs2 gs3 st0; one string, names separated by spaces.
    */
  def hypervisorList: String =
    val fromGroup =
      if Proc.which("orphera", env, root).isEmpty then ""
      else
        val (_, listing) =
          Proc.capture(List("orphera", "nodes", "--groups"), env, root, ErrMode.Discard)
        listing
          .split("\n")
          .filter(l => l.takeWhile(_ != ':') == "hypervisors")
          .lastOption
          .map(l => if l.contains(':') then l.dropWhile(_ != ':').drop(1) else l)
          .getOrElse("")
    val members = fromGroup
      .replaceAll("[ \t,]+", " ")
      .stripPrefix(" ")
      .stripSuffix(" ")
    if members.nonEmpty then members
    else if get("HYPERVISORS").nonEmpty then get("HYPERVISORS")
    else "gs1 gs2 gs3 st0"

  /** `vm_lookup <name | number | config.yaml>`: the config file (as given or
    * made, relative to the checkout), hypervisor and libvirt domain of a VM;
    * None, after saying why on standard error, if they cannot be found.
    */
  def vmLookup(arg: String): Option[Sources.Vm] =
    val cfgE: Either[String, String] =
      if arg.endsWith(".yaml") then Right(arg)
      else
        configDir.map { dir =>
          if Sources.allDigits(arg) then s"$dir/tst$arg.yaml" else s"$dir/$arg.yaml"
        }
    cfgE match
      case Left(msg) =>
        Out.err(msg)
        None
      case Right(cfg) if !isFile(cfg) =>
        Out.err(s"$arg: no config file $cfg")
        None
      case Right(cfg) =>
        val hv = configValue(cfg, "hypervisor")
        val vm = configValue(cfg, "hostname")
        if hv.isEmpty || vm.isEmpty then
          Out.err(s"$arg: $cfg has no hypervisor: or hostname:")
          None
        else if !s"$hv$vm".forall(c => Sources.plainChar(c)) then
          Out.err(s"$arg: unusual characters in hypervisor or hostname in $cfg; not running")
          None
        else Some(Sources.Vm(cfg, hv, vm))

object Sources:

  final case class Vm(cfg: String, hv: String, name: String)

  /** True for "" too, as the shell's `*[!0-9]*` test: a bare number means tstN. */
  def allDigits(s: String): Boolean = s.forall(c => c >= '0' && c <= '9')

  def alnum(c: Char): Boolean =
    (c >= 'A' && c <= 'Z') || (c >= 'a' && c <= 'z') || (c >= '0' && c <= '9')

  def plainChar(c: Char): Boolean = alnum(c) || c == '.' || c == '_' || c == '-'

  /** utils/: the directory this executable is in. */
  def findUtilsDir(): Path =
    Try(Paths.get("/proc/self/exe").toRealPath()).toOption
      .flatMap(exe => Option(exe.getParent))
      .getOrElse(Paths.get("utils").toAbsolutePath)

  /** For a script that does not source orphera-sources.sh
    * (galera-cold-start.sh): this process's environment as it is, relative
    * paths from the working directory.
    */
  def plain(): Sources =
    new Sources(Paths.get("").toAbsolutePath, findUtilsDir(), System.getenv().asScala.toMap)

  /** The environment orphera-sources.sh would give: this process's own, or,
    * in an installation (utils/ is PREFIX/share/orphera/utils and
    * PREFIX/lib/orphera/orphera-env.sh exists), that with ORPHERA_PREFIX set,
    * orphera-env.sh sourced (by bash, so the file stays the one definition of
    * the site defaults) and PREFIX/bin on PATH.
    */
  def load(rootIsCheckout: Boolean): Sources =
    val utilsDir = findUtilsDir()
    val root =
      if rootIsCheckout then Option(utilsDir.getParent).getOrElse(utilsDir)
      else Paths.get("").toAbsolutePath
    val base = System.getenv().asScala.toMap
    val utils = utilsDir.toString
    val suffix = "/share/orphera/utils"
    val installed =
      if !utils.endsWith(suffix) then None
      else
        val prefix0 = utils.stripSuffix(suffix)
        val envFile = s"$prefix0/lib/orphera/orphera-env.sh"
        if Files.isRegularFile(Paths.get(envFile)) then
          Some((if prefix0.isEmpty then "/" else prefix0, envFile))
        else None
    installed match
      case None => new Sources(root, utilsDir, base)
      case Some((prefix, envFile)) =>
        val withPrefix = base + ("ORPHERA_PREFIX" -> prefix)
        val (rc, dump) = Proc.capture(
          List("bash", "-c", ". \"$1\" >/dev/null; env -0", "orphera-env", envFile),
          withPrefix,
          root
        )
        val sourced =
          if rc != 0 then withPrefix
          else
            dump
              .split("\u0000")
              .iterator
              .filter(_.contains('='))
              .map(kv => kv.takeWhile(_ != '=') -> kv.dropWhile(_ != '=').drop(1))
              .toMap
        val path = sourced.getOrElse("PATH", "")
        val bin = s"$prefix/bin"
        val withBin =
          if s":$path:".contains(s":$bin:") then sourced
          else sourced + ("PATH" -> s"$bin:$path")
        new Sources(root, utilsDir, withBin)

/** Lines to standard output and error, flushed at once so the two keep their
  * order on a terminal.
  */
object Out:
  def out(line: String): Unit =
    System.out.println(line)
    System.out.flush()

  def err(line: String): Unit =
    System.err.println(line)
    System.err.flush()

  /** printf without a newline (a prompt). */
  def outPart(text: String): Unit =
    System.out.print(text)
    System.out.flush()

  def outBytes(bytes: Array[Byte]): Unit =
    System.out.write(bytes, 0, bytes.length)
    System.out.flush()
