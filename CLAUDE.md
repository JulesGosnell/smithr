# Smithr — Developer Guide

> Smithr is a project-agnostic CI/testing/sandbox framework with Phone-as-a-Service,
> managed by a Clojure control plane with a real-time dashboard.

## Quick Reference

| What | Where |
|------|-------|
| Architecture docs | [docs/ARCHITECTURE.md](docs/ARCHITECTURE.md) |
| Clojure service docs | [docs/CLOJURE-SERVICE.md](docs/CLOJURE-SERVICE.md) |
| iOS setup guide | [docs/IOS-SETUP.md](docs/IOS-SETUP.md) |
| OpenAPI spec | [resources/openapi.yaml](resources/openapi.yaml) |
| Bash CLI | `bin/smithr` |
| Clojure service | `src/smithr/` (namespaces: `smithr.*`) |
| Docker Compose layers | `layers/` |
| Demo app | `demo/` |
| Config example | `smithr.yml.example` |

## Project Structure

```
smithr/
├── CLAUDE.md                  ← You are here
├── deps.edn                   ← Clojure dependencies (tools.deps)
├── shadow-cljs.edn            ← ClojureScript build config
├── package.json               ← npm deps for shadow-cljs
├── bin/                       ← CLI + MCP tool scripts
│   ├── smithr                  ← Bash CLI (phone/server/test/build/sandbox/doctor)
│   ├── smithr-registry         ← Registry management script
│   ├── mcp-clojure-tools.sh    ← Clojure REPL MCP server
│   ├── mcp-language-server.sh  ← Clojure LSP MCP server
│   └── lib/                   ← Shared bash libraries (common.sh, config.sh, phone-pool.sh)
├── src/smithr/                ← Backend (Clojure)
│   ├── core.clj               ← Entry point, system startup
│   ├── config.clj             ← EDN config loader (Aero)
│   ├── state.clj              ← Atom-based state (resources/leases/hosts)
│   ├── docker.clj             ← Docker event subscription (docker-java)
│   ├── lease.clj              ← Lease acquire/unlease/GC + SSH tunnels
│   ├── macos.clj              ← macOS user lifecycle (create/delete via SSH)
│   ├── linux.clj              ← Linux user lifecycle (useradd/userdel via SSH)
│   ├── api.clj                ← Reitit routes + Ring middleware
│   ├── handlers.clj           ← Ring handler implementations
│   ├── compose.clj            ← Shell out to docker compose CLI
│   └── clojure_mcp.clj        ← nREPL + clojure-mcp entry point
├── src/smithr/ui/             ← Frontend (ClojureScript + Reagent)
│   ├── core.cljs              ← App entry, mount + polling
│   ├── dashboard.cljs         ← Dashboard components
│   ├── api.cljs               ← HTTP client (polls API every 4s)
│   └── state.cljs             ← Reagent atoms
├── resources/
│   ├── smithr.edn             ← Service config (symlink → config/<hostname>.edn)
│   ├── config/                ← Per-host config files
│   │   ├── megalodon.edn
│   │   └── prognathodon.edn
│   ├── openapi.yaml           ← OpenAPI 3.1 spec (source of truth)
│   ├── logback.xml            ← Logging config
│   ├── compose-templates/     ← Templates served by /api/compose/:template
│   └── public/                ← Static assets (index.html, CSS)
├── layers/                    ← Docker Compose layers
│   ├── server.yml             ← Smithr service container (port 7070)
│   ├── registry.yml           ← Local OCI registry (port 5000)
│   ├── android.yml            ← Android emulator (labelled smithr.managed=true)
│   ├── xcode.yml              ← macOS+Xcode VM (labelled smithr.managed=true)
│   ├── ios.yml                ← iOS Simulator sidecar (labelled)
│   ├── email.yml              ← Mock Resend email API (port 3100)
│   ├── sms.yml                ← Mock Twilio SMS API (port 3200)
│   ├── network.yml, database.yml, dns.yml, tls-proxy.yml, dind.yml, metro.yml
│   ├── images/                ← Dockerfiles (smithr-proxy, android-build)
│   ├── registry/templates/    ← OCI compose templates for proxy sidecar
│   └── scripts/ios/           ← iOS VM scripts
├── mocks/                     ← Mock API services for E2E testing
│   ├── email/                 ← Resend-compatible email mock (Fastify)
│   └── sms/                   ← Twilio-compatible SMS mock (Fastify)
├── demo/                      ← Demo app (Fastify+Prisma server, Expo mobile)
├── docs/                      ← Documentation
└── templates/                 ← GitHub Actions CI templates
```

