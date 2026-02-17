# Smithr — Developer Guide

> Smithr is a project-agnostic CI/testing/sandbox framework with Phone-as-a-Service,
> managed by a Clojure control plane with a real-time dashboard.

## Quick Reference

| What | Where |
|------|-------|
| Architecture docs | [docs/ARCHITECTURE.md](docs/ARCHITECTURE.md) |
| Clojure service docs | [docs/CLOJURE-SERVICE.md](docs/CLOJURE-SERVICE.md) |
| iOS setup guide | [docs/IOS-SETUP.md](docs/IOS-SETUP.md) |
| OpenAPI spec | [hammar/resources/openapi.yaml](hammar/resources/openapi.yaml) |
| Bash CLI | `bin/smithr` |
| Clojure service | `hammar/` (namespaces: `hammar.*`) |
| Docker Compose layers | `layers/` |
| Demo app | `demo/` |
| Config example | `smithr.yml.example` |

## Project Structure

```
smithr/
├── CLAUDE.md                  ← You are here
├── bin/                       ← Bash CLI (smithr phone/server/test/build/sandbox/doctor)
│   └── lib/                   ← Shared bash libraries (common.sh, config.sh, phone-pool.sh)
├── hammar/                    ← Clojure control plane service
│   ├── deps.edn               ← Clojure dependencies (tools.deps)
│   ├── shadow-cljs.edn        ← ClojureScript build config
│   ├── package.json            ← npm deps for shadow-cljs
│   ├── bin/                    ← MCP tool scripts
│   │   ├── mcp-clojure-tools.sh    ← Clojure REPL MCP server
│   │   └── mcp-language-server.sh  ← Clojure LSP MCP server
│   ├── src/hammar/             ← Backend (Clojure)
│   │   ├── core.clj            ← Entry point, system startup
│   │   ├── config.clj          ← EDN config loader (Aero)
│   │   ├── state.clj           ← Atom-based state (resources/leases/hosts)
│   │   ├── docker.clj          ← Docker event subscription (docker-java)
│   │   ├── lease.clj           ← Lease acquire/unlease/GC + SSH tunnels
│   │   ├── macos.clj           ← macOS user lifecycle (create/delete via SSH)
│   │   ├── api.clj             ← Reitit routes + Ring middleware
│   │   ├── handlers.clj        ← Ring handler implementations
│   │   ├── compose.clj         ← Shell out to docker compose CLI
│   │   └── clojure_mcp.clj     ← nREPL + clojure-mcp entry point
│   ├── src/hammar/ui/          ← Frontend (ClojureScript + Reagent)
│   │   ├── core.cljs            ← App entry, mount + polling
│   │   ├── dashboard.cljs       ← Dashboard components
│   │   ├── api.cljs             ← HTTP client (polls API every 4s)
│   │   └── state.cljs           ← Reagent atoms
│   └── resources/
│       ├── hammar.edn           ← Service config (hosts, ports, GC)
│       ├── openapi.yaml         ← OpenAPI 3.1 spec (source of truth)
│       ├── logback.xml          ← Logging config
│       └── public/              ← Static assets (index.html, CSS)
├── layers/                     ← Docker Compose layers
│   ├── hammar.yml              ← Clojure service container (port 7070)
│   ├── android.yml             ← Android emulator (labelled smithr.managed=true)
│   ├── ios.yml                 ← macOS VM (labelled smithr.managed=true)
│   ├── ios-sim.yml             ← iOS Simulator sidecar (labelled)
│   ├── network.yml, database.yml, dns.yml, tls-proxy.yml, dind.yml, metro.yml
│   └── scripts/ios/            ← iOS VM scripts
├── demo/                       ← Demo app (Fastify+Prisma server, Expo mobile)
├── docs/                       ← Documentation
└── templates/                  ← GitHub Actions CI templates
```

## Running the Clojure Service

```bash
# Start with Clojure CLI
cd hammar && clojure -M:run

# Or via Docker Compose
docker compose -f layers/network.yml -f layers/hammar.yml up -d

# Dev REPL
cd hammar && clojure -M:dev
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
| GET | `/openapi.yaml` | Raw OpenAPI spec |

See [hammar/resources/openapi.yaml](hammar/resources/openapi.yaml) for full schema details.

## Key Architectural Decisions

- **Push-based state**: Docker events via docker-java, not polling
- **Atomic concurrency**: Clojure atoms + `swap!` for all state mutations
- **SSH tunnels on lease**: Hammar creates tunnel on acquire, destroys on unlease/GC.
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
  smithr.resource.parent: "smithr-ios-fe"  # (optional) parent container
```

## Config Files

- **`hammar/resources/hammar.edn`** — Service config (server port, Docker hosts, GC interval)
- **`smithr.yml`** (in consuming projects) — Project config for server/mobile/tests
- **`hammar/resources/openapi.yaml`** — API contract (source of truth for endpoints)

## Development Conventions

- **Logging**: Use `clojure.tools.logging` (backed by Logback). All bash scripts log to stderr.
- **State**: Never mutate state outside `swap!` on `hammar.state/state` atom
- **Naming**: Container names = `smithr-<type>-<rune>` (e.g., `smithr-android-fe`)
- **Network**: `smithr-network` at 10.21.0.0/16, service at 10.21.0.20
- **Ports**: postgres:5433, redis:6380, Smithr API:7070, shadow-cljs:8090

## MCP Tools

The project has Clojure MCP tools configured in `.mcp.json`:

- **clojure-tools**: nREPL + clojure-mcp for interactive REPL evaluation
- **clojure-language-server**: Clojure LSP for code navigation and analysis

These connect to the hammar project classpath and provide code intelligence
for the Clojure codebase.

## Norse Naming

| Role | Name | Description |
|------|------|-------------|
| Jarl | Jules | Human, final authority |
| Skald | Claude | Orchestrator, quality gate |
| Volva | Grok | Business perspective |
| Karls | Workers | Sandbox agents (Rune, Sif, Tyr, Vali) |

GitHub: [JulesGosnell/smithr](https://github.com/JulesGosnell/smithr)
