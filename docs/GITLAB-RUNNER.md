<!-- SPDX-License-Identifier: Apache-2.0 -->
# A GitLab runner that can run Orphera

This builds a GitLab Runner on a VM of its own, so that a pipeline in your lab GitLab can run `orphera`
commands and playbooks against the fleet.

**Status:** written from the code and the README; it has not yet been run on a real runner. The steps that
carry the most risk are marked *check*. Update this page after the first run.

## The one rule

Do **not** make the runner a copy of the control host. `control_host_handover.scala` copies the CA key, the
age identity and the SSH key to the new host. A runner that holds those lets anyone who can push a pipeline
to the project do anything to the fleet. The runner gets an identity of its own instead:

| The runner has | The runner does not have |
|---|---|
| `ca.crt` (public) | `ca.key` |
| an operator certificate and key (`ci`) | `age.key` and `secrets.age` (unless a playbook reads secrets) |
| the agent token, as a protected CI variable | your SSH key |

## What you need

- An Ubuntu 24.04 VM for the runner (2 CPUs and 4 GB is plenty), with a config file and an inventory entry
  like any other lab VM, or build it by hand. A `localadmin` user with `sudo` and an SSH key, as on the
  other lab hosts; no root login.
- The runner can reach your GitLab, and port 50051 on every node it will manage.
- You can reach GitLab as a user who may create a runner (project owner or group owner).
- scala0, for the steps that need the CA key.

## 1. The host

On the runner VM, as `localadmin`:

```sh
sudo apt update
sudo apt install -y openjdk-21-jre-headless git age
```

`age` is only used if a playbook calls `Secrets.get`; install it anyway so that a playbook which does fails
with its own message and not a missing program.

Install the `orphera` command-line package. Build it on scala0 (`make cli-package`) or take it from a
release, copy the `.deb` over, and install it:

```sh
sudo apt install ./orphera_<version>_all.deb
orphera version
```

`orphera version` prints a warning that there is no inventory. That is right at this point.

Do not run `control_host.scala` or `control_host_handover.scala` on this host.

## 2. The runner

Install GitLab Runner from GitLab's apt repository (see
[the GitLab Runner install page](https://docs.gitlab.com/runner/install/linux-repository.html) for the
repository line for your release), then make a runner in GitLab: in the project (or group), **Settings,
CI/CD, Runners, New project runner**. Give it the tag `orphera`, tick **Run untagged jobs** off, and
copy the authentication token it shows (it starts `glrt-`).

Register it on the runner VM:

```sh
sudo gitlab-runner register --non-interactive \
  --url https://<your-gitlab> \
  --token <glrt-token> \
  --executor shell \
  --description orphera-exec
```

If your lab GitLab uses a certificate from a private CA, add `--tls-ca-file /path/to/that-ca.crt`, or the
registration fails with a certificate error. *Check* that a plain `curl https://<your-gitlab>` from the
runner VM works before registering.

The shell executor runs each job as the `gitlab-runner` user on this VM, which is what lets the job use
the files in that user's home. It also means the VM itself is the security boundary: keep nothing else on
it.

In the runner's settings in GitLab, tick **Protected** so that only protected branches and tags use it.

## 3. Its identity

On scala0, make an operator certificate for the runner. This does not touch the shared client certificate:

```sh
orphera issue-client-cert --operator ci --role pipeline --days 30
```

That writes `certs/operators/ci.crt` and `ci.key` (subject `CN=ci, OU=pipeline`). Names and roles are
lower-case letters, digits, `.`, `_` and `-`.

Copy `ci.crt`, `ci.key` and `ca.crt` to the runner VM, into the `gitlab-runner` user's directory (on the
runner VM, as `localadmin`):

```sh
sudo install -d -m 700 -o gitlab-runner -g gitlab-runner /home/gitlab-runner/.config/orphera/certs/operators
sudo install -m 644 -o gitlab-runner -g gitlab-runner ca.crt /home/gitlab-runner/.config/orphera/certs/ca.crt
sudo install -m 600 -o gitlab-runner -g gitlab-runner ci.crt /home/gitlab-runner/.config/orphera/certs/operators/ci.crt
sudo install -m 600 -o gitlab-runner -g gitlab-runner ci.key /home/gitlab-runner/.config/orphera/certs/operators/ci.key
```

Then delete the copies in `localadmin`'s home. Do not copy `ca.key`, `client.key`, `age.key`, or an SSH key.

The certificate lasts 30 days. Put the expiry in your calendar, or pass a longer `--days`. When it
lapses, jobs fail with a certificate error and the fix is to issue another one and copy it over.

### The CI variables

In GitLab, **Settings, CI/CD, Variables** (project or group), add:

