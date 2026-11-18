package com.coffiok.zapret

import android.app.ActivityManager
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.net.VpnService
import android.os.Build
import android.os.ParcelFileDescriptor
import android.os.Process
import android.service.quicksettings.TileService
import androidx.core.app.NotificationCompat
import androidx.core.app.ServiceCompat
import androidx.core.content.ContextCompat
import java.net.InetSocketAddress
import java.net.Socket
import kotlin.concurrent.thread

/**
 * Обход на телефоне: ByeDPI работает как SOCKS5-прокси на 127.0.0.1, а VPN без сервера (всё внутри телефона)
 * заворачивает в него трафик всех приложений через hev-socks5-tunnel. Само приложение в VPN не входит,
 * иначе трафик ByeDPI ходил бы по кругу.
 * Сервис живёт в отдельном процессе «:vpn»: ByeDPI можно запустить только один раз за процесс,
 * поэтому после остановки процесс завершается, а следующий запуск получает новый.
 */
class ZapretVpnService : VpnService() {
    companion object {
        const val ACTION_START = "com.coffiok.zapret.START"
        const val ACTION_STOP = "com.coffiok.zapret.STOP"
        const val STATUS = "com.coffiok.zapret.STATUS"
        private const val CHANNEL = "zapret"
        private const val NOTIF_ID = 1
        private const val PORT = 1080

        fun start(ctx: Context) {
            ContextCompat.startForegroundService(ctx, Intent(ctx, ZapretVpnService::class.java).setAction(ACTION_START))
        }

        fun stop(ctx: Context) {
            if (running(ctx)) ctx.startService(Intent(ctx, ZapretVpnService::class.java).setAction(ACTION_STOP))
        }

        @Suppress("DEPRECATION")
        fun running(ctx: Context): Boolean {
            val am = ctx.getSystemService(Context.ACTIVITY_SERVICE) as ActivityManager
            return am.getRunningServices(Int.MAX_VALUE).any { it.service.className == ZapretVpnService::class.java.name && it.started }
        }
    }

