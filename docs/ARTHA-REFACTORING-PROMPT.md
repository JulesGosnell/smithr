# Artha CI Refactoring: Adopt the Smithr Proxy Pattern

> **Context**: You are the Artha Claude. You wrote the current CI pipeline that
> calls Smithr's REST API directly to acquire leases, parse JSON, set up socat
> tunnels, and manage cleanup. Smithr now has a **proxy sidecar** that handles
> all of this automatically. Your job is to refactor the CI scripts to use it.

## What Changed in Smithr

Smithr now provides a **compose template API** at `GET /api/compose/:template`.
You `curl` the template, pipe it to `docker compose`, and get a container that:

1. Acquires a lease automatically on startup
2. Forwards the resource's ports to `localhost` via socat
3. Unleases automatically on container stop (SIGTERM) or crash (GC reaps)
4. Has health checks that verify port forwarding is live

**Available templates**: `android-phone`, `ios-phone`, `macos-build`, `android-build`

The proxy image is in the local registry at `localhost:5000/smithr/proxy:latest`
on both megalodon and prognathodon.

## The Pattern

### Before (what you wrote):
```bash
# Acquire lease
LEASE=$(curl -s -X POST http://localhost:7070/api/leases \
  -H 'Content-Type: application/json' \
  -d '{"type":"phone","platform":"android","lessee":"ci-123","ttl_seconds":1800}')
LEASE_ID=$(echo "$LEASE" | jq -r '.id')
TUNNEL_PORT=$(echo "$LEASE" | jq -r '.connection.tunnel_port')

# Set up local forwarding
socat TCP-LISTEN:5555,fork,reuseaddr TCP:10.21.0.1:$TUNNEL_PORT &
SOCAT_PID=$!

# ... tests use localhost:5555 ...

# Cleanup
kill $SOCAT_PID 2>/dev/null
curl -s -X DELETE "http://localhost:7070/api/leases/$LEASE_ID"
```

### After (what it should become):
```bash
# Start proxy (acquires lease + forwards ports automatically)
curl -s http://localhost:7070/api/compose/android-phone | \
  SMITHR_LESSEE="ci-123" \
  docker compose -f - -p android-phone up -d

# Wait for healthy (lease acquired, ports live)
timeout 30 docker compose -p android-phone wait android-phone 2>/dev/null || \
  docker compose -p android-phone logs

# ... tests use localhost:5555 (unchanged) ...

# Cleanup (unleases automatically)
curl -s http://localhost:7070/api/compose/android-phone | \
  docker compose -f - -p android-phone down
```

**No jq. No LEASE_ID. No TUNNEL_PORT. No socat. No trap.**

## What Needs Refactoring

### 1. Phone Leases in E2E Tests

**Files**: `.github/workflows/dev-build-test-deploy.yml` (lines ~328-357),
`bin/e2e-ci.sh`, `bin/e2e-vm-test.sh`

Currently these scripts acquire phone leases via curl, extract `tunnel_port`,
and set up socat forwarding. Replace with the proxy pattern.

**Port mapping**:
- Android phone: proxy forwards `localhost:5555` (ADB)
- iOS phone: proxy forwards `localhost:7001` (Maestro)

**Environment variable**: Set `SMITHR_LESSEE` to identify the lease:
```bash
SMITHR_LESSEE="ci-${{ github.run_id }}-$TEST_NAME"
```

### 2. Workspace/Build Leases

**Files**: `bin/apk-build.sh`, `bin/server-build.sh`, `bin/xcode-build.sh`

These acquire workspace leases (type=vm, lease_type=build) and SSH into the
workspace VM. The proxy now handles this too.

**Templates**: `android-build` or `macos-build`

The proxy mounts the SSH key at `/run/secrets/ssh-key` and provides a
`workspace-ssh` helper script. The workspace name IS the SSH username.

**Before** (what you wrote):
```bash
LEASE=$(curl -s -X POST http://localhost:7070/api/leases \
  -H 'Content-Type: application/json' \
  -d '{"type":"vm","platform":"macos","lease_type":"build","workspace":"artha-apk",...}')
SSH_PORT=$(echo "$LEASE" | jq -r '.connection.tunnel_port')
SSH_USER=$(echo "$LEASE" | jq -r '.connection.ssh_user')
ssh -i /srv/shared/images/ssh/macos-ssh-key -p $SSH_PORT $SSH_USER@localhost "xcodebuild ..."
```

**After**:
```bash
# Start workspace proxy (acquires build lease, forwards port 22, holds SSH key)
curl -s http://localhost:7070/api/compose/macos-build | \
  SMITHR_LESSEE="ci-123" \
  SMITHR_WORKSPACE="artha-apk" \
  docker compose -f - -p macos-build up -d
timeout 30 docker compose -p macos-build wait macos-build 2>/dev/null || true

# Run commands in workspace — no SSH key, no port, no jq
docker exec macos-build workspace-ssh "cd ~/artha && xcodebuild ..."

# Cleanup
curl -s http://localhost:7070/api/compose/macos-build | \
  docker compose -f - -p macos-build down
```

