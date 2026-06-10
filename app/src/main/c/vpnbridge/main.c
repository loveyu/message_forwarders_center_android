/*
 * VPN Bridge wrapper — launches hev-socks5-tunnel with a TUN fd
 * received via Unix domain socket (SCM_RIGHTS).
 *
 * Usage: vpnbridge --control-socket NAME --config PATH
 */

#include <stdio.h>
#include <stdlib.h>
#include <string.h>
#include <signal.h>
#include <unistd.h>
#include <sys/socket.h>
#include <linux/un.h>
#include <errno.h>

#include "hev-main.h"

static volatile int running = 1;

static void sigterm_handler(int signum)
{
    (void)signum;
    running = 0;
    hev_socks5_tunnel_quit();
}

static int receive_tun_fd(const char *socket_name)
{
    int fd;
    struct sockaddr_un addr;
    struct msghdr msg;
    struct iovec iov;
    char buf[1] = {0};
    char cmsgbuf[CMSG_SPACE(sizeof(int))];

    fd = socket(AF_UNIX, SOCK_STREAM, 0);
    if (fd < 0) {
        fprintf(stderr, "socket: %s\n", strerror(errno));
        return -1;
    }

    memset(&addr, 0, sizeof(addr));
    addr.sun_family = AF_UNIX;
    addr.sun_path[0] = '\0';
    strncpy(addr.sun_path + 1, socket_name, sizeof(addr.sun_path) - 2);

    socklen_t addr_len =
        offsetof(struct sockaddr_un, sun_path) + 1 + strlen(socket_name);
    if (connect(fd, (struct sockaddr *)&addr, addr_len) < 0) {
        fprintf(stderr, "connect: %s\n", strerror(errno));
        close(fd);
        return -1;
    }

    memset(&msg, 0, sizeof(msg));
    iov.iov_base = buf;
    iov.iov_len = 1;
    msg.msg_iov = &iov;
    msg.msg_iovlen = 1;
    msg.msg_control = cmsgbuf;
    msg.msg_controllen = sizeof(cmsgbuf);

    if (recvmsg(fd, &msg, 0) < 0) {
        fprintf(stderr, "recvmsg: %s\n", strerror(errno));
        close(fd);
        return -1;
    }

    struct cmsghdr *cmsg = CMSG_FIRSTHDR(&msg);
    if (!cmsg || cmsg->cmsg_level != SOL_SOCKET || cmsg->cmsg_type != SCM_RIGHTS) {
        fprintf(stderr, "no fd received\n");
        close(fd);
        return -1;
    }

    int tun_fd;
    memcpy(&tun_fd, CMSG_DATA(cmsg), sizeof(tun_fd));
    close(fd);
    return tun_fd;
}

int main(int argc, char *argv[])
{
    const char *control_socket = NULL;
    const char *config_path = NULL;

    for (int i = 1; i < argc; i++) {
        if (strcmp(argv[i], "--control-socket") == 0 && i + 1 < argc) {
            control_socket = argv[++i];
        } else if (strcmp(argv[i], "--config") == 0 && i + 1 < argc) {
            config_path = argv[++i];
        }
    }

    if (!control_socket || !config_path) {
        fprintf(stderr, "Usage: %s --control-socket NAME --config PATH\n", argv[0]);
        return 1;
    }

    int tun_fd = receive_tun_fd(control_socket);
    if (tun_fd < 0) {
        fprintf(stderr, "Failed to receive TUN fd\n");
        return 2;
    }

    signal(SIGTERM, sigterm_handler);
    signal(SIGPIPE, SIG_IGN);

    int res = hev_socks5_tunnel_main(config_path, tun_fd);

    close(tun_fd);
    return res < 0 ? 3 : 0;
}
