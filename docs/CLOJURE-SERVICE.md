# Smithr Clojure Service

> The Clojure control plane that manages resources via Docker event subscription,
> atomic lease management, and a real-time Reagent dashboard.

## Overview

The service (namespaces `smithr.*`) replaces the previous
NFS JSON + flock phone pool with a proper server:

- **Docker event subscription** via docker-java — push-based, not polling
- **Atom-based state** — all mutations via `swap!` for thread-safety
- **SSH tunnel management** — tunnels created on lease, destroyed on unlease/GC
- **REST API** — OpenAPI 3.1 spec at `resources/openapi.yaml`
- **Reagent dashboard** — real-time resource status with 4s polling

## Namespace Guide

### Backend (Clojure)

| Namespace | Responsibility |
|-----------|---------------|
| `smithr.core` | Entry point. Connects Docker hosts, starts GC loop, starts Jetty on port 7070 |
| `smithr.config` | Loads `smithr.edn` via Aero. Checks `SMITHR_CONFIG` env var first |
| `smithr.state` | Single atom holding `{:resources {} :leases {} :hosts {} :workspaces {} :events []}`. All queries and mutations |
| `smithr.docker` | Docker client creation, container inspection, event subscription. Auto SSH tunnels for remote hosts |
| `smithr.lease` | `acquire!` / `unlease!` / `gc-expired-leases!`. Manages SSH tunnel lifecycle, ADB readiness, server port forwarding |
| `smithr.macos` | macOS user lifecycle via SSH: create/delete build users, script bootstrap via scp |
| `smithr.linux` | Linux user lifecycle via SSH: create/delete build users via useradd/userdel |
| `smithr.api` | Reitit router with Muuntaja. Serves API + static files |
| `smithr.handlers` | Ring handlers for each endpoint. Serializes Clojure maps to JSON with underscore keys |
| `smithr.compose` | Shells out to `docker compose` CLI for up/down/ps |

### Frontend (ClojureScript)

| Namespace | Responsibility |
|-----------|---------------|
| `smithr.ui.core` | Reagent mount + polling init |
| `smithr.ui.dashboard` | Components: header, summary bar, host panels, resource cards |
| `smithr.ui.api` | HTTP client using cljs-ajax. Polls all endpoints every 4s |
| `smithr.ui.state` | Reagent atoms: resources, leases, hosts, health, error |

## State Shape

```clojure
{:resources {"megalodon:android:smithr-android-fe"
             {:id         "megalodon:android:smithr-android-fe"
              :type       :phone
              :platform   :android
              :host       "megalodon"
              :status     :warm      ;; :booting :warm :leased :shared :dead
              :container  "smithr-android-fe"
              :substrate  "emulated" ;; emulated | simulated | physical
              :model      nil        ;; "OPPO A15" for physical phones
              :parent     nil        ;; "smithr-xcode-fe" for iOS sims
              :connection {:adb-host "10.21.0.31" :adb-port 5555}
              :updated-at #inst "..."}}

 ;; macOS VMs have extra fields for shared access:
 ;; {:max-slots 10, :active-leases #{lease-id ...}}

 :leases   {"uuid-here"
             {:id            "uuid-here"
              :resource-id   "megalodon:android:smithr-android-fe"
              :host          "megalodon"
              :lessee        "artha-ci"
              :lease-type    :phone    ;; :phone | :build
              :ttl-seconds   1800
              :acquired-at   #inst "..."
              :expires-at    #inst "..."
              :connection    {:tunnel-port 17001}
              ;; Build leases also have:
              ;; :macos-user "build-abc12345"
              ;; :workspace  "artha-build-1"
              ;; Phone leases may have:
              ;; :parent-lease-id "uuid" (cascading iOS hold)
              ;; :server-ports [3000 6379]
              }}

 :hosts    {"megalodon"
             {:label      "megalodon"
              :docker-uri "unix:///var/run/docker.sock"
              :connected? true}}

 :workspaces {"artha-build-1"
               {:name        "artha-build-1"
                :resource-id "megalodon:macos:smithr-xcode-fe"
                :macos-user  "artha-build-1"
                :status      :idle     ;; :idle | :leased
                :lease-id    nil
                :created-at  #inst "..."}}

 :events   [{:type "lease" :timestamp "..." :lessee "..." :resource "..." ...}]}
```

## Docker Event → State Mapping

