// SPDX-License-Identifier: Apache-2.0

import orphera.orchestrator.*
import orphera.orchestrator.ClusterPlaybookDsl.*

// Turns an empty test VM into a control and build host equivalent to scala0:
// the toolchain, a checkout of this repository in ~localadmin/orphera-pkg, and
// `orphera` built and installed from it (make orpheracli).
//
//   manifests/mk-new-and-empty.sh 15                      # the VM, agent, DNS, base packages
//   orphera cluster-playbook control_host.scala --config tst15
//   orphera cluster-playbook control_host_handover.scala --config tst15
//
// The target is the config's `hostname`, which must be in inventory.yaml with a
// running agent (mk-new-and-empty.sh leaves it that way).
//
// What scala0 has, and what this installs for it:
//   build (sbt, Scala agent)  openjdk-21-jdk-headless, sbt from the scala-sbt apt
//                             repository (the project pins sbt 1.10.7 itself in
//                             project/build.properties; Scala 3.8.4 and protoc are
//                             fetched by sbt on the first build)
//   packages                  make, dpkg-dev (dpkg-deb), rpm (rpmbuild), openssl
//   Go agent (agent-go/)      golang-go (1.22, as go.mod asks), protobuf-compiler,
//                             protoc-gen-go v1.35.2 and protoc-gen-go-grpc v1.5.1
//                             (agent-go/gen.sh), staticcheck 2024.1.1 (the last
//                             release that builds with Go 1.22)
//   orphera itself            age (secret store), openssh-client, git
//   utils/                    python3-yaml, python3-jsonschema (schemas, SQL round
//                             trip), python3-venv (openstack-client-setup.sh),
//                             mariadb-client (the read-only database copy), jq,
//                             shellcheck, bash-completion
//
// scala0 runs Oracle GraalVM 21.0.9; this uses Ubuntu's OpenJDK 21, which the
// README says builds and runs the jars the same. GraalVM only matters for
// native-image, which is not in use.
//
// Not done here: anything secret. The CA, the secret store and the SSH key are
// copied by control_host_handover.scala, kept separate so that building the
// host and giving it the keys to the fleet are two decisions.
//
// Safe to re-run: the repository is cloned only if ~/orphera-pkg is missing (an
// existing checkout is left as it is, so local work is never lost), the Go tools
// are reinstalled at the same versions, and `make orpheracli` rebuilds from
// whatever is checked out.
object control_host extends OrpheraClusterPlaybook:

  private val params: VmParams =
    ConfigYaml.fromEnv() match
      case Some(Right(p))  => p
      case Some(Left(err)) =>
        throw new RuntimeException(s"--config given but failed to load: $err")
      case None =>
        throw new RuntimeException(
          "control_host requires --config <vm config> (e.g. --config tst15)"
        )

  private val host = params.hostname
  private val user = "localadmin"
  private val repoUrl = "https://github.com/ljalbinson/orphera-pkg.git"

  // Runs a script as localadmin with a login shell, so ~/.profile (and the Go
  // bin directory it adds to PATH) applies, the same as an interactive session.
  private def asUser(script: String, timeout: Int): Task =
    Task.RunCommand(
      List("sudo", "-u", user, "-H", "bash", "-lc", script),
      timeoutSeconds = timeout
    )

  private val sbtRepoScript =
    """set -e
      |install -d -m 755 /etc/apt/keyrings
      |if [ ! -s /etc/apt/keyrings/scalasbt.gpg ]; then
      |  curl -fsSL 'https://keyserver.ubuntu.com/pks/lookup?op=get&search=0x2EE0EA64E40A89B84B2DF73499E82A75642AC823' \
      |    | gpg --dearmor -o /etc/apt/keyrings/scalasbt.gpg
      |  chmod 644 /etc/apt/keyrings/scalasbt.gpg
      |fi
      |echo 'deb [signed-by=/etc/apt/keyrings/scalasbt.gpg] https://repo.scala-sbt.org/scalasbt/debian all main' \
      |  > /etc/apt/sources.list.d/sbt.list
      |echo "sbt apt repository configured"""".stripMargin

  private val packages = List(
    // build
    "openjdk-21-jdk-headless",
    "sbt",
    "git",
    "make",
    "dpkg-dev",
    "rpm",
    "openssl",
    // Go agent
    "golang-go",
    "protobuf-compiler",
    // running orphera
    "age",
    "openssh-client",
    // utils/
    "python3-venv",
    "python3-yaml",
    "python3-jsonschema",
    "mariadb-client",
    "jq",
    "shellcheck",
    "bash-completion",
    "curl",
    "gnupg"
  )

  private val goToolsScript =
    """set -e
      |grep -q 'HOME/go/bin' ~/.profile || echo 'PATH="$HOME/go/bin:$PATH"' >> ~/.profile
      |export PATH="$HOME/go/bin:$PATH"
      |go install google.golang.org/protobuf/cmd/protoc-gen-go@v1.35.2
      |go install google.golang.org/grpc/cmd/protoc-gen-go-grpc@v1.5.1
      |go install honnef.co/go/tools/cmd/staticcheck@2024.1.1
      |ls ~/go/bin""".stripMargin

  private val cloneScript =
    s"""set -e
       |if [ -d ~/orphera-pkg/.git ]; then
       |  echo "~/orphera-pkg already exists; left as it is"
       |else
       |  git clone $repoUrl ~/orphera-pkg
       |fi
       |git -C ~/orphera-pkg log -1 --format='checked out: %h %s'""".stripMargin

  // make orpheracli runs `sudo cp`/`sudo install` itself; localadmin has
  // passwordless sudo on these VMs (cloud-init). The first build downloads the
  // sbt launcher, Scala and every dependency, hence the long timeout.
  private val buildScript =
    """set -e
      |cd ~/orphera-pkg
      |make orpheracli
      |ls -l /usr/local/lib/orphera-orchestrator.jar /usr/local/lib/orphera-scripting.jar /usr/local/bin/orphera""".stripMargin

  // The Go agent's own checks (gofmt, go vet, staticcheck, tests) and binaries:
  // proves the Go toolchain matches what agent-go/ needs.
  private val goBuildScript =
    """set -e
      |cd ~/orphera-pkg/agent-go
      |make
      |ls -l bin/""".stripMargin

  private val verifyScript =
    """set -e
      |java -version 2>&1 | head -1
      |go version
      |protoc --version
      |age --version
      |rpmbuild --version
      |dpkg-deb --version | head -1
      |mariadb --version
      |python3 -c 'import yaml, jsonschema; print("python3: yaml and jsonschema import")'
      |command -v orphera
      |cd ~/orphera-pkg && echo "orphera-pkg at $(git rev-parse --short HEAD), VERSION $(cat VERSION)"""".stripMargin

  val playbook: ClusterPlaybook =
    clusterPlaybook("control-host")(
      stage("sbt-apt-repository", host)
        .task("add the scala-sbt apt repository and its key")(
          Task.RunCommand(List("sh", "-c", sbtRepoScript), timeoutSeconds = 120)
        )
        .build,
      stage("toolchain-packages", host)
        .task(s"install ${packages.size} packages")(
          Task.Install(packages, updateCache = true)
        )
        .build,
      stage("go-tools", host)
        .task("protoc-gen-go, protoc-gen-go-grpc, staticcheck for localadmin")(
          asUser(goToolsScript, 900)
        )
        .build,
      stage("orphera-checkout", host)
        .task(s"clone $repoUrl into ~$user/orphera-pkg")(
          asUser(cloneScript, 600)
        )
        .build,
      stage("build-and-install-orphera", host)
        .task("make orpheracli (assembly jars, /usr/local/bin/orphera)")(
          asUser(buildScript, 3600)
        )
        .task("build and check the Go agent (agent-go: make)")(
          asUser(goBuildScript, 1200)
        )
        .build,
      stage("verify", host)
        .task("report tool versions and the installed orphera")(
          asUser(verifyScript, 120)
        )
        .build
    )
