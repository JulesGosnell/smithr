#!/usr/bin/env bash
# Copyright 2026 Jules Gosnell
# SPDX-License-Identifier: Apache-2.0
# build-wda.sh — Build signed Maestro WDA XCTest runner on macOS VM
#                and install on physical iPhones connected to megalodon.
#
# Strategy:
#   1. Build unsigned (CODE_SIGNING_ALLOWED=NO) — avoids xctrunner profile issue
#   2. Manually re-sign all components with codesign + embedded profiles
#   3. Copy signed .app bundles to megalodon
#   4. Install on physical iPhones via pymobiledevice3
#
# The xctrunner problem: Xcode appends ".xctrunner" to UITest bundle IDs,
# creating a bundle ID (care.artha.maestro-driver-tests.xctrunner) that has
# no matching provisioning profile. We work around this by building unsigned
# and then re-signing with the correct profiles, changing the runner's bundle
# ID to match the tests profile.
#
# Prerequisites:
#   - macOS VM running (smithr-xcode-fe) with SSH on port 50922
#   - Provisioning profiles in /tmp/ on megalodon
#   - Signing cert + WWDR in /Users/smithr/signing/ on VM
#   - Physical iPhones connected via USB
#
# Usage: bin/build-wda.sh

set -euo pipefail

# --- Configuration -----------------------------------------------------------

SSH_PORT=50922
SSH_USER=smithr
SSH_HOST=localhost
SSH_OPTS="-o StrictHostKeyChecking=no -o PasswordAuthentication=no -o ConnectTimeout=10"
SSH_CMD="ssh $SSH_OPTS -p $SSH_PORT $SSH_USER@$SSH_HOST"
SCP_CMD="scp $SSH_OPTS -P $SSH_PORT"

SIGNING_DIR="/Users/smithr/signing"
MAESTRO_REPO="https://github.com/mobile-dev-inc/maestro.git"
MAESTRO_TAG="v2.1.0"
MAESTRO_SRC="/Users/smithr/src/maestro"
DERIVED_DATA="/tmp/maestro-wda-build"
BUILD_OUTPUT="$DERIVED_DATA/Build/Products/Debug-iphoneos"

# Provisioning profiles on megalodon
PROFILE_DRIVER="${WDA_PROFILE_DRIVER:?Set WDA_PROFILE_DRIVER to path of driver .mobileprovision}"
PROFILE_TESTS="${WDA_PROFILE_TESTS:?Set WDA_PROFILE_TESTS to path of tests .mobileprovision}"

# Bundle IDs
NEW_DRIVER_BUNDLE="${WDA_DRIVER_BUNDLE:-care.artha.maestro-driver}"
NEW_TESTS_BUNDLE="${WDA_TESTS_BUNDLE:-care.artha.maestro-driver-tests}"

# Signing
TEAM_ID="${WDA_TEAM_ID:?Set WDA_TEAM_ID to your Apple Developer Team ID}"

# Physical iPhones (space-separated list for multiple devices)
IPHONE_UDIDS="${WDA_IPHONE_UDIDS:?Set WDA_IPHONE_UDIDS to space-separated device UDIDs}"

PMD3="/home/jules/.local/bin/pymobiledevice3"

# --- Helpers -----------------------------------------------------------------

log() { echo "  [wda] $*" >&2; }
die() { echo "  [wda] FATAL: $*" >&2; exit 1; }

ssh_run() {
  $SSH_CMD "$@"
}

# --- Step 1: Copy provisioning profiles to VM --------------------------------

log "Step 1: Copying provisioning profiles to VM..."

for f in "$PROFILE_DRIVER" "$PROFILE_TESTS"; do
  [[ -f "$f" ]] || die "Missing provisioning profile: $f"
done

$SCP_CMD "$PROFILE_DRIVER" "$SSH_USER@$SSH_HOST:$SIGNING_DIR/maestro-driver.mobileprovision"
$SCP_CMD "$PROFILE_TESTS"  "$SSH_USER@$SSH_HOST:$SIGNING_DIR/maestro-tests.mobileprovision"

log "  Profiles copied to $SIGNING_DIR/"

# --- Step 2: Clone maestro source on VM (if not present) ---------------------

log "Step 2: Ensuring Maestro source on VM..."

