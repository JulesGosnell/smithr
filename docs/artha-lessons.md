# Lessons from Artha for Smithr

Smithr was spun out of the Artha project to provide composable Docker services for mobile development. These are the key issues from Artha that inform Smithr's design and roadmap, distilled into actionable takeaways.

---

## #509 - Composable Docker Services for Mobile Dev (Genesis Issue)

**Takeaway:** Smithr needs to provide Metro, iOS, Android, sidecars, and DinD as independently composable Docker services. Each service should be a self-contained unit that can be mixed and matched.

**Smithr should implement:** A service composition layer where each concern (bundler, emulator, build tools, sidecars) is a discrete Docker service with well-defined interfaces. Users pick the services they need; Smithr wires them together.

## #507 - Karl as Pop-Up Self-Contained Service

**Takeaway:** A "submit prompt, get PR back" pattern requires spinning up a full dev environment, doing work, and tearing it down. This demands a job queue for async tasks and ephemeral sandbox lifecycle management.

**Smithr should implement:** A job queue pattern where each task spins up a disposable sandbox with all required services, executes the work, produces an artifact (e.g., a PR), and tears down cleanly. The sandbox lifecycle must be fully automated with no manual intervention.

## #480 - Idempotent App Installation

**Takeaway:** Sandbox setup should automatically install APKs on emulators without requiring a separate manual `install` step. The environment should be ready to use the moment it comes up.

**Smithr should implement:** An idempotent setup phase that detects whether the APK is already installed and installs it if not. The sandbox `up` command should leave the emulator in a fully ready state with the app installed and launchable.

## #449 - DinD Volume Management

**Takeaway:** Deleting DinD volumes on `sandbox down` forces re-pulling all Docker images on next startup, which is slow and wasteful. Volumes should persist across sandbox lifecycles.

**Smithr should implement:** Persist DinD data volumes across sandbox stop/start cycles by default. Provide an explicit `--clean` or `--purge` flag for when users actually want a fresh start. Consider a shared registry mirror (see #371) as a complementary approach.

## #441 - Sandbox Garbage Collection

**Takeaway:** Persistent DinD volumes cause container name conflicts when sandboxes are restarted. Stale state accumulates and causes subtle failures.

**Smithr should implement:** A garbage collection mechanism that reconciles persistent volumes with running sandboxes. On startup, detect and resolve container name conflicts (either by removing stale containers or by using unique naming). Provide a `gc` command for manual cleanup.

## #434 - macOS as the Sandbox (Not Inside It)

**Takeaway:** Nested virtualization for iOS (Linux container, DinD, Docker-OSX, QEMU, macOS, Simulator) is brittle and slow. The sandbox itself should be a macOS VM, giving native iOS Simulator access without nesting.

**Smithr should implement:** For iOS workflows, treat macOS as a first-class sandbox host rather than nesting macOS inside Linux containers. This means supporting macOS VMs (e.g., via Tart or Anka) as sandbox targets, with the iOS Simulator running natively. Reserve the Linux/Docker model for Android-only workflows.

## #391 - Shared pnpm Store Volume

**Takeaway:** Each sandbox re-downloading JS dependencies is wasteful. A shared Docker volume for the pnpm store eliminates redundant downloads across sandboxes.

**Smithr should implement:** Mount a shared, named Docker volume for the pnpm store (and potentially other package manager caches like Gradle, CocoaPods) across all sandboxes. This volume should be created once and reused. Document the trade-off: shared volumes speed up setup but can cause issues if two sandboxes write conflicting versions simultaneously.

## #388 - virtio-9p vs Rsync for iOS Builds

**Takeaway:** Volume mounts across sandbox/host boundaries are fragile, especially with SELinux (`:Z` suffix for private labels). Rsync is more reliable than virtio-9p for moving build artifacts.

**Smithr should implement:** Default to rsync for transferring build artifacts between sandbox and host. If using bind mounts, apply the correct SELinux labels (`:Z` for private, `:z` for shared). Provide a configuration option to choose the file transfer strategy, with rsync as the recommended default.

## #371 - Registry Mirror for DinD

**Takeaway:** A caching registry proxy on the host allows DinD instances to pull images from a local cache instead of hitting the network. First pull is cached; subsequent sandboxes benefit immediately.

**Smithr should implement:** Ship a registry mirror service (e.g., based on `distribution/distribution` in pull-through cache mode) that runs on the host. Configure all DinD instances with `--registry-mirror` pointing to this cache. This complements the volume persistence from #449 and dramatically reduces cold-start time for new sandboxes.

## #135 - Audit Trail Consistency

**Takeaway:** Versioned entities should consistently require `createdBy` columns. Inconsistent audit metadata makes it hard to trace who changed what.

**Smithr should implement:** If Smithr introduces any persistent data model (sandbox configs, job records, build logs), enforce a consistent schema with `createdBy`, `createdAt`, `updatedBy`, and `updatedAt` on all mutable entities from day one. This is a low-cost discipline that pays off in debugging and compliance.
