package com.coffiok.zapret

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.provider.Settings
import androidx.compose.runtime.mutableStateOf
import androidx.core.content.FileProvider
import org.json.JSONObject
import java.io.File
import java.net.HttpURLConnection
import java.net.URL
import java.security.MessageDigest
import kotlin.concurrent.thread

/**
 * Обновления: последняя версия берётся из релизов GitHub, APK скачивается в приложение,
 * сверяется с контрольной суммой из GitHub и отдаётся системному установщику (ставится поверх).
 */
object Updater {
    private const val API = "https://api.github.com/repos/COFFIOK/zapret-android/releases/latest"
    const val PC_URL = "https://github.com/COFFIOK/zapret-gui/releases/latest"

    class Release(val version: String, val url: String, val size: Long, val sha256: String?, val notes: String)

    val latest = mutableStateOf<Release?>(null)       // есть версия новее установленной
    val checking = mutableStateOf(false)
    val progress = mutableStateOf(-1f)                 // 0..1 — идёт загрузка
    val message = mutableStateOf<String?>(null)

    fun check(ctx: Context, quiet: Boolean) {
        val sp = ctx.getSharedPreferences("zapret", Context.MODE_PRIVATE)
        if (quiet && System.currentTimeMillis() - sp.getLong("updChecked", 0) < 3 * 3600_000L) {
            restoreCached(sp.getString("updCache", null)); return
        }
        if (checking.value) return
        checking.value = true
        if (!quiet) message.value = "Проверяю…"
        thread {
            try {
                val c = URL(API).openConnection() as HttpURLConnection
                c.connectTimeout = 10000; c.readTimeout = 10000
                c.setRequestProperty("Accept", "application/vnd.github+json")
                c.setRequestProperty("User-Agent", "ZapretAndroid/" + BuildConfig.VERSION_NAME)
                val body = c.inputStream.bufferedReader().use { it.readText() }
                c.disconnect()
                sp.edit().putLong("updChecked", System.currentTimeMillis()).putString("updCache", body).apply()
                restoreCached(body)
                if (!quiet) message.value = if (latest.value == null) "Установлена последняя версия" else null
            } catch (e: Exception) {
                if (!quiet) message.value = "Не удалось проверить обновления — нет связи с GitHub"
            } finally { checking.value = false }
        }
    }

    private fun restoreCached(body: String?) {
        if (body == null) return
        try {
            val o = JSONObject(body)
            val ver = o.getString("tag_name").removePrefix("v")
            val assets = o.getJSONArray("assets")
            var rel: Release? = null
            for (i in 0 until assets.length()) {
                val a = assets.getJSONObject(i)
                if (a.getString("name") == "Zapret-universal.apk")
                    rel = Release(ver, a.getString("browser_download_url"), a.optLong("size"),
                        a.optString("digest").removePrefix("sha256:").ifBlank { null }, o.optString("body"))
            }
            latest.value = if (rel != null && newer(ver, BuildConfig.VERSION_NAME)) rel else null
        } catch (e: Exception) { }
    }

    fun newer(a: String, b: String): Boolean {
        val x = a.split('.', '-').map { it.toIntOrNull() ?: 0 }; val y = b.split('.', '-').map { it.toIntOrNull() ?: 0 }
        for (i in 0 until maxOf(x.size, y.size)) { val d = x.getOrElse(i) { 0 } - y.getOrElse(i) { 0 }; if (d != 0) return d > 0 }
        return false
    }

    /** Скачать и установить. Если Android ещё не разрешил приложению ставить APK — откроет это разрешение. */
    fun install(ctx: Context) {
        val rel = latest.value ?: return
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O && !ctx.packageManager.canRequestPackageInstalls()) {
            message.value = "Разреши Zapret устанавливать обновления и нажми «Обновить» ещё раз"
            ctx.startActivity(Intent(Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES, Uri.parse("package:" + ctx.packageName)).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
            return
        }
        if (progress.value >= 0) return
        progress.value = 0f; message.value = null
        val app = ctx.applicationContext
        thread {
            try {
                val dir = File(app.cacheDir, "update").apply { mkdirs() }
                val f = File(dir, "Zapret.apk"); f.delete()
                val c = URL(rel.url).openConnection() as HttpURLConnection
                c.connectTimeout = 15000; c.readTimeout = 30000; c.instanceFollowRedirects = true
                val len = c.contentLengthLong.takeIf { it > 0 } ?: rel.size
                val md = MessageDigest.getInstance("SHA-256")
                c.inputStream.use { inp ->
                    f.outputStream().use { out ->
                        val buf = ByteArray(64 * 1024); var n: Int; var got = 0L
                        while (inp.read(buf).also { n = it } > 0) {
                            out.write(buf, 0, n); md.update(buf, 0, n); got += n
                            if (len > 0) progress.value = got.toFloat() / len
                        }
                    }
                }
                c.disconnect()
                val hex = md.digest().joinToString("") { "%02x".format(it) }
                if (rel.sha256 != null && !hex.equals(rel.sha256, true)) { f.delete(); throw IllegalStateException("файл повреждён при загрузке") }
                progress.value = -1f
                val uri = FileProvider.getUriForFile(app, app.packageName + ".files", f)
                app.startActivity(Intent(Intent.ACTION_VIEW).setDataAndType(uri, "application/vnd.android.package-archive")
                    .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_ACTIVITY_NEW_TASK))
            } catch (e: Exception) {
                progress.value = -1f
                message.value = "Не удалось скачать обновление: ${e.message}"
            }
        }
    }
}