**No SSH key path. No tunnel port. No jq. No StrictHostKeyChecking flags.**

The `workspace-ssh` script inside the proxy reads the mounted key and the
lease metadata automatically. The workspace name = the SSH username.

### 3. Adopt Tunnels

**Files**: `bin/e2e-server.sh` (lines ~157-170)

The adopt API (`POST /api/adopt`) registers CI runner containers with Smithr so
workspace VMs can reach them. The proxy has an `adopt` mode for this.

**However**, the current adopt pattern is already clean (single curl call, auto-
cleanup on container stop). The proxy adds a container but doesn't simplify much.

**Recommendation**: Leave adopts as-is. They're already lean.

## Recommended Refactoring Order

1. **Android phone leases in `dev-build-test-deploy.yml`** — 4 parallel E2E
   test jobs each acquire a phone. This is the highest-impact change.

2. **Workspace/build leases** — `apk-build.sh`, `server-build.sh`,
   `xcode-build.sh`. Replace curl+jq+SSH with `docker compose up` +
   `docker exec workspace-ssh`.

3. **Android phone lease in `android-e2e.yml`** — Standalone Android E2E
   workflow, similar pattern.

4. **iOS phone leases** (if/when iOS E2E tests exist in workflow) — Same
   pattern with `ios-phone` template.

5. **Leave adopts as-is** — They're already lean (single curl call, auto-
   cleanup on container stop).

## Concrete Changes for `dev-build-test-deploy.yml`

In each of the 4 E2E test jobs (e2e-documents, e2e-profile, e2e-notifications,
e2e-schedule), find the phone lease acquisition block and replace it.

### Find this pattern (approximate):
```yaml
- name: Acquire Android phone
  run: |
    LEASE=$(curl -s -X POST http://localhost:7070/api/leases ...)
    LEASE_ID=$(echo "$LEASE" | jq -r '.id')
    TUNNEL_PORT=$(echo "$LEASE" | jq -r '.connection.tunnel_port')
    echo "LEASE_ID=$LEASE_ID" >> $GITHUB_ENV
    echo "TUNNEL_PORT=$TUNNEL_PORT" >> $GITHUB_ENV
```

### Replace with:
```yaml
- name: Start Android phone proxy
  run: |
    curl -s http://localhost:7070/api/compose/android-phone | \
      SMITHR_LESSEE="ci-${{ github.run_id }}-${{ matrix.test }}" \
      docker compose -f - -p phone-${{ matrix.test }} up -d
    # Wait for lease + port forwarding
    timeout 30 docker compose -p phone-${{ matrix.test }} wait android-phone 2>/dev/null || true
```

### Find the cleanup:
```yaml
- name: Cleanup phone
  if: always()
  run: curl -s -X DELETE "http://localhost:7070/api/leases/${{ env.LEASE_ID }}" || true
```

### Replace with:
```yaml
- name: Cleanup phone
  if: always()
  run: |
    curl -s http://localhost:7070/api/compose/android-phone | \
      docker compose -f - -p phone-${{ matrix.test }} down || true
```

### Remove from the test scripts:
- Any `socat TCP-LISTEN:5555` commands for ADB forwarding
- Any `TUNNEL_PORT` / `LEASE_ID` environment variable plumbing
- Any `fuser -k 5555/tcp` cleanup

### Keep unchanged:
- All test code that uses `localhost:5555` for ADB — the proxy forwards the
  same port
- All Maestro flows, Playwright tests, build scripts
- Workspace leases in build scripts
- Adopt calls in `e2e-server.sh`

## Testing Strategy

1. Pick ONE E2E test job (e.g., `e2e-documents`) and refactor just that one
2. Push to a branch and run the workflow
3. Verify: proxy container starts, lease acquired, tests pass, cleanup works
4. If green, refactor the other 3 E2E jobs
5. Remove any now-dead code (socat helpers, LEASE_ID plumbing)

## What Test Code Sees

**Nothing changes.** The proxy forwards canonical ports:

| Resource | Port | Protocol |
|----------|------|----------|
| Android phone | `localhost:5555` | ADB |
| iOS phone | `localhost:7001` | Maestro |
| macOS build | `localhost:22` | SSH |
| Android build | `localhost:22` | SSH |

Maestro flows, Playwright scripts, ADB commands, and SSH sessions all work
identically — they're talking to `localhost:PORT` either way.

## Important Notes

- The proxy image must be available on all runner hosts. It's already in the
  local registry (`localhost:5000/smithr/proxy:latest`) and can also be loaded
  from `/srv/shared/images/smithr-proxy.tar`
- The proxy container joins `smithr-network` — this network must exist (it does
  on all current runner hosts)
- Each proxy instance acquires exactly one lease. For 4 parallel tests, you get
  4 proxy containers and 4 leases
- If no resource is available, the proxy retries 5 times with 5s delay, then
  exits unhealthy — same behavior as the current retry loop in your scripts
- The `docker compose wait` command blocks until the container is healthy or
  fails — use `timeout` to cap it
