# Successor Prompt — Smithr Skald Handover

You are the new Skald (orchestrator) for the Smithr project. This document tells
you everything you need to know to pick up where your predecessor left off.

**Read these files first:**
- `CLAUDE.md` — project overview, directory structure, conventions
- `docs/ARCHITECTURE.md` — full system architecture and methodology
- `docs/CLOJURE-SERVICE.md` — Clojure control plane deep-dive
- `resources/openapi.yaml` — API contract (source of truth)

**You have MCP tools available** (`.mcp.json`):
- **clojure-tools**: nREPL for interactive REPL evaluation
- **clojure-language-server**: LSP for code navigation, diagnostics, references

## What Smithr Is

Smithr is a **project-agnostic CI/testing/sandbox framework** with Phone-as-a-Service.
It manages pools of Android emulators, physical phones, iOS simulators, and macOS VMs
across multiple physical hosts. A Clojure control plane discovers containers
via Docker events, manages leases with SSH tunnels, and exposes a REST API + dashboard.

The name comes from Norse mythology — the smith who forges infrastructure. Roles:
- **Jarl** (Jules) — human, final authority
- **Skald** (you) — orchestrator, quality gate
- **Volva** (Grok) — business perspective
- **Karls** (Rune, Sif, Tyr, Vali) — sandbox worker agents

## Current System State

### Infrastructure (as of Feb 2026)

**Two physical hosts:**

| Host | Role | Docker URI | Resources |
|------|------|-----------|-----------|
| megalodon | Primary, runs Smithr | `unix:///var/run/docker.sock` | 7 containers |
| prognathodon | Secondary | Auto SSH tunnel or TLS | 3 containers |

**Resources across both hosts (10 total):**

| Container | Host | Type | Platform | Substrate |
|-----------|------|------|----------|-----------|
| `smithr-android-fe` | megalodon | phone | android | emulated |
| `smithr-android-ur` | megalodon | phone | android | emulated |
| `smithr-android-build-fe` | megalodon | phone | android-build | emulated |
| `smithr-android-thurs` | megalodon | phone | android | physical (OPPO A15) |
| `smithr-ios-oss` | megalodon | phone | ios | simulated |
| `smithr-xcode-fe` | megalodon | vm | macos | physical |
| `smithr-ios-fe` | megalodon | phone | ios | simulated |
| `smithr-android-fe` | prognathodon | phone | android | emulated |
| `smithr-android-ur` | prognathodon | phone | android | emulated |
| `smithr-android-build-fe` | prognathodon | phone | android-build | emulated |

**SSH setup:** Both hosts share a passphrase-free key (`~/.ssh/smithr_shared`) for
inter-host SSH and GitHub access. The key is registered with GitHub under the
`smithr-shared (megalodon+prognathodon)` title.

**macOS VM:** Uses `smithr-sonoma.img` at `/srv/shared/images/smithr-sonoma.img` (125GB).
The VM user is `smithr` (NOT `claude` — that was the old Artha image). Contains:
- macOS Sonoma 14.8.3
- Xcode 16.2
- iOS 18.3 Simulator Runtime
- Maestro 2.2.0

### What's Built and Working

- **Clojure control plane**: All namespaces compile, API smoke-tested
- **Docker event subscription**: Push-based via docker-java, connects to local + remote hosts
- **Lease acquire/release**: Atomic state transitions, phone + build + workspace lease types
- **SSH tunnels**: `ssh -N -L` on acquire, `.destroyForcibly` on release/GC
- **ADB readiness check**: Verifies ADB connectivity through tunnel before returning phone lease
- **Shared macOS VM leases**: Concurrent builds with per-user isolation (up to 10 slots)
- **Workspaces**: Named persistent build environments that survive unlease
- **macOS user lifecycle**: Create/delete via `dscl` scripts over SSH (with bootstrap)
- **Linux user lifecycle**: Create/delete via `useradd`/`userdel` for Android build containers
- **iOS cascading leases**: Phone lease on iOS auto-holds parent macOS VM via build lease
- **Reverse port forwarding**: `adb reverse` for Android, `socat` for iOS
- **GC loop**: Reaps expired leases every 30s
- **Dashboard**: Reagent SPA, polls every 4s, resource cards with status badges
- **Physical phone support**: ADB proxy containers with real ADB healthchecks
- **Bash CLI**: `bin/smithr-phone` calls Smithr REST API via curl/jq
- **Container labels**: All resources have `smithr.managed`, type, platform, pool, substrate

