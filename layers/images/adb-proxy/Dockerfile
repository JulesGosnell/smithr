# Phone bridge — wraps a physical phone as a container with ADB on port 5555.
#
# Bridges host-side ADB forward into the container via socat.
# From the outside, indistinguishable from an emulated Android phone.
#
# Env: BRIDGE_HOST, BRIDGE_PORT, SERIAL
FROM alpine:3.21
RUN apk add --no-cache socat android-tools bash
COPY entrypoint.sh /entrypoint.sh
COPY healthcheck.sh /healthcheck.sh
RUN chmod +x /entrypoint.sh /healthcheck.sh
EXPOSE 5555
HEALTHCHECK --interval=10s --timeout=5s --retries=6 --start-period=15s \
  CMD /healthcheck.sh
ENTRYPOINT ["/entrypoint.sh"]
