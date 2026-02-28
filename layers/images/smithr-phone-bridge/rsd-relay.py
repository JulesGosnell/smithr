#!/usr/bin/env python3
"""Transparent TCP relay for iOS RSD tunnel services.

iptables REDIRECT funnels all incoming connections in a port range to a
single listening port.  This proxy reads SO_ORIGINAL_DST to recover the
real destination port and relays to the RSD tunnel's IPv6 TUN address
on that port.

Usage: rsd-relay.py <listen-port> <target-ipv6>
"""
import asyncio, socket, struct, sys, os

SO_ORIGINAL_DST = 80  # linux/netfilter constant


async def relay(src, dst):
    try:
        while data := await src.read(65536):
            dst.write(data)
            await dst.drain()
    except (ConnectionResetError, BrokenPipeError, OSError):
        pass
    finally:
        dst.close()


async def handle(reader, writer, target_ipv6):
    sock = writer.get_extra_info("socket")
    buf = sock.getsockopt(socket.SOL_IP, SO_ORIGINAL_DST, 16)
    port = struct.unpack(">H", buf[2:4])[0]
    try:
        remote_r, remote_w = await asyncio.wait_for(
            asyncio.open_connection(target_ipv6, port, family=socket.AF_INET6),
            timeout=5,
        )
    except Exception:
        writer.close()
        return
    await asyncio.gather(
        relay(reader, remote_w), relay(remote_r, writer), return_exceptions=True
    )


async def main():
    listen_port = int(sys.argv[1])
    target = sys.argv[2]
    server = await asyncio.start_server(
        lambda r, w: handle(r, w, target), "0.0.0.0", listen_port
    )
    # Write PID for healthcheck
    with open("/tmp/rsd-relay.pid", "w") as f:
        f.write(str(os.getpid()))
    async with server:
        await server.serve_forever()


if __name__ == "__main__":
    asyncio.run(main())
