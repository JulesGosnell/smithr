#!/bin/bash
# docker/shared/android-entrypoint.sh
#
# Wrapper entrypoint for budtmo/docker-android that fixes DinD compatibility.
#
# Problem: The docker-android image runs `sudo chown 1300:1301 /dev/kvm` on startup,
# which fails in Docker-in-Docker (DinD) environments due to user namespace issues.
#
# Solution: If /dev/kvm is already writable by the current user, patch the
# change_permission() method to skip the sudo commands.
#
# Origin: Ported from Artha (https://github.com/JulesGosnell/artha/issues/401)

EMULATOR_PY="/home/androidusr/docker-android/cli/src/device/emulator.py"

# Check if /dev/kvm exists and is writable
if [[ -c /dev/kvm ]] && [[ -w /dev/kvm ]]; then
    echo "[android-entrypoint] /dev/kvm is already writable, patching change_permission() to skip sudo"

    if [[ -f "$EMULATOR_PY" ]]; then
        python3 << 'PATCH_SCRIPT'
import re

emulator_py = "/home/androidusr/docker-android/cli/src/device/emulator.py"

with open(emulator_py, 'r') as f:
    content = f.read()

old_method = r'''    def change_permission\(self\) -> None:
        kvm_path = "/dev/kvm"
        if os\.path\.exists\(kvm_path\):
            cmds = \(f"sudo chown 1300:1301 \{kvm_path\}",
                    "sudo sed -i '1d' /etc/passwd"\)
            for c in cmds:
                subprocess\.check_call\(c, shell=True\)
            self\.logger\.info\("KVM permission is granted!"\)
        else:
            raise RuntimeError\("/dev/kvm cannot be found!"\)'''

new_method = '''    def change_permission(self) -> None:
        # Patched for DinD compatibility - /dev/kvm already accessible
        self.logger.info("KVM permission check skipped (DinD mode - already accessible)")'''

if re.search(old_method, content):
    content = re.sub(old_method, new_method, content)
    with open(emulator_py, 'w') as f:
        f.write(content)
    print("[android-entrypoint] Successfully patched emulator.py")
else:
    print("[android-entrypoint] Warning: Could not find change_permission method to patch")
    print("[android-entrypoint] The image may have been updated - check for compatibility")
PATCH_SCRIPT
    else
        echo "[android-entrypoint] Warning: $EMULATOR_PY not found"
    fi
else
    echo "[android-entrypoint] /dev/kvm not writable, using original startup (may require sudo)"
fi

# Call the original entrypoint
exec /home/androidusr/docker-android/mixins/scripts/run.sh "$@"