ssh_run "
  if [[ -d '$MAESTRO_SRC/maestro-ios-xctest-runner' ]]; then
    echo '  Maestro source already present'
  else
    mkdir -p /Users/smithr/src
    cd /Users/smithr/src
    git clone --depth 1 --branch $MAESTRO_TAG $MAESTRO_REPO
    echo '  Cloned maestro $MAESTRO_TAG'
  fi
"

# --- Step 3: Set up keychain on VM -------------------------------------------

log "Step 3: Setting up keychain and certificates..."

# Extract profile UUIDs from filenames (e.g. /tmp/51049c76-...mobileprovision → 51049c76-...)
PROFILE_DRIVER_UUID="$(basename "$PROFILE_DRIVER" .mobileprovision)"
PROFILE_TESTS_UUID="$(basename "$PROFILE_TESTS" .mobileprovision)"

ssh_run bash -s <<REMOTE_SIGNING
set -euo pipefail

SIGNING_DIR="/Users/smithr/signing"
KEYCHAIN="\$HOME/Library/Keychains/build.keychain-db"

P12_PASS=\$(cat "\$SIGNING_DIR/.p12-password")

# Delete stale keychain if any
security delete-keychain "\$KEYCHAIN" 2>/dev/null || true

# Create fresh keychain — all security commands redirect stdout to stderr
security create-keychain -p "" "\$KEYCHAIN" >&2
security unlock-keychain -p "" "\$KEYCHAIN" >&2
security set-keychain-settings -t 7200 "\$KEYCHAIN" >&2

# Import WWDR G3 intermediate certificate
if [[ -f "\$SIGNING_DIR/AppleWWDRCAG3.cer" ]]; then
  security import "\$SIGNING_DIR/AppleWWDRCAG3.cer" \\
    -k "\$KEYCHAIN" -T /usr/bin/codesign >&2
  echo "  Imported WWDR G3 cert"
fi

# Import P12 certificate
IMPORT_ERR=\$(security import "\$SIGNING_DIR/Certificates.p12" \\
  -k "\$KEYCHAIN" -P "\$P12_PASS" \\
  -T /usr/bin/codesign -T /usr/bin/security 2>&1) || true
if [[ "\$IMPORT_ERR" == *"failed"* ]]; then
  echo "  ERROR: P12 import failed: \$IMPORT_ERR" >&2
  exit 1
fi
echo "  Imported P12 certificate"

# Allow codesign to use the keychain without prompting
# CRITICAL: include "codesign:" in partition list to avoid errSecInternalComponent
security set-key-partition-list -S apple-tool:,apple:,codesign: -s -k "" "\$KEYCHAIN" >&2

# Set as default + add to search list
security default-keychain -d user -s "\$KEYCHAIN" >&2
security list-keychains -d user -s "\$KEYCHAIN" >&2

# Install provisioning profiles by UUID
PROFILE_DIR="\$HOME/Library/MobileDevice/Provisioning Profiles"
mkdir -p "\$PROFILE_DIR"
cp "\$SIGNING_DIR/maestro-driver.mobileprovision" "\$PROFILE_DIR/${PROFILE_DRIVER_UUID}.mobileprovision"
cp "\$SIGNING_DIR/maestro-tests.mobileprovision"  "\$PROFILE_DIR/${PROFILE_TESTS_UUID}.mobileprovision"
echo "  Installed 2 provisioning profiles"

security find-identity -v -p codesigning "\$KEYCHAIN" >&2
REMOTE_SIGNING

# --- Step 4: Modify bundle IDs in project.pbxproj ----------------------------

log "Step 4: Updating bundle IDs in project.pbxproj..."

ssh_run bash -s <<REMOTE_BUNDLE
set -euo pipefail

PBXPROJ="/Users/smithr/src/maestro/maestro-ios-xctest-runner/maestro-driver-ios.xcodeproj/project.pbxproj"

# Restore from original if we have a backup (idempotent reruns)
if [[ -f "\$PBXPROJ.orig" ]]; then
  cp "\$PBXPROJ.orig" "\$PBXPROJ"
else
  cp "\$PBXPROJ" "\$PBXPROJ.orig"
fi

# Replace driver bundle ID (app target)
sed -i '' 's/dev\.mobile\.maestro-driver-ios"/${NEW_DRIVER_BUNDLE}"/g' "\$PBXPROJ"

