FROM python:3.12-slim

RUN apt-get update && apt-get install -y --no-install-recommends \
      socat bash iproute2 iptables openssh-server \
      libusbmuxd-tools \
      openjdk-17-jre-headless \
    && rm -rf /var/lib/apt/lists/* \
    && pip install --no-cache-dir \
       asn1crypto==1.5.1 certbuilder==0.14.2 construct==2.10.70 \
       hyperframe==6.0.1 oscrypto==1.3.0 PyNaCl==1.5.0 qh3==1.0.4 \
       srptools==1.0.1 pytun-pmd3>=2.0.0 \
       pymobiledevice3==7.7.2

COPY entrypoint.sh /entrypoint.sh
COPY healthcheck.sh /healthcheck.sh
COPY rsd-relay.py /rsd-relay.py
COPY start-xctest.sh /start-xctest.sh
RUN chmod +x /entrypoint.sh /healthcheck.sh /start-xctest.sh

RUN mkdir -p /root/.ssh \
 && echo 'PermitRootLogin yes' >> /etc/ssh/sshd_config \
 && echo 'PubkeyAuthentication yes' >> /etc/ssh/sshd_config \
 && ssh-keygen -A

EXPOSE 22

HEALTHCHECK --interval=10s --timeout=5s --start-period=30s --retries=6 \
  CMD /healthcheck.sh

ENTRYPOINT ["/entrypoint.sh"]
