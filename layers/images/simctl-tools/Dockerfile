FROM alpine:3.21

RUN apk add --no-cache openssh-client curl bash openjdk17-jre-headless

COPY entrypoint.sh /entrypoint.sh
COPY maestro-sidecar.sh /maestro-sidecar.sh
COPY run-test.sh /run-test.sh
RUN chmod +x /entrypoint.sh /maestro-sidecar.sh /run-test.sh

COPY scripts/ /opt/scripts/

ENTRYPOINT ["/entrypoint.sh"]
