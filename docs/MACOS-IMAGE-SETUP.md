# macOS VM Image Setup

## Overview

Smithr uses a macOS Sonoma VM image (`smithr-sonoma.img`) for:
- **Build leases**: Multiple concurrent SSH sessions with isolated user accounts
- **Phone leases**: Exclusive iOS Simulator access via the `smithr` desktop user

## Image Details

| Property | Value |
|----------|-------|
| File | `/srv/shared/images/smithr-sonoma.img` |
| Base OS | macOS Sonoma |
| Format | QCOW2 (raw on disk, overlays at runtime) |
| Size | ~117GB |
| Admin user | `smithr` (UID 503, admin group) |
| Password | `smithr123` |
| SSH key | `layers/scripts/ios/ssh/macos-ssh-key` |
| Auto-login | `smithr` (via `/etc/kcpassword`) |
| Sudo | Passwordless (`/etc/sudoers.d/smithr`) |

## Pre-installed Software

- macOS Sonoma
- Xcode 16
- iOS 18.3 Simulator Runtime
- Maestro 2.0.10 (E2E test automation)
- SSH with key-based auth

## User Account Architecture

### `smithr` (admin)
- Owns the macOS desktop (auto-login)
- Used for phone leases (iOS Simulator runs under this user)
- Has passwordless sudo for managing build users
- SSH key auth configured

### `build-*` (dynamic)
- Created on build lease acquire, deleted on unlease
- Naming: `build-<first-8-chars-of-lease-id>`
- UIDs start at 600
- Staff group (GID 20)
- Isolated home directories at `/Users/build-*`
- Created/deleted via `dscl` commands over SSH

## Prep Script

The image was prepared using `layers/scripts/ios/prep-smithr-image.sh`:

```bash
# Boot in persistent mode (writes directly to image)
SMITHR_MACOS_IMAGE=/srv/shared/images/smithr-sonoma.img \
SMITHR_MACOS_PERSISTENT=1 \
  docker compose -f layers/network.yml -f layers/xcode.yml up -d

# Wait for boot, then run prep
./layers/scripts/ios/prep-smithr-image.sh 50922 localhost --remove-claude

# Shut down cleanly
ssh -i layers/scripts/ios/ssh/macos-ssh-key -p 50922 smithr@localhost 'sudo shutdown -h now'

# Compact the image (reclaim deleted file space)
qemu-img convert -O qcow2 smithr-sonoma.img smithr-sonoma-compact.img
mv smithr-sonoma-compact.img smithr-sonoma.img
```

## Building a New Image from Scratch

If you need to create a fresh image (2-4 hours):

1. Run Docker-OSX installer with 1 CPU core
2. Install macOS Sonoma via VNC
3. Install Xcode from App Store
4. Download iOS Simulator Runtime (Xcode > Settings > Platforms)
5. Install Maestro: `curl -Ls "https://get.maestro.mobile.dev" | bash`
6. Configure SSH key auth for initial user
7. Run `prep-smithr-image.sh` to create `smithr` admin user
8. Walk through the first-login setup wizard via VNC
9. Shut down and compact

## Operational Notes

- **Volatile mode** (default): QCOW2 overlay protects base image. All changes lost on restart. Good for CI/testing.
- **Persistent mode** (`SMITHR_MACOS_PERSISTENT=1`): Writes directly to image. Only for maintenance/updates.
- **Auto-login**: Uses `/etc/kcpassword` (XOR-encoded password). The `smithr` desktop loads automatically on boot.
- **First-login wizard**: Already completed. New images must do this manually via VNC before production use.
- **NVMe recommended**: Significant boot time improvement over SATA SSD.

## Environment Variables

| Variable | Default | Description |
|----------|---------|-------------|
| `SMITHR_MACOS_IMAGE` | (required) | Path to macOS disk image |
| `SMITHR_MACOS_SSH_USER` | `smithr` | SSH user in macOS |
| `SMITHR_MACOS_MAX_SLOTS` | `10` | Max concurrent build leases per VM |
| `SMITHR_MACOS_PERSISTENT` | `fe` | Set to `1` for persistent writes |
| `SMITHR_MACOS_RAM` | `22` | RAM in GB |
| `SMITHR_MACOS_CORES` | `4` | CPU cores |

## History

- **Original image**: `artha-sonoma.img` with `claude` user (from Artha project)
- **2026-02-17**: Copied to `smithr-sonoma.img`, created `smithr` admin user, removed `claude` user, configured auto-login, compacted QCOW2
