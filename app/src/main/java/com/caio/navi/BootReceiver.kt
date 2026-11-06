package com.caio.navi

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent

/** Religa a Navi depois de reiniciar o celular ou de atualizar o app. */
class BootReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (!Prefs.enabled(context)) return
        try {
            NaviService.start(context)
        } catch (e: Exception) {
            NaviService.notifyTapToStart(context)
        }
    }
}
