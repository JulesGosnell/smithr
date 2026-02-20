# Artha CI Fixes — Smithr Proxy Integration

> Prompt for an Artha agent. Three bugs in the CI pipeline need fixing.
> All three are Artha-side — Smithr proxy infrastructure is working correctly.

## Context

Artha CI (`dev-build-test-deploy.yml`) uses Smithr compose-based proxies for:
- **Build jobs**: `xcode-build.sh` → workspace proxy → macOS VM
- **E2E tests**: `e2e-ci.sh` → phone proxy + workspace proxy → android-build workspace

The proxy handles lease acquisition, SSH tunnels, and cleanup automatically.
Reverse tunnels (`SMITHR_REVERSE_PORTS`) expose server + phone ports as
`localhost` inside each workspace.

Failed run: https://github.com/JulesGosnell/artha/actions/runs/22234241123

---

## Bug 1: DNS Resolution — E2E Server Unreachable (CRITICAL)

**Symptom**: E2E Profile and Notifications tests fail — Playwright `page.goto('/login')`
gets `ERR_CONNECTION_REFUSED`. The server is running but unreachable from the workspace.

**Root cause**: `SERVER_TUNNEL_HOST` is `megalodon` (returned by Smithr adopt API).
The workspace proxy container builds SSH `-R` reverse tunnel commands like:

```
-R 3000:megalodon:17040
```

