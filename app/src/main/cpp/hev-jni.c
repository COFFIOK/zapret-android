// Туннель hev-socks5-tunnel (heiher, MIT): весь трафик телефона из VPN → SOCKS5 ByeDPI.
// По мотивам ByeByeDPI (romanvht/ByeByeDPI, GPL-3.0).
#include <dlfcn.h>
#include <jni.h>
#include <pthread.h>

static pthread_once_t tunnel_once = PTHREAD_ONCE_INIT;
static int (*tunnel_main)(const unsigned char *, unsigned int, int);
static void (*tunnel_quit)(void);

static void load_tunnel(void) {
    void *lib = dlopen("libhev-socks5-tunnel.so", RTLD_NOW | RTLD_LOCAL);
    if (!lib) return;
    tunnel_main = (int (*)(const unsigned char *, unsigned int, int)) dlsym(lib, "hev_socks5_tunnel_main_from_str");
    tunnel_quit = (void (*)(void)) dlsym(lib, "hev_socks5_tunnel_quit");
}

JNIEXPORT jint JNICALL
Java_com_coffiok_zapret_Native_startTunnel(JNIEnv *env, jobject thiz, jstring config, jint fd) {
    pthread_once(&tunnel_once, load_tunnel);
    if (!tunnel_main || !tunnel_quit) return -1;
    const char *cfg = (*env)->GetStringUTFChars(env, config, NULL);
    if (!cfg) return -1;
    unsigned int len = (unsigned int) (*env)->GetStringUTFLength(env, config);
    int result = tunnel_main((const unsigned char *) cfg, len, fd);
    (*env)->ReleaseStringUTFChars(env, config, cfg);
    return result;
}

JNIEXPORT void JNICALL
Java_com_coffiok_zapret_Native_stopTunnel(JNIEnv *env, jobject thiz) {
    pthread_once(&tunnel_once, load_tunnel);
    if (tunnel_quit) tunnel_quit();
}
