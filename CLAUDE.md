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

## For CI Clients (the simple version)

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

### Available templates

| Template | Resource | Forwarded Port | Use Case |
|----------|----------|----------------|----------|
| `android-phone` | Android emulator | `localhost:5555` (ADB) | E2E tests |
| `ios-phone` | iOS Simulator | `localhost:7001` (Maestro) | E2E tests |
| `macos-build` | macOS + Xcode VM | `localhost:22` (SSH) | iOS/macOS builds |
| `android-build` | Fedora + Android SDK | `localhost:22` (SSH) | Android builds, E2E |
| `sandbox` | Fedora + Claude Code | `localhost:22` (SSH) | Dev sandboxes (Karls) |
| `phone` | Android or iOS phone | `localhost:22` (SSH) | Unified near-side phone proxy |
| `server` | Adopted server | `localhost:3000` | E2E tests against an API server |
| `adopt-proxy` | External container | configurable | Adopt + tunnel any container |
| `android-app` | Android app sidecar | — | App install/config lifecycle |
| `ios-app` | iOS app sidecar | — | App install/config lifecycle |
| `maestro` | Maestro test runner | — | Android E2E test orchestration |
| `ios-maestro` | iOS Maestro sidecar | — | iOS E2E test orchestration |
| `unified-app` | Near-side app sidecar | — | Platform-aware app lifecycle |
| `unified-maestro` | Near-side Maestro | — | Platform-aware test orchestration |

### Environment variables

| Variable | Default | Description |
|----------|---------|-------------|
| `SMITHR_LESSEE` | `anonymous` | Who owns this lease (for tracking) |
| `SMITHR_TTL` | `3600` | Lease duration in seconds |
| `SMITHR_WORKSPACE` | — | Named persistent workspace (build leases) |
| `SMITHR_PREFER_HOST` | — | Prefer a specific host for the lease |

## Architecture Overview

```
┌──────────────────────────────────────────────────────────┐
│  CI Runner / Client                                      │
│                                                          │
│  curl template │ docker compose up │ run tests │ down    │
│       │                │                           │     │
└───────┼────────────────┼───────────────────────────┼─────┘
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
   │  megalodon     │  │  prognathodon  │
   │  Docker host   │  │  Docker host   │
   │  - 4 resources │  │  - 3 resources │
   └───────────────┘  └────────────────┘
```

Smithr runs on **both hosts** simultaneously. Each instance manages its own
host's resources and can see the other's via TLS Docker connections.

## Project Structure

```
smithr/
├── CLAUDE.md                  ← You are here
├── deps.edn                   ← Clojure dependencies
├── shadow-cljs.edn            ← ClojureScript build config
├── src/smithr/                ← Clojure control plane
│   ├── core.clj               ← Entry point (-main)
│   ├── state.clj              ← Single atom for all state
│   ├── docker.clj             ← Docker event subscription
│   ├── lease.clj              ← Lease lifecycle + SSH tunnels + GC
│   ├── api.clj                ← Reitit routes
│   ├── handlers.clj           ← Request handlers
│   ├── macos.clj              ← macOS VM user management
│   └── linux.clj              ← Linux container user management
├── src/smithr/ui/             ← Dashboard (ClojureScript + Reagent)
├── resources/
│   ├── smithr.edn             ← Config (symlink → config/<hostname>.edn)
│   ├── openapi.yaml           ← OpenAPI 3.1 spec
│   ├── compose-templates/     ← YAML served by /api/compose/:template
│   └── public/                ← Dashboard static assets
├── layers/                    ← Docker Compose layers
│   ├── server.yml             ← Smithr service (port 7070)
│   ├── registry.yml           ← OCI registry (port 5000)
│   ├── android.yml            ← Android emulator containers
│   ├── android-build.yml      ← Android build containers
│   ├── xcode.yml              ← macOS + Xcode VMs
│   ├── ios.yml                ← iOS Simulator sidecars
│   └── images/smithr-proxy/   ← Proxy sidecar Dockerfile
├── bin/
│   ├── smithr                 ← Bash CLI
│   └── smithr-registry        ← Registry management
├── mocks/                     ← Mock email (Resend) + SMS (Twilio) for E2E
├── docs/                      ← Extended documentation
└── tls/                       ← Inter-host Docker TLS certificates
```

## Running Smithr

```bash
# From project root
clojure -M:run

# Or via Docker Compose
docker compose -f layers/network.yml -f layers/server.yml up -d
```

Listens on port **7070**. Dashboard at `http://localhost:7070/`.

## REST API

