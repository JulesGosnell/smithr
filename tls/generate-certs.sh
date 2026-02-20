#!/bin/bash
# Generate TLS certificates for Smithr inter-host Docker communication.
#
# Creates:
#   ca.pem, ca-key.pem           — Certificate Authority
#   server-<host>.pem/key        — Docker daemon cert per host
#   client.pem, client-key.pem   — Client cert for Smithr
#
# Usage:
#   cd smithr/tls && bash generate-certs.sh host1 host2 [host3 ...]
#
# Example:
#   bash generate-certs.sh megalodon prognathodon
#
# Each host argument creates a server-<host>.pem and server-<host>-key.pem.
# Optionally set SMITHR_HOST_IPS to a comma-separated map of host=IP pairs:
#   SMITHR_HOST_IPS="megalodon=192.168.0.73,prognathodon=192.168.0.75" bash generate-certs.sh megalodon prognathodon
#
# Then deploy with:
#   bash deploy-certs.sh

set -euo pipefail

if [[ $# -lt 1 ]]; then
  echo "Usage: $0 <host1> <host2> [host3 ...]"
  echo "Example: $0 megalodon prognathodon"
  exit 1
fi

CERT_DIR="$(cd "$(dirname "$0")" && pwd)"
DAYS=3650  # 10 years
HOSTS=("$@")

# Parse optional host→IP map from SMITHR_HOST_IPS env var
declare -A HOST_IPS=()
if [[ -n "${SMITHR_HOST_IPS:-}" ]]; then
  IFS=',' read -ra PAIRS <<< "$SMITHR_HOST_IPS"
  for pair in "${PAIRS[@]}"; do
    IFS='=' read -r host ip <<< "$pair"
    HOST_IPS["$host"]="$ip"
  done
fi

echo "=== Generating Smithr Docker TLS certificates ==="
echo "Output: $CERT_DIR"
echo "Hosts: ${HOSTS[*]}"

# --- CA ---
echo "--- CA ---"
openssl genrsa -out "$CERT_DIR/ca-key.pem" 4096
openssl req -new -x509 -days $DAYS -key "$CERT_DIR/ca-key.pem" \
  -sha256 -out "$CERT_DIR/ca.pem" \
  -subj "/CN=Smithr Docker CA"

# --- Server certs (one per host) ---
for host in "${HOSTS[@]}"; do
  echo "--- Server: $host ---"
  openssl genrsa -out "$CERT_DIR/server-${host}-key.pem" 4096
  openssl req -new -key "$CERT_DIR/server-${host}-key.pem" \
    -subj "/CN=${host}" \
    -out "$CERT_DIR/server-${host}.csr"

  # Build SAN list: always include hostname + localhost + 127.0.0.1
  san="DNS:${host},DNS:localhost,IP:127.0.0.1"
  if [[ -n "${HOST_IPS[$host]:-}" ]]; then
    san="${san},IP:${HOST_IPS[$host]}"
  fi

  cat > "$CERT_DIR/server-${host}-ext.cnf" <<EXTEOF
subjectAltName = ${san}
extendedKeyUsage = serverAuth
EXTEOF

  openssl x509 -req -days $DAYS -sha256 \
    -in "$CERT_DIR/server-${host}.csr" \
    -CA "$CERT_DIR/ca.pem" -CAkey "$CERT_DIR/ca-key.pem" -CAcreateserial \
    -extfile "$CERT_DIR/server-${host}-ext.cnf" \
    -out "$CERT_DIR/server-${host}.pem"
done

# --- Client cert (for Smithr) ---
echo "--- Client: smithr ---"
openssl genrsa -out "$CERT_DIR/client-key.pem" 4096
openssl req -new -key "$CERT_DIR/client-key.pem" \
  -subj "/CN=smithr-client" \
  -out "$CERT_DIR/client.csr"

cat > "$CERT_DIR/client-ext.cnf" <<EXTEOF
extendedKeyUsage = clientAuth
EXTEOF

openssl x509 -req -days $DAYS -sha256 \
  -in "$CERT_DIR/client.csr" \
  -CA "$CERT_DIR/ca.pem" -CAkey "$CERT_DIR/ca-key.pem" -CAcreateserial \
  -extfile "$CERT_DIR/client-ext.cnf" \
  -out "$CERT_DIR/client.pem"

# --- Cleanup CSRs and temp files ---
rm -f "$CERT_DIR"/*.csr "$CERT_DIR"/*.cnf "$CERT_DIR"/*.srl

# --- Set permissions ---
chmod 600 "$CERT_DIR"/*-key.pem
chmod 644 "$CERT_DIR"/ca.pem "$CERT_DIR"/server-*.pem "$CERT_DIR"/client.pem

echo ""
echo "=== Done! ==="
echo "Files:"
ls -la "$CERT_DIR"/*.pem
echo ""
echo "Next: run 'bash deploy-certs.sh' to install on both hosts."
