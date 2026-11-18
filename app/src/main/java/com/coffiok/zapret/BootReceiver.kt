package com.coffiok.zapret

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.net.VpnService

/** Запуск обхода при включении телефона (если включено в настройках и разрешение на VPN уже дано). */
class BootReceiver : BroadcastReceiver() {
    override fun onReceive(ctx: Context, intent: Intent) {
        if (intent.action != Intent.ACTION_BOOT_COMPLETED && intent.action != Intent.ACTION_MY_PACKAGE_REPLACED) return
        if (intent.action == Intent.ACTION_MY_PACKAGE_REPLACED && !Prefs(ctx).autostart) return
        if (!Prefs(ctx).autostart) return
        if (VpnService.prepare(ctx) == null) ZapretVpnService.start(ctx)
    }
}
