package com.caio.navi

import android.app.Activity
import android.app.KeyguardManager
import android.content.Context
import android.content.Intent
import android.os.Bundle

/**
 * Tela invisível que acende o celular e mostra o desbloqueio (digital/PIN).
 * Usada quando a Navi precisa ler mensagens ou e-mails com o celular bloqueado.
 */
class UnlockActivity : Activity() {

    companion object {
        fun request(c: Context) {
            try {
                c.startActivity(Intent(c, UnlockActivity::class.java)
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_NO_ANIMATION))
            } catch (_: Exception) {
                // Sem permissão para abrir telas em segundo plano: a pessoa desbloqueia sozinha.
            }
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setShowWhenLocked(true)
        setTurnScreenOn(true)
        val km = getSystemService(KeyguardManager::class.java)
        if (km == null || !km.isKeyguardLocked) {
            finish()
            return
        }
        km.requestDismissKeyguard(this, object : KeyguardManager.KeyguardDismissCallback() {
            override fun onDismissSucceeded() { finish() }
            override fun onDismissCancelled() { finish() }
            override fun onDismissError() { finish() }
        })
    }
}