| Method | Path | Description |
|--------|------|-------------|
| GET | `/api/resources` | List resources (filter: type, platform, status, host) |
| GET | `/api/resources/{id}` | Get a single resource |
| GET | `/api/leases` | List active leases |
| POST | `/api/leases` | Acquire a lease |
| GET | `/api/leases/{id}` | Get a single lease |
| DELETE | `/api/leases/{id}` | Unlease |
| GET | `/api/hosts` | List connected Docker hosts |
| GET | `/api/workspaces` | List persistent workspaces |
| GET | `/api/workspaces/{name}` | Get a workspace by name |
| DELETE | `/api/workspaces/{name}` | Purge a workspace |
| POST | `/api/adopt` | Register an external container for tunneling |
| GET | `/api/adopts` | List active adopted containers |
| DELETE | `/api/adopts/{id}` | Unadopt a container |
| GET | `/api/health` | Health check |
| GET | `/api/metrics` | Resource and lease metrics |
| GET | `/api/events` | Recent Docker and lease events |
| GET | `/api/catalogue` | Provisionable resource types and active resources |
| POST | `/api/provision` | Auto-provision a resource from a template |
| GET | `/api/scan/devices` | Scan for connected USB devices |
| GET | `/api/compose/{template}` | Compose YAML for proxy sidecar |
| GET | `/api/templates` | List published compose templates |
| POST | `/api/templates` | Publish a compose template |
| DELETE | `/api/templates/{name}` | Delete a published template |

Full spec: [resources/openapi.yaml](resources/openapi.yaml)

## Key Concepts

**Resources** — Docker containers with `smithr.managed=true` labels. Smithr
discovers them via Docker event subscription (push, not polling).

**Leases** — Acquire exclusive (phone) or shared (build) access to a resource.
Smithr creates SSH tunnels on lease and destroys them on unlease. GC reaps
expired leases every 30 seconds.

**Workspaces** — Named persistent build environments (e.g., `artha-apk`). The
workspace name = the SSH username on the VM. State survives across leases.

**Proxy sidecar** — A lightweight Alpine container that acquires a lease, sets
up socat port forwarding, and handles cleanup. Clients interact with the proxy
instead of the Smithr API directly.

**Adopt** — Register an external container (one you own) with Smithr. The
adopted container becomes a leasable resource — clients lease it identically
to native Smithr containers. Cross-host tunneling works transparently.
See [E2E Test Architecture](docs/ARCHITECTURE.md#ci-mode--e2e-test-architecture).

## Container Naming

All containers use Younger Futhark rune suffixes: `smithr-<type>-<rune>`

Examples: `smithr-android-fe`, `smithr-xcode-fe`, `smithr-android-build-fe`

Runes: fe, ur, thurs, oss, reid, kaun, hagall, naud, iss, ar, sol, tyr,
bjarkan, madhr, logr, yr

## Network

- `smithr-network` at `10.21.0.0/16`
- Smithr API: port 7070
- OCI registry: port 5000
- Gateway: `10.21.0.1` (host accessible from containers)

### IP Allocation

| IP | Service | Layer |
|----|---------|-------|
| `10.21.0.1` | Gateway (host) | network.yml |
| `10.21.0.5` | OCI Registry | registry.yml |
| `10.21.0.10` | PostgreSQL | database.yml |
| `10.21.0.11` | Redis | database.yml |
| `10.21.0.12` | TLS Proxy (Caddy) | tls-proxy.yml |
| `10.21.0.20` | Smithr API | server.yml |
| `10.21.0.30` | Android Emulator | android.yml |
| `10.21.0.40` | macOS / Xcode VM | xcode.yml |
| `10.21.0.50` | Android Build* | android-build.yml |
| `10.21.0.50` | Mock Email* | email.yml |
| `10.21.0.60` | Sandbox | sandbox.yml |
| `10.21.0.50` | Physical Phone* | physical-phone.yml |
| `10.21.0.51` | Mock SMS | sms.yml |
| `10.21.0.52` | Physical iPhone | physical-iphone.yml |
| `10.21.0.100` | DNS (dnsmasq) | dns.yml |

*These layers share `10.21.0.50` and are mutually exclusive (never composed together).

## Config

Each host has its own config at `resources/config/<hostname>.edn`. The file
`resources/smithr.edn` is a symlink to the current host's config. Local host
is listed first (no `:host-address`), remote hosts after (with `:host-address`
and `:tls` settings).

## Development

```bash
# REPL
clojure -M:dev

# Rebuild ClojureScript dashboard
npx shadow-cljs release app

# Rebuild proxy image
docker build -t smithr-proxy:latest layers/images/smithr-proxy/

# Push proxy to local registry
bin/smithr-registry publish
```

GitHub: [JulesGosnell/smithr](https://github.com/JulesGosnell/smithr)
