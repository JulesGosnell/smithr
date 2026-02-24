# Smithr Architecture

> **Smithr** — the smith who forges the infrastructure.
> A project-agnostic, containerised CI/testing/sandbox framework with Phone as a Service.

## Table of Contents

1. [Methodology](#methodology)
2. [Vision](#vision)
3. [Design Principles](#design-principles)
4. [The Skald Workflow](#the-skald-workflow)
5. [System Overview](#system-overview)
6. [Layer Architecture](#layer-architecture)
7. [Phone as a Service](#phone-as-a-service)
8. [Shared Server Environment](#shared-server-environment)
9. [Sandbox Mode](#sandbox-mode)
10. [CI Mode](#ci-mode)
11. [Configuration System](#configuration-system)
12. [Directory Structure](#directory-structure)
13. [Naming Conventions](#naming-conventions)
14. [Demo Application](#demo-application)
15. [Migration Path for Artha](#migration-path-for-artha)

---

## Methodology

Smithr is a **methodology** first and infrastructure second. It defines a safe, reproducible way of working with LLM agents on real codebases.

### The Pattern

Smithr lives as a **sibling directory** to your project:

```
~/src/
├── smithr/          ← Infrastructure & methodology
└── myapp/           ← Your project
```

You start Smithr, point it at your project, and the **Skald** (orchestrator) takes over:

1. **Jarl** (human) creates an issue describing work to be done
2. **Skald** reads the issue, provisions a sandbox, assigns it to a **Karl** (worker)
3. **Karl** works in full isolation — writes code, tests locally, creates a PR on a branch
4. **Skald** reviews the PR, waits for CI green, merges to main
5. If CI breaks, **Skald** fixes it before accepting the next PR
6. **Main is always green.** This is a strict invariant.

### Issue In, PR Out

The fundamental contract: a Karl receives a GitHub issue and produces a Pull Request. The Karl cannot push to main — it works on a branch and creates a PR. This provides:

- **Safety** — no agent can break production
- **Auditability** — every change goes through a PR with review
- **Isolation** — each Karl works in its own sandboxed environment
- **Quality** — the Skald ensures main stays green at all times

### Roles

| Role | Actor | Responsibility |
|------|-------|---------------|
| **Jarl** | Human (Jules) | Define issues, make architectural decisions, final authority |
| **Skald** | LLM (Claude) | Orchestrate sandboxes, merge PRs, keep main green, fix builds |
| **Karl** | LLM (Claude) | Work on issues in sandbox, write code, create PRs |
| **Volva** | LLM (Grok) | Business perspective, strategic guidance |

---

## Vision

Smithr provides reusable infrastructure for:

1. **Phone as a Service (PaaS)** — warm pools of Android emulators and iOS simulators that can be acquired, used, and released in seconds. No boot wait. Install app, test, uninstall, return.

2. **Shared warm server environments** — a single server (database, API, TLS proxy) that persists across test runs, eliminating redundant setup for each test in a matrix.

3. **DinD-isolated sandboxes** — complete development environments where LLM agents or human developers work in full isolation with their own Docker daemon, database, and phone.

4. **Composable CI pipelines** — GitHub Actions workflows that leverage phone pools and shared servers to run N tests in parallel across a phone × test matrix.

All of this is **project-agnostic**. Smithr doesn't know or care what app it's testing. Projects provide a configuration file (`smithr.yml`) that tells Smithr where to find the app, how to build it, and what tests to run.

## Design Principles

1. **Parameterise everything** — no hardcoded project names, repo URLs, image paths, or port numbers.
2. **Compose, don't monolith** — infrastructure is built from composable Docker Compose layers that can be mixed and matched.
3. **Warm by default** — phones and servers stay running. Tests interact with warm resources rather than booting from scratch.
4. **Isolate by default** — DinD gives each sandbox/CI-job its own Docker daemon, port space, and network namespace.
5. **Symmetrical lifecycle** — startup and shutdown are mirror images. Containers come up in dependency order gated by health checks; they come down in reverse order, each level cleaning its resources before the next shuts down. No orphaned containers, no dangling networks, no leaked volumes.
6. **Script simplicity** — a thin Bash script layer, not Kubernetes. Designed for 2-10 nodes.
7. **Document everything** — if it's not documented, it doesn't exist.

## The Skald Workflow

The Skald is the quality gate — the single responsible party for the health of the main branch.

### Continuous Loop

```
┌──────────────────────────────────────────────────────────┐
│                     SKALD LOOP                           │
│                                                          │
│  1. Watch GitHub issue queue                             │
│     └─ New issue? → Assess scope and priority            │
│                                                          │
│  2. Provision sandbox                                    │
│     └─ smithr sandbox start --worker rune                │
│     └─ Clone repo, install deps, seed DB                 │
│                                                          │
│  3. Assign issue to Karl                                 │
│     └─ Send issue context + codebase to sandbox          │
│     └─ Karl works autonomously                           │
│                                                          │
│  4. Receive PR from Karl                                 │
│     └─ Karl pushes branch, creates PR                    │
│                                                          │
│  5. Review & merge                                       │
│     └─ Wait for CI green                                 │
│     └─ Review code changes                               │
│     └─ Merge to main                                     │
│                                                          │
│  6. Post-merge validation                                │
│     └─ Verify main is still green                        │
│     └─ If broken: FIX before accepting next PR           │
│     └─ If green: back to step 1                          │
│                                                          │
│  INVARIANT: Main is ALWAYS green.                        │
└──────────────────────────────────────────────────────────┘
```

### Skald Commands

```bash
# Watch the issue queue and process work
smithr skald watch --repo MyOrg/myapp

# Provision a sandbox for a specific issue
smithr skald assign --issue 42 --worker rune

# Review and merge a Karl's PR
smithr skald review --pr 123

# Fix a broken build on main
smithr skald fix-build

# Status of all active work
smithr skald status
```

### Safety Rules

1. **Karls cannot push to main** — git hooks enforce this in sandboxes
2. **Karls cannot force-push** — git hooks prevent this
3. **Skald merges only when CI is green** — automated check
4. **One PR at a time on main** — serialised merges prevent conflicts
5. **Broken build = stop everything** — fix before proceeding

---

## System Overview

```
┌─────────────────────────────────────────────────────────────────────────┐
│                          Physical Hosts                                 │
│  (e.g. prognathodon, megalodon — connected via NFS at /srv/shared)     │
│                                                                         │
│  ┌──────────────────────────────────────────────────────────────────┐   │
│  │                    Smithr Services                                │   │
│  │                                                                   │   │
│  │  ┌─────────────┐  ┌──────────────┐  ┌────────────────────────┐  │   │
│  │  │ Phone Pool   │  │ Server Pool  │  │ Sandbox Manager        │  │   │
│  │  │              │  │              │  │                        │  │   │
│  │  │ Android:     │  │ DB + Redis   │  │ DinD environments     │  │   │
│  │  │  phone-1     │  │ API server   │  │ for dev/agent work    │  │   │
│  │  │  phone-2     │  │ TLS proxy    │  │                        │  │   │
│  │  │  phone-3     │  │ DNS          │  │ Each sandbox gets:    │  │   │
│  │  │              │  │              │  │  - own Docker daemon   │  │   │
│  │  │ iOS:         │  │ (warm,       │  │  - own phone(s)       │  │   │
│  │  │  macOS-VM-1  │  │  shared by   │  │  - own server         │  │   │
│  │  │   └ iPhone16 │  │  all tests)  │  │  - VNC access         │  │   │
│  │  │   └ iPhone15 │  │              │  │                        │  │   │
│  │  │  macOS-VM-2  │  │              │  │                        │  │   │
│  │  │   └ iPhone16 │  │              │  │                        │  │   │
│  │  └─────────────┘  └──────────────┘  └────────────────────────┘  │   │
│  └──────────────────────────────────────────────────────────────────┘   │
│                                                                         │
│  NFS: /srv/shared/smithr/                                               │
│    ├── phone-pool/state.json      (phone allocation state)             │
│    ├── artifacts/<run-id>/        (per-run build artifacts)            │
│    └── docker-cache/              (cached Docker images)               │
└─────────────────────────────────────────────────────────────────────────┘
```

## Layer Architecture

Smithr uses composable Docker Compose "layers" that can be combined:

### Base Layers (project-agnostic, provided by Smithr)

| Layer | File | Provides |
|-------|------|----------|
| DinD | `layers/dind.yml` | Docker-in-Docker daemon with isolation |
| Network | `layers/network.yml` | smithr-network (10.21.0.0/16) |
| DNS | `layers/dns.yml` | dnsmasq for service discovery |
| TLS Proxy | `layers/tls-proxy.yml` | Caddy with pre-generated CA |
| Database | `layers/database.yml` | PostgreSQL + Redis |
| Android Phone | `layers/android.yml` | Parameterised Android emulator |
| Xcode VM | `layers/xcode.yml` | macOS+Xcode VM via Docker-OSX (QEMU/KVM) |
| iOS Simulator | `layers/ios.yml` | iOS Simulator sidecar (boots inside Xcode VM) |
| Physical Phone | `layers/physical-phone.yml` | ADB proxy for USB-connected Android phones |
| Metro | `layers/metro.yml` | React Native Metro bundler |
| Maestro Sidecar | compose template `maestro` | Persistent Maestro test runner (Android) |
| iOS App Sidecar | compose template `ios-app` | App install/uninstall via SSH + simctl |
| iOS Maestro Sidecar | compose template `ios-maestro` | Maestro test runner via SSH into macOS VM |
| Smithr Service | `layers/server.yml` | Clojure control plane (port 7070) |

### Project Layers

Projects don't provide their own compose layers. Instead, they supply a `smithr.yml` configuration file (see [Configuration System](#configuration-system)) that tells Smithr how to set up the database, start the project's API server (via the project's own compose file), find or build the mobile app, and run tests.

### Composition Examples

Layers are composed by the `smithr` CLI commands, not invoked directly. The server command combines the base infrastructure layers:

```bash
# smithr server start internally runs:
docker compose \
  -f layers/network.yml \
  -f layers/database.yml \
  -f layers/dns.yml \
  -f layers/tls-proxy.yml \
  -p "smithr-server" \
  up -d
```

The project's API server is started separately (via `smithr.yml` config), using the project's own compose file and connecting to the smithr-network.

**Sandbox (all layers):**
```bash
docker compose \
  -f layers/dind.yml \
  -f layers/network.yml \
  -f layers/database.yml \
  -f layers/tls-proxy.yml \
  -f layers/dns.yml \
  -f layers/metro.yml \
  -f layers/android.yml \
  -p "smithr-sandbox-rune" \
  up -d
```

## Phone as a Service

### Core Interface

```bash
# Acquire a warm phone from the pool (returns lease UUID on stdout)
smithr phone get --platform android    # → e.g. 3fa85f64-5717-4562-b3fc-2c963f66afa6
smithr phone get --platform ios        # → e.g. 7c9e6679-7425-40de-944b-e07fc1f90ae7

# Unlease it back to the pool
smithr phone unlease <lease-id>

# Show connection details for a lease
smithr phone info <lease-id>

# List all phones across all hosts
smithr phone list

# Check pool status
smithr phone status
```

### Phone Lifecycle

```
  COLD                    WARM                    LEASED                  CLEANING
  (not running)  ──boot──▶ (running,      ──get──▶ (in use by     ──unlease──▶ (uninstalling
                            idle,                   a test)                      app, wiping
                            in pool)                                             data)
                     ▲                                                    │
                     └────────────────────────────────────────────────────┘
                                        (return to pool)
```

### Android Pool

- **Emulated**: `budtmo/docker-android` containers on smithr-network
  - Default device profile: Nexus 5 (works on API 28 and API 34; `pixel_7` does NOT exist as a profile name)
  - ~4 cores + 4GB RAM per emulator → 4 phones per 16-core/64GB host
  - KVM + GPU passthrough for performance
  - Unique IP (10.21.0.30, .31, .32...), ADB port (5555, 5556...), VNC port (5900, 5901...)
  - CA certificate pre-baked — no per-phone cert installation needed
- **Physical**: USB-connected phones via ADB TCP proxy containers
  - Uses `smithr-adb-proxy` image (Alpine + socat + android-tools)
  - ADB healthcheck verifies actual device responsiveness, not just TCP open
  - Phone must have ADB over TCP enabled: `adb -s <serial> tcpip <port>`
  - Compose: `layers/physical-phone.yml`
  - Labels include `smithr.resource.substrate: "physical"` and `smithr.resource.model`

### Android Scaling Strategy

**One emulator per container, multiple containers per host.** This is a deliberate
architectural choice, not a limitation.

The `budtmo/docker-android` image is designed for a single emulator — its startup
code, port forwarder, health check, and supervisord config all assume one instance.
Running multiple emulators in a single container would require forking the image's
Python startup code to manage multiple AVDs, port pairs, and health checks.

More importantly, multi-emulator-per-container provides no meaningful benefit:

| Factor | Multi-container | Multi-emulator-in-one |
|--------|----------------|----------------------|
| **RAM per emulator** | ~2.3 GiB | ~2.3 GiB (same) |
| **CPU per emulator** | ~4 cores | ~4 cores (same) |
| **System image sharing** | Docker layer dedup | Filesystem sharing |
| **Isolation** | Full (independent restart/kill) | None (one crash affects all) |
| **Health checks** | Per-container (native Docker) | Custom multi-target needed |
| **Smithr integration** | 1 container = 1 resource | Complex slot tracking |
| **Image modification** | None (use upstream as-is) | Fork + maintain startup code |

**Resource budget per emulator:**
- RAM: ~2.3 GiB (hw.ramSize=2048 + QEMU overhead)
- CPU: 4 cores (hw.cpu.ncore=4)
- Disk: ~3.2 GiB per AVD + 2.7 GiB shared system images

**Capacity planning (per host):**

| Host | RAM | Cores | Max emulators | Practical limit |
|------|-----|-------|---------------|-----------------|
| megalodon (64 GiB) | 62.7 GiB | 32 | ~8 | 4-6 (with other workloads) |
| prognathodon (64 GiB) | 62.7 GiB | 32 | ~8 | 4-6 (with other workloads) |

To add capacity, launch more containers with different rune suffixes:

```bash
# Second emulator
SMITHR_ANDROID_RUNE=ur SMITHR_ANDROID_ADB_PORT=5556 \
  SMITHR_ANDROID_VNC_PORT=5901 SMITHR_ANDROID_IP=10.21.0.31 \
  docker compose -f layers/network.yml -f layers/android.yml \
  -p smithr-phone-ur up -d

# Third emulator
SMITHR_ANDROID_RUNE=thurs SMITHR_ANDROID_ADB_PORT=5557 \
  SMITHR_ANDROID_VNC_PORT=5902 SMITHR_ANDROID_IP=10.21.0.32 \
  docker compose -f layers/network.yml -f layers/android.yml \
  -p smithr-phone-thurs up -d
```

Each container is independently discoverable by Smithr via `smithr.managed=true`
labels, independently leasable, and independently restartable.

### Substrate Labels

Resources carry a `substrate` label indicating their backing technology:
- `emulated` — Android emulator (QEMU/KVM)
- `simulated` — iOS Simulator (inside macOS VM)
- `physical` — USB-connected real device

### iOS Pool

- Each macOS VM runs via Docker-OSX (QEMU) with QCOW2 overlays
- Apple constraint: one instance per phone model per macOS VM
- Within a VM, simulators are lightweight (~30s boot via `xcrun simctl`)
- ~22GB RAM per macOS VM → 2 VMs per 64GB host
- Multiple phone models per VM (iPhone 16 + iPhone 15 + iPad Pro)
- For duplicate models, schedule across different VMs

### macOS VM Lease Types

macOS VMs support two concurrent access modes:

1. **Phone leases** (exclusive) — One lease locks the entire VM. Used for iOS Simulator
   testing where the Simulator runs per-user. Status: `:leased`.

2. **Build leases** (shared) — Multiple concurrent builds per VM (up to `max_slots`,
   default 10). Each build gets its own macOS user account, SSH tunnel, and home
   directory. Status: `:shared`.

Status transitions:
```
  :warm ──phone──▶ :leased    (exclusive, no other leases allowed)
  :warm ──build──▶ :shared    (concurrent, more builds can join)
  :shared ─build──▶ :shared   (up to max_slots)
  :shared ─last unlease──▶ :warm  (all builds done)
```

Build users are created via `dscl` over SSH. Each gets:
- macOS user account (`build-<8chars>` or workspace name)
- Home directory at `/Users/<username>`
- SSH key auth (copied from smithr admin)
- `com.apple.access_ssh` group membership
- Shell profile with PATH and locale

### Workspaces (Warm Builds)

Workspaces are named persistent build environments that survive unlease:

```bash
# Acquire a workspace lease — user account persists after unlease
POST /api/leases {"type":"vm","platform":"macos","workspace":"artha-build-1"}

# Unlease — workspace marked idle, user/home dir preserved
DELETE /api/leases/{id}

# Next acquire reuses the same user, avoiding git clone + npm install
POST /api/leases {"type":"vm","platform":"macos","workspace":"artha-build-1"}

# Purge when no longer needed — deletes user and home dir
DELETE /api/workspaces/artha-build-1
```

One lease per workspace at a time. Workspace names must match `^[a-zA-Z][a-zA-Z0-9-]{2,30}$`.

### State Management

**Clojure control plane** (see [CLOJURE-SERVICE.md](CLOJURE-SERVICE.md)):

- Push-based state via Docker event subscription (docker-java)
- Atom-based concurrency (`swap!`) — no filesystem locking
- SSH tunnels created on lease acquire, destroyed on unlease/GC
- REST API on port 7070 with real-time Reagent dashboard
- OpenAPI 3.1 spec at `resources/openapi.yaml`

The Clojure service has replaced the legacy NFS JSON + flock phone pool.

### Sandbox Phone Visibility

- In **sandbox mode**: one phone gets a VNC view forwarded to the host desktop
- In **CI mode**: phones run headless — no display needed

### Maestro Sidecar (Android)

The `maestro` compose template provides a persistent Maestro test runner for
Android targets (emulator and physical). It composes with the `android-phone`
proxy template:

```bash
# Download both templates
curl -s http://localhost:7070/api/compose/android-phone > phone.yml
curl -s http://localhost:7070/api/compose/maestro > maestro.yml

# Start together — Maestro waits for phone proxy to be healthy
SMITHR_LESSEE="ci-123" FLOWS_DIR=/path/to/flows \
  docker compose -f phone.yml -f maestro.yml -p test up -d

# Run tests via docker exec
docker exec test-maestro-1 maestro --device android-phone:5555 test /flows/login.yaml

# Tear down
docker compose -p test down
```

Key design decisions:

- **Sidecar mode**: Connects ADB on startup, stays alive via `sleep infinity`.
  Tests are invoked via `docker exec`, not by restarting the container.
- **ADB key sharing**: Mounts host's `~/.android/adbkey` so physical phones
  don't prompt for USB debugging authorization.
- **Same image for emulator and physical**: The proxy abstracts the phone
  substrate. Maestro sees ADB at `android-phone:5555` regardless of whether
  the backend is an emulator or a USB-connected phone.
- **Substrate filtering**: Pass `SMITHR_SUBSTRATE=emulated` or `physical` to
  target a specific phone type.

### Maestro Sidecar (iOS)

iOS Maestro is fundamentally different from Android. Maestro requires the
XCTest framework, which only runs on macOS — the same host as the Simulator.
The sidecar SSHes into the macOS VM to execute Maestro remotely.

```bash
# Download templates
curl -s http://localhost:7070/api/compose/ios-phone > ios.yml
curl -s http://localhost:7070/api/compose/ios-maestro > ios-maestro.yml

# Start — Maestro sidecar waits for phone proxy (SSH tunnel)
SMITHR_LESSEE="ci-123" SSH_KEY_PATH=~/.ssh/id_macos FLOWS_DIR=/path/to/flows \
  docker compose -f ios.yml -f ios-maestro.yml -p test up -d

# Run tests via docker exec (Maestro runs ON the macOS VM via SSH)
docker exec test-maestro-1 /run-test.sh /flows/login.yaml

# Tear down
docker compose -p test down
```

Key design decisions:

- **SSH-through-proxy**: The iOS phone proxy tunnels port 22 (SSH) from the
  macOS VM. The sidecar SSHes through `ios-phone:22` to run Maestro remotely.
- **Sidecar mode**: Like Android, the container stays alive and tests are
  invoked via `docker exec /run-test.sh <flow>`. Flow files are SCP'd to the
  VM before each test and cleaned up afterwards.
- **`path_helper` for SSH sessions**: macOS SSH sessions lack the full PATH.
  The run script sources `/usr/libexec/path_helper` to find Maestro.

#### Custom Maestro Build (smithr)

Maestro 2.1.0 ships pre-built XCTest runners for arm64 only. Our macOS VMs
run under QEMU on x86_64 hosts, so the Simulator needs x86_64 binaries. We
also need the driver-port patch for concurrent Maestro instances.

**Two patched jars** (both at `/srv/shared/images/`):

| Jar | Size | What's changed |
|-----|------|----------------|
| `maestro-cli-2.1.0-smithr.jar` | 1.1 MB | `selectPort()` reads `-Dmaestro.driver.port` system property for concurrent instances |
| `maestro-ios-driver-smithr.jar` | 17 MB | XCTest runner rebuilt as **universal binary (x86_64 + arm64)** for Simulator; arm64 for physical devices |

**How to rebuild** (on the macOS VM):

```bash
# Clone Maestro source
cd ~/src && git clone --depth 1 --branch v2.1.0 https://github.com/mobile-dev-inc/maestro.git

# Build universal Simulator runner
cd maestro/maestro-ios-xctest-runner
xcodebuild build-for-testing \
  -project maestro-driver-ios.xcodeproj \
  -scheme maestro-driver-ios \
  -sdk iphonesimulator \
  -destination "generic/platform=iOS Simulator" \
  ARCHS="x86_64 arm64" ONLY_ACTIVE_ARCH=NO \
  -derivedDataPath /tmp/maestro-build-sim

# Build device runner
xcodebuild build-for-testing \
  -project maestro-driver-ios.xcodeproj \
  -scheme maestro-driver-ios \
  -sdk iphoneos \
  -destination "generic/platform=iOS" \
  CODE_SIGN_IDENTITY=- CODE_SIGNING_ALLOWED=NO \
  ARCHS="arm64" \
  -derivedDataPath /tmp/maestro-build-device

# Repack: extract original jar, replace zipped .app bundles, rejar
```

**Install on VM**: Copy both jars to `~/.maestro/lib/`, replacing
`maestro-cli-2.1.0.jar` and `maestro-ios-driver.jar`.

### iOS App Sidecar

The `ios-app` compose template installs/uninstalls an iOS app on the Simulator
via SSH + `xcrun simctl`. It composes with the `ios-phone` proxy:

```bash
curl -s http://localhost:7070/api/compose/ios-phone > ios.yml
curl -s http://localhost:7070/api/compose/ios-app > ios-app.yml

APP_PATH=/path/to/MyApp.app SSH_KEY_PATH=~/.ssh/id_macos \
  docker compose -f ios.yml -f ios-app.yml -p test up -d

# On shutdown: uninstalls the app automatically
docker compose -p test down
```

Supports optional `API_URL` env var to inject `api-config.json` into the app
bundle after install (overrides the baked-in production URL for E2E testing).

## Shared Server Environment

Instead of each test job spinning up its own database, API server, and TLS proxy, Smithr provides a **shared warm server**:

```
┌──────────────────────────────────────────────────┐
│              Shared Server Environment            │
│                                                    │
│  PostgreSQL ──── Redis ──── API Server             │
│       │                        │                   │
│       └──── TLS Proxy (Caddy) ─┘                  │
│              │                                     │
│         dnsmasq (DNS)                              │
│              │                                     │
│    ┌─────────┴─────────┐                          │
│    │  smithr-network    │                          │
│    │  10.21.0.0/16     │                          │
│    └─────────┬─────────┘                          │
└──────────────┼────────────────────────────────────┘
               │
    ┌──────────┼──────────┐
    │          │          │
 phone-1   phone-2   phone-3    (all connect to same server)
```

### Two Modes

1. **Per-machine server** (default): Each physical host runs its own shared server. Phones on that host connect to it. Simpler networking, no cross-machine dependencies.

2. **Cross-machine server** (optional): One server shared across all hosts via NFS-mounted artifacts and network bridging. More efficient but requires stable cross-host networking.

### Server Lifecycle

```bash
# Start the shared server environment
# Without --config: starts base infrastructure only (DB, Redis, TLS, DNS)
# With --config: also runs project DB setup + starts project API server
smithr server start --config smithr.yml

# Idempotent start (safe for CI — only starts if not already running)
smithr server ensure --config smithr.yml

# Check server health
smithr server status

# Stop when done (reverse startup order)
smithr server stop --config smithr.yml
```

Startup phases (with `--config`):
1. **Network** — create smithr-network (10.21.0.0/16)
2. **Database + cache** — postgres + redis, wait for healthy
3. **DNS** — dnsmasq (optional)
4. **TLS proxy** — Caddy with pre-generated CA
5. **Project DB setup** — run `server.setup` commands from smithr.yml (e.g. Prisma migrations)
6. **Project API server** — start via project's compose file, wait for health check

The server starts once per CI pipeline run (or stays always-on for sandboxes). All phone-based test jobs connect to it.

## Sandbox Mode

Sandboxes provide isolated development environments for LLM agents or human developers:

- Full DinD isolation (own Docker daemon, port space, network)
- Own database, server, and phone(s)
- VNC access to desktop environment
- Git safety hooks (no push to main, no force-push)
- Pre-configured tooling (MCP servers, test frameworks)
- Memory-capped (configurable, default 8GB)

### Named Instances

Smithr provides 4 named sandbox slots with fixed port allocations:

| Sandbox | VNC Port | Metro Port | Emoji |
|---------|----------|------------|-------|
| Rune    | 5996     | 8082       | ᚱ     |
| Sif     | 5997     | 8083       | ⚔     |
| Tyr     | 5998     | 8084       | ☄     |
| Vali    | 5999     | 8085       | 🏹    |

(All Norse, all monosyllabic, no collisions with Artha's Ulfr/Bjorn/Arn/Orm.)

## CI Mode

### GitHub Actions Integration

```yaml
# .github/workflows/ci.yml (in the consuming project)
jobs:
  build:
    runs-on: [self-hosted, linux]
    steps:
      - uses: actions/checkout@v4
      - name: Build artifacts
        run: smithr build --config smithr.yml

  test:
    needs: build
    runs-on: [self-hosted, linux]
    strategy:
      matrix:
        test: [documents, profile, notifications, schedule]
    steps:
      - name: Start shared server (idempotent — safe for matrix)
        run: smithr server ensure --config smithr.yml

      - name: Acquire phone
        id: phone
        run: echo "lease_id=$(smithr phone get --platform android)" >> $GITHUB_OUTPUT

      - name: Run E2E test
        run: |
          smithr test run ${{ matrix.test }} \
            --config smithr.yml

      - name: Unlease phone
        if: always()
        run: smithr phone unlease ${{ steps.phone.outputs.lease_id }}

  deploy:
    needs: test
    runs-on: [self-hosted, linux]
    if: github.ref == 'refs/heads/main'
    steps:
      - name: Deploy
        run: smithr deploy --config smithr.yml
```

### Pipeline Flow

```
Build Phase (parallel)
├── Build server Docker image
├── Build Android APK
└── Build iOS app
        │
        ▼ (artifacts to NFS)
Server Start (once)
├── Start shared DB + Redis + API + TLS
        │
        ▼
Test Phase (parallel matrix)
├── Test 1: get phone → run test → unlease phone
├── Test 2: get phone → run test → unlease phone
├── Test 3: get phone → run test → unlease phone
└── Test 4: get phone → run test → unlease phone
        │
        ▼ (all green)
Deploy Phase
└── Deploy to production
```

## Configuration System

Projects consume Smithr via a `smithr.yml` configuration file placed in the project root. All paths are relative to the directory containing the config file.

### Parser Implementation

The config parser (`bin/lib/config.sh`) uses Python3 + PyYAML to convert YAML to JSON, then jq to query values. This avoids complex Bash YAML parsing while requiring only standard tools (Python3, jq).

```bash
source bin/lib/config.sh
smithr_config_load ./smithr.yml              # Parse and cache as JSON
smithr_config ".project.name"                # Query a scalar → "artha"
smithr_config ".server.db.user" "smithr"     # Query with default
smithr_config_arr ".server.setup"            # Query array → one line per element
smithr_config_has ".server.api.compose"      # Check if key exists → true/false
smithr_config_export_env ".server.env"       # Export all key-value pairs as env vars
smithr_config_path ".mobile.android.apk"     # Resolve path relative to config dir
```

### Config Schema

```yaml
# smithr.yml — project-specific Smithr configuration
project:
  name: artha                              # Project identifier
  app_id: com.artha.healthcare             # Mobile app package ID

server:
  # Database configuration (overrides Smithr defaults)
  db:
    user: artha
    password: artha_dev_password
    name: artha

  # Environment variables for project setup commands
  env:
    DATABASE_URL: "postgresql://artha:artha_dev_password@localhost:5432/artha"
    REDIS_URL: "redis://localhost:6379"
    AUTH_SECRET: "ci-secret-for-testing"

  # Commands to run after database is healthy (in order)
  # Run from the project root directory
  setup:
    - "pnpm install --frozen-lockfile"
    - "pnpm --filter @artha/db exec prisma generate"
    - "psql $DATABASE_URL -c \"ALTER DATABASE artha SET app.seed_demo = 'true';\""
    - "pnpm --filter @artha/db exec prisma migrate deploy"

  # Project API server (started via Docker Compose)
  api:
    compose: "docker/api.yml"              # Path to project's compose file
    container: "artha-api"                 # Container name for health checks
    health_url: "http://localhost:3000/api/v0/health"  # HTTP health endpoint
    health_timeout: 60                     # Seconds to wait for healthy
    env:                                   # Env vars for the API container
      NODE_ENV: "production"
      DATABASE_URL: "postgresql://artha:artha_dev_password@smithr-postgres:5432/artha"
      REDIS_URL: "redis://smithr-redis:6379"

mobile:
  android:
    apk: "docker/images/bundles/android/app-release.apk"   # Pre-built APK path
    build: "./bin/mobile-build.sh android prod"             # Build command
  ios:
    app: "docker/images/bundles/ios/ArthaHealthcare-ios.tar.gz"

tests:
  dir: "tests/mobile"                      # Directory containing test files
  default:                                 # Tests to run by default
    - "login-only.yaml"
    - "smoke-test.yaml"

network:
  # ADB reverse mappings: route emulator localhost → host port
  # Format: "device_port host_port"
  adb_reverse:
    - "3000 3000"
```

### How Scripts Use the Config

- **`smithr server start --config smithr.yml`**: Reads `server.db.*` for postgres credentials, runs `server.setup` commands, starts the API via `server.api.compose`, waits for `server.api.health_url`.
- **`smithr test run --config smithr.yml`**: Reads `mobile.android.apk` to find the APK, `tests.dir` for test location, `network.adb_reverse` for port forwarding.
- **`smithr build --config smithr.yml`**: Reads `mobile.android.build` / `mobile.ios.build` for build commands.

## Directory Structure

```
smithr/
├── CLAUDE.md                   # Developer guide (start here)
├── smithr.yml.example          # Example configuration for consuming projects
├── README.md
├── deps.edn                    # Clojure deps (tools.deps)
├── shadow-cljs.edn             # ClojureScript build
├── src/smithr/                 # Backend: core, state, docker, lease, macos, linux, api, handlers
├── src/smithr/ui/              # Frontend: Reagent dashboard
├── resources/                  # Config, OpenAPI spec, static assets
├── bin/
│   ├── smithr                  # Main CLI entrypoint (dispatches subcommands)
│   ├── lib/
│   │   ├── common.sh           # Shared utilities (logging, die, wait_for_healthy)
│   │   ├── config.sh           # smithr.yml parser (Python/PyYAML → JSON → jq)
│   │   └── phone-pool.sh      # Phone pool management (acquire/unlease/state)
│   ├── smithr-phone            # Phone subcommand (get/unlease/list/status/warm/clean)
│   ├── smithr-server           # Server subcommand (start/stop/status/ensure)
│   ├── smithr-sandbox          # Sandbox subcommand (start/stop/list)
│   ├── smithr-build            # Build subcommand
│   ├── smithr-test             # Test subcommand (run/list)
│   └── smithr-doctor           # Diagnostics subcommand
├── layers/
│   ├── network.yml             # smithr-network definition (10.21.0.0/16)
│   ├── database.yml            # PostgreSQL + Redis
│   ├── dns.yml                 # dnsmasq for service discovery
│   ├── tls-proxy.yml           # Caddy TLS termination
│   ├── android.yml             # Parameterised Android emulator
│   ├── xcode.yml               # macOS+Xcode VM via Docker-OSX (QEMU/KVM)
│   ├── ios.yml                 # iOS Simulator sidecar (boots inside Xcode VM)
│   ├── physical-phone.yml      # Physical phone ADB proxy (socat + healthcheck)
│   ├── images/adb-proxy/       # Dockerfile for ADB proxy image (Alpine + socat + android-tools)
│   ├── metro.yml               # React Native Metro bundler
│   ├── dind.yml                # Docker-in-Docker daemon (for sandboxes)
│   └── scripts/
│       └── ios/
│           ├── launch-preinstalled.sh  # Custom macOS launch (avoids InstallMedia dialog)
│           ├── ios-sim-boot.sh         # Simulator boot by UUID
│           ├── ios-healthcheck.sh      # iOS VM health probe
│           ├── macos-healthcheck.sh    # macOS VM health probe
│           ├── build-user/             # macOS build user scripts (bootstrapped via scp)
│           └── ssh/                    # SSH keys for macOS VM access
├── docker/
│   └── shared/
│       ├── android-entrypoint.sh       # Custom Android emulator entrypoint
│       └── tls/                        # Pre-generated CA cert/key
├── templates/
│   └── github-actions/
│       └── ci.yml              # Template CI workflow for consuming projects
├── demo/
│   ├── server/                 # Minimal Fastify+Prisma API server
│   ├── mobile/                 # Minimal Expo Router mobile app
│   ├── tests/maestro/          # Demo Maestro tests (login, smoke)
│   └── smithr.yml              # Demo project config
├── docs/
│   ├── ARCHITECTURE.md         # This file
│   ├── CLOJURE-SERVICE.md      # Clojure control plane deep-dive
│   ├── SUCCESSOR-PROMPT.md     # Handover prompt for new Skalds
│   ├── HOST-SETUP.md           # Adding new Smithr hosts
│   ├── IOS-SETUP.md            # iOS PaaS setup guide
│   ├── RESEARCH.md             # Research notes
│   └── artha-lessons.md        # Lessons learned from Artha integration
└── .github/
    └── workflows/              # Smithr's own CI
```

## Naming Conventions

- **Docker prefix**: `smithr-` for all containers, networks, volumes
- **Compose project names**: `smithr-<mode>-<name>-<id>` (e.g., `smithr-ci-training-12345`)
- **Network**: `smithr-network` (10.21.0.0/16) — distinct from Artha's networks (10.20.0.0/16 for `artha-network`, plus `artha_default` for compose services)
- **Sandbox workers**: Rune, Sif, Tyr, Vali (Norse, monosyllabic, no collision with Artha's Ulfr/Bjorn/Arn/Orm)
- **NFS paths**: `/srv/shared/smithr/` (separate from `/srv/shared/artha-ci/`)

## Demo Application

A minimal application to prove the infrastructure works end-to-end:

- **Fastify + Prisma API**: JWT auth, user CRUD, health endpoints (`/health`, `/ready`)
- **Expo Router mobile app**: login screen, greeting from API
- **Maestro tests**: login flow (`login.yaml`), smoke test (`smoke.yaml`)

This is intentionally trivial — the point is to exercise the infrastructure, not to build a real app. The demo has its own `smithr.yml` that configures server, database, phones, and tests.

## Migration Path for Artha

Progress towards running Artha's E2E tests through Smithr:

1. **Add `smithr.yml` to Artha** — DONE. Config at `artha/smithr.yml` with DB setup (Prisma migrations + demo seed), API server (Next.js standalone Docker image), APK paths, test directory, and ADB reverse mappings.
2. **Config-aware Smithr commands** — DONE. `smithr server` and `smithr test` accept `--config smithr.yml` and use the config for DB setup, API lifecycle, APK discovery, and port forwarding.
3. **Build APK with correct API URL** — IN PROGRESS. The pre-built release APK points at `https://artha.care` (baked at build time). Need to either build with `--local` flag (requires `pnpm install` + Gradle) or set up TLS+DNS to proxy `artha.care` to the local API.
4. **Replace Artha's GitHub Actions** — TODO. Replace `artha/.github/workflows/android-e2e.yml` with a Smithr-based workflow using phone pool and shared server.
5. **Remove Artha's embedded infra** — TODO. Delete redundant Docker, CI, and sandbox code once Smithr handles everything.

This is a gradual migration. Artha runs both systems in parallel during the transition.
