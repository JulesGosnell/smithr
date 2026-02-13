# Smithr Research: Existing Tools and Reuse Opportunities

> Research conducted 2026-02-13 to avoid reinventing the wheel.

## Summary

After surveying the landscape, the conclusion is: **there is no single tool that does what Smithr does**. However, there are several excellent tools we can either use directly or learn from. The most actionable findings are:

1. **Google's Cuttlefish** — a strong alternative to `budtmo/docker-android` for Android emulation in containers, designed specifically for CI
2. **Marathon** — a mature distributed test runner that handles device pooling, sharding, and flakiness management for both Android and iOS
3. **Android Emulator Snapshots** — warm boot from snapshots is the industry standard for fast emulator startup in CI
4. **Dagger.io** — a programmable CI engine that could replace our Bash scripts with type-safe, cacheable pipeline code

## Tool-by-Tool Analysis

### 1. DeviceFarmer/STF (Smartphone Test Farm)

**What:** Web-based remote management of physical Android devices. Control real phones from your browser.

**Status:** Active. DeviceFarmer org maintains the fork (original openstf/stf is archived). Last activity Dec 2025.

**Relevance: LOW** — STF is designed for *physical device* farms, not emulator pools. It provides remote screen mirroring and input via ADB for real phones. Since Smithr uses Docker-based emulators and macOS VMs (not physical devices), STF's core value prop doesn't apply.

**Verdict:** Skip. Different use case.

### 2. budtmo/docker-android (Current choice in Arthur)

**What:** Docker images with Android emulator + noVNC + video recording. Drop-in emulator containers.

**Status:** Active, popular (~1k+ stars). Images available for Android 9-14.

**Relevance: HIGH** — Already proven in Arthur. Simple, reliable, well-documented.

**Limitations:**
- No built-in pool management or warm-boot support
- Each container boots from cold (3-4 min)
- No snapshot/restore capability
- Heavy images (~2-3GB)

**Verdict:** Keep using for now. Consider switching to Cuttlefish or Google's containers for warm-boot capability.

### 3. Google android-emulator-container-scripts

**What:** Official Google scripts for running Android emulators in Docker containers. Pre-built images available.

**Status:** Maintained by Google. Pre-built containers at `us-docker.pkg.dev/android-emulator-268719/images/`.

**Relevance: HIGH** — These are the official Google-blessed way to run emulators in CI. Key advantages:
- **Pre-built images** — no need to build your own
- **Designed for CI** — lightweight, headless-capable
- Supports KVM acceleration
- Available for API levels 28+

**Verdict:** Evaluate as replacement for `budtmo/docker-android`. The official Google images may be leaner and better supported.

### 4. Google Cuttlefish

**What:** A virtual Android device that runs natively on Linux x86/arm64 without a graphical emulator. Designed for AOSP development and CI testing.

**Status:** Active, part of AOSP. Runs in Docker/Kubernetes.

**Relevance: MEDIUM-HIGH** — Cuttlefish is the next evolution beyond the standard emulator:
- **Container-native** — designed from the ground up for Docker/K8s
- **Multiple instances** — can run N Cuttlefish instances per host
- **No GPU required** — uses software rendering (virtio-gpu)
- **Kubernetes support** — can be orchestrated as pods
- **Full Android fidelity** — runs the actual Android framework, not a simulation

**Limitations:**
- More complex setup than budtmo/docker-android
- Focused on AOSP testing, may need adaptation for app testing
- Less documentation for app-level E2E testing (Maestro/Appium)

**Verdict:** Research further. If Cuttlefish works well with Maestro, it could be a better foundation than budtmo for the phone pool. The Kubernetes article shows someone running Cuttlefish at scale.

### 5. Marathon (MarathonLabs)

**What:** Cross-platform test runner for Android and iOS. Handles test distribution, parallelism, flakiness, retries, device pooling, and test sharding.

**Status:** Active, v0.9.x. Open-source runner + commercial cloud offering.

**Relevance: HIGH** — Marathon is exactly the kind of test orchestrator we need:
- **Device pools** — manages pools of devices (real or emulated)
- **Test sharding** — distributes tests across devices
- **Flakiness management** — adaptive retries, quarantine flaky tests
- **Both platforms** — Android (ADB) and iOS (xcrun simctl)
- **Sorting & batching** — optimises test execution order

**Key insight:** Marathon handles the *test distribution* layer (which test runs on which device), while Smithr handles the *infrastructure* layer (providing warm phones). They complement each other perfectly.

**Integration idea:** Smithr provides the warm phone pool. Marathon consumes it as a device pool and distributes tests across it.

**Verdict:** Strong candidate for the test runner layer. Could replace our custom test dispatch logic.

### 6. Android Emulator Warm Boot / Snapshots

**What:** An emulator technique where you save a snapshot of a fully booted, ready-to-test emulator, then restore from that snapshot instead of cold-booting.

**Status:** Built into Android Emulator (snapshot support). Industry standard practice.

**Relevance: CRITICAL** — This is the key enabler for Phone as a Service:
- **Cold boot:** 3-4 minutes (boot Android OS from scratch)
- **Warm boot from snapshot:** 5-15 seconds (restore saved state)

