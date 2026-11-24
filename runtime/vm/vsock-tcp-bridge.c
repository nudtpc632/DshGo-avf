// Guest-side vsock<->TCP bridge. Static aarch64 binary.
// Listen mode (default): vsock:<vport> -> 127.0.0.1:<tport> (dsh web, vm-ctl);
// the host connects with AF_VSOCK (JNI libvsock_client.so in the App).
// Connect mode (-c): 127.0.0.1:<tport> -> vsock CID 2:<vport> (App egress /
// phone listeners). Node in the guest has no AF_VSOCK, so guest-originated
// hops use this bridge instead of a JS socket.
// build: aarch64-linux-gnu-gcc -static -O2 -o vsock-tcp-bridge vsock-tcp-bridge.c
#include <stdio.h>
#include <stdlib.h>
#include <string.h>
#include <unistd.h>
#include <errno.h>
#include <pthread.h>
#include <sys/socket.h>
#include <netinet/in.h>
#include <arpa/inet.h>
#include <linux/vm_sockets.h>

static unsigned g_tport;
static unsigned g_vport;
static int g_connect_mode;

// Per-connection state shared by the two pump threads. Each pump owns one
// direction; on EOF it half-closes the peer's write side and marks itself
// done. The last pump to finish closes both fds and frees the state, so a
// client that half-closes after sending its request still receives the full
// response (the old code did SHUT_RDWR on both sockets at first EOF, which
// raced the response and dropped it intermittently).
struct conn {
    int vfd, tfd;
    pthread_mutex_t mu;
    int done;
};

static void conn_finish(struct conn *cn, int a, int b) {
    shutdown(b, SHUT_WR);
    pthread_mutex_lock(&cn->mu);
    int last = (++cn->done == 2);
    pthread_mutex_unlock(&cn->mu);
    if (last) {
        close(cn->vfd);
        close(cn->tfd);
        pthread_mutex_destroy(&cn->mu);
        free(cn);
    }
    (void)a;
}

static void *pump(void *arg) {
    struct conn *cn = arg;
    int a = cn->vfd, b = cn->tfd;
    char buf[16384];
    for (;;) {
        ssize_t n = read(a, buf, sizeof buf);
        if (n <= 0) break;
        ssize_t off = 0;
        while (off < n) {
            ssize_t w = write(b, buf + off, n - off);
            if (w <= 0) { n = 0; break; }
            off += w;
        }
        if (n <= 0) break;
    }
    conn_finish(cn, a, b);
    return NULL;
}

static void *pump_rev(void *arg) {
    struct conn *cn = arg;
    int a = cn->tfd, b = cn->vfd;
    char buf[16384];
    for (;;) {
        ssize_t n = read(a, buf, sizeof buf);
        if (n <= 0) break;
        ssize_t off = 0;
        while (off < n) {
            ssize_t w = write(b, buf + off, n - off);
            if (w <= 0) { n = 0; break; }
            off += w;
        }
        if (n <= 0) break;
    }
    conn_finish(cn, a, b);
    return NULL;
}

int main(int argc, char **argv) {
    int argi = 1;
    if (argc > 1 && strcmp(argv[1], "-c") == 0) { g_connect_mode = 1; argi = 2; }
    if (argc - argi != 2) { fprintf(stderr, "usage: %s [-c] <port-a> <port-b>\n", argv[0]); return 2; }
    unsigned pa = (unsigned)atoi(argv[argi]);
    unsigned pb = (unsigned)atoi(argv[argi + 1]);
    int ls;
    if (g_connect_mode) {
        // Listen on loopback TCP, dial the host over vsock CID 2.
        g_tport = pa;
        g_vport = pb;
        ls = socket(AF_INET, SOCK_STREAM, 0);
        if (ls < 0) { perror("socket tcp"); return 1; }
        int one = 1;
        setsockopt(ls, SOL_SOCKET, SO_REUSEADDR, &one, sizeof one);
        struct sockaddr_in la;
        memset(&la, 0, sizeof la);
        la.sin_family = AF_INET;
        la.sin_port = htons((unsigned short)g_tport);
        la.sin_addr.s_addr = htonl(INADDR_LOOPBACK);
        if (bind(ls, (struct sockaddr *)&la, sizeof la) < 0) { perror("bind tcp"); return 1; }
        if (listen(ls, 16) < 0) { perror("listen tcp"); return 1; }
        fprintf(stderr, "vsock-tcp-bridge: 127.0.0.1:%u -> vsock host:%u\n", g_tport, g_vport);
    } else {
        g_vport = pa;
        g_tport = pb;
        ls = socket(AF_VSOCK, SOCK_STREAM, 0);
        if (ls < 0) { perror("socket vsock"); return 1; }
        struct sockaddr_vm sa;
        memset(&sa, 0, sizeof sa);
        sa.svm_family = AF_VSOCK;
        sa.svm_cid = VMADDR_CID_ANY;
        sa.svm_port = g_vport;
        if (bind(ls, (struct sockaddr *)&sa, sizeof sa) < 0) { perror("bind vsock"); return 1; }
        if (listen(ls, 16) < 0) { perror("listen vsock"); return 1; }
        fprintf(stderr, "vsock-tcp-bridge: vsock:%u -> 127.0.0.1:%u\n", g_vport, g_tport);
    }
    for (;;) {
        int c = accept(ls, NULL, NULL);
        if (c < 0) { perror("accept"); continue; }
        int t;
        if (g_connect_mode) {
            t = socket(AF_VSOCK, SOCK_STREAM, 0);
            if (t < 0) { perror("socket vsock"); close(c); continue; }
            struct sockaddr_vm va;
            memset(&va, 0, sizeof va);
            va.svm_family = AF_VSOCK;
            va.svm_cid = VMADDR_CID_HOST;
            va.svm_port = g_vport;
            if (connect(t, (struct sockaddr *)&va, sizeof va) < 0) {
                perror("connect vsock"); close(c); close(t); continue;
            }
        } else {
            t = socket(AF_INET, SOCK_STREAM, 0);
            if (t < 0) { perror("socket tcp"); close(c); continue; }
            struct sockaddr_in ta;
            memset(&ta, 0, sizeof ta);
            ta.sin_family = AF_INET;
            ta.sin_port = htons((unsigned short)g_tport);
            ta.sin_addr.s_addr = htonl(INADDR_LOOPBACK);
            if (connect(t, (struct sockaddr *)&ta, sizeof ta) < 0) {
                perror("connect tcp"); close(c); close(t); continue;
            }
        }
        struct conn *cn = calloc(1, sizeof *cn);
        if (!cn) { close(c); close(t); continue; }
        cn->vfd = g_connect_mode ? t : c;
        cn->tfd = g_connect_mode ? c : t;
        pthread_mutex_init(&cn->mu, NULL);
        pthread_t th1, th2;
        if (pthread_create(&th1, NULL, pump, cn) != 0) { close(c); close(t); free(cn); continue; }
        pthread_detach(th1);
        if (pthread_create(&th2, NULL, pump_rev, cn) != 0) {
            // th1 is running; mark the reverse direction done so th1's
            // finish closes both fds.
            shutdown(t, SHUT_WR);
            pthread_mutex_lock(&cn->mu);
            cn->done++;
            pthread_mutex_unlock(&cn->mu);
            continue;
        }
        pthread_detach(th2);
    }
    return 0;
}