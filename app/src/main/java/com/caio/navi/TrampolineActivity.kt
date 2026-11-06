package com.caio.navi

import android.app.Activity
import android.os.Bundle

/** Tela invisível: abre o assistente e fecha na hora. */
class TrampolineActivity : Activity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        AssistLauncher.launchNow(this)
        finish()
        @Suppress("DEPRECATION")
        overridePendingTransition(0, 0)
    }
}
