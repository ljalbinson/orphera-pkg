#!/usr/bin/env bash
# SPDX-License-Identifier: Apache-2.0
#
# Makes the private CA and the wildcard server certificate the agents use
# (`make certs DOMAIN=yourdomain.com`), in certs/:
#
#   ca.crt, ca.key         the CA: ca.crt goes to the orchestrator and into the agent package
#   server.crt, server.key the certificate every agent presents: *.DOMAIN, localhost, 127.0.0.1
#
# The keys are mode 600 and git-ignored; never commit them. Refuses to run when
# certs/ca.crt exists, so a CA that agents already trust is not replaced by accident:
# move certs/ away (or `make certs-clean`) first, knowing that every agent must then be
# reinstalled with the new certificate (`orphera bootstrap --file <new .deb> --nodes ...`,
# which works over SSH and does not need the old certificate to be trusted).
#
# Usage: utils/gen-certs.sh DOMAIN [DAYS]     (DAYS defaults to 1825, five years)
set -euo pipefail

DOMAIN=${1:?usage: utils/gen-certs.sh DOMAIN [DAYS]}
DAYS=${2:-1825}
DIR=${CERTS_DIR:-certs}

if [ -e "$DIR/ca.crt" ]; then
  echo "$DIR/ca.crt already exists: not replacing a CA that agents may trust." >&2
  echo "Move $DIR away (or run 'make certs-clean') if you mean to make a new one." >&2
  exit 1
fi

mkdir -p "$DIR"
umask 077
cd "$DIR"

SAN="subjectAltName=DNS:*.$DOMAIN,DNS:$DOMAIN,DNS:localhost,IP:127.0.0.1"

# The CA.
openssl req -x509 -newkey rsa:4096 -nodes -days "$DAYS" \
  -keyout ca.key -out ca.crt -subj "/CN=Orphera CA ($DOMAIN)" \
  -addext "basicConstraints=critical,CA:TRUE" \
  -addext "keyUsage=critical,keyCertSign,cRLSign"

# The server certificate, signed by it.
openssl req -newkey rsa:4096 -nodes \
  -keyout server.key -out server.csr -subj "/CN=*.$DOMAIN"

openssl x509 -req -in server.csr -CA ca.crt -CAkey ca.key -CAcreateserial \
  -out server.crt -days "$DAYS" \
  -extfile <(printf '%s\nbasicConstraints=CA:FALSE\nkeyUsage=digitalSignature,keyEncipherment\nextendedKeyUsage=serverAuth\n' "$SAN")

chmod 600 ca.key server.key
chmod 644 ca.crt server.crt

echo
echo "Made $DIR/ca.crt, $DIR/ca.key, $DIR/server.crt, $DIR/server.key"
openssl x509 -in server.crt -noout -subject -dates -ext subjectAltName
openssl verify -CAfile ca.crt server.crt
