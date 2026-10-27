#!/usr/bin/env bash
# SPDX-License-Identifier: Apache-2.0
#
# Builds a minimum working environment on the OpenStack lab and boots Ubuntu 26.04 in it:
#
#   project   demo                       (Default domain)
#   user      demo                       member of the project; password in the secret store (demo/user-password)
#   image     ubuntu-26.04               downloaded from cloud-images.ubuntu.com, checksum verified, public
#   flavor    orphera.small              1 vCPU, 2 GB RAM, 10 GB disk
#   network   provider + subnet          the flat provider network (10.10.6.0/24, DHCP), shared
#   security  demo-ssh                   SSH and ICMP in, everything out
#   keypair   demo-key                   from ~/.ssh/orphera-demo (made if missing)
#   server    demo-vm                    Ubuntu 26.04 on the provider network, config drive for the key
#
# NO ROUTER AND NO FLOATING IP. This lab's Neutron has neither an L3 agent nor tenant networks
# (neutron_single_node.scala: no L3, no VXLAN, no metadata agent). The instance sits directly on the
# flat provider network and gets its address from DHCP there. Router and floating IP steps need L3 added to
# Neutron first.
#
# ONE FLAT NETWORK ONLY. The ML2 flat driver allows one network on the physical network `provider`. If one
# already exists (any name) this script uses it. While it exists, the CirrOS boot test of
# test_nova_compute.sh and the network create in the smoke check cannot make their own. `down --all` removes it.
#
# Usage, from scala0 (needs the clients: utils/openstack-client-setup.sh):
#
#   utils/openstack-minimal-env.sh           # same as `up`; safe to re-run, it skips what exists
#   utils/openstack-minimal-env.sh up
#   utils/openstack-minimal-env.sh down      # removes the server, key pair, security group, user, project
#   utils/openstack-minimal-env.sh down --all   # and the image, flavor, subnet, network and the stored password
#
# Settings (environment): OS_RC (admin-openrc.sh), DEMO_PROJECT, DEMO_USER, DEMO_PW_SECRET, IMAGE_NAME,
# IMAGE_FILE (a local image instead of the download), CACHE_DIR, FLAVOR, NET_NAME, PHYSNET, CIDR, GATEWAY,
# POOL_START, POOL_END, DNS (a nameserver to hand out; unset = what the DHCP agent decides), KEY_FILE,
# VM_NAME, BOOT_TIMEOUT (seconds to wait for the guest to finish booting).
#
# The subnet defaults are the ones nova_compute.scala uses for its boot test; the nodes themselves use
# 10.10.6.29-31, outside the pool.
#
# The demo password is passed to the client on its command line while the user is created, so it is visible
# to other users of scala0 in `ps` for a moment. Test lab only.

set -uo pipefail

HERE="$(cd "$(dirname "$0")" && pwd)"

OS_RC="${OS_RC:-$HOME/.config/openstack/admin-openrc.sh}"
PROJECT="${DEMO_PROJECT:-demo}"
DEMO_USER="${DEMO_USER:-demo}"
PW_SECRET="${DEMO_PW_SECRET:-demo/user-password}"

IMAGE_NAME="${IMAGE_NAME:-ubuntu-26.04}"
IMAGE_BASE="https://cloud-images.ubuntu.com/releases/resolute/release"
IMAGE_FILE_NAME="ubuntu-26.04-server-cloudimg-amd64.img"
CACHE_DIR="${CACHE_DIR:-$HOME/.cache/orphera/images}"

FLAVOR="${FLAVOR:-orphera.small}"
NET_NAME="${NET_NAME:-provider}"
PHYSNET="${PHYSNET:-provider}"
CIDR="${CIDR:-10.10.6.0/24}"
GATEWAY="${GATEWAY:-10.10.6.1}"
POOL_START="${POOL_START:-10.10.6.200}"
POOL_END="${POOL_END:-10.10.6.220}"
DNS="${DNS:-}"

KEY_FILE="${KEY_FILE:-$HOME/.ssh/orphera-demo}"
KEY_NAME="demo-key"
SG_NAME="demo-ssh"
VM_NAME="${VM_NAME:-demo-vm}"
BOOT_TIMEOUT="${BOOT_TIMEOUT:-300}"

log() { printf '[%s] %s\n' "$(date +%H:%M:%S)" "$1"; }
die() { printf 'ERROR: %s\n' "$1" >&2; exit 1; }

# True if the command succeeds; output is discarded. Used to test whether a resource exists.
exists() { "$@" >/dev/null 2>&1; }

# ---- clients and credentials ----------------------------------------------------------------------------