**How it works:**
1. Boot the emulator once (cold boot, ~4 min)
2. Wait for full boot, home screen ready
3. Save snapshot: `adb emu avd snapshot save clean-state`
4. For each test: restore snapshot → install app → run test → discard state

**Verdict:** MUST IMPLEMENT. This is the foundation of fast phone acquisition. Our warm pool should maintain snapshot-ready emulators.

### 7. Dagger.io

**What:** A programmable CI/CD engine. Write pipelines in Go/Python/TypeScript/etc., runs everything in containers with automatic caching.

**Status:** Active, v0.15+. By Docker co-founder Solomon Hykes. Growing adoption.

**Relevance: MEDIUM** — Dagger could replace our Bash script layer:
- **Type-safe pipelines** — no more Bash fragility
- **Container-native** — every step runs in a container
- **Caching** — automatic layer caching across runs
- **Portable** — same pipeline runs locally and in CI
- **SDK in 8 languages** — including TypeScript (matches Arthur's stack)

**Trade-offs:**
- Adds a dependency (Dagger Engine)
- Learning curve for the team
- May be overkill for 2 nodes
- Our Bash scripts are already working

**Verdict:** Interesting for v2. For now, Bash scripts are simpler and the team already understands them. But worth evaluating for the future, especially if pipelines get more complex.

### 8. Testcontainers

**What:** Library for writing tests that use Docker containers for dependencies (databases, browsers, etc.).

**Status:** Very active. Multi-language (Java, Node.js, Go, Python, .NET, Rust).

**Relevance: LOW for Smithr** — Testcontainers is a test-code library, not infrastructure tooling. It's for spinning up containers *inside* test code (e.g., a test that needs a fresh Postgres). Our use case is infrastructure-level container orchestration.

**Verdict:** Not applicable to Smithr's core mission. Could be useful in Arthur's unit tests.

## Recommendations

### Immediate Actions (v0.1)

1. **Keep budtmo/docker-android** — it works, it's proven
2. **Implement emulator snapshots** — the single biggest speed win
3. **Keep Bash scripts** — simple, understandable, maintainable
4. **Evaluate Marathon** — could replace our custom test dispatch

### Near-term (v0.2)

5. **Evaluate Google's pre-built emulator containers** — potentially leaner than budtmo
6. **Evaluate Cuttlefish** — if it works with Maestro, it's the better long-term choice
7. **Integrate Marathon** — for test distribution and flakiness management

### Future (v1.0)

8. **Consider Dagger** — if pipeline complexity grows
9. **Consider Cuttlefish + Kubernetes** — if scaling beyond 2 nodes

## Additional Findings (Deep Research)

### Facebook idb (iOS Development Bridge)
`facebook/idb` (~4.5k stars) — gRPC-based interface to `simctl` for automating iOS Simulators. More reliable than raw `xcrun simctl`. Should be installed inside macOS VMs for programmatic simulator management.

### DeviceFarmer/adbkit
Node.js ADB client library (~3k stars). Useful if we ever need programmatic ADB beyond shell commands.

### Industry Warm Pool Patterns
The universal pattern across Google, Meta, and Netflix:
1. Pre-boot N emulators on each host
2. Track allocation state in a shared store (NFS file at our scale, Redis/etcd at larger scale)
3. Lease with TTL to handle crashes/timeouts
4. **Clean between uses (uninstall app, clear data) rather than restart the container**
5. Periodic full restart (e.g. nightly) to prevent state drift

This validates Smithr's architecture exactly. The "cleanup not restart" pattern is key — after a test, run `adb shell pm clear` / `adb uninstall` for Android, `xcrun simctl erase` for iOS. Don't destroy the container.

### Docker Checkpoint/Restore (CRIU)
Experimental Docker feature that freezes a running container to disk and restores it later. True instant restore. Worth monitoring but not production-ready.

### Key Gap Confirmed
**Smithr fills a genuine gap in the ecosystem.** There is no open-source tool that provides warm pool management for containerised Android emulators AND macOS VMs with iOS simulators, with a simple allocation/release API, integrated with CI. The closest commercial alternatives are Corellium ($100k+/year), BrowserStack, and AWS Device Farm.

## Sources

- [DeviceFarmer/STF](https://github.com/DeviceFarmer)
- [budtmo/docker-android](https://github.com/budtmo/docker-android)
- [Google android-emulator-container-scripts](https://github.com/google/android-emulator-container-scripts)
- [Google Cuttlefish](https://source.android.com/docs/devices/cuttlefish)
- [Running Android on Kubernetes with Cuttlefish](https://realz.medium.com/running-android-on-kubernetes-be73b940833f)
- [MarathonLabs/marathon](https://github.com/MarathonLabs/marathon)
- [Marathon docs](https://docs.marathonlabs.io/)
- [Android Emulator in CI (Google)](https://medium.com/androiddevelopers/android-emulator-in-a-ci-environment-dd65f63cdcd)
- [Continuous Testing with Android Emulator Containers](https://medium.com/androiddevelopers/continuous-testing-with-android-emulator-containers-95d89a3ea270)
- [Dagger.io](https://dagger.io/)
- [5 strategies for Android emulator stability](https://www.qawolf.com/blog/5-strategies-to-address-android-emulator-instability-during-automated-testing)