## Running the Clojure Service

```bash
# Start with Clojure CLI (from project root)
clojure -M:run

# Or via Docker Compose
docker compose -f layers/network.yml -f layers/server.yml up -d

# Dev REPL
clojure -M:dev
```

The service listens on port **7070** and serves both the REST API and dashboard.

## API Endpoints

| Method | Path | Description |
|--------|------|-------------|
| GET | `/api/resources` | List resources (filterable by type/platform/status/host) |
| GET | `/api/resources/{id}` | Get single resource |
| POST | `/api/leases` | Acquire a lease (supports `lease_type`: build/phone, optional `workspace`) |
| DELETE | `/api/leases/{id}` | Unlease a resource |
| GET | `/api/leases` | List active leases |
| GET | `/api/workspaces` | List all workspaces (persistent build environments) |
| GET | `/api/workspaces/{name}` | Get workspace by name |
| DELETE | `/api/workspaces/{name}` | Purge workspace (delete macOS user + home dir) |
| GET | `/api/hosts` | List Docker hosts |
| GET | `/api/health` | Health check |
| GET | `/api/compose/:template` | Get compose YAML for a resource type |
| GET | `/openapi.yaml` | Raw OpenAPI spec |

See [resources/openapi.yaml](resources/openapi.yaml) for full schema details.

## Key Architectural Decisions

- **Push-based state**: Docker events via docker-java, not polling
- **Atomic concurrency**: Clojure atoms + `swap!` for all state mutations
- **SSH tunnels on lease**: Smithr creates tunnel on acquire, destroys on unlease/GC.
  Clients never talk directly to containers — tunnel destruction forcibly disconnects.
- **Per-host GC**: Each Smithr instance only garbage-collects its own host's expired leases
- **Younger Futhark runes**: Container suffixes use runes (fe, ur, thurs, oss, reid, kaun...)
  instead of numeric IDs

## Container Labels

All managed containers must have these Docker labels for discovery:

```yaml
labels:
  smithr.managed: "true"
  smithr.resource.type: "phone"       # phone | vm
  smithr.resource.platform: "android" # android | ios | macos
  smithr.resource.pool: "android"     # pool grouping
  smithr.resource.parent: "smithr-xcode-fe"  # (optional) parent container
```

## Config Files

- **`resources/smithr.edn`** — Service config symlink → `config/<hostname>.edn`
- **`smithr.yml`** (in consuming projects) — Project config for server/mobile/tests
- **`resources/openapi.yaml`** — API contract (source of truth for endpoints)

## Development Conventions

- **Logging**: Use `clojure.tools.logging` (backed by Logback). All bash scripts log to stderr.
- **State**: Never mutate state outside `swap!` on `smithr.state/state` atom
- **Naming**: Container names = `smithr-<type>-<rune>` (e.g., `smithr-android-fe`)
- **Network**: `smithr-network` at 10.21.0.0/16, service at 10.21.0.20
- **Ports**: postgres:5433, redis:6380, Smithr API:7070, shadow-cljs:8090, mock-email:3100, mock-sms:3200

## MCP Tools

The project has Clojure MCP tools configured in `.mcp.json`:

- **clojure-tools**: nREPL + clojure-mcp for interactive REPL evaluation
- **clojure-language-server**: Clojure LSP for code navigation and analysis

These connect to the smithr project classpath and provide code intelligence
for the Clojure codebase.

## Norse Naming

| Role | Name | Description |
|------|------|-------------|
| Jarl | Jules | Human, final authority |
| Skald | Claude | Orchestrator, quality gate |
| Volva | Grok | Business perspective |
| Karls | Workers | Sandbox agents (Rune, Sif, Tyr, Vali) |

GitHub: [JulesGosnell/smithr](https://github.com/JulesGosnell/smithr)
