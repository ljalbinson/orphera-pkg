// SPDX-License-Identifier: Apache-2.0

package orphera.orchestrator

import java.nio.charset.StandardCharsets
import java.security.MessageDigest

import scala.util.Try

import cats.effect.{IO, Ref}
import cats.syntax.all.*

/** Declarative apply (docs/APPLY-DESIGN.md): a description of how a node should
  * be, as resources. Each resource is observed (read from the node), compared
  * with what is wanted, and changed only where it differs; with `check`, only
  * observed and compared.
  *
  * A resource is named by its kind and a name unique on the node
  * (`file[/etc/chrony.conf]`). `after` makes it wait for others (and be skipped
  * if one of them fails); `notifies` makes a change to it run a command marked
  * `onChangeOnly`, once, after everything that notifies it.
  */
sealed trait ApplyResource:
  def id: String

  /** Ids of the resources this one comes after. */
  def needs: List[String]

  /** Ids of the commands a change to this one runs. */
  def triggers: List[String]
  protected def relinked(
      needs: List[String],
      triggers: List[String]
  ): ApplyResource

  /** This resource after `others` (wherever they stand in the list). */
  def after(others: ApplyResource*): ApplyResource =
    relinked(needs ++ others.map(_.id), triggers)

  /** As `after`, by id (`"file[/etc/x]"`). */
  def afterIds(ids: String*): ApplyResource = relinked(needs ++ ids, triggers)

  /** When this resource changes, `commands` (each `onChangeOnly`) run. */
  def notifies(commands: ApplyResource*): ApplyResource =
    relinked(needs, triggers ++ commands.map(_.id))

  def notifiesIds(ids: String*): ApplyResource =
    relinked(needs, triggers ++ ids)

object ApplyResource:

  /** A file's wanted content: text, secret text (never shown), or a local file
    * (a `.mustache` one rendered with `vars` and the node's facts and vars, as
    * `copy` does).
    */
  enum Content:
    case Text(text: String)
    case Hidden(secret: Secret)
    case Source(path: String, vars: Map[String, String])

    /** Text with `{{...}}` in it, rendered on this node with its facts and vars
      * (and `vars`) when the file is compared: `{{cluster_ip}}`,
      * `{{nodes.tst4.cluster_ip}}`, `{{node_name}}` (the node's inventory
      * name), `{{default_interface}}` (the interface of the node's default
      * route).
      */
    case Template(text: String, vars: Map[String, String])

    /** The bytes of a file on the node itself. They are copied there by the
      * agent and never come here, so it suits a key or a kubeconfig.
      */
    case OnNode(path: String)

  final case class File(
      path: String,
      content: Option[Content],
      mode: String,
      owner: String,
      group: String,
      present: Boolean,
      needs: List[String] = Nil,
      triggers: List[String] = Nil,
      onlyIfMissing: Boolean = false
  ) extends ApplyResource:
    def id: String = s"file[$path]"
    protected def relinked(n: List[String], t: List[String]): ApplyResource =
      copy(needs = n, triggers = t)

  final case class Directory(
      path: String,
      mode: String,
      owner: String,
      group: String,
      present: Boolean,
      needs: List[String] = Nil,
      triggers: List[String] = Nil,
      recursive: Boolean = false
  ) extends ApplyResource:
    def id: String = s"directory[$path]"
    protected def relinked(n: List[String], t: List[String]): ApplyResource =
      copy(needs = n, triggers = t)

  final case class Symlink(
      path: String,
      target: String,
      present: Boolean,
      needs: List[String] = Nil,
      triggers: List[String] = Nil
  ) extends ApplyResource:
    def id: String = s"symlink[$path]"
    protected def relinked(n: List[String], t: List[String]): ApplyResource =
      copy(needs = n, triggers = t)

  /** A shell command (`sh -c`). It runs when its guard says it is needed
    * (`onlyIf` exits 0, `unless` exits non-zero, `creates` does not exist) and,
    * with `onChangeOnly`, only when a resource that notifies it changed.
    *
    * A `gate` runs nothing: it is a question about the node (its guard). When
    * the guard says no, the gate is skipped and so is everything that comes
    * after it (`after`), which is how a description says "only on a node that
    * has systemd-resolved". When the guard says yes, the gate is unchanged.
    */
  final case class Command(
      label: String,
      run: String,
      onlyIf: String = "",
      unless: String = "",
      creates: String = "",
      onChangeOnly: Boolean = false,
      timeout: Int = 300,
      gate: Boolean = false,
      needs: List[String] = Nil,
      triggers: List[String] = Nil
  ) extends ApplyResource:
    def id: String = s"command[$label]"
    def guarded: Boolean =
      onlyIf.nonEmpty || unless.nonEmpty || creates.nonEmpty
    protected def relinked(n: List[String], t: List[String]): ApplyResource =
      copy(needs = n, triggers = t)

  /** A package, through apt (Debian, Ubuntu) or dnf (Rocky, RHEL), whichever
    * the node has. `version`, if given, is the version as the package manager
    * names it, and the installed one must equal it. `updateCache` runs
    * `apt-get update` before installing (dnf refreshes by itself).
    */
  final case class Package(
      name: String,
      version: String,
      updateCache: Boolean,
      present: Boolean,
      needs: List[String] = Nil,
      triggers: List[String] = Nil
  ) extends ApplyResource:
    def id: String = s"package[$name]"
    protected def relinked(n: List[String], t: List[String]): ApplyResource =
      copy(needs = n, triggers = t)

  /** A systemd unit, enabled or not and running or not (None: left as it is). A
    * resource that notifies it restarts it, once, unless this run starts it
    * anyway.
    */
  final case class Service(
      unit: String,
      enabled: Option[Boolean],
      running: Option[Boolean],
      needs: List[String] = Nil,
      triggers: List[String] = Nil
  ) extends ApplyResource:
    def id: String = s"service[$unit]"
    protected def relinked(n: List[String], t: List[String]): ApplyResource =
      copy(needs = n, triggers = t)

  /** A local user. `uid` -1, and an empty `group`, `home` or `shell`, are left
    * as they are; `groups` are supplementary groups it must be in (others it is
    * in are left alone). Removing it leaves its home directory.
    */
  final case class User(
      name: String,
      uid: Int,
      group: String,
      groups: List[String],
      home: String,
      shell: String,
      system: Boolean,
      present: Boolean,
      needs: List[String] = Nil,
      triggers: List[String] = Nil
  ) extends ApplyResource:
    def id: String = s"user[$name]"
    protected def relinked(n: List[String], t: List[String]): ApplyResource =
      copy(needs = n, triggers = t)

  /** A local group (`gid` -1: any). */
  final case class Group(
      name: String,
      gid: Int,
      system: Boolean,
      present: Boolean,
      needs: List[String] = Nil,
      triggers: List[String] = Nil
  ) extends ApplyResource:
    def id: String = s"group[$name]"
    protected def relinked(n: List[String], t: List[String]): ApplyResource =
      copy(needs = n, triggers = t)

  /** A kernel parameter: set now (`sysctl -w`) and, with `persist`, in
    * /etc/sysctl.d/60-orphera-<key>.conf so that it survives a reboot.
    */
  final case class Sysctl(
      key: String,
      value: String,
      persist: Boolean,
      needs: List[String] = Nil,
      triggers: List[String] = Nil
  ) extends ApplyResource:
    def id: String = s"sysctl[$key]"
    protected def relinked(n: List[String], t: List[String]): ApplyResource =
      copy(needs = n, triggers = t)

  /** A kernel module, loaded now (`modprobe`) and, with `persist`, listed in
    * /etc/modules-load.d/orphera-<name>.conf so that it is loaded at boot.
    */
  final case class KernelModule(
      name: String,
      persist: Boolean,
      needs: List[String] = Nil,
      triggers: List[String] = Nil
  ) extends ApplyResource:
    def id: String = s"kernel_module[$name]"
    protected def relinked(n: List[String], t: List[String]): ApplyResource =
      copy(needs = n, triggers = t)

  /** One line in a text file. Present: the line is there (with `match`, a
    * regular expression (awk ERE): the first line it matches is replaced by
    * `line` and other matches are dropped; with none, `line` is appended; the
    * file is created if missing). Absent: lines equal to `line`, or matching
    * `match`, are removed.
    */
  final case class LineInFile(
      path: String,
      line: String,
      matching: String,
      present: Boolean,
      needs: List[String] = Nil,
      triggers: List[String] = Nil
  ) extends ApplyResource:
    def id: String = s"line[$path: ${LineInFile.shown(line)}]"
    protected def relinked(n: List[String], t: List[String]): ApplyResource =
      copy(needs = n, triggers = t)

  object LineInFile:
    def shown(s: String): String =
      if s.length > 40 then s.take(37) + "..." else s

  /** A public key in a user's ~/.ssh/authorized_keys, found by its type and key
    * (the comment may differ).
    */
  final case class AuthorizedKey(
      user: String,
      key: String,
      present: Boolean,
      needs: List[String] = Nil,
      triggers: List[String] = Nil
  ) extends ApplyResource:
    def id: String = s"authorized_key[$user: ${AuthorizedKey.shown(key)}]"
    def keyId: String = key.trim.split("\\s+").take(2).mkString(" ")
    protected def relinked(n: List[String], t: List[String]): ApplyResource =
      copy(needs = n, triggers = t)

  object AuthorizedKey:
    def shown(key: String): String =
      val f = key.trim.split("\\s+").toList
      f match
        case t :: b :: rest =>
          s"$t ...${b.takeRight(8)}${rest.headOption.fold("")(" " + _)}"
        case _ => key

  /** A mount: its /etc/fstab entry (device, type, options) and whether it is
    * mounted now (`mounted` None: left as it is). Absent: unmounted and removed
    * from fstab; the directory stays.
    */
  final case class Mount(
      path: String,
      device: String,
      fstype: String,
      options: String,
      mounted: Option[Boolean],
      present: Boolean,
      needs: List[String] = Nil,
      triggers: List[String] = Nil
  ) extends ApplyResource:
    def id: String = s"mount[$path]"
    protected def relinked(n: List[String], t: List[String]): ApplyResource =
      copy(needs = n, triggers = t)

/** The functions a description is written with. `mode` is octal text ("0644");
  * an empty `mode`, `owner` or `group` is left as it is.
  */
