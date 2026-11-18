package com.coffiok.zapret

import android.content.Context
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.os.Build
import android.provider.Settings
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.net.HttpURLConnection
import java.net.Inet6Address
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.Proxy
import java.net.URL
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.UUID
import java.util.concurrent.Executors

/** Журнал событий (пишут оба процесса) и отчёт об ошибке для автора. */
object Diag {
    private val fmt = SimpleDateFormat("dd.MM HH:mm:ss", Locale.US)

    fun log(ctx: Context, msg: String) {
        try {
            val f = File(ctx.filesDir, "zapret.log")
            if (f.length() > 64 * 1024) f.writeText(f.readText().takeLast(32 * 1024))
            f.appendText("${fmt.format(Date())} $msg\n")
        } catch (e: Exception) { }
    }

    private fun tail(ctx: Context, n: Int): String = try { File(ctx.filesDir, "zapret.log").readLines().takeLast(n).joinToString("\n") } catch (e: Exception) { "" }

    /** Собирает всё, что помогает понять, почему не работает. Долго (проверки сайтов) — вызывать не в главном потоке. */
    fun report(ctx: Context): String {
        val p = Prefs(ctx)
        val sb = StringBuilder()
        sb.append("Zapret Android ${BuildConfig.VERSION_NAME} · Android ${Build.VERSION.RELEASE} (SDK ${Build.VERSION.SDK_INT}) · ${Build.MANUFACTURER} ${Build.MODEL} · ${Build.SUPPORTED_ABIS.firstOrNull()}\n")
        val on = ZapretVpnService.running(ctx)
        val st = p.current()
        sb.append("Обход: ${if (on) "включён" else "выключен"} · ${st.name}: ${st.args} · DNS ${p.dns} · IPv6 через обход: ${if (p.ipv6) "да" else "нет"} · QUIC: ${if (p.blockQuic) "закрыт" else "открыт"} · TG-прокси: ${if (p.tgProxy) "да" else "нет"}\n")

        // сеть
        try {
            val cm = ctx.getSystemService(ConnectivityManager::class.java)
            for (n in cm.allNetworks) {
                val c = cm.getNetworkCapabilities(n) ?: continue
                val lp = cm.getLinkProperties(n)
                val t = listOfNotNull(
                    "wifi".takeIf { c.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) },
                    "моб".takeIf { c.hasTransport(NetworkCapabilities.TRANSPORT_CELLULAR) },
                    "vpn".takeIf { c.hasTransport(NetworkCapabilities.TRANSPORT_VPN) }).joinToString("+")
                val v6 = lp?.linkAddresses?.any { it.address is Inet6Address && !it.address.isLinkLocalAddress } == true
                val pdns = if (Build.VERSION.SDK_INT >= 28 && lp != null) (if (lp.isPrivateDnsActive) "частный DNS ${lp.privateDnsServerName ?: "авто"}" else "") else ""
                sb.append("Сеть: $t · IPv6: ${if (v6) "есть" else "нет"} · ${lp?.interfaceName ?: "?"} ${pdns}\n")
            }
            sb.append("Частный DNS в настройках: ${Settings.Global.getString(ctx.contentResolver, "private_dns_mode") ?: "?"}\n")
        } catch (e: Exception) { sb.append("Сеть: ошибка ${e.message}\n") }

        // адреса и проверки: напрямую (как без Zapret) и через движок обхода (если включён)
        val sites = listOf("www.youtube.com", "i.ytimg.com", "redirector.googlevideo.com", Tester.VIDEO, "discord.com", "gateway.discord.gg", "web.telegram.org", "www.instagram.com")
        try {
            val a = InetAddress.getAllByName("www.youtube.com").joinToString(" ") { it.hostAddress ?: "" }
            sb.append("DNS youtube: ${a.take(200)}\n")
        } catch (e: Exception) { sb.append("DNS youtube: не найден (${e.message})\n") }
        // обход выключен — проверяем через временную копию движка с выбранной стратегией
        val tmp = if (on) null else Tester.spawn(ctx, p.resolve(p.current().args), 21999)
        val viaPort = if (on) 1080 else if (tmp != null) 21999 else 0
        try { sb.append("Видеосервер: ${Tester.videoNode(if (viaPort > 0) Proxy(Proxy.Type.SOCKS, InetSocketAddress("127.0.0.1", viaPort)) else null) ?: "не найден"}\n") } catch (e: Exception) { }
        val pool = Executors.newFixedThreadPool(8)
        val direct = sites.map { s -> pool.submit<String> { check(s, null) } }
        val via = if (viaPort > 0) sites.map { s -> pool.submit<String> { check(s, Proxy(Proxy.Type.SOCKS, InetSocketAddress("127.0.0.1", viaPort))) } } else null
        sb.append(if (on) "Напрямую / через обход:\n" else "Напрямую / через стратегию (обход выключен):\n")
        sites.forEachIndexed { i, s -> sb.append("  $s: ${direct[i].get()}${if (via != null) " / " + via[i].get() else ""}\n") }
        pool.shutdown()
        tmp?.destroy()

