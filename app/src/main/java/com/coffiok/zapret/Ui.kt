package com.coffiok.zapret

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.Font
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

// цвета — как в Zapret GUI на ПК
val Bg = Color(0xFF0F1115)
val Card = Color(0xFF1E2129)
val Line = Color(0xFF262A33)
val Fg = Color(0xFFE6E8EE)
val Muted = Color(0xFF8A90A0)
val Accent = Color(0xFF3DDC97)
val AccentDark = Color(0xFF1F6E4C)
val Amber = Color(0xFFFFB547)
val Red = Color(0xFFFF5C6C)
val Pixel = FontFamily(Font(R.font.zpixel))

@Composable
fun Title(text: String, sub: String? = null) {
    Text(text, fontFamily = Pixel, fontSize = 22.sp, color = Fg)
    if (sub != null) Text(sub, fontSize = 12.sp, color = Muted)
    Spacer(Modifier.height(12.dp))
}

@Composable
fun Caption(text: String) {
    Text(text.uppercase(), fontSize = 11.sp, color = Muted, letterSpacing = 1.sp, modifier = Modifier.padding(top = 6.dp, bottom = 6.dp))
}

@Composable
fun PixelCard(modifier: Modifier = Modifier, border: Color = Line, content: @Composable ColumnScope.() -> Unit) {
    Column(modifier.fillMaxWidth().background(Card).border(2.dp, border).padding(horizontal = 16.dp, vertical = 14.dp), content = content)
}

/** Пиксельная кнопка с «тенью», как на ПК. */
@Composable
fun PixelButton(text: String, modifier: Modifier = Modifier, accent: Boolean = true, enabled: Boolean = true, onClick: () -> Unit) {
    val bg = when { !enabled -> Line; accent -> Accent; else -> Card }
    val fg = when { !enabled -> Muted; accent -> Bg; else -> Fg }
    Box(modifier.height(48.dp)) {
        Box(Modifier.matchParentSize().padding(start = 4.dp, top = 4.dp).background(if (accent && enabled) AccentDark else Color(0xFF0A0C10)))
        Box(
            Modifier.fillMaxWidth().height(44.dp).background(bg).border(2.dp, if (accent && enabled) Accent else Line)
                .clickable(enabled = enabled) { onClick() },
            contentAlignment = Alignment.Center
        ) { Text(text, color = fg, fontSize = 14.sp, fontWeight = FontWeight.SemiBold) }
    }
}

@Composable
fun PixelToggle(on: Boolean) {
    Box(Modifier.size(52.dp, 28.dp).background(if (on) Accent else Line).padding(4.dp), contentAlignment = if (on) Alignment.CenterEnd else Alignment.CenterStart) {
        Box(Modifier.size(20.dp).background(if (on) Bg else Muted))
    }
}

@Composable
fun ToggleRow(title: String, sub: String?, on: Boolean, onClick: () -> Unit) {
    PixelCard(Modifier.clickable { onClick() }) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text(title, fontSize = 14.sp, color = Fg)
                if (sub != null) Text(sub, fontSize = 11.sp, color = Muted)
            }
            PixelToggle(on)
        }
    }
}

@Composable
fun Chip(text: String, on: Boolean, onClick: () -> Unit) {
    Box(
        Modifier.background(if (on) Card else Bg).border(2.dp, if (on) Accent else Line).clickable { onClick() }.padding(horizontal = 12.dp, vertical = 8.dp)
    ) { Text(text, fontSize = 13.sp, color = if (on) Fg else Muted) }
}

/** Полоса прогресса/процента. */
@Composable
fun Bar(frac: Float, color: Color, height: Dp = 6.dp, modifier: Modifier = Modifier) {
    Box(modifier.fillMaxWidth().height(height).background(Line)) {
        Box(Modifier.fillMaxWidth(frac.coerceIn(0f, 1f)).fillMaxHeight().background(color))
    }
}

fun pctColor(p: Int): Color = when { p >= 90 -> Accent; p >= 50 -> Amber; else -> Red }
