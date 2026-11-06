package com.caio.navi

import android.Manifest
import android.app.SearchManager
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.hardware.camera2.CameraCharacteristics
import android.hardware.camera2.CameraManager
import android.media.AudioManager
import android.net.Uri
import android.os.BatteryManager
import android.provider.AlarmClock
import android.provider.MediaStore
import org.json.JSONObject
import java.util.Calendar

/** Ações no próprio celular. */
object PhoneActions {

    private fun start(c: Context, i: Intent) {
        c.startActivity(i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
    }

    fun alarm(c: Context, hour: Int, minute: Int, label: String?): JSONObject {
        val i = Intent(AlarmClock.ACTION_SET_ALARM)
            .putExtra(AlarmClock.EXTRA_HOUR, hour.coerceIn(0, 23))
            .putExtra(AlarmClock.EXTRA_MINUTES, minute.coerceIn(0, 59))
            .putExtra(AlarmClock.EXTRA_SKIP_UI, true)
        if (!label.isNullOrBlank()) i.putExtra(AlarmClock.EXTRA_MESSAGE, label)
        start(c, i)
        return ok("Alarme para %02d:%02d criado.".format(hour, minute))
    }

    fun timer(c: Context, seconds: Int, label: String?): JSONObject {
        val i = Intent(AlarmClock.ACTION_SET_TIMER)
            .putExtra(AlarmClock.EXTRA_LENGTH, seconds.coerceIn(1, 86_399))
            .putExtra(AlarmClock.EXTRA_SKIP_UI, true)
        if (!label.isNullOrBlank()) i.putExtra(AlarmClock.EXTRA_MESSAGE, label)
        start(c, i)
        return ok("Timer de $seconds segundos iniciado.")
    }

    fun openApp(c: Context, name: String): JSONObject {
        val pm = c.packageManager
        val launcher = Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER)
        val apps = pm.queryIntentActivities(launcher, 0)
        val wanted = Brain.normalize(name)
        fun label(r: android.content.pm.ResolveInfo) = Brain.normalize(r.loadLabel(pm).toString())
        val match = apps.firstOrNull { label(it) == wanted }
            ?: apps.firstOrNull { label(it).startsWith(wanted) }
            ?: apps.firstOrNull { label(it).contains(wanted) }
            ?: return fail("Não achei o app \"$name\" no celular.")
        val i = pm.getLaunchIntentForPackage(match.activityInfo.packageName)
            ?: return fail("Não consegui abrir ${match.loadLabel(pm)}.")
        start(c, i)
        return ok("Abri ${match.loadLabel(pm)}.")
    }

    fun call(c: Context, who: String): JSONObject {
        val digits = Contacts.digits(who)
        val (name, number) = if (digits.length >= 8) who to who else {
            val found = Contacts.findPhones(c, who).firstOrNull()
                ?: return fail(if (Contacts.hasPermission(c)) "Não achei \"$who\" nos contatos." else "Preciso da permissão de contatos.")
            found.name to found.phone
        }
        val canCall = c.checkSelfPermission(Manifest.permission.CALL_PHONE) == PackageManager.PERMISSION_GRANTED
        val i = Intent(if (canCall) Intent.ACTION_CALL else Intent.ACTION_DIAL, Uri.parse("tel:" + Uri.encode(number)))
        start(c, i)
        return ok(if (canCall) "Ligando para $name." else "Abri o discador com o número de $name; toque para ligar.")
    }

    fun flashlight(c: Context, on: Boolean): JSONObject {
        val cm = c.getSystemService(CameraManager::class.java) ?: return fail("Sem câmera.")
        val id = cm.cameraIdList.firstOrNull {
            cm.getCameraCharacteristics(it).get(CameraCharacteristics.FLASH_INFO_AVAILABLE) == true
        } ?: return fail("Este celular não tem flash.")
        cm.setTorchMode(id, on)
        return ok(if (on) "Lanterna ligada." else "Lanterna desligada.")
    }

    fun playMusic(c: Context, query: String): JSONObject {
        val i = Intent(MediaStore.INTENT_ACTION_MEDIA_PLAY_FROM_SEARCH)
            .putExtra(MediaStore.EXTRA_MEDIA_FOCUS, "vnd.android.cursor.item/*")
            .putExtra(SearchManager.QUERY, query)
        return try {
            start(c, i)
            ok("Tocando \"$query\".")
        } catch (_: Exception) {
            // Sem app de música compatível: abre a busca no YouTube.
            start(c, Intent(Intent.ACTION_VIEW, Uri.parse("https://www.youtube.com/results?search_query=" + Uri.encode(query))))
            ok("Abri a busca de \"$query\" no YouTube.")
        }
    }

    /** Horário de um lembrete: daqui a X minutos, ou hora:minuto (de hoje, amanhã ou de uma data). */
    fun reminderTime(minutes: Int, hour: Int, minute: Int, date: String): Long? {
        if (minutes > 0) return System.currentTimeMillis() + minutes * 60_000L
        if (hour !in 0..23) return null
        val cal = Calendar.getInstance()
        val d = Regex("(\\d{4})-(\\d{1,2})-(\\d{1,2})").find(date)
        if (d != null) {
            cal.set(d.groupValues[1].toInt(), d.groupValues[2].toInt() - 1, d.groupValues[3].toInt())
        }
        cal.set(Calendar.HOUR_OF_DAY, hour)
        cal.set(Calendar.MINUTE, minute.coerceIn(0, 59))
        cal.set(Calendar.SECOND, 0)
        cal.set(Calendar.MILLISECOND, 0)
        if (d == null && cal.timeInMillis <= System.currentTimeMillis()) cal.add(Calendar.DAY_OF_YEAR, 1)
        return cal.timeInMillis
    }

    fun battery(c: Context): JSONObject {
        val bm = c.getSystemService(BatteryManager::class.java)
        val level = bm?.getIntProperty(BatteryManager.BATTERY_PROPERTY_CAPACITY) ?: -1
        val charging = bm?.isCharging == true
        return JSONObject().put("status", "ok").put("bateria_porcentagem", level).put("carregando", charging)
    }

    fun mediaVolume(c: Context, percent: Int): JSONObject {
        val am = c.getSystemService(AudioManager::class.java) ?: return fail("Sem controle de áudio.")
        val max = am.getStreamMaxVolume(AudioManager.STREAM_MUSIC)
        am.setStreamVolume(AudioManager.STREAM_MUSIC, (max * percent.coerceIn(0, 100) / 100), 0)
        return ok("Volume de mídia do celular em ${percent.coerceIn(0, 100)}%.")
    }
}