load_admin() {
  [ -r "$OS_RC" ] || die "$OS_RC not found; run utils/openstack-client-setup.sh first"
  # shellcheck disable=SC1090
  . "$OS_RC"
  command -v openstack >/dev/null 2>&1 || die "the openstack client is not on PATH after sourcing $OS_RC"
  command -v orphera >/dev/null 2>&1 || die "orphera is not on PATH (the demo password is kept in its secret store)"
  openstack token issue -f value -c id >/dev/null 2>&1 \
    || die "cannot get an admin token from Keystone (is the admin password in the secret store the one Keystone has?)"
}

# Runs the client as the demo user in the demo project. Keys and servers belong to the user, not to the admin.
DEMO_PW=""
user_os() {
  OS_USERNAME="$DEMO_USER" OS_PASSWORD="$DEMO_PW" OS_PROJECT_NAME="$PROJECT" openstack "$@"
}

load_demo_password() {
  if ! orphera secrets list 2>/dev/null | grep -qxF "$PW_SECRET"; then
    [ "${1:-}" = "create" ] || return 1
    log "making the secret $PW_SECRET"
    orphera secrets generate "$PW_SECRET" >/dev/null || die "could not make the secret $PW_SECRET"
  fi
  DEMO_PW="$(orphera secrets get "$PW_SECRET" 2>/dev/null)"
  [ -n "$DEMO_PW" ] || die "the secret $PW_SECRET is empty"
}

# ---- up --------------------------------------------------------------------------------------------------

ensure_project_and_user() {
  if exists openstack project show --domain Default "$PROJECT"; then
    log "project $PROJECT exists"
  else
    log "creating project $PROJECT"
    openstack project create --domain Default --description "Orphera demo environment" "$PROJECT" >/dev/null \
      || die "could not create project $PROJECT"
  fi

  load_demo_password create
  if exists openstack user show --domain Default "$DEMO_USER"; then
    log "user $DEMO_USER exists"
  else
    log "creating user $DEMO_USER"
    openstack user create --domain Default --password "$DEMO_PW" --project "$PROJECT" "$DEMO_USER" >/dev/null \
      || die "could not create user $DEMO_USER"
  fi

  # Idempotent: adding a role that is already there is not an error.
  openstack role add --user "$DEMO_USER" --user-domain Default --project "$PROJECT" --project-domain Default member \
    || die "could not give $DEMO_USER the member role in $PROJECT"

  # A token as the user proves the password and the role. Set the password only if the login fails: setting
  # one makes Keystone revoke the user's tokens.
  if ! user_os token issue -f value -c id >/dev/null 2>&1; then
    log "the stored password does not log $DEMO_USER in; setting it"
    openstack user set --domain Default --password "$DEMO_PW" "$DEMO_USER" || die "could not set the password of $DEMO_USER"
    user_os token issue -f value -c id >/dev/null 2>&1 || die "$DEMO_USER still cannot log in to project $PROJECT"
  fi
}

ensure_image() {
  if exists openstack image show "$IMAGE_NAME"; then
    log "image $IMAGE_NAME exists"
    return 0
  fi
  local file="${IMAGE_FILE:-$CACHE_DIR/$IMAGE_FILE_NAME}"
  if [ -z "${IMAGE_FILE:-}" ]; then
    mkdir -p "$CACHE_DIR" || die "cannot make $CACHE_DIR"
    local sums want got
    sums="$(curl -fsSL "$IMAGE_BASE/SHA256SUMS")" || die "could not fetch $IMAGE_BASE/SHA256SUMS (set IMAGE_FILE=/path/to/image.img to use a local file)"
    want="$(printf '%s\n' "$sums" | awk -v f="$IMAGE_FILE_NAME" '$2 == f || $2 == "*" f { print $1 }')"
    [ -n "$want" ] || die "$IMAGE_FILE_NAME is not listed in $IMAGE_BASE/SHA256SUMS"
    got=""
    [ -f "$file" ] && got="$(sha256sum "$file" | awk '{print $1}')"
    if [ "$got" != "$want" ]; then
      log "downloading $IMAGE_FILE_NAME"
      curl -fL -C - -o "$file" "$IMAGE_BASE/$IMAGE_FILE_NAME" || die "download failed"
      got="$(sha256sum "$file" | awk '{print $1}')"
      [ "$got" = "$want" ] || { rm -f "$file"; die "checksum of $IMAGE_FILE_NAME does not match SHA256SUMS; removed it"; }
    fi
    log "checksum ok"
  else
    [ -r "$file" ] || die "IMAGE_FILE $file is not readable"
  fi
  log "uploading $IMAGE_NAME to Glance"
  openstack image create --file "$file" --disk-format qcow2 --container-format bare --public \
    --property os_distro=ubuntu --property os_version=26.04 "$IMAGE_NAME" >/dev/null \
    || die "could not upload the image"
}

