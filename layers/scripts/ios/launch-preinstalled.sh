#!/bin/bash
# Copyright 2026 Jules Gosnell
# SPDX-License-Identifier: Apache-2.0
#
# Launch script for PRE-INSTALLED macOS images
#
# This is a modified version of Docker-OSX's Launch.sh that:
# - REMOVES the InstallMedia/BaseSystem drive (not needed for pre-installed images)
# - Avoids the "disk not readable" dialog caused by dummy BaseSystem files
# - Uses a Unix socket for QEMU monitor (enables graceful ACPI shutdown)
# - VOLATILE MODE (default): Creates QCOW2 overlay, base image stays read-only
# - PERSISTENT MODE: Writes directly to base image (for updates)
#
# Usage: Mount your disk image to /home/arch/OSX-KVM/mac_hdd_ng.img and run this script
#
# Environment variables:
#   PERSISTENT=1      - Write directly to base image (default: 0, use overlay)
#   IMAGE_PATH        - Path to base image (default: /home/arch/OSX-KVM/mac_hdd_ng.img)
#   IMAGE_FORMAT      - Format of base image: raw or qcow2 (default: auto-detect)
#
# Graceful shutdown: Send "system_powerdown" to the monitor socket
#   echo "system_powerdown" | socat - UNIX-CONNECT:/tmp/qemu-monitor.sock
#
set -eu

# Monitor socket path (accessible from host via docker exec)
MONITOR_SOCKET="/tmp/qemu-monitor.sock"
OVERLAY_PATH="/tmp/macos-overlay.qcow2"
BASE_IMAGE="${IMAGE_PATH:-/home/arch/OSX-KVM/mac_hdd_ng.img}"

# Fix permissions
sudo chown $(id -u):$(id -g) /dev/kvm 2>/dev/null || true
sudo chown -R $(id -u):$(id -g) /dev/snd 2>/dev/null || true

# Handle RAM shortcuts
[[ "${RAM:-}" = max ]] && export RAM="$(("$(head -n1 /proc/meminfo | tr -dc "[:digit:]") / 1000000"))"
[[ "${RAM:-}" = half ]] && export RAM="$(("$(head -n1 /proc/meminfo | tr -dc "[:digit:]") / 2000000"))"

# Cleanup any stale socket and overlay
rm -f "$MONITOR_SOCKET"

# Detect image format if not specified
detect_image_format() {
    local img="$1"
    if command -v qemu-img &>/dev/null; then
        qemu-img info "$img" 2>/dev/null | grep -q "file format: qcow2" && echo "qcow2" || echo "raw"
    elif [[ "$img" == *.qcow2 ]]; then
        echo "qcow2"
    else
        echo "raw"
    fi
}

# Determine boot image (overlay or direct)
setup_boot_image() {
    if [[ "${PERSISTENT:-0}" == "1" ]]; then
        echo "PERSISTENT MODE: Writing directly to base image"
        BOOT_IMAGE="$BASE_IMAGE"
        BOOT_FORMAT="${IMAGE_FORMAT:-$(detect_image_format "$BASE_IMAGE")}"
    else
        echo "VOLATILE MODE: Creating ephemeral overlay (base image protected)"

        # Detect base image format
        local base_format="${IMAGE_FORMAT:-$(detect_image_format "$BASE_IMAGE")}"
        echo "  Base image: $BASE_IMAGE (format: $base_format)"

        # Remove any stale overlay
        rm -f "$OVERLAY_PATH"

        # Create overlay backed by base image
        # -F specifies the backing file format
        qemu-img create -f qcow2 -b "$BASE_IMAGE" -F "$base_format" "$OVERLAY_PATH"

        BOOT_IMAGE="$OVERLAY_PATH"
        BOOT_FORMAT="qcow2"
        echo "  Overlay: $OVERLAY_PATH"
    fi
}

setup_boot_image

