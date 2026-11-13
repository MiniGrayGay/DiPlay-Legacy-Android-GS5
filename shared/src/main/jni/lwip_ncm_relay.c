#include <jni.h>
#include <stdint.h>
#include <stdlib.h>
#include <string.h>
#include <stdio.h>
#ifdef _WIN32
#include <winsock2.h>
#include <ws2tcpip.h>
#include <windows.h>
#include <pthread.h>
#include <stdatomic.h>
#else
#include <unistd.h>
#include <pthread.h>
#include <stdatomic.h>
#include <fcntl.h>
#include <errno.h>
#include <sys/types.h>
#include <sys/socket.h>
#include <sys/resource.h>
#include <netinet/in.h>
#include <netinet/tcp.h>
#include <arpa/inet.h>
#ifdef __ANDROID__
#include <android/log.h>
#endif
#endif

#include "lwip/init.h"
#include "lwip/netif.h"
#include "lwip/pbuf.h"
#include "lwip/tcp.h"
#include "lwip/udp.h"
#include "lwip/ethip6.h"
#include "lwip/timeouts.h"
#include "lwip/priv/tcp_priv.h"
#include "netif/ethernet.h"

#define TAG "xcertplay-lwip"
#ifdef _WIN32
#define LOGI(...) do { fprintf(stderr, TAG " I: "); fprintf(stderr, __VA_ARGS__); fputc('\n', stderr); } while (0)
#define LOGW(...) do { fprintf(stderr, TAG " W: "); fprintf(stderr, __VA_ARGS__); fputc('\n', stderr); } while (0)
#define LOGE(...) do { fprintf(stderr, TAG " E: "); fprintf(stderr, __VA_ARGS__); fputc('\n', stderr); } while (0)
typedef SOCKET relay_socket_t;
#define RELAY_INVALID_SOCKET INVALID_SOCKET
#define relay_socket_valid(fd) ((fd) != INVALID_SOCKET)
#define relay_socket_close(fd) closesocket(fd)
#define relay_last_error() WSAGetLastError()
#define relay_would_block(error) ((error) == WSAEWOULDBLOCK)
#define relay_interrupted(error) ((error) == WSAEINTR)
#define relay_set_nonblocking(fd) do { u_long mode = 1; ioctlsocket((fd), FIONBIO, &mode); } while (0)
#ifndef MSG_NOSIGNAL
#define MSG_NOSIGNAL 0
#endif
#elif defined(__ANDROID__)
#define LOGI(...) __android_log_print(ANDROID_LOG_INFO, TAG, __VA_ARGS__)
#define LOGW(...) __android_log_print(ANDROID_LOG_WARN, TAG, __VA_ARGS__)
#define LOGE(...) __android_log_print(ANDROID_LOG_ERROR, TAG, __VA_ARGS__)
#else
#define LOGI(...) do { fprintf(stderr, TAG " I: "); fprintf(stderr, __VA_ARGS__); fputc('\n', stderr); } while (0)
#define LOGW(...) do { fprintf(stderr, TAG " W: "); fprintf(stderr, __VA_ARGS__); fputc('\n', stderr); } while (0)
#define LOGE(...) do { fprintf(stderr, TAG " E: "); fprintf(stderr, __VA_ARGS__); fputc('\n', stderr); } while (0)
#endif
#ifndef _WIN32
typedef int relay_socket_t;
#define RELAY_INVALID_SOCKET (-1)
#define relay_socket_valid(fd) ((fd) >= 0)
#define relay_socket_close(fd) close(fd)
#define relay_last_error() errno
#define relay_would_block(error) ((error) == EAGAIN || (error) == EWOULDBLOCK)
#define relay_interrupted(error) ((error) == EINTR)
#define relay_set_nonblocking(fd) do { int flags = fcntl((fd), F_GETFL, 0); fcntl((fd), F_SETFL, flags | O_NONBLOCK); } while (0)
#endif

#define MAX_RELAY_CONNS 32
#define MAX_UDP_RELAYS 32
/* Phone-bound bytes waiting for tcp_write. One window is enough. */
#define LOOPBACK_TO_LWIP_BUFFER_SIZE 16384
/* Phone-to-app bytes. Must cover TCP_WND (just under 64 KiB) plus one more
 * flight, otherwise recv returns ERR_MEM while the advertised window is still
 * open and the iPhone waits for a retransmit. */
#define LWIP_TO_LOOPBACK_BUFFER_SIZE (128 * 1024)
#define LOOPBACK_SOCKET_BUFFER_SIZE (256 * 1024)

/* lwIP hands tcp_relay_recv a whole reassembled out-of-order chain (up to the
 * receive window) at once, and the window only reopens after tcp_recved. A
 * buffer smaller than TCP_WND can therefore refuse a chain forever: lwIP keeps
 * it as refused_data and drops every later data segment, and the connection
 * stalls while small UDP traffic still flows. */
_Static_assert(LWIP_TO_LOOPBACK_BUFFER_SIZE >= TCP_WND,
               "the phone-to-app relay buffer must cover the whole receive window");
/* No window scaling: the window is announced in the 16-bit TCP field. */
_Static_assert(TCP_WND <= 0xffff, "TCP_WND must fit the unscaled window field");

// NO_SYS lwIP owns process-global heaps, PCB lists and cyclic timers.
// Reinitializing those globals for another USB session corrupts next_timeout.
static pthread_once_t lwip_once = PTHREAD_ONCE_INIT;
static pthread_mutex_t lwip_core_lock = PTHREAD_MUTEX_INITIALIZER;
static void initialize_lwip_once(void) {
#ifdef _WIN32
    WSADATA data;
    if (WSAStartup(MAKEWORD(2, 2), &data) != 0) {
        LOGE("WSAStartup failed");
    }
#endif
    lwip_init();
}

struct LwipContext;

typedef struct {
    int active;
    struct LwipContext *ctx;
    struct tcp_pcb *lwip_pcb;
    relay_socket_t loopback_fd;
    uint8_t loopback_to_lwip_buf[LOOPBACK_TO_LWIP_BUFFER_SIZE];
    int loopback_to_lwip_len;
    uint8_t lwip_to_loopback_buf[LWIP_TO_LOOPBACK_BUFFER_SIZE];
    int lwip_to_loopback_len;
    /* The phone sent FIN while bytes were still waiting here; the loopback
     * write side is shut down once they have been delivered. */
    int fin_pending;
} TcpRelayConn;

typedef struct {
    int active;
    int port;
    struct LwipContext *ctx;
    struct udp_pcb *lwip_pcb;
    relay_socket_t loopback_fd;
    ip_addr_t peer_ip;
    u16_t peer_port;
    int has_peer;
} UdpRelay;

typedef struct {
    int active;
    int port;
    int is_tcp;
} PortMapping;

#define MAX_PORT_MAPPINGS 32

/* Performance counters, read and reset by nativeGetPerfCounters. Indexes are
 * shared with LwipNcmNative.PERF_* in Kotlin. Updated under lwip_core_lock. */
