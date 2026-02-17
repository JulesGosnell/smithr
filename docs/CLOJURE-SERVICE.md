# Smithr Clojure Service

> The Clojure control plane that manages resources via Docker event subscription,
> atomic lease management, and a real-time Reagent dashboard.

## Overview

The service (codebase in `hammar/`, namespaces `hammar.*`) replaces the previous
NFS JSON + flock phone pool with a proper server:

- **Docker event subscription** via docker-java — push-based, not polling
- **Atom-based state** — all mutations via `swap!` for thread-safety
- **SSH tunnel management** — tunnels created on lease, destroyed on release/GC
- **REST API** — OpenAPI 3.1 spec at `resources/openapi.yaml`
- **Reagent dashboard** — real-time resource status with 4s polling

## Namespace Guide

### Backend (Clojure)

| Namespace | Responsibility |
|-----------|---------------|
| `hammar.core` | Entry point. Connects Docker hosts, starts GC loop, starts Jetty on port 7070 |
| `hammar.config` | Loads `hammar.edn` via Aero. Checks `HAMMAR_CONFIG` env var first |
| `hammar.state` | Single atom holding `{:resources {} :leases {} :hosts {}}`. All queries and mutations |
| `hammar.docker` | Docker client creation, container inspection, event subscription. One thread per host |
| `hammar.lease` | `acquire!` / `release!` / `gc-expired-leases!`. Manages SSH tunnel lifecycle |
| `hammar.api` | Reitit router with Muuntaja. Serves API + static files |
| `hammar.handlers` | Ring handlers for each endpoint. Serializes Clojure maps to JSON with underscore keys |
| `hammar.compose` | Shells out to `docker compose` CLI for up/down/ps |

### Frontend (ClojureScript)

| Namespace | Responsibility |
|-----------|---------------|
| `hammar.ui.core` | Reagent mount + polling init |
| `hammar.ui.dashboard` | Components: header, summary bar, host panels, resource cards |
| `hammar.ui.api` | HTTP client using cljs-ajax. Polls all endpoints every 4s |
| `hammar.ui.state` | Reagent atoms: resources, leases, hosts, health, error |

## State Shape

```clojure
{:resources {"megalodon:android:smithr-android-fe"
             {:id         "megalodon:android:smithr-android-fe"
              :type       :phone
              :platform   :android
              :host       "megalodon"
              :status     :warm      ;; :booting :warm :leased :dead
              :container  "smithr-android-fe"
              :connection {:adb-host "10.21.0.31" :adb-port 5555}
              :updated-at #inst "..."}}

 :leases   {"uuid-here"
             {:id          "uuid-here"
              :resource-id "megalodon:android:smithr-android-fe"
              :host        "megalodon"
              :lessee      "artha-ci"
              :ttl-seconds 1800
              :acquired-at #inst "..."
              :expires-at  #inst "..."
              :connection  {:tunnel-port 17001}}}

 :hosts    {"megalodon"
             {:label      "megalodon"
              :docker-uri "unix:///var/run/docker.sock"
              :connected? true}}}
```

## Docker Event → State Mapping

| Docker Event | State Transition |
|-------------|-----------------|
| `start` | Inspect container → upsert resource as `:booting` or `:warm` |
| `health_status: healthy` | Mark `:warm` (unless currently `:leased`) |
| `die` / `destroy` | Remove resource from state |

Events are filtered by label `smithr.managed=true`.

## Lease Lifecycle

```
Client: POST /api/leases {type: "phone", platform: "android", ttl_seconds: 300}
  ↓
acquire! → swap! state atom:
  1. Find first :warm resource matching type+platform
  2. Atomically mark :leased, create lease entry
  3. Start SSH tunnel (allocate port from 17000+)
  4. Return lease with tunnel connection info
  ↓
Client uses tunnel port to connect
  ↓
Client: DELETE /api/leases/{id}  (or TTL expires → GC reaps it)
  ↓
release! → swap! state atom:
  1. Mark resource :warm again
  2. Remove lease entry
  3. .destroyForcibly on tunnel process → client disconnected
```

## SSH Tunnel Design

Leases provision SSH tunnels so clients never talk directly to containers:

- On **acquire**: allocate a tunnel port, start socat/SSH bridge
- On **release/GC**: `.destroyForcibly` on the tunnel process
- This forcibly disconnects the client — better than losing control

Tunnel port allocation starts at 17000 and increments. The actual tunnel
process spawning is platform-specific and needs implementation:
- **Android**: `socat TCP-LISTEN:port,fork TCP:adb-host:adb-port`
- **iOS/macOS**: SSH tunnel through container to macOS VM

## Configuration (`hammar.edn`)

```clojure
{:server {:port 7070 :host "0.0.0.0"}
 :hosts [{:label "megalodon" :docker-uri "unix:///var/run/docker.sock"}]
 :gc {:interval-seconds 30 :own-host "megalodon"}
 :compose {:project "smithr" :network "smithr-network"}
 :tunnel {:key-path "/ssh-key/macos-ssh-key" :base-port 17000}}
```

## Container Labels

All managed containers MUST have these labels for Docker event discovery:

```yaml
labels:
  smithr.managed: "true"
  smithr.resource.type: "phone"       # phone | vm
  smithr.resource.platform: "android" # android | ios | macos
  smithr.resource.pool: "android"     # pool grouping
```

Optional: `smithr.resource.parent` for iOS sims that depend on a macOS VM.

## Running

```bash
# Direct
cd hammar && clojure -M:run

# Docker Compose
docker compose -f layers/network.yml -f layers/hammar.yml up -d

# Dev REPL with nREPL
cd hammar && clojure -M:dev -m nrepl.cmdline

# ClojureScript dev
cd hammar && npm install && npm run dev
```

## What's Working

- All Clojure namespaces compile cleanly
- API smoke-tested: health, resources, hosts, lease acquire, lease list all return correct JSON
- Docker event subscription connects to local daemon
- Lease acquire/release with atomic state transitions
- GC loop for expired leases
- Dashboard HTML/CSS ready, ClojureScript components written

## What Needs Work

1. **SSH tunnel process spawning** — port allocation works, actual socat/SSH process creation is stubbed
2. **ClojureScript build** — `npm install && npx shadow-cljs release app` not yet run
3. **Docker Compose test** — `layers/hammar.yml` not yet tested with live Docker
4. **Remote host connection** — TCP Docker daemon connection (prognathodon) untested
5. **Integration test** — full flow: start containers → discover → lease → release → GC
6. **iOS cascading leases** — leasing an iOS phone should hold its parent macOS VM
7. **Bash CLI migration** — update `bin/smithr-phone` to call Hammar API instead of NFS JSON
