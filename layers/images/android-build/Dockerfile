# Copyright 2026 Jules Gosnell
# SPDX-License-Identifier: Apache-2.0

# Smithr Android Build Image
#
# Fedora 43 base (matches host) + Android SDK + Node.js + sshd
# for workspace build leases with user isolation.
#
# Two-layer build:
#   Layer 1 (base): Fedora 43 + system packages + sshd
#   Layer 2 (tools): Android SDK + Node.js + pnpm
#
# Build:
#   docker build -t smithr-android-build .
#
# Or build just the base for reuse:
#   docker build --target base -t smithr-android-build-base .

# ---------------------------------------------------------------------------
# Layer 1: Base OS + sshd + user management
# ---------------------------------------------------------------------------
FROM fedora:43 AS base

RUN dnf install -y \
    openssh-server \
    passwd \
    shadow-utils \
    git \
    curl \
    unzip \
    which \
    procps-ng \
    openssl \
    socat \
    && dnf clean all

# Generate host keys and configure sshd
RUN ssh-keygen -A \
    && sed -i 's/^#PermitRootLogin.*/PermitRootLogin no/' /etc/ssh/sshd_config \
    && sed -i 's/^#PasswordAuthentication.*/PasswordAuthentication no/' /etc/ssh/sshd_config \
    && sed -i 's/^#PubkeyAuthentication.*/PubkeyAuthentication yes/' /etc/ssh/sshd_config \
    && sed -i 's/^#AllowTcpForwarding.*/AllowTcpForwarding yes/' /etc/ssh/sshd_config \
    && sed -i 's/^#GatewayPorts.*/GatewayPorts yes/' /etc/ssh/sshd_config

# Create a service user for SSH access (like 'smithr' on macOS)
RUN useradd -m -s /bin/bash smithr \
    && mkdir -p /home/smithr/.ssh \
    && chmod 700 /home/smithr/.ssh

# Allow smithr to create/delete users via sudo (for workspace management)
RUN dnf install -y sudo && dnf clean all \
    && echo "smithr ALL=(ALL) NOPASSWD: ALL" >> /etc/sudoers.d/smithr

EXPOSE 22

# ---------------------------------------------------------------------------
# Layer 2: Android SDK + Node.js + pnpm
# ---------------------------------------------------------------------------
FROM base

# Java 21 JDK (Gradle requirement — Fedora 43 ships 21+)
RUN dnf install -y java-21-openjdk-devel && dnf clean all
ENV JAVA_HOME=/usr/lib/jvm/java-21-openjdk

# Node.js 22 + pnpm + Docker CLI + Docker Compose
RUN dnf install -y nodejs npm docker-cli docker-compose && dnf clean all \
    && npm install -g pnpm

# Android SDK command-line tools
ENV ANDROID_HOME=/opt/android-sdk
ENV PATH="${PATH}:${ANDROID_HOME}/cmdline-tools/bin:${ANDROID_HOME}/platform-tools"

ARG CMD_TOOLS_VERSION=11076708
RUN mkdir -p ${ANDROID_HOME}/cmdline-tools \
    && curl -o /tmp/cmdtools.zip https://dl.google.com/android/repository/commandlinetools-linux-${CMD_TOOLS_VERSION}_latest.zip \
    && unzip -q /tmp/cmdtools.zip -d ${ANDROID_HOME}/cmdline-tools \
    && mv ${ANDROID_HOME}/cmdline-tools/cmdline-tools ${ANDROID_HOME}/cmdline-tools/latest \
    && rm /tmp/cmdtools.zip

ENV PATH="${PATH}:${ANDROID_HOME}/cmdline-tools/latest/bin"

# Accept licenses and install SDK components
RUN yes | sdkmanager --sdk_root=${ANDROID_HOME} --licenses > /dev/null 2>&1 \
    && sdkmanager --sdk_root=${ANDROID_HOME} \
       "platform-tools" \
       "platforms;android-34" \
       "platforms;android-36" \
       "build-tools;34.0.0" \
       "build-tools;35.0.0" \
       "build-tools;36.0.0" \
       "ndk;27.1.12297006" \
       "cmake;3.22.1"

