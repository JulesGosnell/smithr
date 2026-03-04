# Smithr

> Resource-as-a-Service for CI, testing, and development.

Smithr enhances Docker with four capabilities that Docker alone doesn't provide:

1. **Location independence** — Resources live on any host. A client on host A
   leases a phone on host B without knowing or caring where it runs. Smithr
   routes SSH tunnels automatically across the cluster.

2. **Leasing** — Acquire exclusive or shared access to a resource for a
   bounded duration. When the lease expires (or the client disconnects),
   Smithr guarantees cleanup and returns the resource to the pool.

3. **Warm resources** — Containers are pre-started and health-checked. Leasing
   is instant — no boot wait, no provisioning delay.

4. **Stickiness** — Named workspaces persist across leases. Lease a sandbox
   called `karl-1`, do work, unlease, re-lease later — your files are still
   there.

Any Docker container can be a Smithr resource. The project includes templates
for common resource types:

- **Emulated phones** — Android emulators (ADB access)
- **Physical phones** — USB-attached Android and iOS devices
- **Simulated phones** — iOS Simulators inside macOS VMs
- **macOS + Xcode VMs** — QEMU-hosted macOS for iOS/macOS builds
- **Physical macOS hardware** — Bare-metal Macs adopted as build resources
- **Build containers** — Fedora + Android SDK for CI builds
- **Dev sandboxes** — Fedora + Claude Code for AI-assisted development
- **Adopted servers** — Any container you own, tunneled transparently

Develop on commodity Linux hardware. Share expensive, specialised resources
— Macs, iPhones, Android phones — transparently.

## Quick Start

Clients get access through a **proxy sidecar** that handles leasing, port
forwarding, and cleanup automatically.

### Lease a phone (Android)

```bash
curl -s http://localhost:7070/api/compose/android-phone | \
  SMITHR_LESSEE="ci-123" docker compose -f - -p my-phone up -d

# ADB is now available at localhost:5555
adb connect localhost:5555
maestro test flows/

# Done — tears down lease automatically
curl -s http://localhost:7070/api/compose/android-phone | \
  docker compose -f - -p my-phone down
```

### Lease a build workspace (macOS)

```bash
curl -s http://localhost:7070/api/compose/macos-build | \
  SMITHR_LESSEE="ci-123" SMITHR_WORKSPACE="my-build" \
  docker compose -f - -p my-build up -d

# Run commands on the remote VM — no SSH keys or ports needed
docker exec my-build-macos-build-1 workspace-ssh "xcodebuild -workspace ..."

# Cleanup
curl -s http://localhost:7070/api/compose/macos-build | \
  docker compose -f - -p my-build down
```

### Lease a dev sandbox

```bash
curl -s http://localhost:7070/api/compose/sandbox | \
  SMITHR_LESSEE="ci-123" SMITHR_WORKSPACE="karl-1" \
  docker compose -f - -p my-sandbox up -d

# SSH into the sandbox — Claude Code, gh CLI, Android SDK all pre-installed
docker exec my-sandbox-sandbox-1 workspace-ssh "claude --version"

# Cleanup
curl -s http://localhost:7070/api/compose/sandbox | \
  docker compose -f - -p my-sandbox down
```

## Available Templates

| Template | Resource | Forwarded Port | Use Case |
|----------|----------|----------------|----------|
| `android-phone` | Android emulator | `localhost:5555` (ADB) | E2E tests |
| `ios-phone` | iOS Simulator | `localhost:7001` (Maestro) | E2E tests |
| `macos-build` | macOS + Xcode VM | `localhost:22` (SSH) | iOS/macOS builds |
| `android-build` | Fedora + Android SDK | `localhost:22` (SSH) | Android builds, E2E |
| `sandbox` | Fedora + Claude Code | `localhost:22` (SSH) | Dev sandboxes |
| `phone` | Android or iOS phone | `localhost:22` (SSH) | Unified phone proxy |
| `server` | Adopted server | `localhost:3000` | E2E tests against an API |
| `adopt-proxy` | External container | configurable | Adopt + tunnel any container |

## Architecture

```
┌──────────────────────────────────────────────────────────┐
│  CI Runner / Client                                      │
│                                                          │
│  curl template │ docker compose up │ run tests │ down    │
└───────┬────────────────┬───────────────────────────┬─────┘
        │                │                           │
   ┌────▼────┐    ┌──────▼──────┐              ┌─────▼─────┐
   │ Smithr  │    │ Proxy       │              │ Proxy     │
   │ API     │◄───│ Container   │──socat──────►│ Container │
   │ :7070   │    │ (lease+fwd) │              │ (cleanup) │
   └────┬────┘    └─────────────┘              └───────────┘
        │
   ┌────▼───────────────────────────────────────────┐
   │  Smithr Control Plane (Clojure)                │
   │  - Docker event subscription (push-based)      │
   │  - Lease state (Clojure atom)                  │
   │  - SSH tunnel management                       │
   │  - GC loop (reaps expired leases every 30s)    │
   │  - Dashboard (Reagent SPA on :7070)            │
   └────┬───────────────────────────────────────────┘
        │
   ┌────▼──────────┐  ┌────────────────┐
   │  Host A        │  │  Host B        │
   │  Docker host   │  │  Docker host   │
   └───────────────┘  └────────────────┘
```

Smithr discovers resources via Docker labels (`smithr.managed=true`) using
push-based event subscription. Resources are served warm across hosts with
automatic SSH tunnel routing.

## Key Concepts

- **Resources** — Any Docker container with `smithr.managed=true` labels.
- **Leases** — Exclusive (phones) or shared (builds/sandboxes) access. SSH tunnels created on lease, destroyed on unlease.
- **Workspaces** — Named persistent environments (e.g., `karl-1`). State survives across leases.
- **Proxy sidecar** — Lightweight Alpine container handling lease lifecycle and port forwarding.
- **Adopt** — Register any external container as a leasable Smithr resource.

## Environment Variables

| Variable | Default | Description |
|----------|---------|-------------|
| `SMITHR_LESSEE` | `anonymous` | Who owns this lease (for tracking) |
| `SMITHR_TTL` | `3600` | Lease duration in seconds |
| `SMITHR_WORKSPACE` | — | Named persistent workspace (build leases) |
| `SMITHR_PREFER_HOST` | — | Prefer a specific host for the lease |

## Running Smithr

```bash
# From project root
clojure -M:run

# Or via Docker Compose
docker compose -f layers/network.yml -f layers/server.yml up -d
```

Dashboard at `http://localhost:7070/`.

## Requirements

- Linux (Fedora 43 recommended)
- Docker with Compose v2
- Clojure 1.12+ (control plane)
- KVM (`/dev/kvm`) for Android emulators and macOS VMs
- 16+ GB RAM (64 GB recommended for phone pools)

## License

See [LICENSE](LICENSE).
