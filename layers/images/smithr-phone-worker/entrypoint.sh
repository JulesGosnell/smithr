#!/bin/bash
# smithr-phone-worker entrypoint
#
# Universal near-side worker container. Provides SSH access + testing
# tools (Maestro, ADB) colocated with the phone resource on smithr-network.
#
# Env vars:
#   PHONE_HOST   — Docker IP or hostname of the phone resource (required)
#   PLATFORM     — android | ios (default: android)
#   SSH_AUTHORIZED_KEY — public key string to authorize (optional, alternative to volume mount)
set -e

: "${PHONE_HOST:?PHONE_HOST required (Docker IP or hostname of phone resource)}"
PLATFORM="${PLATFORM:-android}"

log() { echo "[worker] $*" >&2; }

# Set up SSH authorized_keys
if [ -f /root/.ssh/authorized_keys.mount ]; then
  cp /root/.ssh/authorized_keys.mount /root/.ssh/authorized_keys
  chmod 600 /root/.ssh/authorized_keys
elif [ -n "$SSH_AUTHORIZED_KEY" ]; then
  echo "$SSH_AUTHORIZED_KEY" > /root/.ssh/authorized_keys
  chmod 600 /root/.ssh/authorized_keys
fi
chmod 700 /root/.ssh

# Generate host keys if missing (Alpine ssh-keygen -A in Dockerfile should handle this)
[ -f /etc/ssh/ssh_host_rsa_key ] || ssh-keygen -A

# Start sshd
/usr/sbin/sshd
log "sshd started."

# Connect ADB to phone resource (Android only)
if [ "$PLATFORM" = "android" ]; then
  log "Connecting ADB to ${PHONE_HOST}:5555..."
  for i in $(seq 1 60); do
    if adb connect "${PHONE_HOST}:5555" 2>/dev/null | grep -q "connected"; then
      break
    fi
    sleep 2
  done
  adb -s "${PHONE_HOST}:5555" wait-for-device
  log "ADB connected to ${PHONE_HOST}:5555"
  export ANDROID_SERIAL="${PHONE_HOST}:5555"
fi

log "Ready. Platform: $PLATFORM, Phone: $PHONE_HOST"
exec sleep infinity
