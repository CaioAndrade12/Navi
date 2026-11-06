package com.caio.navi

import android.app.AlarmManager
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.media.AudioAttributes
import android.media.RingtoneManager
import android.os.Build
import org.json.JSONArray
import org.json.JSONObject
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Date
import java.util.Locale

/**
 * Lembretes e timers da própria Navi (AlarmManager): funcionam com o celular bloqueado
 * e com a tela apagada, sem depender de abrir o app de relógio.
 */
object Reminders {
    data class Item(val id: Int, val at: Long, val text: String, val kind: String) {
        fun label(): String {
            val now = Calendar.getInstance()
            val then = Calendar.getInstance().apply { timeInMillis = at }
            val sameDay = now.get(Calendar.DAY_OF_YEAR) == then.get(Calendar.DAY_OF_YEAR) &&
                now.get(Calendar.YEAR) == then.get(Calendar.YEAR)
            val fmt = if (sameDay) "HH:mm" else "dd/MM 'às' HH:mm"
            return SimpleDateFormat(fmt, Locale("pt", "BR")).format(Date(at))
        }

        fun toJson() = JSONObject().put("id", id).put("at", at).put("text", text).put("kind", kind)
    }

    private const val CHANNEL = "navi_reminders"

    @Synchronized
    fun list(c: Context): List<Item> = try {
        val a = JSONArray(Prefs.remindersRaw(c))
        (0 until a.length()).map {
            val o = a.getJSONObject(it)
            Item(o.getInt("id"), o.getLong("at"), o.getString("text"), o.optString("kind", "lembrete"))
        }.sortedBy { it.at }
    } catch (_: Exception) {
        emptyList()
    }

    @Synchronized
    private fun save(c: Context, items: List<Item>) {
        Prefs.setRemindersRaw(c, JSONArray(items.map { it.toJson() }).toString())
    }

    @Synchronized
    fun add(c: Context, at: Long, text: String, kind: String): Item {
        val items = list(c).filter { it.at > System.currentTimeMillis() - 60_000 }
        val id = ((items.maxOfOrNull { it.id } ?: 1000) + 1)
        val item = Item(id, at, text.take(200), kind)
        save(c, items + item)
        schedule(c, item)
        return item
    }

    @Synchronized
    fun remove(c: Context, id: Int): Item? {
        val items = list(c)
        val item = items.firstOrNull { it.id == id } ?: return null
        save(c, items.filter { it.id != id })
        cancel(c, id)
        return item
    }

    @Synchronized
    fun take(c: Context, id: Int): Item? {
        val items = list(c)
        val item = items.firstOrNull { it.id == id } ?: return null
        save(c, items.filter { it.id != id })
        return item
    }

    private fun pending(c: Context, id: Int): PendingIntent = PendingIntent.getBroadcast(
        c, id, Intent(c, ReminderReceiver::class.java).putExtra("id", id),
        PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
    )

    private fun schedule(c: Context, item: Item) {
        val am = c.getSystemService(AlarmManager::class.java) ?: return
        val pi = pending(c, item.id)
        val exact = Build.VERSION.SDK_INT < 31 || am.canScheduleExactAlarms()
        try {
            if (exact) am.setExactAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, item.at, pi)
            else am.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, item.at, pi)
        } catch (_: SecurityException) {
            am.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, item.at, pi)
        }
    }

    private fun cancel(c: Context, id: Int) {
        c.getSystemService(AlarmManager::class.java)?.cancel(pending(c, id))
    }

    /** Depois de reiniciar o celular, os alarmes do sistema somem: agenda tudo de novo. */
    fun rescheduleAll(c: Context) {
        val now = System.currentTimeMillis()
        for (item in list(c)) {
            if (item.at < now - 10 * 60_000) take(c, item.id) // muito antigo: descarta
            else schedule(c, item.copy(at = maxOf(item.at, now + 5_000)))
        }
    }

    fun createChannel(c: Context) {
        val ch = NotificationChannel(CHANNEL, "Lembretes e timers", NotificationManager.IMPORTANCE_HIGH)
        ch.setSound(
            RingtoneManager.getDefaultUri(RingtoneManager.TYPE_ALARM)
                ?: RingtoneManager.getDefaultUri(RingtoneManager.TYPE_NOTIFICATION),
            AudioAttributes.Builder().setUsage(AudioAttributes.USAGE_ALARM)
                .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION).build()
        )
        ch.enableVibration(true)
        ch.vibrationPattern = longArrayOf(0, 400, 200, 400, 200, 400)
        c.getSystemService(NotificationManager::class.java).createNotificationChannel(ch)
    }

    fun spokenText(c: Context, item: Item): String {
        val name = Prefs.userName(c)
        val who = if (name.isNotEmpty()) "$name, " else ""
        return if (item.kind == "timer") "${who}o timer acabou. ${item.text}".trim()
        else "${who}lembrete: ${item.text}"
    }

    fun notify(c: Context, item: Item) {
        createChannel(c)
        val open = PendingIntent.getActivity(
            c, item.id, Intent(c, MainActivity::class.java),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )
        val n = Notification.Builder(c, CHANNEL)
            .setSmallIcon(R.drawable.ic_sparkle)
            .setContentTitle(if (item.kind == "timer") "⏰ Timer da Navi" else "🔔 Lembrete da Navi")
            .setContentText(item.text)
            .setStyle(Notification.BigTextStyle().bigText(item.text))
            .setCategory(if (item.kind == "timer") Notification.CATEGORY_ALARM else Notification.CATEGORY_REMINDER)
            .setContentIntent(open)
            .setAutoCancel(true)
            .build()
        try {
            c.getSystemService(NotificationManager::class.java).notify(5000 + item.id, n)
        } catch (_: SecurityException) {
        }
    }
}

/** Dispara quando chega a hora de um lembrete ou timer. */
class ReminderReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val id = intent.getIntExtra("id", -1)
        val item = Reminders.take(context, id) ?: return
        Reminders.notify(context, item)
        if (NaviService.running) NaviService.say(context, Reminders.spokenText(context, item))
    }
}