# Replace test bundle ID (UITests target)
sed -i '' 's/dev\.mobile\.maestro-driver-iosUITests"/${NEW_TESTS_BUNDLE}"/g' "\$PBXPROJ"

# Set all DEVELOPMENT_TEAM entries to our team
sed -i '' "s/DEVELOPMENT_TEAM = 25CQD4CKK3/DEVELOPMENT_TEAM = ${TEAM_ID}/g" "\$PBXPROJ"
sed -i '' "s/DEVELOPMENT_TEAM = \\"\\"/DEVELOPMENT_TEAM = ${TEAM_ID}/g" "\$PBXPROJ"

echo "  Bundle IDs:"
grep 'PRODUCT_BUNDLE_IDENTIFIER' "\$PBXPROJ" | sed 's/^/    /'
REMOTE_BUNDLE

# --- Step 5: Build unsigned for iphoneos (arm64) -----------------------------

log "Step 5: Building XCTest runner unsigned for iphoneos (arm64)..."

ssh_run bash -s <<'REMOTE_BUILD'
set -euo pipefail
eval "$(/usr/libexec/path_helper -s)"

DERIVED_DATA="/tmp/maestro-wda-build"
XCTEST_DIR="/Users/smithr/src/maestro/maestro-ios-xctest-runner"

rm -rf "$DERIVED_DATA"

echo "  Building with xcodebuild (unsigned)..."

xcodebuild clean build-for-testing \
  -project "$XCTEST_DIR/maestro-driver-ios.xcodeproj" \
  -scheme maestro-driver-ios \
  -sdk iphoneos \
  -destination "generic/platform=iOS" \
  -derivedDataPath "$DERIVED_DATA" \
  ARCHS=arm64 \
  CODE_SIGNING_ALLOWED=NO \
  CODE_SIGNING_REQUIRED=NO \
  CODE_SIGN_IDENTITY="" \
  -quiet 2>&1

echo "  Build succeeded"

for app in maestro-driver-ios.app maestro-driver-iosUITests-Runner.app; do
  if [[ -d "$DERIVED_DATA/Build/Products/Debug-iphoneos/$app" ]]; then
    echo "  $app: OK"
  else
    echo "  ERROR: $app not found" >&2
    exit 1
  fi
done
REMOTE_BUILD

# --- Step 6: Re-sign all components on VM ------------------------------------

log "Step 6: Re-signing .app bundles with provisioning profiles..."

ssh_run bash -s <<REMOTE_SIGN
set -euo pipefail

BUILD="/tmp/maestro-wda-build/Build/Products/Debug-iphoneos"
SIGNING_DIR="/Users/smithr/signing"
KEYCHAIN="\$HOME/Library/Keychains/build.keychain-db"

DRIVER_APP="\$BUILD/maestro-driver-ios.app"
RUNNER_APP="\$BUILD/maestro-driver-iosUITests-Runner.app"
XCTEST_BUNDLE="\$RUNNER_APP/PlugIns/maestro-driver-iosUITests.xctest"

DRIVER_PROFILE="\$SIGNING_DIR/maestro-driver.mobileprovision"
TESTS_PROFILE="\$SIGNING_DIR/maestro-tests.mobileprovision"

# Unlock keychain
security unlock-keychain -p "" "\$KEYCHAIN" >&2

# Get the signing SHA-1 hash (NOT the name — name causes errSecInternalComponent)
SIGN_HASH=\$(security find-identity -v -p codesigning "\$KEYCHAIN" | head -1 | awk '{print \$2}')
echo "  Signing hash: \$SIGN_HASH"

# Helper: extract entitlements from a provisioning profile
extract_entitlements() {
  local profile="\$1"
  local output="\$2"
  security cms -D -i "\$profile" -o /tmp/profile_plist.plist 2>/dev/null
  /usr/libexec/PlistBuddy -x -c "Print :Entitlements" /tmp/profile_plist.plist > "\$output"
}

extract_entitlements "\$DRIVER_PROFILE" /tmp/driver-ent.plist
extract_entitlements "\$TESTS_PROFILE" /tmp/tests-ent.plist