ensure_flavor() {
  if exists openstack flavor show "$FLAVOR"; then
    log "flavor $FLAVOR exists"
  else
    log "creating flavor $FLAVOR"
    openstack flavor create --vcpus 1 --ram 2048 --disk 10 --public "$FLAVOR" >/dev/null || die "could not create flavor $FLAVOR"
  fi
}

NET_ID=""
ensure_network() {
  # One flat network per physical network: take whatever is there.
  local found
  found="$(openstack network list --provider-physical-network "$PHYSNET" -f value -c ID -c Name | head -n 1)"
  if [ -n "$found" ]; then
    NET_ID="${found%% *}"
    NET_NAME="${found#* }"
    log "network $NET_NAME ($NET_ID) exists on physical network $PHYSNET"
  else
    log "creating the flat network $NET_NAME on physical network $PHYSNET"
    NET_ID="$(openstack network create --share --provider-network-type flat --provider-physical-network "$PHYSNET" \
      -f value -c id "$NET_NAME")" || die "could not create the network"
    [ -n "$NET_ID" ] || die "no network id came back"
  fi
  if [ -n "$(openstack subnet list --network "$NET_ID" -f value -c ID)" ]; then
    log "network $NET_NAME already has a subnet"
  else
    log "creating subnet $CIDR (DHCP, pool $POOL_START-$POOL_END, gateway $GATEWAY)"
    local dns_args=()
    [ -n "$DNS" ] && dns_args=(--dns-nameserver "$DNS")
    openstack subnet create --network "$NET_ID" --subnet-range "$CIDR" --gateway "$GATEWAY" \
      --allocation-pool "start=$POOL_START,end=$POOL_END" --dhcp "${dns_args[@]}" "$NET_NAME-subnet" >/dev/null \
      || die "could not create the subnet"
  fi
}

ensure_security_group() {
  if exists user_os security group show "$SG_NAME"; then
    log "security group $SG_NAME exists"
    return 0
  fi
  log "creating security group $SG_NAME (SSH and ICMP in)"
  user_os security group create --description "SSH and ping" "$SG_NAME" >/dev/null || die "could not create security group $SG_NAME"
  user_os security group rule create --ingress --protocol tcp --dst-port 22 "$SG_NAME" >/dev/null || die "could not add the SSH rule"
  user_os security group rule create --ingress --protocol icmp "$SG_NAME" >/dev/null || die "could not add the ICMP rule"
}

ensure_keypair() {
  if [ ! -f "$KEY_FILE" ]; then
    log "making the SSH key $KEY_FILE"
    mkdir -p "$(dirname "$KEY_FILE")" && chmod 700 "$(dirname "$KEY_FILE")"
    ssh-keygen -q -t ed25519 -N '' -C "orphera-demo" -f "$KEY_FILE" || die "ssh-keygen failed"
  fi
  if exists user_os keypair show "$KEY_NAME"; then
    log "key pair $KEY_NAME exists"
  else
    log "importing key pair $KEY_NAME"
    user_os keypair create --public-key "$KEY_FILE.pub" "$KEY_NAME" >/dev/null || die "could not import the key pair"
  fi
}

ensure_server() {
  if exists user_os server show "$VM_NAME"; then
    log "server $VM_NAME exists"
  else
    log "booting $VM_NAME (this waits until Nova reports it ACTIVE)"
    user_os server create --image "$IMAGE_NAME" --flavor "$FLAVOR" --network "$NET_ID" \
      --key-name "$KEY_NAME" --security-group "$SG_NAME" --config-drive true --wait "$VM_NAME" >/dev/null \
      || { user_os server show "$VM_NAME" -f value -c fault 2>/dev/null; die "the server did not come up"; }
  fi
  local status
  status="$(user_os server show "$VM_NAME" -f value -c status)"
  if [ "$status" != "ACTIVE" ]; then
    user_os server show "$VM_NAME" -f value -c fault 2>/dev/null
    die "server $VM_NAME is $status, not ACTIVE"
  fi
}

