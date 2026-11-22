// SPDX-License-Identifier: Apache-2.0

import java.nio.file.{Files, Path, Paths}
import orphera.orchestrator.*
import orphera.orchestrator.ClusterPlaybookDsl.*

// Turns an empty VM (runner1) into a GitLab runner with the Docker executor,
// for the project's own build job (`compile_and_test` in .gitlab-ci.yml, which
// runs in `eclipse-temurin:21-jdk`). Unlike runner0 (gitlab_runner.scala), it is
// not part of Orphera: it gets no operator certificate and no agent token, so
// nothing on it can reach a node. See docs/GITLAB-RUNNER.md.
//
//   manifests/mk-new-and-empty.sh runner1                      # the VM, agent, DNS, base packages
//   orphera secrets set gitlab/runner1-token                    # the glrt- token from GitLab
//   GITLAB_URL=https://gitlab.example orphera cluster-playbook gitlab_runner_docker.scala --config runner1
//
// The secret is named gitlab/<hostname>-token. Make the runner in GitLab first
// (Admin, CI/CD, Runners, New instance runner): no tags, "Run untagged jobs"
// on, "Protected" off, so that the untagged build job on any branch lands here.
//
// Before anything is sent, the secret and GITLAB_URL must exist, and
// GITLAB_CA=<file> if GitLab's certificate comes from a private CA (copied to the
// runner and given to `gitlab-runner register --tls-ca-file`).
//
// What it does on the VM: docker.io, git, curl and GnuPG; GitLab's apt
// repository and the gitlab-runner package; the gitlab-runner user in the
// docker group; the runner registered with the docker executor and the default
// image eclipse-temurin:21-jdk (once: an existing registration for the URL is
// kept); the image pulled; then a check that the runner is registered and that
// the gitlab-runner user can run a container.
//
// Safe to re-run. To register against a new runner token,
// `sudo gitlab-runner unregister --all-runners` on the VM first.
object gitlab_runner_docker extends OrpheraClusterPlaybook:

  private val params: VmParams =
    ConfigYaml.fromEnv() match
      case Some(Right(p))  => p
      case Some(Left(err)) =>
        throw new RuntimeException(s"--config given but failed to load: $err")
      case None =>
        throw new RuntimeException(
          "gitlab_runner_docker requires --config <vm config> (e.g. --config runner1)"
        )

  private val host = params.hostname
  private val runnerUser = "gitlab-runner"
  private val image = "eclipse-temurin:21-jdk"

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

  private val gitlabCa: Option[Path] =
    sys.env
      .get("GITLAB_CA")
      .filter(_.nonEmpty)
      .map(p => Paths.get(p).toAbsolutePath)

  if gitlabCa.exists(p => !Files.exists(p)) then
    throw new RuntimeException(
      s"not found, nothing sent:\n  ${gitlabCa.get}: GITLAB_CA does not exist"
    )

  // Read now, so that a missing secret stops the playbook before anything is
  // sent. Registered for masking by Secrets.secret.
  private val runnerToken = Secrets.secret(s"gitlab/$host-token")

  private val mode644 = 420 // 0644
  private val caDest = "/var/tmp/orphera-gitlab-ca.crt"

  private val packages =
    List("docker.io", "git", "curl", "gnupg", "ca-certificates")

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

  // The service runs as gitlab-runner and talks to the Docker socket, so it
  // needs the docker group.
  private val dockerScript =
    s"""set -e
       |systemctl enable --now docker
       |usermod -aG docker $runnerUser
       |echo "$runnerUser is in the docker group"""".stripMargin

  private val tlsArg = gitlabCa.map(_ => s"--tls-ca-file $caDest").getOrElse("")

  // `register` with an authentication token (glrt-...) takes its tags, lock and
  // description from the runner as made in GitLab; the executor and its image
  // are local. Already registered for this URL: left as it is.
  private val registerScript =
    s"""set -e
       |if grep -q '^ *url = "$gitlabUrl' /etc/gitlab-runner/config.toml 2>/dev/null; then
       |  echo "already registered with $gitlabUrl"
       |else
       |  gitlab-runner register --non-interactive --url '$gitlabUrl' \\
       |    --token '${runnerToken.reveal}' --executor docker \\
       |    --docker-image $image $tlsArg
       |fi
       |systemctl enable --now gitlab-runner
       |systemctl restart gitlab-runner""".stripMargin

  private val pullScript =
    s"""set -e
       |sudo -u $runnerUser -H docker pull $image
       |echo "$image pulled"""".stripMargin

  private val checkScript =
    s"""set -e
       |gitlab-runner verify 2>&1 | tail -5 || true
       |systemctl is-active gitlab-runner
       |sudo -u $runnerUser -H docker run --rm $image java -version 2>&1""".stripMargin

  val playbook: ClusterPlaybook =
    clusterPlaybook("gitlab-runner-docker")(
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
        .task(s"start docker and put $runnerUser in its group")(
          Task.RunCommand(List("sh", "-c", dockerScript), timeoutSeconds = 60)
        )
        .build,
      stage("register-the-runner", host)
        .task("GitLab's CA certificate")(
          gitlabCa match
            case Some(p) =>
              Task.Copy(p.toString, caDest, "root", "root", mode644)
            case None => Task.RunCommand(List("true"), timeoutSeconds = 10)
        )
        .task(s"register with $gitlabUrl and start the service")(
          Task
            .RunCommand(List("sh", "-c", registerScript), timeoutSeconds = 120)
        )
        .build,
      stage("pull-the-image", host)
        .task(s"pull $image")(
          Task.RunCommand(List("sh", "-c", pullScript), timeoutSeconds = 900)
        )
        .build,
      stage("check", host)
        .task("the runner is registered; a container runs as the runner")(
          Task.RunCommand(List("sh", "-c", checkScript), timeoutSeconds = 300)
        )
        .build
    )
