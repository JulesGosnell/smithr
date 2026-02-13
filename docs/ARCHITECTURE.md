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
15. [Migration Path for Arthur](#migration-path-for-arthur)

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
| iOS Phone | `layers/ios.yml` | macOS VM + iOS Simulator |
| Metro | `layers/metro.yml` | React Native Metro bundler |

### Project Layers (provided by the consuming project)

| Layer | File | Provides |
|-------|------|----------|
| App Server | `layers/app-server.yml` | The project's API/web server |
| App Build | `layers/app-build.yml` | Build instructions for the app |
| Test Runner | `layers/test-runner.yml` | Test execution (Maestro, Playwright, etc.) |

### Composition Examples

**CI test job:**
```bash
docker compose \
  -f layers/network.yml \
  -f layers/database.yml \
  -f layers/tls-proxy.yml \
  -f layers/dns.yml \
  -f layers/android.yml \
  -f project/app-server.yml \
  -p "smithr-ci-test-training-$(date +%s)" \
  up -d
```

**Sandbox:**
```bash
docker compose \
  -f layers/dind.yml \
  -f layers/network.yml \
  -f layers/database.yml \
  -f layers/tls-proxy.yml \
  -f layers/dns.yml \
  -f layers/metro.yml \
  -f layers/android.yml \
  -f project/app-server.yml \
  -p "smithr-sandbox-ulfr" \
  up -d
```

## Phone as a Service

### Core Interface

```bash
# Acquire a warm phone from the pool
smithr phone get --type pixel-7        # → handle: megalodon:android:5557
smithr phone get --type iphone-16      # → handle: prognathodon:ios:vm1:iphone-16

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
- No restriction on duplicate models — run N identical Pixel 7s
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

Phone pool state tracked in JSON on NFS:

```
/srv/shared/smithr/phone-pool/state.json
```

- flock-based locking for concurrent access
- Each entry has a TTL (default: 30 min) for abandoned phone cleanup
- Cron job reaps expired leases

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
smithr server start --config smithr.yml

# Check server health
smithr server status

# Stop when done
smithr server stop
```

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

(All Norse, all monosyllabic, no collisions with Arthur's Ulfr/Bjorn/Arn/Orm.)

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
      - name: Start shared server (once, first job only)
        run: smithr server ensure --config smithr.yml

      - name: Acquire phone
        id: phone
        run: echo "handle=$(smithr phone get --type pixel-7)" >> $GITHUB_OUTPUT

      - name: Run E2E test
        run: smithr test run ${{ matrix.test }} --device ${{ steps.phone.outputs.handle }}

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

Projects consume Smithr via a `smithr.yml` configuration file:

```yaml
# smithr.yml — project-specific Smithr configuration
project:
  name: artha
  repo: JulesGosnell/artha
  prefix: artha                    # Docker container prefix

server:
  compose_file: docker/api.yml    # project's server compose
  health_check: "curl -f http://localhost:3000/api/health"
  setup_commands:
    - "pnpm db:migrate"
    - "pnpm db:seed"

build:
  android:
    command: "pnpm mobile:build:android"
    artifact: "apps/mobile/android/app/build/outputs/apk/release/app-release.apk"
  ios:
    command: "pnpm mobile:build:ios"
    artifact: "apps/mobile/ios/build/ArthaHealthcare.app"
  server:
    command: "docker build -t ${PROJECT_NAME}-api ."
    artifact: "${PROJECT_NAME}-api:latest"

phones:
  android:
    model: "pixel_7"
    api_level: 34
    pool_size: 4                   # warm phones per host
  ios:
    models: ["iPhone 16", "iPhone 15"]
    pool_size: 2                   # VMs per host

tests:
  framework: maestro+playwright    # or just "playwright", "maestro", etc.
  suites:
    - name: documents
      script: "bin/run-documents-test.sh"
    - name: profile
      script: "bin/run-profile-test.sh"
    - name: notifications
      script: "bin/run-notifications-test.sh"
    - name: schedule
      script: "bin/run-schedule-test.sh"

infrastructure:
  network_subnet: "10.21.0.0/16"  # default, override if needed
  nfs_path: "/srv/shared"
  macos_image: "/srv/shared/images/sonoma.img"
  tls:
    ca_cert: "docker/shared/tls/ca.crt"
    ca_key: "docker/shared/tls/ca.key"
    domain: "artha.care"           # domain for TLS termination

sandboxes:
  memory_limit: "8g"
  workers:
    - name: rune
      vnc_port: 5996
      metro_port: 8082
    - name: sif
      vnc_port: 5997
      metro_port: 8083
    - name: tyr
      vnc_port: 5998
      metro_port: 8084
    - name: vali
      vnc_port: 5999
      metro_port: 8085
```

## Directory Structure

```
smithr/
├── smithr.yml.example          # Example configuration for consuming projects
├── bin/
│   ├── smithr                  # Main CLI entrypoint
│   ├── lib/
│   │   ├── phone-pool.sh      # Phone pool management core
│   │   ├── server-mgr.sh      # Shared server lifecycle
│   │   ├── sandbox-mgr.sh     # Sandbox lifecycle
│   │   ├── config.sh           # smithr.yml parser
│   │   └── common.sh           # Shared utilities
│   ├── smithr-phone            # Phone subcommand (get/release/list/status)
│   ├── smithr-server           # Server subcommand (start/stop/status)
│   ├── smithr-sandbox          # Sandbox subcommand (start/stop/list)
│   ├── smithr-build            # Build subcommand
│   ├── smithr-test             # Test subcommand
│   └── smithr-deploy           # Deploy subcommand
├── layers/
│   ├── dind.yml                # Docker-in-Docker daemon
│   ├── network.yml             # smithr-network definition
│   ├── dns.yml                 # dnsmasq service
│   ├── tls-proxy.yml           # Caddy TLS termination
│   ├── database.yml            # PostgreSQL + Redis
│   ├── android.yml             # Parameterised Android emulator
│   ├── ios.yml                 # macOS VM + iOS Simulator
│   └── metro.yml               # React Native Metro bundler
├── docker/
│   ├── Dockerfile.ci           # CI runner image (Node, Java, Maestro, Playwright, etc.)
│   ├── Dockerfile.sandbox      # Sandbox image (desktop, tools, safety hooks)
│   ├── ci-entrypoint.sh        # CI job lifecycle script
│   ├── sandbox-entrypoint.sh   # Sandbox setup script
│   └── shared/
│       └── tls/
│           ├── ca.crt          # Pre-generated CA certificate
│           └── ca.key          # Pre-generated CA key
├── templates/
│   ├── github-actions/
│   │   ├── ci.yml              # Template CI workflow
│   │   └── sandbox.yml         # Template sandbox workflow
│   └── phone-pods/
│       ├── android-pod.yml     # Template for Android phone container
│       └── ios-pod.yml         # Template for iOS phone VM
├── demo/
│   ├── app/                    # Minimal React Native demo app
│   ├── server/                 # Minimal Express API server
│   ├── tests/                  # Demo Maestro + Playwright tests
│   └── smithr.yml              # Demo project config
├── docs/
│   ├── ARCHITECTURE.md         # This file
│   ├── GETTING-STARTED.md      # Quick start guide
│   ├── PHONE-AS-A-SERVICE.md   # Detailed PaaS documentation
│   ├── CONFIGURATION.md        # smithr.yml reference
│   ├── CI-INTEGRATION.md       # GitHub Actions integration guide
│   ├── SANDBOX.md              # Sandbox setup and usage
│   └── MIGRATION.md            # Guide for migrating from embedded infra
└── .github/
    └── workflows/
        └── smithr-ci.yml       # Smithr's own CI (tests the demo app)
```

## Naming Conventions

- **Docker prefix**: `smithr-` for all containers, networks, volumes
- **Compose project names**: `smithr-<mode>-<name>-<id>` (e.g., `smithr-ci-training-12345`)
- **Network**: `smithr-network` (10.21.0.0/16) — distinct from Arthur's `artha-network` (172.20.0.0/16)
- **Sandbox workers**: Rune, Sif, Tyr, Vali (Norse, monosyllabic, no collision with Arthur's Ulfr/Bjorn/Arn/Orm)
- **NFS paths**: `/srv/shared/smithr/` (separate from `/srv/shared/artha-ci/`)

## Demo Application

A minimal application to prove the infrastructure works end-to-end:

- **React Native app**: single screen with a counter button and a greeting from the API
- **Express API**: single endpoint (`GET /api/hello`) backed by PostgreSQL
- **Maestro test**: tap the button, verify counter increments, verify API greeting displays
- **Playwright test**: create a record via the API, verify it in the database

This is intentionally trivial — the point is to exercise the infrastructure, not to build a real app.

## Migration Path for Arthur

Once Smithr is proven with the demo app:

1. **Add `smithr.yml` to Arthur** — configure Arthur's builds, tests, server, and phone requirements
2. **Replace Arthur's `docker/` infra** — swap Arthur's compose files for Smithr layers
3. **Replace Arthur's `bin/` scripts** — swap Arthur's CI/sandbox scripts for `smithr` CLI commands
4. **Update Arthur's GitHub Actions** — use Smithr template workflows
5. **Remove Arthur's embedded infra** — delete the now-redundant Docker, CI, and sandbox code
6. **Verify** — run Arthur's full test suite through Smithr

This is a gradual migration. Arthur can run both systems in parallel during the transition.
