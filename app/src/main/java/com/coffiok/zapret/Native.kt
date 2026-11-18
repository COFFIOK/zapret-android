package com.coffiok.zapret

/** Движок ByeDPI (hufrea/byedpi) и туннель hev-socks5-tunnel — в libbyedpi.so / libhev-socks5-tunnel.so. */
object Native {
    init { System.loadLibrary("byedpi") }

    /** Блокирует поток, пока прокси работает. Вызывается один раз за процесс. */
    external fun startProxy(args: Array<String>): Int
    external fun stopProxy(): Int

    /** Блокирует поток, пока работает туннель. */
    external fun startTunnel(config: String, fd: Int): Int
    external fun stopTunnel()
}
