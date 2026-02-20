#!/bin/bash
# Generate TLS certificates for Smithr inter-host Docker communication.
#
# Creates:
#   ca.pem, ca-key.pem           — Certificate Authority
#   server-megalodon.pem/key     — Docker daemon cert for megalodon
#   server-prognathodon.pem/key  — Docker daemon cert for prognathodon
#   client.pem, client-key.pem   — Client cert for Smithr
#
# Usage:
#   cd smithr/tls && bash generate-certs.sh
#
# Then deploy with:
#   bash deploy-certs.sh

set -euo pipefail

CERT_DIR="$(cd "$(dirname "$0")" && pwd)"
DAYS=3650  # 10 years

echo "=== Generating Smithr Docker TLS certificates ==="
echo "Output: $CERT_DIR"

# --- CA ---
echo "--- CA ---"
openssl genrsa -out "$CERT_DIR/ca-key.pem" 4096
openssl req -new -x509 -days $DAYS -key "$CERT_DIR/ca-key.pem" \
  -sha256 -out "$CERT_DIR/ca.pem" \
  -subj "/CN=Smithr Docker CA"

# --- Server cert for megalodon ---
echo "--- Server: megalodon ---"
openssl genrsa -out "$CERT_DIR/server-megalodon-key.pem" 4096
openssl req -new -key "$CERT_DIR/server-megalodon-key.pem" \
  -subj "/CN=megalodon" \
  -out "$CERT_DIR/server-megalodon.csr"

cat > "$CERT_DIR/server-megalodon-ext.cnf" <<EXTEOF
subjectAltName = DNS:megalodon,DNS:localhost,IP:127.0.0.1,IP:192.168.0.73
extendedKeyUsage = serverAuth
EXTEOF

openssl x509 -req -days $DAYS -sha256 \
  -in "$CERT_DIR/server-megalodon.csr" \
  -CA "$CERT_DIR/ca.pem" -CAkey "$CERT_DIR/ca-key.pem" -CAcreateserial \
  -extfile "$CERT_DIR/server-megalodon-ext.cnf" \
  -out "$CERT_DIR/server-megalodon.pem"

# --- Server cert for prognathodon ---
echo "--- Server: prognathodon ---"
openssl genrsa -out "$CERT_DIR/server-prognathodon-key.pem" 4096
openssl req -new -key "$CERT_DIR/server-prognathodon-key.pem" \
  -subj "/CN=prognathodon" \
  -out "$CERT_DIR/server-prognathodon.csr"

cat > "$CERT_DIR/server-prognathodon-ext.cnf" <<EXTEOF
subjectAltName = DNS:prognathodon,DNS:localhost,IP:127.0.0.1,IP:192.168.0.75
extendedKeyUsage = serverAuth
EXTEOF

openssl x509 -req -days $DAYS -sha256 \
  -in "$CERT_DIR/server-prognathodon.csr" \
  -CA "$CERT_DIR/ca.pem" -CAkey "$CERT_DIR/ca-key.pem" -CAcreateserial \
  -extfile "$CERT_DIR/server-prognathodon-ext.cnf" \
  -out "$CERT_DIR/server-prognathodon.pem"

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
