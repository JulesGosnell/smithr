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

**Current image location**: `/srv/shared/images/smithr-sonoma.img` (125GB)

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
5. Install Maestro: `curl -Ls "https://get.maestro.mobile.dev" | bash`
6. Configure SSH key auth
7. Extract the disk image

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
| `SMITHR_IOS_DEVICE` | `iPhone 16` | Simulator device |
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

### Apple Constraints
- Only ONE instance of each iPhone model per macOS VM
- For testing multiple devices of the same model, use separate VMs
- iOS Simulator is lightweight within a VM (~30s boot)
- The macOS VM itself takes 60-120s to boot