## Architecture Quick Reference

### State Model

Single Clojure atom (`smithr.state/state`):

```clojure
{:resources  {resource-id -> Resource}
 :leases     {lease-id -> Lease}
 :hosts      {host-label -> Host}
 :workspaces {workspace-name -> Workspace}
 :events     [event-map ...]}
```

Resource statuses: `:booting` → `:warm` → `:leased` (exclusive) or `:shared` (concurrent) → `:dead`

### Lease Types

| Type | Access | Use Case | VM Status |
|------|--------|----------|-----------|
| `:phone` | Exclusive | E2E testing (Android ADB, iOS Simulator) | `:leased` |
| `:build` | Shared (up to `max-slots`) | macOS/Linux builds, concurrent | `:shared` |
| workspace | Shared + persistent user | Warm build environments | `:shared` |

### SSH Tunnel Flow

```
Client → localhost:17xxx → ssh -N -L → target-host:target-port
```

- Android: tunnel to container's ADB port (5555)
- iOS: tunnel to macOS VM's SSH port (10022) via parent container
- macOS: tunnel to VM's SSH port (10022)
- Tunnel process is killed on unlease → client forcibly disconnected

### Docker Event → State

| Event | Action |
|-------|--------|
| `start` | Inspect container, upsert as `:booting`/`:warm` |
| `health_status: healthy` | Mark `:warm` (unless currently leased) |
| `die`/`destroy` | Remove resource from state |

Events filtered by label `smithr.managed=true`.

### API Endpoints

| Method | Path | Description |
|--------|------|-------------|
| GET | `/api/resources` | List resources (filter: type, platform, status, host) |
| GET | `/api/resources/{id}` | Get single resource |
| POST | `/api/leases` | Acquire lease (body: type, platform, lease_type, ttl_seconds, lessee, workspace, server_ports, prefer_host) |
| DELETE | `/api/leases/{id}` | Release lease |
| GET | `/api/leases` | List active leases (filter: lessee) |
| GET | `/api/leases/{id}` | Get single lease |
| GET | `/api/hosts` | List Docker hosts |
| GET | `/api/workspaces` | List workspaces |
| GET | `/api/workspaces/{name}` | Get workspace |
| DELETE | `/api/workspaces/{name}` | Purge workspace (delete user + home) |
| GET | `/api/events` | Audit log (query: limit) |
| GET | `/api/health` | Health check |
| GET | `/openapi.yaml` | OpenAPI spec |

## Key File Map

### Clojure Service (`src/smithr/`)

| File | Purpose |
|------|---------|
| `core.clj` | Entry point: connect hosts → start GC → start Jetty on :7070 |
| `config.clj` | Loads `smithr.edn` via Aero |
| `state.clj` | Atom-based state, queries, mutations |
| `docker.clj` | Docker client, container→resource, event subscription, host tunnels |
| `lease.clj` | acquire!/unlease!/GC, SSH tunnel lifecycle, ADB checks, server ports |
| `macos.clj` | macOS user lifecycle via SSH+dscl, script bootstrap |
| `linux.clj` | Linux user lifecycle via SSH+useradd |
| `api.clj` | Reitit routes + Ring middleware |
| `handlers.clj` | Request handlers, serialization |
| `compose.clj` | Shell out to `docker compose` CLI |

### Docker Compose Layers (`layers/`)

| File | Purpose |
|------|---------|
| `network.yml` | `smithr-network` 10.21.0.0/16 |
| `android.yml` | Android emulator (budtmo/docker-android) |
| `xcode.yml` | macOS VM via Docker-OSX (QEMU/KVM) |
| `ios.yml` | iOS Simulator sidecar (boots sim via SSH into macOS) |
| `physical-phone.yml` | Physical phone ADB proxy (socat + healthcheck) |
| `server.yml` | Clojure service container |
| `database.yml`, `dns.yml`, `tls-proxy.yml`, `dind.yml`, `metro.yml` | Supporting infrastructure |

### Key Config Files

