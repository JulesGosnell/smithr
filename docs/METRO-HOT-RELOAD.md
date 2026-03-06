# Metro Hot-Reload on Smithr-Managed Phones

Smithr's `metro` compose template runs Metro (React Native's JS bundler) in a
container alongside a leased phone, enabling instant hot-reload on all four
platform/substrate combinations:

| Platform | Substrate | Port Routing |
|----------|-----------|--------------|
| Android | emulated | `adb reverse tcp:8081` |
| Android | physical | `adb reverse tcp:8081` |
| iOS | simulated | SSH reverse tunnel `-R 8081` to macOS VM |
| iOS | physical | Metro binds `0.0.0.0`, device reaches via LAN |

## How It Works

### The Two Injection Points

The metro template deliberately separates **what gets bundled** from **how it
reaches the phone**. Both are injected by the caller:

#### 1. Source Injection (`METRO_SOURCE`)

Metro needs the app source code to bundle. The host mounts the monorepo root
into the container:

```
METRO_SOURCE=/home/jules/src/artha  →  mounted at /src inside container
```

Metro then runs inside `/src/${METRO_SUBDIR}` (default: `apps/mobile`). This
means:
- Source code lives on the host — edit with any editor
- Metro watches for changes via filesystem events
- `node_modules` must exist (container runs `pnpm install` if missing)
- The mount is read-write so Metro can write caches

#### 2. Debug Stub Injection (`APP_PATH` / `APP_FILE`)

For hot-reload, the app binary must be built **without bundled JS** — a debug
build that knows to fetch bundles from Metro at `localhost:8081` instead of
using embedded assets. This is the "debug stub".

The stub is Artha's responsibility, not Smithr's:
- **Android**: `./gradlew assembleDebug` produces `app-debug.apk`
- **iOS**: `xcodebuild` with Debug configuration produces a `.app` bundle

These are injected via the existing `android-app` or `ios-app` sidecar templates
using `APP_PATH` and `APP_FILE` — the same mechanism used for production
binaries, just pointed at a debug build.

### Template Composition

The `metro` template composes alongside the existing phone + app stack.
No templates are modified — metro is additive:

```
┌──────────┐  ┌────────┐  ┌─────────┐  ┌─────────┐
│  phone   │  │ server │  │   app   │  │  metro  │   ← NEW
│ (lease)  │  │ (lease)│  │ (stub)  │  │ (8081)  │
└──────────┘  └────────┘  └─────────┘  └─────────┘
```

The metro container:
1. Installs platform tools (ADB or SSH client)
2. Connects to the phone via retry loop (no `depends_on` needed)
3. Sets up port routing so the phone can reach `localhost:8081`
4. Starts Metro in the mounted source directory

## Usage

### Fetch templates (once)

```bash
SMITHR=http://10.21.0.1:7070
curl -s $SMITHR/api/compose/phone > /tmp/phone.yml
curl -s $SMITHR/api/compose/android-app > /tmp/app.yml
curl -s $SMITHR/api/compose/metro > /tmp/metro.yml
# Also: server.yml if your app needs a backend
```

### Android (emulated)

```bash
# Build the debug stub (one-time or after native changes)
cd ~/src/artha && ./gradlew :apps:mobile:android:app:assembleDebug
# Output: apps/mobile/android/app/build/outputs/apk/debug/app-debug.apk

# Launch the stack
SMITHR_PLATFORM=android \
  SMITHR_LESSEE=jules-dev \
  APP_PATH=~/src/artha/apps/mobile/android/app/build/outputs/apk/debug \
  APP_FILE=app-debug.apk \
  BUNDLE_ID=care.artha.mobile \
  METRO_SOURCE=~/src/artha \
  docker compose -f /tmp/phone.yml -f /tmp/app.yml -f /tmp/metro.yml \
    -p dev up -d

# Edit source → phone hot-reloads
# Tear down
docker compose -p dev down
```

### Android (physical)

Same as above, add `SMITHR_SUBSTRATE=physical`.

### iOS (simulated)

```bash
# Fetch iOS templates
curl -s $SMITHR/api/compose/ios-phone > /tmp/ios-phone.yml
curl -s $SMITHR/api/compose/ios-app > /tmp/ios-app.yml

SMITHR_PLATFORM=ios \
  SMITHR_LESSEE=jules-dev \
  APP_PATH=~/src/artha/apps/mobile/ios/build/Debug \
  APP_BASENAME=ArthaHealthcare.app \
  BUNDLE_ID=care.artha.mobile \
  METRO_SOURCE=~/src/artha \
  docker compose -f /tmp/ios-phone.yml -f /tmp/ios-app.yml -f /tmp/metro.yml \
    -p dev up -d
```

### iOS (physical)

Same as simulated, add `SMITHR_SUBSTRATE=physical`. Metro binds `0.0.0.0`
automatically — the physical device reaches it over the network.

## Environment Variables

| Variable | Required | Default | Description |
|----------|----------|---------|-------------|
| `METRO_SOURCE` | yes | — | Host path to monorepo root |
| `SMITHR_PLATFORM` | yes | — | `android` or `ios` |
| `SMITHR_SUBSTRATE` | no | — | `emulated`/`simulated`/`physical` |
| `METRO_PORT` | no | `8081` | Metro bundler port |
| `METRO_SUBDIR` | no | `apps/mobile` | Subdirectory within source for Metro |
| `ADB_TARGET` | no | `android-phone:5555` | ADB endpoint (Android) |
| `SSH_TARGET` | no | `ios-phone:22` | SSH endpoint (iOS simulated) |

## Troubleshooting

### Metro starts but app shows "No script URL provided"

The app binary is a release build, not a debug stub. Rebuild with
`assembleDebug` (Android) or Debug configuration (iOS). The app must have
`expo-dev-client` installed.

### Metro starts but phone can't reach it

- **Android**: Check `adb reverse --list` shows `tcp:8081 tcp:8081`
- **iOS simulated**: Check SSH tunnel is established (metro container logs)
- **iOS physical**: Ensure device is on same network as the Smithr host

### "module not found" errors in Metro

`node_modules` may be stale. Delete and let the container reinstall:
```bash
cd ~/src/artha && rm -rf node_modules apps/*/node_modules && pnpm install
```

### Metro is slow to start

First start installs `pnpm` and platform tools (~10-15s). Subsequent
container restarts reuse the installed tools if the container isn't recreated.
