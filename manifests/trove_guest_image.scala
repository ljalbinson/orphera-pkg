// SPDX-License-Identifier: Apache-2.0

import orphera.orchestrator.*

// OpenStack Trove, part 2 of two, the guest image on the builder tst21, as a declarative description
// (OrpheraApply, docs/APPLY-DESIGN.md). It replaced the first stage of the cluster playbook of the same name;
// the datastore registration on tst25 is trove_datastore.scala. A second run changes nothing, and `--check`
// shows what would change.
//
//   orphera apply manifests/trove_guest_image.scala      # builds the image, uploads it, makes the flavor (tst21)
//   orphera apply manifests/trove_datastore.scala        # registers the mysql datastore (tst25)
//
// What a Trove guest is: a Nova server booted from a guest image that holds the Trove guest agent and Docker.
// The agent starts the database as a container (here MySQL, pulled from Docker Hub when an instance is made)
// and reports to the conductor over RabbitMQ. So the image only has to contain the agent and Docker.
//
// What this does on tst21:
//   - installs diskimage-builder (a virtualenv in /opt/diskimage-builder-env) and its host tools, and takes the
//     elements from the Trove source (/opt/trove-src, branch stable/2025.1);
//   - builds an Ubuntu noble image with `disk-image-create` using the elements that `trovestack build-image`
//     uses (base vm ubuntu-minimal cloud-init-datasources pip-cache guest-agent ubuntu-docker), with
//     DEV_MODE=false so the agent code is put into the image at build time (the guest-agent element clones
//     Trove stable/2025.1 and its constraints file from opendev.org). The image is /var/lib/orphera/trove-image/
//     trove-guest-ubuntu-noble.qcow2 (5 GB). It is not built while the file exists; TROVE_REBUILD_IMAGE=1
//     builds it again and replaces the Glance image (and does so on every run for as long as it is set);
//   - uploads it to Glance (public, tags `trove` and `mysql`, hw_rng_model virtio), skipped while an active
//     image with those tags is there, and makes the Nova flavor orphera.trove (id orphera-trove; 1 vCPU,
//     2048 MB, 10 GB), skipped while it exists. They are openstackImage and openstackFlavor: the helpers
//     `orphera-image-trove-guest-ubuntu-noble` and `orphera-flavor-orphera-trove` hold the description, and their
//     `check` is the read-only test.
//
// Prerequisites, not checked here: trove_single_node.scala has run; tst21 reaches GitHub, opendev.org,
// download.docker.com and the Ubuntu mirrors (the build downloads them).
//
// Not in this version: other datastores (MariaDB, PostgreSQL), backups, replication.
//
// Facts this was written from, to check if a run fails: the build is the one in
// trove/integration/scripts/functions_qemu (build_guest_image) of stable/2025.1.
object trove_guest_image extends OrpheraApply:
  import Resources.*

  private val buildNode = "tst21"

  override def nodes: List[String] = List(buildNode)

  private val ksFqdn = "tst6.ljalbinson.com"
  private val ksUrl = s"https://$ksFqdn:5000"

  private val adminPassword = Secrets.secret("keystone/admin")

  private val troveBranch = "stable/2025.1"
  private val srcDir = "/opt/trove-src"
  private val venvDir = "/opt/diskimage-builder-env"
  private val imageDir = "/var/lib/orphera/trove-image"
  private val cacheDir = "/var/cache/orphera-trove-dib"
  private val imageName = "trove-guest-ubuntu-noble"
  private val imageFile = s"$imageDir/$imageName.qcow2"
  private val caFile = s"$imageDir/ca.crt"
  private val flavorId = "orphera-trove"
  private val flavorName = "orphera.trove"

  private val rebuild = sys.env.get("TROVE_REBUILD_IMAGE").contains("1")
  // Shell text, not an s-string: `$OUT` is the shell variable of buildScript.
  private val removeOldImage = if rebuild then "rm -f \"$OUT\"" else ":"

  private val buildPackages = List(
    "qemu-utils",
    "git",
    "kpartx",
    "debootstrap",
    "squashfs-tools",
    "python3-pip",
    "python3-setuptools",
    "python3-venv",
    "parted",
    "gdisk",
    "dosfstools",
    "openssl",
    "curl",
    "ca-certificates"
  )

  // ---- tst21: the image ------------------------------------------------

  private val prepareScript =
    s"""set -e
       |mkdir -p $imageDir $cacheDir
       |if [ ! -x $venvDir/bin/disk-image-create ]; then
       |  python3 -m venv $venvDir
       |  $venvDir/bin/pip install --upgrade pip setuptools
       |  $venvDir/bin/pip install diskimage-builder
       |fi
       |if [ ! -d $srcDir/.git ]; then
       |  git clone -q --depth 1 -b $troveBranch https://github.com/openstack/trove $srcDir
       |fi
       |ELEM=$srcDir/integration/scripts/files/elements
       |for e in guest-agent ubuntu-docker ubuntu-guest; do
       |  [ -d "$$ELEM/$$e" ] || { echo "element $$e is missing in $$ELEM" >&2; exit 1; }
       |done
       |echo "diskimage-builder $$($venvDir/bin/pip show diskimage-builder | awk '/^Version/{print $$2}'), Trove elements in $$ELEM"""".stripMargin

  // Done when the virtualenv has disk-image-create, the Trove source is cloned and the elements are there.
  private val prepared =
    s"""[ -x $venvDir/bin/disk-image-create ] && [ -d $srcDir/.git ] && for e in guest-agent ubuntu-docker ubuntu-guest; do [ -d "$srcDir/integration/scripts/files/elements/$$e" ] || exit 1; done"""

  // The variables are those of build_guest_image in functions_qemu; the guest-agent install script runs with
  // `set -u` and reads SYNC_LOG_TO_CONTROLLER and DEV_MODE, so they must be set.
  private val buildScript =
    s"""set -e
       |OUT=$imageFile
       |$removeOldImage
       |if [ -s "$$OUT" ]; then
       |  echo "guest image already built: $$OUT ($$(du -h "$$OUT" | cut -f1)); set TROVE_REBUILD_IMAGE=1 to build it again"
       |  exit 0
       |fi
       |. $venvDir/bin/activate
       |export ELEMENTS_PATH=$srcDir/integration/scripts/files/elements
       |export DIB_RELEASE=noble DISTRO_NAME=ubuntu
       |export DIB_CLOUD_INIT_DATASOURCES="ConfigDrive, OpenStack" DIB_CLOUD_INIT_ETC_HOSTS=localhost
       |export GUEST_USERNAME=ubuntu HOST_SCP_USERNAME=root ESCAPED_PATH_TROVE=unused DEV_MODE=false SYNC_LOG_TO_CONTROLLER=False
       |LOG=$imageDir/build.log
       |echo "building $$OUT (about 10 to 30 minutes); log: $$LOG"
       |if ! disk-image-create -a amd64 -o "$$OUT" -t qcow2 --image-size 5 --image-cache $cacheDir base vm ubuntu-minimal cloud-init-datasources pip-cache guest-agent ubuntu-docker >"$$LOG" 2>&1; then
       |  rm -f "$$OUT"
       |  echo "disk-image-create failed; the end of $$LOG:" >&2
       |  tail -60 "$$LOG" >&2
       |  exit 1
       |fi
       |[ -s "$$OUT" ] || { echo "disk-image-create produced no $$OUT; the end of $$LOG:" >&2; tail -30 "$$LOG" >&2; exit 1; }
       |qemu-img info "$$OUT" | sed -n '1,6p'
       |echo "guest image built: $$OUT"""".stripMargin

  def describe(target: Node): List[ApplyResource] =
    val tools = buildPackages.map(p => pkg(p, updateCache = true))

    val imageDirectory = directory(imageDir)
    val cacheDirectory = directory(cacheDir)
    val prepare = command(
      "diskimage-builder and the Trove elements",
      prepareScript,
      unless = prepared,
      timeout = 900
    ).after(tools*)
    val ca = serverCa(
      "fetch Keystone's CA from its TLS handshake",
      ksFqdn,
      5000,
      caFile,
      trust = false
    ).after(imageDirectory)
    val build = command(
      "build the Ubuntu noble guest image",
      buildScript,
      // With TROVE_REBUILD_IMAGE=1 the script deletes the file and builds again, whatever is there.
      unless = if rebuild then "" else s"[ -s $imageFile ]",
      timeout = 3600
    ).after((prepare :: imageDirectory :: cacheDirectory :: Nil)*)

    val (imageHelper, ensuredImage) = openstackImage(
      "upload the image to Glance",
      imageName,
      imageFile,
      ksUrl,
      adminPassword,
      caFile,
      tags = List("trove", "mysql"),
      properties = Map("hw_rng_model" -> "virtio"),
      replace = rebuild,
      timeout = 3600
    )
    val uploaded = ensuredImage.after(build, ca)
    val (flavorHelper, ensuredFlavor) = openstackFlavor(
      "make the flavor orphera.trove",
      flavorId,
      flavorName,
      2048, // MB
      1, // vCPU
      10, // GB
      ksUrl,
      adminPassword,
      caFile
    )
    val flavor = ensuredFlavor.after(ca)

    tools ++ List(
      imageDirectory,
      cacheDirectory,
      prepare,
      ca,
      build,
      imageHelper,
      uploaded,
      flavorHelper,
      flavor
    )
