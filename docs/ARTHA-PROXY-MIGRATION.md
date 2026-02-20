# Artha Proxy Migration Guide

> How to migrate Artha's CI workflows from direct Smithr API calls
> to the smithr-proxy sidecar pattern.

## Overview

Currently, Artha's GitHub Actions workflows call the Smithr REST API directly
to acquire leases, parse connection details, set up socat tunnels, and clean up
on exit. The smithr-proxy sidecar handles all of this automatically:

- Acquires a lease on startup
- Creates socat port forwards to the tunnelled resource
- Unleases on container stop (SIGTERM/SIGINT) or GC reaps on crash
- Health checks verify the forwarded ports are live

## Before (direct API calls)

```yaml
# .github/workflows/e2e-android.yml (simplified)
steps:
  - name: Acquire Android phone
    run: |
      LEASE=$(curl -s -X POST http://localhost:7070/api/leases \
        -H 'Content-Type: application/json' \
        -d '{"type":"phone","platform":"android","lessee":"ci-${{ github.run_id }}"}')
      LEASE_ID=$(echo "$LEASE" | jq -r '.id')
      TUNNEL_PORT=$(echo "$LEASE" | jq -r '.connection.tunnel_port')
      echo "LEASE_ID=$LEASE_ID" >> $GITHUB_ENV
      echo "TUNNEL_PORT=$TUNNEL_PORT" >> $GITHUB_ENV
      socat TCP-LISTEN:5555,fork,reuseaddr TCP:10.21.0.1:$TUNNEL_PORT &

  # ... test steps using localhost:5555 ...

  - name: Cleanup
    if: always()
    run: curl -sf -X DELETE "http://localhost:7070/api/leases/$LEASE_ID" || true
```

## After (smithr-proxy sidecar)

### Option A: API-served compose (recommended, works immediately)

```yaml
steps:
  - name: Start Android phone proxy
    run: |
      curl -s http://localhost:7070/api/compose/android-phone | \
        SMITHR_LESSEE="ci-${{ github.run_id }}" \
        docker compose -f - -p android-phone up -d
      # Wait for proxy to be healthy (lease acquired, ports forwarded)
      docker compose -p android-phone wait android-phone

  # ... test steps using localhost:5555 (unchanged) ...

  - name: Cleanup
    if: always()
    run: |
      curl -s http://localhost:7070/api/compose/android-phone | \
        docker compose -f - -p android-phone down
```

### Option B: OCI artifact (requires insecure-registries config)

```yaml
steps:
  - name: Start Android phone proxy
    run: |
      SMITHR_LESSEE="ci-${{ github.run_id }}" \
        docker compose -f oci://localhost:5000/smithr/android-phone:latest \
        -p android-phone up -d

  # ... test steps ...

  - name: Cleanup
    if: always()
    run: |
      docker compose -p android-phone down
```

### Option C: Inline compose (no external dependencies)

```yaml
steps:
  - name: Start Android phone proxy
    run: |
      cat <<'EOF' | SMITHR_LESSEE="ci-${{ github.run_id }}" docker compose -f - -p android-phone up -d
      services:
        android-phone:
          image: smithr-proxy:latest
          environment:
            SMITHR_MODE: lease
            SMITHR_URL: http://10.21.0.1:7070
            SMITHR_RESOURCE_TYPE: phone
            SMITHR_PLATFORM: android
            SMITHR_LESSEE: ${SMITHR_LESSEE:-anonymous}
            SMITHR_PORTS: "5555"
            SMITHR_TTL: "3600"
          networks: [smithr-network]
      networks:
        smithr-network:
          external: true
      EOF

  # ... test steps ...

  - name: Cleanup
    if: always()
    run: docker compose -p android-phone down
```

## iOS Build + Phone (cascading lease)

For iOS workflows that need both a build VM and a phone:

```yaml
steps:
  # 1. Acquire macOS build VM
  - name: Start macOS build proxy
    run: |
      curl -s http://localhost:7070/api/compose/macos-build | \
        SMITHR_LESSEE="ci-${{ github.run_id }}" \
        SMITHR_WORKSPACE="artha-ios" \
        docker compose -f - -p macos-build up -d

  # 2. Build the app (SSH to build VM via proxy on port 22)
  - name: Build iOS app
    run: ssh -p 22 builduser@localhost "cd ~/artha && xcodebuild ..."

  # 3. Acquire iOS phone for E2E tests
  - name: Start iOS phone proxy
    run: |
      curl -s http://localhost:7070/api/compose/ios-phone | \
        SMITHR_LESSEE="ci-${{ github.run_id }}" \
        docker compose -f - -p ios-phone up -d

  # 4. Run E2E tests
  - name: Run Maestro tests
    run: maestro test --host localhost --port 7001 flows/

  # 5. Cleanup (reverse order)
  - name: Cleanup
    if: always()
    run: |
      curl -s http://localhost:7070/api/compose/ios-phone | \
        docker compose -f - -p ios-phone down || true
      curl -s http://localhost:7070/api/compose/macos-build | \
        docker compose -f - -p macos-build down || true
```

## Migration Checklist

- [ ] Verify `smithr-proxy:latest` image exists on all runner hosts
  - Either via registry: `docker pull localhost:5000/smithr/proxy:latest`
  - Or via shared image: `docker load -i /srv/shared/images/smithr-proxy.tar`
- [ ] For OCI artifacts (Option B): add `localhost:5000` to `insecure-registries`
  in `/etc/docker/daemon.json` on each runner host
- [ ] Update each workflow file:
  - Replace lease acquisition curl + jq + socat with `docker compose up -d`
  - Replace cleanup curl with `docker compose down`
  - Remove socat background process management
  - Remove LEASE_ID / TUNNEL_PORT env var plumbing
- [ ] Test on a single workflow first before bulk migration
- [ ] Keep old lease-direct pattern commented out for one release cycle

## What Changes for Test Code

**Nothing.** The proxy forwards the same canonical ports:
- Android phone: `localhost:5555` (ADB)
- iOS phone: `localhost:7001` (Maestro)
- macOS build: `localhost:22` (SSH)

Test code, Maestro flows, and build scripts are unchanged.

## Troubleshooting

```bash
# Check proxy logs
docker compose -p android-phone logs

# Check if lease was acquired
curl -s http://localhost:7070/api/leases | jq '.[] | select(.lessee | startswith("ci-"))'

# Check proxy health
docker inspect --format='{{.State.Health.Status}}' android-phone-android-phone-1

# Manual cleanup if proxy is stuck
docker compose -p android-phone down --remove-orphans
```