# 1. Sign maestro-driver-ios.app (host app)
echo "  [1/4] Signing maestro-driver-ios.app..."
cp "\$DRIVER_PROFILE" "\$DRIVER_APP/embedded.mobileprovision"
/usr/libexec/PlistBuddy -c "Set :CFBundleIdentifier ${NEW_DRIVER_BUNDLE}" "\$DRIVER_APP/Info.plist"
codesign --force --sign "\$SIGN_HASH" \\
  --keychain "\$KEYCHAIN" \\
  --entitlements /tmp/driver-ent.plist \\
  --timestamp=none \\
  "\$DRIVER_APP" >&2

# 2. Sign the xctest plugin
echo "  [2/4] Signing maestro-driver-iosUITests.xctest..."
codesign --force --sign "\$SIGN_HASH" \\
  --keychain "\$KEYCHAIN" \\
  --entitlements /tmp/tests-ent.plist \\
  --timestamp=none \\
  "\$XCTEST_BUNDLE" >&2

# 3. Sign embedded frameworks in the runner
echo "  [3/4] Signing embedded frameworks..."
for item in "\$RUNNER_APP/Frameworks/"*; do
  if [[ -e "\$item" ]]; then
    codesign --force --sign "\$SIGN_HASH" \\
      --keychain "\$KEYCHAIN" \\
      --timestamp=none \\
      "\$item" >&2
  fi
done

# 4. Sign the runner app — change bundle ID from .xctrunner to match profile
echo "  [4/4] Signing maestro-driver-iosUITests-Runner.app..."
/usr/libexec/PlistBuddy -c "Set :CFBundleIdentifier ${NEW_TESTS_BUNDLE}" \\
  "\$RUNNER_APP/Info.plist"
cp "\$TESTS_PROFILE" "\$RUNNER_APP/embedded.mobileprovision"
codesign --force --sign "\$SIGN_HASH" \\
  --keychain "\$KEYCHAIN" \\
  --entitlements /tmp/tests-ent.plist \\
  --timestamp=none \\
  "\$RUNNER_APP" >&2

# Verify
echo ""
codesign --verify --deep --strict "\$DRIVER_APP" 2>&1 && echo "  Driver: VALID" || { echo "  Driver: INVALID" >&2; exit 1; }
codesign --verify --deep --strict "\$RUNNER_APP" 2>&1 && echo "  Runner: VALID" || { echo "  Runner: INVALID" >&2; exit 1; }
REMOTE_SIGN

# --- Step 7: Copy built .app bundles from VM to megalodon --------------------

log "Step 7: Copying signed .app bundles to megalodon..."

BUILD_DIR="/tmp/maestro-wda-apps"
rm -rf "$BUILD_DIR"
mkdir -p "$BUILD_DIR"

ssh_run "cd /tmp/maestro-wda-build/Build/Products/Debug-iphoneos && \
  tar cf - maestro-driver-ios.app maestro-driver-iosUITests-Runner.app" \
  | tar xf - -C "$BUILD_DIR/"

log "  Extracted to $BUILD_DIR/"

# --- Step 8: Install on both iPhones ----------------------------------------

log "Step 8: Installing on physical iPhones..."

install_app() {
  local udid="$1"
  local name="$2"
  local app="$3"

  log "  Installing $app on $name ($udid)..."
  $PMD3 apps install --udid "$udid" "$BUILD_DIR/$app" 2>&1
  log "  OK"
}

for udid in $IPHONE_UDIDS; do
  install_app "$udid" "$udid" "maestro-driver-ios.app"
  install_app "$udid" "$udid" "maestro-driver-iosUITests-Runner.app"
done

# --- Step 9: Verify installation ---------------------------------------------

log "Step 9: Verifying installation..."

for udid in $IPHONE_UDIDS; do
  log "  Device: $udid"
  $PMD3 apps list --udid "$udid" 2>&1 | python3 -c "
import sys, json
apps = json.load(sys.stdin)
for bid in ['$NEW_DRIVER_BUNDLE', '$NEW_TESTS_BUNDLE']:
    if bid in apps:
        ver = apps[bid].get('CFBundleShortVersionString', '?')
        signer = apps[bid].get('SignerIdentity', '?')
        print(f'    {bid}: v{ver} ({signer})')
    else:
        print(f'    {bid}: NOT FOUND')
        sys.exit(1)
" 2>&1
done

log "Done. WDA XCTest runner built and installed on all devices."
