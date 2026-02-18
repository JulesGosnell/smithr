#!/bin/bash
# Deploy TLS certificates and configure Docker daemon on THIS host.
#
# Auto-detects hostname and installs the correct server cert.
# Run on each host individually:
#
#   cd smithr/tls && sudo bash deploy-certs.sh
#
# Prerequisites:
#   - Run generate-certs.sh first (certs must be in this directory)

set -euo pipefail

CERT_DIR="$(cd "$(dirname "$0")" && pwd)"
TLS_DIR="/etc/smithr/tls"
HOST="$(hostname)"

# --- Verify this host has a server cert ---
if [ ! -f "$CERT_DIR/server-${HOST}.pem" ]; then
  echo "ERROR: No server cert for '$HOST' — expected server-${HOST}.pem"
  echo "Available: $(ls "$CERT_DIR"/server-*.pem 2>/dev/null | sed 's/.*server-//;s/\.pem//' | tr '\n' ' ')"
  exit 1
fi

for f in ca.pem client.pem client-key.pem; do
  if [ ! -f "$CERT_DIR/$f" ]; then
    echo "ERROR: Missing $f — run generate-certs.sh first"
    exit 1
  fi
done

echo "=== Deploying Smithr Docker TLS on $HOST ==="

# --- Install certs ---
echo "--- Installing certs to $TLS_DIR ---"
mkdir -p "$TLS_DIR"
cp "$CERT_DIR/ca.pem"                     "$TLS_DIR/ca.pem"
cp "$CERT_DIR/server-${HOST}.pem"         "$TLS_DIR/server-cert.pem"
cp "$CERT_DIR/server-${HOST}-key.pem"     "$TLS_DIR/server-key.pem"
cp "$CERT_DIR/client.pem"                 "$TLS_DIR/cert.pem"
cp "$CERT_DIR/client-key.pem"             "$TLS_DIR/key.pem"
chmod 644 "$TLS_DIR/ca.pem" "$TLS_DIR/server-cert.pem" "$TLS_DIR/cert.pem"
chmod 600 "$TLS_DIR/server-key.pem" "$TLS_DIR/key.pem"

# --- Configure Docker daemon ---
echo "--- Configuring Docker daemon ---"
mkdir -p /etc/docker
python3 -c "
import json, os
path = '/etc/docker/daemon.json'
cfg = {}
if os.path.exists(path):
    with open(path) as f:
        cfg = json.load(f)
cfg['hosts'] = ['unix:///var/run/docker.sock', 'tcp://0.0.0.0:2376']
cfg['tls'] = True
cfg['tlsverify'] = True
cfg['tlscacert'] = '$TLS_DIR/ca.pem'
cfg['tlscert'] = '$TLS_DIR/server-cert.pem'
cfg['tlskey'] = '$TLS_DIR/server-key.pem'
with open(path, 'w') as f:
    json.dump(cfg, f, indent=2)
print('daemon.json updated')
"

# --- Systemd override: remove -H fd:// so daemon.json hosts are used ---
mkdir -p /etc/systemd/system/docker.service.d
cat > /etc/systemd/system/docker.service.d/override.conf <<OVERRIDE
[Service]
ExecStart=
ExecStart=/usr/bin/dockerd --containerd=/run/containerd/containerd.sock
OVERRIDE

systemctl daemon-reload
systemctl restart docker

echo ""
echo "=== Done! Docker on $HOST now has TLS on port 2376 ==="
echo ""
echo "Verify:"
echo "  docker --tlsverify --tlscacert=$TLS_DIR/ca.pem --tlscert=$TLS_DIR/cert.pem --tlskey=$TLS_DIR/key.pem -H tcp://$HOST:2376 info"