Inside Docker containers on smithr-network, `megalodon` resolves to `127.0.0.1`
(the container's own loopback) because Docker's embedded DNS falls back to host
DNS, where `/etc/hosts` maps the hostname to localhost. So the reverse tunnel
connects to the proxy container's own port 17040 (nothing there), not to the
host's port 17040 (where the adopt tunnel actually listens).

**Proof**:
```bash
docker run --rm --network smithr-network alpine sh -c "nslookup megalodon"
# megalodon → 127.0.0.1  (WRONG — should be the host)

docker run --rm --network smithr-network alpine sh -c "nslookup prognathodon"
# prognathodon → 192.168.0.75  (correct — different from localhost)
```

**Fix**: In `bin/e2e-ci.sh`, replace `SERVER_TUNNEL_HOST` with the Docker gateway
IP `10.21.0.1` when building `REVERSE_PORTS`. The gateway is how containers on
smithr-network reach the host machine's ports.

**File**: `bin/e2e-ci.sh`, around line 130 where `REVERSE_PORTS` is constructed.

**Current code**:
```bash
REVERSE_PORTS="5555:${PHONE_CONTAINER}:5555,3000:$SERVER_TUNNEL_HOST:$SERVER_HTTP_PORT,4443:$SERVER_TUNNEL_HOST:$SERVER_HTTPS_PORT"
```

**Fixed code**:
```bash
# Docker gateway: how containers on smithr-network reach host ports.
# Cannot use SERVER_TUNNEL_HOST (hostname) because Docker DNS resolves
# "megalodon" to 127.0.0.1 inside containers (falls back to host /etc/hosts).
DOCKER_GATEWAY="10.21.0.1"
REVERSE_PORTS="5555:${PHONE_CONTAINER}:5555,3000:${DOCKER_GATEWAY}:$SERVER_HTTP_PORT,4443:${DOCKER_GATEWAY}:$SERVER_HTTPS_PORT"
```

**Why PHONE_CONTAINER is fine**: `${PHONE_CONTAINER}` is a Docker container name
(e.g. `phone-documents-android-phone-1`) which Docker's embedded DNS resolves
correctly on user-defined bridge networks. Only bare hostnames like `megalodon`
have the loopback problem.

---

## Bug 2: iOS Device Build — Artifact Streaming Killed (exit 137)

**Symptom**: `build-ios-device` job fails with exit 137 (SIGKILL). The Xcode
build itself succeeds (`BUILD SUCCEEDED` appears in logs), but the artifact
tar.gz streaming is killed ~3 seconds after it starts.

**What happens**:
1. `xcode-build.sh` runs `docker exec $CONTAINER workspace-ssh "bash ~/xcode-vm-build.sh"`
2. `xcode-vm-build.sh` on the macOS VM does `xcodebuild` (logs to stderr),
   then `tar -czf - $APP_PATH` (streams to stdout → back through SSH → docker exec → file)
3. The tar streaming starts, then something kills the process chain

**Likely cause**: The workspace proxy container's health check fails during the
long build, and Docker kills/restarts it. Or the Smithr lease TTL (3600s)
expires during a cold production build (arm64 Release builds are slow).

**Investigation needed**:
- Check the proxy container logs: `docker logs $CONTAINER 2>&1 | tail -50`
- Check if the lease was GC'd: the proxy logs "Lease acquired" with TTL info
- Check Docker events: `docker events --filter container=$CONTAINER` (if reproducible)

**Potential fixes** (try in order):

1. **Increase TTL for device builds**: In `.github/workflows/dev-build-test-deploy.yml`,
   the `build-ios-device` step uses `SMITHR_TTL: "3600"` (1 hour). If cold production
   builds take longer, increase to `7200`.

2. **Save artifact before cleanup**: In `bin/xcode-build.sh`, the `docker exec`
   pipes stdout directly to the artifact file. If the pipe breaks, nothing is saved.
   Consider a two-step approach:
   ```bash
   # Instead of streaming directly:
   # docker exec "$CONTAINER" workspace-ssh "bash ~/xcode-vm-build.sh" > "$ARTIFACT_DIR/$ARTIFACT_NAME"

   # Stream to temp, then move:
   docker exec "$CONTAINER" workspace-ssh "bash ~/xcode-vm-build.sh" > "$ARTIFACT_DIR/$ARTIFACT_NAME.tmp"
   mv "$ARTIFACT_DIR/$ARTIFACT_NAME.tmp" "$ARTIFACT_DIR/$ARTIFACT_NAME"
   ```
   This doesn't fix the kill, but at least you can distinguish "killed mid-stream"
   (tmp exists, final doesn't) from "build failed" (neither exists).

3. **Add retry for artifact streaming**: If the build succeeded but streaming failed,
   the artifact exists on the VM. Add a fallback that re-downloads:
   ```bash
   if [[ ! -s "$ARTIFACT_DIR/$ARTIFACT_NAME" ]]; then
     log "Retrying artifact download..."
     docker exec "$CONTAINER" workspace-ssh "cat ~/build-output.tar.gz" > "$ARTIFACT_DIR/$ARTIFACT_NAME"
   fi
   ```
   This requires `xcode-vm-build.sh` to tee the tar to both stdout AND a file on the VM.

---

## Bug 3: ADB Broken Pipe During APK Install (E2E Documents/Schedule)

**Symptom**: E2E Documents and Schedule tests connect to the Android phone
successfully (ADB reports SDK 28, Pixel 4 emulator) but `adb install` fails
with `Broken pipe (32)` partway through the APK transfer.

**What happens**:
1. Phone proxy acquires phone lease, port 5555 tunneled
2. Workspace proxy sets up reverse tunnel: `-R 5555:phone-container:5555`
3. Inside workspace: `adb connect localhost:5555` succeeds, device shows online
4. `adb install app-release.apk` starts transferring the ~50MB APK
5. Transfer breaks mid-stream with EPIPE

**Likely cause**: The ADB connection goes through a 3-hop chain:
```
workspace → SSH reverse tunnel → proxy container → SSH forward tunnel → phone container → emulator
```
This is fragile for large sustained transfers. TCP keepalives or buffer sizes
may cause the SSH session to drop.

**Potential fixes**:

1. **Copy APK first, then install locally**: Instead of streaming the APK through
   the ADB tunnel, copy it to the workspace first (already done in `e2e-ci.sh`
   step 3), then inside the workspace:
   ```bash
   # In e2e-vm-test.sh: instead of adb install from local path
   adb push ~/app-release.apk /data/local/tmp/app-release.apk
   adb shell pm install /data/local/tmp/app-release.apk
   ```
   `adb push` may be more resilient than `adb install` since install also does
   verification/installation logic.

2. **Add retry logic**: Wrap `adb install` in a retry loop:
   ```bash
   for attempt in 1 2 3; do
     adb install -r ~/app-release.apk && break
     echo "ADB install attempt $attempt failed, retrying..."
     adb disconnect localhost:5555
     sleep 2
     adb connect localhost:5555
     sleep 2
   done
   ```

3. **Check if this is actually Bug 1 in disguise**: If the ADB broken pipe only
   happens on tests that also fail to reach the server (Documents, Schedule),
   the phone tunnel might be fine — the test might be failing at a later stage
   (trying to use the app after APK install, where it needs the server).
   Fix Bug 1 first and re-run before investigating this further.

---

## Recommended Fix Order

1. **Fix Bug 1 first** (DNS resolution) — this is definitively diagnosed and has
   a one-line fix. It will unblock Profile and Notifications tests.
2. **Re-run CI** after Bug 1 fix — Documents/Schedule may also start working if
   Bug 3 was caused by the server being unreachable (the app might crash during
   install when it can't reach the API).
3. **Fix Bug 2** (iOS device artifact) — increase TTL or add retry logic.
4. **Fix Bug 3** only if it persists after Bug 1 is fixed.

## Key Files

| File | What to change |
|------|---------------|
| `bin/e2e-ci.sh:~130` | Replace `SERVER_TUNNEL_HOST` with `10.21.0.1` in REVERSE_PORTS |
| `bin/xcode-build.sh` | Investigate exit 137, add artifact retry |
| `bin/e2e-vm-test.sh` | ADB install retry (if needed after Bug 1 fix) |
| `.github/workflows/dev-build-test-deploy.yml` | Increase SMITHR_TTL for device builds |

## Reference: How Smithr Tunnels Work

```
                    Forward tunnel (SSH -L)                Reverse tunnel (SSH -R)
                    created by Smithr on lease             created by proxy container
┌──────────┐     ┌──────────────────┐     ┌───────────────┐     ┌──────────────────┐
│  Smithr  │────▶│ Host port :NNNNN │────▶│ Proxy (sshd)  │────▶│ Workspace user   │
│  Host    │     │ (adopt tunnel)   │     │ smithr-network│     │ on android-build │
└──────────┘     └──────────────────┘     └───────────────┘     └──────────────────┘
                                              │
                                              │ -R 3000:TARGET:PORT
                                              │ TARGET must be reachable FROM proxy container
                                              │ ✗ megalodon → 127.0.0.1 (container loopback)
                                              │ ✓ 10.21.0.1 → host machine (Docker gateway)
                                              │ ✓ container-name → Docker DNS (correct)
```
