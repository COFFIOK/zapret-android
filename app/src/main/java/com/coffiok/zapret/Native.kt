package com.coffiok.zapret

/** Движок ByeDPI (hufrea/byedpi) и туннель hev-socks5-tunnel — в libbyedpi.so / libhev-socks5-tunnel.so. */
object Native {
    init { System.loadLibrary("byedpi") }

    /** Блокирует поток, пока прокси работает. Вызывается один раз за процесс. */
    external fun startProxy(args: Array<String>): Int
    external fun stopProxy(): Int

    /** Не пускать UDP на порт 443 (QUIC): приложения переходят на обычный HTTPS. */
    external fun setDropQuic(on: Boolean)

    /** Не соединять по IPv6: приложения сразу переходят на IPv4. */
    external fun setDropV6(on: Boolean)

    /** Блокирует поток, пока работает туннель. */
    external fun startTunnel(config: String, fd: Int): Int
    external fun stopTunnel()
}