| Docker Event | State Transition |
|-------------|-----------------|
| `start` | Inspect container → upsert resource as `:booting` or `:warm` |
| `health_status: healthy` | Mark `:warm` (unless currently `:leased` or `:shared`) |
| `die` / `destroy` | Remove resource from state |

Events are filtered by label `smithr.managed=true`. Exec events are skipped (too noisy).

## Lease Lifecycle

### Phone Lease (exclusive)

```
Client: POST /api/leases {type: "phone", platform: "android", ttl_seconds: 300}
  ↓
acquire! → swap! state atom:
  1. Find first :warm resource matching type+platform (prefer prefer_host)
  2. Atomically mark :leased, create lease entry
  3. Start SSH tunnel (ssh -N -L, allocate port from 17000+)
  4. Wait for tunnel port to accept connections (up to 10s)
  5. For Android: verify ADB responsive through tunnel (up to 15s)
  6. For iOS: acquire cascading build lease on parent macOS VM
  7. Set up server_ports (adb reverse / socat) if requested
  8. Return lease with tunnel connection info
  ↓
Client uses tunnel port to connect (e.g., adb connect localhost:17001)
  ↓
Client: DELETE /api/leases/{id}  (or TTL expires → GC reaps it)
  ↓
unlease! → swap! state atom:
  1. Mark resource :warm again
  2. Remove lease entry
  3. Tear down server_ports (adb reverse --remove-all / pkill socat)
  4. .destroyForcibly on tunnel process → client disconnected
  5. Release cascading parent lease if present
```

### Build Lease (shared, macOS/Linux VMs)

```
Client: POST /api/leases {type: "vm", platform: "macos", lease_type: "build"}
  ↓
acquire! → swap! state atom:
  1. Find :warm or :shared VM with available slots (count < max-slots)
  2. Add lease-id to resource's :active-leases set
  3. Mark :shared (or stay :shared)
  4. Create macOS/Linux user account via SSH (dscl/useradd + SSH key + profile)
  5. Start SSH tunnel to VM
  6. Return lease with SSH connection info (user, host, port, home dir)
  ↓
Client SSHes as build user, runs build
  ↓
unlease! → swap! state atom:
  1. Remove lease-id from :active-leases
  2. If active-leases now empty → mark :warm, else stay :shared
  3. Delete user + home dir (unless workspace lease → mark idle, keep user)
```

### Workspace Lease (persistent build environment)

```
Client: POST /api/leases {type: "vm", platform: "macos", workspace: "artha-build-1"}
  ↓
acquire!:
  1. Validate workspace name (^[a-zA-Z][a-zA-Z0-9-]{2,30}$)
  2. Check not already leased (409 Conflict if busy)
  3. Ensure macOS user exists (create if new, reuse if warm)
  4. Same slot logic as build lease
  5. Register workspace in :workspaces state
  ↓
unlease!:
  1. Same slot cleanup as build lease
  2. Mark workspace :idle (do NOT delete user/home dir)
  ↓
Client: DELETE /api/workspaces/artha-build-1  (explicit purge)
  → Deletes macOS user + home dir permanently
```

## SSH Tunnel Design

Leases provision SSH tunnels so clients never talk directly to containers:

- On **acquire**: allocate port (17000+), start `ssh -N -L port:target-host:target-port localhost`
- On **unlease/GC**: `.destroyForcibly` on the tunnel process
- **Port readiness**: waits up to 10s for tunnel port to accept TCP connections
- **ADB readiness**: for Android, `adb connect` + `getprop sys.boot_completed` through tunnel

Platform-specific targets:
- **Android**: `adb-host:5555` (container IP or host-mapped port)
- **iOS**: parent macOS VM's `ssh-host:10022` (resolved via `smithr.resource.parent` label)
- **macOS**: `ssh-host:10022` (VM SSH port)
- **Android-build**: `ssh-host:22` (container SSH port)

### Remote Host Tunnels

For remote Docker daemon access, `smithr.docker` auto-creates SSH tunnels:
- Remote host without explicit `docker-uri`: `ssh -fNL port:/var/run/docker.sock hostname`
- Remote host with TLS: direct `tcp://host:2376` with mutual TLS
- Remote host with explicit URI: used as-is

### Reverse Port Forwarding (server_ports)

When `server_ports` is included in the lease request, Smithr sets up reverse
forwarding so the phone app can reach server ports on the host:

- **Android**: `adb -s localhost:tunnel_port reverse tcp:PORT tcp:PORT`
- **iOS**: `socat TCP-LISTEN:PORT,fork TCP:10.0.2.2:PORT` inside macOS VM

