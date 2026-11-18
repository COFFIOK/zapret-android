// Запуск/остановка ByeDPI (hufrea/byedpi, MIT) из Kotlin. По мотивам ByeByeDPI (romanvht/ByeByeDPI, GPL-3.0).
#include <errno.h>
#include <jni.h>
#include <pthread.h>
#include <stdlib.h>
#include <string.h>
#include <sys/socket.h>

#include "byedpi/error.h"
#include "main.h"

extern int server_fd;
static pthread_mutex_t proxy_mutex = PTHREAD_MUTEX_INITIALIZER;
static int proxy_started;
static int proxy_running;

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
