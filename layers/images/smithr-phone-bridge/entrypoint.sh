#!/bin/bash
# Physical phone bridge — proxies device protocol from host bridge to container.
#
# Android: socat container:5555 → host:BRIDGE_PORT (ADB)
# iOS:     socat container:62078 → host:BRIDGE_PORT (lockdown)
#          + RSD tunnel → iptables REDIRECT + rsd-relay.py (all service ports)
#
# Env vars:
#   BRIDGE_HOST  — Host address to reach the bridge (default: 10.21.0.1)
#   BRIDGE_PORT  — Host port where the bridge is listening (required)
#   SERIAL       — Device serial/UDID (informational)
#   PLATFORM     — android | ios (default: android)
set -e

: "${BRIDGE_PORT:?BRIDGE_PORT is required}"
BRIDGE_HOST="${BRIDGE_HOST:-10.21.0.1}"
PLATFORM="${PLATFORM:-android}"

# Port for the transparent relay proxy (outside the redirected range)
RELAY_PORT=9999

log() { echo "[phone-bridge] $*" >&2; }

cleanup() {
  log "Shutting down..."
  kill $(jobs -p) 2>/dev/null
  wait
}
trap cleanup EXIT

# Set up SSH authorized_keys for near-side access
if [ -f /root/.ssh/authorized_keys.mount ]; then
  cp /root/.ssh/authorized_keys.mount /root/.ssh/authorized_keys
  chmod 600 /root/.ssh/authorized_keys
fi
chmod 700 /root/.ssh 2>/dev/null || true

# Start sshd (for near-side access — tools run here, not on the client)
/usr/sbin/sshd 2>/dev/null && log "sshd started" || log "sshd not available, skipping"

case "$PLATFORM" in
  ios)
    LISTEN_PORT=62078
    log "Platform: iOS"
    log "Device: ${SERIAL:-unknown}"
    log "Lockdown bridge: ${BRIDGE_HOST}:${BRIDGE_PORT} → container:${LISTEN_PORT}"

    # Start lockdown bridge
    socat TCP-LISTEN:${LISTEN_PORT},fork,reuseaddr \
      "TCP:${BRIDGE_HOST}:${BRIDGE_PORT}" &
    SOCAT_PID=$!

    # Start RSD tunnel (needs CAP_NET_ADMIN + /dev/net/tun + /var/run/usbmuxd)
    if [ -S /var/run/usbmuxd ] && [ -n "$SERIAL" ]; then
      log "Starting RSD tunnel for UDID: $SERIAL"
      cd /opt/py_ios_rsd_tunnel
      PYTHONUNBUFFERED=1 python3 -m ios_rsd_tunnel tunnel -u "$SERIAL" \
        > /tmp/rsd-tunnel.json &
      RSD_PID=$!

      # Wait for tunnel to produce JSON output (up to 30s)
      for i in $(seq 1 60); do
        if [ -s /tmp/rsd-tunnel.json ]; then
          RSD_IPV6=$(python3 -c "import json; d=json.load(open('/tmp/rsd-tunnel.json')); print(d['ipv6'])")
          RSD_TUNNEL_PORT=$(python3 -c "import json; d=json.load(open('/tmp/rsd-tunnel.json')); print(d['port'])")
          log "RSD tunnel ready: [${RSD_IPV6}]:${RSD_TUNNEL_PORT}"

          # iptables REDIRECT: forward all ephemeral-range TCP to relay proxy.
          # Skip lockdown port (62078) — that goes to the socat bridge above.
          iptables -t nat -A PREROUTING -p tcp --dport ${LISTEN_PORT} -j RETURN
          iptables -t nat -A PREROUTING -p tcp --dport 49152:65535 -j REDIRECT --to-port ${RELAY_PORT}
          log "iptables REDIRECT 49152:65535 → relay:${RELAY_PORT}"

          # Start transparent relay proxy
          python3 /rsd-relay.py ${RELAY_PORT} "${RSD_IPV6}" &
          log "RSD relay proxy started (pid $!)"

          echo "$RSD_IPV6 $RSD_TUNNEL_PORT" > /tmp/rsd-ready
          break
        fi
        sleep 0.5
      done

      if [ ! -f /tmp/rsd-ready ]; then
        log "WARNING: RSD tunnel failed to start (lockdown bridge still works)"
      fi
    else
      log "RSD tunnel skipped (no usbmuxd socket or SERIAL not set)"
    fi

    # Wait for lockdown socat — if it dies, container exits.
    # RSD tunnel dying is non-fatal (lockdown bridge still works).
    wait $SOCAT_PID
    ;;

  *)
    LISTEN_PORT=5555
    log "Platform: Android"
    log "Device: ${SERIAL:-unknown}"
    log "Bridge: ${BRIDGE_HOST}:${BRIDGE_PORT} → container:${LISTEN_PORT}"

    exec socat TCP-LISTEN:${LISTEN_PORT},fork,reuseaddr \
      "TCP:${BRIDGE_HOST}:${BRIDGE_PORT}"
    ;;
esac
