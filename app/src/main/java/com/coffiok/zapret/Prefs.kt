package com.coffiok.zapret

import android.content.Context

data class Strategy(val id: String, val name: String, val args: String)

/** Настройки приложения. Сервис обхода живёт в своём процессе и читает их заново при каждом запуске. */
class Prefs(context: Context) {
    private val sp = context.getSharedPreferences("zapret", Context.MODE_PRIVATE)
    private val ctx = context.applicationContext

    var strategyId: String
        get() = sp.getString("strategy", "Z1") ?: "Z1"
        set(v) { sp.edit().putString("strategy", v).commit() }

    var customArgs: String
        get() = sp.getString("custom", "") ?: ""
        set(v) { sp.edit().putString("custom", v).commit() }

    var autostart: Boolean
        get() = sp.getBoolean("autostart", false)
        set(v) { sp.edit().putBoolean("autostart", v).commit() }

    var dns: String
        get() = sp.getString("dns", "1.1.1.1") ?: "1.1.1.1"
        set(v) { sp.edit().putString("dns", v).commit() }

    /** IPv6 тоже через обход: иначе приложения ходят по IPv6 мимо Zapret (например, YouTube на мобильном интернете). */
    var ipv6: Boolean
        get() = sp.getBoolean("ipv6", true)
        set(v) { sp.edit().putBoolean("ipv6", v).commit() }

    var tgProxy: Boolean
        get() = sp.getBoolean("tgProxy", false)
        set(v) { sp.edit().putBoolean("tgProxy", v).commit() }

    var askedNotifications: Boolean
        get() = sp.getBoolean("askedNotif", false)
        set(v) { sp.edit().putBoolean("askedNotif", v).apply() }

    /** Готовые стратегии: Z1 — стандартная, остальные — набор из ByeByeDPI (GPL-3.0). */
    fun strategies(): List<Strategy> {
        val lines = ctx.assets.open("strategies.list").bufferedReader().readLines().map { it.trim() }.filter { it.isNotEmpty() && !it.startsWith("#") }
        val list = lines.mapIndexed { i, a -> Strategy("Z${i + 1}", "Z${i + 1}", a.replace("{sni}", "google.com")) }.toMutableList()
        list.add(Strategy("custom", "Своя", customArgs))
        return list
    }

    fun current(): Strategy = strategies().firstOrNull { it.id == strategyId } ?: strategies().first()

    /** Параметры ByeDPI с учётом кавычек. */
    fun args(): List<String> = split(current().args)

    companion object {
        fun split(s: String): List<String> {
            val out = mutableListOf<String>(); val cur = StringBuilder(); var q: Char? = null; var any = false
            for (c in s) {
                when {
                    q != null && c == q -> q = null
                    q == null && (c == '"' || c == '\'') -> { q = c; any = true }
                    q == null && c.isWhitespace() -> { if (cur.isNotEmpty() || any) { out.add(cur.toString()); cur.clear(); any = false } }
                    else -> cur.append(c)
                }
            }
            if (cur.isNotEmpty() || any) out.add(cur.toString())
            return out
        }
    }
}
