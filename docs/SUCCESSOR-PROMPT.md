# Successor Prompt — Smithr Clojure Service

You are continuing work on Smithr's Clojure control plane. Your predecessor
built the full skeleton — all namespaces compile, API endpoints are smoke-tested,
but several things need finishing.

**Read these files first:**
- `CLAUDE.md` — project overview and conventions
- `docs/CLOJURE-SERVICE.md` — detailed Clojure service architecture
- `hammar/resources/openapi.yaml` — API contract (source of truth)

**You have Clojure MCP tools available** (REPL + language server) — use them
for interactive development. They connect to the hammar project classpath.

## Priority Work Items

### 1. SSH Tunnel Process Spawning (lease.clj)
The tunnel port allocation works but actual process creation is stubbed.
Implement in `hammar/src/hammar/lease.clj`:
- **Android**: `socat TCP-LISTEN:{tunnel-port},fork,reuseaddr TCP:{adb-host}:{adb-port}`
- **iOS/macOS**: SSH tunnel through container to macOS VM
- Store the `Process` object in the `tunnels` atom so `.destroyForcibly` works on GC

### 2. ClojureScript Build
```bash
cd hammar && npm install && npx shadow-cljs release app
```
This compiles the Reagent dashboard to `resources/public/js/main.js`.
Test by starting the server (`clojure -M:run`) and opening http://localhost:7070.

### 3. Docker Compose Live Test
```bash
docker compose -f layers/network.yml -f layers/hammar.yml up -d
```
Verify the container starts, connects to Docker socket, discovers managed containers.

### 4. iOS Cascading Leases ✅
Done. When leasing an iOS phone (simulator), the parent macOS VM is automatically
held via a build lease. The `smithr.resource.parent` label links child → parent.
- On acquire: creates a "hold:lease-id" build lease on the parent VM
- On unlease: automatically releases the parent hold lease
- Parent hold uses build lease type (shared), so other builds can still use the VM
- Stored as `:parent-lease-id` on the child lease

### 5. Integration Test
Full flow with live Docker:
1. Start an Android emulator with labels: `docker compose -f layers/network.yml -f layers/android.yml up -d`
2. Start Smithr service: `clojure -M:run`
3. Verify `GET /api/resources` shows the emulator
4. `POST /api/leases` acquires it
5. `DELETE /api/leases/{id}` unleases it
6. Wait for TTL expiry to test GC

### 6. Bash CLI Migration ✅
Done. `bin/smithr-phone` now calls the Clojure REST API via curl/jq:
- `smithr phone get --platform android` → `POST /api/leases`, outputs lease UUID
- `smithr phone unlease <lease-id>` → `DELETE /api/leases/{id}`
- `smithr phone info <lease-id>` → `GET /api/leases/{id}` (new command)
- `smithr phone list` → `GET /api/resources`
- `smithr phone status` → `GET /api/health` + `GET /api/leases`
- `smithr phone warm` still uses Docker Compose directly (Clojure auto-discovers via events)
- `smithr phone reap` removed (Clojure GC handles this)

### 7. Remote Docker Host
Test TCP connection to second host (prognathodon):
- Uncomment the second host in `hammar/resources/hammar.edn`
- Ensure Docker daemon on remote host exposes TCP (port 2375)
- Verify event subscription works across hosts

## Key Technical Notes

- **docker-java 3.4.x**: Use `DockerClientImpl/getInstance` (not DockerClientBuilder)
- **State mutations**: Always via `swap!` on `hammar.state/state` atom
- **JSON keys**: Use underscores in API responses (the `kw->underscore` helper in handlers.clj)
- **Container naming**: Younger Futhark runes (fe, ur, thurs...), not numbers
- **Branding**: User-facing = "Smithr", directory = `hammar/`, namespaces = `hammar.*`
