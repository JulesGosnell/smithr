#!/bin/bash
# Smithr Sandbox entrypoint
# Starts sshd + optional VNC/Xfce desktop

# --- SSH setup (inherited from android-build) ---
cp /tmp/authorized_keys /home/smithr/.ssh/authorized_keys
chown -R smithr:smithr /home/smithr/.ssh
chmod 700 /home/smithr/.ssh
chmod 600 /home/smithr/.ssh/authorized_keys
if [ -S /var/run/docker.sock ]; then chmod 666 /var/run/docker.sock; fi

# --- VNC desktop (if enabled) ---
if [ "${SMITHR_VNC:-0}" = "1" ]; then
    VNC_DISPLAY="${VNC_DISPLAY:-:1}"
    VNC_RESOLUTION="${VNC_RESOLUTION:-1920x1080}"

    # Create VNC password file (no auth by default for local use)
    mkdir -p /home/smithr/.vnc
    echo "${VNC_PASSWORD:-sandbox}" | vncpasswd -f > /home/smithr/.vnc/passwd
    chmod 600 /home/smithr/.vnc/passwd

    # Xfce startup for VNC sessions
    cat > /home/smithr/.vnc/xstartup << 'XSTARTUP'
#!/bin/bash
unset SESSION_MANAGER DBUS_SESSION_BUS_ADDRESS
exec startxfce4
XSTARTUP
    chmod +x /home/smithr/.vnc/xstartup
    chown -R smithr:smithr /home/smithr/.vnc

    # Start VNC server as smithr user
    su -l smithr -c "vncserver ${VNC_DISPLAY} -geometry ${VNC_RESOLUTION} -depth 24 -localhost no" &
    echo "[sandbox] VNC started on ${VNC_DISPLAY} (${VNC_RESOLUTION})"
fi

# --- Start sshd (foreground, keeps container alive) ---
exec /usr/sbin/sshd -D -e
