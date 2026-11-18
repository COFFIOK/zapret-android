@file:OptIn(androidx.compose.foundation.layout.ExperimentalLayoutApi::class)

package com.coffiok.zapret

import android.Manifest
import android.content.BroadcastReceiver
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageManager
import android.net.Uri
import android.net.VpnService
import android.os.Build
import android.os.Bundle
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.ContextCompat
import kotlin.concurrent.thread

class MainActivity : ComponentActivity() {
    private val state = mutableStateOf("off")          // off | starting | on
    private val error = mutableStateOf<String?>(null)
    private val strategyId = mutableStateOf("Z1")
    private var restartAfterStop = false

    private val vpnPermission = registerForActivityResult(ActivityResultContracts.StartActivityForResult()) {
        if (VpnService.prepare(this) == null) startVpn() else error.value = "Без разрешения на VPN обход не включить"
    }
    private val notifPermission = registerForActivityResult(ActivityResultContracts.RequestPermission()) { }

    private val receiver = object : BroadcastReceiver() {
        override fun onReceive(c: Context, i: Intent) {
            when (i.getStringExtra("state")) {
                "starting" -> state.value = "starting"
                "on" -> { state.value = "on"; error.value = null }
                "error" -> error.value = i.getStringExtra("info")
                "off" -> {
                    state.value = "off"
                    if (restartAfterStop) { restartAfterStop = false; window.decorView.postDelayed({ startVpn() }, 600) }
                }
            }
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.statusBarColor = android.graphics.Color.parseColor("#0F1115")
        window.navigationBarColor = android.graphics.Color.parseColor("#0F1115")
        strategyId.value = Prefs(this).strategyId
        ContextCompat.registerReceiver(this, receiver, IntentFilter(ZapretVpnService.STATUS), ContextCompat.RECEIVER_NOT_EXPORTED)
        Updater.check(this, quiet = true)
        setContent { App() }
    }

    override fun onResume() {
        super.onResume()
        if (state.value != "starting") state.value = if (ZapretVpnService.running(this)) "on" else "off"
    }

    override fun onDestroy() {
        unregisterReceiver(receiver)
        super.onDestroy()
    }

    // ---- обход ----
    private fun toggle() {
        error.value = null
        if (state.value == "off") askAndStart() else { state.value = "off"; ZapretVpnService.stop(this) }
    }

    private fun askAndStart() {
        val prefs = Prefs(this)
        if (Build.VERSION.SDK_INT >= 33 && !prefs.askedNotifications &&
            ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) {
            prefs.askedNotifications = true
            notifPermission.launch(Manifest.permission.POST_NOTIFICATIONS)
        }
        val ask = VpnService.prepare(this)
        if (ask != null) vpnPermission.launch(ask) else startVpn()
    }

    private fun startVpn() {
        state.value = "starting"
        ZapretVpnService.start(this)
    }

    /** Выбрать стратегию; если обход включён — перезапустить с ней. */
    private fun useStrategy(id: String) {
        if (id == strategyId.value) return
        strategyId.value = id; Prefs(this).strategyId = id
        if (state.value == "on" || state.value == "starting") { restartAfterStop = true; ZapretVpnService.stop(this) }
    }

    private fun toast(t: String) = Toast.makeText(this, t, Toast.LENGTH_SHORT).show()
    private fun open(url: String) = startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(url)))

    // ---- экран ----
    @Composable
    private fun App() {
        var tab by rememberSaveable { mutableStateOf(0) }
        Column(Modifier.fillMaxSize().background(Bg)) {
            Box(Modifier.weight(1f)) {
                when (tab) {
                    0 -> HomeTab { tab = 1 }
                    1 -> TestTab()
                    2 -> SitesTab()
                    else -> MoreTab()
                }
            }
            // нижние вкладки
            Row(Modifier.fillMaxWidth().background(Color(0xFF171A21)).border(2.dp, Line)) {
                val upd by Updater.latest
                listOf("Обход", "Тест", "Сайты", "Ещё").forEachIndexed { i, t ->
                    Box(Modifier.weight(1f).clickable { tab = i }.padding(vertical = 14.dp), contentAlignment = Alignment.Center) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Text(t, fontFamily = Pixel, fontSize = 15.sp, color = if (tab == i) Accent else Muted)
                            if (i == 3 && upd != null) { Spacer(Modifier.width(4.dp)); Box(Modifier.size(6.dp).background(Amber)) }
                        }
                    }
                }
            }
        }
    }

    // ---- вкладка «Обход» ----
    @Composable
    private fun HomeTab(openTest: () -> Unit) {
        val prefs = remember { Prefs(this) }
        var picking by remember { mutableStateOf(false) }
        var editing by remember { mutableStateOf(false) }
        val st by state
        val err by error
        val sid by strategyId
        val upd by Updater.latest

        Box(Modifier.fillMaxSize()) {
            Column(Modifier.fillMaxSize().padding(horizontal = 20.dp, vertical = 18.dp)) {
                Row(verticalAlignment = Alignment.Bottom) {
                    Text("ZAPRET", fontFamily = Pixel, fontSize = 33.sp, color = Fg)
                    Spacer(Modifier.width(10.dp))
                    Text("by COFFIOK", fontSize = 12.sp, color = Muted, modifier = Modifier.padding(bottom = 6.dp))
                }
                if (upd != null) {
                    Spacer(Modifier.height(10.dp))
                    PixelCard(Modifier.clickable { Updater.install(this@MainActivity) }, border = Amber) {
                        Text("Доступна версия ${upd!!.version}", color = Amber, fontSize = 14.sp, fontWeight = FontWeight.SemiBold)
                        Text("нажми, чтобы обновить", color = Muted, fontSize = 11.sp)
                    }
                }
                Spacer(Modifier.weight(1f))
                Box(Modifier.fillMaxWidth(), contentAlignment = Alignment.Center) { PowerButton(st) { toggle() } }
                Spacer(Modifier.height(18.dp))
                Text(
                    when (st) { "on" -> "Обход включён"; "starting" -> "Включаю…"; else -> "Обход выключен" },
                    color = when (st) { "on" -> Accent; "starting" -> Amber; else -> Muted },
                    fontSize = 15.sp, fontWeight = FontWeight.SemiBold, modifier = Modifier.align(Alignment.CenterHorizontally)
                )
                if (err != null) {
                    Spacer(Modifier.height(8.dp))
                    Text(err!!, color = Red, fontSize = 13.sp, modifier = Modifier.align(Alignment.CenterHorizontally))
                }
                Spacer(Modifier.weight(1f))

                val list = prefs.strategies()
                val cur = list.firstOrNull { it.id == sid } ?: list.first()
                val score = Tester.scores(this@MainActivity)[cur.id]
                PixelCard(Modifier.clickable { picking = true }) {
                    Caption("Стратегия")
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text(cur.name, fontFamily = Pixel, fontSize = 22.sp, color = Fg)
                        if (score != null) { Spacer(Modifier.width(8.dp)); ScoreTag(score) }
                        Spacer(Modifier.width(10.dp))
                        Text(cur.args.ifBlank { "не задана" }, fontSize = 11.sp, color = Muted, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f))
                        Text("›", fontSize = 22.sp, color = Muted)
                    }
                }
                Spacer(Modifier.height(10.dp))
                PixelButton("Подобрать стратегию", accent = false) { openTest() }
                Spacer(Modifier.height(10.dp))
                TelegramCard()
            }

            if (picking) StrategyList(prefs, sid,
                onPick = { s ->
                    picking = false
                    if (s.id == "custom") editing = true else useStrategy(s.id)
                },
                onClose = { picking = false })

            if (editing) CustomEditor(prefs.customArgs,
                onSave = { text -> editing = false; prefs.customArgs = text.trim(); strategyId.value = ""; useStrategy("custom") },
                onClose = { editing = false })
        }
    }

    @Composable
    private fun TelegramCard() {
        val prefs = remember { Prefs(this) }
        var tg by remember { mutableStateOf(prefs.tgProxy) }
        PixelCard {
            Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.clickable {
                tg = !tg; prefs.tgProxy = tg
                if (state.value == "on") { restartAfterStop = true; ZapretVpnService.stop(this@MainActivity) }
            }) {
                Column(Modifier.weight(1f)) {
                    Text("Telegram-прокси", fontSize = 14.sp, color = Fg)
                    Text("работает, пока включён обход", fontSize = 11.sp, color = Muted)
                }
                PixelToggle(tg)
            }
            if (tg) {
                Spacer(Modifier.height(10.dp))
                Text("Подключить в Telegram", fontSize = 13.sp, color = Bg, fontWeight = FontWeight.SemiBold,
                    modifier = Modifier.background(Accent).clickable {
                        try { open(TgProxy.link(this@MainActivity)) } catch (e: Exception) { toast("Telegram не установлен") }
                    }.padding(horizontal = 12.dp, vertical = 8.dp))
            }
        }
    }

    @Composable
    private fun ScoreTag(p: Int) {
        Text(if (p < 0) "не запуск." else "$p%", fontSize = 12.sp, color = if (p < 0) Red else pctColor(p), fontWeight = FontWeight.SemiBold)
    }

    @Composable
    private fun PowerButton(st: String, onClick: () -> Unit) {
        val on = st == "on"
        val col = when (st) { "on" -> Accent; "starting" -> Amber; else -> Line }
        Box(Modifier.size(196.dp)) {
            Box(Modifier.offset(8.dp, 8.dp).size(184.dp).background(if (on) AccentDark else Color(0xFF0A0C10)))
            Box(
                Modifier.size(184.dp).background(if (on) Accent else Card).border(4.dp, col)
                    .clickable(interactionSource = remember { MutableInteractionSource() }, indication = null) { onClick() },
                contentAlignment = Alignment.Center
            ) { Text(if (on) "ВКЛ" else if (st == "starting") "…" else "ВЫКЛ", fontFamily = Pixel, fontSize = 33.sp, color = if (on) Bg else Fg) }
        }
    }

    @Composable
    private fun StrategyList(prefs: Prefs, selected: String, onPick: (Strategy) -> Unit, onClose: () -> Unit) {
        BackHandler { onClose() }
        val scores = remember { Tester.scores(this) }
        // проверенные — сверху по проценту
        val list = remember { prefs.strategies().sortedWith(compareBy<Strategy> { if (it.id == "custom") 2 else if (scores.containsKey(it.id)) 0 else 1 }.thenByDescending { scores[it.id] ?: 0 }) }
        Column(Modifier.fillMaxSize().background(Bg).padding(horizontal = 20.dp, vertical = 18.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text("Стратегия", fontFamily = Pixel, fontSize = 22.sp, color = Fg, modifier = Modifier.weight(1f))
                Text("✕", fontSize = 20.sp, color = Muted, modifier = Modifier.clickable { onClose() }.padding(8.dp))
            }
            Text("проценты — из последнего теста", fontSize = 12.sp, color = Muted)
            Spacer(Modifier.height(12.dp))
            LazyColumn(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                items(list) { s ->
                    val sel = s.id == selected
                    val sc = scores[s.id]
                    Column(Modifier.fillMaxWidth().background(Card).border(2.dp, if (sel) Accent else Line).clickable { onPick(s) }.padding(horizontal = 14.dp, vertical = 10.dp)) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Text(s.name, fontFamily = Pixel, fontSize = 22.sp, color = if (sel) Accent else Fg)
                            if (s.id == "Z1") { Spacer(Modifier.width(8.dp)); Text("стандартная", fontSize = 11.sp, color = Muted) }
                            if (s.id.startsWith("F")) { Spacer(Modifier.width(8.dp)); Text("Flowseal", fontSize = 11.sp, color = Muted) }
                            Spacer(Modifier.weight(1f))
                            if (sc != null) ScoreTag(sc)
                        }
                        Text(if (s.id == "custom") s.args.ifBlank { "свои параметры ByeDPI" } else s.args, fontSize = 11.sp, color = Muted, maxLines = 2, overflow = TextOverflow.Ellipsis)
                    }
                }
            }
        }
    }

    @Composable
    private fun CustomEditor(initial: String, onSave: (String) -> Unit, onClose: () -> Unit) {
        BackHandler { onClose() }
        var text by remember { mutableStateOf(initial) }
        Column(Modifier.fillMaxSize().background(Bg).padding(horizontal = 20.dp, vertical = 18.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text("Своя стратегия", fontFamily = Pixel, fontSize = 22.sp, color = Fg, modifier = Modifier.weight(1f))
                Text("✕", fontSize = 20.sp, color = Muted, modifier = Modifier.clickable { onClose() }.padding(8.dp))
            }
            Text("параметры ByeDPI, например: -o1 -a1 -r-5+se", fontSize = 12.sp, color = Muted)
            Spacer(Modifier.height(12.dp))
            Field(text, { text = it }, Modifier.heightIn(min = 120.dp))
            Spacer(Modifier.height(14.dp))
            PixelButton("Сохранить", enabled = text.isNotBlank()) { onSave(text) }
        }
    }

    @Composable
    private fun Field(value: String, onChange: (String) -> Unit, modifier: Modifier = Modifier, hint: String? = null, single: Boolean = false) {
        Box(modifier.fillMaxWidth().background(Card).border(2.dp, Line).padding(12.dp)) {
            if (value.isEmpty() && hint != null) Text(hint, color = Muted, fontSize = 14.sp)
            BasicTextField(value = value, onValueChange = onChange, singleLine = single,
                textStyle = TextStyle(color = Fg, fontSize = 14.sp), cursorBrush = SolidColor(Accent), modifier = Modifier.fillMaxWidth())
        }
    }

    // ---- вкладка «Тест» ----
    @Composable
    private fun TestTab() {
        val prefs = remember { Prefs(this) }
        val groups = remember { Sites.all(this) }
        var picked by remember { mutableStateOf(groups.filter { !it.builtin || it.name in setOf("YouTube", "Discord") }.map { it.name }.toSet()) }
        var all by remember { mutableStateOf(false) }
        var expanded by remember { mutableStateOf<String?>(null) }
        val running by Tester.running
        val status by Tester.status
        val done by Tester.done
        val total by Tester.total
        val sid by strategyId

        LazyColumn(Modifier.fillMaxSize().padding(horizontal = 20.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            item {
                Spacer(Modifier.height(18.dp))
                Title("Тест стратегий", "проверяю, какая стратегия лучше открывает твои сайты")
                if (!running) {
                    Caption("Что проверять")
                    FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        groups.forEach { g -> Chip(g.name, g.name in picked) { picked = if (g.name in picked) picked - g.name else picked + g.name } }
                    }
                    Caption("Стратегии")
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        Chip("Быстро · Flowseal", !all) { all = false }
                        Chip("Все · ${prefs.strategies().size}", all) { all = true }
                    }
                    Spacer(Modifier.height(12.dp))
                    val n = if (all) prefs.strategies().count { it.args.isNotBlank() } else prefs.strategies().count { it.id.startsWith("F") }
                    val sites = groups.filter { it.name in picked }.sumOf { minOf(it.sites.size, 20) }
                    Text(if (sites == 0) "Выбери хотя бы одну группу" else "$n стратегий × $sites сайтов · примерно ${maxOf(1, n * 6 / 3 / 60)} мин", fontSize = 12.sp, color = Muted)
                    Spacer(Modifier.height(10.dp))
                    PixelButton("Запустить тест", enabled = sites > 0) {
                        val list = prefs.strategies().filter { it.args.isNotBlank() }.let { if (all) it else it.filter { s -> s.id.startsWith("F") } }
                        Tester.start(this@MainActivity, list, groups.filter { it.name in picked })
                    }
                } else {
                    Text(status, fontSize = 13.sp, color = Fg)
                    Spacer(Modifier.height(8.dp))
                    Bar(if (total > 0) done.toFloat() / total else 0f, Accent, 8.dp)
                    Spacer(Modifier.height(12.dp))
                    PixelButton("Остановить", accent = false) { Tester.stop() }
                }
                if (!running && status.isNotBlank()) { Spacer(Modifier.height(10.dp)); Text(status, fontSize = 12.sp, color = Muted) }
                if (Tester.results.isNotEmpty()) Caption("Результаты · нажми на строку, чтобы увидеть сайты")
            }
            items(Tester.results.toList(), key = { it.strategy.id }) { r ->
                val best = r === Tester.results.firstOrNull() && !running && !r.failed
                Column(Modifier.fillMaxWidth().background(Card).border(2.dp, if (best) Accent else Line)
                    .clickable { expanded = if (expanded == r.strategy.id) null else r.strategy.id }.padding(horizontal = 14.dp, vertical = 10.dp)) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text(r.strategy.name, fontFamily = Pixel, fontSize = 20.sp, color = Fg)
                        if (best) { Spacer(Modifier.width(8.dp)); Text("★ лучшая", fontSize = 11.sp, color = Bg, modifier = Modifier.background(Accent).padding(horizontal = 6.dp, vertical = 1.dp)) }
                        if (r.strategy.id == sid) { Spacer(Modifier.width(8.dp)); Text("сейчас", fontSize = 11.sp, color = Muted) }
                        Spacer(Modifier.weight(1f))
                        Text(if (r.failed) "не запустилась" else "${r.ok}/${r.total} · ${r.pct}%", fontSize = 12.sp, color = if (r.failed) Red else pctColor(r.pct))
                    }
                    Spacer(Modifier.height(6.dp))
                    Bar(if (r.failed) 0f else r.pct / 100f, if (r.failed) Red else pctColor(r.pct))
                    if (expanded == r.strategy.id) {
                        Spacer(Modifier.height(8.dp))
                        r.checks.groupBy { it.group }.forEach { (g, cs) ->
                            Text("$g · ${cs.count { it.ok }}/${cs.size}", fontSize = 12.sp, color = Fg, fontWeight = FontWeight.SemiBold)
                            cs.forEach { c -> Text((if (c.ok) "✓ " else "✕ ") + c.site, fontSize = 12.sp, color = if (c.ok) Accent else Red) }
                            Spacer(Modifier.height(4.dp))
                        }
                    }
                    if (!r.failed && r.ok > 0 && r.strategy.id != sid) {
                        Spacer(Modifier.height(8.dp))
                        Text("Включить", fontSize = 13.sp, color = Bg, fontWeight = FontWeight.SemiBold,
                            modifier = Modifier.background(Accent).clickable { useStrategy(r.strategy.id); toast("Стратегия ${r.strategy.name}") }.padding(horizontal = 12.dp, vertical = 6.dp))
                    }
                }
            }
            item { Spacer(Modifier.height(18.dp)) }
        }
    }

    // ---- вкладка «Сайты» ----
    @Composable
    private fun SitesTab() {
        var groups by remember { mutableStateOf(Sites.user(this)) }
        var editing by remember { mutableStateOf<SiteGroup?>(null) }
        var newGroup by remember { mutableStateOf(false) }
        var enterCode by remember { mutableStateOf(false) }
        var shareCode by remember { mutableStateOf<String?>(null) }
        var busy by remember { mutableStateOf(false) }

        LazyColumn(Modifier.fillMaxSize().padding(horizontal = 20.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            item {
                Spacer(Modifier.height(18.dp))
                Title("Мои сайты", "группы сайтов для теста стратегий · как в Zapret на ПК")
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    PixelButton("+ Группа", Modifier.weight(1f)) { newGroup = true }
                    PixelButton("Ввести код", Modifier.weight(1f), accent = false) { enterCode = true }
                }
                Spacer(Modifier.height(8.dp))
                PixelButton(if (busy) "Создаю код…" else "Поделиться", accent = false, enabled = groups.isNotEmpty() && !busy) {
                    busy = true
                    thread {
                        val code = try { Sites.makeCode(groups) } catch (e: Exception) { null }
                        runOnUiThread {
                            busy = false
                            if (code == null) toast("Сервер кодов не ответил — попробуй позже")
                            else {
                                shareCode = Sites.pretty(code)
                                (getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager).setPrimaryClip(ClipData.newPlainText("код", shareCode))
                            }
                        }
                    }
                }
                if (shareCode != null) {
                    Spacer(Modifier.height(10.dp))
                    PixelCard(border = Accent) {
                        Text(shareCode!!, fontFamily = Pixel, fontSize = 33.sp, color = Accent, modifier = Modifier.align(Alignment.CenterHorizontally))
                        Text("код скопирован · работает 30 дней · друг вводит его в Zapret на ПК или телефоне", fontSize = 11.sp, color = Muted, modifier = Modifier.align(Alignment.CenterHorizontally))
                    }
                }
                if (groups.isEmpty()) { Spacer(Modifier.height(16.dp)); Text("Здесь пока пусто", color = Muted, fontSize = 13.sp, modifier = Modifier.fillMaxWidth().padding(8.dp)) }
                Spacer(Modifier.height(4.dp))
            }
            items(groups, key = { it.name }) { g ->
                PixelCard(Modifier.clickable { editing = g }) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text(g.name, fontSize = 15.sp, color = Fg, fontWeight = FontWeight.SemiBold, modifier = Modifier.weight(1f))
                        Text("${g.sites.size} сайт.", fontSize = 12.sp, color = Muted)
                    }
                    Text(g.sites.take(4).joinToString(", "), fontSize = 11.sp, color = Muted, maxLines = 1, overflow = TextOverflow.Ellipsis)
                }
            }
            item {
                Caption("Встроенные · для проверки")
                Sites.builtin.forEach { g ->
                    Text("${g.name} — ${g.sites.joinToString(", ")}", fontSize = 11.sp, color = Muted, modifier = Modifier.padding(vertical = 3.dp))
                }
                Spacer(Modifier.height(18.dp))
            }
        }

        if (newGroup || editing != null) GroupEditor(editing,
            onSave = { name, sites ->
                val list = groups.filter { it.name != editing?.name && it.name != name }.toMutableList()
                list.add(0, SiteGroup(name, sites, false))
                groups = list; Sites.saveUser(this, list); newGroup = false; editing = null
            },
            onDelete = { g -> groups = groups.filter { it.name != g.name }; Sites.saveUser(this, groups); editing = null },
            onClose = { newGroup = false; editing = null })

        if (enterCode) CodeEntry(onDone = { added -> enterCode = false; if (added) groups = Sites.user(this) }, onClose = { enterCode = false })
    }

    @Composable
    private fun GroupEditor(g: SiteGroup?, onSave: (String, List<String>) -> Unit, onDelete: (SiteGroup) -> Unit, onClose: () -> Unit) {
        BackHandler { onClose() }
        var name by remember { mutableStateOf(g?.name ?: "") }
        var text by remember { mutableStateOf(g?.sites?.joinToString("\n") ?: "") }
        Column(Modifier.fillMaxSize().background(Bg).padding(horizontal = 20.dp, vertical = 18.dp).verticalScroll(rememberScrollState())) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(if (g == null) "Новая группа" else "Группа", fontFamily = Pixel, fontSize = 22.sp, color = Fg, modifier = Modifier.weight(1f))
                Text("✕", fontSize = 20.sp, color = Muted, modifier = Modifier.clickable { onClose() }.padding(8.dp))
            }
            Caption("Название")
            Field(name, { name = it }, hint = "например: Игры", single = true)
            Caption("Сайты · по одному в строке")
            Field(text, { text = it }, Modifier.heightIn(min = 180.dp), hint = "youtube.com\ndiscord.com")
            Spacer(Modifier.height(14.dp))
            val sites = Sites.cleanDomains(text)
            PixelButton("Сохранить · ${sites.size} сайт.", enabled = name.isNotBlank() && sites.isNotEmpty()) { onSave(name.trim(), sites) }
            if (g != null) { Spacer(Modifier.height(8.dp)); PixelButton("Удалить группу", accent = false) { onDelete(g) } }
        }
    }

    @Composable
    private fun CodeEntry(onDone: (Boolean) -> Unit, onClose: () -> Unit) {
        BackHandler { onClose() }
        var text by remember { mutableStateOf("") }
        var busy by remember { mutableStateOf(false) }
        var msg by remember { mutableStateOf<String?>(null) }
        Column(Modifier.fillMaxSize().background(Bg).padding(horizontal = 20.dp, vertical = 18.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text("Ввести код", fontFamily = Pixel, fontSize = 22.sp, color = Fg, modifier = Modifier.weight(1f))
                Text("✕", fontSize = 20.sp, color = Muted, modifier = Modifier.clickable { onClose() }.padding(8.dp))
            }
            Text("введи код, который прислал друг", fontSize = 12.sp, color = Muted)
            Spacer(Modifier.height(12.dp))
            Field(text, { text = it.uppercase() }, hint = "ABC-123", single = true)
            if (msg != null) { Spacer(Modifier.height(8.dp)); Text(msg!!, color = Red, fontSize = 12.sp) }
            Spacer(Modifier.height(14.dp))
            val code = Sites.normCode(text)
            PixelButton(if (busy) "Загружаю…" else "Добавить сайты", enabled = code != null && !busy) {
                busy = true; msg = null
                thread {
                    val g = try { Sites.fromCode(code!!) } catch (e: Exception) { null }
                    runOnUiThread {
                        busy = false
                        if (g == null) msg = "Код не найден или сервер недоступен"
                        else { Sites.merge(this@MainActivity, g); toast("Добавлено групп: ${g.size}"); onDone(true) }
                    }
                }
            }
        }
    }

    // ---- чат с автором и отчёт об ошибке ----
    @Composable
    private fun ChatScreen(bug: Boolean, onClose: () -> Unit) {
        BackHandler { onClose() }
        var msgs by remember { mutableStateOf<List<Chat.Msg>>(emptyList()) }
        var text by remember { mutableStateOf("") }
        var nick by remember { mutableStateOf(Chat.nick(this)) }
        var busy by remember { mutableStateOf<String?>(null) }
        var err by remember { mutableStateOf<String?>(null) }
        var bugMode by remember { mutableStateOf(bug) }
        LaunchedEffect(Unit) {
            while (true) {
                thread { val m = Chat.messages(this@MainActivity); runOnUiThread { msgs = m } }
                kotlinx.coroutines.delay(15000)
            }
        }
        Column(Modifier.fillMaxSize().padding(horizontal = 20.dp, vertical = 18.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(if (bugMode) "Ошибка" else "Чат с автором", fontFamily = Pixel, fontSize = 22.sp, color = Fg, modifier = Modifier.weight(1f))
                Text("✕", fontSize = 20.sp, color = Muted, modifier = Modifier.clickable { onClose() }.padding(8.dp))
            }
            Text(if (bugMode) "опиши, что не работает · к сообщению приложится отчёт: версия, сеть, проверка сайтов и журнал"
                 else "пиши об ошибках, идеях и вопросах — автор Zapret ответит здесь же", fontSize = 12.sp, color = Muted)
            Spacer(Modifier.height(10.dp))
            LazyColumn(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(6.dp), reverseLayout = true) {
                items(msgs.reversed(), key = { it.n }) { m ->
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = if (m.dev) Arrangement.Start else Arrangement.End) {
                        Column(Modifier.fillMaxWidth(0.85f).background(if (m.dev) Card else Color(0xFF173A2B)).border(2.dp, if (m.dev) Line else AccentDark).padding(10.dp)) {
                            if (m.dev) Text("COFFIOK", fontSize = 10.sp, color = Accent)
                            Text(m.text, fontSize = 13.sp, color = Fg)
                        }
                    }
                }
                if (msgs.isEmpty()) item { Text("Здесь пока пусто", color = Muted, fontSize = 13.sp, modifier = Modifier.padding(8.dp)) }
            }
            Spacer(Modifier.height(8.dp))
            if (nick.length < 2 || Chat.nick(this@MainActivity).length < 2) {
                Field(nick, { nick = it.take(24) }, hint = "Твой ник, например: Вася", single = true)
                Spacer(Modifier.height(6.dp))
            }
            Field(text, { text = it.take(2000) }, Modifier.heightIn(min = 70.dp), hint = if (bugMode) "Например: YouTube не грузится, Discord работает" else "Сообщение…")
            if (err != null) { Spacer(Modifier.height(6.dp)); Text(err!!, color = Red, fontSize = 12.sp) }
            Spacer(Modifier.height(8.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                PixelButton(busy ?: if (bugMode) "Отправить отчёт" else "Отправить", Modifier.weight(1f), enabled = busy == null && text.isNotBlank() && nick.trim().length >= 2) {
                    Chat.setNick(this@MainActivity, nick)
                    val t = text.trim(); val withLog = bugMode
                    busy = if (withLog) "Собираю отчёт…" else "Отправляю…"; err = null
                    thread {
                        val log = if (withLog) try { Diag.report(this@MainActivity) } catch (e: Exception) { "отчёт не собрался: ${e.message}" } else null
                        runOnUiThread { busy = "Отправляю…" }
                        val e = Chat.send(this@MainActivity, t, if (withLog) "bug" else "other", log)
                        val m = if (e == null) Chat.messages(this@MainActivity) else null
                        runOnUiThread {
                            busy = null
                            if (e != null) err = e else { text = ""; bugMode = false; msgs = m ?: msgs; toast(if (withLog) "Отчёт отправлен автору" else "Отправлено") }
                        }
                    }
                }
                if (!bugMode) PixelButton("Ошибка", Modifier.width(110.dp), accent = false) { bugMode = true }
            }
        }
    }

    // ---- вкладка «Ещё» ----
    @Composable
    private fun MoreTab() {
        val prefs = remember { Prefs(this) }
        var autostart by remember { mutableStateOf(prefs.autostart) }
        var dns by remember { mutableStateOf(prefs.dns) }
        val upd by Updater.latest
        val checking by Updater.checking
        val prog by Updater.progress
        val msg by Updater.message
        var chat by remember { mutableStateOf(0) }       // 0 — нет, 1 — чат, 2 — отчёт об ошибке
        var ipv6 by remember { mutableStateOf(prefs.ipv6) }
        var quic by remember { mutableStateOf(prefs.blockQuic) }

        if (chat != 0) { ChatScreen(bug = chat == 2) { chat = 0 }; return }

        Column(Modifier.fillMaxSize().padding(horizontal = 20.dp).verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Spacer(Modifier.height(18.dp))
            Title("Ещё")

            Caption("Связь с автором")
            PixelButton("Сообщить об ошибке") { chat = 2 }
            PixelButton("Чат с автором", accent = false) { chat = 1 }

            Caption("Обновления")
            PixelCard(border = if (upd != null) Amber else Line) {
                Text("Zapret для Android v${BuildConfig.VERSION_NAME}", fontSize = 14.sp, color = Fg)
                Text(when {
                    prog >= 0 -> "Скачиваю… ${(prog * 100).toInt()}%"
                    upd != null -> "Доступна версия ${upd!!.version}"
                    else -> msg ?: "проверяется при запуске"
                }, fontSize = 12.sp, color = if (upd != null) Amber else Muted)
                if (prog >= 0) { Spacer(Modifier.height(6.dp)); Bar(prog, Accent) }
                if (upd != null && msg != null) Text(msg!!, fontSize = 12.sp, color = Muted)
                Spacer(Modifier.height(10.dp))
                if (upd != null) PixelButton("Обновить", enabled = prog < 0) { Updater.install(this@MainActivity) }
                else PixelButton(if (checking) "Проверяю…" else "Проверить обновления", accent = false, enabled = !checking) { Updater.check(this@MainActivity, quiet = false) }
            }

            Caption("Запуск")
            ToggleRow("Запуск с телефоном", "включать обход после перезагрузки", autostart) { autostart = !autostart; prefs.autostart = autostart }

            ToggleRow("Закрыть QUIC", "YouTube и другие приложения пойдут через обход", quic) {
                quic = !quic; prefs.blockQuic = quic
                if (state.value == "on") { restartAfterStop = true; ZapretVpnService.stop(this@MainActivity) }
            }
            ToggleRow("IPv6 через обход", "без этого часть приложений идёт мимо Zapret", ipv6) {
                ipv6 = !ipv6; prefs.ipv6 = ipv6
                if (state.value == "on") { restartAfterStop = true; ZapretVpnService.stop(this@MainActivity) }
            }

            Caption("DNS")
            PixelCard {
                Text("через какой DNS открывать адреса", fontSize = 12.sp, color = Muted)
                Spacer(Modifier.height(8.dp))
                FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    listOf("1.1.1.1" to "Cloudflare", "8.8.8.8" to "Google", "9.9.9.9" to "Quad9", "77.88.8.8" to "Яндекс").forEach { (ip, n) ->
                        Chip(n, dns == ip) { dns = ip; prefs.dns = ip; if (state.value == "on") toast("Применится после перезапуска обхода") }
                    }
                }
            }

            Caption("Скачать")
            PixelButton("Zapret GUI для компьютера", accent = false) { open(Updater.PC_URL) }
            PixelButton("Все версии для Android", accent = false) { open("https://github.com/COFFIOK/zapret-android/releases") }

            Caption("О программе")
            Text("Zapret для Android · by COFFIOK\nДвижок ByeDPI (hufrea, MIT) · туннель hev-socks5-tunnel (heiher, MIT) · по мотивам ByeByeDPI (romanvht, GPL-3.0)\nОткрытый код: GPL-3.0",
                fontSize = 11.sp, color = Muted, modifier = Modifier.clickable { open("https://github.com/COFFIOK/zapret-android") })
            Spacer(Modifier.height(18.dp))
        }
    }
}