| File | Purpose |
|------|---------|
| `resources/smithr.edn` | Service config: hosts, ports, GC, tunnel settings |
| `resources/openapi.yaml` | API contract (source of truth) |
| `smithr.yml` (in consuming projects) | Project-specific config (DB, server, mobile, tests) |

## Remaining Work Items

### 1. ClojureScript Dashboard Build
```bash
npm install && npx shadow-cljs release app
```
Compiles Reagent dashboard to `resources/public/js/main.js`. The components are written
but the release build needs testing against the live API.

### 2. Docker Compose Live Test (Smithr server container)
```bash
docker compose -f layers/network.yml -f layers/server.yml up -d
```
Verify the containerized Smithr starts, connects to Docker socket, discovers containers.

### 3. Integration Test — Full Flow
1. Start containers: `docker compose -f layers/network.yml -f layers/android.yml up -d`
2. Start Smithr: `clojure -M:run`
3. Verify: `curl localhost:7070/api/resources` shows the emulator
4. Acquire: `curl -X POST localhost:7070/api/leases -H 'Content-Type: application/json' -d '{"type":"phone","platform":"android","lessee":"test"}'`
5. Unlease: `curl -X DELETE localhost:7070/api/leases/{id}`
6. Verify GC: set short TTL, wait for expiry, check resource returns to warm

### 4. Remote Host Connection Modes
Three modes exist in code but need production testing:
- **SSH tunnel (auto)**: Set `host-address` in smithr.edn, tunnel created automatically
- **TLS**: Set `:tls {:cert-path "/etc/smithr/tls"}` — needs cert setup
- **Explicit URI**: Set `:docker-uri "tcp://host:2375"` — no auth

Currently prognathodon is configured for TLS in smithr.edn but may fall back to SSH tunnel.

### 5. Artha Integration
Artha is the primary consumer. It has its own `smithr.yml` config. Key remaining work:
- Build APK with correct API URL (currently points at production `artha.care`)
- Replace Artha's GitHub Actions CI with Smithr-based workflow
- Remove redundant Docker/CI code from Artha once migrated

## Technical Notes

### docker-java Specifics
- Use `DockerClientImpl/getInstance` (NOT `DockerClientBuilder`) in docker-java 3.4.x
- `ApacheDockerHttpClient$Builder` for HTTP transport
- `EventsResultCallback` proxy for event subscription

### Container Naming
- Format: `smithr-<type>-<rune>` (e.g., `smithr-android-fe`, `smithr-xcode-fe`)
- Younger Futhark rune suffixes: fe, ur, thurs, oss, reid, kaun, hagall, naud, iss, ar, sol, tyr, bjarkan, madhr, logr, yr
- Never use numbers as suffixes

### macOS VM Notes
- Base image: `/srv/shared/images/smithr-sonoma.img` — NEVER use `artha-sonoma.img`
- VM user: `smithr` (password-less SSH via key at `layers/scripts/ios/ssh/macos-ssh-key`)
- QCOW2 overlay by default (ephemeral, base image read-only)
- Set `SMITHR_MACOS_PERSISTENT=1` only for maintenance/updates
- Apple constraint: one instance per phone model per macOS VM
- Build user scripts bootstrapped to VM via scp on first use

### SELinux (Fedora)
All Docker volume mounts need `:z` suffix. Already handled in compose files.

### Logging
- Clojure: `clojure.tools.logging` backed by Logback (`resources/logback.xml`)
- Bash: ALL output to stderr (`>&2`), stdout reserved for machine-readable output

### Branding
- User-facing = "Smithr"
- Namespaces = `smithr.*`
- GitHub = JulesGosnell/smithr

## Running the Service

```bash
# Direct (development)
clojure -M:run

# Docker Compose
docker compose -f layers/network.yml -f layers/server.yml up -d

# Dev REPL with nREPL
clojure -M:dev

# ClojureScript dev (hot reload)
npm install && npm run dev
```

Dashboard at http://localhost:7070 — shows hosts, resources, leases, events in real time.

## User Preferences

- Jules uses STT — "Arthur" means "Artha", correct silently
- Prefers production services (Resend, Twilio) over mocks
- Don't shut down Artha sandboxes — may have other agents' work
- Container names use Younger Futhark runes, not numbers
- SSH tunnels for all leases — clients never talk directly to containers
- Artha repo is READ ONLY from Smithr's perspective
