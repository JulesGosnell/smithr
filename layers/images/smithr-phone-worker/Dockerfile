FROM eclipse-temurin:21-jre-alpine

RUN apk add --no-cache \
    openssh-server bash socat android-tools rsync python3 \
  && ssh-keygen -A \
  && mkdir -p /root/.ssh \
  && echo 'PermitRootLogin yes' >> /etc/ssh/sshd_config \
  && echo 'PubkeyAuthentication yes' >> /etc/ssh/sshd_config

# Maestro CLI (pre-installed from shared image or built separately)
# Mount or COPY maestro into /root/.maestro at build time
ENV PATH="/root/.maestro/bin:${PATH}"

COPY entrypoint.sh /entrypoint.sh
RUN chmod +x /entrypoint.sh

EXPOSE 22
HEALTHCHECK --interval=10s --timeout=3s --retries=3 \
  CMD ssh -o BatchMode=yes -o ConnectTimeout=2 -o StrictHostKeyChecking=no localhost echo ok 2>/dev/null || exit 1

ENTRYPOINT ["/entrypoint.sh"]
