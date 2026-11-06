package com.caio.navi

import android.accessibilityservice.AccessibilityService
import android.content.ComponentName
import android.content.Context
import android.os.Handler
import android.os.Looper
import android.provider.Settings
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo
import kotlinx.coroutines.CompletableDeferred

/**
 * Serviço de acessibilidade usado só para apertar "Enviar" no WhatsApp
 * depois que a Navi abre a conversa com a mensagem já escrita.
 * Ele só olha o WhatsApp e só age quando a Navi pediu um envio.
 */
class NaviAccessibilityService : AccessibilityService() {

    companion object {
        @Volatile var instance: NaviAccessibilityService? = null
            private set
        @Volatile private var pending: CompletableDeferred<Boolean>? = null
        @Volatile private var pendingUntil = 0L

        fun isEnabled(c: Context): Boolean {
            val flat = Settings.Secure.getString(c.contentResolver, Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES).orEmpty()
            val me = ComponentName(c, NaviAccessibilityService::class.java).flattenToString()
            return flat.split(":").any { it.equals(me, ignoreCase = true) }
        }

        /** Avisa que um envio vai acontecer; o resultado diz se o botão foi apertado. */
        fun expectSend(timeoutMs: Long): CompletableDeferred<Boolean> {
            pending?.complete(false)
            val d = CompletableDeferred<Boolean>()
            pendingUntil = System.currentTimeMillis() + timeoutMs
            pending = d
            return d
        }

        fun cancelSend() {
            pending?.complete(false)
            pending = null
        }
    }

    private val handler = Handler(Looper.getMainLooper())

    override fun onServiceConnected() {
        instance = this
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {
        val p = pending ?: return
        if (System.currentTimeMillis() > pendingUntil) {
            pending = null
            p.complete(false)
            return
        }
        val pkg = event?.packageName?.toString() ?: return
        if (!pkg.startsWith("com.whatsapp")) return
        val root = rootInActiveWindow ?: return
        val button = root.findAccessibilityNodeInfosByViewId("$pkg:id/send").firstOrNull()
            ?: findByDescription(root, setOf("enviar", "send"))
            ?: return
        if (!button.isEnabled) return
        if (click(button)) {
            pending = null
            p.complete(true)
            // Sai da conversa e volta para onde o usuário estava.
            handler.postDelayed({ performGlobalAction(GLOBAL_ACTION_BACK) }, 800)
            handler.postDelayed({ performGlobalAction(GLOBAL_ACTION_BACK) }, 1500)
        }
    }

    private fun click(node: AccessibilityNodeInfo): Boolean {
        var n: AccessibilityNodeInfo? = node
        var depth = 0
        while (n != null && depth < 4) {
            if (n.isClickable && n.performAction(AccessibilityNodeInfo.ACTION_CLICK)) return true
            n = n.parent
            depth++
        }
        return false
    }

    private fun findByDescription(root: AccessibilityNodeInfo, wanted: Set<String>): AccessibilityNodeInfo? {
        val queue = ArrayDeque<AccessibilityNodeInfo>()
        queue.add(root)
        var visited = 0
        while (queue.isNotEmpty() && visited < 400) {
            val n = queue.removeFirst()
            visited++
            val desc = n.contentDescription?.toString()?.trim()?.lowercase()
            if (desc != null && desc in wanted) return n
            for (i in 0 until n.childCount) n.getChild(i)?.let { queue.add(it) }
        }
        return null
    }

    override fun onInterrupt() {}

    override fun onDestroy() {
        if (instance === this) instance = null
        super.onDestroy()
    }
}
