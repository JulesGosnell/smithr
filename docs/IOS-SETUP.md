# iOS Phone-as-a-Service Setup

## Overview

Smithr runs iOS apps on real iOS Simulators inside macOS VMs. The architecture:

```
Linux Host (Fedora)
+-- Docker Container (sickcodes/docker-osx)
    +-- QEMU/KVM Virtual Machine
        +-- macOS Sonoma
            +-- Xcode 16 + iOS Simulator (iOS 18.3)
                +-- iPhone 16 (or other devices)
```

## Prerequisites

### 1. Hardware Requirements

- **CPU**: x86_64 with VT-x/AMD-V (KVM support required)
- **RAM**: 22GB per macOS VM instance (minimum 32GB total, 64GB recommended)
- **Disk**: ~130GB for the macOS base image
- **KVM**: `/dev/kvm` must be accessible

### 2. macOS Base Image

The macOS base image is a pre-installed QCOW2/raw disk image containing:
- macOS Sonoma
- Xcode 16
- iOS 18.3 Simulator Runtime
- Maestro 2.0.10 (for E2E test automation)
- SSH configured with key-based auth

**Current image location**: `/srv/shared/images/smithr-sonoma.img` (~120GB)

This image is NOT in git. To set up a new machine, either:

#### Option A: Copy from existing machine
```bash
# From source machine
scp /srv/shared/images/smithr-sonoma.img newmachine:/srv/shared/images/
scp -r /srv/shared/images/ssh/ newmachine:/srv/shared/images/
```

