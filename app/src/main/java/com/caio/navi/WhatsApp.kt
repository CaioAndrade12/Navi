package com.caio.navi

import android.app.KeyguardManager
import android.content.Context
import android.content.Intent
import android.net.Uri
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import org.json.JSONObject

/** Envio de mensagens no WhatsApp. */
object WhatsApp {

    private fun installedPackage(c: Context): String? =
        listOf("com.whatsapp", "com.whatsapp.w4b").firstOrNull {
            try {
                c.packageManager.getPackageInfo(it, 0); true
            } catch (_: Exception) {
                false
            }
        }

    /** Descobre para quem mandar: devolve (nome, número) ou um texto de erro. */
    fun resolve(c: Context, to: String): Pair<Pair<String, String>?, String?> {
        val digits = Contacts.digits(to)
        if (digits.length >= 8) return (to to Contacts.toWhatsApp(to)) to null
        if (!Contacts.hasPermission(c)) return null to "Preciso da permissão de contatos para achar \"$to\". Libere nos ajustes da Navi."
        val found = Contacts.findPhones(c, to)
        if (found.isEmpty()) return null to "Não achei \"$to\" nos seus contatos."
        val best = found.first()
        return (best.name to Contacts.toWhatsApp(best.phone)) to null
    }

    suspend fun send(c: Context, to: String, text: String): JSONObject {
        // 1) Se tem notificação aberta dessa conversa, responde por ela (funciona até bloqueado).
        if (Contacts.digits(to).length < 8) {
            val replied = withContext(Dispatchers.Main) { NaviNotificationService.reply(c, to, text, "whatsapp") }
            if (replied != null) return ok("Mensagem enviada para $replied (pela notificação).")
        }

        // 2) Abre a conversa com o texto pronto e aperta "Enviar" pela acessibilidade.
        val pkg = installedPackage(c) ?: return fail("O WhatsApp não está instalado.")
        val (target, err) = resolve(c, to)
        if (target == null) return fail(err ?: "Não sei para quem mandar.")
        val (name, number) = target

        val km = c.getSystemService(KeyguardManager::class.java)
        if (km != null && km.isKeyguardLocked) {
            return fail("O celular está bloqueado. Desbloqueie para eu conseguir abrir o WhatsApp e mandar para $name.")
        }

        val uri = Uri.parse("https://api.whatsapp.com/send?phone=$number&text=" + Uri.encode(text))
        val intent = Intent(Intent.ACTION_VIEW, uri).setPackage(pkg)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP)

        val auto = NaviAccessibilityService.isEnabled(c) && NaviAccessibilityService.instance != null
        val waiter = if (auto) NaviAccessibilityService.expectSend(10_000) else null
        try {
            withContext(Dispatchers.Main) { c.startActivity(intent) }
        } catch (e: Exception) {
            NaviAccessibilityService.cancelSend()
            return fail("Não consegui abrir o WhatsApp: ${e.message}")
        }
        if (waiter == null) {
            return ok("Abri a conversa com $name com a mensagem pronta, mas é preciso tocar em enviar. " +
                "Ative a acessibilidade da Navi nos ajustes para ela enviar sozinha.")
        }
        val sent = withTimeoutOrNull(11_000) { waiter.await() } ?: false
        return if (sent) ok("Mensagem enviada para $name no WhatsApp.")
        else ok("Abri a conversa com $name com a mensagem escrita, mas não consegui apertar enviar. Confira a tela.")
    }
}
