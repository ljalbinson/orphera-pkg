# Orphera agent in Go

A Go implementation of the Orphera agent, kept alongside the Scala agent in
`agent/` so the two can be compared on real nodes. It serves the same gRPC
service from the same `common/src/main/protobuf/Protocol.proto`, on the same
port (50051), with the same token (`ORPHERA_TOKEN`), the same certificates
(`/etc/orphera-agent/tls/`, falling back to `certs/`) and the same never-reboot
marker. The orchestrator does not need to know which agent it is talking to.

Nothing outside `agent-go/` is changed: the sbt build, the Scala agent, the
protocol file and the packaging are untouched. Go code is generated from the
shared `.proto` with `-M` options instead of adding a `go_package` to it.

## Layout

| Path | What it is |
|---|---|
| `cmd/agent/` | `main`: token and certificate checks, TLS gRPC server, shutdown on SIGTERM |
| `internal/agent/` | The service, one file per area (packages, command, files, network, facts, interfaces, power, stats, auth) |
| `internal/pb/` | Generated from `Protocol.proto` by `gen.sh` (checked in, so building needs no `protoc`) |
| `packaging/orphera-agent-go.conf` | systemd drop-in to run the Go agent in place of the Scala one on a test node |
| `compare/` | Compares the Go agent's interface and disk facts with the JVM's on a node |

Each Scala file has a Go counterpart with the same behaviour, and the Scala
comments that explain *why* (the RESULT that must end every stream, the
post-install check that must never run the binaries, the known-good network
rollback, the self-upgrade name check) are carried over.

## Build and test

Needs Go 1.22 or later.

```sh
cd agent-go
make test     # go vet, and unit + end-to-end tests with the race detector
make build    # static binaries: bin/orphera-agent-go-amd64, bin/orphera-agent-go-arm64
```

The version comes from the repository's `VERSION` file. `CGO_ENABLED=0`, so the
binaries have no dependencies: no JVM, no libc version to match.

The end-to-end tests run the real service over TLS with the token check and
call it with a gRPC client: token refusal, streamed commands ending at RESULT,
a failing or panicking task still ending its stream with FAILED, file copy
(owner, group, mode, atomic rename), check and fetch, the self-upgrade refusal
without a node certificate, facts, uptime and thread info. The unit tests
cover the never-reboot guard (the Scala `AgentGuardCheck`), command timeouts
and exit codes, the package-install post-check, the network confirm and
rollback, and the interface ordering.

## Trying it on a test node

```sh
make amd64
scp bin/orphera-agent-go-amd64 packaging/orphera-agent-go.conf root@tst0:/tmp/
ssh root@tst0 '
  install -m 0755 /tmp/orphera-agent-go-amd64 /opt/orphera-agent/orphera-agent-go
  install -D -m 0644 /tmp/orphera-agent-go.conf /etc/systemd/system/orphera-agent.service.d/orphera-agent-go.conf
  systemctl daemon-reload && systemctl restart orphera-agent'
```

The drop-in replaces only `ExecStart`, so the unit, `agent.env`, the
certificates and the never-reboot marker are the same ones the Scala agent
uses. To go back: remove the drop-in, `daemon-reload`, restart.

Before running playbooks that template node addresses, check the facts match
on that node (it has Java, since the Scala agent is installed):

```sh
compare/compare-facts.sh /opt/orphera-agent/orphera-agent-go
```

## Matching the JVM agent's facts

The orchestrator templates `ip_<name>` from each interface's first address and
`ip_secondary` from the second interface's first address, so the interface list
must come out exactly as `java.net.NetworkInterface` produced it. The Go agent
reproduces the JDK's Linux enumeration (JDK 21 `NetworkInterface.c`): IPv4
addresses from `SIOCGIFCONF`, then IPv6 from `/proc/net/if_inet6`, each new
interface and each address added at the front. That means:

- interfaces come out in reverse order of discovery;
- an interface's IPv6 addresses come before its IPv4 ones, so on a host with
  IPv6, `ip_eth0` is an IPv6 address;
- IPv6 is written in Java's full form with the interface as scope,
  `fe80:0:0:0:a00:27ff:fe4e:1b2c%eth0`, not `fe80::a00:27ff:fe4e:1b2c`;
- aliases such as `eth0:1` add their address to `eth0`.

Disks likewise follow `FileSystems.getFileStores()`: every line of `/etc/mtab`
in order. One quirk is kept on purpose: `mount_point` holds the device or
source (`/dev/vda`, `tmpfs`), not the directory, because the Scala agent fills
it from `FileStore.name()`. Changing that belongs in both agents at once.

On the build machine, `compare-facts.sh` reported identical output. That
machine has no IPv6, so the IPv6 ordering is covered by unit tests written from
the JDK source but has not yet been compared against a JVM on a real node.

## Differences from the Scala agent

Deliberate, each small:

- **Self-upgrade (`InstallDebPackage`)**: the package path is passed to the
  install script as `$1` instead of being pasted into the script text. With an
  ordinary path the two behave the same; a path containing shell syntax can no
  longer run anything.
- **`ExecuteCommand`**: if the command exits but leaves a background process
  holding its output open, the Go agent stops waiting for output after one
  second. The Scala agent waited for that output indefinitely.
- **Network confirm racing the rollback**: whichever happens first wins and
  the other does nothing. NetworkManager profiles are brought up in file-name
  order.
- **User and group names** (file copy and check): read from `/etc/passwd` and
  `/etc/group`, then through `getent`, so LDAP or sssd users still resolve
  without linking against libc.
- **`GetThreadInfo`**: the fields named for the JVM carry the Go equivalents:
  goroutines (current, peak, started for tasks), the Go heap, `GOMEMLIMIT` or
  -1, and goroutines by state (`IO wait`, `select`, ...). OS threads and RSS
  are as before. `orphera thread-info` still prints them under its "java"
  labels.

## Not yet verified

These need a real node; the build machine could not test them:

- calls from the actual Scala orchestrator (the tests use a Go gRPC client
  over the same protocol and TLS);
- apt and dnf installs, removals and upgrades on Debian/Ubuntu and Rocky/Fedora hosts;
- `networkctl` and `nmcli` reloads and an actual rollback;
- `systemd-run` for reboot, power off and self-upgrade;
- interface order on hosts with IPv6 (run `compare/compare-facts.sh`).

## Size and start-up, measured on the build machine

| | Go agent |
|---|---|
| Binary | 11.6 MB (amd64), 10.9 MB (arm64), static, no runtime needed |
| Start to listening | 7 ms |
| Resident memory | about 10 MB idle, 13 MB after serving calls |
| OS threads | 8 |

Measure the Scala agent on the same node with `orphera thread-info` for a
like-for-like comparison.
