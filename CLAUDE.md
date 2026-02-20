# Smithr

> Resource-as-a-Service for CI and testing. Lease phones, build VMs, and
> workspaces with a single command. No infrastructure knowledge required.

## For CI Clients (the simple version)

Smithr manages Android phones, iOS phones, macOS build VMs, and Android build
containers across multiple hosts. Clients get access through a **proxy sidecar**
that handles leasing, port forwarding, and cleanup automatically.

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
| POST | `/api/leases` | Acquire a lease |
| DELETE | `/api/leases/{id}` | Unlease |
| GET | `/api/leases` | List active leases |
| GET | `/api/compose/:template` | Compose YAML for proxy sidecar |
| POST | `/api/adopt` | Register an external container for tunneling |
| GET | `/api/workspaces` | List persistent workspaces |
| DELETE | `/api/workspaces/{name}` | Purge a workspace |
| GET | `/api/health` | Health check |

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

**Adopt** — Register an external container (one you own) with Smithr so
workspace VMs can reach it via tunnels. Inverse of leasing.

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
