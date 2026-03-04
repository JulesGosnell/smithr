# Copyright 2026 Jules Gosnell
# SPDX-License-Identifier: Apache-2.0

FROM eclipse-temurin:21-jre-alpine

RUN apk add --no-cache \
    openssh-server openssh-client bash socat android-tools rsync python3 \
  && ssh-keygen -A \
  && mkdir -p /root/.ssh \
  && echo 'PermitRootLogin yes' >> /etc/ssh/sshd_config \
  && echo 'PubkeyAuthentication yes' >> /etc/ssh/sshd_config

# Maestro CLI — baked from /srv/shared/images at build time.
# The tarball contains maestro/bin/maestro + maestro/lib/*.jar.
# Patched smithr jars are overlaid on top (driver-port fix for concurrent instances).
#
# Build via the build script (copies jars into context automatically):
#   layers/images/smithr-phone-worker/build.sh
#
# Or manually:
#   cp /srv/shared/images/maestro-2.2.0-smithr.tar.gz layers/images/smithr-phone-worker/maestro.tar.gz
#   cp /srv/shared/images/maestro-cli-2.1.0-smithr.jar layers/images/smithr-phone-worker/maestro-cli-smithr.jar
#   docker build -t smithr-phone-worker:latest layers/images/smithr-phone-worker/
COPY maestro.tar.gz /tmp/maestro.tar.gz
RUN tar xzf /tmp/maestro.tar.gz -C /opt \
  && rm /tmp/maestro.tar.gz \
  && chmod +x /opt/maestro/bin/maestro

# Overlay patched smithr jars (driver-port fix for concurrent instances).
# The 2.2.0 tarball ships vanilla jars; these smithr-patched versions must
# replace them so selectPort() reads -Dmaestro.driver.port.
# Also overlay ios-driver to fix LocalSimulatorUtils version mismatch.
COPY maestro-cli-smithr.jar maestro-client-smithr.jar maestro-utils-smithr.jar maestro-ios-driver-smithr.jar /tmp/
RUN for JAR in cli client utils ios-driver; do \
      ORIG=$(ls /opt/maestro/lib/maestro-${JAR}*.jar 2>/dev/null | head -1); \
      if [ -n "$ORIG" ]; then \
        cp "/tmp/maestro-${JAR}-smithr.jar" "$ORIG"; \
      else \
        cp "/tmp/maestro-${JAR}-smithr.jar" "/opt/maestro/lib/maestro-${JAR}-smithr.jar"; \
      fi; \
    done \
  && rm /tmp/maestro-*-smithr.jar

ENV PATH="/opt/maestro/bin:${PATH}"
ENV MAESTRO_CLI_NO_ANALYTICS=true
ENV MAESTRO_CLI_ANALYSIS_NOTIFICATION_DISABLED=true

COPY entrypoint.sh /entrypoint.sh
RUN chmod +x /entrypoint.sh

EXPOSE 22
HEALTHCHECK --interval=10s --timeout=3s --retries=3 \
  CMD nc -z localhost 22 || exit 1

ENTRYPOINT ["/entrypoint.sh"]
