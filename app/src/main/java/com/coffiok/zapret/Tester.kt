package com.coffiok.zapret

import android.content.Context
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import org.json.JSONObject
import java.io.File
import java.net.HttpURLConnection
import java.net.InetSocketAddress
import java.net.Proxy
import java.net.Socket
import java.net.URL
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import kotlin.concurrent.thread

class SiteCheck(val group: String, val site: String, val ok: Boolean)
class TestResult(val strategy: Strategy, val ok: Int, val total: Int, val failed: Boolean, val checks: List<SiteCheck>) {
    val pct: Int get() = if (total > 0) ok * 100 / total else 0
}

/**
 * Тест стратегий: каждая стратегия запускается отдельной программой ByeDPI (libciadpi.so) на своём порту,
 * и сайты открываются через неё как через прокси. Несколько стратегий проверяются одновременно.
 * Обход (VPN) при этом не мешает: само приложение в VPN не входит.
 */
object Tester {
    val running = mutableStateOf(false)
    val done = mutableStateOf(0)
    val total = mutableStateOf(0)
    val status = mutableStateOf("")
    val results = mutableStateListOf<TestResult>()
    @Volatile private var cancel = false
    private val procs = mutableListOf<Process>()
    private val port = java.util.concurrent.atomic.AtomicInteger(0)

    private const val PARALLEL = 3
    private const val TIMEOUT = 5000

    fun start(ctx: Context, strategies: List<Strategy>, groups: List<SiteGroup>) {
        if (running.value) return
        val exe = File(ctx.applicationInfo.nativeLibraryDir, "libciadpi.so").absolutePath
        val sites = groups.flatMap { g -> g.sites.take(20).map { g.name to it } }
        cancel = false; running.value = true; done.value = 0; total.value = strategies.size; results.clear()
        status.value = "Запускаю…"
        val app = ctx.applicationContext
        thread(name = "tester") {
            val pool = Executors.newFixedThreadPool(PARALLEL)
            strategies.forEach { st ->
                pool.execute {
                    if (cancel) return@execute
                    val r = testOne(exe, st, sites, 21000 + port.getAndIncrement() % 500)
                    if (cancel) return@execute
                    synchronized(results) {
                        results.add(r)
                        results.sortWith(compareByDescending<TestResult> { if (it.failed) -1 else it.ok })
                    }
                    done.value = done.value + 1
                    status.value = "Проверено ${done.value} из ${total.value}"
                    saveScore(app, r)
                }
            }
            pool.shutdown()
            pool.awaitTermination(2, TimeUnit.HOURS)
            killAll()
            status.value = if (cancel) "Тест остановлен · проверено ${done.value} из ${total.value}" else "Готово · проверено ${done.value} стратегий"
            running.value = false
        }
    }

    fun stop() {
        cancel = true
        status.value = "Останавливаю…"
        killAll()
    }

    private fun killAll() { synchronized(procs) { procs.forEach { try { it.destroy() } catch (e: Exception) { } }; procs.clear() } }

    private fun testOne(exe: String, st: Strategy, sites: List<Pair<String, String>>, port: Int): TestResult {
        val args = listOf(exe, "--ip", "127.0.0.1", "--port", port.toString()) + Prefs.split(st.args)
        val p = try { ProcessBuilder(args).redirectErrorStream(true).start() } catch (e: Exception) { return TestResult(st, 0, sites.size, true, emptyList()) }
        synchronized(procs) { procs.add(p) }
        thread { try { p.inputStream.use { it.readBytes() } } catch (e: Exception) { } }     // вывод не нужен, но читать его надо
        try {
            if (!waitPort(port, p)) return TestResult(st, 0, sites.size, true, emptyList())
            val proxy = Proxy(Proxy.Type.SOCKS, InetSocketAddress("127.0.0.1", port))
            val pool = Executors.newFixedThreadPool(8)
            val futures = sites.map { (g, s) -> pool.submit<SiteCheck> { SiteCheck(g, s, !cancel && open(s, proxy)) } }
            val checks = futures.map { it.get() }
            pool.shutdown()
            return TestResult(st, checks.count { it.ok }, checks.size, false, checks)
        } finally {
            p.destroy()
            synchronized(procs) { procs.remove(p) }
        }
    }

    private fun waitPort(port: Int, p: Process): Boolean {
        repeat(40) {
            if (cancel) return false
            try { if (p.exitValue() >= 0) return false } catch (e: IllegalThreadStateException) { }     // ещё работает
            try { Socket().use { it.connect(InetSocketAddress("127.0.0.1", port), 100) }; return true } catch (e: Exception) { Thread.sleep(80) }
        }
        return false
    }

    /** Сайт открылся — сервер ответил по HTTPS (любым кодом). */
    private fun open(site: String, proxy: Proxy): Boolean = try {
        val c = URL("https://$site/").openConnection(proxy) as HttpURLConnection
        c.connectTimeout = TIMEOUT; c.readTimeout = TIMEOUT; c.instanceFollowRedirects = false
        c.requestMethod = "GET"; c.setRequestProperty("User-Agent", "Mozilla/5.0")
        val code = c.responseCode
        c.disconnect()
        code > 0
    } catch (e: Exception) { false }

    // ---- результаты: процент последнего теста у каждой стратегии ----
    private fun saveScore(ctx: Context, r: TestResult) {
        val sp = ctx.getSharedPreferences("zapret", Context.MODE_PRIVATE)
        synchronized(this) {
            val o = try { JSONObject(sp.getString("scores", "{}")!!) } catch (e: Exception) { JSONObject() }
            o.put(r.strategy.id, if (r.failed) -1 else r.pct)
            sp.edit().putString("scores", o.toString()).commit()
        }
    }

    /** id стратегии → процент (-1 — не запустилась). */
    fun scores(ctx: Context): Map<String, Int> {
        val o = try { JSONObject(ctx.getSharedPreferences("zapret", Context.MODE_PRIVATE).getString("scores", "{}")!!) } catch (e: Exception) { JSONObject() }
        return o.keys().asSequence().associateWith { o.getInt(it) }
    }
}
