// SPDX-License-Identifier: Apache-2.0

import orphera.orchestrator.*
import orphera.orchestrator.PlaybookDsl.*

// A file server for Linux and macOS clients, on one Ubuntu or Debian node.
//
// It uses Samba (SMB3). macOS Finder and Linux (`mount -t cifs`, `smbclient`, a file manager) all speak it,
// where NFS is awkward on macOS. The macOS extras are Samba's `fruit` module (Finder metadata, resource
// forks and Spotlight-friendly names kept in extended attributes, so files copied from a Mac keep them) and
// Avahi, which announces the server on the local network so that it appears in Finder's sidebar without
// typing an address. Optionally the server is also a Time Machine target.
//
// Run:     FILE_SERVER_NODE=fs0 FILE_SERVER_USERS="alice bob" orphera playbook file_server.scala
// Then:    macOS Finder, Go > Connect to Server (Cmd-K):  smb://fs0.example.com/files
//          Linux:  sudo mount -t cifs //fs0.example.com/files /mnt/files -o username=alice,vers=3.0,uid=$USER
//
// Settings, from the environment (so the file is not edited per site):
//   FILE_SERVER_NODE         the inventory name of the node; default fs0
//   FILE_SERVER_USERS        space- or comma-separated user names that may use the share; default localadmin
//   FILE_SERVER_TIMEMACHINE  "yes" adds a Time Machine share, backups of every user in it; default no
// More below as constants (share names, directories, workgroup, Time Machine size limit).
//
// Users: a name that is not already an account on the node gets a login-less one (no home directory, no
// shell, so it can use the share and nothing else). Each user's Samba password is the secret
// `fileserver/<user>` in the secret store, made at random the first time and printed by
// `orphera secrets get fileserver/<user>`; set your own first with `orphera secrets set` to choose it.
// Running the playbook again leaves everything in place and sets each Samba password to the stored one.
//
// Not changed: the node's firewall beyond allowing Samba and mDNS when ufw is active; the disk the share
// sits on (/srv is on the root filesystem unless you mount something there first, which is advised for
// a real server); backups of the share itself.
object file_server extends OrpheraPlaybook:

  private val node = sys.env.getOrElse("FILE_SERVER_NODE", "fs0")
  private val users = sys.env
    .getOrElse("FILE_SERVER_USERS", "localadmin")
    .split("[ ,]+")
    .toList
    .filter(_.nonEmpty)
  private val timeMachine = sys.env
    .get("FILE_SERVER_TIMEMACHINE")
    .exists(v => v.equalsIgnoreCase("yes") || v.equalsIgnoreCase("true"))

  private val group = "fileusers"
  private val workgroup = "WORKGROUP"
  private val shareName = "files"
  private val sharePath = "/srv/files"
  private val timeMachineName = "timemachine"
  private val timeMachinePath = "/srv/timemachine"
  private val timeMachineSize = "1T"

  // A Unix account name: what useradd accepts without a warning. Anything else is refused before it can
  // reach a shell command.
  private val validUser = "[a-z_][a-z0-9_-]{0,31}".r
  private val badUsers = users.filterNot(u => validUser.matches(u))

  private def quoted(s: String): String = "'" + s.replace("'", "'\\''") + "'"

  private val packages = List("samba", "smbclient", "avahi-daemon")

  private val userList = users.mkString(" ")

  // Accounts and the share directories. Each user's lines are written out, with the password from the
  // store; the password is on the node's command line while this runs (see "Secrets" in the README).

  private val accountsScript: String =
    if badUsers.nonEmpty then
      val shown = badUsers.mkString(" ").replaceAll("[^A-Za-z0-9_ -]", "?")
      s"""echo "FILE_SERVER_USERS has names that are not valid account names: $shown" >&2
         |exit 1""".stripMargin
    else
      val perUser = users
        .map { u =>
          val pw = quoted(Secrets.secret(s"fileserver/$u").reveal)
          s"""id -u $u >/dev/null 2>&1 || useradd --no-create-home --shell /usr/sbin/nologin $u
             |usermod -aG $group $u
             |printf '%s\\n%s\\n' $pw $pw | smbpasswd -s -a $u""".stripMargin
        }
        .mkString("\n")
      val timeMachineDir =
        if timeMachine then s"""mkdir -p $timeMachinePath
             |chgrp $group $timeMachinePath
             |chmod 2770 $timeMachinePath""".stripMargin
        else ""
      s"""set -e
         |getent group $group >/dev/null || groupadd $group
         |$perUser
         |mkdir -p $sharePath
         |chgrp $group $sharePath
         |chmod 2775 $sharePath
         |$timeMachineDir
         |[ -f /etc/samba/smb.conf.orig ] || cp -p /etc/samba/smb.conf /etc/samba/smb.conf.orig
         |echo "accounts and directories ready for: $userList"""".stripMargin

  // The macOS settings are the ones the Samba project documents for vfs_fruit. `multicast dns register = no`
  // leaves announcing to Avahi, so the two do not both answer. Printing is off: nothing here shares a printer.
  private val timeMachineShare: String =
    if timeMachine then s"""
       |[$timeMachineName]
       |   path = $timeMachinePath
       |   valid users = @$group
       |   read only = no
       |   browseable = yes
       |   create mask = 0660
       |   force create mode = 0660
       |   directory mask = 2770
       |   force directory mode = 2770
       |   force group = $group
       |   vfs objects = fruit streams_xattr
       |   fruit:time machine = yes
       |   fruit:time machine max size = $timeMachineSize
       |""".stripMargin
    else ""

  private val smbConf: String =
    s"""# Managed by Orphera (file_server.scala). The original is /etc/samba/smb.conf.orig.
       |[global]
       |   workgroup = $workgroup
       |   server string = %h file server
       |   server role = standalone server
       |   security = user
       |   map to guest = never
       |   server min protocol = SMB2
       |   multicast dns register = no
       |   load printers = no
       |   printing = bsd
       |   printcap name = /dev/null
       |   disable spoolss = yes
       |   log file = /var/log/samba/log.%m
       |   max log size = 1000
       |   vfs objects = fruit streams_xattr
       |   fruit:metadata = stream
       |   fruit:model = MacSamba
       |   fruit:posix_rename = yes
       |   fruit:veto_appledouble = no
       |   fruit:nfs_aces = no
       |   fruit:wipe_intentionally_left_blank_rfork = yes
       |   fruit:delete_empty_adfiles = yes
       |
       |[$shareName]
       |   path = $sharePath
       |   valid users = @$group
       |   read only = no
       |   browseable = yes
       |   create mask = 0664
       |   force create mode = 0664
       |   directory mask = 2775
       |   force directory mode = 2775
       |   force group = $group
       |$timeMachineShare""".stripMargin

  // What Avahi announces: the SMB service, an icon for Finder (a rack server), and, for Time Machine, the
  // `_adisk` record that makes the share a backup destination.
  private val timeMachineAdvert: String =
    if timeMachine then s"""
       |  <service>
       |    <type>_adisk._tcp</type>
       |    <txt-record>sys=waMa=0,adVF=0x100</txt-record>
       |    <txt-record>dk0=adVN=$timeMachineName,adVF=0x82</txt-record>
       |  </service>""".stripMargin
    else ""

  private val avahiService: String =
    s"""<?xml version="1.0" standalone='no'?>
       |<!DOCTYPE service-group SYSTEM "avahi-service.dtd">
       |<!-- Managed by Orphera (file_server.scala) -->
       |<service-group>
       |  <name replace-wildcards="yes">%h</name>
       |  <service>
       |    <type>_smb._tcp</type>
       |    <port>445</port>
       |  </service>
       |  <service>
       |    <type>_device-info._tcp</type>
       |    <port>0</port>
       |    <txt-record>model=RackMac</txt-record>
       |  </service>$timeMachineAdvert
       |</service-group>
       |""".stripMargin

  // testparm refuses a configuration Samba would not load, so a typo stops the playbook before the restart.
  private val startScript: String =
    s"""set -e
       |testparm -s /etc/samba/smb.conf >/dev/null
       |systemctl enable smbd avahi-daemon
       |systemctl restart smbd avahi-daemon
       |sleep 2
       |systemctl is-active --quiet smbd || { echo "smbd is not running: journalctl -xeu smbd" >&2; exit 1; }
       |systemctl is-active --quiet avahi-daemon || { echo "avahi-daemon is not running: journalctl -xeu avahi-daemon" >&2; exit 1; }
       |if command -v ufw >/dev/null 2>&1 && ufw status | grep -q '^Status: active'; then
       |  ufw allow Samba >/dev/null
       |  ufw allow 5353/udp >/dev/null
       |  echo "ufw: Samba and mDNS allowed"
       |fi
       |echo "smbd and avahi-daemon running"""".stripMargin

  // A real login, a listing and a file written, read back and removed, through the share the way a client
  // does it. The first user is enough: the others have the same group and the same share.
  private val checkScript: String =
    users.filter(u => validUser.matches(u)).headOption match
      case None =>
        """echo "no users, nothing to check" >&2
          |exit 1""".stripMargin
      case Some(u) =>
        val login = quoted(s"$u%" + Secrets.secret(s"fileserver/$u").reveal)
        s"""set -e
           |smbclient -L localhost -U $login | grep -q '$shareName'
           |echo orphera-file-server-check > /tmp/orphera-fs-check.txt
           |smbclient //localhost/$shareName -U $login -c 'put /tmp/orphera-fs-check.txt orphera-fs-check.txt; get orphera-fs-check.txt /tmp/orphera-fs-check.back; del orphera-fs-check.txt' >/dev/null
           |cmp /tmp/orphera-fs-check.txt /tmp/orphera-fs-check.back
           |rm -f /tmp/orphera-fs-check.txt /tmp/orphera-fs-check.back
           |echo "share '$shareName' listed, and a file written, read back and removed as $u"""".stripMargin

  private val howToConnect: String =
    s"On macOS: Finder, Go, Connect to Server (Cmd-K): smb://$node/$shareName (the server also appears in the sidebar under Network). " +
      s"On Linux: sudo mount -t cifs //$node/$shareName /mnt/$shareName -o username=<user>,vers=3.0. " +
      "Passwords: orphera secrets get fileserver/<user>."

  val playbook: Playbook =
    PlaybookDsl
      .playbook("file-server", node)
      .task("install samba and avahi")(
        Task.Install(packages = packages, updateCache = true)
      )
      .task("make the accounts, the group and the share directories")(
        Task.RunCommand(List("sh", "-c", accountsScript), timeoutSeconds = 120)
      )
      .task("write smb.conf")(
        Task.WriteFile(
          content = smbConf,
          dest = "/etc/samba/smb.conf",
          owner = "root",
          group = "root",
          mode = 420
        )
      )
      .task("announce the server with avahi")(
        Task.WriteFile(
          content = avahiService,
          dest = "/etc/avahi/services/orphera-fileserver.service",
          owner = "root",
          group = "root",
          mode = 420
        )
      )
      .task("check the configuration and start the services")(
        Task.RunCommand(List("sh", "-c", startScript), timeoutSeconds = 120)
      )
      .task("check the share by using it")(
        Task.RunCommand(List("sh", "-c", checkScript), timeoutSeconds = 60)
      )
      .task("how to connect")(Task.Debug(howToConnect))
      .build
