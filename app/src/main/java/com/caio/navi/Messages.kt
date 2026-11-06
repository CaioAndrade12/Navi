package com.caio.navi

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/** Uma mensagem capturada das notificações. */
data class Msg(
    val app: String,          // "whatsapp", "instagram", "gmail"...
    val conversation: String, // nome do contato ou grupo (ou remetente do e-mail)
    val sender: String,       // quem mandou (em grupos é diferente da conversa)
    val text: String,
    val time: Long,
    var read: Boolean = false,
) {
    val id get() = "$app|$conversation|$sender|$time|${text.hashCode()}"

    fun toJson() = JSONObject()
        .put("app", app).put("conversa", conversation).put("de", sender)
        .put("texto", text).put("hora", timeLabel(time))

    companion object {
        fun timeLabel(t: Long): String {
            val now = System.currentTimeMillis()
            val fmt = if (now - t < 20 * 3600_000L) "HH:mm" else "dd/MM HH:mm"
            return SimpleDateFormat(fmt, Locale("pt", "BR")).format(Date(t))
        }
    }
}

/** Guarda as últimas mensagens recebidas (sobrevive a reinícios do app). */
object MessageStore {
    val APPS = mapOf(
        "com.whatsapp" to "whatsapp",
        "com.whatsapp.w4b" to "whatsapp",
        "com.instagram.android" to "instagram",
        "com.google.android.gm" to "gmail",
        "org.telegram.messenger" to "telegram",
        "com.google.android.apps.messaging" to "sms",
        "com.android.mms" to "sms",
    )

    private const val MAX = 300
    private val items = ArrayList<Msg>()
    private var loaded = false

    private fun file(c: Context) = File(c.filesDir, "messages.json")

    @Synchronized
    private fun load(c: Context) {
        if (loaded) return
        loaded = true
        try {
            val arr = JSONArray(file(c).readText())
            for (i in 0 until arr.length()) {
                val o = arr.getJSONObject(i)
                items += Msg(o.getString("app"), o.getString("c"), o.getString("s"),
                    o.getString("t"), o.getLong("time"), o.optBoolean("r"))
            }
        } catch (_: Exception) {
        }
    }

    @Synchronized
    private fun save(c: Context) {
        try {
            val arr = JSONArray()
            for (m in items) arr.put(JSONObject().put("app", m.app).put("c", m.conversation)
                .put("s", m.sender).put("t", m.text).put("time", m.time).put("r", m.read))
            file(c).writeText(arr.toString())
        } catch (_: Exception) {
        }
    }

    @Synchronized
    fun add(c: Context, list: List<Msg>) {
        load(c)
        val known = items.map { it.id }.toHashSet()
        var changed = false
        for (m in list) {
            if (m.text.isBlank() || m.id in known) continue
            items += m
            changed = true
        }
        if (!changed) return
        items.sortBy { it.time }
        while (items.size > MAX) items.removeAt(0)
        save(c)
    }

    /** Busca mensagens. Marca como lidas as que forem devolvidas. */
    @Synchronized
    fun query(c: Context, app: String?, from: String?, unreadOnly: Boolean, limit: Int): List<Msg> {
        load(c)
        val wantedApp = app?.lowercase(Locale.ROOT)?.takeIf { it.isNotEmpty() && it != "todos" }
        val who = from?.let { Brain.normalize(it) }?.takeIf { it.isNotEmpty() }
        val res = items.asReversed().asSequence()
            .filter { wantedApp == null || it.app == wantedApp }
            .filter { !unreadOnly || !it.read }
            .filter {
                who == null || Brain.normalize(it.conversation).contains(who) ||
                    Brain.normalize(it.sender).contains(who)
            }
            .take(limit.coerceIn(1, 40))
            .toList()
        if (res.any { !it.read }) {
            res.forEach { it.read = true }
            save(c)
        }
        return res.reversed()
    }

    @Synchronized
    fun unreadCount(c: Context): Int {
        load(c)
        return items.count { !it.read }
    }

    @Synchronized
    fun clear(c: Context) {
        load(c)
        items.clear()
        save(c)
    }
}
