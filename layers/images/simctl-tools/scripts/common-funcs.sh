# common-funcs.sh — Shared utility functions for iOS sidecars.
# Source this file to get logging, SSH wait, and readiness helpers.
#
# Expects:
#   SIDECAR_NAME — prefix for log messages (e.g., "ios-app", "ios-maestro")
#
# Provides:
#   log()             — timestamped log with sidecar name prefix
#   wait_for_ssh()    — poll SSH until connected (up to 60 attempts, 2s apart)
#   mark_ready()      — touch a readiness marker file

T0=$(date +%s)

log() { echo "[$SIDECAR_NAME] [$(( $(date +%s) - T0 ))s] $*"; }

# Wait for SSH target to become reachable.
# Requires: remote() from ssh-common.sh, SSH_TARGET, SSH_USER
wait_for_ssh() {
    log "Waiting for SSH at $SSH_TARGET (user: $SSH_USER)..."
    for _i in $(seq 1 60); do
        if remote "echo ok" >/dev/null 2>&1; then
            break
        fi
        sleep 2
    done
    log "SSH connected."
}

# Signal readiness by touching a marker file.
# Usage: mark_ready /tmp/app-installed
mark_ready() {
    touch "$1"
}