| Variable | Value | Flags |
|---|---|---|
| `ORPHERA_TOKEN` | the agent token (`orphera secrets get orphera/agent-token` on scala0) | Masked, Protected |
| `ORPHERA_OPERATOR` | `ci` | Protected |
| `ORPHERA_CERTS_DIR` | `/home/gitlab-runner/.config/orphera/certs` | Protected |

`ORPHERA_TOKEN` in the environment is used before the secret store, so the runner needs no `age.key`.
Protected variables reach only protected branches and tags, which is the point. *Check* that the token
fits GitLab's rules for a masked variable (at least 8 characters, a limited character set); if GitLab
refuses to mask it, do not store it unmasked: generate a token without the awkward characters
(`orphera secrets generate orphera/agent-token`, then `utils/set-agent-token.sh` to roll it to the nodes).

## 4. What the identity may do

A certificate on its own records who acted. To limit what the runner may do, two things must be true on the
nodes:

1. The node **requires client certificates**: `orphera issue-certs --nodes ... --client-auth`. Without
   it, the agent also accepts the shared token alone, and a pipeline that leaks the token walks round the
   role.
2. The node holds a **policy** that names the role: add it to `access.yaml` and place it with
   `orphera issue-policy --nodes ...` (`--dry-run` shows it first).

`access.yaml` already has a `deployer` role (copy and packages on the ceph group) and an `applier` role (apply
descriptions without `run`, on tst39), so give the runner a role of its own, `pipeline`, and name only the
nodes its pipelines touch:

```yaml
roles:
  pipeline:
    allow: [facts, version, uptime, observe, apply-files, apply-access, apply-software, packages, copy, run]
    nodes: [tst3, tst4, tst5, tst7]
```

If a pipeline only applies `.yaml` descriptions or Scala playbooks made of `Task.Apply`, leave `run` out, as
the `applier` role does. Then place it: `orphera issue-policy --nodes tst3,tst4,tst5,tst7` (add `--dry-run`
first to see it).

Be clear about what `run` grants: a role with it is root on the node. Most playbooks in `manifests/` use
`Task.RunCommand`, so today a pipeline that runs them needs `run`, and the role limits **where** the runner
can act (which nodes name it) and **who** acted (the audit log), not **what** it may do. As
`docs/COMMAND-CONVERSION-PLAN.md` replaces commands with typed actions, `pipeline` can lose `run` playbook
by playbook.

`ORPHERA_OPERATOR=ci` also makes the orchestrator refuse early, before contacting a node, anything the role
may not do (see *Early refusal* in the README).

## 5. A first job

Put this in `.gitlab-ci.yml` of the project that holds the inventory and manifests:

```yaml
stages: [check, deploy]

orphera-reachable:
  stage: check
  tags: [orphera]
  rules:
    - if: $CI_COMMIT_BRANCH == "main"
  script:
    - orphera version --nodes tst3,tst7

deploy-wordpress:
  stage: deploy
  tags: [orphera]
  rules:
    - if: $CI_COMMIT_BRANCH == "main"
  script:
    - orphera cluster-playbook manifests/wordpress_site.scala -I inventory.yaml
```

The job checks the repository out and runs from there, so the inventory and manifests are those of the
commit. *Check* how `-I` and the checkout directory combine on the first run (the launcher changes into
`$ORPHERA_HOME` if it is set), and use `ORPHERA_INVENTORY` and `ORPHERA_MANIFESTS` if the paths are not
found. `orphera audit-log` on the runner VM should then show `operator=ci role=pipeline` for each step.

The first run to make is the `orphera version` job alone. A TLS error there points at the certificate, an
authentication error at the token, and a refusal naming the role at `access.yaml`.

## What stays on scala0

`bootstrap`, `issue-certs`, `issue-policy`, `init-ca` and `issue-client-cert` use SSH or the CA key. The
runner should not hold either, so run them yourself.

Keep the destructive playbooks out of automatic pipelines: the teardowns, `reboot-all` and anything that
rebuilds a cluster. If you want them in CI, give them `when: manual` in a protected environment, so that a
person presses the button.

## Keeping it safe

- Anyone who can push to a protected branch of this project can make the runner do what the `pipeline`
  role allows. Restrict who may push to `main`, and who may edit `.gitlab-ci.yml` there.
- Protected runner, protected variables, protected branches: all three, or a feature branch can read the
  token.
- A merge request pipeline from a fork or an unprotected branch must not get these variables. Leave
  "Protected" ticked, and do not use the runner for them.
- The runner VM holds a token, a certificate and a key. Patch it, keep it off other duties, and rotate the
  certificate and token when someone who knew them leaves.
- To revoke the runner at once: add the certificate's serial number to `revoked:` in `access.yaml` and place
  the policy again (`openssl x509 -noout -serial -in certs/operators/ci.crt` gives the serial), then roll
  the token.
