# Resource Setup Guide

Smithr manages several resource types. This guide covers first-time setup
for each one. For day-to-day operations see [operations.md](../operations.md).

## Table of Contents

1. [Android Emulator](#1-android-emulator)
2. [Android Physical Phone](#2-android-physical-phone)
3. [iOS Simulator (via macOS VM)](#3-ios-simulator-via-macos-vm)
4. [iOS Physical Phone](#4-ios-physical-phone)
5. [macOS Build VM](#5-macos-build-vm)
6. [Android Build Container](#6-android-build-container)

---

## 1. Android Emulator

An emulated Android phone running inside a Docker container via KVM.

### Prerequisites

| Requirement | Details |
|-------------|---------|
| KVM | `/dev/kvm` must exist and be accessible |
| GPU | `/dev/dri` for hardware rendering (AMD/NVIDIA); fallback: `swiftshader` |
| RAM | ~4 GB per emulator instance |
| Docker image | `budtmo/docker-android:emulator_9.0` (pulled automatically) |
| Network | `smithr-network` created (`10.21.0.0/16`) |

### Setup

```bash
# 1. Ensure smithr-network exists
docker network create --subnet=10.21.0.0/16 smithr-network 2>/dev/null || true

# 2. Start the emulator
cd ~/src/smithr
docker compose -f layers/network.yml -f layers/android.yml up -d

# 3. Wait for healthy (takes 2-5 minutes on first boot)
watch docker inspect smithr-android-fe --format '{{.State.Health.Status}}'

# 4. Verify ADB
adb connect localhost:5555
adb shell getprop ro.product.model
# → "Nexus 5"
```

### Device Profile

The default device profile is **Nexus 5** (API 28). This was chosen because:
- Widely tested with `budtmo/docker-android`
- Works on both API 28 and 34
- `pixel_7` does NOT exist as a valid AVD profile in this image

Override with `SMITHR_ANDROID_DEVICE` if needed, but test thoroughly.

### Multiple Emulators

Each instance needs unique rune, ports, and IP:

```bash
SMITHR_ANDROID_RUNE=ur \
SMITHR_ANDROID_ADB_PORT=5556 \
SMITHR_ANDROID_VNC_PORT=5901 \
SMITHR_ANDROID_NOVNC_PORT=6081 \
SMITHR_ANDROID_IP=10.21.0.31 \
  docker compose -f layers/network.yml -f layers/android.yml \
    -p smithr-phone-ur up -d
```

### Debugging

- **VNC**: `localhost:5900` (or noVNC at `localhost:6080`)
- **GPU issues**: Set `SMITHR_ANDROID_GPU=swiftshader` to use software rendering
- **Boot loops**: Check `docker logs smithr-android-fe` for QEMU/emulator errors

### Key Files

```
layers/android.yml                        # Compose definition
docker/shared/android-entrypoint.sh       # Custom entrypoint (fixes sudo issue)
```

---

## 2. Android Physical Phone

A USB-connected Android phone wrapped in a Docker container so it looks
identical to an emulator from the outside.

### Prerequisites

| Requirement | Details |
|-------------|---------|
| Phone | Any Android phone with USB debugging |
| USB cable | Data-capable (not charge-only) |
| Host tools | `adb`, `socat` installed on the Linux host |
| Docker image | `smithr-phone-bridge:latest` (built locally) |

### Phone Preparation

#### Step 1: Enable Developer Options

1. Open **Settings > About Phone**
2. Find **Build Number** (location varies by manufacturer):
   - Stock Android / Pixel: Settings > About Phone > Build Number
   - Samsung: Settings > About Phone > Software Information > Build Number
   - OPPO/Realme: Settings > About Phone > Version > Build Number
   - Xiaomi: Settings > About Phone > MIUI Version
3. Tap **Build Number** 7 times rapidly
4. Enter your PIN/password if prompted
5. You should see "You are now a developer!" toast message
6. Go back to Settings — **Developer Options** now appears (sometimes
   under "System" or "Additional Settings")

#### Step 2: Enable USB Debugging

1. Open **Settings > Developer Options** (or System > Developer Options)
2. Scroll down to **Debugging** section
3. Enable **USB Debugging**
4. Connect phone to the host via USB cable
5. On the phone, a dialog appears: **"Allow USB debugging?"**
6. Check **"Always allow from this computer"**
7. Tap **Allow**

If no dialog appears:
- Try a different USB cable (some are charge-only)
- Try a different USB port (avoid hubs)
- Revoke USB debugging authorizations: Developer Options > Revoke USB
  debugging authorizations, then reconnect

#### Step 3: Verify ADB Connection

```bash
adb devices
# Should show:
# 24ffc8cc    device
#
# If it shows "unauthorized" — unlock the phone and accept the dialog
# If it shows "no permissions" — check udev rules (see below)
```

**udev rules** (if `adb devices` shows "no permissions"):

```bash
# Find vendor ID
lsusb | grep -i "phone-manufacturer"

# Create udev rule
sudo tee /etc/udev/rules.d/51-android.rules <<EOF
SUBSYSTEM=="usb", ATTR{idVendor}=="22d9", MODE="0666", GROUP="plugdev"
EOF
sudo udevadm control --reload-rules
sudo udevadm trigger

# Reconnect USB cable
```

Common vendor IDs: Google `18d1`, Samsung `04e8`, OPPO `22d9`,
Xiaomi `2717`, Huawei `12d1`, OnePlus `2a70`.

#### Step 4: Disable Google Play Protect

Play Protect shows a "Send app for security check?" dialog on every
sideloaded APK. This blocks unattended CI.

1. Open **Play Store** on the phone
2. Tap your **profile icon** (top right)
3. Tap **Play Protect**
4. Tap the **gear icon** (top right of the Play Protect screen)
5. Toggle OFF **"Scan apps with Play Protect"**
6. Toggle OFF **"Improve harmful app detection"**
7. Confirm the warning dialog

**Note**: These toggles cannot be set via ADB — `settings put global`
requires `WRITE_SECURE_SETTINGS` which needs root. You can open the
settings screen remotely with:
```bash
adb shell am start -a com.google.android.gms.settings.VERIFY_APPS_SETTINGS
```
But the toggles must be tapped manually.

**Note**: Play Store updates may re-enable Play Protect. Re-check after
Play Store auto-updates.

#### Step 5: CI-Friendly Settings

For phones that will be permanently connected as CI devices:

1. **Stay Awake**: Developer Options > Enable **Stay Awake** (screen on
   while charging)
2. **Disable Lock Screen**: Settings > Security > Screen Lock > **None**
   or **Swipe**
3. **Disable Animations** (faster E2E tests): Developer Options >
   - Window animation scale: **Off**
   - Transition animation scale: **Off**
   - Animator duration scale: **Off**

### Build the Bridge Image

```bash
cd ~/src/smithr
docker build -t smithr-phone-bridge:latest layers/images/adb-proxy/
```

### How Registration Works

Smithr auto-discovers USB devices via `adb devices` scans. When found:

1. `adb forward` maps a host port to the device's TCP port 5555
2. `socat` makes the forwarded port reachable on `0.0.0.0`
3. A wrapper container is created on `smithr-network` that bridges
   container port 5555 to the host socat port
4. Docker event subscription propagates the container to all hosts

The wrapper container carries labels marking it as a physical phone
resource, so it's leasable like any emulated phone.

### Leasing

```bash
# Physical phone lease (note SMITHR_SUBSTRATE filter)
curl -s http://localhost:7070/api/compose/android-phone | \
  SMITHR_LESSEE="test" SMITHR_SUBSTRATE=physical \
  docker compose -f - -p my-test up -d
```

### Building ARM APKs

Physical phones need `arm64-v8a` APKs (emulators use `x86_64`):

```bash
# Via CI build script
TARGET=device ./bin/mobile-build.sh --platform android release HEAD

# Or directly
./gradlew assembleRelease -PreactNativeArchitectures=arm64-v8a
```

### Troubleshooting

| Problem | Solution |
|---------|----------|
| `INSTALL_FAILED_NO_MATCHING_ABIS` | Wrong APK arch. Use `arm64-v8a` for physical. |
| Device shows "unauthorized" | Unlock screen, accept USB debugging dialog. |
| Wrapper container unhealthy | Check `adb forward --list` and socat process. |
| Play Protect dialog reappears | Play Store update re-enabled it. Re-disable. |
| Phone disconnects mid-test | Use short cable, avoid hubs, check `dmesg`. |

### Key Files

```
layers/images/adb-proxy/
  Dockerfile           # Bridge image (alpine + socat + adb)
  entrypoint.sh        # socat: container:5555 → host:BRIDGE_PORT
  healthcheck.sh       # adb connect + adb shell echo ok
src/smithr/devices.clj # USB scan, bridge lifecycle, wrapper management
```

### Detailed Guide

See [ANDROID-PHYSICAL-SETUP.md](ANDROID-PHYSICAL-SETUP.md) for the full
architecture diagram and container label reference.

---

## 3. iOS Simulator (via macOS VM)

An iOS Simulator running inside a macOS VM (QEMU/KVM) inside Docker.

### Prerequisites

| Requirement | Details |
|-------------|---------|
| CPU | x86_64 with VT-x/AMD-V (KVM required) |
| RAM | 22 GB per macOS VM |
| Disk | ~130 GB for the base image |
| KVM | `/dev/kvm` accessible |
| Base image | `smithr-sonoma.img` at `/srv/shared/images/` |
| Docker image | `sickcodes/docker-osx:latest` |
| SSH key | `layers/scripts/ios/ssh/macos-ssh-key` |

### macOS VM Image

The macOS VM comes as a pre-built QCOW2 disk image containing:
- macOS Sonoma with auto-login (`smithr` user)
- Xcode 16 + iOS 18.3 Simulator Runtime
- Maestro 2.2.0 (for E2E tests)
- SSH with key-based auth
- Warmed simulator devices

**To get the image**: Copy from an existing host:
```bash
scp megalodon:/srv/shared/images/smithr-sonoma.img /srv/shared/images/
scp -r megalodon:/srv/shared/images/ssh/ /srv/shared/images/
```

To build from scratch (2-4 hours), see [MACOS-IMAGE-SETUP.md](MACOS-IMAGE-SETUP.md).

### Setup

```bash
# 1. Pull the Docker-OSX base image
docker pull sickcodes/docker-osx:latest

# 2. Start macOS VM + iOS Simulator sidecar
cd ~/src/smithr
SMITHR_MACOS_IMAGE=/srv/shared/images/smithr-sonoma.img \
  docker compose -f layers/network.yml -f layers/xcode.yml -f layers/ios.yml up -d

# 3. Wait for healthy (macOS boots in 60-120s, then Simulator boots in ~30s)
watch -n5 'docker inspect smithr-xcode-fe --format "xcode: {{.State.Health.Status}}" && \
            docker inspect smithr-ios-fe --format "ios: {{.State.Health.Status}}"'

# 4. Verify via SSH
ssh -i layers/scripts/ios/ssh/macos-ssh-key -o StrictHostKeyChecking=no \
  -p 50922 smithr@localhost "xcrun simctl list devices booted"
```

### Xcode and Simulator Configuration

The macOS VM image should have:

1. **Xcode installed** from App Store (Xcode 16)
2. **iOS Simulator Runtime** downloaded (Xcode > Settings > Platforms > iOS 18.3)
3. **Simulator devices created** and warmed (see below)
4. **RuntimeMap configured** if SDK version != runtime version (see below)

#### Simulator Device Warming

First boot of each device+runtime builds a cache (~30s each). Warm them
in PERSISTENT mode so the caches bake into the base image:

```bash
# Boot in persistent mode
SMITHR_MACOS_PERSISTENT=1 docker compose ... up -d

# SSH in and warm devices
ssh -i layers/scripts/ios/ssh/macos-ssh-key -p 50922 smithr@localhost
# Inside macOS:
xcrun simctl boot "iPhone SE (3rd generation)"
# Wait for it to fully boot, then:
xcrun simctl shutdown "iPhone SE (3rd generation)"
# Repeat for each target device
```

Recommended minimum device set:

| Device | Generation | Form Factor |
|--------|-----------|-------------|
| iPhone SE (3rd generation) | SE (2022) | Budget |
| iPhone 12 mini | 12 (2020) | Mini |
| iPhone 13 | 13 (2021) | Standard |
| iPhone 14 Plus | 14 (2022) | Plus |
| iPhone 15 Pro | 15 (2023) | Pro |
| iPhone 16 Pro Max | 16 (2024) | Pro Max |

#### SDK-to-Runtime Mapping

When Xcode SDK version != installed runtime (e.g. Xcode 16.2 SDK 18.2 +
iOS 18.3.1 runtime):

```bash
# Find runtime build number
xcrun simctl list runtimes
# → "iOS 18.3 (18.3.1 - 22D8075)" → build number is 22D8075

# Set mapping
xcrun simctl runtime match set iphoneos18.2 22D8075
```

This writes `~/Library/Developer/CoreSimulator/RuntimeMap.plist`. Smithr
auto-syncs this file to build users.

**RuntimeMap needs BOTH entries**: `iphoneos` for device SDK and
`iphonesimulator` for asset catalog compilation. If `actool` fails with
"No simulator runtime version available", add the `iphonesimulator`
entry manually with PlistBuddy.

### Volatile vs Persistent Mode

- **Volatile** (default): QCOW2 overlay protects base image. All changes
  lost on restart. Use for CI/testing.
- **Persistent** (`SMITHR_MACOS_PERSISTENT=1`): Writes directly to image.
  Only for maintenance. **Always compact after** — see Image Compaction
  in [IOS-SETUP.md](IOS-SETUP.md#image-compaction).

### Debugging

- **VNC**: `localhost:5999` — see the macOS desktop
- **SSH**: `ssh -i layers/scripts/ios/ssh/macos-ssh-key -p 50922 smithr@localhost`
- **Simulator logs**: `xcrun simctl spawn booted log stream`

### Key Files

```
layers/xcode.yml                          # macOS VM compose
layers/ios.yml                            # iOS Simulator sidecar compose
layers/scripts/ios/
  launch-preinstalled.sh                  # Custom QEMU launch script
  ios-sim-boot.sh                         # Boots Simulator via SSH
  macos-healthcheck.sh                    # macOS health check
  ios-healthcheck.sh                      # iOS Simulator health check
  ssh/macos-ssh-key                       # SSH private key
```

### Detailed Guides

- [IOS-SETUP.md](IOS-SETUP.md) — full iOS phone setup and troubleshooting
- [MACOS-IMAGE-SETUP.md](MACOS-IMAGE-SETUP.md) — building the macOS VM image
- [IOS-RUNTIME-FIX.md](IOS-RUNTIME-FIX.md) — SDK-to-runtime mapping details

---

## 4. iOS Physical Phone

A USB-connected iPhone managed via `libimobiledevice` and wrapped in a
Docker container — same pattern as Android physical phones.

### Prerequisites

| Requirement | Details |
|-------------|---------|
| iPhone | Any iPhone (ideally running same iOS as Simulator runtime) |
| USB cable | Lightning or USB-C (Apple original recommended) |
| Host tools | `libimobiledevice`, `iproxy` on the Linux host |

### Phone Preparation

#### Step 1: Enable Developer Mode (iOS 16+)

On iOS 16 and later, Developer Mode must be explicitly enabled:

1. Connect the iPhone to a Mac with Xcode (even briefly — this seeds the
   developer disk image)
2. On the iPhone: **Settings > Privacy & Security > Developer Mode**
3. Toggle **Developer Mode** ON
4. The phone will prompt to restart — tap **Restart**
5. After reboot, confirm the "Turn On Developer Mode?" dialog

**Alternative** (without a Mac): Use `ideviceimagemounter` from
`libimobiledevice` to mount the developer disk image. Download the
appropriate DeveloperDiskImage.dmg for your iOS version.

#### Step 2: Trust the Computer

1. Connect iPhone to the Linux host via USB
2. On the iPhone: tap **Trust** on the "Trust This Computer?" dialog
3. Enter your passcode

#### Step 3: Verify Connection

```bash
# Install libimobiledevice
sudo dnf install libimobiledevice usbmuxd    # Fedora
sudo apt install libimobiledevice-utils usbmuxd   # Ubuntu/Debian

# Check device visibility
idevice_id -l
# → <40-character UDID>

# Get device info
ideviceinfo -k DeviceName
# → "Jules' iPhone 12 Pro Max"
```

#### Step 4: Disable Passcode (Recommended for CI)

1. **Settings > Face ID & Passcode** (or Touch ID & Passcode)
2. Tap **Turn Passcode Off**
3. Confirm with current passcode

#### Step 5: Disable Auto-Lock

1. **Settings > Display & Brightness > Auto-Lock**
2. Set to **Never**

### Status

iOS physical phone support uses the same wrapper container pattern as
Android. The bridge uses `iproxy` instead of `adb forward + socat`.
Maestro runs inside the macOS VM (not on the Linux host) since it needs
the XCTest runner.

### Key Files

```
src/smithr/devices.clj     # scan-ios-devices, start-ios-bridge!
```

---

## 5. macOS Build VM

Shared-access macOS VM for iOS/macOS builds. Multiple build users run
concurrently (up to 10 per VM).

### Prerequisites

Same as iOS Simulator — the macOS VM is the same container. Build leases
and phone leases share the same underlying VM.

### Setup

The macOS VM setup in Section 3 covers this. Build leases use:
- **SSH access** with dynamically created `build-*` users
- **Isolated home directories** on a RAM disk (`/Volumes/BuildHomes`)
- **Shared Xcode** and SDK installation

### How Build Users Work

On lease acquire:
1. Smithr creates a macOS user: `build-<first-8-of-lease-id>`
2. UID starts at 600, staff group (GID 20)
3. Home directory created at `/Users/build-*`
4. SSH authorized_keys set up
5. RuntimeMap.plist copied from `smithr` user
6. User added to `com.apple.access_ssh` group

On unlease:
1. All user processes killed
2. Home directory deleted
3. macOS user account deleted via `dscl`

### Leasing

```bash
curl -s http://localhost:7070/api/compose/macos-build | \
  SMITHR_LESSEE="ci-123" SMITHR_WORKSPACE="my-build" \
  docker compose -f - -p my-build up -d

# SSH into the workspace
docker exec my-build-macos-build-1 workspace-ssh "xcodebuild ..."
```

### Key Files

```
layers/xcode.yml                          # macOS VM compose
src/smithr/macos.clj                      # Build user lifecycle
layers/scripts/ios/build-user/            # User creation/deletion scripts
```

### Detailed Guide

See [MACOS-IMAGE-SETUP.md](MACOS-IMAGE-SETUP.md) for user account
architecture and image preparation.

---

## 6. Android Build Container

A Fedora-based container with Android SDK, Node.js, and sshd for
building Android APKs. Supports concurrent build leases with isolated
SSH sessions.

### Prerequisites

| Requirement | Details |
|-------------|---------|
| Docker | Standard Docker (no KVM needed) |
| Image | `smithr-android-build:latest` (built locally or loaded from tar) |
| SSH key | Same key as macOS builds (`/srv/shared/images/ssh/macos-ssh-key.pub`) |

### Setup

```bash
# Option A: Load pre-built image from shared storage
docker load -i /srv/shared/images/smithr-android-build.tar

# Option B: Build locally
cd ~/src/smithr
docker build -t smithr-android-build:latest layers/images/android-build/

# Start the container
docker compose -f layers/network.yml -f layers/android-build.yml up -d

# Verify
docker inspect smithr-android-build-fe --format '{{.State.Health.Status}}'
# → "healthy" (SSH listening)
```

### What's Inside

- Fedora 43
- Android SDK (build-tools, platform-tools, SDK platforms)
- Node.js (for React Native builds)
- sshd (port 22)
- Docker CLI (for nested Docker operations via mounted socket)

### Leasing

```bash
curl -s http://localhost:7070/api/compose/android-build | \
  SMITHR_LESSEE="ci-123" SMITHR_WORKSPACE="my-build" \
  docker compose -f - -p my-build up -d

# Run commands in the workspace
docker exec my-build-android-build-1 workspace-ssh "./gradlew assembleRelease"
```

### Key Files

```
layers/android-build.yml                  # Compose definition
layers/images/android-build/Dockerfile    # Image definition
src/smithr/linux.clj                      # Build user lifecycle (Linux variant)
```

---

## Common Setup

These steps apply to all resource types.

### smithr-network

Every resource lives on `smithr-network`:

```bash
docker network create --subnet=10.21.0.0/16 smithr-network
```

### SELinux (Fedora/RHEL)

All Docker volume mounts need the `:z` suffix:

```yaml
volumes:
  - ./script.sh:/script.sh:z    # :z is required
```

The compose files already include this.

### Shared Images Directory

Resources that need shared images (macOS, Android build, Maestro jars):

```bash
sudo mkdir -p /srv/shared/images/ssh
sudo chown -R jules:jules /srv/shared/images
```

### Adding a Second Host

See [HOST-SETUP.md](HOST-SETUP.md) for connecting a new machine to Smithr
and configuring cross-host Docker event subscription.