# Graceful shutdown handler
shutdown_handler() {
    echo ""
    echo "Received SIGTERM - sending ACPI shutdown to macOS..."
    if command -v socat &>/dev/null && [ -S "$MONITOR_SOCKET" ]; then
        echo "system_powerdown" | socat - UNIX-CONNECT:"$MONITOR_SOCKET" 2>/dev/null || true
        echo "Waiting for macOS to shutdown gracefully..."
        # Wait for QEMU to exit (up to 60 seconds)
        for i in {1..60}; do
            if ! kill -0 $QEMU_PID 2>/dev/null; then
                echo "macOS shutdown complete."
                cleanup_overlay
                exit 0
            fi
            sleep 1
        done
        echo "Timeout waiting for graceful shutdown, forcing..."
    fi
    kill $QEMU_PID 2>/dev/null || true
    cleanup_overlay
    exit 0
}

# Cleanup overlay on exit (only in volatile mode)
cleanup_overlay() {
    if [[ "${PERSISTENT:-0}" != "1" ]] && [[ -f "$OVERLAY_PATH" ]]; then
        echo "Cleaning up ephemeral overlay: $OVERLAY_PATH"
        rm -f "$OVERLAY_PATH"
    fi
}

trap shutdown_handler SIGTERM SIGINT

echo "Starting QEMU with monitor socket at $MONITOR_SOCKET"

# Start QEMU with monitor on Unix socket (not stdio)
qemu-system-x86_64 -m ${RAM:-8}000 \
-cpu ${CPU:-Haswell-noTSX},${CPUID_FLAGS:-vendor=GenuineIntel,+invtsc,vmware-cpuid-freq=on,+ssse3,+sse4.2,+popcnt,+avx,+aes,+xsave,+xsaveopt,check,}${BOOT_ARGS:-} \
-machine q35,${KVM:-"accel=kvm:tcg"} \
-smp ${CPU_STRING:-${SMP:-4},cores=${CORES:-4}} \
-device qemu-xhci,id=xhci \
-device usb-kbd,bus=xhci.0 -device usb-tablet,bus=xhci.0 \
-device isa-applesmc,osk=ourhardworkbythesewordsguardedpleasedontsteal\(c\)AppleComputerInc \
-drive if=pflash,format=raw,readonly=on,file=/home/arch/OSX-KVM/OVMF_CODE.fd \
-drive if=pflash,format=raw,file=/home/arch/OSX-KVM/OVMF_VARS-1024x768.fd \
-smbios type=2 \
-audiodev ${AUDIO_DRIVER:-alsa},id=hda -device ich9-intel-hda -device hda-duplex,audiodev=hda \
-device ich9-ahci,id=sata \
-drive id=OpenCoreBoot,if=none,snapshot=on,format=qcow2,file=${BOOTDISK:-/home/arch/OSX-KVM/OpenCore/OpenCore.qcow2} \
-device ide-hd,bus=sata.2,drive=OpenCoreBoot \
-drive id=MacHDD,if=none,file=${BOOT_IMAGE},format=${BOOT_FORMAT} \
-device ide-hd,bus=sata.4,drive=MacHDD \
-netdev user,id=net0,hostfwd=tcp::${INTERNAL_SSH_PORT:-10022}-:22,hostfwd=tcp::${SCREEN_SHARE_PORT:-5900}-:5900,${ADDITIONAL_PORTS:-} \
-device ${NETWORKING:-vmxnet3},netdev=net0,id=net0,mac=${MAC_ADDRESS:-52:54:00:09:49:17} \
-monitor unix:${MONITOR_SOCKET},server,nowait \
-boot menu=off \
-vga ${VGA:-vmware} \
-virtfs local,path=/mnt/hostshare,mount_tag=hostshare,security_model=none,id=hostshare0 \
${EXTRA:-} &

QEMU_PID=$!
echo "QEMU started with PID $QEMU_PID"
echo "Monitor socket: $MONITOR_SOCKET"

# Wait for QEMU to exit
wait $QEMU_PID
EXIT_CODE=$?

# Cleanup
rm -f "$MONITOR_SOCKET"
cleanup_overlay
echo "QEMU exited with code $EXIT_CODE"
exit $EXIT_CODE
