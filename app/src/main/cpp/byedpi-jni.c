// Запуск/остановка ByeDPI (hufrea/byedpi, MIT) из Kotlin. По мотивам ByeByeDPI (romanvht/ByeByeDPI, GPL-3.0).
#include <errno.h>
#include <jni.h>
#include <pthread.h>
#include <stdlib.h>
#include <string.h>
#include <sys/socket.h>
#include <netinet/in.h>

#include "byedpi/error.h"
#include "main.h"

extern int server_fd;
static pthread_mutex_t proxy_mutex = PTHREAD_MUTEX_INITIALIZER;
static int proxy_started;
static int proxy_running;

// «Закрыть QUIC»: UDP на порт 443 не соединяем — приложения переходят на обычный HTTPS (его ByeDPI обходит)
static volatile int drop_quic = 0;
static volatile int drop_v6 = 0;
int __real_connect(int fd, const struct sockaddr *a, socklen_t l);
int __wrap_connect(int fd, const struct sockaddr *a, socklen_t l) {
    if (drop_v6 && a && a->sa_family == AF_INET6) { errno = ENETUNREACH; return -1; }
    if (drop_quic && a && (a->sa_family == AF_INET || a->sa_family == AF_INET6)) {
        int type = 0; socklen_t tl = sizeof(type);
        if (getsockopt(fd, SOL_SOCKET, SO_TYPE, &type, &tl) == 0 && type == SOCK_DGRAM) {
            unsigned short port = a->sa_family == AF_INET ? ntohs(((const struct sockaddr_in *) a)->sin_port)
                                                          : ntohs(((const struct sockaddr_in6 *) a)->sin6_port);
            if (port == 443) { errno = ECONNREFUSED; return -1; }
        }
    }
    return __real_connect(fd, a, l);
}

JNIEXPORT void JNICALL
Java_com_coffiok_zapret_Native_setDropV6(JNIEnv *env, jobject thiz, jboolean on) {
    drop_v6 = on ? 1 : 0;
}

JNIEXPORT void JNICALL
Java_com_coffiok_zapret_Native_setDropQuic(JNIEnv *env, jobject thiz, jboolean on) {
    drop_quic = on ? 1 : 0;
}

int __wrap_daemon(int nochdir, int noclose) {
    errno = EPERM;
    return -1;
}

JNIEXPORT jint JNICALL
Java_com_coffiok_zapret_Native_startProxy(JNIEnv *env, jobject thiz, jobjectArray args) {
    pthread_mutex_lock(&proxy_mutex);
    if (proxy_started) {          // ByeDPI запускается один раз за процесс — сервис живёт в своём процессе
        pthread_mutex_unlock(&proxy_mutex);
        return -1;
    }
    proxy_started = 1;
    pthread_mutex_unlock(&proxy_mutex);

    int argc = (*env)->GetArrayLength(env, args);
    char **argv = calloc(argc + 1, sizeof(char *));
    int result = -1;
    if (!argv) return result;

    for (int i = 0; i < argc; i++) {
        jstring arg = (jstring) (*env)->GetObjectArrayElement(env, args, i);
        if (!arg) goto cleanup;
        const char *s = (*env)->GetStringUTFChars(env, arg, NULL);
        if (s) {
            argv[i] = strdup(s);
            (*env)->ReleaseStringUTFChars(env, arg, s);
        }
        (*env)->DeleteLocalRef(env, arg);
        if (!argv[i]) goto cleanup;
    }

    pthread_mutex_lock(&proxy_mutex);
    server_fd = -1;
    proxy_running = 1;
    pthread_mutex_unlock(&proxy_mutex);

    result = main(argc, argv);

    pthread_mutex_lock(&proxy_mutex);
    proxy_running = 0;
    pthread_mutex_unlock(&proxy_mutex);

cleanup:
    for (int i = 0; i < argc; i++) free(argv[i]);
    free(argv);
    return result;
}

JNIEXPORT jint JNICALL
Java_com_coffiok_zapret_Native_stopProxy(JNIEnv *env, jobject thiz) {
    pthread_mutex_lock(&proxy_mutex);
    int result = proxy_running && server_fd >= 0 ? shutdown(server_fd, SHUT_RDWR) : -1;
    pthread_mutex_unlock(&proxy_mutex);
    return result;
}