object Resources:
  import ApplyResource.*

  def file(
      path: String,
      content: String,
      mode: String = "",
      owner: String = "",
      group: String = ""
  ): ApplyResource =
    File(path, Some(Content.Text(content)), mode, owner, group, present = true)

  /** A file that is written when it is not there and never again: what it holds
    * afterwards is the user's. Its mode, owner and group are still kept.
    */
  def seedFile(
      path: String,
      content: String,
      mode: String = "",
      owner: String = "",
      group: String = ""
  ): ApplyResource =
    File(
      path,
      Some(Content.Text(content)),
      mode,
      owner,
      group,
      present = true,
      onlyIfMissing = true
    )

  /** A file whose content is secret: it is never shown in a plan or diff. */
  def secretFile(
      path: String,
      content: Secret,
      mode: String = "0600",
      owner: String = "",
      group: String = ""
  ): ApplyResource =
    File(
      path,
      Some(Content.Hidden(content)),
      mode,
      owner,
      group,
      present = true
    )

  /** A file whose content is this text, rendered with the node's facts and vars
    * first (see `Content.Template`).
    */
  def fileTemplate(
      path: String,
      template: String,
      vars: Map[String, String] = Map.empty,
      mode: String = "",
      owner: String = "",
      group: String = ""
  ): ApplyResource =
    File(
      path,
      Some(Content.Template(template, vars)),
      mode,
      owner,
      group,
      present = true
    )

  /** A file whose content is a local file (`.mustache`: rendered). */
  def fileFrom(
      path: String,
      source: String,
      vars: Map[String, String] = Map.empty,
      mode: String = "",
      owner: String = "",
      group: String = ""
  ): ApplyResource =
    File(
      path,
      Some(Content.Source(source, vars)),
      mode,
      owner,
      group,
      present = true
    )

  /** A file whose content is another file on the same node: a certificate
    * copied into a service's config, a key, a kubeconfig. The agent copies the
    * bytes (nothing is sent here, so a secret stays on the node); it is
    * unchanged when the two have the same hash, and fails if `from` is not
    * there, so make the step that creates `from` come first (`after`).
    */
  def fileCopy(
      path: String,
      from: String,
      mode: String = "",
      owner: String = "",
      group: String = ""
  ): ApplyResource =
    File(path, Some(Content.OnNode(from)), mode, owner, group, present = true)

  def absentFile(path: String): ApplyResource =
    File(path, None, "", "", "", present = false)

  def directory(
      path: String,
      mode: String = "",
      owner: String = "",
      group: String = ""
  ): ApplyResource =
    Directory(path, mode, owner, group, present = true)

  /** Removed if empty; a directory with something in it fails. */
  /** With `recursive`, the directory goes with everything in it (never across a
    * link or a mount; the agent checks). The path must be absolute and clean
    * and at least two components deep, so `/srv` or `/` cannot be written by
    * mistake.
    */
  def absentDirectory(path: String, recursive: Boolean = false): ApplyResource =
    if recursive then
      require(
        path.startsWith("/") && path.split('/').count(_.nonEmpty) >= 2 &&
          !path.endsWith("/") && !path.contains("//") &&
          !path.split('/').exists(c => c == "." || c == ".."),
        s"absentDirectory(recursive = true) needs an absolute, clean path at least two levels deep, not '$path'"
      )
    Directory(path, "", "", "", present = false, recursive = recursive)

  def symlink(path: String, target: String): ApplyResource =
    Symlink(path, target, present = true)

  def absentSymlink(path: String): ApplyResource =
    Symlink(path, "", present = false)

  /** A package installed (`package` is a word Scala keeps for itself). */
  def pkg(
      name: String,
      version: String = "",
      updateCache: Boolean = false
  ): ApplyResource =
    Package(name, version, updateCache, present = true)

  def absentPkg(name: String): ApplyResource =
    Package(name, "", updateCache = false, present = false)

  /** A systemd unit: by default enabled and running; None leaves either as it
    * is.
    */
  def service(
      unit: String,
      enabled: Option[Boolean] = Some(true),
      running: Option[Boolean] = Some(true)
  ): ApplyResource =
    Service(unit, enabled, running)

  def user(
      name: String,
      uid: Int = -1,
      group: String = "",
      groups: List[String] = Nil,
      home: String = "",
      shell: String = "",
      system: Boolean = false
  ): ApplyResource =
    User(name, uid, group, groups, home, shell, system, present = true)

  def absentUser(name: String): ApplyResource =
    User(name, -1, "", Nil, "", "", system = false, present = false)

  def group(
      name: String,
      gid: Int = -1,
      system: Boolean = false
  ): ApplyResource =
    Group(name, gid, system, present = true)

  def absentGroup(name: String): ApplyResource =
    Group(name, -1, system = false, present = false)

  /** A kernel parameter, set now and (`persist`) at boot. */
  def sysctl(
      key: String,
      value: String,
      persist: Boolean = true
  ): ApplyResource =
    Sysctl(key, value, persist)

  /** A kernel module, loaded now and (`persist`) at boot. */
  def kernelModule(name: String, persist: Boolean = true): ApplyResource =
    KernelModule(name, persist)

  /** `line` is in the file; `matching` (a regular expression) names the line it
    * replaces.
    */
  def lineInFile(
      path: String,
      line: String,
      matching: String = ""
  ): ApplyResource =
    LineInFile(path, line, matching, present = true)

  /** Lines equal to `line`, or matching `matching`, are not in the file. */
  def absentLine(
      path: String,
      line: String,
      matching: String = ""
  ): ApplyResource =
    LineInFile(path, line, matching, present = false)

  def authorizedKey(user: String, key: String): ApplyResource =
    AuthorizedKey(user, key, present = true)

  def absentAuthorizedKey(user: String, key: String): ApplyResource =
    AuthorizedKey(user, key, present = false)

  /** An fstab entry, mounted by default; `mounted = None` leaves that alone. */
  def mount(
      path: String,
      device: String,
      fstype: String,
      options: String = "defaults",
      mounted: Option[Boolean] = Some(true)
  ): ApplyResource =
    Mount(path, device, fstype, options, mounted, present = true)

  def absentMount(path: String): ApplyResource =
    Mount(path, "", "", "", None, present = false)

  def command(
      label: String,
      run: String,
      onlyIf: String = "",
      unless: String = "",
      creates: String = "",
      onChangeOnly: Boolean = false,
      timeout: Int = 300
  ): ApplyResource =
    Command(label, run, onlyIf, unless, creates, onChangeOnly, timeout)

  private def rabbitName(what: String, text: String): Unit =
    require(
      text.nonEmpty && text.forall(c =>
        c.isLetterOrDigit || c == '_' || c == '-' || c == '.'
      ),
      s"$what must be letters, digits, underscores, hyphens and dots, not '$text'"
    )

  private val RabbitUp =
    "podman ps -q --filter 'name=^rabbitmq$' 2>/dev/null | grep -q ."

  /** A vhost and its user on a RabbitMQ broker that runs as the container
    * `rabbitmq` on the node, as a description. The user has the password and
    * full permissions on the vhost, so a rotated password is applied by running
    * it again. It is `unchanged` while the vhost exists, the user can
    * authenticate with the password and has full permissions on it. With the
    * vhost `/` (always there) only the user is made. The broker is not touched
    * otherwise; with no running `rabbitmq` container the step fails and says
    * so.
    */
  def rabbitAccount(
      label: String,
      vhost: String,
      user: String,
      password: Secret
  ): ApplyResource =
    rabbitName("a vhost", if vhost == "/" then "default" else vhost)
    rabbitName("a user", user)
    val vh = Apply.quote(vhost)
    val u = Apply.quote(user)
    val pw = Apply.quote(password.reveal)
    val vhostCheck =
      s"podman exec rabbitmq rabbitmqctl list_vhosts -q | grep -qx $vh"
    val makeVhost =
      if vhost == "/" then ""
      else s"""if ! $vhostCheck; then
           |  podman exec rabbitmq rabbitmqctl add_vhost $vh
           |fi
           |""".stripMargin
    val run =
      s"""set -e
         |if ! podman ps -q --filter 'name=^rabbitmq$$' | grep -q .; then
         |  echo "no running 'rabbitmq' container on this node - apply rabbitmq_single_node.scala first" >&2
         |  exit 1
         |fi
         |$makeVhost""".stripMargin +
        s"""if podman exec rabbitmq rabbitmqctl list_users -q | awk '{print $$1}' | grep -qx $u; then
           |  podman exec rabbitmq rabbitmqctl change_password $u $pw
           |else
           |  podman exec rabbitmq rabbitmqctl add_user $u $pw
           |fi
           |podman exec rabbitmq rabbitmqctl set_permissions -p $vh $u '.*' '.*' '.*'""".stripMargin
    val canAuthenticate =
      s"podman exec rabbitmq rabbitmqctl authenticate_user $u $pw >/dev/null 2>&1"
    val hasFullPermissions =
      s"""podman exec rabbitmq rabbitmqctl list_permissions -p $vh -q | awk -F'\t' '$$1=="$user" && $$2==".*" && $$3==".*" && $$4==".*" {found=1} END {exit !found}'"""
    val inPlace =
      (if vhost == "/" then Nil else List(vhostCheck)) ::: List(
        canAuthenticate,
        hasFullPermissions
      )
    command(label, run, unless = inPlace.mkString(" && "), timeout = 120)

  /** The vhost and the user gone from the broker, as a description: removed
    * when either exists in a running broker. With no running broker there is
    * nothing to remove.
    */
  def absentRabbitAccount(
      label: String,
      vhost: String,
      user: String
  ): ApplyResource =
    require(
      vhost != "/",
      "absentRabbitAccount does not remove the default vhost"
    )
    rabbitName("a vhost", vhost)
    rabbitName("a user", user)
    val vh = Apply.quote(vhost)
    val u = Apply.quote(user)
    val exists =
      s"""$RabbitUp && { podman exec rabbitmq rabbitmqctl list_vhosts -q | grep -qx $vh || podman exec rabbitmq rabbitmqctl list_users -q | awk '{print $$1}' | grep -qx $u; }"""
    command(
      label,
      s"""podman exec rabbitmq rabbitmqctl delete_vhost $vh || true
         |podman exec rabbitmq rabbitmqctl delete_user $u || true""".stripMargin,
      onlyIf = exists,
      timeout = 60
    )

  /** The CA behind a TLS server, as a description: the last certificate the
    * server presents (trust on first use), which must be self-signed and must
    * verify the server's own certificate. It is written to `caFile` (its
    * directory is made) and, unless `trust` is false, added to the system trust
    * store (a file in `anchorsDeb` where `update-ca-certificates` exists,
    * otherwise in `anchorsRpm`, followed by the matching refresh). It is
    * `unchanged` while `caFile` equals the CA the server presents now (and the
    * trust store holds it). The server must present at least the leaf and the
    * CA; `hint` is appended to the message when it does not.
    */
  def serverCa(
      label: String,
      host: String,
      port: Int,
      caFile: String,
      trust: Boolean = true,
      hint: String = "",
      anchorsDeb: String = "/usr/local/share/ca-certificates",
      anchorsRpm: String = "/etc/pki/ca-trust/source/anchors"
  ): ApplyResource =
    require(
      host.nonEmpty && host.forall(c =>
        c.isLetterOrDigit || c == '.' || c == '-'
      ),
      s"not a host name: '$host'"
    )
    require(port >= 1 && port <= 65535, s"not a port: $port")
    require(
      caFile.startsWith("/") && !caFile.endsWith("/"),
      s"caFile must be an absolute file path: '$caFile'"
    )
    require(
      !hint.exists(c => c == '"' || c == '$' || c == '`' || c == '\\'),
      "hint must not hold \" $ ` or \\"
    )
    val cf = Apply.quote(caFile)
    val parent = caFile.substring(0, caFile.lastIndexOf('/'))
    val dir = Apply.quote(if parent.isEmpty then "/" else parent)
    val chainTo =
      s"""chainTo() { openssl s_client -connect $host:$port -servername $host -showcerts </dev/null 2>/dev/null | awk -v d="$$1" '/-----BEGIN CERTIFICATE-----/{n++; p=1} p{print > (d "/c" n ".pem")} /-----END CERTIFICATE-----/{p=0}'; }"""
    val trustTarget =
      s"""if command -v update-ca-certificates >/dev/null 2>&1; then t=${Apply
          .quote(s"$anchorsDeb/orphera-test-ca.crt")}; else t=${Apply.quote(
          s"$anchorsRpm/orphera-test-ca.crt"
        )}; fi"""
    val current =
      if trust then s"""  $trustTarget
         |  cmp -s "$$D/c$$N.pem" $cf && cmp -s $cf "$$t" && R=0""".stripMargin
      else s"""  cmp -s "$$D/c$$N.pem" $cf && R=0"""
    val unless =
      s"""$chainTo
         |D=$$(mktemp -d)
         |chainTo "$$D"
         |N=$$(ls "$$D"/c*.pem 2>/dev/null | wc -l)
         |R=1
         |if [ "$$N" -ge 2 ]; then
         |$current
         |fi
         |rm -rf "$$D"
         |exit $$R""".stripMargin
    val more = if hint.isEmpty then "" else s" - $hint"
    val install =
      if trust then
        s"""$trustTarget
           |cp $cf "$$t"
           |if command -v update-ca-certificates >/dev/null 2>&1; then update-ca-certificates; else update-ca-trust; fi
           |""".stripMargin
      else ""
    val run =
      s"""set -e
         |$chainTo
         |mkdir -p $dir
         |D=$$(mktemp -d)
         |trap 'rm -rf "$$D"' EXIT
         |chainTo "$$D"
         |COUNT=$$(ls "$$D"/c*.pem 2>/dev/null | wc -l)
         |if [ "$$COUNT" -lt 2 ]; then
         |  echo "$host:$port presented $$COUNT certificate(s); expected leaf + CA$more" >&2
         |  exit 1
         |fi
         |cp "$$D/c$$COUNT.pem" $cf
         |openssl verify -CAfile $cf "$$D/c1.pem"
         |SUBJ=$$(openssl x509 -in $cf -noout -subject | sed 's/^subject=//')
         |ISS=$$(openssl x509 -in $cf -noout -issuer | sed 's/^issuer=//')
         |if [ "$$SUBJ" != "$$ISS" ]; then echo "last certificate is not self-signed: $$SUBJ / $$ISS" >&2; exit 1; fi
         |${install}echo "CA of $host:$port installed: $$SUBJ"""".stripMargin
    command(label, run, unless = unless, timeout = 60)

  /** An apt repository with its signing key, as a description: the key fetched
    * from `keyUrl` and dearmored into `keyring`, and `sourcesFile` holding the
    * one line `deb [signed-by=<keyring>] <repository>` (`repository` is what
    * follows, such as `https://apt.example.com stable main`). It is `unchanged`
    * while the keyring is there and the sources file holds exactly that line
    * (and, when `offers` names a package, apt offers that package from the
    * repository's host). With `update` the run also refreshes the package lists
    * of this source alone (with up to three tries), so a package installed
    * right after sees it. The node needs `curl` and `gpg`.
    */
  def aptRepository(
      label: String,
      keyUrl: String,
      keyring: String,
      sourcesFile: String,
      repository: String,
      offers: String = "",
      update: Boolean = false,
      timeout: Int = 720
  ): ApplyResource =
    def absolute(what: String, path: String): Unit =
      require(
        path.startsWith("/") && !path.endsWith("/") && path.forall(c =>
          c.isLetterOrDigit || "/._-".contains(c)
        ),
        s"$what must be an absolute file path of letters, digits and / . _ -: '$path'"
      )
    absolute("keyring", keyring)
    absolute("sourcesFile", sourcesFile)
    require(
      keyUrl.startsWith("https://") && !keyUrl.exists(c =>
        c.isWhitespace || c == '\''
      ),
      s"keyUrl must be an https URL: '$keyUrl'"
    )
    require(
      repository.startsWith("https://") && repository.forall(c =>
        c.isLetterOrDigit || ":/._+~- ".contains(c)
      ),
      s"repository must start with https:// and hold letters, digits and : / . _ + ~ - and spaces: '$repository'"
    )
    require(
      offers.forall(c => c.isLetterOrDigit || "._+-".contains(c)),
      s"not a package name: '$offers'"
    )
    val line = s"deb [signed-by=$keyring] $repository"
    val host =
      repository.stripPrefix("https://").takeWhile(c => c != '/' && c != ' ')
    val offered = if offers.isEmpty then ""
    else
      s" && apt-cache policy ${Apply.quote(offers)} 2>/dev/null | grep -qF ${Apply.quote(host)}"
    val unless =
      s"""[ -s ${Apply.quote(keyring)} ] && [ "$$(cat ${Apply.quote(
          sourcesFile
        )} 2>/dev/null)" = ${Apply.quote(line)} ]$offered"""
    def parent(path: String): String =
      Apply.quote(if path.lastIndexOf('/') == 0 then "/"
      else path.substring(0, path.lastIndexOf('/')))
    val refresh =
      if update then
        s"""for try in 1 2 3; do timeout -k 10 180 apt-get update -o Dir::Etc::sourcelist=${Apply
            .quote(
              sourcesFile
            )} -o Dir::Etc::sourceparts=- -o APT::Get::List-Cleanup=0 && break; done
           |""".stripMargin
      else ""
    val check =
      if offers.isEmpty then ""
      else
        s"""apt-cache policy ${Apply.quote(offers)} | grep -qF ${Apply.quote(
            host
          )} || { echo "$offers is not offered by $host" >&2; exit 1; }
              |""".stripMargin
    val run =
      s"""set -e
         |install -d -m 755 ${parent(keyring)} ${parent(sourcesFile)}
         |curl -fsSL --retry 3 ${Apply.quote(
          keyUrl
        )} | gpg --dearmor --yes -o ${Apply.quote(keyring)}
         |[ -s ${Apply.quote(
          keyring
        )} ] || { echo "no key came from $keyUrl" >&2; exit 1; }
         |chmod 644 ${Apply.quote(keyring)}
         |printf '%s\\n' ${Apply.quote(line)} > ${Apply.quote(sourcesFile)}
         |$refresh$check""".stripMargin + s"""echo "apt repository $host configured""""
    command(label, run, unless = unless, timeout = timeout)

  private def absoluteClean(what: String, path: String): Unit =
    require(
      path.startsWith("/") && !path.endsWith("/") && !path.contains(
        "//"
      ) && !path.split('/').contains("..") &&
        path.forall(c => c.isLetterOrDigit || "/._+-".contains(c)),
      s"$what must be a clean absolute file path of letters, digits and / . _ + -: '$path'"
    )

  private def plainUrl(url: String): Unit =
    require(
      (url.startsWith("https://") || url.startsWith("http://")) && !url.exists(
        c => c.isWhitespace || "'\"`$\\".contains(c)
      ),
      s"not a plain http(s) URL: '$url'"
    )

  /** A file downloaded to `path`, as a description. It is fetched to
    * `path.part` (with a line printed every 20 seconds, so that a long download
    * does not lose its connection to the agent), checked, and only then moved
    * into place; a failed download leaves nothing at `path`. `magic` is what
    * the file must start with (such as `QFI` for qcow2) and `sha256` its
    * checksum, each checked when given. It is `unchanged` while `path` is not
    * empty and passes the checks, or while `skipIf` (a shell test, such as "the
    * image is already in Glance") succeeds. A file that fails the checks is
    * fetched again.
    */
  def download(
      label: String,
      url: String,
      path: String,
      magic: String = "",
      sha256: String = "",
      skipIf: String = "",
      timeout: Int = 1800
  ): ApplyResource =
    plainUrl(url)
    absoluteClean("path", path)
    require(
      magic.length <= 8 && magic.forall(c => c.isLetterOrDigit),
      s"magic must be up to 8 letters or digits: '$magic'"
    )
    require(
      sha256.isEmpty || (sha256.length == 64 && sha256
        .forall(c => "0123456789abcdef".contains(c))),
      "sha256 must be 64 lower-case hex digits"
    )
    def ok(file: String): String =
      val f = Apply.quote(file)
      val tests =
        List(s"[ -s $f ]") :::
          (if magic.isEmpty then Nil
           else
             List(s"""[ "$$(head -c ${magic.length} $f 2>/dev/null)" = ${Apply
                 .quote(magic)} ]""")
          ) :::
          (if sha256.isEmpty then Nil
           else
             List(
               s"""[ "$$(sha256sum $f 2>/dev/null | cut -d' ' -f1)" = "$sha256" ]"""
             ))
      tests.mkString(" && ")
    val unless =
      if skipIf.isEmpty then ok(path) else s"$skipIf || { ${ok(path)}; }"
    val dir = Apply.quote(if path.lastIndexOf('/') == 0 then "/"
    else path.substring(0, path.lastIndexOf('/')))
    val run =
      s"""set -e
         |heartbeat() { "$$@" & P=$$!; N=0; while kill -0 $$P 2>/dev/null; do sleep 1; N=$$((N+1)); if [ $$((N % 20)) -eq 0 ]; then echo "still working ($$(date +%H:%M:%S))"; fi; done; wait $$P; }
         |mkdir -p $dir
         |rm -f ${Apply.quote(path + ".part")}
         |if ! heartbeat curl -fsSL --retry 3 --connect-timeout 30 -o ${Apply
          .quote(path + ".part")} ${Apply.quote(url)}; then
         |  rm -f ${Apply.quote(path + ".part")}
         |  echo "cannot download $url - put the file at $path and run again" >&2
         |  exit 1
         |fi
         |if ! { ${ok(path + ".part")}; }; then
         |  rm -f ${Apply.quote(path + ".part")}
         |  echo "the file from $url is not what was expected (magic '$magic', sha256 '$sha256')" >&2
         |  exit 1
         |fi
         |mv ${Apply.quote(path + ".part")} ${Apply.quote(path)}
         |ls -l ${Apply.quote(path)}""".stripMargin
    command(label, run, unless = unless, timeout = timeout)

  /** Files taken out of a `.tar.gz` archive from `url` and installed with
    * `mode`, as a description: each pair is the path inside the archive and the
    * file to make (its directory is made). It is `unchanged` while every target
    * exists (and is executable when `mode` makes it so for its owner). The node
    * needs `curl` and `tar`.
    */
  def archiveFiles(
      label: String,
      url: String,
      files: List[(String, String)],
      mode: String = "0755",
      timeout: Int = 300
  ): ApplyResource =
    plainUrl(url)
    require(files.nonEmpty, "archiveFiles needs at least one file")
    require(
      mode.length == 4 && mode.forall(c => c >= '0' && c <= '7'),
      s"mode must be four octal digits: '$mode'"
    )
    files.foreach { (inside, target) =>
      require(
        inside.nonEmpty && !inside
          .startsWith("/") && !inside.split('/').contains("..") &&
          inside.forall(c => c.isLetterOrDigit || "/._+-".contains(c)),
        s"not a path inside an archive: '$inside'"
      )
      absoluteClean("target", target)
    }
    val executable = ((mode(1) - '0') & 1) == 1
    val unless = files
      .map((_, target) =>
        s"[ ${if executable then "-x" else "-f"} ${Apply.quote(target)} ]"
      )
      .mkString(" && ")
    val installs = files
      .map { (inside, target) =>
        s"""[ -f "$$T/$inside" ] || { echo "${inside} is not in the archive from $url" >&2; exit 1; }
         |install -m $mode -D "$$T/$inside" ${Apply.quote(
            target
          )}""".stripMargin
      }
      .mkString("\n")
    val run =
      s"""set -e
         |T=$$(mktemp -d)
         |trap 'rm -rf "$$T"' EXIT
         |curl -fsSL --retry 3 --connect-timeout 30 -o "$$T/archive.tar.gz" ${Apply
          .quote(url)}
         |tar xzf "$$T/archive.tar.gz" -C "$$T"
         |$installs
         |echo "installed ${files.map(_._2).mkString(", ")}"""".stripMargin
    command(label, run, unless = unless, timeout = timeout)

  /** One Keystone service of a registration: its name, its type and the URL of
    * its public, internal and admin endpoints. `adminUrl` is the URL of the
    * admin endpoint when that differs from `url` (Swift's admin endpoint has no
    * project in it).
    */
  final case class KeystoneService(
      name: String,
      serviceType: String,
      url: String,
      adminUrl: Option[String] = None
  )

  /** A Keystone domain a service owns (Heat's stack domain, Magnum's trustee
    * domain): its `name` and `description`, and its admin user `adminUser`, who
    * has `adminPassword` and the admin role on the domain.
    */
  final case class KeystoneDomain(
      name: String,
      description: String,
      adminUser: String,
      adminPassword: Secret
  )

  private val keystoneHelperPython: String =
    """#!/usr/bin/python3
# Makes, checks and removes the Keystone entries one service needs (written by Orphera).
#   ensure | check | exists | remove
import ssl, sys, urllib.request, urllib.error
import json

KS = @@KS@@
ADMIN_PW = @@ADMINPW@@
SPEC = @@SPEC@@
CTX = ssl.create_default_context(cafile=@@CA@@) if KS.startswith('https') else None
IFACES = ('public', 'internal', 'admin')


def url_for(svc, iface):
    return svc.get('admin_url', svc['url']) if iface == 'admin' else svc['url']


def call(method, url, body=None, token=None):
    data = json.dumps(body).encode() if body is not None else None
    req = urllib.request.Request(url, data=data, method=method)
    req.add_header('Content-Type', 'application/json')
    if token:
        req.add_header('X-Auth-Token', token)
    try:
        r = urllib.request.urlopen(req, context=CTX, timeout=60)
        status, text, headers = r.status, r.read().decode(), r.headers
    except urllib.error.HTTPError as e:
        status, text, headers = e.code, e.read().decode(), e.headers
    try:
        parsed = json.loads(text) if text else {}
    except ValueError:
        parsed = {}
    return status, parsed, headers


def die(msg):
    sys.stderr.write(msg + '\n')
    sys.exit(1)


def get_token(user, password, project, domain='Default'):
    body = {'auth': {'identity': {'methods': ['password'], 'password': {'user': {'name': user, 'domain': {'name': 'Default'}, 'password': password}}}, 'scope': {'project': {'name': project, 'domain': {'name': domain}}}}}
    status, parsed, headers = call('POST', KS + '/auth/tokens', body)
    if status != 201:
        die('token request for %s failed: HTTP %s %s' % (user, status, parsed))
    return headers['X-Subject-Token']


def find(token, path, key):
    status, parsed, _ = call('GET', KS + path, token=token)
    items = parsed.get(key, [])
    return items[0] if items else None


def ensure_one(token, what, path, key, body, create_key):
    found = find(token, path, key)
    if found is not None:
        return found
    s, p, _ = call('POST', KS + '/' + what, body, token)
    if s != 201:
        die('creating %s failed: HTTP %s %s' % (what, s, p))
    return p[create_key]


def ensure(spec):
    token = get_token('admin', ADMIN_PW, 'admin')
    project = ensure_one(token, 'projects', '/projects?name=' + spec['project'], 'projects', {'project': {'name': spec['project'], 'domain_id': 'default'}}, 'project')
    user = find(token, '/users?name=' + spec['name'], 'users')
    if user is None:
        s, p, _ = call('POST', KS + '/users', {'user': {'name': spec['name'], 'domain_id': 'default', 'password': spec['password']}}, token)
        if s != 201:
            die('creating user %s failed: HTTP %s %s' % (spec['name'], s, p))
        user = p['user']
    else:
        call('PATCH', KS + '/users/' + user['id'], {'user': {'password': spec['password']}}, token)
    admin_role = find(token, '/roles?name=admin', 'roles')
    if admin_role is None:
        die('role admin not found')
    s, p, _ = call('PUT', KS + '/projects/%s/users/%s/roles/%s' % (project['id'], user['id'], admin_role['id']), None, token)
    if s not in (200, 204):
        die('granting admin to %s failed: HTTP %s %s' % (spec['name'], s, p))
    for role in spec.get('roles', []):
        ensure_one(token, 'roles', '/roles?name=' + role, 'roles', {'role': {'name': role}}, 'role')
    dom = spec.get('domain')
    if dom:
        domain = ensure_one(token, 'domains', '/domains?name=' + dom['name'], 'domains', {'domain': {'name': dom['name'], 'description': dom['description']}}, 'domain')
        dadmin = find(token, '/users?name=%s&domain_id=%s' % (dom['admin'], domain['id']), 'users')
        if dadmin is None:
            s, p, _ = call('POST', KS + '/users', {'user': {'name': dom['admin'], 'domain_id': domain['id'], 'password': dom['password']}}, token)
            if s != 201:
                die('creating user %s failed: HTTP %s %s' % (dom['admin'], s, p))
            dadmin = p['user']
        else:
            call('PATCH', KS + '/users/' + dadmin['id'], {'user': {'password': dom['password']}}, token)
        s, p, _ = call('PUT', KS + '/domains/%s/users/%s/roles/%s' % (domain['id'], dadmin['id'], admin_role['id']), None, token)
        if s not in (200, 204):
            die('granting admin on domain %s to %s failed: HTTP %s %s' % (dom['name'], dom['admin'], s, p))
    for svc in spec['services']:
        service = ensure_one(token, 'services', '/services?type=' + svc['type'], 'services', {'service': {'name': svc['name'], 'type': svc['type']}}, 'service')
        for iface in IFACES:
            ep = find(token, '/endpoints?service_id=%s&interface=%s' % (service['id'], iface), 'endpoints')
            want = url_for(svc, iface)
            if ep is None:
                s, p, _ = call('POST', KS + '/endpoints', {'endpoint': {'interface': iface, 'url': want, 'service_id': service['id'], 'region_id': 'RegionOne'}}, token)
                if s != 201:
                    die('creating %s endpoint of %s failed: HTTP %s %s' % (iface, svc['type'], s, p))
            elif ep['url'] != want:
                call('PATCH', KS + '/endpoints/' + ep['id'], {'endpoint': {'url': want}}, token)
    print('registered %s in keystone (project %s, user %s, domain %s, services %s)' % (spec['name'], spec['project'], spec['name'], dom['name'] if dom else 'none', ' '.join(s['type'] for s in spec['services']) or 'none'))


def check(spec):
    # Exit 0 only when everything ensure() makes is in place. Makes no change.
    get_token(spec['name'], spec['password'], spec['project'])
    token = get_token('admin', ADMIN_PW, 'admin')
    for role in spec.get('roles', []):
        if find(token, '/roles?name=' + role, 'roles') is None:
            die('role %s missing' % role)
    dom = spec.get('domain')
    if dom:
        domain = find(token, '/domains?name=' + dom['name'], 'domains')
        if domain is None:
            die('no domain ' + dom['name'])
        dadmin = find(token, '/users?name=%s&domain_id=%s' % (dom['admin'], domain['id']), 'users')
        if dadmin is None:
            die('no user ' + dom['admin'])
        body = {'auth': {'identity': {'methods': ['password'], 'password': {'user': {'name': dom['admin'], 'domain': {'name': dom['name']}, 'password': dom['password']}}}}}
        s, p, _ = call('POST', KS + '/auth/tokens', body)
        if s != 201:
            die('%s cannot get a token: HTTP %s %s' % (dom['admin'], s, p))
        admin_role = find(token, '/roles?name=admin', 'roles')
        s, p, _ = call('HEAD', KS + '/domains/%s/users/%s/roles/%s' % (domain['id'], dadmin['id'], admin_role['id']), None, token)
        if s != 204:
            die('%s does not hold admin on domain %s: HTTP %s' % (dom['admin'], dom['name'], s))
    for svc in spec['services']:
        service = find(token, '/services?type=' + svc['type'], 'services')
        if service is None:
            die('no service of type %s' % svc['type'])
        for iface in IFACES:
            ep = find(token, '/endpoints?service_id=%s&interface=%s' % (service['id'], iface), 'endpoints')
            if ep is None or ep['url'] != url_for(svc, iface):
                die('endpoint %s of %s missing or wrong' % (iface, svc['type']))
    print('%s is registered in keystone' % spec['name'])


def exists(spec):
    # Exit 0 when anything ensure() makes is still there: the user, the domain, or a service of one of the types.
    token = get_token('admin', ADMIN_PW, 'admin')
    if find(token, '/users?name=' + spec['name'], 'users') is not None:
        return
    if spec.get('domain') and find(token, '/domains?name=' + spec['domain']['name'], 'domains') is not None:
        return
    for svc in spec['services']:
        if find(token, '/services?type=' + svc['type'], 'services') is not None:
            return
    die('nothing of %s is in keystone' % spec['name'])


def remove(spec):
    token = get_token('admin', ADMIN_PW, 'admin')
    for svc in spec['services']:
        service = find(token, '/services?type=' + svc['type'], 'services')
        if service is not None:
            call('DELETE', KS + '/services/' + service['id'], None, token)
    user = find(token, '/users?name=' + spec['name'], 'users')
    if user is not None:
        call('DELETE', KS + '/users/' + user['id'], None, token)
    dom = spec.get('domain')
    domain = find(token, '/domains?name=' + dom['name'], 'domains') if dom else None
    if domain is not None:
        # The service makes users and projects in its domain: remove them all, disable the domain, then delete it.
        s, p, _ = call('GET', KS + '/users?domain_id=' + domain['id'], None, token)
        for u in p.get('users', []):
            call('DELETE', KS + '/users/' + u['id'], None, token)
        s, p, _ = call('GET', KS + '/projects?domain_id=' + domain['id'], None, token)
        for pr in p.get('projects', []):
            call('DELETE', KS + '/projects/' + pr['id'], None, token)
        call('PATCH', KS + '/domains/' + domain['id'], {'domain': {'enabled': False}}, token)
        call('DELETE', KS + '/domains/' + domain['id'], None, token)
    print('removed %s from keystone (services %s, their endpoints, user %s, domain %s)' % (spec['name'], ' '.join(s['type'] for s in spec['services']) or 'none', spec['name'], dom['name'] if dom else 'none'))


if __name__ == '__main__':
    if len(sys.argv) != 2 or sys.argv[1] not in ('ensure', 'check', 'exists', 'remove'):
        die('usage: ensure | check | exists | remove')
    {'ensure': ensure, 'check': check, 'exists': exists, 'remove': remove}[sys.argv[1]](SPEC)
"""

  /** A string as a Python (and JSON) literal; printable ASCII only. */
  private def pyString(what: String, text: String): String =
    require(
      text.forall(c => c >= ' ' && c <= '~'),
      s"$what must be printable ASCII"
    )
    "\"" + text.flatMap {
      case '"'  => "\\\""
      case '\\' => "\\\\"
      case c    => c.toString
    } + "\""

  private def keystoneName(what: String, text: String): Unit =
    require(
      text.nonEmpty && text.forall(c => c.isLetterOrDigit || "_-.".contains(c)),
      s"$what must be letters, digits and _ - . , not '$text'"
    )

  /** The path of the helper `keystoneEntries` writes for the entries of `name`.
    */
  def keystoneHelperPath(name: String): String =
    s"/usr/local/sbin/orphera-keystone-$name"

  /** The Keystone entries a service needs, as a description: a user `name` with
    * `password` and the admin role in `project`, the extra `roles` made if
    * missing, and each service of `services` (found by its type) with its
    * public, internal and admin endpoints at the given URL (`services` may be
    * empty: a user only; `domain` adds a domain with its admin, who holds the
    * admin role on it). The result is the helper (a root-only script that holds
    * the admin password, `keystone` being the base URL such as
    * `https://tst6.example.com:5000`, and `ca` the CA file that verifies it)
    * and the command that runs it. The command is `unchanged` while the user
    * can get a token in the project, the roles exist, the domain admin can get
    * a token and holds admin on the domain, and every service has its three
    * endpoints at the URL, so a changed password, role or URL is put right by
    * running it again.
    */
  def keystoneEntries(
      label: String,
      name: String,
      password: Secret,
      services: List[KeystoneService],
      keystone: String,
      adminPassword: Secret,
      ca: String,
      roles: List[String] = Nil,
      project: String = "service",
      domain: Option[KeystoneDomain] = None,
      timeout: Int = 120
  ): (ApplyResource, ApplyResource) =
    keystoneName("name", name)
    domain.foreach { d =>
      keystoneName("the domain name", d.name)
      keystoneName("the domain admin", d.adminUser)
    }
    keystoneName("project", project)
    roles.foreach(keystoneName("a role", _))
    services.foreach { sv =>
      keystoneName("a service name", sv.name)
      keystoneName("a service type", sv.serviceType)
      (sv.url :: sv.adminUrl.toList).foreach(u =>
        require(
          u.startsWith("http://") || u.startsWith("https://"),
          s"not an endpoint URL: '$u'"
        )
      )
    }
    require(
      keystone.startsWith("https://") || keystone.startsWith("http://"),
      s"not a Keystone URL: '$keystone'"
    )
    def field(key: String, value: String): String =
      pyString(key, key) + ": " + value
    val serviceSpecs = services.map { sv =>
      "{" + (List(
        field("name", pyString("a service name", sv.name)),
        field("type", pyString("a service type", sv.serviceType)),
        field("url", pyString("a URL", sv.url))
      ) ::: sv.adminUrl.toList.map(u =>
        field("admin_url", pyString("a URL", u))
      )).mkString(", ") + "}"
    }
    val domainSpec = domain.fold("") { d =>
      ", " + field(
        "domain",
        "{" + List(
          field("name", pyString("the domain name", d.name)),
          field(
            "description",
            pyString("the domain description", d.description)
          ),
          field("admin", pyString("the domain admin", d.adminUser)),
          field(
            "password",
            pyString("the domain admin password", d.adminPassword.reveal)
          )
        ).mkString(", ") + "}"
      )
    }
    val spec = "{" + List(
      field("name", pyString("name", name)),
      field("password", pyString("password", password.reveal)),
      field("project", pyString("project", project)),
      field("roles", roles.map(pyString("a role", _)).mkString("[", ", ", "]")),
      field("services", serviceSpecs.mkString("[", ", ", "]"))
    ).mkString(", ") + domainSpec + "}"
    val script = keystoneHelperPython
      .replace(
        "@@KS@@",
        pyString("keystone", keystone.stripSuffix("/") + "/v3")
      )
      .replace(
        "@@ADMINPW@@",
        pyString("the admin password", adminPassword.reveal)
      )
      .replace("@@CA@@", pyString("ca", ca))
      .replace("@@SPEC@@", spec)
    val path = keystoneHelperPath(name)
    val helper = secretFile(
      path,
      Secret(script),
      mode = "0700",
      owner = "root",
      group = "root"
    )
    val ensure = command(
      label,
      s"${Apply.quote(path)} ensure",
      unless = s"${Apply.quote(path)} check >/dev/null 2>&1",
      timeout = timeout
    ).after(helper)
    (helper, ensure)

  /** What `keystoneEntries` made, removed again: the services of the
    * registration with their endpoints, the user, and the domain with every
    * user and project in it. It does nothing without the helper on the node or
    * while nothing is there, and a failure is reported but does not stop the
    * run (the node is being torn down). The helper is left for the caller to
    * remove.
    */
  def absentKeystoneEntries(
      label: String,
      name: String,
      timeout: Int = 120
  ): ApplyResource =
    keystoneName("name", name)
    val path = Apply.quote(keystoneHelperPath(name))
    command(
      label,
      s"""$path remove || echo "keystone cleanup failed (continuing)"""",
      onlyIf = s"[ -x $path ] && $path exists >/dev/null 2>&1",
      timeout = timeout
    )

  // The start of the helpers of `openstackImage` and `openstackFlavor`: a token for the admin user and the public
  // endpoints of Glance and Nova from the catalogue.
  private val openstackClientPython: String =
    """#!/usr/bin/python3
import json, os, ssl, sys, time, urllib.request, urllib.error

KS = @@KS@@
ADMIN_PW = @@ADMINPW@@
SPEC = @@SPEC@@
CTX = ssl.create_default_context(cafile=@@CA@@) if KS.startswith('https') else None


def call(method, url, body=None, token=None):
    data = json.dumps(body).encode() if body is not None else None
    req = urllib.request.Request(url, data=data, method=method)
    req.add_header('Content-Type', 'application/json')
    if token:
        req.add_header('X-Auth-Token', token)
    try:
        r = urllib.request.urlopen(req, context=CTX if url.startswith('https') else None, timeout=60)
        status, text, headers = r.status, r.read().decode(), r.headers
    except urllib.error.HTTPError as e:
        status, text, headers = e.code, e.read().decode(), e.headers
    try:
        parsed = json.loads(text) if text else {}
    except ValueError:
        parsed = {}
    return status, parsed, headers


def die(msg):
    sys.stderr.write(msg + '\n')
    sys.exit(1)


def endpoint(parsed, svc_type):
    for c in parsed['token']['catalog']:
        if c['type'] == svc_type:
            for e in c['endpoints']:
                if e['interface'] == 'public':
                    return e['url'].rstrip('/')
    die('no %s service in the catalog' % svc_type)


def versioned(url, suffix):
    return url if url.endswith(suffix) else url + suffix


class Clients(object):
    def __init__(self):
        body = {'auth': {'identity': {'methods': ['password'], 'password': {'user': {'name': 'admin', 'domain': {'name': 'Default'}, 'password': ADMIN_PW}}}, 'scope': {'project': {'name': 'admin', 'domain': {'name': 'Default'}}}}}
        status, parsed, headers = call('POST', KS + '/auth/tokens', body)
        if status != 201:
            die('token request for admin failed: HTTP %s %s' % (status, parsed))
        self.token = headers['X-Subject-Token']
        self.doc = parsed
        self.nova = versioned(endpoint(parsed, 'compute'), '/v2.1')
        self.glance = versioned(endpoint(parsed, 'image'), '/v2')

    def c(self, method, path, body=None):
        return call(method, self.nova + path, body, self.token)

    def g(self, method, path, body=None):
        return call(method, self.glance + path, body, self.token)
"""

  private val openstackImagePython: String = openstackClientPython +
    """

def images(cl):
    # The images of this name that carry every wanted tag.
    s, p, _ = cl.g('GET', '/images?name=' + SPEC['name'])
    if s != 200:
        die('image list failed: HTTP %s %s' % (s, p))
    return [i for i in p.get('images', []) if i.get('name') == SPEC['name'] and all(t in i.get('tags', []) for t in SPEC['tags'])]


def good(i):
    if i.get('status') != 'active' or i.get('visibility') != SPEC['visibility'] or i.get('disk_format') != SPEC['disk_format']:
        return False
    return all(str(i.get(k)) == v for k, v in SPEC['properties'].items())


def ensure(replace):
    cl = Clients()
    existing = images(cl)
    ok = [i for i in existing if good(i)]
    if ok and not replace:
        print('image %s (%s) is already active in Glance' % (SPEC['name'], ok[0]['id']))
        return
    path = SPEC['file']
    if not os.path.isfile(path):
        die('the image file %s does not exist' % path)
    for i in existing:
        cl.g('DELETE', '/images/' + i['id'])
    body = {'name': SPEC['name'], 'disk_format': SPEC['disk_format'], 'container_format': 'bare', 'visibility': SPEC['visibility'], 'tags': SPEC['tags']}
    body.update(SPEC['properties'])
    s, p, _ = cl.g('POST', '/images', body)
    if s != 201:
        die('image create failed: HTTP %s %s' % (s, p))
    iid = p['id']
    size = os.path.getsize(path)
    with open(path, 'rb') as fh:
        req = urllib.request.Request(cl.glance + '/images/%s/file' % iid, data=fh, method='PUT')
        req.add_header('Content-Type', 'application/octet-stream')
        req.add_header('Content-Length', str(size))
        req.add_header('X-Auth-Token', cl.token)
        try:
            urllib.request.urlopen(req, context=CTX if req.full_url.startswith('https') else None, timeout=3600).read()
        except urllib.error.HTTPError as e:
            die('image upload failed: HTTP %s %s' % (e.code, e.read().decode()[:300]))
    end = time.time() + 600
    status = None
    while time.time() < end:
        s, p, _ = cl.g('GET', '/images/' + iid)
        status = p.get('status')
        if status == 'active':
            print('image uploaded: %s (%s), %d bytes' % (SPEC['name'], iid, size))
            return
        if status in ('killed', 'deleted'):
            break
        time.sleep(3)
    die('image %s did not become active (status %s)' % (iid, status))


def check():
    # Exit 0 only when an active image of this name has the tags, the visibility, the format and the properties.
    ok = [i for i in images(Clients()) if good(i)]
    if not ok:
        die('no active Glance image %s with the wanted tags and properties' % SPEC['name'])
    print('image %s (%s) is active' % (SPEC['name'], ok[0]['id']))


def exists():
    # Exit 0 when an image of this name with the tags is there in any state.
    if not images(Clients()):
        die('no image %s' % SPEC['name'])


def remove():
    cl = Clients()
    found = images(cl)
    for i in found:
        cl.g('DELETE', '/images/' + i['id'])
    print('removed %d image(s) %s' % (len(found), SPEC['name']))


if __name__ == '__main__':
    cmd = sys.argv[1] if len(sys.argv) > 1 else ''
    if cmd == 'ensure':
        ensure('--replace' in sys.argv[2:])
    elif cmd == 'check':
        check()
    elif cmd == 'exists':
        exists()
    elif cmd == 'remove':
        remove()
    else:
        die('usage: ensure [--replace] | check | exists | remove')
"""

  private val openstackFlavorPython: String = openstackClientPython +
    """

def current(cl):
    s, p, _ = cl.c('GET', '/flavors/' + SPEC['id'])
    if s == 200:
        return p['flavor']
    if s == 404:
        return None
    die('flavor lookup failed: HTTP %s %s' % (s, p))


def same(f):
    return f.get('name') == SPEC['name'] and f.get('ram') == SPEC['ram'] and f.get('vcpus') == SPEC['vcpus'] and f.get('disk') == SPEC['disk']


def ensure():
    cl = Clients()
    f = current(cl)
    if f is not None and same(f):
        print('flavor %s exists' % SPEC['id'])
        return
    if f is not None:
        # A flavor cannot be changed. Servers keep their own copy, so replacing it is safe.
        cl.c('DELETE', '/flavors/' + SPEC['id'])
    s, p, _ = cl.c('POST', '/flavors', {'flavor': {'id': SPEC['id'], 'name': SPEC['name'], 'ram': SPEC['ram'], 'vcpus': SPEC['vcpus'], 'disk': SPEC['disk'], 'os-flavor-access:is_public': True}})
    if s not in (200, 201):
        die('creating flavor %s failed: HTTP %s %s' % (SPEC['id'], s, p))
    print('flavor %s made (%d vCPU, %d MB, %d GB)' % (SPEC['id'], SPEC['vcpus'], SPEC['ram'], SPEC['disk']))


def check():
    f = current(Clients())
    if f is None:
        die('flavor %s does not exist' % SPEC['id'])
    if not same(f):
        die('flavor %s differs: %s' % (SPEC['id'], f))
    print('flavor %s is as wanted' % SPEC['id'])


def exists():
    if current(Clients()) is None:
        die('no flavor %s' % SPEC['id'])


def remove():
    s, _, _ = Clients().c('DELETE', '/flavors/' + SPEC['id'])
    print('flavor %s: HTTP %s' % (SPEC['id'], s))


if __name__ == '__main__':
    cmd = sys.argv[1] if len(sys.argv) > 1 else ''
    if cmd == 'ensure':
        ensure()
    elif cmd == 'check':
        check()
    elif cmd == 'exists':
        exists()
    elif cmd == 'remove':
        remove()
    else:
        die('usage: ensure | check | exists | remove')
"""

  private val openstackKeypairPython: String = openstackClientPython +
    """

class KClients(Clients):
    # The key pair belongs to the user in SPEC (the admin user unless another one is named).
    def __init__(self):
        body = {'auth': {'identity': {'methods': ['password'], 'password': {'user': {'name': SPEC['user'], 'domain': {'name': 'Default'}, 'password': SPEC['password']}}}, 'scope': {'project': {'name': SPEC['project'], 'domain': {'name': 'Default'}}}}}
        status, parsed, headers = call('POST', KS + '/auth/tokens', body)
        if status != 201:
            die('token request for %s failed: HTTP %s %s' % (SPEC['user'], status, parsed))
        self.token = headers['X-Subject-Token']
        self.doc = parsed
        self.nova = versioned(endpoint(parsed, 'compute'), '/v2.1')
        self.glance = versioned(endpoint(parsed, 'image'), '/v2')


def exists_now(cl):
    s, p, _ = cl.c('GET', '/os-keypairs/' + SPEC['name'])
    if s == 200:
        return True
    if s == 404:
        return False
    die('key pair lookup failed: HTTP %s %s' % (s, p))


def ensure():
    cl = KClients()
    if exists_now(cl):
        print('key pair %s exists' % SPEC['name'])
        return
    # No public key is given, so Nova makes a pair and keeps the public half. The private half is written to
    # SPEC['keyfile'] (root only) when one is named, and is otherwise not kept.
    s, p, _ = cl.c('POST', '/os-keypairs', {'keypair': {'name': SPEC['name']}})
    if s not in (200, 201):
        die('creating key pair %s failed: HTTP %s %s' % (SPEC['name'], s, p))
    if SPEC['keyfile']:
        os.makedirs(os.path.dirname(SPEC['keyfile']), exist_ok=True)
        fd = os.open(SPEC['keyfile'], os.O_WRONLY | os.O_CREAT | os.O_TRUNC, 0o600)
        with os.fdopen(fd, 'w') as f:
            f.write(p['keypair']['private_key'])
    print('key pair %s made' % SPEC['name'])


def check():
    if not exists_now(KClients()):
        die('key pair %s does not exist' % SPEC['name'])
    print('key pair %s exists' % SPEC['name'])


def exists():
    if not exists_now(KClients()):
        die('no key pair %s' % SPEC['name'])


def remove():
    s, _, _ = KClients().c('DELETE', '/os-keypairs/' + SPEC['name'])
    print('key pair %s: HTTP %s' % (SPEC['name'], s))


if __name__ == '__main__':
    cmd = sys.argv[1] if len(sys.argv) > 1 else ''
    if cmd == 'ensure':
        ensure()
    elif cmd == 'check':
        check()
    elif cmd == 'exists':
        exists()
    elif cmd == 'remove':
        remove()
    else:
        die('usage: ensure | check | exists | remove')
"""

  /** The path of the helper `openstackKeypair` writes for the key pair `name`.
    */
  def openstackKeypairHelperPath(name: String, user: String = "admin"): String =
    if user == "admin" then s"/usr/local/sbin/orphera-keypair-$name"
    else s"/usr/local/sbin/orphera-keypair-$name-$user"

  // The helpers of `openstackNetwork` and `openstackSecurityGroup`: the base plus Neutron and the project lookup.
  private val openstackNeutronClientPython: String = openstackClientPython +
    """

class NClients(Clients):
    def n(self, method, path, body=None):
        return call(method, versioned(endpoint(self.doc, 'network'), '/v2.0') + path, body, self.token)

    def project_id(self, name):
        s, p, _ = call('GET', KS + '/projects?name=' + name, None, self.token)
        if s != 200 or not p.get('projects'):
            die('project %s not found (HTTP %s)' % (name, s))
        return p['projects'][0]['id']

    def one(self, path, key, what):
        s, p, _ = self.n('GET', path)
        if s != 200:
            die('%s lookup failed: HTTP %s %s' % (what, s, p))
        items = p.get(key, [])
        return items[0] if items else None
"""

  private val openstackNetworkPython: String = openstackNeutronClientPython +
    """

def find_net(cl):
    pid = cl.project_id(SPEC['project'])
    return pid, cl.one('/networks?name=%s&project_id=%s' % (SPEC['name'], pid), 'networks', 'network')


def problems(cl, net):
    # What differs from the description; an empty list means nothing to do.
    if net is None:
        return ['network %s is missing' % SPEC['name']]
    out = []
    if SPEC['flat'] and (net.get('provider:network_type') != 'flat' or net.get('provider:physical_network') != SPEC['flat']):
        out.append('network %s is not flat on %s' % (SPEC['name'], SPEC['flat']))
    if bool(net.get('shared')) != SPEC['shared']:
        out.append('network %s: shared is %s' % (SPEC['name'], net.get('shared')))
    if bool(net.get('router:external')) != SPEC['external']:
        out.append('network %s: external is %s' % (SPEC['name'], net.get('router:external')))
    sub = SPEC['subnet']
    if sub:
        subs = cl.one('/subnets?network_id=' + net['id'], 'subnets', 'subnet')
        if subs is None:
            out.append('network %s has no subnet' % SPEC['name'])
        elif subs.get('cidr') != sub['cidr']:
            out.append('subnet of %s is %s, not %s' % (SPEC['name'], subs.get('cidr'), sub['cidr']))
    return out


def ensure():
    cl = NClients()
    pid, net = find_net(cl)
    if net is None:
        body = {'name': SPEC['name'], 'project_id': pid, 'shared': SPEC['shared'], 'router:external': SPEC['external']}
        if SPEC['flat']:
            body['provider:network_type'] = 'flat'
            body['provider:physical_network'] = SPEC['flat']
        s, p, _ = cl.n('POST', '/networks', {'network': body})
        if s not in (200, 201):
            die('creating network %s failed: HTTP %s %s' % (SPEC['name'], s, p))
        net = p['network']
    elif SPEC['flat'] and (net.get('provider:network_type') != 'flat' or net.get('provider:physical_network') != SPEC['flat']):
        die('network %s exists but is %s on %s, not flat on %s' % (SPEC['name'], net.get('provider:network_type'), net.get('provider:physical_network'), SPEC['flat']))
    else:
        change = {}
        if bool(net.get('shared')) != SPEC['shared']:
            change['shared'] = SPEC['shared']
        if bool(net.get('router:external')) != SPEC['external']:
            change['router:external'] = SPEC['external']
        if change:
            s, p, _ = cl.n('PUT', '/networks/' + net['id'], {'network': change})
            if s != 200:
                die('updating network %s failed: HTTP %s %s' % (SPEC['name'], s, p))
    sub = SPEC['subnet']
    if sub:
        have = cl.one('/subnets?network_id=' + net['id'], 'subnets', 'subnet')
        if have is None:
            body = {'network_id': net['id'], 'ip_version': 4, 'name': sub['name'], 'cidr': sub['cidr'], 'project_id': pid, 'enable_dhcp': sub['dhcp'], 'gateway_ip': sub['gateway']}
            if sub['pool']:
                body['allocation_pools'] = [{'start': sub['pool'][0], 'end': sub['pool'][1]}]
            s, p, _ = cl.n('POST', '/subnets', {'subnet': body})
            if s not in (200, 201):
                die('creating subnet %s failed: HTTP %s %s' % (sub['name'], s, p))
        elif have.get('cidr') != sub['cidr']:
            die('network %s has the subnet %s, not %s; delete the network and run again' % (SPEC['name'], have.get('cidr'), sub['cidr']))
    print('network %s ready (%s)' % (SPEC['name'], net['id']))


def check():
    cl = NClients()
    pid, net = find_net(cl)
    bad = problems(cl, net)
    if bad:
        die('; '.join(bad))
    print('network %s is as wanted (%s)' % (SPEC['name'], net['id']))


def exists():
    if find_net(NClients())[1] is None:
        die('no network %s' % SPEC['name'])


def remove():
    cl = NClients()
    pid, net = find_net(cl)
    if net is None:
        print('network %s: not there' % SPEC['name'])
        return
    s, p, _ = cl.n('GET', '/ports?network_id=' + net['id'])
    for port in p.get('ports', []) if s == 200 else []:
        if port.get('device_owner') != 'network:dhcp':
            cl.n('DELETE', '/ports/' + port['id'])
    end = time.time() + 120
    while True:
        s, _, _ = cl.n('DELETE', '/networks/' + net['id'])
        if s in (204, 404):
            print('network %s removed' % SPEC['name'])
            return
        if time.time() > end:
            die('network %s could not be deleted: HTTP %s' % (SPEC['name'], s))
        time.sleep(5)


if __name__ == '__main__':
    cmd = sys.argv[1] if len(sys.argv) > 1 else ''
    if cmd == 'ensure':
        ensure()
    elif cmd == 'check':
        check()
    elif cmd == 'exists':
        exists()
    elif cmd == 'remove':
        remove()
    else:
        die('usage: ensure | check | exists | remove')
"""

  private val openstackSecurityGroupPython: String =
    openstackNeutronClientPython +
      """

def find_sg(cl):
    pid = cl.project_id(SPEC['project'])
    return pid, cl.one('/security-groups?name=%s&project_id=%s' % (SPEC['name'], pid), 'security_groups', 'security group')


def missing_rules(sg):
    have = set((r.get('direction'), r.get('protocol'), r.get('port_range_min')) for r in sg.get('security_group_rules', []))
    return [r for r in SPEC['rules'] if ('ingress', r[0], r[1]) not in have]


def ensure():
    cl = NClients()
    pid, sg = find_sg(cl)
    if sg is None:
        s, p, _ = cl.n('POST', '/security-groups', {'security_group': {'name': SPEC['name'], 'description': SPEC['description'], 'project_id': pid}})
        if s not in (200, 201):
            die('creating security group %s failed: HTTP %s %s' % (SPEC['name'], s, p))
        sg = p['security_group']
    for proto, port in missing_rules(sg):
        rule = {'security_group_id': sg['id'], 'direction': 'ingress', 'ethertype': 'IPv4', 'protocol': proto, 'remote_ip_prefix': '0.0.0.0/0'}
        if port is not None:
            rule['port_range_min'] = port
            rule['port_range_max'] = port
        s, p, _ = cl.n('POST', '/security-group-rules', {'security_group_rule': rule})
        if s not in (200, 201):
            die('creating a rule in %s failed: HTTP %s %s' % (SPEC['name'], s, p))
    print('security group %s ready (%s)' % (SPEC['name'], sg['id']))


def check():
    cl = NClients()
    sg = find_sg(cl)[1]
    if sg is None:
        die('security group %s does not exist' % SPEC['name'])
    bad = missing_rules(sg)
    if bad:
        die('security group %s lacks the rules %s' % (SPEC['name'], bad))
    print('security group %s is as wanted (%s)' % (SPEC['name'], sg['id']))


def exists():
    if find_sg(NClients())[1] is None:
        die('no security group %s' % SPEC['name'])


def remove():
    cl = NClients()
    sg = find_sg(cl)[1]
    if sg is None:
        print('security group %s: not there' % SPEC['name'])
        return
    s, _, _ = cl.n('DELETE', '/security-groups/' + sg['id'])
    print('security group %s: HTTP %s' % (SPEC['name'], s))


if __name__ == '__main__':
    cmd = sys.argv[1] if len(sys.argv) > 1 else ''
    if cmd == 'ensure':
        ensure()
    elif cmd == 'check':
        check()
    elif cmd == 'exists':
        exists()
    elif cmd == 'remove':
        remove()
    else:
        die('usage: ensure | check | exists | remove')
"""

  /** The path of the helper `openstackNetwork` writes for the network `name` in
    * `project`.
    */
  def openstackNetworkHelperPath(
      name: String,
      project: String = "admin"
  ): String =
    s"/usr/local/sbin/orphera-network-$project-$name"

  /** The path of the helper `openstackSecurityGroup` writes for the group
    * `name` in `project`.
    */
  def openstackSecurityGroupHelperPath(
      name: String,
      project: String = "admin"
  ): String =
    s"/usr/local/sbin/orphera-secgroup-$project-$name"

  /** The path of the helper `openstackImage` writes for the image `name`. */
  def openstackImageHelperPath(name: String): String =
    s"/usr/local/sbin/orphera-image-$name"

  /** The path of the helper `openstackFlavor` writes for the flavor `id`. */
  def openstackFlavorHelperPath(id: String): String =
    s"/usr/local/sbin/orphera-flavor-$id"

  private def openstackScript(
      template: String,
      keystone: String,
      adminPassword: Secret,
      ca: String,
      spec: String
  ): String =
    require(
      keystone.startsWith("https://") || keystone.startsWith("http://"),
      s"not a Keystone URL: '$keystone'"
    )
    template
      .replace(
        "@@KS@@",
        pyString("keystone", keystone.stripSuffix("/") + "/v3")
      )
      .replace(
        "@@ADMINPW@@",
        pyString("the admin password", adminPassword.reveal)
      )
      .replace("@@CA@@", pyString("ca", ca))
      .replace("@@SPEC@@", spec)

  /** A Glance image as a description: the image `name` made from the file
    * `imageFile` on the node, public (or as `visibility` says), in
    * `diskFormat`, with `tags` and the extra `properties`. The result is the
    * helper (a root-only script holding the admin password; `keystone` is the
    * base URL such as `https://tst6.example.com:5000` and `ca` the file that
    * verifies it) and the command that runs it. The command is `unchanged`
    * while an active image of that name with the tags, the visibility, the
    * format and the properties is in Glance; otherwise the images of that name
    * are deleted and the file is uploaded. With `replace` it runs every time
    * and uploads again (for a rebuilt image). The file must exist when it runs,
    * so the caller orders it after whatever makes the file.
    */
  def openstackImage(
      label: String,
      name: String,
      imageFile: String,
      keystone: String,
      adminPassword: Secret,
      ca: String,
      tags: List[String] = Nil,
      properties: Map[String, String] = Map.empty,
      diskFormat: String = "qcow2",
      visibility: String = "public",
      replace: Boolean = false,
      timeout: Int = 1800
  ): (ApplyResource, ApplyResource) =
    keystoneName("image name", name)
    keystoneName("disk format", diskFormat)
    keystoneName("visibility", visibility)
    tags.foreach(keystoneName("an image tag", _))
    properties.keys.foreach(keystoneName("an image property", _))
    require(
      imageFile.startsWith("/"),
      s"the image file must be an absolute path: '$imageFile'"
    )
    val props = properties.toList
      .sortBy(_._1)
      .map((k, v) =>
        pyString("property", k) + ": " + pyString("a property value", v)
      )
    val spec = "{" + List(
      "'name': " + pyString("name", name),
      "'file': " + pyString("file", imageFile),
      "'disk_format': " + pyString("disk format", diskFormat),
      "'visibility': " + pyString("visibility", visibility),
      "'tags': " + tags.map(pyString("a tag", _)).mkString("[", ", ", "]"),
      "'properties': " + props.mkString("{", ", ", "}")
    ).mkString(", ") + "}"
    val path = openstackImageHelperPath(name)
    val helper = secretFile(
      path,
      Secret(
        openstackScript(openstackImagePython, keystone, adminPassword, ca, spec)
      ),
      mode = "0700",
      owner = "root",
      group = "root"
    )
    // An upload that runs for minutes with no output can lose its connection to the agent, so the helper runs
    // under a function that prints a line every 20 seconds while it works.
    val heartbeat =
      """heartbeat() { "$@" & P=$!; while kill -0 $P 2>/dev/null; do sleep 20; echo "still working ($(date +%H:%M:%S))"; done; wait $P; }"""
    val quoted = Apply.quote(path)
    val flags = if replace then " --replace" else ""
    val ensure = command(
      label,
      s"""set -e
         |$heartbeat
         |heartbeat $quoted ensure$flags""".stripMargin,
      unless = if replace then "false" else s"$quoted check >/dev/null 2>&1",
      timeout = timeout
    ).after(helper)
    (helper, ensure)

  /** What `openstackImage` made, removed again: the images of that name with
    * the tags. It does nothing without the helper on the node or while no such
    * image is there, and a failure is reported but does not stop the run. The
    * helper is left for the caller to remove.
    */
  def absentOpenstackImage(
      label: String,
      name: String,
      timeout: Int = 300
  ): ApplyResource =
    keystoneName("image name", name)
    val path = Apply.quote(openstackImageHelperPath(name))
    command(
      label,
      s"""$path remove || echo "Glance cleanup failed (continuing)"""",
      onlyIf = s"[ -x $path ] && $path exists >/dev/null 2>&1",
      timeout = timeout
    )

  /** A Nova flavor as a description: the flavor `id` with the `name`, `ram`
    * (MB), `vcpus` and `disk` (GB), public. The result is the helper and the
    * command that runs it, which is `unchanged` while the flavor is as wanted;
    * a flavor with other values is deleted and made again (servers keep their
    * own copy).
    */
  def openstackFlavor(
      label: String,
      id: String,
      name: String,
      ram: Int,
      vcpus: Int,
      disk: Int,
      keystone: String,
      adminPassword: Secret,
      ca: String,
      timeout: Int = 120
  ): (ApplyResource, ApplyResource) =
    keystoneName("flavor id", id)
    keystoneName("flavor name", name)
    require(
      ram > 0 && vcpus > 0 && disk >= 0,
      "a flavor needs positive memory and vCPUs, and a disk of 0 or more"
    )
    val spec = "{" + List(
      "'id': " + pyString("id", id),
      "'name': " + pyString("name", name),
      s"'ram': $ram",
      s"'vcpus': $vcpus",
      s"'disk': $disk"
    ).mkString(", ") + "}"
    val path = openstackFlavorHelperPath(id)
    val helper = secretFile(
      path,
      Secret(
        openstackScript(
          openstackFlavorPython,
          keystone,
          adminPassword,
          ca,
          spec
        )
      ),
      mode = "0700",
      owner = "root",
      group = "root"
    )
    val ensure = command(
      label,
      s"${Apply.quote(path)} ensure",
      unless = s"${Apply.quote(path)} check >/dev/null 2>&1",
      timeout = timeout
    ).after(helper)
    (helper, ensure)

  /** What `openstackFlavor` made, removed again. It does nothing without the
    * helper or while the flavor is not there.
    */
  def absentOpenstackFlavor(
      label: String,
      id: String,
      timeout: Int = 120
  ): ApplyResource =
    keystoneName("flavor id", id)
    val path = Apply.quote(openstackFlavorHelperPath(id))
    command(
      label,
      s"""$path remove || echo "Nova cleanup failed (continuing)"""",
      onlyIf = s"[ -x $path ] && $path exists >/dev/null 2>&1",
      timeout = timeout
    )

  /** A Nova key pair as a description: the pair `name`, made by Nova with no
    * public key given. By default it belongs to the admin user; with `owner` (a
    * user name and its password) it belongs to that user and is made in
    * `project`, which is where a service looks it up as itself. The private
    * half is written to `privateKeyFile` (root only, when the pair is made) if
    * one is named, and is otherwise not kept. The result is the helper and the
    * command that runs it, which is `unchanged` while the key pair exists.
    */
  def openstackKeypair(
      label: String,
      name: String,
      keystone: String,
      adminPassword: Secret,
      ca: String,
      owner: Option[(String, Secret)] = None,
      project: String = "admin",
      privateKeyFile: Option[String] = None,
      timeout: Int = 120
  ): (ApplyResource, ApplyResource) =
    keystoneName("key pair name", name)
    keystoneName("project", project)
    owner.foreach((user, _) => keystoneName("key pair owner", user))
    privateKeyFile.foreach(f =>
      require(
        f.startsWith("/"),
        s"the private key file must be an absolute path: '$f'"
      )
    )
    val user = owner.map(_._1).getOrElse("admin")
    val spec = "{" + List(
      "'name': " + pyString("name", name),
      "'user': " + pyString("user", user),
      "'password': " + owner
        .map((_, pw) => pyString("the owner's password", pw.reveal))
        .getOrElse("ADMIN_PW"),
      "'project': " + pyString("project", project),
      "'keyfile': " + privateKeyFile
        .map(pyString("private key file", _))
        .getOrElse("None")
    ).mkString(", ") + "}"
    val path = openstackKeypairHelperPath(name, user)
    val helper = secretFile(
      path,
      Secret(
        openstackScript(
          openstackKeypairPython,
          keystone,
          adminPassword,
          ca,
          spec
        )
      ),
      mode = "0700",
      owner = "root",
      group = "root"
    )
    val ensure = command(
      label,
      s"${Apply.quote(path)} ensure",
      unless = s"${Apply.quote(path)} check >/dev/null 2>&1",
      timeout = timeout
    ).after(helper)
    (helper, ensure)

  /** What `openstackKeypair` made, removed again (`user` is the owner it was
    * made for). It does nothing without the helper or while the key pair is not
    * there.
    */
  def absentOpenstackKeypair(
      label: String,
      name: String,
      user: String = "admin",
      timeout: Int = 120
  ): ApplyResource =
    keystoneName("key pair name", name)
    keystoneName("key pair owner", user)
    val path = Apply.quote(openstackKeypairHelperPath(name, user))
    command(
      label,
      s"""$path remove || echo "Nova cleanup failed (continuing)"""",
      onlyIf = s"[ -x $path ] && $path exists >/dev/null 2>&1",
      timeout = timeout
    )

  /** The subnet of an `openstackNetwork`: IPv4 `cidr`, the `gateway` (None for
    * none), an optional allocation `pool` (first, last address) and whether
    * DHCP is on.
    */
  final case class OpenstackSubnet(
      name: String,
      cidr: String,
      gateway: Option[String] = None,
      pool: Option[(String, String)] = None,
      dhcp: Boolean = true
  )

  private def requireAddress(what: String, text: String): Unit =
    require(
      text.nonEmpty && text.forall(c => c.isDigit || "./".contains(c)),
      s"$what is not an IPv4 address or network: '$text'"
    )

  /** A Neutron network (with at most one IPv4 subnet) of `project` as a
    * description. `flat = Some(physnet)` makes a flat provider network on that
    * physical network; without it the network is a tenant network of whatever
    * type Neutron chooses. `shared` and `external` are set as given. The result
    * is the helper (a root-only script holding the admin password) and the
    * command that runs it, which is `unchanged` while the network, its flags
    * and its subnet's CIDR are as described. An existing network of that name
    * in the project has its flags put right; one that is not flat on `flat` is
    * an error, and so is a subnet with another CIDR.
    */
  def openstackNetwork(
      label: String,
      name: String,
      keystone: String,
      adminPassword: Secret,
      ca: String,
      project: String = "admin",
      flat: Option[String] = None,
      shared: Boolean = false,
      external: Boolean = false,
      subnet: Option[OpenstackSubnet] = None,
      timeout: Int = 180
  ): (ApplyResource, ApplyResource) =
    keystoneName("network name", name)
    keystoneName("project", project)
    flat.foreach(keystoneName("physical network", _))
    subnet.foreach { sn =>
      keystoneName("subnet name", sn.name)
      requireAddress("a CIDR", sn.cidr)
      sn.gateway.foreach(requireAddress("a gateway", _))
      sn.pool.foreach((a, b) => {
        requireAddress("a pool address", a); requireAddress("a pool address", b)
      })
    }
    def py(b: Boolean) = if b then "True" else "False"
    val subnetSpec = subnet match
      case None     => "None"
      case Some(sn) =>
        "{" + List(
          "'name': " + pyString("subnet name", sn.name),
          "'cidr': " + pyString("cidr", sn.cidr),
          "'gateway': " + sn.gateway
            .map(pyString("gateway", _))
            .getOrElse("None"),
          "'pool': " + sn.pool
            .map((a, b) => s"(${pyString("pool", a)}, ${pyString("pool", b)})")
            .getOrElse("None"),
          "'dhcp': " + py(sn.dhcp)
        ).mkString(", ") + "}"
    val spec = "{" + List(
      "'name': " + pyString("name", name),
      "'project': " + pyString("project", project),
      "'flat': " + flat.map(pyString("physical network", _)).getOrElse("None"),
      "'shared': " + py(shared),
      "'external': " + py(external),
      "'subnet': " + subnetSpec
    ).mkString(", ") + "}"
    val path = openstackNetworkHelperPath(name, project)
    val helper = secretFile(
      path,
      Secret(
        openstackScript(
          openstackNetworkPython,
          keystone,
          adminPassword,
          ca,
          spec
        )
      ),
      mode = "0700",
      owner = "root",
      group = "root"
    )
    val ensure = command(
      label,
      s"${Apply.quote(path)} ensure",
      unless = s"${Apply.quote(path)} check >/dev/null 2>&1",
      timeout = timeout
    ).after(helper)
    (helper, ensure)

  /** What `openstackNetwork` made, removed again: the network's ports (except
    * the DHCP ones) and the network. It does nothing without the helper or
    * while the network is not there. A failure is reported and the run goes on.
    * The helper is left for the caller to remove.
    */
  def absentOpenstackNetwork(
      label: String,
      name: String,
      project: String = "admin",
      timeout: Int = 300
  ): ApplyResource =
    keystoneName("network name", name)
    keystoneName("project", project)
    val path = Apply.quote(openstackNetworkHelperPath(name, project))
    command(
      label,
      s"""$path remove || echo "Neutron cleanup failed (continuing)"""",
      onlyIf = s"[ -x $path ] && $path exists >/dev/null 2>&1",
      timeout = timeout
    )

  /** A Neutron security group of `project` with ingress rules from anywhere
    * (IPv4) as a description. `rules` are pairs of protocol and port,
    * `("tcp", Some(9443))` or `("icmp", None)`. It is `unchanged` while the
    * group exists with every rule; missing rules are added and other rules are
    * left alone.
    */
  def openstackSecurityGroup(
      label: String,
      name: String,
      description: String,
      rules: List[(String, Option[Int])],
      keystone: String,
      adminPassword: Secret,
      ca: String,
      project: String = "admin",
      timeout: Int = 120
  ): (ApplyResource, ApplyResource) =
    keystoneName("security group name", name)
    keystoneName("project", project)
    rules.foreach { (proto, port) =>
      require(
        Set("tcp", "udp", "icmp").contains(proto),
        s"not a protocol: '$proto'"
      )
      require(port.forall(p => p > 0 && p < 65536), s"not a port: $port")
    }
    val ruleText = rules
      .map((proto, port) =>
        s"(${pyString("protocol", proto)}, ${port.map(_.toString).getOrElse("None")})"
      )
      .mkString("[", ", ", "]")
    val spec = "{" + List(
      "'name': " + pyString("name", name),
      "'project': " + pyString("project", project),
      "'description': " + pyString("description", description),
      "'rules': " + ruleText
    ).mkString(", ") + "}"
    val path = openstackSecurityGroupHelperPath(name, project)
    val helper = secretFile(
      path,
      Secret(
        openstackScript(
          openstackSecurityGroupPython,
          keystone,
          adminPassword,
          ca,
          spec
        )
      ),
      mode = "0700",
      owner = "root",
      group = "root"
    )
    val ensure = command(
      label,
      s"${Apply.quote(path)} ensure",
      unless = s"${Apply.quote(path)} check >/dev/null 2>&1",
      timeout = timeout
    ).after(helper)
    (helper, ensure)

  /** What `openstackSecurityGroup` made, removed again. It does nothing without
    * the helper or while the group is not there; a group that is still in use
    * is reported and the run goes on.
    */
  def absentOpenstackSecurityGroup(
      label: String,
      name: String,
      project: String = "admin",
      timeout: Int = 120
  ): ApplyResource =
    keystoneName("security group name", name)
    keystoneName("project", project)
    val path = Apply.quote(openstackSecurityGroupHelperPath(name, project))
    command(
      label,
      s"""$path remove || echo "Neutron cleanup failed (continuing)"""",
      onlyIf = s"[ -x $path ] && $path exists >/dev/null 2>&1",
      timeout = timeout
    )

  // The helper of `helmRelease`: a root-only shell script (it may hold secret values) with the whole description
  // in it. check = the hash of the description last installed is the one given and Helm lists the release as
  // deployed; ensure = (re)install with `helm upgrade --install`; exists/remove for the absent kind.
  private val helmReleaseScript: String =
    """#!/bin/sh
set -e
export KUBECONFIG=@@KUBECONFIG@@
REL=@@REL@@
NS=@@NS@@
CHART=@@CHART@@
REPO=@@REPO@@
URL=@@URL@@
VERSION=@@VERSION@@
STATE=@@STATE@@
WANT=@@WANT@@

deployed() {
  helm status "$REL" -n "$NS" -o json 2>/dev/null | grep -Eq '"status": ?"deployed"'
}

case "$1" in
  check)
    [ "$(cat "$STATE" 2>/dev/null)" = "$WANT" ] && deployed
    ;;
  exists)
    deployed
    ;;
  ensure)
    helm repo add "$REPO" "$URL" --force-update >/dev/null
    helm repo update >/dev/null
    V="$VERSION"
    if [ -z "$V" ]; then V=$(helm show chart "$CHART" | awk '/^version:/{print $2}'); fi
    if [ -z "$V" ]; then echo "cannot find a version of $CHART" >&2; exit 1; fi
    echo "installing $CHART $V as $REL in $NS"
@@VALUES@@
    helm upgrade --install "$REL" "$CHART" --version "$V" --namespace "$NS" --create-namespace @@VALUESARG@@@@SETS@@@@WAIT@@
    mkdir -p "$(dirname "$STATE")"
    echo "$WANT" > "$STATE"
    echo "$REL $V installed"
    ;;
  remove)
    helm uninstall "$REL" -n "$NS" || true
    rm -f "$STATE"
    ;;
  *)
    echo "usage: ensure | check | exists | remove" >&2
    exit 1
    ;;
esac
"""

  /** The helper `helmRelease` writes for the release `release` in `namespace`.
    */
  def helmReleaseHelperPath(namespace: String, release: String): String =
    s"/usr/local/sbin/orphera-helm-$namespace-$release"

  /** The state file of a release made by `helmRelease` (the hash of the
    * description last installed).
    */
  def helmReleaseStatePath(
      namespace: String,
      release: String,
      stateDir: String = "/var/lib/orphera/helm"
  ): String =
    s"$stateDir/$namespace-$release.sha"

  private def helmName(what: String, text: String): Unit =
    require(
      text.nonEmpty && text.forall(c => c.isLetterOrDigit || "_-.".contains(c)),
      s"$what must be letters, digits and _ - . , not '$text'"
    )

  /** A Helm release as a description: `chart` (`repo/name`) from the repository
    * at `repoUrl`, installed as `release` in `namespace` (made if missing) with
    * the YAML `values` and the `set` pairs, on the cluster the `kubeconfig`
    * names. The result is the helper (a root-only script that holds the values,
    * which may be secret) and the command that runs it. The command is
    * `unchanged` while the hash of the chart, version, values and set pairs
    * last installed (kept in `helmReleaseStatePath`) is the description given
    * and Helm lists the release as deployed; otherwise it runs
    * `helm upgrade --install --wait`. With a `version` that chart version is
    * installed; without one the newest is installed the first time and again
    * whenever the description changes, and a run that changes nothing never
    * upgrades. A change made to the release outside this description is not
    * noticed unless the release stops being deployed.
    */
  def helmRelease(
      label: String,
      release: String,
      chart: String,
      repoUrl: String,
      namespace: String,
      version: Option[String] = None,
      values: Option[Secret] = None,
      set: List[(String, String)] = Nil,
      kubeconfig: String = "/etc/kubernetes/admin.conf",
      wait: Boolean = true,
      minutes: Int = 10,
      stateDir: String = "/var/lib/orphera/helm"
  ): (ApplyResource, ApplyResource) =
    helmName("a release name", release)
    helmName("a namespace", namespace)
    val parts = chart.split("/", -1)
    require(parts.length == 2, s"the chart must be repo/name, not '$chart'")
    parts.foreach(helmName("a chart or repository name", _))
    require(
      repoUrl.startsWith("https://") || repoUrl.startsWith("http://"),
      s"not a repository URL: '$repoUrl'"
    )
    version.foreach(v =>
      require(
        v.nonEmpty && v.forall(c => c.isLetterOrDigit || "._-+".contains(c)),
        s"not a chart version: '$v'"
      )
    )
    require(
      kubeconfig.startsWith("/"),
      s"the kubeconfig must be an absolute path: '$kubeconfig'"
    )
    require(minutes > 0, "minutes must be positive")
    set.foreach { (k, v) =>
      require(
        k.nonEmpty && k.forall(c => c >= ' ' && c <= '~'),
        s"not a value name: '$k'"
      )
      require(
        v.forall(c => c >= ' ' && c <= '~'),
        s"a set value must be printable ASCII: '$v'"
      )
    }
    val text = values.map(_.reveal)
    text.foreach(t =>
      require(
        !t.linesIterator.exists(_ == "ORPHERA_VALUES_END"),
        "the values must not contain a line ORPHERA_VALUES_END"
      )
    )
    val want = cephSpecSha(
      List(
        chart,
        version.getOrElse(""),
        text.getOrElse(""),
        set.map((k, v) => s"$k=$v").mkString("\n")
      ).mkString("\u0000")
    )
    // Built without stripMargin: a line of the values may itself start with `|` (a YAML block scalar).
    val valuesBlock = text match
      case Some(t) =>
        List(
          "    VALS=$(mktemp)",
          "    trap 'rm -f \"$VALS\"' EXIT",
          "    cat > \"$VALS\" <<'ORPHERA_VALUES_END'",
          t,
          "ORPHERA_VALUES_END"
        ).mkString("\n")
      case None => ""
    val script = helmReleaseScript
      .replace("@@KUBECONFIG@@", Apply.quote(kubeconfig))
      .replace("@@REL@@", Apply.quote(release))
      .replace("@@NS@@", Apply.quote(namespace))
      .replace("@@CHART@@", Apply.quote(chart))
      .replace("@@REPO@@", Apply.quote(parts(0)))
      .replace("@@URL@@", Apply.quote(repoUrl))
      .replace("@@VERSION@@", Apply.quote(version.getOrElse("")))
      .replace(
        "@@STATE@@",
        Apply.quote(helmReleaseStatePath(namespace, release, stateDir))
      )
      .replace("@@WANT@@", Apply.quote(want))
      .replace("@@VALUES@@", valuesBlock)
      .replace("@@VALUESARG@@", if text.isDefined then "-f \"$VALS\" " else "")
      .replace(
        "@@SETS@@",
        set.map((k, v) => s"--set ${Apply.quote(s"$k=$v")} ").mkString
      )
      .replace("@@WAIT@@", if wait then s"--wait --timeout ${minutes}m" else "")
    val path = helmReleaseHelperPath(namespace, release)
    val helper = secretFile(
      path,
      Secret(script),
      mode = "0700",
      owner = "root",
      group = "root"
    )
    val ensure = command(
      label,
      s"${Apply.quote(path)} ensure",
      unless = s"${Apply.quote(path)} check >/dev/null 2>&1",
      timeout = minutes * 60 + 120
    ).after(helper)
    (helper, ensure)

  /** What `helmRelease` made, removed again. It does nothing without the helper
    * or while the release is not deployed.
    */
  def absentHelmRelease(
      label: String,
      release: String,
      namespace: String,
      timeout: Int = 300
  ): ApplyResource =
    helmName("a release name", release)
    helmName("a namespace", namespace)
    val path = Apply.quote(helmReleaseHelperPath(namespace, release))
    command(
      label,
      s"""$path remove || echo "helm uninstall failed (continuing)"""",
      onlyIf = s"[ -x $path ] && $path exists >/dev/null 2>&1",
      timeout = timeout
    )

  private def cephName(what: String, text: String): Unit =
    require(
      text.nonEmpty && text.forall(c => c.isLetterOrDigit || "_-.".contains(c)),
      s"$what must be letters, digits and _ - . , not '$text'"
    )

  private def cephEntity(entity: String): Unit =
    val parts = entity.split("\\.", 2)
    require(
      parts.length == 2 && parts(0).nonEmpty && parts(0).forall(
        _.isLower
      ) && parts(1).nonEmpty &&
        parts(1).forall(c => c.isLetterOrDigit || "_-.".contains(c)),
      s"a cephx user is TYPE.NAME such as client.cinder, not '$entity'"
    )

  /** A pool on a Ceph cluster, as a description, for a node that has `cephadm`
    * (the commands run in `cephadm shell`). It is `unchanged` while the pool is
    * listed; otherwise it is created (an existing pool is never touched) and
    * initialised for `application`: `rbd pool init` for `rbd`, and
    * `ceph osd pool application enable` for any other.
    */
  def cephPool(
      label: String,
      pool: String,
      application: String = "rbd",
      timeout: Int = 180
  ): ApplyResource =
    cephName("a pool name", pool)
    cephName("an application", application)
    val init =
      if application == "rbd" then s"cephadm shell -- rbd pool init $pool"
      else
        s"cephadm shell -- ceph osd pool application enable $pool $application"
    command(
      label,
      s"""set -e
         |cephadm shell -- ceph osd pool create $pool
         |$init
         |echo "pool $pool ready"""".stripMargin,
      unless =
        s"cephadm shell -- ceph osd pool ls 2>/dev/null | grep -qx $pool",
      timeout = timeout
    )

  /** The pool gone, with every object in it: the monitors are allowed to delete
    * pools for the removal only. It does nothing while the pool is not listed.
    * When Ceph cannot be queried the guard says yes, so the removal runs and
    * fails with Ceph's own message: a cluster that is down is not "nothing to
    * remove".
    */
  def absentCephPool(
      label: String,
      pool: String,
      timeout: Int = 180
  ): ApplyResource =
    cephName("a pool name", pool)
    command(
      label,
      s"""R=0
         |cephadm shell -- ceph config set mon mon_allow_pool_delete true || R=1
         |cephadm shell -- ceph osd pool delete $pool $pool --yes-i-really-really-mean-it || R=1
         |cephadm shell -- ceph config set mon mon_allow_pool_delete false || R=1
         |[ "$$R" = 0 ] || exit 1
         |echo "pool $pool removed"""".stripMargin,
      onlyIf =
        s"""OUT=$$(cephadm shell -- ceph osd pool ls 2>/dev/null) || exit 0
           |echo "$$OUT" | tr -d '\\r' | grep -qx $pool""".stripMargin,
      timeout = timeout
    )

  /** A cephx user (`client.cinder`) with a fixed key and capabilities, as a
    * description, for a node with `cephadm`. `caps` are pairs such as
    * `"mon" -> "profile rbd"` and `"osd" -> "profile rbd pool=volumes"`. It is
    * `unchanged` while the key in Ceph is the one given and every capability is
    * there, so a rotated key is applied by running it again; otherwise the user
    * is imported from a keyring file that exists only for the import. The key
    * must be a whole cephx key. For an old `aes` key the monitors are first
    * allowed to accept `aes` (CVE-2025-30156), and for an `aes256k` key their
    * setting is left as it is. Other capabilities the user has are not looked
    * at: an import sets exactly the ones given.
    */
  def cephUser(
      label: String,
      entity: String,
      key: Secret,
      caps: List[(String, String)],
      timeout: Int = 180
  ): ApplyResource =
    cephEntity(entity)
    require(caps.nonEmpty, "cephUser needs at least one capability")
    caps.foreach { (kind, value) =>
      require(
        Set("mon", "osd", "mgr", "mds").contains(kind),
        s"not a capability type: '$kind'"
      )
      require(
        value.nonEmpty && value.forall(c =>
          c >= ' ' && c <= '~' && !"\"'\\$`".contains(c)
        ),
        s"a capability must be printable ASCII without quotes, backslashes, $$ or backticks: '$value'"
      )
    }
    require(CephKeys.isKey(key.reveal), "cephUser: not a cephx key")
    val capLines =
      caps.map((kind, value) => s"    caps $kind = \"$value\"").mkString("\n")
    val file = s"orphera-$entity.keyring"
    val matches = caps
      .map((kind, value) =>
        s"""echo "$$CAPS" | grep -qF 'caps $kind = "$value"'"""
      )
      .mkString(" && ")
    command(
      label,
      s"""set -e
         |${CephKeys.allowCiphersFor(key.reveal, entity)}
         |umask 077
         |KEYRING=/tmp/$file
         |trap 'rm -f "$$KEYRING"' EXIT
         |cat > "$$KEYRING" <<'KEYRING_END'
         |[$entity]
         |    key = ${key.reveal}
         |$capLines
         |KEYRING_END
         |cephadm shell --mount "$$KEYRING" -- ceph auth import -i /mnt/$file
         |cephadm shell -- ceph auth get $entity >/dev/null
         |echo "$entity ready"""".stripMargin,
      unless =
        s"""[ "$$(cephadm shell -- ceph auth get-key $entity 2>/dev/null)" = "${key.reveal}" ] && CAPS=$$(cephadm shell -- ceph auth get $entity 2>/dev/null) && $matches""",
      timeout = timeout
    )

  /** The cephx user gone, as a description; nothing happens while it is not
    * listed. When Ceph cannot be queried the guard says yes, so the removal
    * runs and fails with Ceph's own message.
    */
  def absentCephUser(
      label: String,
      entity: String,
      timeout: Int = 180
  ): ApplyResource =
    cephEntity(entity)
    command(
      label,
      s"""set -e
         |cephadm shell -- ceph auth del $entity
         |echo "$entity removed"""".stripMargin,
      onlyIf = s"""OUT=$$(cephadm shell -- ceph auth ls 2>/dev/null) || exit 0
           |echo "$$OUT" | tr -d '\\r' | grep -qx '$entity'""".stripMargin,
      timeout = timeout
    )

  /** A CephFS filesystem, as a description, for a node with `cephadm`. It is
    * `unchanged` while `ceph fs ls` lists it and an MDS is active; otherwise it
    * is created if missing (`ceph fs volume create`, which also starts the MDS
    * daemons) and the run waits up to five minutes for an active MDS, failing
    * with a message if none comes.
    */
  def cephFilesystem(
      label: String,
      name: String,
      timeout: Int = 660
  ): ApplyResource =
    cephName("a filesystem name", name)
    val listed =
      s"""cephadm shell -- ceph fs ls 2>/dev/null | tr -d '\\r' | grep -q '^name: $name,'"""
    val active =
      """cephadm shell -- ceph mds stat 2>/dev/null | grep -q 'up:active'"""
    command(
      label,
      s"""set -e
         |if ! $listed; then
         |  cephadm shell -- ceph fs volume create $name
         |  echo "filesystem $name created"
         |fi
         |for i in $$(seq 1 60); do
         |  if $active; then echo "an MDS is active for $name"; exit 0; fi
         |  sleep 5
         |done
         |echo "no active MDS for $name after 5 minutes - check 'ceph fs status'" >&2
         |exit 1""".stripMargin,
      unless = s"$listed && $active",
      timeout = timeout
    )

  /** The filesystem gone with ALL its data (and its pools): the monitors are
    * allowed to delete pools for the removal only. It does nothing while
    * `ceph fs ls` does not list it; when Ceph cannot be queried the guard says
    * yes, so the removal fails with Ceph's own message.
    */
  def absentCephFilesystem(
      label: String,
      name: String,
      timeout: Int = 600
  ): ApplyResource =
    cephName("a filesystem name", name)
    command(
      label,
      s"""R=0
         |cephadm shell -- ceph config set mon mon_allow_pool_delete true || R=1
         |cephadm shell -- ceph fs volume rm $name --yes-i-really-mean-it || R=1
         |cephadm shell -- ceph config set mon mon_allow_pool_delete false || R=1
         |[ "$$R" = 0 ] || exit 1
         |echo "filesystem $name removed"""".stripMargin,
      onlyIf = s"""OUT=$$(cephadm shell -- ceph fs ls 2>/dev/null) || exit 0
           |echo "$$OUT" | tr -d '\\r' | grep -q '^name: $name,'""".stripMargin,
      timeout = timeout
    )

  /** An NFS-Ganesha cluster, as a description, with one daemon on the Ceph
    * orchestrator host that has the address `ip` (or the short name
    * `hostName`). It is `unchanged` while `ceph nfs cluster ls` lists it and
    * `ip`:`port` answers; otherwise it is created if missing and the run waits
    * up to five minutes for the port.
    */
  def cephNfsCluster(
      label: String,
      cluster: String,
      ip: String,
      hostName: String,
      port: Int = 2049,
      timeout: Int = 660
  ): ApplyResource =
    cephName("an NFS cluster name", cluster)
    cephName("a host name", hostName)
    require(
      ip.nonEmpty && ip.forall(c => c.isDigit || ".:".contains(c) || c.isLower),
      s"not an address: '$ip'"
    )
    require(port > 0 && port < 65536, s"not a port: $port")
    val listed =
      s"""cephadm shell -- ceph nfs cluster ls 2>/dev/null | tr -d '\\r' | grep -qw '$cluster'"""
    val up =
      s"""python3 -c "import socket; socket.create_connection(('$ip', $port), 3).close()" 2>/dev/null"""
    command(
      label,
      s"""set -e
         |if ! $listed; then
         |  HOST=$$(cephadm shell -- ceph orch host ls --format json 2>/dev/null | python3 -c "import sys, json
         |for h in json.load(sys.stdin):
         |    if h.get('addr') == '$ip' or h.get('hostname', '').split('.')[0] == '$hostName':
         |        print(h['hostname'])
         |        break")
         |  if [ -z "$$HOST" ]; then
         |    echo "no Ceph orchestrator host with address $ip or name $hostName (ceph orch host ls)" >&2
         |    exit 1
         |  fi
         |  cephadm shell -- ceph nfs cluster create $cluster "$$HOST"
         |  echo "NFS cluster $cluster created on $$HOST"
         |fi
         |for i in $$(seq 1 60); do
         |  if $up; then echo "NFS-Ganesha answers on $ip:$port"; exit 0; fi
         |  sleep 5
         |done
         |echo "NFS-Ganesha does not answer on $ip:$port after 5 minutes - check 'ceph nfs cluster info $cluster'" >&2
         |exit 1""".stripMargin,
      unless = s"$listed && $up",
      timeout = timeout
    )

  /** The NFS cluster gone; nothing happens while it is not listed, and a
    * cluster that cannot be queried is not "nothing to remove" (the guard says
    * yes and the removal fails with Ceph's message).
    */
  def absentCephNfsCluster(
      label: String,
      cluster: String,
      timeout: Int = 300
  ): ApplyResource =
    cephName("an NFS cluster name", cluster)
    command(
      label,
      s"""set -e
         |cephadm shell -- ceph nfs cluster rm $cluster
         |echo "NFS cluster $cluster removed"""".stripMargin,
      onlyIf =
        s"""OUT=$$(cephadm shell -- ceph nfs cluster ls 2>/dev/null) || exit 0
           |echo "$$OUT" | tr -d '\\r' | grep -qw '$cluster'""".stripMargin,
      timeout = timeout
    )

  private def cephOptionValue(what: String, text: String): Unit =
    require(
      text.forall(c => c >= ' ' && c <= '~' && c != '\''),
      s"$what must be printable ASCII without a single quote"
    )

  /** Ceph configuration options for one section (`who`, such as
    * `client.rgw.orphera` or `mon`), as a description, for a node with
    * `cephadm`. It is `unchanged` while `ceph config get` returns every value
    * given; otherwise each option is set (setting an option to its value is
    * harmless). Other options of the section are not looked at. A step that
    * depends on a changed option (restarting a daemon) can be notified.
    */
  def cephConfig(
      label: String,
      who: String,
      options: List[(String, String)],
      timeout: Int = 180
  ): ApplyResource =
    cephName("a configuration section", who)
    require(options.nonEmpty, "cephConfig needs at least one option")
    options.foreach { (k, v) =>
      cephName("an option name", k)
      cephOptionValue(s"the value of $k", v)
    }
    val sets = options
      .map((k, v) =>
        s"cephadm shell -- ceph config set $who $k ${Apply.quote(v)}"
      )
      .mkString("\n")
    val checks = options
      .map((k, v) =>
        s"""[ "$$(cephadm shell -- ceph config get $who $k 2>/dev/null | tr -d '\\r')" = ${Apply
            .quote(v)} ]"""
      )
      .mkString(" && ")
    command(
      label,
      s"""set -e
         |$sets
         |echo "${options.size} option(s) set for $who"""".stripMargin,
      unless = checks,
      timeout = timeout
    )

  /** Options of one section gone, as a description: every option of `who` whose
    * name starts with `prefix` (found in `ceph config dump`, where with no mask
    * the option is the first word that starts with the prefix) is removed.
    * Nothing happens while there is none; when Ceph cannot be queried the guard
    * says yes, so the removal fails with Ceph's own message.
    */
  def absentCephConfig(
      label: String,
      who: String,
      prefix: String,
      timeout: Int = 180
  ): ApplyResource =
    cephName("a configuration section", who)
    cephName("an option prefix", prefix)
    val listOptions =
      s"""cephadm shell -- ceph config dump 2>/dev/null | tr -d '\\r' | awk '$$1 == "$who" {for (i = 2; i <= NF; i++) if ($$i ~ /^$prefix/) { print $$i; break }}'"""
    command(
      label,
      s"""set -e
         |for o in $$($listOptions); do
         |  cephadm shell -- ceph config rm $who $$o
         |done
         |echo "options of $who starting with $prefix removed"""".stripMargin,
      onlyIf =
        s"""OUT=$$(cephadm shell -- ceph config dump 2>/dev/null) || exit 0
           |[ -n "$$(echo "$$OUT" | tr -d '\\r' | awk '$$1 == "$who" {for (i = 2; i <= NF; i++) if ($$i ~ /^$prefix/) { print $$i; break }}')" ]""".stripMargin,
      timeout = timeout
    )

  private def cephSpecSha(spec: String): String =
    MessageDigest
      .getInstance("SHA-256")
      .digest(spec.getBytes(StandardCharsets.UTF_8))
      .map(b => f"${b & 0xff}%02x")
      .mkString

  /** The state file of a cephadm service made by `cephOrchService` (the hash of
    * the spec last applied).
    */
  def cephOrchStatePath(
      serviceType: String,
      serviceId: String,
      stateDir: String = "/var/lib/orphera"
  ): String =
    s"$stateDir/ceph-orch-$serviceType.$serviceId.sha"

  /** A cephadm service from a spec file (`ceph orch apply -i`), as a
    * description. It is `unchanged` while the hash of the spec last applied
    * (kept in `cephOrchStatePath`) is the spec given and `ceph orch ls` lists
    * the service; otherwise the spec is applied (cephadm itself redeploys the
    * daemons whose spec changed). `@@HOST@@` in the spec stands for the
    * hostname of the orchestrator host that has the address `hostIp` or the
    * short name `hostName`; a run with no such host fails with a message. The
    * spec must not contain a line `SPEC_END`.
    */
  def cephOrchService(
      label: String,
      serviceType: String,
      serviceId: String,
      spec: String,
      hostIp: String,
      hostName: String,
      stateDir: String = "/var/lib/orphera",
      timeout: Int = 300
  ): ApplyResource =
    cephName("a service type", serviceType)
    cephName("a service id", serviceId)
    cephName("a host name", hostName)
    require(
      hostIp.nonEmpty && hostIp.forall(c =>
        c.isDigit || ".:".contains(c) || c.isLower
      ),
      s"not an address: '$hostIp'"
    )
    require(
      !spec.linesIterator.exists(_.trim == "SPEC_END"),
      "the spec must not contain a line SPEC_END"
    )
    require(!spec.contains("$"), "the spec must not contain $")
    require(
      stateDir.startsWith("/") && !stateDir.exists(c =>
        c.isWhitespace || "'\"$`\\".contains(c)
      ),
      s"not a directory: '$stateDir'"
    )
    require(
      !spec.linesIterator.exists(_.trim.startsWith("|")),
      "a line of the spec must not start with |"
    )
    val state = cephOrchStatePath(serviceType, serviceId, stateDir)
    val sha = cephSpecSha(spec)
    val file = s"orphera-$serviceType-$serviceId.yaml"
    val listed =
      s"""cephadm shell -- ceph orch ls --service-type $serviceType 2>/dev/null | tr -d '\\r' | grep -q '^$serviceType.$serviceId '"""
    command(
      label,
      s"""set -e
         |HOST=$$(cephadm shell -- ceph orch host ls --format json 2>/dev/null | python3 -c "import sys, json
         |for h in json.load(sys.stdin):
         |    if h.get('addr') == '$hostIp' or h.get('hostname', '').split('.')[0] == '$hostName':
         |        print(h['hostname'])
         |        break")
         |if [ -z "$$HOST" ]; then
         |  echo "no Ceph orchestrator host with address $hostIp or name $hostName (ceph orch host ls)" >&2
         |  exit 1
         |fi
         |umask 077
         |SPECFILE=/tmp/$file
         |trap 'rm -f "$$SPECFILE"' EXIT
         |cat <<'SPEC_END' | sed "s/@@HOST@@/$$HOST/g" > "$$SPECFILE"
         |$spec
         |SPEC_END
         |cephadm shell --mount "$$SPECFILE" -- ceph orch apply -i /mnt/$file
         |mkdir -p $stateDir
         |echo "$sha" > $state
         |echo "$serviceType.$serviceId applied"""".stripMargin,
      unless = s"""[ -f $state ] && [ "$$(cat $state)" = "$sha" ] && $listed""",
      timeout = timeout
    )

  /** The cephadm service gone (its daemons stop) and its state file removed, as
    * a description. Nothing happens while the service is not listed and the
    * state file is not there.
    */
  def absentCephOrchService(
      label: String,
      serviceType: String,
      serviceId: String,
      stateDir: String = "/var/lib/orphera",
      timeout: Int = 300
  ): ApplyResource =
    cephName("a service type", serviceType)
    cephName("a service id", serviceId)
    val state = cephOrchStatePath(serviceType, serviceId, stateDir)
    val listed =
      s"""cephadm shell -- ceph orch ls --service-type $serviceType 2>/dev/null | tr -d '\\r' | grep -q '^$serviceType.$serviceId '"""
    command(
      label,
      s"""set -e
         |if $listed; then
         |  cephadm shell -- ceph orch rm $serviceType.$serviceId
         |fi
         |rm -f $state
         |echo "$serviceType.$serviceId removed"""".stripMargin,
      onlyIf = s"""$listed && exit 0
                  |[ -e $state ]""".stripMargin,
      timeout = timeout
    )

  private def ovsName(what: String, text: String): Unit =
    require(
      text.nonEmpty && text.length <= 15 && text.forall(c =>
        c.isLetterOrDigit || "_-.".contains(c)
      ),
      s"$what must be 1 to 15 letters, digits and _ - . , not '$text'"
    )

  /** An Open vSwitch bridge with the given physical (or other) ports, up.
    * Nothing happens while the bridge exists, has every port and its link is
    * up; a missing bridge or port is added, and a port the bridge already has
    * is left alone. Needs Open vSwitch on the node (declare the
    * `openvswitch-switch` package and service before it).
    */
  def ovsBridge(
      label: String,
      bridge: String,
      ports: List[String] = Nil,
      timeout: Int = 60
  ): ApplyResource =
    ovsName("the bridge", bridge)
    ports.foreach(ovsName("a port", _))
    val portCommands =
      ports.map(p => s"ovs-vsctl --may-exist add-port $bridge $p")
    val portChecks =
      ports.map(p => s"ovs-vsctl list-ports $bridge | grep -qx $p")
    val portText = if ports.isEmpty then "no ports" else ports.mkString(" ")
    command(
      label,
      (List(
        "set -e",
        s"ovs-vsctl --may-exist add-br $bridge"
      ) ++ portCommands ++
        List(
          s"ip link set $bridge up",
          s"""echo "$bridge has $portText""""
        )).mkString("\n"),
      unless = (List(s"ovs-vsctl br-exists $bridge") ++ portChecks ++
        List(s"""ip -o link show $bridge | grep -q '<[^>]*UP'"""))
        .mkString(" && "),
      timeout = timeout
    )

  /** Open vSwitch's database manager set to `target` (such as
    * `ptcp:6640:127.0.0.1`, which lets a container reach ovsdb over loopback
    * TCP). Nothing happens while it already is that.
    */
  def ovsManager(
      label: String,
      target: String,
      timeout: Int = 60
  ): ApplyResource =
    require(
      target.nonEmpty && target.forall(c =>
        c.isLetterOrDigit || ":._-".contains(c)
      ),
      s"the manager target must be letters, digits and : . _ - , not '$target'"
    )
    command(
      label,
      s"""set -e
         |ovs-vsctl set-manager $target
         |echo "the Open vSwitch manager is $target"""".stripMargin,
      unless = s"ovs-vsctl get-manager | grep -qx '$target'",
      timeout = timeout
    )

  /** The Open vSwitch bridges gone, with their ports. Nothing happens while
    * none of them exists, or while Open vSwitch is not installed.
    */
  def absentOvsBridge(
      label: String,
      bridges: List[String],
      timeout: Int = 60
  ): ApplyResource =
    require(bridges.nonEmpty, "absentOvsBridge needs a bridge")
    bridges.foreach(ovsName("a bridge", _))
    val bridgeText = bridges.mkString(" ")
    command(
      label,
      (List("set -e") ++ bridges.map(b => s"ovs-vsctl --if-exists del-br $b") ++
        List(s"""echo "$bridgeText removed"""")).mkString("\n"),
      onlyIf = s"command -v ovs-vsctl >/dev/null 2>&1 && { " +
        bridges.map(b => s"ovs-vsctl br-exists $b").mkString(" || ") + "; }",
      timeout = timeout
    )

  /** One port gone from a bridge. Nothing happens while the bridge does not
    * have it, or while Open vSwitch is not installed.
    */
  def absentOvsPort(
      label: String,
      bridge: String,
      port: String,
      timeout: Int = 60
  ): ApplyResource =
    ovsName("the bridge", bridge)
    ovsName("the port", port)
    command(
      label,
      s"""set -e
         |ovs-vsctl --if-exists del-port $bridge $port
         |echo "$port removed from $bridge"""".stripMargin,
      onlyIf =
        s"command -v ovs-vsctl >/dev/null 2>&1 && ovs-vsctl list-ports $bridge 2>/dev/null | grep -qx $port",
      timeout = timeout
    )

  private def sqlName(what: String, text: String): Unit =
    require(
      text.nonEmpty && text.forall(c => c.isLetterOrDigit || c == '_'),
      s"$what must be letters, digits and underscores, not '$text'"
    )

  /** The password as SQL text between single quotes, inside a shell string
    * between double quotes.
    */
  private def sqlPassword(password: Secret): String =
    password.reveal
      .replace("\\", "\\\\")
      .replace("'", "\\'")
      .flatMap {
        case c @ ('\\' | '"' | '$' | '`') => s"\\$c"
        case c                            => c.toString
      }

  /** Databases and the user who owns them on a MariaDB server, as a
    * description, for a node where the `mariadb` client logs in as root (the
    * unix socket, as on a Galera node). The user (`'user'@'%'`, since a proxy
    * hides the client's address) gets all privileges on each database and the
    * given password, so a rotated password is applied by running it again. It
    * is `unchanged` while a real login as the user through `via` (the address
    * clients use, such as the VIP) reaches every database with that password.
    * `charset` and `collation` apply to databases the run creates.
    */
  def sqlDatabase(
      label: String,
      databases: List[String],
      user: String,
      password: Secret,
      via: String,
      charset: String = "",
      collation: String = ""
  ): ApplyResource =
    require(databases.nonEmpty, "sqlDatabase needs at least one database")
    databases.foreach(sqlName("a database name", _))
    sqlName("a user name", user)
    require(
      via.nonEmpty && via.forall(c =>
        c.isLetterOrDigit || c == '.' || c == '-'
      ),
      s"sqlDatabase: not an address: '$via'"
    )
    List(charset, collation).foreach(w =>
      require(
        w.forall(c => c.isLetterOrDigit || c == '_'),
        s"sqlDatabase: not a character set or collation: '$w'"
      )
    )
    val settings =
      (if charset.isEmpty then "" else s" DEFAULT CHARACTER SET $charset") +
        (if collation.isEmpty then "" else s" DEFAULT COLLATE $collation")
    val pw = sqlPassword(password)
    val statements =
      databases
        .map(d => s"CREATE DATABASE IF NOT EXISTS $d$settings; ")
        .mkString +
        s"CREATE USER IF NOT EXISTS '$user'@'%' IDENTIFIED BY '$pw'; " +
        s"ALTER USER '$user'@'%' IDENTIFIED BY '$pw'; " +
        databases
          .map(d => s"GRANT ALL PRIVILEGES ON $d.* TO '$user'@'%'; ")
          .mkString +
        "FLUSH PRIVILEGES;"
    val login = databases
      .map(d =>
        s"MYSQL_PWD=${Apply.quote(password.reveal)} mariadb -N -h $via -u $user $d -e 'SELECT 1' >/dev/null 2>&1"
      )
      .mkString(" && ")
    command(label, s"""mariadb -N -e "$statements"""", unless = login)

  /** The same databases and user gone, as a description: dropped when either
    * exists. When MariaDB cannot be queried the guard says yes, so the drop
    * runs and fails with MariaDB's own message: a database that is down is not
    * "nothing to remove".
    */
  def absentSqlDatabase(
      label: String,
      databases: List[String],
      user: String
  ): ApplyResource =
    require(databases.nonEmpty, "absentSqlDatabase needs at least one database")
    databases.foreach(sqlName("a database name", _))
    sqlName("a user name", user)
    val drops = databases.map(d => s"DROP DATABASE IF EXISTS $d; ").mkString
    val names = databases.map(d => s"'$d'").mkString(",")
    val exists =
      s"""OUT=$$(mariadb -N -e "SELECT 1 FROM information_schema.SCHEMATA WHERE SCHEMA_NAME IN ($names) UNION SELECT 1 FROM mysql.user WHERE User='$user'" 2>/dev/null) || exit 0; [ -n "$$OUT" ]"""
    command(
      label,
      s"""mariadb -N -e "${drops}DROP USER IF EXISTS '$user'@'%'; FLUSH PRIVILEGES;"""",
      onlyIf = exists
    )

  /** Services gone from the node, as a description. Each pair is a systemd unit
    * (its name without `.service`) and the container it runs. The unit is
    * stopped and disabled (nothing happens when it does not exist), its file
    * under /etc/systemd/system is removed, and the container is removed with
    * `podman rm -f` when podman has it; systemd reloads its units once, when a
    * file went. The resources are returned in the order they run, and what must
    * follow the removal is made `.after` all of them.
    */
  def absentContainerUnits(units: List[(String, String)]): List[ApplyResource] =
    require(units.nonEmpty, "absentContainerUnits needs at least one unit")
    require(
      units.forall((u, c) =>
        u.nonEmpty && c.nonEmpty && !u.exists(ch =>
          ch.isWhitespace || ch == '/'
        ) && !c.exists(_.isWhitespace)
      ),
      s"absentContainerUnits: a unit or container name with a space or a slash in $units"
    )
    val reload = command(
      "reload systemd after the unit files were removed",
      "systemctl daemon-reload",
      onChangeOnly = true
    )
    val each = units.flatMap { (unit, container) =>
      val stopped = service(unit, Some(false), Some(false))
      val file = absentFile(s"/etc/systemd/system/$unit.service")
        .after(stopped)
        .notifies(reload)
      val name = Apply.quote(container)
      val gone = command(
        s"remove the $container container",
        s"podman rm -f $name",
        onlyIf =
          s"command -v podman >/dev/null 2>&1 && podman container exists $name"
      ).after(stopped)
      List(stopped, file, gone)
    }
    each :+ reload

  /** Has the container images on the node, as a description: every reference in
    * `images` is pulled with `podman pull` (three tries, with a pause, through
    * `kolla.Pull`) unless the node already has it, so a run with all of them
    * present changes nothing. `hint` says where to look if a reference is
    * wrong, and appears in the message of a failed pull. The images the node
    * already has are left as they are.
    */
  def pullImages(
      label: String,
      images: List[String],
      hint: String = "",
      timeout: Int = 1800
  ): ApplyResource =
    require(images.nonEmpty, "pullImages needs at least one image")
    require(
      images.forall(i =>
        i.nonEmpty && !i.exists(c => c.isWhitespace || c == '\'')
      ),
      s"pullImages: an image reference with a space or a quote in $images"
    )
    val refs = images.map(Apply.quote).mkString(" ")
    val where = if hint.isEmpty then "the image names in the manifest" else hint
    val present =
      s"""for img in $refs; do podman image exists "$$img" || exit 1; done"""
    val run =
      s"""${kolla.Pull.shellFunction(where)}
         |for img in $refs; do
         |  podman image exists "$$img" || pull_retry "$$img" || exit 1
         |done""".stripMargin
    command(label, run, unless = present, timeout = timeout)

  /** Waits until an HTTP(S) endpoint answers, as a description: the URL, the
    * status codes that count as an answer, the CA that signs it, and text the
    * body must hold. It changes nothing: when the endpoint already answers it
    * reports `unchanged`, otherwise it waits (every `interval` seconds, for up
    * to `timeout`) and fails with a message that names the URL and carries
    * `hint` (where to look). `resolve` is curl's `host:port:address`, to reach
    * a name on the certificate at an address of this node whatever DNS says.
    * The check runs on the node, with curl.
    */
  def waitForHttp(
      label: String,
      url: String,
      status: List[Int] = List(200),
      ca: String = "",
      contains: String = "",
      resolve: String = "",
      timeout: Int = 120,
      interval: Int = 5,
      hint: String = ""
  ): ApplyResource =
    require(
      status.nonEmpty && status.forall(c => c >= 100 && c <= 599),
      s"waitForHttp needs status codes between 100 and 599, not $status"
    )
    require(
      interval > 0 && timeout >= interval,
      s"waitForHttp needs 0 < interval <= timeout, not $interval and $timeout"
    )
    val caOption =
      (if ca.isEmpty then "" else s"--cacert ${Apply.quote(ca)} ") +
        (if resolve.isEmpty then ""
         else s"--resolve ${Apply.quote(resolve)} ")
    val body =
      if contains.isEmpty then ""
      else s"""printf '%s' "$$OUT" | grep -qF -- ${Apply.quote(contains)} && """
    val codes = status.mkString("|")
    val check =
      s"""OUT=$$(curl -s --max-time 10 ${caOption}-w '\\n%{http_code}' ${Apply.quote(
          url
        )}); CODE=$$(printf '%s' "$$OUT" | tail -n 1); ${body}case "$$CODE" in $codes) true;; *) false;; esac"""
    waiting(label, check, url, timeout, interval, hint)

  /** Waits until a TCP port accepts a connection, as a description (see
    * `waitForHttp`).
    */
  def waitForPort(
      label: String,
      host: String,
      port: Int,
      timeout: Int = 120,
      interval: Int = 5,
      hint: String = ""
  ): ApplyResource =
    require(
      host.nonEmpty && host.forall(c =>
        c.isLetterOrDigit || ".-_:".contains(c)
      ),
      s"waitForPort needs a plain host name or address, not '$host'"
    )
    require(port >= 1 && port <= 65535, s"waitForPort needs a port, not $port")
    require(
      interval > 0 && timeout >= interval,
      s"waitForPort needs 0 < interval <= timeout, not $interval and $timeout"
    )
    val probe = s"exec 3<>/dev/tcp/$host/$port"
    val check = s"timeout 3 bash -c ${Apply.quote(probe)} >/dev/null 2>&1"
    waiting(label, check, s"$host:$port", timeout, interval, hint)

  private def waiting(
      label: String,
      check: String,
      what: String,
      timeout: Int,
      interval: Int,
      hint: String
  ): ApplyResource =
    val tries = (timeout + interval - 1) / interval
    val failure =
      s"$label: no answer from $what after $timeout s" +
        (if hint.isEmpty then "" else s" - $hint")
    val run =
      s"""for i in $$(seq 1 $tries); do
         |  { $check; } && exit 0
         |  sleep $interval
         |done
         |echo ${Apply.quote(failure)} >&2
         |exit 1""".stripMargin
    // Each try may take its 10 s of curl or 3 s of connect on top of the pause.
    command(
      label,
      run,
      unless = check,
      timeout = tries * (interval + 10) + 30
    )

  /** A question about the node, for resources that apply on some nodes only:
    * give one of `onlyIf` (a shell test that exits 0 when the resources apply),
    * `unless` (exits non-zero when they apply) or `creates` (a path that does
    * not exist). Resources made `after` the gate are skipped on a node where
    * the answer is no, and applied where it is yes. The gate itself changes
    * nothing.
    */
  def gate(
      label: String,
      onlyIf: String = "",
      unless: String = "",
      creates: String = ""
  ): ApplyResource =
    Command(
      label,
      run = "",
      onlyIf = onlyIf,
      unless = unless,
      creates = creates,
      gate = true
    )

/** A checked description: its resources and the order to apply them in. */
final case class ApplyPlan private (
    name: String,
    resources: Vector[ApplyResource],
    order: Vector[Int],
    // index -> the indices it comes after (by `after`)
    needs: Map[Int, Set[Int]],
    // index -> the indices that notify it
    notifiers: Map[Int, Set[Int]]
):
  def index(id: String): Option[Int] = resources.indexWhere(_.id == id) match
    case -1 => None
    case i  => Some(i)

object ApplyPlan:

  /** For a manifest: the plan, or an exception naming every problem when the
    * manifest is loaded, so a mistake in a description is reported before any
    * node is touched.
    */
  def orFail(name: String, resources: List[ApplyResource]): ApplyPlan =
    apply(name, resources) match
      case Right(plan) => plan
      case Left(ps)    =>
        sys.error(s"description '$name': ${ps.mkString("; ")}")

  /** Checks a description and works out its order, or lists every problem: a
    * name used twice, a relative path, a bad mode, a command with no guard and
    * not `onChangeOnly`, a reference to a resource that is not described, a
    * notification of something that is not an `onChangeOnly` command, a cycle.
    */
  def apply(
      name: String,
      resources: List[ApplyResource]
  ): Either[List[String], ApplyPlan] =
    val rs = resources.toVector
    val ids = rs.map(_.id)
    val byId = ids.zipWithIndex.toMap
    val problems = List.newBuilder[String]

    ids
      .groupBy(identity)
      .collect { case (id, xs) if xs.size > 1 => id }
      .toList
      .sorted
      .foreach(id =>
        problems += s"$id is described ${ids.count(_ == id)} times"
      )
    def checkPath(id: String, path: String): Unit =
      if !path.startsWith("/") then
        problems += s"$id: the path must be absolute"
    def checkMode(id: String, mode: String): Unit =
      if mode.nonEmpty && !mode.matches("[0-7]{3,4}") then
        problems += s"$id: mode '$mode' is not an octal mode such as \"0644\""
    rs.foreach {
      case f: ApplyResource.File =>
        checkPath(f.id, f.path); checkMode(f.id, f.mode)
      case d: ApplyResource.Directory =>
        checkPath(d.id, d.path); checkMode(d.id, d.mode)
      case s: ApplyResource.Symlink =>
        checkPath(s.id, s.path)
        if s.present && s.target.isEmpty then problems += s"${s.id}: no target"
      case p: ApplyResource.Package =>
        if !p.name.matches("[A-Za-z0-9][A-Za-z0-9.+_:-]*") then
          problems += s"${p.id}: not a package name"
      case u: ApplyResource.Service =>
        if !u.unit.matches("[A-Za-z0-9@._:-]+") then
          problems += s"${u.id}: not a unit name"
        if u.enabled.isEmpty && u.running.isEmpty && !rs.exists(
            _.triggers.contains(u.id)
          )
        then
          problems += s"${u.id}: says nothing about it (enabled, running) and nothing notifies it"
      case u: ApplyResource.User =>
        if !u.name.matches("[a-z_][a-z0-9_-]*[$]?") then
          problems += s"${u.id}: not a user name"
        if u.home.nonEmpty && !u.home.startsWith("/") then
          problems += s"${u.id}: home must be an absolute path"
      case g: ApplyResource.Group =>
        if !g.name.matches("[a-z_][a-z0-9_-]*[$]?") then
          problems += s"${g.id}: not a group name"
      case s: ApplyResource.Sysctl =>
        if !s.key.matches("[A-Za-z0-9_-]+([./][A-Za-z0-9_-]+)*") then
          problems += s"${s.id}: not a sysctl key"
        if s.value.trim.isEmpty || s.value.exists(_ < ' ') then
          problems += s"${s.id}: the value must be one line of text"
      case m: ApplyResource.KernelModule =>
        if !m.name.matches("[A-Za-z0-9_-]+") then
          problems += s"${m.id}: not a module name"
      case l: ApplyResource.LineInFile =>
        checkPath(l.id, l.path)
        if l.line.contains('\n') then
          problems += s"${l.id}: a line has no newline"
        if l.present && l.line.isEmpty then
          problems += s"${l.id}: the line is empty"
        if !l.present && l.line.isEmpty && l.matching.isEmpty then
          problems += s"${l.id}: give the line or a match"
      case k: ApplyResource.AuthorizedKey =>
        if !k.user.matches("[a-z_][a-z0-9_-]*[$]?") then
          problems += s"${k.id}: not a user name"
        if k.key.contains('\n') || k.key.trim.split("\\s+").length < 2 then
          problems += s"${k.id}: a key is one line: type, key, optional comment"
      case m: ApplyResource.Mount =>
        checkPath(m.id, m.path)
        if m.present && (m.device.isEmpty || m.fstype.isEmpty || m.options.isEmpty)
        then problems += s"${m.id}: device, fstype and options are needed"
        if List(m.path, m.device, m.fstype, m.options).exists(
            _.exists(_.isWhitespace)
          )
        then problems += s"${m.id}: no whitespace in an fstab field"
      case c: ApplyResource.Command =>
        if c.label.trim.isEmpty then problems += "a command has no label"
        if c.run.trim.isEmpty && !c.gate then
          problems += s"${c.id}: nothing to run"
        if List(c.onlyIf, c.unless, c.creates).count(_.nonEmpty) > 1 then
          problems += s"${c.id}: give one of onlyIf, unless and creates"
        if c.gate && !c.guarded then
          problems += s"${c.id}: a gate needs onlyIf, unless or creates"
        if !c.guarded && !c.onChangeOnly && !c.gate then
          problems += s"${c.id}: says neither when it is needed (onlyIf, unless, creates) nor onChangeOnly, " +
            "so it would run every time"
        if c.creates.nonEmpty && !c.creates.startsWith("/") then
          problems += s"${c.id}: creates must be an absolute path"
    }
    rs.foreach { r =>
      r.needs.foreach(n =>
        if !byId.contains(n) then
          problems += s"${r.id} comes after $n, which is not described"
        else if n == r.id then problems += s"${r.id} comes after itself"
      )
      r.triggers.foreach(t =>
        byId.get(t).map(rs(_)) match
          case None =>
            problems += s"${r.id} notifies $t, which is not described"
          case Some(c: ApplyResource.Command) if c.onChangeOnly => ()
          case Some(_: ApplyResource.Service)                   => ()
          case Some(_)                                          =>
            problems += s"${r.id} notifies $t, which is not a service or an onChangeOnly command"
      )
    }
    rs.collect { case c: ApplyResource.Command if c.onChangeOnly => c }
      .foreach(c =>
        if !rs.exists(_.triggers.contains(c.id)) then
          problems += s"${c.id} is onChangeOnly but nothing notifies it"
      )

    val found = problems.result()
    if found.nonEmpty then Left(found)
    else
      // Besides `after`: a user comes after the groups it names, and a file or directory after the user
      // and group that own it, when those are described too.
      def implied(r: ApplyResource): List[String] = r match
        case u: ApplyResource.User =>
          (u.group :: u.groups).filter(_.nonEmpty).map(g => s"group[$g]")
        case f: ApplyResource.File =>
          List(f.owner).filter(_.nonEmpty).map(o => s"user[$o]") ++ List(
            f.group
          ).filter(_.nonEmpty).map(g => s"group[$g]")
        case d: ApplyResource.Directory =>
          List(d.owner).filter(_.nonEmpty).map(o => s"user[$o]") ++ List(
            d.group
          ).filter(_.nonEmpty).map(g => s"group[$g]")
        case k: ApplyResource.AuthorizedKey if k.present =>
          List(s"user[${k.user}]")
        case _ => Nil
      val needs: Map[Int, Set[Int]] =
        rs.indices
          .map(i =>
            i -> (rs(i).needs ++ implied(rs(i)).filter(id =>
              byId.get(id).exists(_ != i)
            )).flatMap(byId.get).toSet
          )
          .toMap
      val notifiers: Map[Int, Set[Int]] =
        rs.indices
          .map(i =>
            i -> rs.indices.filter(j => rs(j).triggers.contains(rs(i).id)).toSet
          )
          .toMap
      // Before i: what it needs and what notifies it. Declaration order otherwise.
      val before: Map[Int, Set[Int]] =
        rs.indices.map(i => i -> (needs(i) ++ notifiers(i))).toMap
      @annotation.tailrec
      def sort(
          done: Vector[Int],
          left: Set[Int]
      ): Either[List[String], Vector[Int]] =
        if left.isEmpty then Right(done)
        else
          left.filter(i => before(i).forall(done.contains)).minOption match
            case Some(next) => sort(done :+ next, left - next)
            case None       =>
              Left(
                List(
                  s"these resources wait for each other: ${left.toList.sorted.map(rs(_).id).mkString(", ")}"
                )
              )
      sort(Vector.empty, rs.indices.toSet).map(order =>
        new ApplyPlan(name, rs, order, needs, notifiers)
      )

/** What a resource's observation found on the node. */
enum Observed:
  case Absent
  case RegularFile(mode: Int, owner: String, group: String, sha256: String)
  case Dir(mode: Int, owner: String, group: String)
  case Link(target: String)
  case OtherType

  /** A command's guard: whether the command is needed. */
  case Guard(needed: Boolean, note: String)

  /** A package: the node's package manager ("apt", "dnf", "none"), and the
    * installed versions.
    */
  case Pkg(manager: String, installed: Boolean, versions: List[String])

  /** A systemd unit, as `systemctl is-enabled` and `is-active` name it. */
  case ServiceState(enabled: String, active: String)

  /** A user that exists. */
  case Account(
      uid: Int,
      group: String,
      groups: List[String],
      home: String,
      shell: String
  )

  /** A group that exists. */
  case GroupEntry(gid: Int)

  /** What a resource's observe snippet printed, as it printed it (key=value).
    */
  case Facts(values: Map[String, String])
  case Unreadable(text: String)

/** How a resource ended. */
enum ApplyOutcome:
  case Unchanged(note: String)
  case NotNotified
  case WouldChange(changes: List[String], detail: List[String])
  case Changed(changes: List[String], detail: List[String])
  case Skipped(reason: String)
  case Failed(error: String)

final case class ResourceResult(id: String, outcome: ApplyOutcome):
  def changed: Boolean = outcome match
    case _: ApplyOutcome.Changed | _: ApplyOutcome.WouldChange => true
    case _                                                     => false
  def failed: Boolean = outcome match
    case _: ApplyOutcome.Failed => true
    case _                      => false
  def skipped: Boolean = outcome match
    case _: ApplyOutcome.Skipped => true
    case _                       => false
  def unchanged: Boolean = outcome match
    case _: ApplyOutcome.Unchanged | ApplyOutcome.NotNotified => true
    case _                                                    => false

/** What applying a description to one node did (or, with `check`, would do). */
final case class ApplyReport(
    node: String,
    name: String,
    check: Boolean,
    results: List[ResourceResult]
):
  /** Whether anything changed (with `check`: would change). */
  def changed: Boolean = results.exists(_.changed)
  def changed(id: String): Boolean =
    results.exists(r => r.id == id && r.changed)
  def failed: Boolean = results.exists(_.failed)
  def failures: List[String] = results.collect {
    case ResourceResult(id, ApplyOutcome.Failed(e)) => s"$id: $e"
  }

  def summary: String =
    val skipped = results.count(_.skipped)
    val failed = results.count(_.failed)
    val parts = List(
      s"${results
          .count(_.changed)} ${if check then "to change" else "changed"}",
      s"${results.count(_.unchanged)} unchanged"
    ) ++ Option.when(skipped > 0)(s"$skipped skipped") ++ Option.when(
      failed > 0
    )(s"$failed failed")
    if check then s"${parts.mkString(", ")} (check: nothing changed)"
    else parts.mkString(", ")

/** A change the agent's `ApplyFiles` makes itself, with no script (see
  * Protocol.proto). Strings that name a mode are octal; empty is "leave it".
  */
enum FileChange:
  case MakeDirectory(path: String, mode: String, owner: String, group: String)
  case SetAttributes(path: String, mode: String, owner: String, group: String)
  case RemovePath(path: String, directory: Boolean, recursive: Boolean = false)
  case MakeSymlink(path: String, target: String)
  case SetSysctl(key: String, value: String)
  case LoadModule(name: String)

  /** `expectedSha256` empty: the file must not exist yet. */
  case ReplaceText(
      path: String,
      content: String,
      expectedSha256: String,
      createMode: String,
      overwrite: Boolean = false,
      source: String = "" // a file on the node to take the bytes from
  )

/** A change the agent's `ApplyAccess` makes itself: accounts, authorized keys
  * and mounts (see Protocol.proto). Strings that name a number are decimal;
  * empty is "leave it".
  */
enum AccessChange:
  case CreateUser(
      name: String,
      uid: String,
      group: String,
      groups: List[String],
      home: String,
      shell: String,
      system: Boolean,
      createHome: Boolean
  )
  case ModifyUser(
      name: String,
      uid: String,
      group: String,
      addGroups: List[String],
      home: String,
      shell: String
  )
  case DeleteUser(name: String)
  case CreateGroup(name: String, gid: String, system: Boolean)
  case ModifyGroup(name: String, gid: String)
  case DeleteGroup(name: String)
  case AddAuthorizedKey(user: String, key: String)
  case RemoveAuthorizedKey(user: String, keyId: String)

  /** `expectedSha256`: the hash of the /etc/fstab the new text was made from.
    */
  case ReplaceFstab(content: String, expectedSha256: String)
  case MountPath(path: String, makeDirectory: Boolean)
  case Remount(path: String)
  case Unmount(path: String)

/** A change the agent's `ApplySoftware` makes itself: packages and systemd
  * units (see Protocol.proto). `verb` is enable, disable, start, stop, restart
  * or try-restart.
  */
enum SoftwareChange:
  case InstallPackage(name: String, version: String, updateCache: Boolean)
  case RemovePackage(name: String)
  case ControlUnit(unit: String, verb: String)
  case ReloadUnits

/** One node, as the engine sees it: how to read it and change it. The real one
  * runs shell commands and writes files through the agent; checks use a fake.
  */
trait ApplyTarget:
  def node: String

  /** The state of these resources (keyed by index); a resource missing from the
    * answer could not be read.
    */
  def observe(resources: List[(Int, ApplyResource)]): IO[Map[Int, Observed]]

  /** A file's text, for a diff (None: cannot be shown). */
  def readText(path: String): IO[Option[String]]

  /** A `fileFrom` source as text, rendered if it is a `.mustache` file. */
  def source(path: String, vars: Map[String, String]): IO[String]

  /** Text with `{{...}}` rendered with the node's facts and vars and `vars`. */
  def render(text: String, vars: Map[String, String]): IO[String]
  def write(
      path: String,
      content: String,
      owner: String,
      group: String,
      mode: Int
  ): IO[Unit]

  /** The same through the agent's `ApplyFiles` (a replacement guarded by the
    * hash of what was read), on an agent that has it; otherwise `write`.
    */
  def writeText(
      path: String,
      content: String,
      owner: String,
      group: String,
      mode: Int,
      @scala.annotation.unused expectedSha: String,
      @scala.annotation.unused overwrite: Boolean
  ): IO[Unit] = write(path, content, owner, group, mode)

  /** Runs a change; raises, with what it printed, when it fails. */
  def shell(label: String, script: String, timeout: Int): IO[Unit]

  /** Makes these changes in order through the agent's typed call, or, on an
    * agent that does not have it, runs `fallback` (the same changes as a
    * script). Raises when it fails. A target that only runs scripts need not
    * override it.
    */
  def typed(
      label: String,
      @scala.annotation.unused changes: List[FileChange],
      fallback: String,
      timeout: Int
  ): IO[Unit] = shell(label, fallback, timeout)

  /** The same for packages and units (`ApplySoftware`). */
  def software(
      label: String,
      @scala.annotation.unused changes: List[SoftwareChange],
      fallback: String,
      timeout: Int
  ): IO[Unit] = shell(label, fallback, timeout)

  /** The same for accounts, keys and mounts (`ApplyAccess`). */
  def access(
      label: String,
      @scala.annotation.unused changes: List[AccessChange],
      fallback: String,
      timeout: Int
  ): IO[Unit] = shell(label, fallback, timeout)

  /** The resources (by id) that an earlier run of this description owed a
    * notification and did not deliver, because what they depend on failed. A
    * target that keeps nothing says none.
    */
  def pendingNotices(
      @scala.annotation.unused plan: String
  ): IO[Set[String]] = IO.pure(Set.empty)

  /** Records which resources are still owed a notification (replacing the
    * earlier record; empty clears it).
    */
  def keepPendingNotices(
      @scala.annotation.unused plan: String,
      @scala.annotation.unused ids: Set[String]
  ): IO[Unit] = IO.unit

object Apply:

  /** The checked plan for `resources`, or an error naming every problem. */
  def plan(name: String, resources: List[ApplyResource]): IO[ApplyPlan] =
    IO.fromEither(
      ApplyPlan(name, resources).left.map(problems =>
        new IllegalArgumentException(
          s"description '$name': ${problems.mkString("; ")}"
        )
      )
    )

  private def sha256(text: String): String =
    MessageDigest
      .getInstance("SHA-256")
      .digest(text.getBytes(StandardCharsets.UTF_8))
      .map(b => f"${b & 0xff}%02x")
      .mkString

  /** The typed changes that write a file's text: the replacement, then its
    * mode, owner and group when they are given.
    */
  private[orchestrator] def writeChanges(
      path: String,
      text: String,
      owner: String,
      group: String,
      mode: Int,
      expectedSha: String,
      overwrite: Boolean,
      source: String = ""
  ): List[FileChange] =
    val create = if mode > 0 then "0" + Integer.toOctalString(mode) else "0644"
    FileChange.ReplaceText(
      path,
      text,
      expectedSha,
      create,
      overwrite,
      source
    ) ::
      (if mode > 0 || owner.nonEmpty || group.nonEmpty then
         List(
           FileChange.SetAttributes(
             path,
             if mode > 0 then "0" + Integer.toOctalString(mode) else "",
             owner,
             group
           )
         )
       else Nil)

  private[orchestrator] def octal(mode: String): Int =
    if mode.isEmpty then 0 else Integer.parseInt(mode, 8)

  private def modeText(m: Int): String =
    if m < 0 then "?" else "0" + Integer.toOctalString(m)

  def quote(s: String): String = "'" + s.replace("'", "'\\''") + "'"

  // A change: a file written through the agent, or a shell command.
  private enum Action:
    /** A file's whole text. `expectedSha` is the hash of what was read ("":
      * there was no file); `overwrite` replaces a file whatever it holds.
      */
    case Write(
        path: String,
        content: String,
        owner: String,
        group: String,
        mode: Int,
        expectedSha: String,
        overwrite: Boolean
    )
    case Shell(script: String)

    /** Changes the agent makes itself, with `fallback` as the script for an
      * agent that cannot.
      */
    case Typed(changes: List[FileChange], fallback: String)

    /** The same through `ApplyAccess`. */
    case Access(changes: List[AccessChange], fallback: String)

    /** The same through `ApplySoftware`. */
    case Software(changes: List[SoftwareChange], fallback: String)

  // What it takes to bring a resource to its wanted state: what differs (for
  // the plan), the actions, and the lines of a content diff.
  private final case class Difference(
      changes: List[String],
      actions: List[Action],
      detail: List[String]
  )

  private val NoDifference = Difference(Nil, Nil, Nil)

  /** The script a node without the typed call runs first: waits (up to 15
    * minutes) until no apt, dpkg or unattended-upgrades is running, as the
    * agent does itself. The dpkg lock alone is not enough: unattended-upgrades
    * can be between two locks, and when it ends needrestart restarts the agent
    * in the middle of the task.
    */
  private val AptIdleWait: String =
    "i=0; while { pgrep -x apt-get || pgrep -x apt || pgrep -x dpkg || pgrep -f '^/usr/bin/python3 /usr/bin/unattended-upgrade$'; } >/dev/null 2>&1; do i=$((i+1)); [ $i -ge 180 ] && { echo 'apt/dpkg still busy after 15 minutes' >&2; exit 1; }; sleep 5; done; "

  // What differs in a path's mode, owner and group, and the commands that fix it.
  private final case class Attrs(
      changes: List[String],
      commands: List[String],
      set: FileChange.SetAttributes
  ):
    /** The change that makes them right, none when they already are. */
    def actions: List[Action] =
      if commands.isEmpty then Nil
      else List(Action.Typed(List(set), commands.mkString(" && ")))

  private def attrs(
      path: String,
      mode: String,
      owner: String,
      group: String,
      m: Int,
      o: String,
      g: String
  ): Attrs =
    val modeWrong = mode.nonEmpty && octal(mode) != m
    val ownerWrong = owner.nonEmpty && owner != o
    val groupWrong = group.nonEmpty && group != g
    val wanted = List(
      Option.when(modeWrong)(
        s"mode ${modeText(m)} -> ${modeText(octal(mode))}" -> s"chmod $mode ${quote(path)}"
      ),
      Option.when(ownerWrong)(
        s"owner $o -> $owner" -> s"chown ${quote(owner)} ${quote(path)}"
      ),
      Option.when(groupWrong)(
        s"group $g -> $group" -> s"chgrp ${quote(group)} ${quote(path)}"
      )
    ).flatten
    Attrs(
      wanted.map(_._1),
      wanted.map(_._2),
      FileChange.SetAttributes(
        path,
        if modeWrong then mode else "",
        if ownerWrong then owner else "",
        if groupWrong then group else ""
      )
    )

  private def onNodeSource(f: ApplyResource.File): Option[String] =
    f.content match
      case Some(ApplyResource.Content.OnNode(p)) => Some(p)
      case _                                     => None

  /** A file that is to hold the bytes of another file on the node. The source
    * is looked at now, not at the start of the run, since an earlier step may
    * have made it.
    */
  private def compareCopy(
      f: ApplyResource.File,
      src: String,
      seen: Observed,
      target: ApplyTarget
  ): IO[Either[String, Difference]] =
    def copy(expected: String): Action =
      val fallback = (s"cp -- ${quote(src)} ${quote(f.path)}" ::
        attrs(f.path, f.mode, f.owner, f.group, -1, "", "").commands)
        .mkString(" && ")
      Action.Typed(
        writeChanges(
          f.path,
          "",
          f.owner,
          f.group,
          octal(f.mode),
          expected,
          false,
          src
        ),
        fallback
      )
    target
      .observe(
        List(0 -> ApplyResource.File(src, None, "", "", "", present = true))
      )
      .map(_.get(0))
      .map {
        case Some(Observed.RegularFile(_, _, _, srcSum)) =>
          seen match
            case Observed.Absent =>
              Right(
                Difference(
                  List(s"created (copied from $src)"),
                  List(copy("")),
                  Nil
                )
              )
            case Observed.RegularFile(m, o, g, sum) =>
              val fix = attrs(f.path, f.mode, f.owner, f.group, m, o, g)
              if sum != srcSum then
                Right(
                  Difference(
                    s"content (copied from $src)" :: fix.changes,
                    List(copy(sum)),
                    Nil
                  )
                )
              else Right(Difference(fix.changes, fix.actions, Nil))
            case Observed.Dir(_, _, _) =>
              Left(s"${f.path} is a directory: not replaced by a file")
            case Observed.Link(t) =>
              Left(s"${f.path} is a symlink (to $t): not replaced by a file")
            case _ => Left(s"${f.path} is not a regular file: not replaced")
        case Some(Observed.Absent) | None =>
          Left(s"the source $src is not on the node")
        case Some(Observed.Unreadable(text)) =>
          Left(s"could not read the source $src: $text")
        case Some(_) => Left(s"the source $src is not a regular file")
      }

  // The difference for one resource, or why it cannot be brought there.
  private def compare(
      r: ApplyResource,
      seen: Observed,
      content: Option[
        (String, Boolean)
      ], // the wanted text, and whether it is secret
      target: ApplyTarget,
      check: Boolean,
      notifiedBy: List[String]
  ): IO[Either[String, Difference]] =
    import ApplyResource.*
    val path = r match
      case f: File      => f.path
      case d: Directory => d.path
      case s: Symlink   => s.path
      case _            => ""
    val q = quote(path)
    seen match
      case Observed.Unreadable(text) =>
        IO.pure(Left(s"could not read it on the node: $text"))
      case _ =>
        r match
          case f: File if !f.present =>
            IO.pure(seen match
              case Observed.Absent => Right(NoDifference)
              case _: Observed.RegularFile | _: Observed.Link =>
                Right(
                  Difference(
                    List("removed"),
                    List(
                      Action.Typed(
                        List(FileChange.RemovePath(path, false)),
                        s"rm -f -- $q"
                      )
                    ),
                    Nil
                  )
                )
              case Observed.Dir(_, _, _) =>
                Left(s"$path is a directory: not removed")
              case _ => Left(s"$path is not a file: not removed"))
          case f: File if f.present && onNodeSource(f).isDefined =>
            compareCopy(f, onNodeSource(f).getOrElse(""), seen, target)
          case f: File =>
            val wanted = content.getOrElse(("", false))
            val text = wanted._1
            val secret = wanted._2
            def write(sha: String) =
              Action.Write(
                f.path,
                text,
                f.owner,
                f.group,
                octal(f.mode),
                sha,
                false
              )
            seen match
              case Observed.Absent =>
                IO.pure(
                  Right(Difference(List("created"), List(write("")), Nil))
                )
              case Observed.RegularFile(m, o, g, sum) =>
                val fix = attrs(f.path, f.mode, f.owner, f.group, m, o, g)
                if f.content.nonEmpty && !f.onlyIfMissing && sum != sha256(text)
                then
                  val diff =
                    if secret then IO.pure(List("(secret: not shown)"))
                    else
                      target
                        .readText(f.path)
                        .map(
                          _.fold(List("(cannot be shown)"))(now =>
                            TextDiff.unified(now, text, f.path)
                          )
                        )
                  diff.map(d =>
                    Right(
                      Difference(
                        List(if secret then "content (secret: not shown)"
                        else "content") ++ fix.changes,
                        List(write(sum)),
                        if secret then Nil else d
                      )
                    )
                  )
                else
                  IO.pure(
                    Right(Difference(fix.changes, fix.actions, Nil))
                  )
              case Observed.Dir(_, _, _) =>
                IO.pure(Left(s"$path is a directory: not replaced by a file"))
              case Observed.Link(t) =>
                IO.pure(
                  Left(s"$path is a symlink (to $t): not replaced by a file")
                )
              case _ =>
                IO.pure(Left(s"$path is not a regular file: not replaced"))
          case d: Directory if !d.present =>
            IO.pure(
              seen match
                case Observed.Absent       => Right(NoDifference)
                case Observed.Dir(_, _, _) =>
                  Right(
                    Difference(
                      List(
                        if d.recursive then "removed (with its contents)"
                        else "removed"
                      ),
                      List(
                        Action.Typed(
                          List(FileChange.RemovePath(path, true, d.recursive)),
                          if d.recursive then s"rm -rf -- $q"
                          else s"rmdir -- $q"
                        )
                      ),
                      Nil
                    )
                  )
                case _ => Left(s"$path is not a directory: not removed")
            )
          case d: Directory =>
            IO.pure(seen match
              case Observed.Absent =>
                val fix = attrs(d.path, d.mode, d.owner, d.group, -1, "", "")
                Right(
                  Difference(
                    List("created"),
                    List(
                      Action.Typed(
                        List(
                          FileChange.MakeDirectory(
                            d.path,
                            d.mode,
                            d.owner,
                            d.group
                          )
                        ),
                        (s"mkdir -p -- $q" :: fix.commands).mkString(" && ")
                      )
                    ),
                    Nil
                  )
                )
              case Observed.Dir(m, o, g) =>
                val fix = attrs(d.path, d.mode, d.owner, d.group, m, o, g)
                Right(Difference(fix.changes, fix.actions, Nil))
              case _ =>
                Left(s"$path exists and is not a directory: not replaced"))
          case s: Symlink if !s.present =>
            IO.pure(
              seen match
                case Observed.Absent  => Right(NoDifference)
                case Observed.Link(_) =>
                  Right(
                    Difference(
                      List("removed"),
                      List(
                        Action.Typed(
                          List(FileChange.RemovePath(path, false)),
                          s"rm -f -- $q"
                        )
                      ),
                      Nil
                    )
                  )
                case _ => Left(s"$path is not a symlink: not removed")
            )
          case s: Symlink =>
            val link = Action.Typed(
              List(FileChange.MakeSymlink(s.path, s.target)),
              s"ln -sfn -- ${quote(s.target)} $q"
            )
            IO.pure(seen match
              case Observed.Absent =>
                Right(
                  Difference(List(s"created -> ${s.target}"), List(link), Nil)
                )
              case Observed.Link(t) if t == s.target => Right(NoDifference)
              case Observed.Link(t)                  =>
                Right(
                  Difference(List(s"target $t -> ${s.target}"), List(link), Nil)
                )
              case _: Observed.RegularFile =>
                Right(
                  Difference(
                    List(s"a file replaced by a link to ${s.target}"),
                    List(link),
                    Nil
                  )
                )
              case _ =>
                Left(
                  s"$path is a directory or other: not replaced by a symlink"
                ))
          case c: Command =>
            IO.pure(
              Right(Difference(List("run"), List(Action.Shell(c.run)), Nil))
            )
          case p: Package => IO.pure(comparePackage(p, seen))
          case u: Service => IO.pure(compareService(u, seen, check, notifiedBy))
          case u: User    => IO.pure(compareUser(u, seen))
          case g: Group   => IO.pure(compareGroup(g, seen))
          case v: Sysctl  => IO.pure(compareSysctl(v, seen))
          case m: KernelModule  => IO.pure(compareModule(m, seen))
          case l: LineInFile    => IO.pure(compareLine(l, seen))
          case k: AuthorizedKey => IO.pure(compareKey(k, seen))
          case m: Mount         => IO.pure(compareMount(m, seen))

  private def shellOf(
      changes: List[String],
      script: String
  ): Either[String, Difference] =
    Right(Difference(changes, List(Action.Shell(script)), Nil))

  /** Whether an installed `version` is the wanted one: equal, or the wanted one
    * without its release part ("1.2.3" matches "1.2.3-1.el9").
    */
  private[orchestrator] def versionMatches(
      installed: String,
      wanted: String
  ): Boolean =
    installed == wanted || installed.startsWith(wanted + "-")

  private def comparePackage(
      p: ApplyResource.Package,
      seen: Observed
  ): Either[String, Difference] =
    val n = quote(p.name)
    val installChange =
      SoftwareChange.InstallPackage(p.name, p.version, p.updateCache)
    seen match
      case Observed.Pkg("none", _, _) =>
        Left("neither apt nor dnf on this node")
      case Observed.Pkg(manager, installed, versions) =>
        val apt = manager == "apt"
        val spec = if p.version.isEmpty then n
        else if apt then quote(s"${p.name}=${p.version}")
        else quote(s"${p.name}-${p.version}")
        val install =
          if apt then
            val update = if p.updateCache then "apt-get update -q && " else ""
            s"$AptIdleWait${update}DEBIAN_FRONTEND=noninteractive apt-get install -y -q -o DPkg::Lock::Timeout=300 --allow-downgrades $spec"
          else s"dnf -y -q install $spec || dnf -y -q downgrade $spec"
        if !p.present then
          if !installed then Right(NoDifference)
          else if apt then
            softwareOf(
              List("removed"),
              List(
                List(SoftwareChange.RemovePackage(p.name)) ->
                  s"${AptIdleWait}DEBIAN_FRONTEND=noninteractive apt-get remove -y -q -o DPkg::Lock::Timeout=300 $n"
              )
            )
          else
            softwareOf(
              List("removed"),
              List(
                List(
                  SoftwareChange.RemovePackage(p.name)
                ) -> s"dnf -y -q remove $n"
              )
            )
        else if !installed then
          softwareOf(
            List(if p.version.isEmpty then "installed"
            else s"installed ${p.version}"),
            List(List(installChange) -> install)
          )
        else if p.version.nonEmpty && !versions.exists(
            versionMatches(_, p.version)
          )
        then
          softwareOf(
            List(s"version ${versions.mkString(",")} -> ${p.version}"),
            List(List(installChange) -> install)
          )
        else Right(NoDifference)
      case other => Left(s"unexpected state: $other")

  private val Enabledish = Set(
    "enabled",
    "enabled-runtime",
    "static",
    "indirect",
    "generated",
    "transient",
    "alias",
    "linked",
    "linked-runtime"
  )
  private val Activeish = Set("active", "activating", "reloading")

  private def compareService(
      u: ApplyResource.Service,
      seen: Observed,
      check: Boolean,
      notifiedBy: List[String]
  ): Either[String, Difference] =
    val n = quote(u.unit)
    seen match
      case Observed.ServiceState(enabled, active) =>
        val missing = enabled == "not-found" || enabled.isEmpty
        val masked = enabled.startsWith("masked")
        val enable: Option[Either[String, (String, String)]] =
          u.enabled.flatMap {
            case true if missing => Some(Left("no such unit"))
            case true if masked  => Some(Left("the unit is masked"))
            case true if !Enabledish(enabled) =>
              Some(Right("enabled" -> "enable"))
            case false
                if enabled == "enabled" || enabled == "enabled-runtime" =>
              Some(Right("disabled" -> "disable"))
            case _ => None
          }
        val running = Activeish(active)
        val run: Option[Either[String, (String, String)]] = u.running.flatMap {
          case true if missing  => Some(Left("no such unit"))
          case true if masked   => Some(Left("the unit is masked"))
          case true if !running =>
            Some(Right("started" -> "start"))
          case false if running =>
            Some(Right("stopped" -> "stop"))
          case _ => None
        }
        val starting = run.exists(_.exists(_._1 == "started"))
        val restart: Option[Either[String, (String, String)]] =
          if notifiedBy.isEmpty || starting || u.running.contains(false) then
            None
          else
            val by = s"(notified by ${notifiedBy.mkString(", ")})"
            u.running match
              case Some(true) =>
                Some(Right(s"restarted $by" -> "restart"))
              case _ =>
                Option.when(running)(
                  Right(s"restarted $by" -> "try-restart")
                )
        val steps = List(enable, run, restart).flatten
        steps.collectFirst { case Left(e) => e } match
          case Some(e) if check && e == "no such unit" =>
            // An earlier resource (a package, a unit file) may yet bring it.
            Right(Difference(List("the unit does not exist yet"), Nil, Nil))
          case Some(e) => Left(e)
          case None    =>
            val ok = steps.collect { case Right(x) => x }
            if ok.isEmpty then Right(NoDifference)
            else
              softwareOf(
                ok.map(_._1),
                List(
                  (SoftwareChange.ReloadUnits ::
                    ok.map((_, verb) =>
                      SoftwareChange.ControlUnit(u.unit, verb)
                    )) ->
                    ("systemctl daemon-reload" :: ok.map((_, verb) =>
                      s"systemctl $verb $n"
                    )).mkString(" && ")
                )
              )
      case other => Left(s"unexpected state: $other")

  // One typed software change, with its script as the fallback.
  private def softwareOf(
      labels: List[String],
      parts: List[(List[SoftwareChange], String)]
  ): Either[String, Difference] =
    Right(
      Difference(
        labels,
        parts.map((c, script) => Action.Software(c, script)),
        Nil
      )
    )

  // One typed access change, with its script as the fallback.
  private def accessOf(
      labels: List[String],
      parts: List[(List[AccessChange], String)],
      detail: List[String] = Nil
  ): Either[String, Difference] =
    Right(
      Difference(
        labels,
        parts.map((c, script) => Action.Access(c, script)),
        detail
      )
    )

  private def compareUser(
      u: ApplyResource.User,
      seen: Observed
  ): Either[String, Difference] =
    val n = quote(u.name)
    seen match
      case Observed.Absent if !u.present => Right(NoDifference)
      case Observed.Absent               =>
        val flags = List(
          Option.when(u.uid >= 0)(s"-u ${u.uid}"),
          Option.when(u.group.nonEmpty)(s"-g ${quote(u.group)}"),
          Option.when(u.groups.nonEmpty)(
            s"-G ${quote(u.groups.mkString(","))}"
          ),
          Option.when(u.home.nonEmpty)(s"-d ${quote(u.home)}"),
          Option.when(!u.system || u.home.nonEmpty)("-m"),
          Option.when(u.shell.nonEmpty)(s"-s ${quote(u.shell)}"),
          Option.when(u.system)("-r")
        ).flatten
        accessOf(
          List("created"),
          List(
            List(
              AccessChange.CreateUser(
                u.name,
                if u.uid >= 0 then u.uid.toString else "",
                u.group,
                u.groups,
                u.home,
                u.shell,
                u.system,
                !u.system || u.home.nonEmpty
              )
            ) -> (("useradd" :: flags) :+ n).mkString(" ")
          )
        )
      case _: Observed.Account if !u.present =>
        accessOf(
          List("removed"),
          List(List(AccessChange.DeleteUser(u.name)) -> s"userdel $n")
        )
      case Observed.Account(uid, group, groups, home, shell) =>
        val missing = u.groups.filterNot(groups.contains)
        val wanted = List(
          Option.when(u.uid >= 0 && u.uid != uid)(
            s"uid $uid -> ${u.uid}" -> s"-u ${u.uid}"
          ),
          Option.when(u.group.nonEmpty && u.group != group)(
            s"group $group -> ${u.group}" -> s"-g ${quote(u.group)}"
          ),
          Option.when(missing.nonEmpty)(
            s"added to ${missing.mkString(", ")}" -> s"-a -G ${quote(missing.mkString(","))}"
          ),
          Option.when(u.home.nonEmpty && u.home != home)(
            s"home $home -> ${u.home}" -> s"-d ${quote(u.home)}"
          ),
          Option.when(u.shell.nonEmpty && u.shell != shell)(
            s"shell $shell -> ${u.shell}" -> s"-s ${quote(u.shell)}"
          )
        ).flatten
        if wanted.isEmpty then Right(NoDifference)
        else
          accessOf(
            wanted.map(_._1),
            List(
              List(
                AccessChange.ModifyUser(
                  u.name,
                  if u.uid >= 0 && u.uid != uid then u.uid.toString else "",
                  if u.group.nonEmpty && u.group != group then u.group else "",
                  missing,
                  if u.home.nonEmpty && u.home != home then u.home else "",
                  if u.shell.nonEmpty && u.shell != shell then u.shell else ""
                )
              ) -> (("usermod" :: wanted.map(_._2)) :+ n).mkString(" ")
            )
          )
      case other => Left(s"unexpected state: $other")

  private def compareGroup(
      g: ApplyResource.Group,
      seen: Observed
  ): Either[String, Difference] =
    val n = quote(g.name)
    seen match
      case Observed.Absent if !g.present => Right(NoDifference)
      case Observed.Absent               =>
        val flags = List(
          Option.when(g.gid >= 0)(s"-g ${g.gid}"),
          Option.when(g.system)("-r")
        ).flatten
        accessOf(
          List("created"),
          List(
            List(
              AccessChange.CreateGroup(
                g.name,
                if g.gid >= 0 then g.gid.toString else "",
                g.system
              )
            ) -> (("groupadd" :: flags) :+ n).mkString(" ")
          )
        )
      case _: Observed.GroupEntry if !g.present =>
        accessOf(
          List("removed"),
          List(List(AccessChange.DeleteGroup(g.name)) -> s"groupdel $n")
        )
      case Observed.GroupEntry(gid) if g.gid >= 0 && g.gid != gid =>
        accessOf(
          List(s"gid $gid -> ${g.gid}"),
          List(
            List(AccessChange.ModifyGroup(g.name, g.gid.toString)) ->
              s"groupmod -g ${g.gid} $n"
          )
        )
      case _: Observed.GroupEntry => Right(NoDifference)
      case other                  => Left(s"unexpected state: $other")

  // ---- sysctl, kernel modules, lines, keys, mounts --------------------------

  private val Mode0644 = Integer.parseInt("644", 8)

  private def squash(s: String): String = s.trim.split("\\s+").mkString(" ")

  private def sysctlFile(key: String): String =
    s"/etc/sysctl.d/60-orphera-${key.replace('/', '.')}.conf"

  private def compareSysctl(
      v: ApplyResource.Sysctl,
      seen: Observed
  ): Either[String, Difference] =
    seen match
      case Observed.Facts(kv) =>
        // A key the node does not have yet (its module is not loaded) is a
        // change to make, not a failure: the module resource before it may
        // provide it, and if nothing does, `sysctl -w` fails when it runs.
        val present = kv.get("found").contains("yes")
        val now = if present then kv.getOrElse("runtime", "") else "(absent)"
        val text = s"${v.key} = ${squash(v.value)}"
        val live = !present || squash(now) != squash(v.value)
        val stored = v.persist && kv.getOrElse("persisted", "") != text
        val actions = List(
          Option.when(live)(
            Action.Typed(
              List(FileChange.SetSysctl(v.key, squash(v.value))),
              s"sysctl -w ${quote(s"${v.key}=${squash(v.value)}")}"
            )
          ),
          Option.when(stored)(
            Action.Write(
              sysctlFile(v.key),
              text + "\n",
              "",
              "",
              Mode0644,
              "",
              true
            )
          )
        ).flatten
        val changes = List(
          Option.when(live)(s"$now -> ${squash(v.value)}"),
          Option.when(stored)(s"persisted in ${sysctlFile(v.key)}")
        ).flatten
        if actions.isEmpty then Right(NoDifference)
        else Right(Difference(changes, actions, Nil))
      case other => Left(s"unexpected state: $other")

  private def compareModule(
      m: ApplyResource.KernelModule,
      seen: Observed
  ): Either[String, Difference] =
    seen match
      case Observed.Facts(kv) =>
        val file = s"/etc/modules-load.d/orphera-${m.name}.conf"
        val live = !kv.get("loaded").contains("yes")
        val stored = m.persist && kv.getOrElse("persisted", "") != m.name
        val actions = List(
          Option.when(live)(
            Action.Typed(
              List(FileChange.LoadModule(m.name)),
              s"modprobe ${quote(m.name)}"
            )
          ),
          Option.when(stored)(
            Action.Write(file, m.name + "\n", "", "", Mode0644, "", true)
          )
        ).flatten
        val changes = List(
          Option.when(live)("loaded"),
          Option.when(stored)(s"listed in $file")
        ).flatten
        if actions.isEmpty then Right(NoDifference)
        else Right(Difference(changes, actions, Nil))
      case other => Left(s"unexpected state: $other")

  // awk reads the line and pattern from the environment, so no quoting reaches the awk program.
  private def awkEdit(
      path: String,
      line: String,
      re: String,
      program: String
  ): String =
    val p = quote(path)
    s"t=$$(mktemp) && LN=${quote(line)} RE=${quote(re)} awk ${quote(program)} $p > \"$$t\" && cat \"$$t\" > $p; rc=$$?; rm -f \"$$t\"; [ $$rc -eq 0 ]"

  private val AwkPut =
    "BEGIN{l=ENVIRON[\"LN\"];r=ENVIRON[\"RE\"]} r!=\"\" && $0~r {if(!d){print l;d=1};next} {print} END{if(!d)print l}"
  private val AwkDrop =
    "BEGIN{l=ENVIRON[\"LN\"];r=ENVIRON[\"RE\"]} $0==l || (r!=\"\" && $0~r) {next} {print}"

  /** The lines of a file as awk reads them: split on newline only, the last one
    * with or without a newline after it.
    */
  private[orchestrator] def records(text: String): List[String] =
    if text.isEmpty then Nil
    else
      val parts = text.split("\n", -1).toList
      if text.endsWith("\n") then parts.dropRight(1) else parts

  /** The text a line resource makes of `content`, as the awk programs do: with
    * `present`, the line replaces the first line that matches (the other
    * matches go) or is added at the end; otherwise the line and every match are
    * dropped.
    */
  private[orchestrator] def editedText(
      l: ApplyResource.LineInFile,
      content: String
  ): String =
    val re = Option.when(l.matching.nonEmpty)(
      java.util.regex.Pattern.compile(l.matching)
    )
    def hit(x: String): Boolean = re.exists(_.matcher(x).find())
    val lines = records(content)
    val out =
      if l.present then
        val (done, kept) =
          lines.foldLeft((false, List.empty[String])) { case ((d, acc), x) =>
            if hit(x) then (true, if d then acc else acc :+ l.line)
            else (d, acc :+ x)
          }
        if done then kept else kept :+ l.line
      else lines.filterNot(x => x == l.line || hit(x))
    if out.isEmpty then "" else out.mkString("", "\n", "\n")

  // The agent read the file's text: the edit is made there as a typed change
  // (guarded by the hash of what was read), with the awk script as the
  // fallback for an agent that cannot. Without the text, only the script.
  private def lineEdit(
      l: ApplyResource.LineInFile,
      kv: Map[String, String],
      changes: List[String],
      script: String
  ): Either[String, Difference] =
    kv.get("content") match
      case Some(content) =>
        val expected =
          if kv.get("exists").contains("yes") then sha256(content) else ""
        Right(
          Difference(
            changes,
            List(
              Action.Typed(
                List(
                  FileChange.ReplaceText(
                    l.path,
                    editedText(l, content),
                    expected,
                    "0644"
                  )
                ),
                script
              )
            ),
            Nil
          )
        )
      case None => shellOf(changes, script)

  private def compareLine(
      l: ApplyResource.LineInFile,
      seen: Observed
  ): Either[String, Difference] =
    seen match
      case Observed.Facts(kv) =>
        val exists = kv.get("exists").contains("yes")
        val exact = kv.get("exact").flatMap(_.toIntOption).getOrElse(0)
        val matches = kv.get("matches").flatMap(_.toIntOption).getOrElse(0)
        if l.present then
          if exact >= 1 && matches <= exact then Right(NoDifference)
          else
            val create = if exists then "" else s"touch -- ${quote(l.path)} && "
            val what =
              if matches > 0 && l.matching.nonEmpty then "replaced" else "added"
            lineEdit(
              l,
              kv,
              List(s"line $what"),
              create + awkEdit(l.path, l.line, l.matching, AwkPut)
            )
        else if !exists || exact + matches == 0 then Right(NoDifference)
        else
          lineEdit(
            l,
            kv,
            List(s"${math.max(exact, matches)} line(s) removed"),
            awkEdit(l.path, l.line, l.matching, AwkDrop)
          )
      case other => Left(s"unexpected state: $other")

  private def compareKey(
      k: ApplyResource.AuthorizedKey,
      seen: Observed
  ): Either[String, Difference] =
    seen match
      case Observed.Facts(kv)
          if !kv.get("exists").contains("yes") && !k.present =>
        Right(NoDifference)
      case Observed.Facts(kv) =>
        // A user that does not exist yet is not a failure when the key is to
        // be added: the user resource before it makes it (check mode reads
        // before that happens), and if nothing does, the script says so.
        val count = kv.get("count").flatMap(_.toIntOption).getOrElse(0)
        val u = quote(k.user)
        val file = "\"$h/.ssh/authorized_keys\""
        val home = s"h=$$(getent passwd $u | cut -d: -f6); g=$$(id -gn $u)"
        if k.present then
          if count >= 1 then Right(NoDifference)
          else
            accessOf(
              List("key added"),
              List(
                List(AccessChange.AddAuthorizedKey(k.user, k.key.trim)) ->
                  (s"""$home; [ -n "$$h" ] || { echo "user ${k.user} does not exist" >&2; exit 1; }; """ +
                    s"""mkdir -p "$$h/.ssh" && chmod 700 "$$h/.ssh" && touch $file && chmod 600 $file && """ +
                    s"""{ [ -z "$$(tail -c1 $file)" ] || echo >> $file; } && printf '%s\\n' ${quote(
                        k.key.trim
                      )} >> $file && """ +
                    s"""chown $u:"$$g" "$$h/.ssh" $file""")
              )
            )
        else if count == 0 || !kv.get("exists").contains("yes") then
          Right(NoDifference)
        else
          accessOf(
            List("key removed"),
            List(
              List(AccessChange.RemoveAuthorizedKey(k.user, k.keyId)) ->
                s"""$home; f=$file; t=$$(mktemp) && K=${quote(
                    k.keyId
                  )} awk ${quote(
                    "$1\" \"$2==ENVIRON[\"K\"]{next}{print}"
                  )} "$$f" > "$$t" && cat "$$t" > "$$f"; rc=$$?; rm -f "$$t"; [ $$rc -eq 0 ]"""
            )
          )
      case other => Left(s"unexpected state: $other")

  // The lines of an /etc/fstab text, as awk reads them.
  private def fstabLines(text: String): List[String] =
    if text.isEmpty then Nil else text.stripSuffix("\n").split("\n", -1).toList

  private def fstabMatches(line: String, path: String): Boolean =
    val f = line.trim.split("\\s+").toList
    f.size >= 2 && !f.lift(0).exists(_.startsWith("#")) && f
      .lift(1)
      .contains(path)

  /** The fstab with `path`'s entry set to `entry` (the first match replaced,
    * any others dropped, or appended when there is none), or, with `entry`
    * None, without it: what the awk programs of the script do.
    */
  private[orchestrator] def fstabEdited(
      text: String,
      path: String,
      entry: Option[String]
  ): String =
    val lines = fstabLines(text)
    val kept = entry match
      case None    => lines.filterNot(fstabMatches(_, path))
      case Some(e) =>
        val (before, rest) = lines.span(!fstabMatches(_, path))
        if rest.isEmpty then before :+ e
        else (before :+ e) ++ rest.drop(1).filterNot(fstabMatches(_, path))
    if kept.isEmpty then "" else kept.mkString("", "\n", "\n")

  private def compareMount(
      m: ApplyResource.Mount,
      seen: Observed
  ): Either[String, Difference] =
    seen match
      case Observed.Facts(kv) =>
        val inFstab = kv.get("fstab").contains("yes")
        val isMounted = kv.get("mounted").contains("yes")
        val fstabText = kv.get("fstab_text").filter(_.nonEmpty)
        val p = quote(m.path)
        val entry = s"${m.device} ${m.path} ${m.fstype} ${m.options} 0 0"
        val awkSet =
          "BEGIN{p=ENVIRON[\"P\"];l=ENVIRON[\"LN\"]} $1!~/^#/ && $2==p {if(!d){print l;d=1};next} {print} END{if(!d)print l}"
        val awkDel = "BEGIN{p=ENVIRON[\"P\"]} $1!~/^#/ && $2==p {next} {print}"
        def fstabEdit(program: String): String =
          s"t=$$(mktemp) && P=$p LN=${quote(entry)} awk ${quote(program)} /etc/fstab > \"$$t\" && cat \"$$t\" > /etc/fstab; rc=$$?; rm -f \"$$t\"; [ $$rc -eq 0 ]"
        // The agent replaces the file only if it is still the text read; with
        // no text read (a Scala agent, a large file) the script edits it.
        def fstabAction(wanted: Option[String], program: String): Action =
          fstabText match
            case Some(text) =>
              Action.Access(
                List(
                  AccessChange.ReplaceFstab(
                    fstabEdited(text, m.path, wanted),
                    sha256(text)
                  )
                ),
                fstabEdit(program)
              )
            case None => Action.Shell(fstabEdit(program))
        def done(parts: List[(String, Action)]) =
          if parts.isEmpty then Right(NoDifference)
          else Right(Difference(parts.map(_._1), parts.map(_._2), Nil))
        if !m.present then
          done(
            List(
              Option.when(isMounted)(
                (
                  "unmounted",
                  Action.Access(
                    List(AccessChange.Unmount(m.path)),
                    s"umount $p"
                  )
                )
              ),
              Option.when(inFstab)(
                ("removed from fstab", fstabAction(None, awkDel))
              )
            ).flatten
          )
        else
          val same =
            kv.getOrElse("dev", "") == m.device &&
              kv.getOrElse("fstype", "") == m.fstype &&
              kv.getOrElse("opts", "") == m.options
          val fstabPart =
            Option.when(!inFstab || !same)(
              (
                if inFstab then "fstab entry changed" else "added to fstab",
                fstabAction(Some(entry), awkSet)
              )
            )
          val mountPart = m.mounted match
            case Some(true) if !isMounted =>
              Some(
                (
                  "mounted",
                  Action.Access(
                    List(AccessChange.MountPath(m.path, true)),
                    s"mkdir -p -- $p && mount $p"
                  )
                )
              )
            case Some(true) if isMounted && inFstab && !same =>
              Some(
                (
                  "remounted",
                  Action.Access(
                    List(AccessChange.Remount(m.path)),
                    s"mount -o remount $p"
                  )
                )
              )
            case Some(false) if isMounted =>
              Some(
                (
                  "unmounted",
                  Action.Access(
                    List(AccessChange.Unmount(m.path)),
                    s"umount $p"
                  )
                )
              )
            case _ => None
          done(List(fstabPart, mountPart).flatten)
      case other => Left(s"unexpected state: $other")

  /** Applies `plan` to `target` (with `check`: only reads and compares),
    * printing each resource's line through `say`, and returns the report. A
    * resource whose `after` or notifying resource failed is skipped; with
    * `stopOnFailure`, everything after the first failure is.
    */
  def run(
      plan: ApplyPlan,
      target: ApplyTarget,
      check: Boolean,
      say: String => IO[Unit],
      stopOnFailure: Boolean = false
  ): IO[ApplyReport] =
    val rs = plan.resources
    val width = rs.map(_.id.length).foldLeft(0)(_ max _) min 60
    def line(id: String, text: String): IO[Unit] = say(
      s"${id.padTo(width, ' ')}  $text"
    )

    for
      // The wanted content of each file: (text, secret), or why it cannot be had.
      contents <- rs.zipWithIndex.toList
        .traverse {
          case (f: ApplyResource.File, i) =>
            (f.content match
              case Some(ApplyResource.Content.Text(t))   => IO.pure((t, false))
              case Some(ApplyResource.Content.Hidden(s)) =>
                IO.pure((s.reveal, true))
              case Some(ApplyResource.Content.Source(p, vars)) =>
                target.source(p, vars).map((_, false))
              case Some(ApplyResource.Content.Template(t, vars)) =>
                target.render(t, vars).map((_, false))
              case Some(ApplyResource.Content.OnNode(_)) =>
                IO.pure(("", false)) // read on the node, never here
              case None => IO.pure(("", false))
            ).attempt.map(e =>
              i -> e
                .leftMap(err => Option(err.getMessage).getOrElse(err.toString))
            )
          case (_, i) => IO.pure(i -> Right(("", false)))
        }
        .map(_.toMap)
      observable = rs.zipWithIndex.toList
        .filter {
          case (c: ApplyResource.Command, _) => c.guarded
          case _                             => true
        }
        .map(_.swap)
      observableIds = observable.map(_._1).toSet
      seenOrError <- target.observe(observable).attempt
      // Notifications an earlier run owed and did not deliver (a file changed, but the unit it
      // restarts was skipped because something before it failed): delivered now, so a change
      // that is already on the node is not left without its restart.
      before <- target
        .pendingNotices(plan.name)
        .attempt
        .map(_.getOrElse(Set.empty[String]))
      owedEarlier = (i: Int) =>
        plan.notifiers(i).nonEmpty && before.contains(rs(i).id)
      outcomes <- Ref.of[IO, Map[Int, ApplyOutcome]](Map.empty)
      _ <- plan.order.toList.traverse_ { i =>
        val r = rs(i)
        def finish(o: ApplyOutcome): IO[Unit] =
          outcomes.update(_ + (i -> o)) >> (o match
            case ApplyOutcome.Unchanged(note) =>
              line(
                r.id,
                if note.isEmpty then "unchanged" else s"unchanged ($note)"
              )
            case ApplyOutcome.NotNotified          => line(r.id, "not notified")
            case ApplyOutcome.WouldChange(ch, det) =>
              line(
                r.id,
                describe(r, ch, would = true, plan, i, owedEarlier(i))
              ) >> det
                .traverse_(d => say(s"  $d"))
            case ApplyOutcome.Changed(ch, det) =>
              line(
                r.id,
                describe(r, ch, would = false, plan, i, owedEarlier(i))
              ) >> det
                .traverse_(d => say(s"  $d"))
            case ApplyOutcome.Skipped(reason) => line(r.id, s"skipped: $reason")
            case ApplyOutcome.Failed(error)   => line(r.id, s"FAILED: $error"))
        outcomes.get.flatMap { done =>
          def badly(j: Int): Boolean = done.get(j).exists {
            case _: ApplyOutcome.Failed  => true
            case ApplyOutcome.Skipped(_) => true
            case _                       => false
          }
          val firstFailure = done.collectFirst {
            case (j, _: ApplyOutcome.Failed) => rs(j).id
          }
          val blocker =
            (plan.needs(i) ++ plan.notifiers(i)).toList.sorted.find(badly)
          if stopOnFailure && firstFailure.isDefined then
            finish(
              ApplyOutcome.Skipped(
                s"stopped after ${firstFailure.getOrElse("")} failed"
              )
            )
          else
            blocker match
              case Some(j) =>
                val why = done(j) match
                  case _: ApplyOutcome.Failed => "failed"
                  case _                      => "was skipped"
                finish(ApplyOutcome.Skipped(s"${rs(j).id} $why"))
              case None =>
                val notifiedBy = plan
                  .notifiers(i)
                  .toList
                  .sorted
                  .filter(j =>
                    done.get(j).exists {
                      case _: ApplyOutcome.Changed |
                          _: ApplyOutcome.WouldChange =>
                        true
                      case _ => false
                    }
                  )
                val notifiedIds =
                  notifiedBy.map(rs(_).id) ++
                    Option.when(owedEarlier(i))(EarlierRun)
                r match
                  case c: ApplyResource.Command
                      if c.onChangeOnly && notifiedIds.isEmpty =>
                    finish(ApplyOutcome.NotNotified)
                  case _ =>
                    // After a real change, what the node holds may have moved (a package brings a unit,
                    // a user owns a file): read this resource again rather than trust the first read.
                    val again =
                      !check && observableIds(i) && done.values.exists {
                        case _: ApplyOutcome.Changed => true
                        case _                       => false
                      }
                    def from(
                        m: Either[Throwable, Map[Int, Observed]]
                    ): Either[String, Observed] = m match
                      case Left(err) =>
                        Left(
                          s"could not read the node: ${Option(err.getMessage).getOrElse(err.toString)}"
                        )
                      case Right(found) =>
                        found.get(i).toRight("the node did not say")
                    val isGate = r match
                      case c: ApplyResource.Command => c.gate
                      case _                        => false
                    val seenIO: IO[Either[String, Observed]] = r match
                      case c: ApplyResource.Command if !c.guarded =>
                        IO.pure(Right(Observed.Guard(needed = true, "")))
                      case _ if again =>
                        target.observe(List(i -> r)).attempt.map(from)
                      case _ => IO.pure(from(seenOrError))
                    seenIO.flatMap(seen =>
                      (seen, contents(i)) match
                        case (Left(e), _) => finish(ApplyOutcome.Failed(e))
                        case (_, Left(e)) =>
                          finish(ApplyOutcome.Failed(s"its content: $e"))
                        case (Right(Observed.Guard(needed, note)), _)
                            if isGate =>
                          finish(
                            if needed then ApplyOutcome.Unchanged("")
                            else
                              ApplyOutcome.Skipped(
                                s"not for this node (${note.trim})"
                              )
                          )
                        case (Right(Observed.Guard(false, note)), _) =>
                          finish(ApplyOutcome.Unchanged(note))
                        case (Right(s), Right(content)) =>
                          val wanted = r match
                            case f: ApplyResource.File if f.content.nonEmpty =>
                              Some(content)
                            case _ => None
                          compare(
                            r,
                            s,
                            wanted,
                            target,
                            check,
                            notifiedIds
                          ).attempt.flatMap {
                            case Left(err) =>
                              finish(
                                ApplyOutcome.Failed(
                                  Option(err.getMessage).getOrElse(err.toString)
                                )
                              )
                            case Right(Left(e)) =>
                              finish(ApplyOutcome.Failed(e))
                            case Right(Right(d)) if d.changes.isEmpty =>
                              finish(ApplyOutcome.Unchanged(""))
                            case Right(Right(d)) if check =>
                              finish(
                                ApplyOutcome.WouldChange(d.changes, d.detail)
                              )
                            case Right(Right(d)) =>
                              val timeout = r match
                                case c: ApplyResource.Command => c.timeout
                                case _: ApplyResource.Package => 900
                                case _                        => 120
                              d.actions
                                .traverse_ {
                                  case Action.Write(p, t, o, g, m, h, ow) =>
                                    target.writeText(p, t, o, g, m, h, ow)
                                  case Action.Shell(script) =>
                                    target.shell(r.id, script, timeout)
                                  case Action.Typed(changes, fallback) =>
                                    target.typed(
                                      r.id,
                                      changes,
                                      fallback,
                                      timeout
                                    )
                                  case Action.Software(changes, fallback) =>
                                    target.software(
                                      r.id,
                                      changes,
                                      fallback,
                                      timeout
                                    )
                                  case Action.Access(changes, fallback) =>
                                    target.access(
                                      r.id,
                                      changes,
                                      fallback,
                                      timeout
                                    )
                                }
                                .attempt
                                .flatMap {
                                  case Right(()) =>
                                    finish(
                                      ApplyOutcome.Changed(d.changes, d.detail)
                                    )
                                  case Left(err) =>
                                    finish(
                                      ApplyOutcome.Failed(
                                        Option(err.getMessage)
                                          .getOrElse(err.toString)
                                      )
                                    )
                                }
                          }
                    )
        }
      }
      done <- outcomes.get
      // Still owed: notified (by a change now, or by an earlier run) and not delivered.
      owed = plan.order.toList
        .filter { i =>
          val skippedOrFailed = done(i) match
            case ApplyOutcome.Skipped(_) | _: ApplyOutcome.Failed => true
            case _                                                => false
          skippedOrFailed && plan.notifiers(i).nonEmpty && (
            before.contains(rs(i).id) ||
              plan
                .notifiers(i)
                .exists(j =>
                  done.get(j).exists {
                    case _: ApplyOutcome.Changed => true
                    case _                       => false
                  }
                )
          )
        }
        .map(rs(_).id)
        .toSet
      _ <-
        if check || owed == before then IO.unit
        else
          target
            .keepPendingNotices(plan.name, owed)
            .handleErrorWith(e =>
              say(
                s"could not record the notifications still owed: ${Option(e.getMessage).getOrElse(e.toString)}"
              )
            )
      _ <-
        if check || owed.isEmpty then IO.unit
        else
          say(
            s"still owed a notification, delivered by the next run: ${owed.toList.sorted.mkString(", ")}"
          )
      report = ApplyReport(
        target.node,
        plan.name,
        check,
        plan.order.toList.map(i => ResourceResult(rs(i).id, done(i)))
      )
      _ <- say(report.summary)
    yield report

  /** What the lines say when the notification is one an earlier run owed. */
  private val EarlierRun = "an earlier run"

  private def describe(
      r: ApplyResource,
      changes: List[String],
      would: Boolean,
      plan: ApplyPlan,
      i: Int,
      earlier: Boolean
  ): String =
    r match
      case _: ApplyResource.Command =>
        val by = plan.notifiers(i).toList.sorted.map(plan.resources(_).id) ++
          Option.when(earlier)(EarlierRun)
        val verb = if would then "would run" else "ran"
        if by.isEmpty then verb else s"$verb (notified by ${by.mkString(", ")})"
      case _ =>
        s"${if would then "would change" else "changed"}: ${changes.mkString(", ")}"

  // ---- the shell side of a real node --------------------------------------

  private val Marker = "@@orphera-apply "

  /** One shell script that reports every resource's state, each after a marker
    * line, so that a node is read in one call.
    */
  def observeScript(resources: List[(Int, ApplyResource)]): String =
    def pathState(i: Int, path: String): String =
      s"""echo '$Marker$i'
         |p=${quote(path)}
         |if [ -L "$$p" ]; then echo type=link; echo "target=$$(readlink "$$p")"
         |elif [ -f "$$p" ]; then echo type=file; stat -c 'mode=%a owner=%U group=%G' "$$p"; echo "sha256=$$(sha256sum "$$p" | cut -d' ' -f1)"
         |elif [ -d "$$p" ]; then echo type=directory; stat -c 'mode=%a owner=%U group=%G' "$$p"
         |elif [ -e "$$p" ]; then echo type=other
         |else echo type=absent; fi""".stripMargin
    def guard(i: Int, c: ApplyResource.Command): String =
      val test =
        if c.onlyIf.nonEmpty then s"sh -c ${quote(c.onlyIf)} >/dev/null 2>&1"
        else if c.unless.nonEmpty then
          s"! sh -c ${quote(c.unless)} >/dev/null 2>&1"
        else s"[ ! -e ${quote(c.creates)} ]"
      s"""echo '$Marker$i'
         |if $test; then echo guard=yes; else echo guard=no; fi""".stripMargin
    def pkg(i: Int, p: ApplyResource.Package): String =
      val n = quote(p.name)
      s"""echo '$Marker$i'
         |if command -v dpkg-query >/dev/null 2>&1 && command -v apt-get >/dev/null 2>&1; then echo manager=apt
         |  s=$$(dpkg-query -W -f='$${db:Status-Abbrev}|$${Version}' $n 2>/dev/null)
         |  case "$$s" in ?i*) echo installed=yes; echo "versions=$${s#*|}";; *) echo installed=no;; esac
         |elif command -v rpm >/dev/null 2>&1; then echo manager=dnf
         |  if v=$$(rpm -q --qf '%{VERSION}-%{RELEASE} ' $n 2>/dev/null); then echo installed=yes; echo "versions=$$(echo $$v | tr ' ' ',')"; else echo installed=no; fi
         |else echo manager=none; fi""".stripMargin
    def unit(i: Int, u: ApplyResource.Service): String =
      val n = quote(u.unit)
      s"""echo '$Marker$i'
         |e=$$(systemctl is-enabled $n 2>/dev/null); [ -n "$$e" ] || e=not-found
         |if [ "$$e" = not-found ] && systemctl cat $n >/dev/null 2>&1; then e=static; fi
         |echo "enabled=$$e"; echo "active=$$(systemctl is-active $n 2>/dev/null)"""".stripMargin
    def user(i: Int, u: ApplyResource.User): String =
      val n = quote(u.name)
      s"""echo '$Marker$i'
         |if e=$$(getent passwd $n); then echo exists=yes
         |  echo "uid=$$(echo "$$e" | cut -d: -f3)"; echo "home=$$(echo "$$e" | cut -d: -f6)"; echo "shell=$$(echo "$$e" | cut -d: -f7)"
         |  echo "group=$$(id -gn $n)"; echo "groups=$$(id -Gn $n | tr ' ' ',')"
         |else echo exists=no; fi""".stripMargin
    def group(i: Int, g: ApplyResource.Group): String =
      val n = quote(g.name)
      s"""echo '$Marker$i'
         |if e=$$(getent group $n); then echo exists=yes; echo "gid=$$(echo "$$e" | cut -d: -f3)"; else echo exists=no; fi""".stripMargin
    def sysctlObs(i: Int, v: ApplyResource.Sysctl): String =
      val k = quote(v.key)
      s"""echo '$Marker$i'
         |if x=$$(sysctl -n $k 2>/dev/null); then echo found=yes; echo "runtime=$$(echo $$x | tr -s '[:space:]' ' ')"; else echo found=no; fi
         |echo "persisted=$$(head -n1 ${quote(
          sysctlFile(v.key)
        )} 2>/dev/null)"""".stripMargin
    def moduleObs(i: Int, m: ApplyResource.KernelModule): String =
      val sys = quote(s"/sys/module/${m.name.replace('-', '_')}")
      s"""echo '$Marker$i'
         |if [ -d $sys ]; then echo loaded=yes; else echo loaded=no; fi
         |echo "persisted=$$(head -n1 ${quote(
          s"/etc/modules-load.d/orphera-${m.name}.conf"
        )} 2>/dev/null)"""".stripMargin
    def lineObs(i: Int, l: ApplyResource.LineInFile): String =
      s"""echo '$Marker$i'
         |if [ -f ${quote(l.path)} ]; then echo exists=yes
         |  LN=${quote(l.line)} RE=${quote(
          l.matching
        )} awk 'BEGIN{l=ENVIRON["LN"];r=ENVIRON["RE"]} $$0==l{e++} r!="" && $$0~r{m++} END{print "exact=" (e+0); print "matches=" (m+0)}' ${quote(
          l.path
        )}
         |else echo exists=no; fi""".stripMargin
    def keyObs(i: Int, k: ApplyResource.AuthorizedKey): String =
      val u = quote(k.user)
      s"""echo '$Marker$i'
         |if e=$$(getent passwd $u); then echo exists=yes; h=$$(echo "$$e" | cut -d: -f6)
         |  if [ -f "$$h/.ssh/authorized_keys" ]; then K=${quote(
          k.keyId
        )} awk '$$1" "$$2==ENVIRON["K"]{c++} END{print "count=" (c+0)}' "$$h/.ssh/authorized_keys"; else echo count=0; fi
         |else echo exists=no; fi""".stripMargin
    def mountObs(i: Int, m: ApplyResource.Mount): String =
      s"""echo '$Marker$i'
         |echo fstab=no
         |P=${quote(
          m.path
        )} awk '$$1!~/^#/ && $$2==ENVIRON["P"]{print "fstab=yes"; print "dev=" $$1; print "fstype=" $$3; print "opts=" $$4; exit}' /etc/fstab 2>/dev/null
         |if mountpoint -q ${quote(
          m.path
        )} 2>/dev/null; then echo mounted=yes; else echo mounted=no; fi""".stripMargin
    ("set +e" :: resources.map {
      case (i, f: ApplyResource.File)          => pathState(i, f.path)
      case (i, d: ApplyResource.Directory)     => pathState(i, d.path)
      case (i, s: ApplyResource.Symlink)       => pathState(i, s.path)
      case (i, c: ApplyResource.Command)       => guard(i, c)
      case (i, p: ApplyResource.Package)       => pkg(i, p)
      case (i, u: ApplyResource.Service)       => unit(i, u)
      case (i, u: ApplyResource.User)          => user(i, u)
      case (i, g: ApplyResource.Group)         => group(i, g)
      case (i, v: ApplyResource.Sysctl)        => sysctlObs(i, v)
      case (i, m: ApplyResource.KernelModule)  => moduleObs(i, m)
      case (i, l: ApplyResource.LineInFile)    => lineObs(i, l)
      case (i, k: ApplyResource.AuthorizedKey) => keyObs(i, k)
      case (i, m: ApplyResource.Mount)         => mountObs(i, m)
    }).mkString("\n")

  /** The states in `observeScript`'s output. */
  def parseObserved(
      resources: List[(Int, ApplyResource)],
      lines: Seq[String]
  ): Map[Int, Observed] =
    val blocks: Map[Int, Map[String, String]] =
      lines
        .foldLeft((Option.empty[Int], Map.empty[Int, Map[String, String]])) {
          case ((current, acc), raw) =>
            val l = raw.stripLineEnd
            if l.startsWith(Marker) then
              val i = l.drop(Marker.length).trim.toIntOption
              (i, i.fold(acc)(n => acc + (n -> Map.empty)))
            else
              current match
                case Some(i) =>
                  val kvs =
                    l.trim.split("\\s+(?=[a-z0-9]+=)").toList.flatMap { kv =>
                      kv.split("=", 2) match
                        case Array(k, v) => Some(k -> v)
                        case _           => None
                    }
                  (current, acc.updated(i, acc(i) ++ kvs))
                case None => (current, acc)
        }
        ._2
    observedFrom(resources, blocks)

  /** One thing to read on a node by the agent's `ObserveNode`, with no script:
    * `kind` is one of path, package, unit, user, group, sysctl, module,
    * file_text, key, mount (see Protocol.proto).
    */
  final case class Probe(kind: String, target: String, extra: String = "")

  /** What `ObserveNode` reads for a resource, or None for one that has no typed
    * read (a command's guard is a script, so it is read by `observeScript`).
    */
  def probe(r: ApplyResource): Option[Probe] = r match
    case f: ApplyResource.File      => Some(Probe("path", f.path))
    case d: ApplyResource.Directory => Some(Probe("path", d.path))
    case s: ApplyResource.Symlink   => Some(Probe("path", s.path))
    case p: ApplyResource.Package   => Some(Probe("package", p.name))
    case u: ApplyResource.Service   => Some(Probe("unit", u.unit))
    case u: ApplyResource.User      => Some(Probe("user", u.name))
    case g: ApplyResource.Group     => Some(Probe("group", g.name))
    case v: ApplyResource.Sysctl    =>
      Some(Probe("sysctl", v.key, sysctlFile(v.key)))
    case m: ApplyResource.KernelModule =>
      Some(
        Probe("module", m.name, s"/etc/modules-load.d/orphera-${m.name}.conf")
      )
    case l: ApplyResource.LineInFile    => Some(Probe("file_text", l.path))
    case k: ApplyResource.AuthorizedKey =>
      Some(Probe("key", k.user, k.keyId))
    case m: ApplyResource.Mount   => Some(Probe("mount", m.path))
    case _: ApplyResource.Command => None

  /** What the agent read for a line resource, as the lines `observeScript`
    * counts: how many are exactly the wanted line and how many match.
    */
  private def lineValues(
      l: ApplyResource.LineInFile,
      values: Map[String, String]
  ): Either[String, Map[String, String]] =
    if !values.get("exists").contains("yes") then
      Right(Map("exists" -> "no", "content" -> ""))
    else if values.get("truncated").contains("yes") then
      Left(s"${l.path} is too large to read here")
    else
      val content = values.getOrElse("content", "")
      val lines = records(content)
      Try(
        if l.matching.isEmpty then None
        else Some(java.util.regex.Pattern.compile(l.matching))
      ).toEither.left
        .map(e => s"match: not a regular expression: ${e.getMessage}")
        .map { re =>
          Map(
            "exists" -> "yes",
            "exact" -> lines.count(_ == l.line).toString,
            "matches" -> re
              .fold(0)(p => lines.count(x => p.matcher(x).find()))
              .toString,
            "content" -> content
          )
        }

  /** The states for what `ObserveNode` read: values by resource index, as the
    * script's lines come out. A resource whose read failed is left out of
    * `read`, which the engine reports as one that could not be read.
    */
  def observedFromAgent(
      resources: List[(Int, ApplyResource)],
      read: Map[Int, Map[String, String]]
  ): Map[Int, Observed] =
    val values = resources.flatMap { (i, r) =>
      read.get(i).flatMap { kv =>
        r match
          case l: ApplyResource.LineInFile =>
            lineValues(l, kv).toOption.map(i -> _)
          case _ => Some(i -> kv)
      }
    }.toMap
    observedFrom(resources, values)

  private def observedFrom(
      resources: List[(Int, ApplyResource)],
      blocks: Map[Int, Map[String, String]]
  ): Map[Int, Observed] =
    resources.flatMap { (i, r) =>
      blocks.get(i).map { kv =>
        def mode = kv
          .get("mode")
          .flatMap(m => Try(Integer.parseInt(m, 8)).toOption)
          .getOrElse(-1)
        val seen = r match
          case c: ApplyResource.Command =>
            val note =
              if c.onlyIf.nonEmpty then "onlyIf: false"
              else if c.unless.nonEmpty then "unless: true"
              else s"${c.creates} exists"
            kv.get("guard") match
              case Some("yes") => Observed.Guard(needed = true, "")
              case Some("no")  => Observed.Guard(needed = false, note)
              case _           => Observed.Unreadable(kv.mkString(" "))
          case _: ApplyResource.Package =>
            kv.get("manager") match
              case Some(m) =>
                Observed.Pkg(
                  m,
                  kv.get("installed").contains("yes"),
                  kv.getOrElse("versions", "")
                    .split(",")
                    .toList
                    .filter(_.nonEmpty)
                )
              case None => Observed.Unreadable(kv.mkString(" "))
          case _: ApplyResource.Service =>
            kv.get("enabled") match
              case Some(e) =>
                Observed.ServiceState(e, kv.getOrElse("active", ""))
              case None => Observed.Unreadable(kv.mkString(" "))
          case _: ApplyResource.User =>
            kv.get("exists") match
              case Some("no")  => Observed.Absent
              case Some("yes") =>
                Observed.Account(
                  kv.get("uid").flatMap(_.toIntOption).getOrElse(-1),
                  kv.getOrElse("group", ""),
                  kv.getOrElse("groups", "")
                    .split(",")
                    .toList
                    .filter(_.nonEmpty),
                  kv.getOrElse("home", ""),
                  kv.getOrElse("shell", "")
                )
              case _ => Observed.Unreadable(kv.mkString(" "))
          case _: ApplyResource.Group =>
            kv.get("exists") match
              case Some("no")  => Observed.Absent
              case Some("yes") =>
                Observed.GroupEntry(
                  kv.get("gid").flatMap(_.toIntOption).getOrElse(-1)
                )
              case _ => Observed.Unreadable(kv.mkString(" "))
          case _: ApplyResource.Sysctl | _: ApplyResource.KernelModule |
              _: ApplyResource.LineInFile | _: ApplyResource.AuthorizedKey |
              _: ApplyResource.Mount =>
            Observed.Facts(kv)
          case _ =>
            kv.get("type") match
              case Some("absent") => Observed.Absent
              case Some("file")   =>
                Observed.RegularFile(
                  mode,
                  kv.getOrElse("owner", ""),
                  kv.getOrElse("group", ""),
                  kv.getOrElse("sha256", "")
                )
              case Some("directory") =>
                Observed.Dir(
                  mode,
                  kv.getOrElse("owner", ""),
                  kv.getOrElse("group", "")
                )
              case Some("link")  => Observed.Link(kv.getOrElse("target", ""))
              case Some("other") => Observed.OtherType
              case _             => Observed.Unreadable(kv.mkString(" "))
        i -> seen
      }
    }.toMap

/** A unified-style line diff, for showing a file's change. */
object TextDiff:
  private val MaxLines = 4000
  private val MaxShown = 200

  /** `---`/`+++` header and the removed (`-`) and added (`+`) lines, with up to
    * two unchanged lines of context around each change.
    */
  def unified(before: String, after: String, path: String): List[String] =
    val a = before.linesIterator.toVector
    val b = after.linesIterator.toVector
    if a.size > MaxLines || b.size > MaxLines then
      List(s"(too long to show: ${a.size} -> ${b.size} lines)")
    else
      // LCS table, then walk it.
      val lcs = Array.ofDim[Int](a.size + 1, b.size + 1)
      for i <- a.indices.reverse; j <- b.indices.reverse do
        lcs(i)(j) = if a(i) == b(j) then lcs(i + 1)(j + 1) + 1
        else lcs(i + 1)(j) max lcs(i)(j + 1)
      val ops = Vector.newBuilder[(Char, String)]
      var i = 0
      var j = 0
      while i < a.size || j < b.size do
        if i < a.size && j < b.size && a(i) == b(j) then {
          ops += (' ' -> a(i)); i += 1; j += 1
        }
        // On a tie the removed line comes first, as in diff -u.
        else if i < a.size && (j == b.size || lcs(i + 1)(j) >= lcs(i)(j + 1))
        then { ops += ('-' -> a(i)); i += 1 }
        else { ops += ('+' -> b(j)); j += 1 }
      val all = ops.result()
      val near = all.indices
        .filter(k => all(k)._1 != ' ')
        .flatMap(k => (k - 2) to (k + 2))
        .toSet
      val shown = all.indices
        .filter(near.contains)
        .map(k => s"${all(k)._1}${all(k)._2}")
        .toList
      val body = if shown.size > MaxShown then
        shown.take(MaxShown) :+ s"... (${shown.size - MaxShown} more lines)"
      else shown
      s"--- $path (now)" :: s"+++ $path (wanted)" :: body
