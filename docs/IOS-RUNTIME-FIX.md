# iOS Simulator Runtime Mismatch Fix

## The Problem

Xcode 16.2 ships with an iOS 18.2 SDK (build `22C146`), but Apple no longer
offers a matching iOS 18.2 simulator runtime for download. The only available
runtime is iOS 18.3.1 (build `22D8075`).

Without an override, `xcodebuild` fails with:

```
No simulator runtime version from [<DVTBuildVersion 22D8075>] available
to use with iphonesimulator SDK version <DVTBuildVersion 22C146>
```

## The Fix

Use `xcrun simctl runtime match set` to tell CoreSimulator that SDK build
`22C146` should use runtime `22D8075`:

```bash
xcrun simctl runtime match set 'iphoneos18.2' '22D8075'
```

This creates/updates `~/Library/Developer/CoreSimulator/RuntimeMap.plist`
with the correct `userOverrides` mapping.

### Verifying the Fix

```bash
xcrun simctl runtime match list
```

Look for:

```
iphoneos18.2:
    Chosen Runtime: 22D8075
    User Override: 22D8075
```

If `User Override` is `(null)` and `Chosen Runtime` is `22C146`, the fix
has not been applied.

## How It's Deployed

### Base Image (Persistent Mode)

The override is baked into `smithr-sonoma.img` at two locations:

1. **Per-user**: `/Users/smithr/Library/Developer/CoreSimulator/RuntimeMap.plist`
2. **System-wide**: `/Library/Developer/CoreSimulator/RuntimeMap.plist`

The RuntimeMap.plist has this structure:

```xml
<plist version="1.0">
<dict>
    <key>preferredRuntimes</key><dict/>
    <key>userOverrides</key>
    <dict>
        <key>iphoneos18.2</key>
        <dict>
            <key>22C146</key>
            <string>22D8075</string>
        </dict>
    </dict>
</dict>
</plist>
```

### Build Users

`create-build-user.sh` copies RuntimeMap.plist from the smithr user's home
to each new build user's `~/Library/Developer/CoreSimulator/` directory.
This means every build user inherits the override automatically.

### iOS Sidecar

The iOS simulator sidecar (`layers/ios-sim.yml`) uses the `IOS_RUNTIME`
env var (default: `iOS 18.3`) to select which simulator device to boot.
This is separate from the xcodebuild SDK mapping — both are needed.

## Automated Fix Script

If the runtime match is lost (e.g., after re-downloading runtimes or
upgrading Xcode), run:

```bash
# Boot VM in persistent mode
SMITHR_MACOS_PERSISTENT=1 SMITHR_MACOS_IMAGE=/srv/shared/images/smithr-sonoma.img \
  docker compose -f layers/network.yml -f layers/xcode.yml up -d

# Wait for healthy, then run fix script
docker cp layers/scripts/ios/fix-base-image.sh smithr-xcode-fe:/tmp/
docker exec smithr-xcode-fe scp -P 10022 -i /ssh-key/macos-ssh-key \
  /tmp/fix-base-image.sh smithr@localhost:/tmp/fix-base-image.sh
docker exec smithr-xcode-fe ssh -p 10022 -i /ssh-key/macos-ssh-key \
  smithr@localhost 'bash /tmp/fix-base-image.sh'

# Clean shutdown to persist
docker exec smithr-xcode-fe ssh -p 10022 -i /ssh-key/macos-ssh-key \
  smithr@localhost 'sudo shutdown -h now'

# Wait, remove container, restart volatile
docker rm smithr-xcode-fe
SMITHR_MACOS_IMAGE=/srv/shared/images/smithr-sonoma.img \
  docker compose -f layers/network.yml -f layers/xcode.yml up -d
```

## Key Facts

| Item | Value |
|------|-------|
| Xcode version | 16.2 |
| SDK build | 22C146 (iOS 18.2) |
| Runtime build | 22D8075 (iOS 18.3.1) |
| Runtime size | ~8.1 GB |
| Override command | `xcrun simctl runtime match set 'iphoneos18.2' '22D8075'` |
| Override file | `~/Library/Developer/CoreSimulator/RuntimeMap.plist` |
| Applied to smithr-sonoma.img | 2026-02-19 |

## What Doesn't Work

- **Downloading iOS 18.2 runtime directly**: `xcodebuild -downloadPlatform iOS -buildVersion 22C146` — Apple says "not available for download"
- **Manually creating RuntimeMap.plist**: The `xcrun simctl runtime match set` command must be used — it writes the correct plist structure. Hand-crafted plists with a different schema are ignored.
