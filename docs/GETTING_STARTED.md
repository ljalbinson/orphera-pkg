<!-- SPDX-License-Identifier: Apache-2.0 -->
# Getting started with Orphera

This takes you from nothing to a machine you manage with `orphera`: install the command on a control
host, make a certificate authority, install the agent on one other machine, and run a command and a
playbook on it. It uses the packages from a GitHub release, so you need no checkout and no build.

When it works, the same steps scale to the whole fleet: add hosts to the inventory and bootstrap them.
The [README](../README.md) has the reference for everything below.

## How it fits together

- **The control host** runs the `orphera` command. It holds your inventory, your certificate authority
  and an encrypted store of secrets. You can have more than one (see *Building a second control host* in
  the README).
- **Each managed machine** runs the **agent**, a small service listening on port 50051. The agent does
  the work the command asks for: install a package, write a file, run a program.
- **Security:** every call travels over TLS, and the agent presents a certificate of its own, signed by
  your certificate authority. Every call also carries a shared token, which the agent checks. Neither is
  in the packages; you make both, and `orphera bootstrap` puts them on each machine.

## What you need

On the control host (Ubuntu or Debian is shown; Rocky and Red Hat work with the `.rpm`):

- Java 17 or later (the package depends on it), and `openssl` (to make the certificate authority).
- `age`, for the secret store: `sudo apt install age`.

On each machine to manage:

- A user you can reach with SSH **without a password** (an SSH key), who may use `sudo` without a
  password. This is only used to install the agent. The examples call it `localadmin`. Root login is not
  needed, and not used. The install runs over SSH with no terminal, so a `sudo` that asks for a password
  fails with `sudo: A terminal is required to authenticate`. To allow it, on that machine:
  `echo 'localadmin ALL=(ALL) NOPASSWD:ALL' | sudo tee /etc/sudoers.d/localadmin` and
  `sudo chmod 440 /etc/sudoers.d/localadmin`; `sudo -n true && echo ok` checks it.
- Port 50051 reachable from the control host.
- A DNS name or address for the control host to use. The agent's certificate will name it exactly.

## 1. Install the command

