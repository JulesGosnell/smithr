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

# Write environment for SSH sessions (sshd doesn't inherit Docker ENV vars)
JAVA_HOME=$(dirname $(dirname $(readlink -f $(which java))))
cat > /etc/profile.d/smithr.sh <<ENVEOF
export PATH="/opt/maestro/bin:${PATH}"
export JAVA_HOME="${JAVA_HOME}"
export MAESTRO_CLI_NO_ANALYTICS=true
export MAESTRO_CLI_ANALYSIS_NOTIFICATION_DISABLED=true
export PHONE_HOST="${PHONE_HOST}"
export PLATFORM="${PLATFORM}"
ENVEOF
# Also set for non-login shells (scp, direct command)
cp /etc/profile.d/smithr.sh /root/.bashrc

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
  # Append ANDROID_SERIAL to SSH env so remote sessions see it
  echo "export ANDROID_SERIAL=\"${PHONE_HOST}:5555\"" >> /etc/profile.d/smithr.sh
  echo "export ANDROID_SERIAL=\"${PHONE_HOST}:5555\"" >> /root/.bashrc
fi

# Verify Maestro is available
if command -v maestro >/dev/null 2>&1; then
  log "Maestro: $(maestro --version 2>/dev/null || echo 'installed')"
else
  log "WARNING: Maestro not found in PATH"
fi

log "Ready. Platform: $PLATFORM, Phone: $PHONE_HOST"
exec sleep infinity
