package com.carlos.paperbot

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent

/** Reinicia el bot tras reiniciar el móvil o actualizar la app, si estaba activado. */
class BootReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (BotService.prefs(context).getBoolean("enabled", false)) {
            try { BotService.start(context) } catch (_: Exception) { }
        }
    }
}