        val top = Tester.results.take(3).joinToString("; ") { "${it.strategy.name} ${it.ok}/${it.total}" }
        if (top.isNotBlank()) sb.append("Последний тест: $top\n")
        sb.append("Журнал:\n").append(tail(ctx, 40)).append("\n")
        try {
            val lc = Runtime.getRuntime().exec(arrayOf("logcat", "-d", "-t", "150", "-v", "brief", "*:W")).inputStream.bufferedReader().readText()
            sb.append("logcat:\n").append(lc.takeLast(2500))
        } catch (e: Exception) { }
        return sb.toString().take(6000)
    }

    private fun check(site: String, proxy: Proxy?): String = if (site == Tester.VIDEO) {
        val t0 = System.currentTimeMillis()
        if (Tester.videoOk(proxy)) "ok ${System.currentTimeMillis() - t0}мс" else "✕"
    } else try {
        val t0 = System.currentTimeMillis()
        val c = (if (proxy != null) URL("https://$site/").openConnection(proxy) else URL("https://$site/").openConnection()) as HttpURLConnection
        c.connectTimeout = 5000; c.readTimeout = 5000; c.instanceFollowRedirects = false
        val code = c.responseCode; c.disconnect()
        "ok $code ${System.currentTimeMillis() - t0}мс"
    } catch (e: Exception) { "✕ " + (e.javaClass.simpleName + " " + (e.message ?: "")).take(60) }
}

/** Чат с автором (тот же сервер, что у Zapret GUI на ПК): автор отвечает из Zapret Dev. */
object Chat {
    private const val SERVER = "https://zapret-share.chodaniel07.workers.dev"
    class Msg(val n: Int, val at: String, val dev: Boolean, val text: String)

    fun id(ctx: Context): String {
        val sp = ctx.getSharedPreferences("zapret", Context.MODE_PRIVATE)
        var id = sp.getString("tid", null)
        if (id == null || !Regex("^[0-9a-f]{32}$").matches(id)) { id = UUID.randomUUID().toString().replace("-", ""); sp.edit().putString("tid", id).commit() }
        return id
    }

    fun nick(ctx: Context) = ctx.getSharedPreferences("zapret", Context.MODE_PRIVATE).getString("nick", "") ?: ""
    fun setNick(ctx: Context, n: String) = ctx.getSharedPreferences("zapret", Context.MODE_PRIVATE).edit().putString("nick", n.trim()).commit()

    /** null — отправлено, иначе текст ошибки. */
    fun send(ctx: Context, text: String, kind: String, log: String?): String? {
        val p = Prefs(ctx)
        val body = JSONObject().put("id", id(ctx)).put("v", BuildConfig.VERSION_NAME).put("z", "android")
            .put("os", "Android ${Build.VERSION.RELEASE}").put("lang", "ru").put("kind", kind).put("text", text)
            .put("nick", nick(ctx)).put("strat", p.current().name).put("on", ZapretVpnService.running(ctx))
        if (log != null) body.put("log", log)
        val (st, res) = post("/chat/send", body)
        return when {
            st == 200 -> null
            st == 403 -> "Ты не можешь писать в чат"
            st == 429 -> "Слишком часто — подожди немного"
            else -> "Сервер не ответил ($st)"
        }
    }

    fun messages(ctx: Context): List<Msg> {
        val (st, res) = post("/chat/get", JSONObject().put("id", id(ctx)).put("after", 0).put("seen", true))
        if (st != 200) return emptyList()
        val a = JSONObject(res).optJSONArray("msgs") ?: JSONArray()
        return (0 until a.length()).map { val m = a.getJSONObject(it); Msg(m.optInt("n"), m.optString("at"), m.optBoolean("dev", m.optInt("dev", 0) == 1), m.optString("text")) }
    }

    private fun post(path: String, body: JSONObject): Pair<Int, String> = try {
        val c = URL(SERVER + path).openConnection() as HttpURLConnection
        c.requestMethod = "POST"; c.connectTimeout = 12000; c.readTimeout = 12000; c.doOutput = true
        c.setRequestProperty("Content-Type", "application/json; charset=utf-8")
        c.setRequestProperty("User-Agent", "ZapretAndroid/" + BuildConfig.VERSION_NAME)
        c.outputStream.use { it.write(body.toString().toByteArray()) }
        val st = c.responseCode
        val t = (if (st in 200..299) c.inputStream else c.errorStream)?.bufferedReader()?.use { it.readText() } ?: ""
        c.disconnect(); st to t
    } catch (e: Exception) { 0 to "" }
}