SERVER_IP=""
check_guest() {
  SERVER_IP="$(user_os server show "$VM_NAME" -f value -c addresses | grep -oE '[0-9]+\.[0-9]+\.[0-9]+\.[0-9]+' | head -n 1)"
  [ -n "$SERVER_IP" ] || die "server $VM_NAME has no address"
  log "$VM_NAME has $SERVER_IP; waiting for the guest to finish booting (up to ${BOOT_TIMEOUT}s)"

  local waited=0 booted=""
  while [ "$waited" -lt "$BOOT_TIMEOUT" ]; do
    if user_os console log show "$VM_NAME" 2>/dev/null | grep -qE 'Cloud-init v\..* finished at|^[^ ]+ login:'; then
      booted=1; break
    fi
    sleep 5; waited=$((waited + 5))
  done
  if [ -n "$booted" ]; then log "the console shows the guest has booted"
  else log "WARNING: no login prompt or cloud-init finish line on the console after ${BOOT_TIMEOUT}s"; fi

  local mac
  mac="$(openstack port list --server "$VM_NAME" -f value -c "MAC Address" 2>/dev/null | head -n 1)"
  if [ -n "$mac" ] && [ -x "$HERE/check-guest-dhcp.sh" ]; then
    "$HERE/check-guest-dhcp.sh" "$SERVER_IP" "$mac" "$NET_ID" || log "WARNING: the guest did not answer ARP in the DHCP namespace on tst19"
  fi

  # scala0 may not be on the provider network's VLAN; this is a best effort.
  local i
  for i in 1 2 3 4 5; do
    if ssh -i "$KEY_FILE" -o BatchMode=yes -o ConnectTimeout=5 -o StrictHostKeyChecking=accept-new \
         "ubuntu@$SERVER_IP" true 2>/dev/null; then
      log "SSH to ubuntu@$SERVER_IP works from here"
      return 0
    fi
    sleep 3
  done
  log "no SSH from this machine to $SERVER_IP (scala0 may not be on the provider network); the checks above are the evidence"
}

up() {
  load_admin
  ensure_project_and_user
  ensure_image
  ensure_flavor
  ensure_network
  ensure_security_group
  ensure_keypair
  ensure_server
  check_guest
  cat <<EOF

Environment ready.

  project    $PROJECT        user $DEMO_USER (domain Default)
  password   orphera secrets get $PW_SECRET
  Horizon    log in with domain Default, user $DEMO_USER, that password
  image      $IMAGE_NAME
  flavor     $FLAVOR
  network    $NET_NAME ($CIDR, DHCP)    no router, no floating IP: this Neutron has no L3
  server     $VM_NAME at $SERVER_IP
  ssh        ssh -i $KEY_FILE ubuntu@$SERVER_IP     (from a machine on the provider network)

Remove it with:  utils/openstack-minimal-env.sh down [--all]
EOF
}

# ---- down ------------------------------------------------------------------------------------------------

down() {
  local all=""
  [ "${1:-}" = "--all" ] && all=1
  load_admin

  if openstack user show --domain Default "$DEMO_USER" >/dev/null 2>&1 && load_demo_password; then
    if exists user_os server show "$VM_NAME"; then
      log "deleting server $VM_NAME"
      user_os server delete --wait "$VM_NAME" || log "WARNING: could not delete $VM_NAME"
    fi
    exists user_os keypair show "$KEY_NAME" && { log "deleting key pair $KEY_NAME"; user_os keypair delete "$KEY_NAME"; }
    exists user_os security group show "$SG_NAME" && { log "deleting security group $SG_NAME"; user_os security group delete "$SG_NAME"; }
  fi
  exists openstack user show --domain Default "$DEMO_USER" && { log "deleting user $DEMO_USER"; openstack user delete --domain Default "$DEMO_USER"; }
  exists openstack project show --domain Default "$PROJECT" && { log "deleting project $PROJECT"; openstack project delete --domain Default "$PROJECT"; }

  if [ -n "$all" ]; then
    exists openstack image show "$IMAGE_NAME" && { log "deleting image $IMAGE_NAME"; openstack image delete "$IMAGE_NAME"; }
    exists openstack flavor show "$FLAVOR" && { log "deleting flavor $FLAVOR"; openstack flavor delete "$FLAVOR"; }
    local found net sub
    found="$(openstack network list --provider-physical-network "$PHYSNET" -f value -c ID -c Name | head -n 1)"
    if [ -n "$found" ]; then
      net="${found%% *}"
      for sub in $(openstack subnet list --network "$net" -f value -c ID); do
        log "deleting subnet $sub"; openstack subnet delete "$sub"
      done
      log "deleting network ${found#* }"; openstack network delete "$net" || log "WARNING: could not delete the network (ports still in use?)"
    fi
    orphera secrets list 2>/dev/null | grep -qxF "$PW_SECRET" && { log "removing the secret $PW_SECRET"; orphera secrets rm "$PW_SECRET" >/dev/null; }
    log "kept: $KEY_FILE and the cached image in $CACHE_DIR"
  else
    log "kept: image, flavor, network and the stored password (down --all removes those too)"
  fi
}

case "${1:-up}" in
  up) up ;;
  down) shift; down "$@" ;;
  -h|--help) sed -n '2,/^set -uo/p' "$0" | sed '$d' | sed 's/^# \{0,1\}//' ;;
  *) die "usage: $0 [up | down [--all]]" ;;
esac
