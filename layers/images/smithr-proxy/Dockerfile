FROM alpine:3.21

RUN apk add --no-cache socat curl jq bash

COPY entrypoint.sh /usr/local/bin/entrypoint.sh
COPY healthcheck.sh /usr/local/bin/healthcheck.sh
RUN chmod +x /usr/local/bin/entrypoint.sh /usr/local/bin/healthcheck.sh

HEALTHCHECK --interval=5s --timeout=3s --retries=5 --start-period=15s \
  CMD /usr/local/bin/healthcheck.sh

ENTRYPOINT ["/usr/local/bin/entrypoint.sh"]
