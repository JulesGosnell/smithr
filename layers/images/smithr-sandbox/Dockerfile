# Smithr Sandbox Image
#
# Headless dev sandbox for Claude Code workers (Karls).
# Extends the android-build image with gh CLI and claude-code.
#
# Build:
#   docker build -t smithr-sandbox:latest layers/images/smithr-sandbox/
#
# Everything from android-build is inherited:
#   Layer 1 (base): Fedora 43 + system packages + sshd
#   Layer 2 (tools): Java 21, Node.js 22, pnpm, Docker CLI, Android SDK
#   Layer 3 (e2e):   Playwright, Maestro 2.2.0, Gradle

FROM smithr-android-build:latest

# ---------------------------------------------------------------------------
# Layer 4: Sandbox-specific tools (gh CLI + Claude Code)
# ---------------------------------------------------------------------------

# GitHub CLI (dnf5 syntax for Fedora 43+)
RUN curl -fsSL https://cli.github.com/packages/rpm/gh-cli.repo \
        -o /etc/yum.repos.d/gh-cli.repo \
    && dnf install -y gh \
    && dnf clean all

# Claude Code (Anthropic CLI)
RUN npm install -g @anthropic-ai/claude-code

# Voice mode: audio libraries + voice-mode package
RUN dnf install -y \
    pulseaudio-utils alsa-utils alsa-plugins-pulseaudio \
    portaudio portaudio-devel libsndfile libsndfile-devel \
    python3-devel gcc alsa-lib-devel python3-pip \
    && dnf clean all \
    && pip3 install voice-mode

# Add smithr user to audio group
RUN usermod -aG audio smithr

# Export additional env vars in global profile
RUN echo 'export PATH="$PATH:$(npm root -g)/.bin"' >> /etc/profile.d/smithr-env.sh