Cleaned up on unlease via `adb reverse --remove-all` or `pkill socat`.

## Container Labels

All managed containers MUST have these labels for Docker event discovery:

```yaml
labels:
  smithr.managed: "true"
  smithr.resource.type: "phone"            # phone | vm
  smithr.resource.platform: "android"      # android | ios | macos | android-build
  smithr.resource.pool: "android"          # pool grouping
  smithr.resource.substrate: "emulated"    # emulated | simulated | physical
```

Optional labels:
- `smithr.resource.parent` — parent container name (iOS sim → macOS VM)
- `smithr.resource.model` — human-readable model ("OPPO A15", "iPhone 16")
- `smithr.resource.max-slots` — concurrent build capacity (default 10)
- `smithr.resource.connect-host` — override SSH target host (iOS sidecar)
- `smithr.resource.connect-port` — override SSH target port (iOS sidecar)

## macOS User Management

Build users on macOS VMs are managed via shell scripts bootstrapped to the VM:

**Bootstrap flow** (`smithr.macos`):
1. On first SSH to a VM, scp `layers/scripts/ios/build-user/*.sh` to `/Users/smithr/bin/`
2. Track bootstrapped VMs in memory (idempotent)

**Scripts:**
- `create-build-user.sh <username>` — dscl user creation, SSH key, access group, profile
- `delete-build-user.sh <username>` — process kill, user deletion, home dir cleanup
- `list-build-users.sh` — lists existing `build-*` users

**Linux variant** (`smithr.linux`):
- Uses `useradd -m` / `userdel -r` via SSH
- Same interface (`:macos-user` key name for compatibility)
- Adds to `docker` group for container access

## Configuration (`smithr.edn`)

```clojure
{:server {:port 7070 :host "0.0.0.0"}

 :hosts [{:label "megalodon"}                          ;; local — unix socket
         {:label "prognathodon"
          :host-address "prognathodon"                  ;; remote hostname
          :tls {:cert-path "/etc/smithr/tls"}}]         ;; mutual TLS

 :gc {:interval-seconds 30
      :own-host "megalodon"}

 :compose {:project "smithr"
           :network "smithr-network"}

 :tunnel {:key-path "/ssh-key/macos-ssh-key"
          :base-port 17000}}
```

Host connection modes:
1. **Local**: No `host-address` → uses `unix:///var/run/docker.sock`
2. **TLS** (preferred for remote): Set `:tls {:cert-path "..."}` → `tcp://host:2376`
3. **SSH tunnel** (auto): Set only `:host-address` → auto SSH tunnel on port 12375+
4. **Explicit URI**: Set `:docker-uri "tcp://host:2375"` → used directly

## Running

```bash
# Direct
clojure -M:run

# Docker Compose
docker compose -f layers/network.yml -f layers/server.yml up -d

# Dev REPL with nREPL
clojure -M:dev -m nrepl.cmdline

# ClojureScript dev
npm install && npm run dev

# ClojureScript release build
npm install && npx shadow-cljs release app
```

## What's Working

- All Clojure namespaces compile cleanly
- API smoke-tested: health, resources, hosts, lease acquire/unlease all return correct JSON
- Docker event subscription connects to local and remote Docker daemons
- Lease acquire/unlease with atomic state transitions (phone + build + workspace)
- SSH tunnel spawning on acquire (`ssh -N -L`), process kill on unlease/GC
- ADB readiness verification through tunnel (Android phone leases)
- Shared macOS VM build leases with per-user isolation (concurrent builds)
- Warm/persistent workspaces — named users that survive unlease
- macOS user lifecycle via SSH (create, delete, SSH key setup, PATH, locale)
- Linux user lifecycle via SSH (useradd/userdel for Android build containers)
- iOS cascading leases — phone lease holds parent macOS VM
- Reverse port forwarding (adb reverse for Android, socat for iOS)
- GC loop for expired leases (30s interval)
- Physical phone support (ADB proxy with real ADB healthcheck)
- Workspace management (list, get, purge) in API
- Event audit log (POST/DELETE operations recorded with timestamps)

## What Needs Work

1. **ClojureScript release build** — `npx shadow-cljs release app` needs testing with live API
2. **Docker Compose test** — `layers/server.yml` not yet tested with live Docker
3. **Integration test** — full flow: start containers → discover → lease → unlease → GC
4. **Artha migration** — build APK with local API URL, replace GitHub Actions CI
