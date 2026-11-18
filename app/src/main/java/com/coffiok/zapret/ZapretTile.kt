package com.coffiok.zapret

import android.app.PendingIntent
import android.content.Intent
import android.net.VpnService
import android.os.Build
import android.service.quicksettings.Tile
import android.service.quicksettings.TileService

/** Плитка в шторке: включить / выключить обход одним касанием. */
class ZapretTile : TileService() {
    override fun onStartListening() { refresh() }

    override fun onClick() {
        if (ZapretVpnService.running(this)) ZapretVpnService.stop(this)
        else if (VpnService.prepare(this) == null) ZapretVpnService.start(this)
        else openApp()          // разрешение на VPN ещё не дано — спросит приложение
        refresh(!ZapretVpnService.running(this))
    }

    private fun openApp() {
        val i = Intent(this, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        if (Build.VERSION.SDK_INT >= 34) startActivityAndCollapse(PendingIntent.getActivity(this, 0, i, PendingIntent.FLAG_IMMUTABLE))
        else @Suppress("DEPRECATION") startActivityAndCollapse(i)
    }

    private fun refresh(on: Boolean = ZapretVpnService.running(this)) {
        val t = qsTile ?: return
        t.state = if (on) Tile.STATE_ACTIVE else Tile.STATE_INACTIVE
        t.label = "Zapret"
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) t.subtitle = if (on) Prefs(this).current().name else "выключен"
        t.updateTile()
    }
}
