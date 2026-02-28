# Adding a New Smithr Host

Guide for setting up a new machine as a Smithr-managed Docker host.

## Prerequisites

- Docker installed and running
- SSH access from the Smithr host (megalodon)
- KVM available (`/dev/kvm`) for emulators/VMs
- GPU available (`/dev/dri`) for Android emulators (optional)

## Step 1: Clone the Smithr repo

```bash
mkdir -p ~/src && cd ~/src
git clone git@github.com:JulesGosnell/smithr.git
cd smithr
```

## Step 2: Create the smithr-network

```bash
docker network create --subnet=10.21.0.0/16 smithr-network
```

## Step 3: Create the custom Android entrypoint

The entrypoint script must exist for Android containers:

```bash
ls docker/shared/android-entrypoint.sh  # should exist from clone
```

## Step 4: Start containers

### Android emulator

```bash
docker compose -f layers/android.yml up -d
```

### macOS VM (requires macOS image)

```bash
SMITHR_MACOS_IMAGE=/srv/shared/images/smithr-sonoma.img \
  docker compose -f layers/xcode.yml up -d
```

**Note:** Each macOS VM needs ~22GB RAM. Adjust `SMITHR_MACOS_RAM` if needed.

### Multiple instances (use different runes and ports)

```bash
# Second Android emulator
SMITHR_ANDROID_RUNE=ur \
SMITHR_ANDROID_ADB_PORT=5556 \
SMITHR_ANDROID_VNC_PORT=5901 \
SMITHR_ANDROID_NOVNC_PORT=6081 \
SMITHR_ANDROID_IP=10.21.0.31 \
  docker compose -f layers/android.yml -p smithr-android-ur up -d
```

## Step 5: Connect to Smithr

Smithr (on megalodon) connects to remote Docker daemons to subscribe to
events and discover managed containers. Two options:

### Option A: SSH tunnel (recommended, no Docker config changes)

From megalodon, create a persistent SSH tunnel:

```bash
ssh -fNL <local-port>:/var/run/docker.sock <hostname>
```

Example for prognathodon:

```bash
ssh -fNL 2375:/var/run/docker.sock prognathodon
```

Then add to `resources/smithr.edn`:

```edn
{:label "prognathodon"
 :docker-uri "tcp://localhost:2375"}
```

### Option B: Docker TCP listener (exposes Docker daemon)

On the remote host, create a systemd override:

```bash
sudo mkdir -p /etc/systemd/system/docker.service.d
sudo tee /etc/systemd/system/docker.service.d/tcp.conf <<'EOF'
[Service]
ExecStart=
ExecStart=/usr/bin/dockerd -H fd:// -H tcp://0.0.0.0:2375 --containerd=/run/containerd/containerd.sock
EOF
sudo systemctl daemon-reload
sudo systemctl restart docker
```

**Warning:** This exposes Docker without TLS. Only use on trusted networks.

Then add to `resources/smithr.edn`:

```edn
{:label "prognathodon"
 :docker-uri "tcp://prognathodon:2375"}
```

### Restart Smithr

After updating `smithr.edn`, restart Smithr to connect to the new host.

## Step 6: Verify

Check the dashboard at http://localhost:7070 — the new host should appear
with its containers. Or via API:

```bash
curl http://localhost:7070/api/hosts
curl http://localhost:7070/api/resources
```

## SELinux (Fedora/RHEL)

All Docker volume mounts must include the `:z` suffix for SELinux
relabelling. This is already handled in the compose files. If you
add custom volumes, always use `:z`:

```yaml
volumes:
  - ./my-script.sh:/my-script.sh:z    # :z is required on Fedora
```

## iOS Physical Device Support (RSD tunnels)

Hosts with USB-connected iPhones (iOS 17+) need RSD tunnels for developer
service access (app install, XCTest, process control). Smithr uses
[py_ios_rsd_tunnel](https://github.com/dryark/py_ios_rsd_tunnel) to create
tunnels **on demand** — no always-on daemon needed.

### Prerequisites

```bash
pip3 install --user pymobiledevice3    # for developer service clients
cd /tmp && git clone https://github.com/JulesGosnell/py_ios_rsd_tunnel.git
pip3 install --user -r /tmp/py_ios_rsd_tunnel/requirements.txt
```

### Build the SUID tunnel helper

The tunnel needs `CAP_NET_ADMIN` to create TUN interfaces. The
`smithr-tunnel` wrapper grants just that one capability — Python runs
as your user, not root.

```bash
cd ~/src/smithr
gcc -Wall -O2 -o bin/smithr-tunnel bin/smithr-tunnel.c
sudo chown root:root bin/smithr-tunnel
sudo chmod u+s bin/smithr-tunnel
```

### Verify

```bash
# Start a tunnel (no sudo needed)
cd /tmp/py_ios_rsd_tunnel
~/src/smithr/bin/smithr-tunnel tunnel -u <UDID>
# Should print: { "ipv6": "<addr>", "port": <port> }

# In another terminal, test developer services through the tunnel
python3 -m pymobiledevice3 developer dvt ls --rsd <addr> <port> /
```

Type `stop` or Ctrl+C to close the tunnel.

### Migrating from pymobiledevice3 tunneld

If you previously ran the always-on tunneld daemon:

```bash
# Stop the daemon
sudo systemctl disable --now pymobiledevice3-tunneld 2>/dev/null
# Or if started manually:
sudo kill $(pgrep -f 'pymobiledevice3 remote tunneld')
```

### Notes

- Tunnels must run on the same host as the USB-connected devices. Cross-host
  RSD forwarding does not work (Remote XPC uses dynamic ports after handshake).
- The `smithr-tunnel` binary uses SUID + ambient capabilities: it starts as
  root, immediately drops to your real user, and grants only `CAP_NET_ADMIN`.
  Python runs as you, not root.

## Per-Host Notes

### megalodon (primary)

- Smithr runs here
- Docker socket: `unix:///var/run/docker.sock`
- IPs: Android 10.21.0.30, macOS 10.21.0.40

### prognathodon (secondary)

- Connected via SSH tunnel: `ssh -fNL 2375:/var/run/docker.sock prognathodon`
- Docker URI in smithr.edn: `tcp://localhost:2375`
- macOS image available via NFS at `/srv/shared/images/smithr-sonoma.img`
- Docker images pre-pulled: `budtmo/docker-android:emulator_9.0`, `sickcodes/docker-osx:latest`
