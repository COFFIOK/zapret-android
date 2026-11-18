package com.coffiok.zapret

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL

/** Группа сайтов: встроенная (для проверки) или своя — как «Мои сайты» в Zapret GUI на ПК. */
data class SiteGroup(val name: String, val sites: List<String>, val builtin: Boolean)

object Sites {
    /** Встроенные группы: адреса, по которым тест понимает, открывается ли сервис. */
    val builtin = listOf(
        SiteGroup("YouTube", listOf("www.youtube.com", "i.ytimg.com", "youtubei.googleapis.com", Tester.VIDEO, "yt3.ggpht.com"), true),
        SiteGroup("Discord", listOf("discord.com", "gateway.discord.gg", "cdn.discordapp.com", "media.discordapp.net", "discord.gg"), true),
        SiteGroup("Instagram", listOf("www.instagram.com", "i.instagram.com", "static.cdninstagram.com"), true),
        SiteGroup("X (Twitter)", listOf("x.com", "abs.twimg.com", "pbs.twimg.com"), true),
        SiteGroup("Telegram", listOf("web.telegram.org", "telegram.org", "t.me"), true),
        SiteGroup("Google", listOf("www.google.com", "play.googleapis.com", "fonts.gstatic.com"), true),
    )

    private const val SHARE = "https://zapret-share.chodaniel07.workers.dev"
    private val CodeRx = Regex("^\\s*([A-HJKMNP-Z2-9]{3})[\\s-]?([A-HJKMNP-Z2-9]{3})\\s*$", RegexOption.IGNORE_CASE)

    fun user(ctx: Context): List<SiteGroup> {
        val raw = ctx.getSharedPreferences("zapret", Context.MODE_PRIVATE).getString("groups", null) ?: return emptyList()
        return try {
            val o = JSONObject(raw)
            o.keys().asSequence().map { k ->
                val a = o.getJSONArray(k)
                SiteGroup(k, (0 until a.length()).map { a.getString(it) }, false)
            }.toList()
        } catch (e: Exception) { emptyList() }
    }

    fun saveUser(ctx: Context, groups: List<SiteGroup>) {
        val o = JSONObject()
        for (g in groups) o.put(g.name, JSONArray(g.sites))
        ctx.getSharedPreferences("zapret", Context.MODE_PRIVATE).edit().putString("groups", o.toString()).commit()
    }

    fun all(ctx: Context): List<SiteGroup> = user(ctx) + builtin

    /** Чистит ввод: «https://www.site.com/путь» → «www.site.com»; пустые и повторы убираются. */
    fun cleanDomains(text: String): List<String> =
        text.split('\n', ',', ' ', ';').map {
            it.trim().lowercase().removePrefix("https://").removePrefix("http://").substringBefore('/').substringBefore(':').trimEnd('.')
        }.filter { it.contains('.') && !it.startsWith("#") }.distinct()

    fun normCode(s: String): String? = CodeRx.find(s)?.let { (it.groupValues[1] + it.groupValues[2]).uppercase() }
    fun pretty(c: String) = if (c.length == 6) c.substring(0, 3) + "-" + c.substring(3) else c

    /** Поделиться: сервер хранит список 30 дней и отдаёт короткий код (тот же, что в Zapret GUI на ПК). */
    fun makeCode(groups: List<SiteGroup>): String? {
        val g = JSONObject(); for (x in groups) g.put(x.name, JSONArray(x.sites))
        val (code, body) = http("POST", "/s", JSONObject().put("groups", g).toString())
        return if (code == 200) JSONObject(body).optString("code").ifBlank { null } else null
    }

    /** Ввести код друга: группы с сервера. null — код не найден или сервер недоступен. */
    fun fromCode(code: String): List<SiteGroup>? {
        val (st, body) = http("GET", "/s/$code", null)
        if (st != 200) return null
        val g = JSONObject(body).optJSONObject("groups") ?: return null
        return g.keys().asSequence().map { k -> val a = g.getJSONArray(k); SiteGroup(k, (0 until a.length()).map { a.getString(it) }, false) }.toList()
    }

    /** Добавляет группы друга к своим: одноимённые объединяются. */
    fun merge(ctx: Context, add: List<SiteGroup>) {
        val cur = user(ctx).associate { it.name to it.sites }.toMutableMap()
        for (g in add) cur[g.name] = ((cur[g.name] ?: emptyList()) + g.sites).distinct()
        saveUser(ctx, cur.map { SiteGroup(it.key, it.value, false) })
    }

    private fun http(method: String, path: String, body: String?): Pair<Int, String> {
        val c = URL(SHARE + path).openConnection() as HttpURLConnection
        c.requestMethod = method; c.connectTimeout = 12000; c.readTimeout = 12000
        c.setRequestProperty("User-Agent", "ZapretAndroid/" + BuildConfig.VERSION_NAME)
        if (body != null) {
            c.doOutput = true
            c.setRequestProperty("Content-Type", "application/json; charset=utf-8")
            c.outputStream.use { it.write(body.toByteArray()) }
        }
        val st = c.responseCode
        val text = (if (st in 200..299) c.inputStream else c.errorStream)?.bufferedReader()?.use { it.readText() } ?: ""
        c.disconnect()
        return st to text
    }
}