enum {
    PERF_RX_FRAMES = 0,       /* frames injected from USB */
    PERF_RX_DROPS,            /* netif input rejected the frame */
    PERF_PBUF_ALLOC_FAILS,    /* no heap for an inbound frame */
    PERF_TX_FRAMES,           /* frames lwIP handed to the USB TX queue */
    PERF_RECV_ERR_MEM,        /* tcp_relay_recv refused data (should stay 0) */
    PERF_RECV_MAX_CHAIN,      /* largest chain passed to tcp_relay_recv */
    PERF_RELAY_PEAK_BYTES,    /* peak phone-to-app bytes waiting for loopback */
    PERF_LOOPBACK_EAGAIN,     /* loopback send would block (app reader is behind) */
    PERF_TCP_REFUSED_PCBS,    /* PCBs holding refused_data at snapshot time */
    PERF_TCP_MIN_RCV_WND,     /* smallest receive window at snapshot time */
    PERF_TCP_OOSEQ_PCBS,      /* PCBs holding out-of-order segments at snapshot time */
    PERF_COUNT
};

typedef struct LwipContext {
    JavaVM *jvm;
    jobject bridge_obj_global;
    jmethodID on_tx_frame_mid;

    struct netif ncm_netif;
    uint8_t host_mac[6];
    ip6_addr_t host_ip6;

    struct tcp_pcb *listen_pcbs[MAX_PORT_MAPPINGS];
    int num_listen_pcbs;

    PortMapping port_mappings[MAX_PORT_MAPPINGS];
    int num_port_mappings;

    TcpRelayConn tcp_conns[MAX_RELAY_CONNS];
    UdpRelay udp_relays[MAX_UDP_RELAYS];

    pthread_t thread;
    atomic_int running;
    int wake_pipe[2]; /* POSIX-only; Windows worker uses a 2 ms wait. */
    /* Set under the core lock when a USB-side call leaves work only the worker
     * can pick up; the call writes the wake pipe after it drops the lock. */
    int wake_needed;

    uint32_t stats_rx_frames;
    uint32_t stats_rx_bytes;
    uint32_t stats_rx_tcp;
    uint32_t stats_rx_tcp_syn;
    uint32_t stats_rx_udp;
    uint32_t stats_rx_icmp6;
    uint32_t stats_rx_drops;
    uint32_t stats_tx_frames;
    uint32_t stats_tx_bytes;
    uint32_t stats_tcp_conns_accepted;
    uint32_t stats_tcp_conns_closed;

    uint64_t perf[PERF_COUNT];
} LwipContext;

static err_t ncm_netif_output_ip6(struct netif *netif, struct pbuf *p, const ip6_addr_t *ip6addr) {
    return ethip6_output(netif, p, ip6addr);
}

