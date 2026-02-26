# Android Physical Phone Setup

## Overview

Smithr can manage physical Android phones connected via USB. A wrapper
container makes the phone indistinguishable from an emulated device —
cross-host leasing, SSH tunnels, and proxy sidecars all work identically.

```
Linux Host (Fedora)
+-- USB cable → Physical Android phone
+-- adb forward (localhost:FWD_PORT → device:5555)
+-- socat (0.0.0.0:BRIDGE_PORT → localhost:FWD_PORT)
+-- Docker wrapper container (smithr-network)
    +-- socat (container:5555 → 10.21.0.1:BRIDGE_PORT)
    +-- healthcheck: adb connect + adb shell echo ok
```

From the outside, the wrapper container looks exactly like an Android
emulator container with ADB on port 5555.

## Prerequisites

### 1. Hardware

- Android phone with USB debugging enabled
- USB cable (data-capable, not charge-only)
- Linux host with `adb` installed

### 2. Host Software

```bash
# Fedora
sudo dnf install android-tools socat

# Ubuntu/Debian
sudo apt install android-tools-adb socat
```

### 3. Phone Bridge Docker Image

Build the lightweight bridge image (alpine + socat + adb):

```bash
cd ~/src/smithr
docker build -t smithr-phone-bridge:latest layers/images/adb-proxy/
```

## Phone Preparation

### Step 1: Enable Developer Options

1. Open **Settings > About Phone**
2. Tap **Build Number** 7 times
3. Go back — **Developer Options** now appears under Settings

### Step 2: Enable USB Debugging

1. Open **Settings > Developer Options**
2. Enable **USB Debugging**
3. Connect phone via USB
4. Accept the "Allow USB debugging?" prompt on the phone
5. Check "Always allow from this computer"

### Step 3: Verify ADB Connection

```bash
adb devices
# Should show your device serial, e.g.:
# 24ffc8cc    device
```

### Step 4: Disable Google Play Protect

Play Protect shows a "Send app for security check?" dialog every time an
APK is sideloaded. This blocks unattended CI.

1. Open **Play Store** on the phone
2. Tap your **profile icon** (top right)
3. Tap **Play Protect**
4. Tap the **gear icon** (top right)
5. Toggle OFF **"Scan apps with Play Protect"**
6. Toggle OFF **"Improve harmful app detection"**
7. Confirm the dialog

**Note**: This cannot be done via ADB on non-rooted phones — the
`settings put global` commands require `WRITE_SECURE_SETTINGS` permission.

You can open the Play Protect settings screen remotely via:
```bash
adb shell am start -a com.google.android.gms.settings.VERIFY_APPS_SETTINGS
```

### Step 5: Keep Screen Awake (Optional)

For phones that will be permanently connected as CI devices:

1. Open **Settings > Developer Options**
2. Enable **Stay Awake** (screen stays on while charging)

### Step 6: Disable Lock Screen (Recommended)

1. Open **Settings > Security > Screen Lock**
2. Set to **None** or **Swipe**

## How It Works

### Automatic Discovery

Smithr scans for USB devices periodically via `adb devices` and
`idevice_id -l`. When a new device is found:

1. **Host bridge created**: `adb forward` + socat on the host
2. **Wrapper container created**: Docker container on `smithr-network`
   with smithr labels
3. **Both hosts see it**: Docker event subscription discovers the
   wrapper container on all connected hosts

When a device is unplugged (and has no active lease):

1. Bridge processes killed (socat, adb forward removed)
2. Wrapper container removed
3. Resource disappears from both hosts

### Container Labels

The wrapper container carries these labels:

```yaml
smithr.managed: "true"
smithr.resource.type: "phone"
smithr.resource.platform: "android"
smithr.resource.substrate: "physical"
smithr.resource.model: "CPH2127"           # ro.product.model
smithr.resource.device-name: "OPPO A53"    # settings global device_name
smithr.resource.connect-host: "megalodon"  # host where USB is connected
smithr.resource.connect-port: "34053"      # host bridge port
smithr.resource.serial: "24ffc8cc"         # USB serial number
```

### Leasing

Leasing works identically to emulated phones:

```bash
# Via compose template
curl -s http://localhost:7070/api/compose/android-phone | \
  SMITHR_LESSEE="my-test" SMITHR_SUBSTRATE=physical \
  docker compose -f - -p my-test up -d

# ADB available at localhost:5555
adb connect localhost:5555
maestro test flows/

# Cleanup
curl -s http://localhost:7070/api/compose/android-phone | \
  docker compose -f - -p my-test down
```

The `SMITHR_SUBSTRATE=physical` filter ensures the lease targets a
physical device rather than an emulator.

## Building the ARM APK

Physical phones use ARM processors, so you need an `arm64-v8a` APK
(not the `x86_64` one used for emulators).

```bash
# Build ARM APK via Smithr build lease
TARGET=device ./bin/mobile-build.sh --platform android release HEAD

# Or directly in a build workspace
./gradlew assembleRelease -PreactNativeArchitectures=arm64-v8a
```

The CI workflow has separate jobs for emulator (`x86_64`) and device
(`arm64-v8a`) APKs.

## Troubleshooting

### Device shows "unauthorized" in `adb devices`

- Unlock the phone screen
- Accept the USB debugging prompt
- If no prompt appears: revoke USB debugging authorizations in
  Developer Options, then reconnect

### Wrapper container unhealthy

Check the bridge chain:

```bash
# Is adb forward active?
adb -s <serial> forward --list

# Is socat running?
ps aux | grep socat

# Can you reach the bridge port?
adb connect localhost:<bridge-port>
```

### "INSTALL_FAILED_NO_MATCHING_ABIS"

You're installing an x86_64 APK on an ARM device. Build with
`-PreactNativeArchitectures=arm64-v8a` or use `TARGET=device`.

### Play Protect dialog keeps appearing

The toggle resets after Play Store updates. Re-disable via:
Play Store > Profile > Play Protect > Gear > Toggle both off.

### Phone disconnects during CI

- Use a short, high-quality USB cable
- Avoid USB hubs — connect directly to the host
- Enable "Stay Awake" in Developer Options
- Check `dmesg` for USB disconnect messages

## Files Reference

```
layers/images/adb-proxy/
  Dockerfile           # Phone bridge image (alpine + socat + adb)
  entrypoint.sh        # socat: container:5555 → host:BRIDGE_PORT
  healthcheck.sh       # adb connect + adb shell echo ok

src/smithr/
  devices.clj          # USB scanning, bridge management, wrapper lifecycle
  docker.clj           # connect-host/connect-port label handling
```
