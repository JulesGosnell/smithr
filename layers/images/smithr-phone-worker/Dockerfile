# Copyright 2026 Jules Gosnell
# SPDX-License-Identifier: Apache-2.0

FROM eclipse-temurin:21-jre-alpine

RUN apk add --no-cache \
    openssh-server openssh-client bash socat android-tools rsync python3 \
  && ssh-keygen -A \
  && mkdir -p /root/.ssh \
  && sed -i 's/^AllowTcpForwarding no/AllowTcpForwarding yes/' /etc/ssh/sshd_config \
  && echo 'PermitRootLogin yes' >> /etc/ssh/sshd_config \
  && echo 'PubkeyAuthentication yes' >> /etc/ssh/sshd_config \
  && echo 'PermitUserEnvironment yes' >> /etc/ssh/sshd_config

# Maestro CLI — baked from /srv/shared/images at build time.
# The tarball contains maestro/bin/maestro + maestro/lib/*.jar.
# Uses vanilla 2.2.0 — no patched JARs needed in per-container workers
# (each container has its own port namespace, so selectPort() patch is unnecessary).
#
# Build via the build script:
#   layers/images/smithr-phone-worker/build.sh
COPY maestro.tar.gz /tmp/maestro.tar.gz
RUN tar xzf /tmp/maestro.tar.gz -C /opt \
  && rm /tmp/maestro.tar.gz \
  && chmod +x /opt/maestro/bin/maestro

ENV PATH="/opt/maestro/bin:${PATH}"
ENV MAESTRO_CLI_NO_ANALYTICS=true
ENV MAESTRO_CLI_ANALYSIS_NOTIFICATION_DISABLED=true

COPY entrypoint.sh /entrypoint.sh
RUN chmod +x /entrypoint.sh

EXPOSE 22
HEALTHCHECK --interval=10s --timeout=3s --retries=3 \
  CMD nc -z localhost 22 || exit 1

ENTRYPOINT ["/entrypoint.sh"]
