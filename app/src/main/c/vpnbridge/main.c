/*
 * VPN Bridge wrapper — launches hev-socks5-tunnel with a TUN fd
 * received via Unix domain socket (SCM_RIGHTS).
 *
 * After TUN fd transfer, the control socket is kept open for stats
 * queries: send byte 0x01 → receive 4 × uint64_t (tx_packets,
 * tx_bytes, rx_packets, rx_bytes) in return.
 *
 * Usage: vpnbridge --control-socket NAME --config PATH
 */

#include <stdio.h>
#include <stdlib.h>
#include <string.h>
#include <signal.h>
#include <unistd.h>
#include <stdint.h>
#include <pthread.h>
#include <sys/socket.h>
#include <linux/un.h>
#include <errno.h>

#include "hev-main.h"

static volatile int running = 1;

static void
sigterm_handler (int signum)
{
    (void)signum;
    running = 0;
    hev_socks5_tunnel_quit ();
}

/*
 * Connect to the Android LocalServerSocket, receive the TUN fd via
 * SCM_RIGHTS.  Returns the TUN fd on success, -1 on error.
 * *out_ctrl_fd receives the connected socket fd (caller must close it).
 */
static int
receive_tun_fd (const char *socket_name, int *out_ctrl_fd)
{
    int fd;
    struct sockaddr_un addr;
    struct msghdr msg;
    struct iovec iov;
    char buf[1] = {0};
    char cmsgbuf[CMSG_SPACE (sizeof (int))];

    fd = socket (AF_UNIX, SOCK_STREAM, 0);
    if (fd < 0) {
        fprintf (stderr, "socket: %s\n", strerror (errno));
        return -1;
    }

    memset (&addr, 0, sizeof (addr));
    addr.sun_family = AF_UNIX;
    addr.sun_path[0] = '\0';
    strncpy (addr.sun_path + 1, socket_name, sizeof (addr.sun_path) - 2);

    socklen_t addr_len
        = offsetof (struct sockaddr_un, sun_path) + 1 + strlen (socket_name);
    if (connect (fd, (struct sockaddr *)&addr, addr_len) < 0) {
        fprintf (stderr, "connect: %s\n", strerror (errno));
        close (fd);
        return -1;
    }

    memset (&msg, 0, sizeof (msg));
    iov.iov_base = buf;
    iov.iov_len = 1;
    msg.msg_iov = &iov;
    msg.msg_iovlen = 1;
    msg.msg_control = cmsgbuf;
    msg.msg_controllen = sizeof (cmsgbuf);

    if (recvmsg (fd, &msg, 0) < 0) {
        fprintf (stderr, "recvmsg: %s\n", strerror (errno));
        close (fd);
        return -1;
    }

    struct cmsghdr *cmsg = CMSG_FIRSTHDR (&msg);
    if (!cmsg || cmsg->cmsg_level != SOL_SOCKET
        || cmsg->cmsg_type != SCM_RIGHTS) {
        fprintf (stderr, "no fd received\n");
        close (fd);
        return -1;
    }

    int tun_fd;
    memcpy (&tun_fd, CMSG_DATA (cmsg), sizeof (tun_fd));

    /*
     * Return the connected socket fd for later stats queries.
     * Caller must close it when done.
     */
    *out_ctrl_fd = fd;
    return tun_fd;
}

/*
 * Thread function: reads commands from stat_fd and writes responses.
 * Commands (single byte):
 *   0x01  STATS_QUERY  →  respond with 4 × uint64_t
 *                         [tx_packets, tx_bytes, rx_packets, rx_bytes]
 */
static void *
stats_thread (void *arg)
{
    int fd = *(int *)arg;
    unsigned char cmd;

    for (;;) {
        ssize_t n = read (fd, &cmd, 1);
        if (n <= 0)
            break; /* connection closed or error */

        if (cmd == 0x01) {
            size_t s_tx_p, s_tx_b, s_rx_p, s_rx_b;
            hev_socks5_tunnel_stats (&s_tx_p, &s_tx_b, &s_rx_p, &s_rx_b);
            uint64_t resp[4]
                = { (uint64_t)s_tx_p, (uint64_t)s_tx_b,
                    (uint64_t)s_rx_p, (uint64_t)s_rx_b };
            write (fd, resp, sizeof (resp));
        }
    }

    close (fd);
    return NULL;
}

int
main (int argc, char *argv[])
{
    const char *control_socket = NULL;
    const char *config_path = NULL;

    for (int i = 1; i < argc; i++) {
        if (strcmp (argv[i], "--control-socket") == 0 && i + 1 < argc) {
            control_socket = argv[++i];
        } else if (strcmp (argv[i], "--config") == 0 && i + 1 < argc) {
            config_path = argv[++i];
        }
    }

    if (!control_socket || !config_path) {
        fprintf (stderr, "Usage: %s --control-socket NAME --config PATH\n",
                 argv[0]);
        return 1;
    }

    int ctrl_fd;
    int tun_fd = receive_tun_fd (control_socket, &ctrl_fd);
    if (tun_fd < 0) {
        fprintf (stderr, "Failed to receive TUN fd\n");
        return 2;
    }

    signal (SIGTERM, sigterm_handler);
    signal (SIGPIPE, SIG_IGN);

    /* Start stats query thread – keeps ctrl_fd open */
    pthread_t thr;
    pthread_create (&thr, NULL, stats_thread, &ctrl_fd);
    pthread_detach (thr);

    int res = hev_socks5_tunnel_main (config_path, tun_fd);

    /*
     * Tunnel is done.  Close ctrl_fd so the stats thread sees EOF
     * and exits, then close the TUN fd.
     */
    close (ctrl_fd);
    close (tun_fd);
    return res < 0 ? 3 : 0;
}