static err_t ncm_linkoutput(struct netif *netif, struct pbuf *p) {
    LwipContext *ctx = (LwipContext *)netif->state;
    if (ctx == NULL || !ctx->running) return ERR_OK;

    int total_len = p->tot_len;
    if (total_len <= 0 || total_len > 65535) return ERR_VAL;

    ctx->stats_tx_frames++;
    ctx->stats_tx_bytes += (uint32_t)total_len;
    ctx->perf[PERF_TX_FRAMES]++;

    JNIEnv *env = NULL;
    int attached = 0;
    if ((*ctx->jvm)->GetEnv(ctx->jvm, (void **)&env, JNI_VERSION_1_6) != JNI_OK) {
#if defined(_WIN32) || !defined(__ANDROID__)
        if ((*ctx->jvm)->AttachCurrentThread(ctx->jvm, (void **)&env, NULL) != 0) {
#else
        if ((*ctx->jvm)->AttachCurrentThread(ctx->jvm, &env, NULL) != 0) {
#endif
            return ERR_VAL;
        }
        attached = 1;
    }

    jbyteArray jframe = (*env)->NewByteArray(env, total_len);
    if (jframe != NULL) {
        jbyte *buf = (*env)->GetByteArrayElements(env, jframe, NULL);
        if (buf != NULL) {
            pbuf_copy_partial(p, buf, total_len, 0);
            /* Issue #305: this runs per frame (hundreds per second at high
             * bitrates); an unconditional log line plus its header copy is
             * pure overhead on small-heap units. Sample it. */
            if (ctx->stats_tx_frames % 300 == 0) {
                uint8_t header[21] = {0};
                memcpy(header, buf, total_len < 21 ? (size_t)total_len : sizeof(header));
                LOGI("ncm_linkoutput sending frame len=%d proto=0x%02x%02x next=0x%02x",
                     total_len, (unsigned char)header[12], (unsigned char)header[13],
                     total_len >= 21 ? (unsigned char)header[20] : 0);
            }
            (*env)->ReleaseByteArrayElements(env, jframe, buf, 0);
            (*env)->CallVoidMethod(env, ctx->bridge_obj_global, ctx->on_tx_frame_mid, jframe);
        }
        (*env)->DeleteLocalRef(env, jframe);
    }

    if (attached) {
        (*ctx->jvm)->DetachCurrentThread(ctx->jvm);
    }
    return ERR_OK;
}

static err_t ncm_netif_init(struct netif *netif) {
    LwipContext *ctx = (LwipContext *)netif->state;
    netif->name[0] = 'n';
    netif->name[1] = 'c';
    netif->output_ip6 = ncm_netif_output_ip6;
    netif->linkoutput = ncm_linkoutput;
    netif->mtu = 1500;
    netif->flags = NETIF_FLAG_BROADCAST | NETIF_FLAG_ETHARP | NETIF_FLAG_ETHERNET | NETIF_FLAG_UP | NETIF_FLAG_LINK_UP;
    netif->hwaddr_len = 6;
    memcpy(netif->hwaddr, ctx->host_mac, 6);
    return ERR_OK;
}

static void close_tcp_relay_conn(LwipContext *ctx, TcpRelayConn *conn) {
    if (!conn->active) return;
    conn->active = 0;
    ctx->stats_tcp_conns_closed++;
    if (relay_socket_valid(conn->loopback_fd)) {
        relay_socket_close(conn->loopback_fd);
        conn->loopback_fd = RELAY_INVALID_SOCKET;
    }
    if (conn->lwip_pcb != NULL) {
        tcp_arg(conn->lwip_pcb, NULL);
        tcp_recv(conn->lwip_pcb, NULL);
        tcp_sent(conn->lwip_pcb, NULL);
        tcp_err(conn->lwip_pcb, NULL);
        tcp_poll(conn->lwip_pcb, NULL, 0);
        // The local relay has ended. Do not leave a closing PCB referencing
        // an obsolete USB session or consuming the global PCB pool on retry.
        tcp_abort(conn->lwip_pcb);
        conn->lwip_pcb = NULL;
    }
    conn->loopback_to_lwip_len = 0;
    conn->lwip_to_loopback_len = 0;
}

static void close_udp_relay(LwipContext *ctx, UdpRelay *relay) {
    (void)ctx;
    if (!relay->active) return;
    relay->active = 0;
    if (relay_socket_valid(relay->loopback_fd)) {
        relay_socket_close(relay->loopback_fd);
        relay->loopback_fd = RELAY_INVALID_SOCKET;
    }
    if (relay->lwip_pcb != NULL) {
        udp_recv(relay->lwip_pcb, NULL, NULL);
        udp_remove(relay->lwip_pcb);
        relay->lwip_pcb = NULL;
    }
    relay->has_peer = 0;
    relay->port = 0;
}

static void udp_relay_recv(void *arg, struct udp_pcb *upcb, struct pbuf *p,
                           const ip_addr_t *addr, u16_t port) {
    (void)upcb;
    if (p == NULL) return;

    UdpRelay *relay = (UdpRelay *)arg;
    if (relay == NULL || !relay->active || !relay_socket_valid(relay->loopback_fd)) {
        pbuf_free(p);
        return;
    }

    if (addr != NULL) {
        ip_addr_copy(relay->peer_ip, *addr);
        relay->peer_port = port;
        // The worker only watches relays that have a peer; let it pick this one up.
        if (!relay->has_peer && relay->ctx != NULL) relay->ctx->wake_needed = 1;
        relay->has_peer = 1;
    }

    struct sockaddr_in sin;
    memset(&sin, 0, sizeof(sin));
    sin.sin_family = AF_INET;
    sin.sin_port = htons((uint16_t)relay->port);
    sin.sin_addr.s_addr = htonl(INADDR_LOOPBACK);

    int tot_len = p->tot_len;
    if (tot_len > 0) {
        if (p->next == NULL) {
            sendto(relay->loopback_fd, p->payload, (size_t)p->len, 0,
                   (struct sockaddr *)&sin, sizeof(sin));
        } else {
            uint8_t stack_buf[2048];
            uint8_t *buf = stack_buf;
            if (tot_len > (int)sizeof(stack_buf)) {
                buf = (uint8_t *)malloc((size_t)tot_len);
            }
            if (buf != NULL) {
                pbuf_copy_partial(p, buf, (u16_t)tot_len, 0);
                sendto(relay->loopback_fd, (const char *)buf, (size_t)tot_len, 0,
                       (struct sockaddr *)&sin, sizeof(sin));
                if (buf != stack_buf) {
                    free(buf);
                }
            }
        }
    }

    pbuf_free(p);
}

static void tcp_relay_err(void *arg, err_t err) {
    (void)err;
    TcpRelayConn *conn = (TcpRelayConn *)arg;
    if (conn != NULL) {
        conn->lwip_pcb = NULL; // PCB is already freed by lwIP before err callback
        if (relay_socket_valid(conn->loopback_fd)) {
            relay_socket_close(conn->loopback_fd);
            conn->loopback_fd = RELAY_INVALID_SOCKET;
        }
        conn->active = 0;
    }
}

static void flush_loopback_to_lwip(TcpRelayConn *conn) {
    if (conn == NULL || !conn->active || conn->lwip_pcb == NULL) return;
    if (conn->loopback_to_lwip_len > 0) {
        u16_t snd_buf = tcp_sndbuf(conn->lwip_pcb);
        if (snd_buf > 0) {
            u16_t to_send = (u16_t)conn->loopback_to_lwip_len;
            if (to_send > snd_buf) {
                to_send = snd_buf;
            }
            err_t wr_err = tcp_write(conn->lwip_pcb, conn->loopback_to_lwip_buf, to_send, TCP_WRITE_FLAG_COPY);
            if (wr_err == ERR_OK) {
                tcp_output(conn->lwip_pcb);
                // A full buffer keeps its socket out of the worker's select set;
                // tell the worker to start reading it again.
                if (conn->loopback_to_lwip_len == LOOPBACK_TO_LWIP_BUFFER_SIZE && conn->ctx != NULL) {
                    conn->ctx->wake_needed = 1;
                }
                conn->loopback_to_lwip_len -= (int)to_send;
                if (conn->loopback_to_lwip_len > 0) {
                    memmove(conn->loopback_to_lwip_buf, conn->loopback_to_lwip_buf + to_send, (size_t)conn->loopback_to_lwip_len);
                }
            } else if (wr_err != ERR_MEM) {
                LOGW("tcp_write failed: %d", wr_err);
            }
        }
    }
}

static err_t tcp_relay_sent(void *arg, struct tcp_pcb *tpcb, u16_t len) {
    (void)tpcb;
    (void)len;
    TcpRelayConn *conn = (TcpRelayConn *)arg;
    if (conn != NULL && conn->active) {
        flush_loopback_to_lwip(conn);
    }
    return ERR_OK;
}

static void shutdown_loopback_write(TcpRelayConn *conn) {
    conn->fin_pending = 0;
    if (!relay_socket_valid(conn->loopback_fd)) return;
#ifdef _WIN32
    shutdown(conn->loopback_fd, SD_SEND);
#else
    shutdown(conn->loopback_fd, SHUT_WR);
#endif
}

static err_t tcp_relay_recv(void *arg, struct tcp_pcb *tpcb, struct pbuf *p, err_t err) {
    (void)err;
    TcpRelayConn *conn = (TcpRelayConn *)arg;
    if (conn == NULL || !conn->active) {
        if (p != NULL) pbuf_free(p);
        return ERR_OK;
    }

    if (p == NULL) {
        // Peer closed connection. Bytes of the same USB batch may still be
        // waiting for loopback; shutting down first would lose them.
        if (conn->lwip_to_loopback_len > 0) {
            conn->fin_pending = 1;
        } else {
            shutdown_loopback_write(conn);
        }
        return ERR_OK;
    }

    // Never wait for a slow local reader while holding the global NO_SYS core
    // lock. Keep bounded data and let the worker forward it nonblockingly.
    LwipContext *ctx = conn->ctx;
    if (ctx != NULL && p->tot_len > ctx->perf[PERF_RECV_MAX_CHAIN]) {
        ctx->perf[PERF_RECV_MAX_CHAIN] = p->tot_len;
    }
    if (p->tot_len > LWIP_TO_LOOPBACK_BUFFER_SIZE - conn->lwip_to_loopback_len) {
        if (ctx != NULL) ctx->perf[PERF_RECV_ERR_MEM]++;
        return ERR_MEM;
    }
    u16_t accepted = (u16_t)p->tot_len;
    pbuf_copy_partial(p, conn->lwip_to_loopback_buf + conn->lwip_to_loopback_len, accepted, 0);
    conn->lwip_to_loopback_len += accepted;
    if (ctx != NULL && (uint64_t)conn->lwip_to_loopback_len > ctx->perf[PERF_RELAY_PEAK_BYTES]) {
        ctx->perf[PERF_RELAY_PEAK_BYTES] = (uint64_t)conn->lwip_to_loopback_len;
    }
    pbuf_free(p);
    // No ACK here: lwIP already ACKs every second segment, and
    // finish_input_batch sends the pending one when the USB batch ends. The
    // odd segment (the tail of a ~7-segment video frame) would otherwise wait
    // for the 250 ms TCP timer and the phone would hold the next frame. The
    // receive window stays closed until finish_input_batch (or the worker)
    // copies these bytes to loopback and calls tcp_recved.
    (void)tpcb;
    return ERR_OK;
}

static err_t tcp_relay_accept(void *arg, struct tcp_pcb *newpcb, err_t err) {
    (void)err;
    LwipContext *ctx = (LwipContext *)arg;
    if (ctx == NULL || newpcb == NULL) return ERR_VAL;

    int port = newpcb->local_port;
    LOGI("lwIP accepted TCP connection on port %d", port);

    TcpRelayConn *conn = NULL;
    for (int i = 0; i < MAX_RELAY_CONNS; i++) {
        if (!ctx->tcp_conns[i].active) {
            conn = &ctx->tcp_conns[i];
            break;
        }
    }

    if (conn == NULL) {
        LOGE("No free relay slots for incoming TCP connection on port %d", port);
        return ERR_MEM;
    }

    relay_socket_t fd = socket(AF_INET, SOCK_STREAM, 0);
    if (!relay_socket_valid(fd)) {
        LOGE("Failed to create loopback socket: %d", relay_last_error());
        return ERR_CLSD;
    }

    int one = 1;
    int socket_buffer = LOOPBACK_SOCKET_BUFFER_SIZE;
    setsockopt(fd, IPPROTO_TCP, TCP_NODELAY, (const char *)&one, sizeof(one));
    setsockopt(fd, SOL_SOCKET, SO_RCVBUF, (const char *)&socket_buffer, sizeof(socket_buffer));
    setsockopt(fd, SOL_SOCKET, SO_SNDBUF, (const char *)&socket_buffer, sizeof(socket_buffer));

    struct sockaddr_in sin;
    memset(&sin, 0, sizeof(sin));
    sin.sin_family = AF_INET;
    sin.sin_port = htons((uint16_t)port);
    sin.sin_addr.s_addr = inet_addr("127.0.0.1");

    int res = connect(fd, (struct sockaddr *)&sin, sizeof(sin));
    if (res < 0) {
        LOGE("Failed to connect to 127.0.0.1:%d, errno=%d", port, relay_last_error());
        relay_socket_close(fd);
        return ERR_CONN;
    }

    relay_set_nonblocking(fd);

    conn->active = 1;
    conn->ctx = ctx;
    conn->lwip_pcb = newpcb;
    conn->loopback_fd = fd;
    conn->loopback_to_lwip_len = 0;
    conn->lwip_to_loopback_len = 0;
    conn->fin_pending = 0;
    ctx->stats_tcp_conns_accepted++;
    // The worker has to add this socket to its select set before it can relay
    // the app's first reply.
    ctx->wake_needed = 1;

    tcp_arg(newpcb, conn);
    tcp_recv(newpcb, tcp_relay_recv);
    tcp_err(newpcb, tcp_relay_err);
    tcp_sent(newpcb, tcp_relay_sent);
    tcp_nagle_disable(newpcb);

    return ERR_OK;
}

/* Sends the phone-to-app bytes waiting on [conn] to its loopback socket without
 * blocking and reopens the receive window for what was taken. Closes the
 * connection on a socket error. Must not run inside an lwIP callback. */
static void forward_to_loopback(LwipContext *ctx, TcpRelayConn *conn) {
    int done = 0;
    while (done < conn->lwip_to_loopback_len) {
        ssize_t sent = send(conn->loopback_fd, (const char *)conn->lwip_to_loopback_buf + done,
                            (size_t)(conn->lwip_to_loopback_len - done), MSG_NOSIGNAL);
        if (sent > 0) {
            done += (int)sent;
        } else if (sent < 0 && relay_interrupted(relay_last_error())) {
            continue;
        } else if (sent < 0 && relay_would_block(relay_last_error())) {
            ctx->perf[PERF_LOOPBACK_EAGAIN]++;
            break;
        } else {
            close_tcp_relay_conn(ctx, conn);
            return;
        }
    }
    if (done == 0) return;
    conn->lwip_to_loopback_len -= done;
    if (conn->lwip_to_loopback_len > 0) {
        memmove(conn->lwip_to_loopback_buf, conn->lwip_to_loopback_buf + done,
                (size_t)conn->lwip_to_loopback_len);
    }
    /* tcp_recved takes u16_t; the buffer can hold more than that. */
    while (done > 0) {
        u16_t credit = done > 0xffff ? 0xffff : (u16_t)done;
        tcp_recved(conn->lwip_pcb, credit);
        done -= credit;
    }
    if (conn->fin_pending && conn->lwip_to_loopback_len == 0) shutdown_loopback_write(conn);
}

/* Runs once after a batch of USB frames went through lwIP, still under the
 * core lock. One loopback send per connection carries the whole batch to the
 * app, and one ACK covers whatever lwIP left delayed, so neither costs a
 * worker wakeup or a USB transfer per segment. */
static void finish_input_batch(LwipContext *ctx) {
    for (int i = 0; i < MAX_RELAY_CONNS; i++) {
        TcpRelayConn *conn = &ctx->tcp_conns[i];
        if (!conn->active || conn->lwip_pcb == NULL) continue;
        if (conn->lwip_to_loopback_len > 0 && relay_socket_valid(conn->loopback_fd)) {
            forward_to_loopback(ctx, conn);
            if (!conn->active || conn->lwip_pcb == NULL) continue;
            // The app reader is behind: the worker waits for the socket to
            // become writable again.
            if (conn->lwip_to_loopback_len > 0) ctx->wake_needed = 1;
        }
        if (conn->lwip_pcb->flags & TF_ACK_DELAY) {
            tcp_ack_now(conn->lwip_pcb);
            tcp_output(conn->lwip_pcb);
        }
    }
}

/* [readable] is the worker's last select result, or NULL to try every socket. */
static void check_and_forward_loopback(LwipContext *ctx, const fd_set *readable) {
    for (int i = 0; i < MAX_RELAY_CONNS; i++) {
        TcpRelayConn *conn = &ctx->tcp_conns[i];
        if (!conn->active || !relay_socket_valid(conn->loopback_fd) || conn->lwip_pcb == NULL) continue;

        if (conn->lwip_to_loopback_len > 0) forward_to_loopback(ctx, conn);
        if (!conn->active) continue;
        flush_loopback_to_lwip(conn);

        int space = LOOPBACK_TO_LWIP_BUFFER_SIZE - conn->loopback_to_lwip_len;
        if (space > 0 && (readable == NULL || FD_ISSET(conn->loopback_fd, readable))) {
            ssize_t n = recv(conn->loopback_fd, (char *)conn->loopback_to_lwip_buf + conn->loopback_to_lwip_len, space, 0);
            if (n > 0) {
                conn->loopback_to_lwip_len += (int)n;
                flush_loopback_to_lwip(conn);
            } else if (n == 0) {
                LOGI("loopback_fd EOF for port %d", conn->lwip_pcb ? conn->lwip_pcb->local_port : 0);
                close_tcp_relay_conn(ctx, conn);
            } else if (n < 0 && !relay_would_block(relay_last_error())) {
                LOGW("loopback_fd read error %d", relay_last_error());
                close_tcp_relay_conn(ctx, conn);
            }
        }
    }
}

static void check_and_forward_udp_loopback(LwipContext *ctx, const fd_set *readable) {
    for (int i = 0; i < MAX_UDP_RELAYS; i++) {
        UdpRelay *relay = &ctx->udp_relays[i];
        if (!relay->active || !relay_socket_valid(relay->loopback_fd) || relay->lwip_pcb == NULL) continue;
        if (!relay->has_peer) continue;
        if (readable != NULL && !FD_ISSET(relay->loopback_fd, readable)) continue;

        uint8_t buf[2048];
        while (1) {
            ssize_t n = recvfrom(relay->loopback_fd, (char *)buf, sizeof(buf), 0, NULL, NULL);
            if (n > 0) {
                struct pbuf *p = pbuf_alloc(PBUF_TRANSPORT, (u16_t)n, PBUF_RAM);
                if (p != NULL) {
                    pbuf_take(p, buf, (u16_t)n);
                    err_t err = udp_sendto(relay->lwip_pcb, p, &relay->peer_ip, relay->peer_port);
                    if (err != ERR_OK) {
                        LOGW("udp_sendto port %d failed: %d", relay->port, err);
                    }
                    pbuf_free(p);
                } else {
                    LOGW("pbuf_alloc failed for udp forwarding on port %d, len=%zd", relay->port, n);
                }
            } else {
                if (n < 0 && !relay_would_block(relay_last_error())) {
                    LOGW("loopback_fd udp recvfrom error %d for port %d", relay_last_error(), relay->port);
                }
                break;
            }
        }
    }
}

static void register_listeners_locked(LwipContext *ctx) {
    for (int m = 0; m < ctx->num_port_mappings; m++) {
        if (!ctx->port_mappings[m].active) continue;
        int port = ctx->port_mappings[m].port;
        int is_tcp = ctx->port_mappings[m].is_tcp;

        if (is_tcp) {
            int already_listening = 0;
            for (int i = 0; i < ctx->num_listen_pcbs; i++) {
                if (ctx->listen_pcbs[i] != NULL && ctx->listen_pcbs[i]->local_port == port) {
                    already_listening = 1;
                    break;
                }
            }
            if (already_listening) continue;

            struct tcp_pcb *pcb = tcp_new_ip6();
            if (pcb == NULL) {
                LOGE("tcp_new_ip6 failed for port %d", port);
                continue;
            }

            tcp_bind_netif(pcb, &ctx->ncm_netif);

            err_t err = tcp_bind(pcb, IP6_ADDR_ANY, (u16_t)port);
            if (err != ERR_OK) {
                LOGE("tcp_bind port %d failed: %d", port, err);
                tcp_close(pcb);
                continue;
            }

            struct tcp_pcb *listen_pcb = tcp_listen_with_backlog(pcb, 4);
            if (listen_pcb == NULL) {
                LOGE("tcp_listen_with_backlog port %d failed", port);
                tcp_close(pcb);
                continue;
            }

            tcp_arg(listen_pcb, ctx);
            tcp_accept(listen_pcb, tcp_relay_accept);

            if (ctx->num_listen_pcbs < MAX_PORT_MAPPINGS) {
                ctx->listen_pcbs[ctx->num_listen_pcbs++] = listen_pcb;
                LOGI("lwIP listening on [fe80::2]:%d (TCP)", port);
            }
        } else {
            int already_relayed = 0;
            for (int i = 0; i < MAX_UDP_RELAYS; i++) {
                if (ctx->udp_relays[i].active && ctx->udp_relays[i].port == port) {
                    already_relayed = 1;
                    break;
                }
            }
            if (already_relayed) continue;

            UdpRelay *relay = NULL;
            for (int i = 0; i < MAX_UDP_RELAYS; i++) {
                if (!ctx->udp_relays[i].active) {
                    relay = &ctx->udp_relays[i];
                    break;
                }
            }
            if (relay == NULL) {
                LOGE("No free UDP relay slots for port %d", port);
                continue;
            }

            struct udp_pcb *pcb = udp_new_ip6();
            if (pcb == NULL) {
                LOGE("udp_new_ip6 failed for port %d", port);
                continue;
            }

            udp_bind_netif(pcb, &ctx->ncm_netif);

            err_t err = udp_bind(pcb, IP6_ADDR_ANY, (u16_t)port);
            if (err != ERR_OK) {
                LOGE("udp_bind port %d failed: %d", port, err);
                udp_remove(pcb);
                continue;
            }

            relay_socket_t fd = socket(AF_INET, SOCK_DGRAM, 0);
            if (!relay_socket_valid(fd)) {
                LOGE("Failed to create loopback UDP socket for port %d: %d", port, relay_last_error());
                udp_remove(pcb);
                continue;
            }

            relay_set_nonblocking(fd);

            int rcvbuf = 256 * 1024;
            int sndbuf = 256 * 1024;
            setsockopt(fd, SOL_SOCKET, SO_RCVBUF, (const char *)&rcvbuf, sizeof(rcvbuf));
            setsockopt(fd, SOL_SOCKET, SO_SNDBUF, (const char *)&sndbuf, sizeof(sndbuf));

            relay->active = 1;
            relay->port = port;
            relay->ctx = ctx;
            relay->lwip_pcb = pcb;
            relay->loopback_fd = fd;
            relay->has_peer = 0;
            memset(&relay->peer_ip, 0, sizeof(relay->peer_ip));
            relay->peer_port = 0;

            udp_recv(pcb, udp_relay_recv, relay);
            LOGI("lwIP listening on [fe80::2]:%d (UDP)", port);
        }
    }
}

/* Upper bound for one worker sleep. lwIP's own timers are due sooner while a
 * connection is open (250 ms TCP timer); this only bounds the retry of a
 * tcp_write that failed for lack of memory, which no event announces. */
#define WORKER_MAX_SLEEP_MS 50

static void *lwip_worker_thread(void *arg) {
    LwipContext *ctx = (LwipContext *)arg;
    // Audio priority (nice -16), like the Java NCM threads. Platform-signed
    // builds are allowed to; others keep the default and log why.
#ifdef _WIN32
    if (!SetThreadPriority(GetCurrentThread(), THREAD_PRIORITY_HIGHEST)) {
        LOGW("lwIP worker priority boost failed error=%lu", (unsigned long)GetLastError());
    }
    LOGI("lwIP worker thread started");
#else
    if (setpriority(PRIO_PROCESS, 0, -16) != 0) {
        LOGW("lwIP worker priority boost failed errno=%d", errno);
    }
    LOGI("lwIP worker thread started nice=%d", getpriority(PRIO_PROCESS, 0));
#endif

#ifndef _WIN32
    /* Sockets select reported readable; NULL until the first select, and after
     * a failed one, so that pass tries every socket. */
    fd_set ready;
    const fd_set *readable = NULL;
#endif

    while (ctx->running) {
        pthread_mutex_lock(&lwip_core_lock);

        ctx->wake_needed = 0;
        sys_check_timeouts();
        register_listeners_locked(ctx);
#ifndef _WIN32
        check_and_forward_loopback(ctx, readable);
        check_and_forward_udp_loopback(ctx, readable);

        /* Sleep until a socket or a USB-side call has work, or the next lwIP
         * timer is due. Nothing here needs a fixed poll: app-to-phone bytes make
         * a socket readable, a slow app reader makes one writable again, and the
         * USB side writes the wake pipe for the rest. */
        fd_set wfds;
        FD_ZERO(&ready);
        FD_ZERO(&wfds);
        int max_fd = ctx->wake_pipe[0];
        FD_SET(ctx->wake_pipe[0], &ready);

        for (int i = 0; i < MAX_RELAY_CONNS; i++) {
            TcpRelayConn *conn = &ctx->tcp_conns[i];
            if (!conn->active || conn->loopback_fd < 0) continue;
            if (conn->loopback_to_lwip_len < LOOPBACK_TO_LWIP_BUFFER_SIZE) {
                FD_SET(conn->loopback_fd, &ready);
                if (conn->loopback_fd > max_fd) max_fd = conn->loopback_fd;
            }
            if (conn->lwip_to_loopback_len > 0) {
                FD_SET(conn->loopback_fd, &wfds);
                if (conn->loopback_fd > max_fd) max_fd = conn->loopback_fd;
            }
        }

        for (int i = 0; i < MAX_UDP_RELAYS; i++) {
            UdpRelay *relay = &ctx->udp_relays[i];
            /* Without a peer nothing drains the socket, so watching it would spin. */
            if (!relay->active || relay->loopback_fd < 0 || !relay->has_peer) continue;
            FD_SET(relay->loopback_fd, &ready);
            if (relay->loopback_fd > max_fd) max_fd = relay->loopback_fd;
        }

        u32_t sleep_ms = sys_timeouts_sleeptime();
        if (sleep_ms > WORKER_MAX_SLEEP_MS) sleep_ms = WORKER_MAX_SLEEP_MS;

        pthread_mutex_unlock(&lwip_core_lock);

        struct timeval tv = { .tv_sec = 0, .tv_usec = (suseconds_t)sleep_ms * 1000 };
        int sel = select(max_fd + 1, &ready, &wfds, NULL, &tv);
        readable = sel >= 0 ? &ready : NULL;

        if (sel > 0 && FD_ISSET(ctx->wake_pipe[0], &ready)) {
            char ch;
            while (read(ctx->wake_pipe[0], &ch, 1) > 0);
        }
#else
        check_and_forward_loopback(ctx, NULL);
        check_and_forward_udp_loopback(ctx, NULL);
        pthread_mutex_unlock(&lwip_core_lock);
        Sleep(2);
#endif
    }

    LOGI("lwIP worker thread stopping");
    return NULL;
}

static void trigger_wake(LwipContext *ctx) {
#ifdef _WIN32
    (void)ctx;
#else
    if (ctx->wake_pipe[1] >= 0) {
        char ch = 'w';
        (void)write(ctx->wake_pipe[1], &ch, 1);
    }
#endif
}

JNIEXPORT jlong JNICALL
Java_com_shilapi_xcertplay_network_LwipNcmNative_nativeCreate(
        JNIEnv *env, jobject thiz, jbyteArray hostMacArr, jstring linkLocalStr) {
    (void)thiz;
    if (hostMacArr == NULL || linkLocalStr == NULL) return 0;

    LwipContext *ctx = (LwipContext *)calloc(1, sizeof(LwipContext));
    if (ctx == NULL) return 0;
    atomic_init(&ctx->running, 0);
    ctx->wake_pipe[0] = -1;
    ctx->wake_pipe[1] = -1;
    for (int i = 0; i < MAX_RELAY_CONNS; i++) {
        ctx->tcp_conns[i].loopback_fd = RELAY_INVALID_SOCKET;
    }
    for (int i = 0; i < MAX_UDP_RELAYS; i++) {
        ctx->udp_relays[i].loopback_fd = RELAY_INVALID_SOCKET;
    }

    (*env)->GetJavaVM(env, &ctx->jvm);
    ctx->bridge_obj_global = (*env)->NewGlobalRef(env, thiz);

    jclass cls = (*env)->GetObjectClass(env, thiz);
    ctx->on_tx_frame_mid = (*env)->GetMethodID(env, cls, "onTxFrame", "([B)V");
    if (ctx->on_tx_frame_mid == NULL) {
        LOGE("Failed to find onTxFrame method");
        free(ctx);
        return 0;
    }

    jbyte *mac_bytes = (*env)->GetByteArrayElements(env, hostMacArr, NULL);
    memcpy(ctx->host_mac, mac_bytes, 6);
    (*env)->ReleaseByteArrayElements(env, hostMacArr, mac_bytes, JNI_ABORT);

    const char *ll_str = (*env)->GetStringUTFChars(env, linkLocalStr, NULL);
    ip6addr_aton(ll_str, &ctx->host_ip6);
    (*env)->ReleaseStringUTFChars(env, linkLocalStr, ll_str);

#ifndef _WIN32
    if (pipe(ctx->wake_pipe) != 0) {
        (*env)->DeleteGlobalRef(env, ctx->bridge_obj_global);
        free(ctx);
        return 0;
    }
    for (int end = 0; end < 2; end++) {
        int flags = fcntl(ctx->wake_pipe[end], F_GETFL, 0);
        fcntl(ctx->wake_pipe[end], F_SETFL, flags | O_NONBLOCK);
    }
#endif

    pthread_mutex_lock(&lwip_core_lock);
    pthread_once(&lwip_once, initialize_lwip_once);

    netif_add_noaddr(&ctx->ncm_netif, ctx, ncm_netif_init, ethernet_input);
    netif_set_link_up(&ctx->ncm_netif);
    netif_set_up(&ctx->ncm_netif);

    s8_t idx = 0;
    netif_add_ip6_address(&ctx->ncm_netif, &ctx->host_ip6, &idx);
    netif_ip6_addr_set_state(&ctx->ncm_netif, idx, IP6_ADDR_VALID);

    ip6_addr_t ip6_alt;
    ip6addr_aton("fe80::1", &ip6_alt);
    if (!ip6_addr_cmp(&ctx->host_ip6, &ip6_alt)) {
        s8_t idx1 = 1;
        netif_add_ip6_address(&ctx->ncm_netif, &ip6_alt, &idx1);
        netif_ip6_addr_set_state(&ctx->ncm_netif, idx1, IP6_ADDR_VALID);
    }

    pthread_mutex_unlock(&lwip_core_lock);
    ctx->running = 1;
    pthread_create(&ctx->thread, NULL, lwip_worker_thread, ctx);

    LOGI("LwipContext initialized");
    return (jlong)(intptr_t)ctx;
}

/* Copies one Ethernet frame out of [frameArr] and runs it through lwIP.
 * Caller holds lwip_core_lock. Returns 0 when a Java exception is pending. */
static int inject_frame_locked(JNIEnv *env, LwipContext *ctx, jbyteArray frameArr, jint offset, jint length) {
    if (length <= 0 || offset < 0 || length > 0xffff) return 1;

    // lwIP is built with NO_SYS=1 / SYS_LIGHTWEIGHT_PROT=0: its heap (mem.c)
    // has no internal locking, so every heap access must hold the core lock.
    struct pbuf *p = pbuf_alloc(PBUF_RAW, (u16_t)length, PBUF_RAM);
    if (p == NULL) {
        ctx->perf[PERF_PBUF_ALLOC_FAILS]++;
        LOGW("pbuf_alloc failed for length %d", length);
        return 1;
    }

    // Copies straight out of the caller's NCM receive buffer: no per-frame
    // Java array is needed for the zero-allocation path.
    (*env)->GetByteArrayRegion(env, frameArr, offset, length, (jbyte *)p->payload);
    if ((*env)->ExceptionCheck(env)) {
        pbuf_free(p);
        return 0;
    }

    ctx->stats_rx_frames++;
    ctx->stats_rx_bytes += (uint32_t)length;
    ctx->perf[PERF_RX_FRAMES]++;
    if (length >= 14) {
        uint8_t *raw = (uint8_t *)p->payload;
        uint16_t ethertype = (((uint16_t)raw[12]) << 8) | raw[13];
        if (ethertype == 0x86dd && length >= 54) {
            uint8_t next_hdr = raw[20]; // 14 + 6
            if (next_hdr == 6) { // TCP
                ctx->stats_rx_tcp++;
                if (length >= 68) {
                    uint8_t tcp_flags = raw[67]; // 14 + 40 + 13
                    if (tcp_flags & 0x02) { // SYN
                        ctx->stats_rx_tcp_syn++;
                    }
                }
            } else if (next_hdr == 17) {
                ctx->stats_rx_udp++;
            } else if (next_hdr == 58) {
                ctx->stats_rx_icmp6++;
            }
        }
    }

    err_t err = ctx->ncm_netif.input(p, &ctx->ncm_netif);
    if (err != ERR_OK) {
        ctx->stats_rx_drops++;
        ctx->perf[PERF_RX_DROPS]++;
        pbuf_free(p);
    }
    return 1;
}

static void input_frame(JNIEnv *env, LwipContext *ctx, jbyteArray frameArr, jint offset, jint length) {
    if (ctx == NULL || !ctx->running || frameArr == NULL) return;
    pthread_mutex_lock(&lwip_core_lock);
    // With a Java exception pending no JNI call is allowed, and sending an
    // ACK makes one; the worker picks the leftovers up on its next pass.
    if (inject_frame_locked(env, ctx, frameArr, offset, length)) finish_input_batch(ctx);
    int wake = ctx->wake_needed;
    pthread_mutex_unlock(&lwip_core_lock);
    if (wake) trigger_wake(ctx);
}

JNIEXPORT void JNICALL
Java_com_shilapi_xcertplay_network_LwipNcmNative_nativeInputFrame(
        JNIEnv *env, jobject thiz, jlong handle, jbyteArray frameArr, jint length) {
    (void)thiz;
    input_frame(env, (LwipContext *)(intptr_t)handle, frameArr, 0, length);
}

JNIEXPORT void JNICALL
Java_com_shilapi_xcertplay_network_LwipNcmNative_nativeInputFrameRange(
        JNIEnv *env, jobject thiz, jlong handle, jbyteArray frameArr, jint offset, jint length) {
    (void)thiz;
    input_frame(env, (LwipContext *)(intptr_t)handle, frameArr, offset, length);
}

/* Must match LwipNcmNative.MAX_BATCH_FRAMES. */
#define MAX_INPUT_BATCH_FRAMES 64

/* Injects [count] frames that all live in [bufferArr]; rangesArr holds an
 * (offset, length) pair per frame. One core-lock hold, one loopback send and
 * one ACK flush for the whole batch. */
JNIEXPORT void JNICALL
Java_com_shilapi_xcertplay_network_LwipNcmNative_nativeInputFrames(
        JNIEnv *env, jobject thiz, jlong handle, jbyteArray bufferArr, jintArray rangesArr, jint count) {
    (void)thiz;
    LwipContext *ctx = (LwipContext *)(intptr_t)handle;
    if (ctx == NULL || !ctx->running || bufferArr == NULL || rangesArr == NULL) return;
    if (count <= 0 || count > MAX_INPUT_BATCH_FRAMES) return;
    if ((*env)->GetArrayLength(env, rangesArr) < count * 2) return;

    jint ranges[MAX_INPUT_BATCH_FRAMES * 2];
    (*env)->GetIntArrayRegion(env, rangesArr, 0, count * 2, ranges);

    pthread_mutex_lock(&lwip_core_lock);
    int ok = 1;
    for (int i = 0; ok && i < count; i++) {
        ok = inject_frame_locked(env, ctx, bufferArr, ranges[i * 2], ranges[i * 2 + 1]);
    }
    // With a Java exception pending no JNI call is allowed, and sending an
    // ACK makes one; the worker picks the leftovers up on its next pass.
    if (ok) finish_input_batch(ctx);
    int wake = ctx->wake_needed;
    pthread_mutex_unlock(&lwip_core_lock);
    if (wake) trigger_wake(ctx);
}

/* Copies the counters into out[] and resets the per-window ones. Peaks and
 * PCB state are sampled now; totals restart at zero after each read. */
JNIEXPORT jint JNICALL
Java_com_shilapi_xcertplay_network_LwipNcmNative_nativeGetPerfCounters(
        JNIEnv *env, jobject thiz, jlong handle, jlongArray out) {
    (void)thiz;
    LwipContext *ctx = (LwipContext *)(intptr_t)handle;
    if (ctx == NULL || out == NULL) return 0;
    jsize count = (*env)->GetArrayLength(env, out);
    if (count > PERF_COUNT) count = PERF_COUNT;

    jlong values[PERF_COUNT];
    pthread_mutex_lock(&lwip_core_lock);
    uint64_t refused = 0;
    uint64_t ooseq = 0;
    uint64_t min_wnd = 0xffffffffu;
    for (int i = 0; i < MAX_RELAY_CONNS; i++) {
        TcpRelayConn *conn = &ctx->tcp_conns[i];
        if (!conn->active || conn->lwip_pcb == NULL) continue;
        struct tcp_pcb *pcb = conn->lwip_pcb;
        if (pcb->refused_data != NULL) refused++;
#if TCP_QUEUE_OOSEQ
        if (pcb->ooseq != NULL) ooseq++;
#endif
        if (pcb->rcv_wnd < min_wnd) min_wnd = pcb->rcv_wnd;
    }
    ctx->perf[PERF_TCP_REFUSED_PCBS] = refused;
    ctx->perf[PERF_TCP_OOSEQ_PCBS] = ooseq;
    ctx->perf[PERF_TCP_MIN_RCV_WND] = min_wnd == 0xffffffffu ? 0 : min_wnd;
    for (int i = 0; i < PERF_COUNT; i++) {
        values[i] = (jlong)ctx->perf[i];
        ctx->perf[i] = 0;
    }
    pthread_mutex_unlock(&lwip_core_lock);

    (*env)->SetLongArrayRegion(env, out, 0, count, values);
    return count;
}

JNIEXPORT void JNICALL
Java_com_shilapi_xcertplay_network_LwipNcmNative_nativeRegisterPort(
        JNIEnv *env, jobject thiz, jlong handle, jint port, jboolean isTcp) {
    (void)env;
    (void)thiz;
    LwipContext *ctx = (LwipContext *)(intptr_t)handle;
    if (ctx == NULL || !ctx->running || port <= 0) return;

    pthread_mutex_lock(&lwip_core_lock);
    for (int i = 0; i < ctx->num_port_mappings; i++) {
        if (ctx->port_mappings[i].port == port &&
            ctx->port_mappings[i].is_tcp == (isTcp ? 1 : 0)) {
            pthread_mutex_unlock(&lwip_core_lock);
            return;
        }
    }
    if (ctx->num_port_mappings < MAX_PORT_MAPPINGS) {
        ctx->port_mappings[ctx->num_port_mappings].port = port;
        ctx->port_mappings[ctx->num_port_mappings].is_tcp = isTcp ? 1 : 0;
        ctx->port_mappings[ctx->num_port_mappings].active = 1;
        ctx->num_port_mappings++;
        LOGI("Registered port %d (tcp=%d) for relay", port, isTcp ? 1 : 0);
    }
    pthread_mutex_unlock(&lwip_core_lock);

    trigger_wake(ctx);
}

JNIEXPORT jboolean JNICALL
Java_com_shilapi_xcertplay_network_LwipNcmNative_nativeSendUdp(
        JNIEnv *env, jobject thiz, jlong handle, jint localPort, jint remotePort,
        jbyteArray payloadArr) {
    (void)thiz;
    LwipContext *ctx = (LwipContext *)(intptr_t)handle;
    if (ctx == NULL || !ctx->running || payloadArr == NULL ||
        localPort <= 0 || localPort > 65535 || remotePort <= 0 || remotePort > 65535) return JNI_FALSE;
    jsize length = (*env)->GetArrayLength(env, payloadArr);
    if (length <= 0 || length > 65535) return JNI_FALSE;
    jbyte *bytes = (*env)->GetByteArrayElements(env, payloadArr, NULL);
    if (bytes == NULL) return JNI_FALSE;

    jboolean sent = JNI_FALSE;
    pthread_mutex_lock(&lwip_core_lock);
    for (int i = 0; i < MAX_UDP_RELAYS; i++) {
        UdpRelay *relay = &ctx->udp_relays[i];
        if (!relay->active || relay->port != localPort || relay->lwip_pcb == NULL || !relay->has_peer) continue;
        struct pbuf *p = pbuf_alloc(PBUF_TRANSPORT, (u16_t)length, PBUF_RAM);
        if (p != NULL) {
            pbuf_take(p, bytes, (u16_t)length);
            sent = udp_sendto(relay->lwip_pcb, p, &relay->peer_ip, (u16_t)remotePort) == ERR_OK
                ? JNI_TRUE : JNI_FALSE;
            pbuf_free(p);
        }
        break;
    }
    pthread_mutex_unlock(&lwip_core_lock);
    (*env)->ReleaseByteArrayElements(env, payloadArr, bytes, JNI_ABORT);
    trigger_wake(ctx);
    return sent;
}

JNIEXPORT void JNICALL
Java_com_shilapi_xcertplay_network_LwipNcmNative_nativeDestroy(
        JNIEnv *env, jobject thiz, jlong handle) {
    (void)thiz;
    LwipContext *ctx = (LwipContext *)(intptr_t)handle;
    if (ctx == NULL) return;

    LOGI("Destroying LwipContext");
    ctx->running = 0;
    trigger_wake(ctx);
    pthread_join(ctx->thread, NULL);

    pthread_mutex_lock(&lwip_core_lock);

    for (int i = 0; i < MAX_RELAY_CONNS; i++) {
        close_tcp_relay_conn(ctx, &ctx->tcp_conns[i]);
    }

    for (int i = 0; i < MAX_UDP_RELAYS; i++) {
        close_udp_relay(ctx, &ctx->udp_relays[i]);
    }

    for (int i = 0; i < ctx->num_listen_pcbs; i++) {
        if (ctx->listen_pcbs[i] != NULL) {
            tcp_close(ctx->listen_pcbs[i]);
            ctx->listen_pcbs[i] = NULL;
        }
    }

    netif_remove(&ctx->ncm_netif);

#ifndef _WIN32
    if (ctx->wake_pipe[0] >= 0) close(ctx->wake_pipe[0]);
    if (ctx->wake_pipe[1] >= 0) close(ctx->wake_pipe[1]);
#endif

    pthread_mutex_unlock(&lwip_core_lock);

    if (ctx->bridge_obj_global != NULL) {
        (*env)->DeleteGlobalRef(env, ctx->bridge_obj_global);
        ctx->bridge_obj_global = NULL;
    }

    free(ctx);
}

JNIEXPORT jstring JNICALL
Java_com_shilapi_xcertplay_network_LwipNcmNative_nativeGetSnapshot(
        JNIEnv *env, jobject thiz, jlong handle) {
    (void)thiz;
    LwipContext *ctx = (LwipContext *)(intptr_t)handle;
    if (ctx == NULL) return (*env)->NewStringUTF(env, "lwip handle null");

    pthread_mutex_lock(&lwip_core_lock);
    int active_tcp = 0;
    for (int i = 0; i < MAX_RELAY_CONNS; i++) {
        if (ctx->tcp_conns[i].active) active_tcp++;
    }
    int active_udp = 0;
    for (int i = 0; i < MAX_UDP_RELAYS; i++) {
        if (ctx->udp_relays[i].active) active_udp++;
    }

    char buf[512];
    snprintf(buf, sizeof(buf),
             "listeners=%d tcp_act=%d (accepted=%u closed=%u) udp_relays=%d "
             "rx_frames=%u (tcp=%u syn=%u udp=%u icmp6=%u drop=%u) tx_frames=%u tx_bytes=%u",
             ctx->num_listen_pcbs,
             active_tcp, ctx->stats_tcp_conns_accepted, ctx->stats_tcp_conns_closed,
             active_udp,
             ctx->stats_rx_frames, ctx->stats_rx_tcp, ctx->stats_rx_tcp_syn,
             ctx->stats_rx_udp, ctx->stats_rx_icmp6, ctx->stats_rx_drops,
             ctx->stats_tx_frames, ctx->stats_tx_bytes);
    pthread_mutex_unlock(&lwip_core_lock);

    return (*env)->NewStringUTF(env, buf);
}