Download the command-line package and the agent package from the
[latest release](https://github.com/ljalbinson/orphera-pkg/releases/latest), with the checksums, and check
them. Use the version number of the release in place of `<version>`:

```sh
v=<version>
base=https://github.com/ljalbinson/orphera-pkg/releases/download/v$v
curl -LO $base/orphera_${v}_all.deb
curl -LO $base/orphera-agent_${v}_amd64.deb
curl -LO $base/SHA256SUMS
sha256sum --ignore-missing -c SHA256SUMS
sudo apt install ./orphera_${v}_all.deb
```

On Rocky or Red Hat, use `orphera-<version>-1.noarch.rpm` and `orphera-agent-<version>-1.noarch.rpm`, and
`sudo dnf install`. There is also a tarball (`orphera-<version>.tar.gz`) that needs no root: see
*Installing the command line* in the README.

Check it runs:

```sh
orphera version
```

It prints a warning that it has no inventory, and its own version. That is right: you have not made
one yet.

The program lives in `/opt/orphera` and is read-only. Your own files (inventory, configs, certificate
authority) go in `~/.config/orphera`, next to your secret store, so two people using one machine each have
their own. What `orphera` writes as it runs goes in `~/.local/state/orphera`. A team that shares one
inventory and one authority can use `/etc/orphera` instead: see *Where the inventory, configs and playbooks
are* in the README.

## 2. Describe your machines

The inventory lists the machines. Make `~/.config/orphera/inventory.yaml` (`mkdir -p ~/.config/orphera` first):

```yaml
nodes:
  - name: web1
    host: web1.example.com
  - name: web2
    host: web2.example.com

groups:
  - name: web
    members: [web1, web2]
```

`name` is what you type in commands (`--nodes web1`). `host` is the DNS name or address that `orphera`
connects to, and the one the agent's certificate is made for. A group lets you write `--node-groups web`
for all its members. Variables, `never-reboot` for hosts that must not be restarted, and a database as the
source instead of a file are described in the README under *Inventory*.

Start with one machine if you prefer; add the others later.

## 3. Make the certificate authority and the token

The certificate authority signs each machine's certificate. Make it once:

```sh
orphera init-ca
```

It writes `ca.crt` (which `orphera` trusts) and `ca.key` (which signs certificates) in
`~/.config/orphera/certs`, and prints the certificate's fingerprint. **Keep `ca.key` private and back it up.**
Without it you cannot issue certificates to new machines, and replacing the authority means every machine
needs a new certificate. `orphera init-ca` refuses to run again if one exists.

The token is a shared secret that every agent and `orphera` hold. Make it in the secret store:

```sh
orphera secrets init
orphera secrets generate orphera/agent-token
```

`orphera secrets init` makes the encryption key. **Back up `~/.config/orphera/age.key` and
`~/.config/orphera/secrets.age`**: without the key the token is lost, and with it every agent would need a
new one.

## 4. Put the agent on a machine

`orphera bootstrap` installs the agent over SSH. It also has the machine make its own key, signs a
certificate for it with your authority (the key never leaves the machine), and sets the token.

```sh
orphera bootstrap --agent orphera-agent_${v}_amd64.deb --nodes web1 --ssh-user localadmin
```

The first time, SSH may ask you to accept the machine's host key. `--ssh-key <file>` selects a key other
than your default. `--agent` is optional if the package is the newest `orphera-agent_*` in the current
directory. Machines with `dnf` take the `.rpm` instead; give `--agent` the `.rpm` for those.

Check that the agent answers and has a valid certificate:

```sh
orphera version --nodes web1
orphera cert-info --nodes web1
```

`version` shows the agent's version, and `cert-info` shows that the certificate names the machine and is
signed by your authority. If `version` says `unreachable`, the cause is usually the network (port 50051)
or a name that does not resolve. If it says `unauthenticated`, the machine has a different token from
the one in your secret store.

Do the rest of the machines the same way: `--nodes web1,web2`, or `--node-groups web`.

## 5. Run something

```sh
orphera run --nodes web1 -- uptime                    # a command, with its output
orphera install nginx --nodes web1                    # a package
orphera write-file /etc/motd --content "Managed by Orphera" --nodes web1
orphera copy ./nginx.conf /etc/nginx/nginx.conf --nodes web1
orphera facts --nodes web1                            # what the machine reports about itself
orphera dist-upgrade --node-groups web                # a group
```

Each machine's output is prefixed with its name, and machines run in parallel. A command that changes a
machine is recorded in the audit trail: `orphera audit-log` lists the recent ones.

## 6. Write a playbook

A playbook is an ordered list of tasks, run on each machine in turn. Save this as `web.yaml`:

```yaml
name: web-baseline

nodes:
  - web1
  - web2

tasks:
  - name: install nginx
    install:
      packages: [nginx]
      update_cache: true

  - name: write the front page
    write_file:
      dest: /var/www/html/index.html
      content: "Hello from Orphera"
      owner: root
      group: root
      mode: "0644"

  - name: check nginx answers
    run_command:
      command: [curl, -fsS, http://localhost/]
      timeout: 30
```

Run it:

```sh
orphera playbook web.yaml
```

Tasks run in order on each machine, and a machine stops at its first failed task; the machines run in
parallel. If a run
stops, fix the cause and run `orphera playbook web.yaml --resume` to skip the tasks that already
finished. The other task types are `copy`, `remove`, `dist_upgrade`, `reboot`, `set_fact` and more: see
*Playbooks* in the README.

For anything more than a list of tasks (loops, computed values, passwords from the secret store), write
the playbook in Scala and give `orphera playbook` the `.scala` file. It is compiled when it runs, and no
build tree is needed. The package ships a library of working examples in `/opt/orphera/manifests`: name
one without its directory, for instance `orphera playbook arithmetic_demo.scala`. See *Run-time-compiled
Scala scripts*.

## 7. Keeping it running

- **Upgrade the agents** when a new release comes out: download the new agent package, then
  `orphera deploy-agent --agent orphera-agent_<new>_amd64.deb`. It waits for each machine to report the new
  version before it says the upgrade is confirmed. Upgrade the command itself with `apt install` of the new
  `orphera` package.
- **Renew or replace a certificate:** `orphera issue-certs --nodes web1 --ssh-user localadmin`. The
  certificates last 825 days; `orphera cert-info` shows the days left.
- **Hosts that must not be restarted** (a hypervisor, say) are marked `never-reboot: true` in the
  inventory; `orphera reboot` and `orphera shutdown` skip them.
- **Where things are:** `orphera help` lists every command. The inventory, the per-machine configs and
  the playbooks can each be somewhere else: see *Where the inventory, configs and playbooks are* in the
  README.

## When something goes wrong

| What you see | Usually |
|---|---|
| `cannot read ...inventory.yaml: no such file` | no inventory yet (step 2), or you are using another location: `-I` or `ORPHERA_INVENTORY` |
| `No matching nodes found in inventory` | the name in `--nodes` is not in the inventory; or the inventory did not load (look at the first line) |
| `no CA certificate: ... does not exist` | step 3 not done, or `ORPHERA_CERTS_DIR` points somewhere else |
| `bootstrap` refuses: no agent token | `orphera secrets generate orphera/agent-token` (step 3) |
| `sudo: A terminal is required to authenticate` from `bootstrap` | the SSH user's `sudo` asks for a password: see *What you need* |
| `unreachable` in `version` | the machine is down, port 50051 is blocked, or its name does not resolve |
| `unauthenticated` in `version` | that agent holds a different token from this machine's secret store |
| The agent keeps restarting | on the machine, `journalctl -u orphera-agent`; it says why (no token, no certificate) |

## Next

The README covers the rest: staged playbooks that coordinate machines, per-VM configuration files,
building virtual machines, the Ceph and OpenStack examples, the secret store in playbooks, and running
with the inventory in a database.