#### Option B: Build from scratch (2-4 hours)
See [Docker-OSX documentation](https://github.com/sickcodes/Docker-OSX) for creating the base image:
1. Run Docker-OSX installer with 1 CPU core
2. Install macOS Sonoma via VNC
3. Install Xcode from App Store
4. Download iOS Simulator Runtime (Xcode > Settings > Platforms)
5. Set RuntimeMap if SDK ≠ runtime version (see "SDK-to-Runtime Mapping" below)
6. Install Maestro: `curl -Ls "https://get.maestro.mobile.dev" | bash`
7. Configure SSH key auth
8. Warm simulator devices (see "Simulator Device Warming" below)
9. Shut down macOS gracefully (ACPI shutdown via QEMU monitor socket)
10. **Compact the image** — mandatory final step (see "Image Compaction" below)

### 3. SSH Key

An SSH key is used for all communication between the host and macOS VM:

**In-repo location**: `layers/scripts/ios/ssh/macos-ssh-key`
**Shared location**: `/srv/shared/images/ssh/macos-ssh-key`

The corresponding public key must be in `~/.ssh/authorized_keys` inside the macOS VM.

### 4. Docker-OSX Image

Pull the Docker-OSX base image:
```bash
docker pull sickcodes/docker-osx:latest
```

### 5. Fedora/SELinux Notes

On Fedora (and other SELinux-enabled systems), Docker volume mounts need the `:z` suffix for SELinux context relabeling. The Smithr compose files include this automatically.

## Quick Start

```bash
# 1. Ensure the macOS image exists
ls -la /srv/shared/images/smithr-sonoma.img

# 2. Start the macOS VM + iOS Simulator
cd /home/jules/src/smithr
SMITHR_MACOS_IMAGE=/srv/shared/images/smithr-sonoma.img \
  docker compose -f layers/network.yml -f layers/xcode.yml -f layers/ios.yml up -d

# 3. Wait for healthy status
docker inspect smithr-xcode-fe --format '{{.State.Health.Status}}'  # macOS+Xcode VM
docker inspect smithr-ios-fe --format '{{.State.Health.Status}}'    # iOS Simulator

# 4. Verify via SSH
ssh -i layers/scripts/ios/ssh/macos-ssh-key -o StrictHostKeyChecking=no -p 50922 smithr@localhost \
  "xcrun simctl list devices booted"

# 5. Connect via VNC (optional, for visual debugging)
remmina vnc://localhost:5999
```

## Using `smithr phone`

```bash
# Warm an iOS phone (starts macOS VM + Simulator)
smithr phone warm --platform ios --type "iPhone 16" --count 1

# List all phones
smithr phone list

# Acquire a phone for testing
handle=$(smithr phone get --platform ios)

# Unlease when done
smithr phone unlease "$handle"

# Shut down
smithr phone shutdown --all
```

## Installing and Running Apps

### Manual App Installation

```bash
SSH_KEY="layers/scripts/ios/ssh/macos-ssh-key"

# Copy app to macOS
scp -i "$SSH_KEY" -P 50922 path/to/MyApp.app.tar.gz smithr@localhost:/tmp/

# Extract and install
ssh -i "$SSH_KEY" -p 50922 smithr@localhost "
  mkdir -p /tmp/app && tar -xzf /tmp/MyApp.app.tar.gz -C /tmp/app
  xcrun simctl install booted /tmp/app/MyApp.app
"

# Launch
ssh -i "$SSH_KEY" -p 50922 smithr@localhost "xcrun simctl launch booted com.example.myapp"

# Take screenshot
ssh -i "$SSH_KEY" -p 50922 smithr@localhost "xcrun simctl io booted screenshot /tmp/screenshot.png"
scp -i "$SSH_KEY" -P 50922 smithr@localhost:/tmp/screenshot.png ./screenshot.png
```

### Running Maestro Tests on iOS

Maestro runs **inside the macOS VM** (not on the Linux host) because it needs direct access to the iOS Simulator:

```bash
SSH_KEY="layers/scripts/ios/ssh/macos-ssh-key"

# Copy test file to macOS
scp -i "$SSH_KEY" -P 50922 tests/mobile/login.yaml smithr@localhost:/tmp/

# Run test
ssh -i "$SSH_KEY" -p 50922 smithr@localhost "
  export PATH=\$HOME/.maestro/bin:\$PATH
  export MAESTRO_DRIVER_STARTUP_TIMEOUT=180000
  maestro test /tmp/login.yaml
"
```

## Architecture Details

### Container Layout

| Container | Purpose | Ports |
|-----------|---------|-------|
| `smithr-xcode-fe` | macOS+Xcode VM (QEMU/KVM) | SSH: 50922, VNC: 5999 |
| `smithr-ios-fe` | Alpine sidecar (boots Simulator via SSH) | - |

### Networking

macOS runs inside QEMU with NAT networking:
- macOS guest IP: `10.0.2.x` (QEMU NAT)
- Host gateway from guest: `10.0.2.2`
- Container IP on smithr-network: `10.21.0.40`

For server connectivity (e.g., API at `localhost:3000` on the host):
- Use socat inside the macOS guest to forward ports
- Or use QEMU hostfwd for specific ports

### QCOW2 Overlay (Volatile Mode)

By default, the macOS VM uses a QCOW2 overlay:
- Base image is never modified (read-only backing file)
- All changes go to an ephemeral overlay in `/tmp`
- Overlay is deleted when the container stops
- This ensures every boot is clean and reproducible

Set `SMITHR_MACOS_PERSISTENT=1` to write directly to the base image (for updates/maintenance).

### SSH Communication

All commands to macOS go through SSH (port 10022 inside container, mapped to 50922 on host):
- Simulator control: `xcrun simctl boot/install/launch/io`
- App management: `xcrun simctl install/uninstall`
- Screenshots: `xcrun simctl io booted screenshot`
- Maestro tests: Run Maestro binary inside macOS

### Health Checks

Two-stage health checking:
1. **macOS health** (`macos-healthcheck.sh`): SSH responsive + Dock running (desktop ready)
2. **iOS health** (`ios-healthcheck.sh`): Simulator.app running + expected device booted

## Environment Variables

| Variable | Default | Description |
|----------|---------|-------------|
| `SMITHR_MACOS_IMAGE` | (required) | Path to macOS disk image |
| `SMITHR_MACOS_RAM` | `22` | RAM in GB |
| `SMITHR_MACOS_CORES` | `4` | CPU cores |
| `SMITHR_MACOS_CPU` | `Haswell-noTSX` | CPU model (critical for AMD) |
| `SMITHR_MACOS_VGA` | `vmware` | VGA adapter |
| `SMITHR_MACOS_VNC_PORT` | `5999` | Host VNC port |
| `SMITHR_MACOS_SSH_PORT` | `50922` | Host SSH port |
| `SMITHR_MACOS_IP` | `10.21.0.40` | IP on smithr-network |
| `SMITHR_IOS_DEVICE` | `iPhone SE (3rd generation)` | Simulator device |
| `SMITHR_IOS_RUNTIME` | `iOS 18.3` | iOS runtime version |
| `SMITHR_MACOS_SSH_USER` | `smithr` | SSH user in macOS |
| `SMITHR_MACOS_PERSISTENT` | `0` | 1=write to image, 0=overlay |
| `SMITHR_MACOS_SHARE` | `/tmp/smithr-macos-share` | Host dir shared to macOS |
| `SMITHR_SCRIPTS_DIR` | `./scripts/ios` | Path to iOS scripts |

## Troubleshooting

### macOS doesn't boot (stuck at OpenCore)
- Ensure `NOPICKER=true` is set
- Check VNC at `localhost:5999` to see the screen
- Try `SMITHR_MACOS_PERSISTENT=1` temporarily for diagnostics

### SSH connection refused
- macOS needs 60-120s to boot — wait for health check to pass
- Check VNC to verify macOS desktop is loaded
- Verify SSH key permissions: `chmod 600 layers/scripts/ios/ssh/macos-ssh-key`

### Permission denied on scripts
- On SELinux systems (Fedora), volume mounts need `:z` suffix
- The compose files include this automatically

### Simulator device not found
- SSH in and list available devices: `xcrun simctl list devices`
- Check runtime: `xcrun simctl list runtimes`
- Ensure the device model exists for the specified runtime

### ALSA audio warnings
- Normal — the container has no audio device
- These warnings are harmless and can be ignored

## Files Reference

### In Smithr Repository
```
layers/
  xcode.yml            # macOS+Xcode VM Docker Compose
  ios.yml              # iOS Simulator sidecar Compose
  scripts/ios/
    launch-preinstalled.sh   # Custom QEMU launch (no InstallMedia)
    ios-sim-boot.sh          # Boots Simulator via SSH
    macos-healthcheck.sh     # macOS health: SSH + Dock
    ios-healthcheck.sh       # iOS health: Simulator + device
    ssh/
      macos-ssh-key          # SSH private key for macOS VM
```

### Outside Repository (per-machine setup)
```
/srv/shared/images/
  smithr-sonoma.img     # macOS base image (125GB, not in git)
  ssh/
    macos-ssh-key      # SSH key (also copied into repo)
```

### Simulator Device Warming

The first boot of each device+runtime combination builds a cache (~30s per
device). To avoid this penalty in CI, warm all target devices in a PERSISTENT
session and bake the caches into the base image.

**Recommended minimum set** — one device per generation and form factor,
covering the realistic range for care workers (iPhone 12 is the oldest
that runs iOS 18):

| Device | Generation | Form Factor |
|--------|-----------|-------------|
| iPhone SE (3rd generation) | SE (2022) | Budget / home button |
| iPhone 12 mini | 12 (2020) | Mini |
| iPhone 13 | 13 (2021) | Standard |
| iPhone 14 Plus | 14 (2022) | Plus |
| iPhone 15 Pro | 15 (2023) | Pro |
| iPhone 16 Pro Max | 16 (2024) | Pro Max |
| iPhone 16e | 16e (2026) | Budget / Dynamic Island |

7 devices, ~3.5 minutes to warm. Add more from the same generation if you
need to test specific screen sizes or hardware features.

**To warm**: In Simulator, create any missing devices via **File > New
Simulator**, then boot each one (**File > Open Simulator**), wait for it to
fully settle, and shut it down. Delete any iPads you don't need.

**To refresh after a runtime reinstall**: All device caches are lost when the
runtime is reinstalled. Re-create and re-warm all devices in PERSISTENT mode.

### SDK-to-Runtime Mapping (RuntimeMap)

When the Xcode SDK version doesn't match the installed Simulator runtime, Xcode
can't resolve build destinations. For example, Xcode 16.2 (SDK 18.2) + iOS 18.3.1
runtime requires a mapping.

**RuntimeMap.plist is per-user**. Each macOS build user needs their own copy or
`xcodebuild` fails with "iOS 18.2 is not installed".

The mapping is set via:
```bash
xcrun simctl runtime match set iphoneos18.2 <runtime-build-number>
```

This writes to `~/Library/Developer/CoreSimulator/RuntimeMap.plist`. Smithr's
`macos.clj` automatically syncs this file from the `smithr` user to every build
user on each lease (see `create-user-cmd`).

**To find the runtime build number**:
```bash
xcrun simctl list runtimes  # Shows e.g. "iOS 18.3 (18.3.1 - 22D8075)"
# Use the build identifier: 22D8075
```

### Image Compaction

The QCOW2 base image fragments over time, especially after persistent-mode
operations (runtime installs, phone warming, user creation). Fragmentation
degrades build I/O performance significantly.

**Check fragmentation**:
```bash
qemu-img check /srv/shared/images/smithr-sonoma.img
# Look for "fragmented" percentage — above 30% warrants compaction
```

**Compact the image** (can run while VMs are using it in volatile mode since
the base image is read-only):
```bash
# Run on the host where the image disk is local (avoid NFS overhead)
qemu-img convert -O qcow2 -c -p \
  /srv/shared/images/smithr-sonoma.img \
  /home/jules/smithr-sonoma-compact.img

# Verify the compacted image
qemu-img check /home/jules/smithr-sonoma-compact.img
qemu-img info /home/jules/smithr-sonoma-compact.img

# Stop VMs, swap images, restart
docker compose -f layers/network.yml -f layers/xcode.yml -f layers/ios.yml down
cp /home/jules/smithr-sonoma-compact.img /srv/shared/images/smithr-sonoma.img
docker compose -f layers/network.yml -f layers/xcode.yml -f layers/ios.yml up -d
rm /home/jules/smithr-sonoma-compact.img
```

**When to compact**: After any persistent-mode session (phone warming, runtime
install, Xcode update). The compaction itself takes 10-30 minutes depending on
image size.

### Persistent Mode Workflow

Use persistent mode only for base image maintenance. The full workflow:

1. **Stop the normal (volatile) container**: `docker compose ... down`
2. **Start in persistent mode**: `SMITHR_MACOS_PERSISTENT=1 docker compose ... up -d`
3. **Make changes** (install runtime, warm phones, update Xcode, etc.)
4. **Set RuntimeMap** (if runtime changed):
   ```bash
   ssh -i layers/scripts/ios/ssh/macos-ssh-key -p 50922 smithr@localhost \
     "xcrun simctl runtime match set iphoneos18.2 <build-number>"
   ```
5. **Shut down VM gracefully** (ACPI shutdown, not docker stop):
   ```bash
   docker exec smithr-xcode-fe bash -c \
     'echo "system_powerdown" | socat - UNIX-CONNECT:/tmp/qemu-monitor.sock'
   ```
   Wait for the container to exit on its own.
6. **Compact the image** — this is **mandatory** after persistent mode, not
   optional. Persistent writes fragment the QCOW2 image. Without compaction,
   volatile-mode builds suffer degraded I/O (up to 60%+ fragmentation
   measured in practice). See "Image Compaction" above.
7. **Restart in normal (volatile) mode**: `docker compose ... up -d`

**CRITICAL**: Only `PERSISTENT=1` enables persistent mode. Any other value
(including the default `0`) uses a volatile overlay. The launch script checks
`== "1"` exactly.

**CRITICAL**: Always compact after persistent mode. Skipping this step is the
single biggest cause of slow builds.

### Apple Constraints
- Only ONE instance of each iPhone model per macOS VM
- For testing multiple devices of the same model, use separate VMs
- iOS Simulator is lightweight within a VM (~30s boot)
- The macOS VM itself takes 60-120s to boot
