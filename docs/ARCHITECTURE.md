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
| iOS macOS VM | `layers/ios.yml` | macOS VM via Docker-OSX (QEMU/KVM) |
| iOS Simulator | `layers/ios-sim.yml` | iOS Simulator sidecar (boots inside macOS VM) |
| Metro | `layers/metro.yml` | React Native Metro bundler |
| Smithr Service | `layers/hammar.yml` | Clojure control plane (port 7070) |

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
# Acquire a warm phone from the pool
smithr phone get --type android        # → handle: megalodon:android:5557
smithr phone get --type ios            # → handle: prognathodon:ios:50922

# Release it back to the pool
smithr phone release <handle>

# List all phones across all hosts
smithr phone list

# Check pool status
smithr phone status
```

### Phone Lifecycle

```
  COLD                    WARM                    LEASED                  CLEANING
  (not running)  ──boot──▶ (running,      ──get──▶ (in use by     ──release──▶ (uninstalling
                            idle,                   a test)                      app, wiping
                            in pool)                                             data)
                     ▲                                                    │
                     └────────────────────────────────────────────────────┘
                                        (return to pool)
```

### Android Pool

- Each phone is a separate `budtmo/docker-android` container on smithr-network
- Default device profile: Nexus 5 (works on API 28 and API 34; `pixel_7` does NOT exist as a profile name)
- ~4 cores + 4GB RAM per emulator → 4 phones per 16-core/64GB host
- KVM + GPU passthrough for performance
- Unique IP (10.21.0.30, .31, .32...), ADB port (5555, 5556...), VNC port (5900, 5901...)
- CA certificate pre-baked — no per-phone cert installation needed

### iOS Pool

- Each macOS VM runs via Docker-OSX (QEMU) with QCOW2 overlays
- Apple constraint: one instance per phone model per macOS VM
- Within a VM, simulators are lightweight (~30s boot via `xcrun simctl`)
- ~22GB RAM per macOS VM → 2 VMs per 64GB host
- Multiple phone models per VM (iPhone 16 + iPhone 15 + iPad Pro)
- For duplicate models, schedule across different VMs

### State Management

**Clojure control plane** (see [CLOJURE-SERVICE.md](CLOJURE-SERVICE.md)):

- Push-based state via Docker event subscription (docker-java)
- Atom-based concurrency (`swap!`) — no filesystem locking
- SSH tunnels created on lease acquire, destroyed on release/GC
- REST API on port 7070 with real-time Reagent dashboard
- OpenAPI 3.1 spec at `hammar/resources/openapi.yaml`

Legacy NFS JSON + flock system (`/srv/shared/smithr/phone-pool/state.json`)
is being replaced by the Clojure service.

### Sandbox Phone Visibility

- In **sandbox mode**: one phone gets a VNC view forwarded to the host desktop
- In **CI mode**: phones run headless — no display needed

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
        run: echo "handle=$(smithr phone get --type android)" >> $GITHUB_OUTPUT

      - name: Run E2E test
        run: |
          smithr test run ${{ matrix.test }} \
            --config smithr.yml \
            --device ${{ steps.phone.outputs.handle }}

      - name: Release phone
        if: always()
        run: smithr phone release ${{ steps.phone.outputs.handle }}

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
├── Test 1: get phone → run test → release phone
├── Test 2: get phone → run test → release phone
├── Test 3: get phone → run test → release phone
└── Test 4: get phone → run test → release phone
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
├── hammar/                     # Clojure control plane (see CLOJURE-SERVICE.md)
│   ├── deps.edn                # Clojure deps (tools.deps)
│   ├── shadow-cljs.edn         # ClojureScript build
│   ├── src/hammar/             # Backend: core, state, docker, lease, api, handlers
│   ├── src/hammar/ui/          # Frontend: Reagent dashboard
│   └── resources/              # Config, OpenAPI spec, static assets
├── bin/
│   ├── smithr                  # Main CLI entrypoint (dispatches subcommands)
│   ├── lib/
│   │   ├── common.sh           # Shared utilities (logging, die, wait_for_healthy)
│   │   ├── config.sh           # smithr.yml parser (Python/PyYAML → JSON → jq)
│   │   └── phone-pool.sh      # Phone pool management (acquire/release/state)
│   ├── smithr-phone            # Phone subcommand (get/release/list/status/warm/clean)
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
│   ├── ios.yml                 # macOS VM via Docker-OSX (QEMU/KVM)
│   ├── ios-sim.yml             # iOS Simulator sidecar (boots inside macOS VM)
│   ├── metro.yml               # React Native Metro bundler
│   ├── dind.yml                # Docker-in-Docker daemon (for sandboxes)
│   └── scripts/
│       └── ios/
│           ├── launch-preinstalled.sh  # Custom macOS launch (avoids InstallMedia dialog)
│           ├── ios-sim-boot.sh         # Simulator boot by UUID
│           ├── ios-healthcheck.sh      # iOS VM health probe
│           ├── macos-healthcheck.sh    # macOS VM health probe
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
