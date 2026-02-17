# Smithr

> The smith who forges the infrastructure.

A project-agnostic, containerised CI/testing/sandbox framework with **Phone as a Service**.

## What Smithr Does

Smithr provides reusable infrastructure for mobile app development teams:

- **Phone as a Service** — warm pools of Android emulators and iOS simulators. Acquire a phone in seconds, run your test, return it.
- **Shared Server Environments** — one database + API + TLS proxy shared across all test jobs. No redundant setup.
- **DinD-Isolated Sandboxes** — complete development environments for LLM agents or developers, fully isolated.
- **Composable CI Pipelines** — Docker Compose layers you mix and match. GitHub Actions templates included.

## Quick Start

```bash
# Check prerequisites
./bin/smithr doctor

# Start the shared server
./bin/smithr server start

# Warm up 4 Android phones
./bin/smithr phone warm --count 4

# Acquire a phone, run a test, unlease it
PHONE=$(./bin/smithr phone get --type pixel_7)
./bin/smithr test run training --device "$PHONE"
./bin/smithr phone unlease "$PHONE"
```

## Project Structure

```
smithr/
├── bin/                  CLI tools (smithr phone, smithr server, etc.)
├── layers/               Composable Docker Compose layers
├── docker/               Dockerfiles and shared config
├── templates/            GitHub Actions and phone pod templates
├── demo/                 Minimal demo app to exercise the infrastructure
└── docs/                 Architecture, research, and guides
```

## Configuration

Copy `smithr.yml.example` to your project as `smithr.yml` and customise.

## Documentation

- [Architecture](docs/ARCHITECTURE.md) — full system design
- [Research](docs/RESEARCH.md) — analysis of existing tools and reuse opportunities

## Naming

Smithr uses an Old Norse theme:

| Role | Name | Description |
|------|------|-------------|
| Project Owner | **Jarl** (Jules) | Technical leader |
| Infrastructure | **Smithr** | This project — the smith |
| Orchestrator | **Skald** | Keeper of history, CI supervisor |
| Business Advisor | **Volva** | Strategic guidance |
| Sandbox Workers | **Rune, Sif, Tyr, Vali** | Development environments |

## Requirements

- Linux (Fedora 43 recommended)
- Docker with Compose v2
- KVM (`/dev/kvm`) for emulator hardware virtualisation
- GPU (`/dev/dri`) for Android rendering (optional but recommended)
- 16+ GB RAM (64 GB recommended for phone pools)

## License

See [LICENSE](LICENSE).
