package com.coffiok.zapret

import android.Manifest
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageManager
import android.net.Uri
import android.net.VpnService
import android.os.Build
import android.os.Bundle
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
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.Font
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.material3.Text
import androidx.core.content.ContextCompat

// цвета — как в Zapret GUI на ПК
private val Bg = Color(0xFF0F1115)
private val Card = Color(0xFF1E2129)
private val Line = Color(0xFF262A33)
private val Fg = Color(0xFFE6E8EE)
private val Muted = Color(0xFF8A90A0)
private val Accent = Color(0xFF3DDC97)
private val Amber = Color(0xFFFFB547)
private val Red = Color(0xFFFF5C6C)
private val Pixel = FontFamily(Font(R.font.zpixel))

class MainActivity : ComponentActivity() {
    private val state = mutableStateOf("off")          // off | starting | on
    private val error = mutableStateOf<String?>(null)
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
        ContextCompat.registerReceiver(this, receiver, IntentFilter(ZapretVpnService.STATUS), ContextCompat.RECEIVER_NOT_EXPORTED)
        setContent { Screen() }
    }

    override fun onResume() {
        super.onResume()
        if (state.value != "starting") state.value = if (ZapretVpnService.running(this)) "on" else "off"
    }

    override fun onDestroy() {
        unregisterReceiver(receiver)
        super.onDestroy()
    }

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

    /** Сменили стратегию при включённом обходе — перезапускаем. */
    private fun applyStrategy() {
        if (state.value == "on" || state.value == "starting") { restartAfterStop = true; ZapretVpnService.stop(this) }
    }

    @Composable
    private fun Screen() {
        val prefs = remember { Prefs(this) }
        var picking by remember { mutableStateOf(false) }
        var editing by remember { mutableStateOf(false) }
        var strategyId by remember { mutableStateOf(prefs.strategyId) }
        var autostart by remember { mutableStateOf(prefs.autostart) }
        val st by state
        val err by error

        Box(Modifier.fillMaxSize().background(Bg)) {
            Column(Modifier.fillMaxSize().padding(horizontal = 20.dp, vertical = 18.dp)) {
                Row(verticalAlignment = Alignment.Bottom) {
                    Text("ZAPRET", fontFamily = Pixel, fontSize = 33.sp, color = Fg)
                    Spacer(Modifier.width(10.dp))
                    Text("by COFFIOK", fontSize = 12.sp, color = Muted, modifier = Modifier.padding(bottom = 6.dp))
                }
                Spacer(Modifier.weight(1f))

                // большая кнопка
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

                // стратегия
                val cur = prefs.strategies().firstOrNull { it.id == strategyId } ?: prefs.strategies().first()
                PixelCard(Modifier.clickable { picking = true }) {
                    Text("СТРАТЕГИЯ", fontSize = 11.sp, color = Muted, letterSpacing = 1.sp)
                    Spacer(Modifier.height(4.dp))
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text(cur.name, fontFamily = Pixel, fontSize = 22.sp, color = Fg)
                        Spacer(Modifier.width(10.dp))
                        Text(cur.args.ifBlank { "не задана" }, fontSize = 11.sp, color = Muted, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f))
                        Text("›", fontSize = 22.sp, color = Muted)
                    }
                }
                Spacer(Modifier.height(10.dp))
                PixelCard(Modifier.clickable { autostart = !autostart; prefs.autostart = autostart }) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Column(Modifier.weight(1f)) {
                            Text("Запуск с телефоном", fontSize = 14.sp, color = Fg)
                            Text("включать обход после перезагрузки", fontSize = 11.sp, color = Muted)
                        }
                        PixelToggle(autostart)
                    }
                }
                Spacer(Modifier.height(14.dp))
                Text(
                    "v${BuildConfig.VERSION_NAME} · движок ByeDPI · открытый код (GPL-3.0)",
                    fontSize = 11.sp, color = Muted,
                    modifier = Modifier.align(Alignment.CenterHorizontally).clickable {
                        startActivity(Intent(Intent.ACTION_VIEW, Uri.parse("https://github.com/COFFIOK/zapret-android")))
                    }
                )
            }

            if (picking) StrategyList(prefs, strategyId,
                onPick = { s ->
                    picking = false
                    if (s.id == "custom") { editing = true; return@StrategyList }
                    if (s.id != strategyId) { strategyId = s.id; prefs.strategyId = s.id; applyStrategy() }
                },
                onClose = { picking = false })

            if (editing) CustomEditor(prefs.customArgs,
                onSave = { text ->
                    editing = false
                    prefs.customArgs = text.trim(); strategyId = "custom"; prefs.strategyId = "custom"; applyStrategy()
                },
                onClose = { editing = false })
        }
    }

    @Composable
    private fun PowerButton(st: String, onClick: () -> Unit) {
        val on = st == "on"
        val col = when (st) { "on" -> Accent; "starting" -> Amber; else -> Line }
        // пиксельная кнопка: сдвинутая «тень» + рамка
        Box(Modifier.size(196.dp)) {
            Box(Modifier.offset(8.dp, 8.dp).size(184.dp).background(if (on) Color(0xFF1F6E4C) else Color(0xFF0A0C10)))
            Box(
                Modifier.size(184.dp).background(if (on) Accent else Card).border(4.dp, col)
                    .clickable(interactionSource = remember { MutableInteractionSource() }, indication = null) { onClick() },
                contentAlignment = Alignment.Center
            ) {
                Text(if (on) "ВКЛ" else if (st == "starting") "…" else "ВЫКЛ", fontFamily = Pixel, fontSize = 33.sp, color = if (on) Bg else Fg)
            }
        }
    }

    @Composable
    private fun PixelToggle(on: Boolean) {
        Box(Modifier.size(52.dp, 28.dp).background(if (on) Accent else Line).padding(4.dp), contentAlignment = if (on) Alignment.CenterEnd else Alignment.CenterStart) {
            Box(Modifier.size(20.dp).background(if (on) Bg else Muted))
        }
    }

    @Composable
    private fun PixelCard(modifier: Modifier = Modifier, content: @Composable ColumnScope.() -> Unit) {
        Column(modifier.fillMaxWidth().background(Card).border(2.dp, Line).padding(horizontal = 16.dp, vertical = 14.dp), content = content)
    }

    @Composable
    private fun StrategyList(prefs: Prefs, selected: String, onPick: (Strategy) -> Unit, onClose: () -> Unit) {
        BackHandler { onClose() }
        Column(Modifier.fillMaxSize().background(Bg).padding(horizontal = 20.dp, vertical = 18.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text("Стратегия", fontFamily = Pixel, fontSize = 22.sp, color = Fg, modifier = Modifier.weight(1f))
                Text("✕", fontSize = 20.sp, color = Muted, modifier = Modifier.clickable { onClose() }.padding(8.dp))
            }
            Text("если сайты не открываются — попробуй другую", fontSize = 12.sp, color = Muted)
            Spacer(Modifier.height(12.dp))
            LazyColumn(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                items(prefs.strategies()) { s ->
                    val sel = s.id == selected
                    Column(Modifier.fillMaxWidth().background(Card).border(2.dp, if (sel) Accent else Line).clickable { onPick(s) }.padding(horizontal = 14.dp, vertical = 10.dp)) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Text(s.name, fontFamily = Pixel, fontSize = 22.sp, color = if (sel) Accent else Fg)
                            if (s.id == "Z1") { Spacer(Modifier.width(8.dp)); Text("стандартная", fontSize = 11.sp, color = Muted) }
                        }
                        Text(if (s.id == "custom") (s.args.ifBlank { "свои параметры ByeDPI" }) else s.args, fontSize = 11.sp, color = Muted, maxLines = 2, overflow = TextOverflow.Ellipsis)
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
            BasicTextField(
                value = text, onValueChange = { text = it },
                textStyle = TextStyle(color = Fg, fontSize = 14.sp),
                cursorBrush = SolidColor(Accent),
                modifier = Modifier.fillMaxWidth().heightIn(min = 120.dp).background(Card).border(2.dp, Line).padding(12.dp)
            )
            Spacer(Modifier.height(14.dp))
            Box(Modifier.fillMaxWidth().background(if (text.isBlank()) Line else Accent).clickable(enabled = text.isNotBlank()) { onSave(text) }.padding(vertical = 14.dp), contentAlignment = Alignment.Center) {
                Text("Сохранить", color = Bg, fontSize = 15.sp, fontWeight = FontWeight.SemiBold)
            }
        }
    }
}
