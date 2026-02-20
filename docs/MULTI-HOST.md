# Multi-Host Setup

Smithr runs across multiple Docker hosts for redundancy and capacity.
Each host runs its own Smithr instance that manages local containers
and watches remote hosts via Docker's native TLS.

## Architecture

```
┌─────────────────────┐         TLS (2376)        ┌─────────────────────┐
│     megalodon        │◄────────────────────────►│    prognathodon      │
│                      │                           │                      │
│  Smithr (7070)       │                           │  Smithr (7070)       │
│  ├── local Docker    │                           │  ├── local Docker    │
│  │   (unix socket)   │                           │  │   (unix socket)   │
│  └── watches prog    │                           │  └── watches meg     │
│      (TLS :2376)     │                           │      (TLS :2376)     │
│                      │                           │                      │
│  Containers:         │                           │  Containers:         │
│  - android-fe        │                           │  - (future)          │
│  - android-build-fe  │                           │                      │
│  - ios-fe            │                           │                      │
│  - xcode-fe          │                           │                      │
└─────────────────────┘                           └─────────────────────┘
```

Each Smithr instance:
- **Manages** containers on its own host (leases, GC, tunnels)
- **Watches** containers on remote hosts (read-only visibility via Docker events)
- **Never GCs** remote containers — each host cleans up its own

## How It Works

Docker daemons on each host listen on both:
- `unix:///var/run/docker.sock` — local access (default)
- `tcp://0.0.0.0:2376` — remote access with mutual TLS

Smithr connects to local Docker via the unix socket (fast, no auth needed),
and to remote Docker via TCP+TLS (authenticated, no SSH tunnel required).

## Setup

### 1. Generate TLS Certificates

```bash
cd smithr/tls
bash generate-certs.sh
```

This creates a private CA and generates:
- Server certs for each host (with SANs for hostname + IP)
- Client cert for Smithr
- Valid for 10 years

### 2. Deploy Certs and Configure Docker

```bash
sudo bash deploy-certs.sh
```

This script (run once from megalodon):
1. Copies certs to `/etc/smithr/tls/` on both hosts
2. Configures Docker daemon (`daemon.json`) with TLS settings
3. Creates systemd override so `daemon.json` hosts are used
4. Restarts Docker on both hosts

### 3. Verify Docker TLS

```bash
# From megalodon, reach prognathodon:
docker --tlsverify \
  --tlscacert=/etc/smithr/tls/ca.pem \
  --tlscert=/etc/smithr/tls/cert.pem \
  --tlskey=/etc/smithr/tls/key.pem \
  -H tcp://prognathodon:2376 info

# From prognathodon, reach megalodon:
docker --tlsverify \
  --tlscacert=/etc/smithr/tls/ca.pem \
  --tlscert=/etc/smithr/tls/cert.pem \
  --tlskey=/etc/smithr/tls/key.pem \
  -H tcp://megalodon:2376 info
```

### 4. Create Host Config Symlink

Each host needs a symlink pointing to its config:

```bash
# On megalodon:
ln -sf config/megalodon.edn resources/smithr.edn

# On prognathodon:
ln -sf config/prognathodon.edn resources/smithr.edn
```

### 5. Start Smithr

```bash
clojure -M:run
```

Smithr reads the host-specific config, connects to local Docker via unix socket,
and connects to remote hosts via TLS. No manual tunnel setup.

## Configuration

Host configs live in `resources/config/<hostname>.edn`.
Each host symlinks `resources/smithr.edn` to its own config.

```edn
{:server {:port 7070 :host "0.0.0.0"}

 :hosts [;; Local host — always unix socket
         {:label "megalodon"}
         ;; Remote host — TLS connection
         {:label "prognathodon"
          :host-address "prognathodon"
          :tls {:cert-path "/etc/smithr/tls"}}]

 :gc {:interval-seconds 30
      :own-host "megalodon"}   ;; Only GC local containers

 :compose {:project "smithr" :network "smithr-network"}
 :tunnel {:key-path "/ssh-key/macos-ssh-key" :base-port 17000}}
```

### Remote Host Connection Modes

The `:hosts` config supports three ways to connect to remote Docker:

| Config | How it works |
|--------|-------------|
| `:tls {:cert-path "..."}` | Direct TCP+TLS to port 2376 (recommended) |
| `:host-address "host"` only | Auto-creates SSH tunnel (needs passwordless SSH) |
| `:docker-uri "tcp://..."` | Uses URI directly (you manage connectivity) |

## Adding a New Host

1. Generate a server cert (add to `generate-certs.sh` or create manually)
2. Deploy certs and configure Docker daemon on the new host
3. Create `resources/config/<hostname>.edn`
4. Add the new host to existing hosts' config files
5. Symlink `smithr.edn` on the new host
6. Create `smithr-network`: `docker network create --subnet 10.21.0.0/16 smithr-network`
7. Start Smithr

## Firewall

Ensure port **2376** (Docker TLS) is open between hosts.
Port **7070** (Smithr API) should also be accessible if you want
cross-host API access or a unified dashboard.

## Cert Directory Layout

After deployment, `/etc/smithr/tls/` on each host contains:

```
/etc/smithr/tls/
├── ca.pem           # CA certificate (same on all hosts)
├── server-cert.pem  # This host's server certificate
├── server-key.pem   # This host's server private key
├── cert.pem         # Smithr client certificate
└── key.pem          # Smithr client private key
```

docker-java expects `ca.pem`, `cert.pem`, and `key.pem` in the cert-path directory.
