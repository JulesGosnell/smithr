/*
 * smithr-tunnel — run ios_rsd_tunnel with CAP_NET_ADMIN
 *
 * SUID-root wrapper that drops to the real user but grants CAP_NET_ADMIN
 * as an ambient capability. Python runs as you, not root — only the one
 * capability needed for TUN interface creation is elevated.
 *
 * Build:   gcc -o bin/smithr-tunnel bin/smithr-tunnel.c
 * Install: sudo chown root:root bin/smithr-tunnel
 *          sudo chmod u+s bin/smithr-tunnel
 * Usage:   bin/smithr-tunnel tunnel -u <UDID>
 */
#define _GNU_SOURCE
#include <stdio.h>
#include <stdlib.h>
#include <unistd.h>
#include <sys/prctl.h>
#include <sys/syscall.h>
#include <linux/capability.h>

#define CAP_NET_ADMIN 12

int main(int argc, char *argv[]) {
    uid_t uid = getuid();
    gid_t gid = getgid();

    /* Keep capabilities across setuid */
    if (prctl(PR_SET_KEEPCAPS, 1) < 0) {
        perror("PR_SET_KEEPCAPS");
        return 1;
    }

    /* Drop to real user */
    if (setgid(gid) < 0 || setuid(uid) < 0) {
        perror("setuid/setgid");
        return 1;
    }

    /* Grant only CAP_NET_ADMIN in permitted + inheritable */
    struct __user_cap_header_struct hdr = {
        .version = _LINUX_CAPABILITY_VERSION_3, .pid = 0,
    };
    struct __user_cap_data_struct data[2] = {};
    data[0].effective   = (1u << CAP_NET_ADMIN);
    data[0].permitted   = (1u << CAP_NET_ADMIN);
    data[0].inheritable = (1u << CAP_NET_ADMIN);
    if (syscall(SYS_capset, &hdr, data) < 0) {
        perror("capset");
        return 1;
    }

    /* Raise as ambient so it survives exec into python3 */
    if (prctl(PR_CAP_AMBIENT, PR_CAP_AMBIENT_RAISE, CAP_NET_ADMIN, 0, 0) < 0) {
        perror("PR_CAP_AMBIENT");
        return 1;
    }

    /* Sanitize linker env */
    unsetenv("LD_PRELOAD");
    unsetenv("LD_LIBRARY_PATH");

    /* exec: python3 -m ios_rsd_tunnel <args...> */
    char **av = malloc((argc + 3) * sizeof(char *));
    if (!av) { perror("malloc"); return 1; }
    av[0] = "/usr/bin/python3";
    av[1] = "-m";
    av[2] = "ios_rsd_tunnel";
    for (int i = 1; i < argc; i++) av[i + 2] = argv[i];
    av[argc + 2] = NULL;

    execv(av[0], av);
    perror("execv");
    return 1;
}
