package com.caio.navi

import android.content.Context
import android.content.Intent
import android.os.PowerManager
import android.os.VibrationEffect
import android.os.Vibrator

/** Abre o Gemini (ou o assistente padrão do celular) no modo de escuta. */
object AssistLauncher {

    private const val GEMINI_PKG = "com.google.android.apps.bard"

    fun candidates(c: Context): List<Intent> {
        val voice = Intent(Intent.ACTION_VOICE_COMMAND)
        val assist = Intent(Intent.ACTION_ASSIST)
        val gemini = c.packageManager.getLaunchIntentForPackage(GEMINI_PKG)
        val order = when (Prefs.mode(c)) {
            Prefs.MODE_ASSIST -> listOf(assist, voice, gemini)
            Prefs.MODE_GEMINI -> listOf(gemini, voice, assist)
            else -> listOf(voice, assist, gemini)
        }
        return order.filterNotNull()
            .filter { it.resolveActivity(c.packageManager) != null }
            .map { it.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP) }
    }

    /** Chamado pelo serviço em segundo plano: acorda a tela e passa pela tela invisível. */
    fun launchFromBackground(c: Context) {
        feedback(c)
        wakeScreen(c)
        val i = Intent(c, TrampolineActivity::class.java)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_NO_ANIMATION)
        try {
            c.startActivity(i)
        } catch (e: Exception) {
            // Se a tela invisível for bloqueada, tenta abrir direto.
            launchNow(c)
        }
    }

    /** Abre de fato. Retorna false se nenhum assistente foi encontrado. */
    fun launchNow(c: Context): Boolean {
        for (intent in candidates(c)) {
            try {
                c.startActivity(intent)
                return true
            } catch (_: Exception) {
            }
        }
        return false
    }

    private fun feedback(c: Context) {
        if (!Prefs.vibrate(c)) return
        try {
            @Suppress("DEPRECATION")
            val v = c.getSystemService(Vibrator::class.java)
            v?.vibrate(VibrationEffect.createOneShot(60, VibrationEffect.DEFAULT_AMPLITUDE))
        } catch (_: Exception) {
        }
    }

    private fun wakeScreen(c: Context) {
        try {
            val pm = c.getSystemService(PowerManager::class.java) ?: return
            if (!pm.isInteractive) {
                @Suppress("DEPRECATION")
                pm.newWakeLock(
                    PowerManager.FULL_WAKE_LOCK or PowerManager.ACQUIRE_CAUSES_WAKEUP,
                    "navi:wake"
                ).acquire(5_000)
            }
        } catch (_: Exception) {
        }
    }
}
