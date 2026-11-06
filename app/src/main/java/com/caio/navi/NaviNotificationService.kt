package com.caio.navi

import android.app.Notification
import android.app.Person
import android.app.RemoteInput
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.Bundle
import android.os.Parcelable
import android.provider.Settings
import android.service.notification.NotificationListenerService
import android.service.notification.StatusBarNotification

/**
 * Lê as notificações de WhatsApp, Instagram, Gmail etc. para a Navi poder
 * ler as mensagens em voz alta e responder pela própria notificação.
 */
class NaviNotificationService : NotificationListenerService() {

    companion object {
        @Volatile var instance: NaviNotificationService? = null
            private set

        fun isEnabled(c: Context): Boolean {
            val flat = Settings.Secure.getString(c.contentResolver, "enabled_notification_listeners").orEmpty()
            val me = ComponentName(c, NaviNotificationService::class.java).flattenToString()
            return flat.split(":").any { it == me }
        }

        private val SUMMARY = Regex("^\\d+ (novas |new )?(mensagens|messages|conversas|chats)", RegexOption.IGNORE_CASE)

        /** Extrai as mensagens de uma notificação. */
        fun extract(sbn: StatusBarNotification): List<Msg> {
            val app = MessageStore.APPS[sbn.packageName] ?: return emptyList()
            val n = sbn.notification
            if (n.flags and Notification.FLAG_GROUP_SUMMARY != 0) return emptyList()
            if (n.flags and Notification.FLAG_ONGOING_EVENT != 0) return emptyList()
            val ex = n.extras ?: return emptyList()
            val title = ex.getCharSequence(Notification.EXTRA_TITLE)?.toString().orEmpty()
            val convTitle = ex.getCharSequence(Notification.EXTRA_CONVERSATION_TITLE)?.toString()
            val conversation = (convTitle ?: title).trim()

            @Suppress("DEPRECATION")
            val parcels: Array<Parcelable>? = ex.getParcelableArray(Notification.EXTRA_MESSAGES)
            if (!parcels.isNullOrEmpty()) {
                return parcels.mapNotNull { p ->
                    val b = p as? Bundle ?: return@mapNotNull null
                    val text = b.getCharSequence("text")?.toString() ?: return@mapNotNull null
                    @Suppress("DEPRECATION")
                    val person = if (Build.VERSION.SDK_INT >= 28) b.getParcelable<Person>("sender_person") else null
                    val sender = person?.name?.toString() ?: b.getCharSequence("sender")?.toString() ?: conversation
                    Msg(app, conversation, sender, text, b.getLong("time", sbn.postTime))
                }.filter { !SUMMARY.containsMatchIn(it.text) }
            }

            val big = ex.getCharSequence(Notification.EXTRA_BIG_TEXT)?.toString()
            val text = (big ?: ex.getCharSequence(Notification.EXTRA_TEXT)?.toString()).orEmpty().trim()
            if (text.isEmpty() || SUMMARY.containsMatchIn(text) || conversation.isEmpty()) return emptyList()
            // No Gmail, o título é o remetente e o texto traz assunto + começo do e-mail.
            return listOf(Msg(app, conversation, conversation, text.take(1200), sbn.postTime))
        }

        private fun replyAction(n: Notification): Notification.Action? {
            n.actions?.firstOrNull { !it.remoteInputs.isNullOrEmpty() }?.let { return it }
            return try {
                Notification.WearableExtender(n).actions.firstOrNull { !it.remoteInputs.isNullOrEmpty() }
            } catch (_: Exception) {
                null
            }
        }

        /**
         * Responde uma conversa pela notificação (funciona com a tela bloqueada).
         * Devolve o nome da conversa respondida, ou null se não há notificação aberta dela.
         */
        fun reply(c: Context, who: String, text: String, app: String?): String? {
            val svc = instance ?: return null
            val wanted = Brain.normalize(who)
            val active = try { svc.activeNotifications.toList() } catch (_: Exception) { emptyList() }
            val candidates = active
                .filter { MessageStore.APPS.containsKey(it.packageName) }
                .filter { app.isNullOrEmpty() || app == "todos" || MessageStore.APPS[it.packageName] == app }
                .filter { replyAction(it.notification) != null }
            fun convOf(s: StatusBarNotification): String {
                val ex = s.notification.extras
                return (ex.getCharSequence(Notification.EXTRA_CONVERSATION_TITLE)
                    ?: ex.getCharSequence(Notification.EXTRA_TITLE))?.toString().orEmpty()
            }
            val target = candidates.firstOrNull { Brain.normalize(convOf(it)) == wanted }
                ?: candidates.firstOrNull { Brain.normalize(convOf(it)).contains(wanted) }
                ?: candidates.firstOrNull { wanted.isNotEmpty() && wanted.contains(Brain.normalize(convOf(it))) }
                ?: return null
            val action = replyAction(target.notification) ?: return null
            val inputs = action.remoteInputs
            val results = Bundle()
            for (ri in inputs) results.putCharSequence(ri.resultKey, text)
            val intent = Intent()
            RemoteInput.addResultsToIntent(inputs, intent, results)
            return try {
                action.actionIntent.send(c, 0, intent)
                MessageStore.add(c, listOf(Msg(MessageStore.APPS[target.packageName] ?: "app",
                    convOf(target), "Eu (Navi)", text, System.currentTimeMillis(), read = true)))
                convOf(target)
            } catch (_: Exception) {
                null
            }
        }
    }

    override fun onListenerConnected() {
        instance = this
        // Pega o que já estava na barra de notificações.
        try {
            activeNotifications?.forEach { MessageStore.add(this, extract(it)) }
        } catch (_: Exception) {
        }
    }

    override fun onListenerDisconnected() {
        if (instance === this) instance = null
    }

    override fun onNotificationPosted(sbn: StatusBarNotification) {
        try {
            val msgs = extract(sbn)
            if (msgs.isNotEmpty()) MessageStore.add(this, msgs)
        } catch (_: Exception) {
        }
    }

    override fun onDestroy() {
        if (instance === this) instance = null
        super.onDestroy()
    }
}