    @Volatile private var stopping = false
    @Volatile private var started = false
    private var tun: ParcelFileDescriptor? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_STOP) { stopAll(null); return START_NOT_STICKY }
        // ACTION_START, а также запуск системой (постоянный VPN) — action == android.net.VpnService
        foreground()
        if (!started) { started = true; thread(name = "zapret-start") { startAll() } }
        return START_NOT_STICKY
    }

    override fun onRevoke() { stopAll(null) }       // включили другой VPN или отозвали разрешение

    override fun onDestroy() {
        stopAll(null)
        super.onDestroy()
        Process.killProcess(Process.myPid())
    }

    private fun startAll() {
        try {
            val prefs = Prefs(this)
            val st = prefs.current()
            val args = arrayOf("ciadpi", "--ip", "127.0.0.1", "--port", PORT.toString()) + prefs.args()
            Native.setDropQuic(prefs.blockQuic)
            // IPv6 всегда заворачиваем в туннель, иначе он идёт мимо обхода. Выключен в настройках — IPv6 сразу отклоняется, приложения уходят на IPv4
            Native.setDropV6(!prefs.ipv6)
            val v6 = try {
                val cm = getSystemService(android.net.ConnectivityManager::class.java)
                cm.getLinkProperties(cm.activeNetwork)?.linkAddresses?.any { val a = it.address; a is java.net.Inet6Address && !a.isLinkLocalAddress && !a.isSiteLocalAddress && (a.address[0].toInt() and 0xFE) != 0xFC } == true
            } catch (e: Exception) { false }
            status("starting", st.name)
            Diag.log(this, "старт: ${st.name} ${st.args} · DNS ${prefs.dns} · IPv6 ${prefs.ipv6}/${v6} · QUIC ${if (prefs.blockQuic) "закрыт" else "открыт"}")
            thread(name = "byedpi") {
                val code = try { Native.startProxy(args) } catch (e: Throwable) { -1 }
                if (!stopping) stopAll("Стратегия ${st.name} не запустилась (код $code). Попробуй другую.")
            }
            if (!waitPort()) { stopAll("Стратегия ${st.name} не запустилась. Попробуй другую."); return }
            if (stopping) return

            val b = Builder()
                .setSession("Zapret")
                .addAddress("10.10.10.10", 32)
                .addRoute("0.0.0.0", 0)
                .addDnsServer(prefs.dns)
                .setConfigureIntent(PendingIntent.getActivity(this, 0, Intent(this, MainActivity::class.java), PendingIntent.FLAG_IMMUTABLE))
            b.addAddress("fd00::1", 128); b.addRoute("::", 0)
            b.addDisallowedApplication(packageName)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) b.setMetered(false)
            val fd = b.establish() ?: run { stopAll("Android не дал создать VPN"); return }
            tun = fd

            val cfg = "tunnel:\n  mtu: 8500\nmisc:\n  task-stack-size: 81920\nsocks5:\n  address: 127.0.0.1\n  port: $PORT\n  udp: udp\n"
            thread(name = "tunnel") { try { Native.startTunnel(cfg, fd.fd) } catch (e: Throwable) { if (!stopping) stopAll("Туннель не запустился") } }
            if (prefs.tgProxy) TgProxy.start(TgProxy.secret(this)) { Diag.log(this, it) }
            status("on", st.name)
            updateNotification(st.name)
            Diag.log(this, "обход включён")
        } catch (e: Throwable) {
            stopAll("Не удалось включить: ${e.message}")
        }
    }

    private fun waitPort(): Boolean {
        repeat(50) {
            if (stopping) return false
            try { Socket().use { it.connect(InetSocketAddress("127.0.0.1", PORT), 100) }; return true } catch (e: Exception) { Thread.sleep(100) }
        }
        return false
    }

    @Synchronized
    private fun stopAll(error: String?) {
        if (stopping) return
        stopping = true
        Diag.log(this, if (error != null) "ошибка: $error" else "обход выключен")
        TgProxy.stop()
        try { Native.stopTunnel() } catch (e: Throwable) { }
        try { Native.stopProxy() } catch (e: Throwable) { }
        try { tun?.close() } catch (e: Throwable) { }
        tun = null
        if (error != null) status("error", error)
        status("off", null)
        ServiceCompat.stopForeground(this, ServiceCompat.STOP_FOREGROUND_REMOVE)
        stopSelf()
    }

    private fun status(state: String, info: String?) {
        sendBroadcast(Intent(STATUS).setPackage(packageName).putExtra("state", state).putExtra("info", info))
        try { TileService.requestListeningState(this, ComponentName(this, ZapretTile::class.java)) } catch (e: Throwable) { }
    }

    // ---- уведомление (без него Android не даёт держать VPN в фоне) ----
    private fun notification(text: String): Notification {
        val nm = getSystemService(NotificationManager::class.java)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O && nm.getNotificationChannel(CHANNEL) == null)
            nm.createNotificationChannel(NotificationChannel(CHANNEL, "Обход", NotificationManager.IMPORTANCE_LOW))
        val open = PendingIntent.getActivity(this, 0, Intent(this, MainActivity::class.java), PendingIntent.FLAG_IMMUTABLE)
        val stop = PendingIntent.getService(this, 1, Intent(this, ZapretVpnService::class.java).setAction(ACTION_STOP), PendingIntent.FLAG_IMMUTABLE)
        return NotificationCompat.Builder(this, CHANNEL)
            .setSmallIcon(R.drawable.ic_stat)
            .setContentTitle("Zapret")
            .setContentText(text)
            .setContentIntent(open)
            .addAction(0, "Выключить", stop)
            .setOngoing(true)
            .setSilent(true)
            .build()
    }

    private fun foreground() {
        val n = notification("Включаю обход…")
        val type = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) ServiceInfo.FOREGROUND_SERVICE_TYPE_SYSTEM_EXEMPTED else 0
        ServiceCompat.startForeground(this, NOTIF_ID, n, type)
    }

    private fun updateNotification(name: String) {
        getSystemService(NotificationManager::class.java).notify(NOTIF_ID, notification("Обход включён · стратегия $name"))
    }
}
