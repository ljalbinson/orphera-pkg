// SPDX-License-Identifier: Apache-2.0

import java.nio.file.{Files, Path, Paths}
import orphera.orchestrator.*
import orphera.orchestrator.ClusterPlaybookDsl.*

// Turns an empty VM (runner0) into a GitLab runner that can run `orphera` from
// pipelines in the lab GitLab. See docs/GITLAB-RUNNER.md for why the runner is
// not a copy of the control host: it gets an operator certificate and the agent
// token, not the CA key, the age identity or an SSH key.
//
//   manifests/mk-new-and-empty.sh runner0                       # the VM, agent, DNS, base packages
//   orphera issue-client-cert --operator ci --role pipeline --days 30
//   orphera secrets set gitlab/runner-token                     # the glrt- token from GitLab
//   GITLAB_URL=https://gitlab.example orphera cluster-playbook gitlab_runner.scala --config runner0
//
// Before anything is sent, every local input must exist; a missing one stops
// the playbook here, on scala0, saying what to make:
//   - certs/ca.crt, certs/operators/ci.crt and ci.key (orphera issue-client-cert)
//   - orphera_<VERSION>_all.deb, the command-line package (make cli-package), or
//     ORPHERA_CLI_DEB=<file>
//   - the secret gitlab/runner-token, and orphera/agent-token (for the check)
//   - GITLAB_URL, the address of the lab GitLab
//   - GITLAB_CA=<file>, only if GitLab's certificate comes from a private CA:
//     copied to the runner and given to `gitlab-runner register --tls-ca-file`
//
// What it does on the VM: Java, git and age; GitLab's apt repository and the
// gitlab-runner package; the orphera command-line package; the CI operator's
// certificates in ~gitlab-runner/.config/orphera/certs; the runner registered
// with a shell executor (once: an existing registration for the URL is kept);
// then a check that the runner is registered and that `orphera version` works as
// the gitlab-runner user with the operator certificate.
//
// What it does not do, because GitLab owns it: make the runner in GitLab (tags,
// "Protected", whether it runs untagged jobs are settings of the runner there),
// and set the CI/CD variables ORPHERA_TOKEN, ORPHERA_OPERATOR and
// ORPHERA_CERTS_DIR. docs/GITLAB-RUNNER.md lists them.
//
// Safe to re-run: packages and files are compared, the repository key is
// fetched only if missing, and the runner is registered only if its URL is not
// already in /etc/gitlab-runner/config.toml. A new certificate or token is
// picked up by running it again; to register against a new runner token,
// `sudo gitlab-runner unregister --all-runners` on the VM first.
object gitlab_runner extends OrpheraClusterPlaybook:

  private val params: VmParams =
    ConfigYaml.fromEnv() match
      case Some(Right(p))  => p
      case Some(Left(err)) =>
        throw new RuntimeException(s"--config given but failed to load: $err")
      case None =>
        throw new RuntimeException(
          "gitlab_runner requires --config <vm config> (e.g. --config runner0)"
        )

  private val host = params.hostname
  private val runnerUser = "gitlab-runner"
  private val runnerHome = s"/home/$runnerUser"
  private val operator = "ci"

  private val gitlabUrl: String =
    sys.env
      .get("GITLAB_URL")
      .map(_.trim.stripSuffix("/"))
      .filter(_.nonEmpty)
      .getOrElse(
        throw new RuntimeException(
          "GITLAB_URL is not set: the address of the lab GitLab, " +
            "e.g. GITLAB_URL=https://gitlab.example"
        )
      )

  private val certsDir: Path =
    Paths.get(sys.env.getOrElse("ORPHERA_CERTS_DIR", "certs")).toAbsolutePath

  private val caCert = certsDir.resolve("ca.crt")
  private val opCert = certsDir.resolve(s"operators/$operator.crt")
  private val opKey = certsDir.resolve(s"operators/$operator.key")

  private val cliDeb: Path =
    sys.env
      .get("ORPHERA_CLI_DEB")
      .map(p => Paths.get(p))
      .getOrElse {
        val version = Files.readString(Paths.get("VERSION")).trim
        Paths.get(s"orphera_${version}_all.deb")
      }
      .toAbsolutePath

  private val gitlabCa: Option[Path] =
    sys.env.get("GITLAB_CA").filter(_.nonEmpty).map(p => Paths.get(p).toAbsolutePath)

  private val missing: List[String] =
    List(
      (caCert, "orphera init-ca (or the CA certificate of this host)"),
      (opCert, s"orphera issue-client-cert --operator $operator --role pipeline --days 30"),
      (opKey, s"orphera issue-client-cert --operator $operator --role pipeline --days 30"),
      (cliDeb, "make cli-package (or set ORPHERA_CLI_DEB)")
    ).filterNot((p, _) => Files.exists(p)).map((p, how) => s"$p: $how") ++
      gitlabCa.filterNot(Files.exists(_)).map(p => s"$p: GITLAB_CA does not exist")
  if missing.nonEmpty then
    throw new RuntimeException(
      "not found, nothing sent:\n  " + missing.mkString("\n  ")
    )

  // Read now, so that a missing secret stops the playbook before anything is
  // sent. Both are registered for masking by Secrets.secret.
  private val runnerToken = Secrets.secret("gitlab/runner-token")
  private val agentToken = Secrets.secret(AgentToken.StoreName)

  private val mode600 = 384 // 0600
  private val mode644 = 420 // 0644
  private val caDest = "/var/tmp/orphera-gitlab-ca.crt"
  private val debDest = "/var/tmp/orphera-cli.deb"

  private val packages = List(
    "openjdk-21-jre-headless",
    "git",
    "age",
    "openssh-client",
    "curl",
    "gnupg",
    "ca-certificates"
  )

  private val repoScript =
    """set -e
      |install -d -m 755 /etc/apt/keyrings
      |if [ ! -s /etc/apt/keyrings/gitlab-runner.gpg ]; then
      |  curl -fsSL https://packages.gitlab.com/runner/gitlab-runner/gpgkey \
      |    | gpg --dearmor -o /etc/apt/keyrings/gitlab-runner.gpg
      |  chmod 644 /etc/apt/keyrings/gitlab-runner.gpg
      |fi
      |. /etc/os-release
      |echo "deb [signed-by=/etc/apt/keyrings/gitlab-runner.gpg] https://packages.gitlab.com/runner/gitlab-runner/ubuntu/ $VERSION_CODENAME main" \
      |  > /etc/apt/sources.list.d/gitlab-runner.list
      |echo "gitlab-runner apt repository configured for $VERSION_CODENAME"""".stripMargin

  // apt-get install of a file also installs what it depends on (the Java
  // runtime); the same version again changes nothing.
  private val cliInstallScript =
    s"""set -e
       |DEBIAN_FRONTEND=noninteractive apt-get install -y -o DPkg::Lock::Timeout=300 $debDest
       |orphera version 2>&1 | tail -1 || true""".stripMargin

  private val dirsScript =
    s"""set -e
       |install -d -o $runnerUser -g $runnerUser -m 700 $runnerHome/.config $runnerHome/.config/orphera $runnerHome/.config/orphera/certs $runnerHome/.config/orphera/certs/operators
       |echo "directories ready"""".stripMargin

  private val tlsArg = gitlabCa.map(_ => s"--tls-ca-file $caDest").getOrElse("")

  // `register` with an authentication token (glrt-...) takes its tags, lock and
  // description from the runner as made in GitLab. Already registered for this
  // URL: left as it is.
  private val registerScript =
    s"""set -e
       |if grep -q '^ *url = "$gitlabUrl' /etc/gitlab-runner/config.toml 2>/dev/null; then
       |  echo "already registered with $gitlabUrl"
       |else
       |  gitlab-runner register --non-interactive --url '$gitlabUrl' \\
       |    --token '${runnerToken.reveal}' --executor shell $tlsArg
       |fi
       |systemctl enable --now gitlab-runner
       |systemctl restart gitlab-runner""".stripMargin

  // One node, tst0, is enough to prove the certificate, the token and the CA
  // end to end. `version` exits 0 even when a node refuses it, so its output is
  // checked. The inventory is a temporary one, made here, so that nothing of the
  // control host is needed.
  private val checkScript =
    s"""set -e
       |gitlab-runner verify 2>&1 | tail -5 || true
       |systemctl is-active gitlab-runner
       |inv=$$(mktemp)
       |trap 'rm -f "$$inv"' EXIT
       |printf 'nodes:\\n  - name: tst0\\n    host: tst0.${params.domainname}\\n' > "$$inv"
       |chmod 644 "$$inv"
       |out=$$(sudo -u $runnerUser -H env ORPHERA_OPERATOR=$operator \\
       |  ORPHERA_CERTS_DIR=$runnerHome/.config/orphera/certs ORPHERA_INVENTORY="$$inv" \\
       |  ORPHERA_TOKEN='${agentToken.reveal}' \\
       |  orphera version --nodes tst0 2>&1)
       |echo "$$out"
       |bad=$$(echo "$$out" | grep '^\\[' | grep -v -E '^\\[[^]]+\\] [0-9]' || true)
       |if [ -n "$$bad" ] || ! echo "$$out" | grep -q '^\\[tst0\\] [0-9]'; then
       |  echo "tst0 did not answer with its version as $operator (certificate, token or policy?)"
       |  exit 1
       |fi""".stripMargin

  private def copy(src: Path, dest: String, owner: String, mode: Int): Task =
    Task.Copy(src.toString, dest, owner, owner, mode)

  val playbook: ClusterPlaybook =
    clusterPlaybook("gitlab-runner")(
      stage("packages", host)
        .task(s"install ${packages.size} packages")(
          Task.Install(packages, updateCache = true)
        )
        .build,
      stage("gitlab-runner-package", host)
        .task("add the GitLab Runner apt repository and its key")(
          Task.RunCommand(List("sh", "-c", repoScript), timeoutSeconds = 120)
        )
        .task("install gitlab-runner")(
          Task.Install(List("gitlab-runner"), updateCache = true)
        )
        .build,
      stage("orphera-command-line", host)
        .task("send the orphera command-line package")(
          copy(cliDeb, debDest, "root", mode644)
        )
        .task("install it")(
          Task.RunCommand(List("sh", "-c", cliInstallScript), timeoutSeconds = 600)
        )
        .build,
      stage("operator-certificate", host)
        .task(s"create ~$runnerUser/.config/orphera/certs")(
          Task.RunCommand(List("sh", "-c", dirsScript), timeoutSeconds = 30)
        )
        .task("CA certificate")(
          copy(caCert, s"$runnerHome/.config/orphera/certs/ca.crt", runnerUser, mode644)
        )
        .task(s"operator certificate $operator.crt")(
          copy(opCert, s"$runnerHome/.config/orphera/certs/operators/$operator.crt", runnerUser, mode600)
        )
        .task(s"operator key $operator.key")(
          copy(opKey, s"$runnerHome/.config/orphera/certs/operators/$operator.key", runnerUser, mode600)
        )
        .build,
      stage("register-the-runner", host)
        .task("GitLab's CA certificate")(
          gitlabCa match
            case Some(p) => copy(p, caDest, "root", mode644)
            case None    => Task.RunCommand(List("true"), timeoutSeconds = 10)
        )
        .task(s"register with $gitlabUrl and start the service")(
          Task.RunCommand(List("sh", "-c", registerScript), timeoutSeconds = 120)
        )
        .build,
      stage("check", host)
        .task("the runner is registered; orphera version works as the runner")(
          Task.RunCommand(List("sh", "-c", checkScript), timeoutSeconds = 180)
        )
        .build
    )