# Make SDK readable by all users (workspace users need access)
RUN chmod -R a+rX ${ANDROID_HOME}

# ---------------------------------------------------------------------------
# Layer 3: E2E test tooling (Playwright, Maestro)
# ---------------------------------------------------------------------------

# Playwright Chromium system deps (Fedora equivalents of Ubuntu's --with-deps)
RUN dnf install -y \
    nss nspr atk at-spi2-atk cups-libs libdrm mesa-libgbm pango \
    alsa-lib libXcomposite libXdamage libXrandr libXi libxkbcommon \
    libXfixes dbus-libs mesa-libEGL libxshmfence \
    && dnf clean all

# Playwright with Chromium browser — install to shared location for workspace users
ENV PLAYWRIGHT_BROWSERS_PATH=/opt/playwright
RUN npx playwright install chromium \
    && chmod -R a+rwX /opt/playwright

# Maestro mobile testing framework — direct install from GitHub release
# (avoids the curl installer which pulls in Linuxbrew and conflicts with
# workspace users' own Maestro installs)
ENV MAESTRO_HOME=/opt/maestro
ARG MAESTRO_VERSION=cli-2.1.0
RUN curl -fsSL "https://github.com/mobile-dev-inc/maestro/releases/download/${MAESTRO_VERSION}/maestro.zip" \
        -o /tmp/maestro.zip \
    && unzip -q /tmp/maestro.zip -d /opt \
    && rm /tmp/maestro.zip \
    && chmod -R a+rX /opt/maestro

# Gradle — pre-installed so workspace builds don't download on every cold start
ENV GRADLE_HOME=/opt/gradle
ARG GRADLE_VERSION=8.14.3
RUN curl -fsSL "https://services.gradle.org/distributions/gradle-${GRADLE_VERSION}-bin.zip" \
        -o /tmp/gradle.zip \
    && unzip -q /tmp/gradle.zip -d /opt \
    && mv /opt/gradle-${GRADLE_VERSION} /opt/gradle \
    && rm /tmp/gradle.zip \
    && chmod -R a+rX /opt/gradle \
    && ln -sf /opt/gradle/bin/gradle /usr/local/bin/gradle

# Export env vars in global profile (SSH sessions don't inherit Docker ENV)
RUN echo 'export ANDROID_HOME=/opt/android-sdk' >> /etc/profile.d/smithr-env.sh \
    && echo 'export JAVA_HOME=/usr/lib/jvm/java-21-openjdk' >> /etc/profile.d/smithr-env.sh \
    && echo 'export PLAYWRIGHT_BROWSERS_PATH=/opt/playwright' >> /etc/profile.d/smithr-env.sh \
    && echo 'export PATH="$PATH:$ANDROID_HOME/cmdline-tools/latest/bin:$ANDROID_HOME/cmdline-tools/bin:$ANDROID_HOME/platform-tools"' >> /etc/profile.d/smithr-env.sh \
    && echo 'export GRADLE_HOME=/opt/gradle' >> /etc/profile.d/smithr-env.sh \
    && echo 'export PATH="$PATH:/opt/gradle/bin:/opt/maestro/bin"' >> /etc/profile.d/smithr-env.sh

# Healthcheck: sshd port open
HEALTHCHECK --interval=5s --timeout=3s --retries=3 \
    CMD bash -c 'echo > /dev/tcp/localhost/22' || exit 1

# Entrypoint: fix SSH keys, fix Docker socket permissions, start sshd
CMD ["bash", "-c", "cp /tmp/authorized_keys /home/smithr/.ssh/authorized_keys && chown -R smithr:smithr /home/smithr/.ssh && chmod 700 /home/smithr/.ssh && chmod 600 /home/smithr/.ssh/authorized_keys && if [ -S /var/run/docker.sock ]; then chmod 666 /var/run/docker.sock; fi && exec /usr/sbin/sshd -D -e"]
