package com.caio.navi

import android.Manifest
import android.accessibilityservice.AccessibilityService
import android.app.KeyguardManager
import android.app.NotificationManager
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
import android.provider.Settings
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

    /** Volume do celular: mídia, toque ou alarme. [action]: consultar, definir, aumentar, diminuir, mudo. */
    fun volume(c: Context, action: String, level: Int?, kind: String): JSONObject {
        val am = c.getSystemService(AudioManager::class.java) ?: return fail("Sem controle de áudio.")
        val (stream, name) = when (kind) {
            "toque" -> AudioManager.STREAM_RING to "toque"
            "alarme" -> AudioManager.STREAM_ALARM to "alarme"
            else -> AudioManager.STREAM_MUSIC to "mídia"
        }
        val max = am.getStreamMaxVolume(stream).coerceAtLeast(1)
        val now = am.getStreamVolume(stream) * 100 / max
        val target = when (action) {
            "consultar" -> return ok("O volume de $name do celular está em $now%.")
            "mudo" -> 0
            "aumentar" -> now + (level ?: 15)
            "diminuir" -> now - (level ?: 15)
            else -> level ?: 50
        }.coerceIn(0, 100)
        return try {
            am.setStreamVolume(stream, (max * target + 50) / 100, 0)
            ok("Volume de $name do celular em $target%.")
        } catch (_: SecurityException) {
            askDnd(c)
        }
    }

    fun mediaVolume(c: Context, percent: Int): JSONObject = volume(c, "definir", percent, "midia")

    // ---------------- brilho ----------------

    private fun maxBrightness(): Int = try {
        val r = android.content.res.Resources.getSystem()
        val id = r.getIdentifier("config_screenBrightnessSettingMaximum", "integer", "android")
        if (id != 0) r.getInteger(id).coerceAtLeast(1) else 255
    } catch (_: Exception) {
        255
    }

    /** Brilho da tela (o controle do Android é "perceptual": metade da barra não é metade da luz). */
    fun brightness(c: Context, action: String, level: Int?): JSONObject {
        if (!Settings.System.canWrite(c)) {
            try {
                start(c, Intent(Settings.ACTION_MANAGE_WRITE_SETTINGS, Uri.parse("package:" + c.packageName)))
            } catch (_: Exception) {
            }
            return fail("Preciso da permissão \"Alterar configurações do sistema\". Abri a tela: ative para a Navi e peça de novo.")
        }
        val cr = c.contentResolver
        if (action == "automatico") {
            Settings.System.putInt(cr, Settings.System.SCREEN_BRIGHTNESS_MODE, Settings.System.SCREEN_BRIGHTNESS_MODE_AUTOMATIC)
            return ok("Brilho automático ligado.")
        }
        val max = maxBrightness()
        val raw = Settings.System.getInt(cr, Settings.System.SCREEN_BRIGHTNESS, max / 2)
        val now = (Math.pow(raw.toDouble() / max, 1 / 2.2) * 100).toInt().coerceIn(0, 100)
        val target = when (action) {
            "consultar" -> return ok("O brilho do celular está em $now%.")
            "aumentar" -> now + (level ?: 20)
            "diminuir" -> now - (level ?: 20)
            else -> level ?: 50
        }.coerceIn(1, 100)
        Settings.System.putInt(cr, Settings.System.SCREEN_BRIGHTNESS_MODE, Settings.System.SCREEN_BRIGHTNESS_MODE_MANUAL)
        Settings.System.putInt(cr, Settings.System.SCREEN_BRIGHTNESS,
            (Math.pow(target / 100.0, 2.2) * max).toInt().coerceIn(1, max))
        return ok("Brilho do celular em $target%.")
    }

    // ---------------- som do toque ----------------

    private fun askDnd(c: Context): JSONObject {
        try {
            start(c, Intent(Settings.ACTION_NOTIFICATION_POLICY_ACCESS_SETTINGS))
        } catch (_: Exception) {
        }
        return fail("Preciso do acesso ao \"Não perturbe\". Abri a tela: ative para a Navi e peça de novo.")
    }

    /** normal, vibrar, silencioso ou nao_perturbe. */
    fun ringerMode(c: Context, mode: String): JSONObject {
        val am = c.getSystemService(AudioManager::class.java) ?: return fail("Sem controle de áudio.")
        val nm = c.getSystemService(NotificationManager::class.java) ?: return fail("Sem notificações.")
        return try {
            when (mode) {
                "nao_perturbe" -> {
                    if (!nm.isNotificationPolicyAccessGranted) return askDnd(c)
                    nm.setInterruptionFilter(NotificationManager.INTERRUPTION_FILTER_PRIORITY)
                    ok("Não perturbe ligado.")
                }
                "silencioso" -> {
                    if (!nm.isNotificationPolicyAccessGranted) return askDnd(c)
                    am.ringerMode = AudioManager.RINGER_MODE_SILENT
                    ok("Celular no silencioso.")
                }
                "vibrar" -> {
                    am.ringerMode = AudioManager.RINGER_MODE_VIBRATE
                    ok("Celular no vibrar.")
                }
                else -> {
                    if (nm.isNotificationPolicyAccessGranted) nm.setInterruptionFilter(NotificationManager.INTERRUPTION_FILTER_ALL)
                    am.ringerMode = AudioManager.RINGER_MODE_NORMAL
                    ok("Som do celular de volta ao normal.")
                }
            }
        } catch (_: SecurityException) {
            askDnd(c)
        }
    }

    // ---------------- tela, rotas, ajustes, câmera ----------------

    private fun locked(c: Context) = c.getSystemService(KeyguardManager::class.java)?.isDeviceLocked == true

    /** bloquear, print, inicio, voltar, recentes, notificacoes, ajustes_rapidos (pela acessibilidade). */
    fun screen(c: Context, action: String): JSONObject {
        val svc = NaviAccessibilityService.instance
            ?: return fail("Ative a acessibilidade da Navi (Ajustes da Navi > Acessibilidade) para eu mexer na tela.")
        val (code, said) = when (action) {
            "bloquear" -> AccessibilityService.GLOBAL_ACTION_LOCK_SCREEN to "Tela bloqueada."
            "print" -> AccessibilityService.GLOBAL_ACTION_TAKE_SCREENSHOT to "Print tirado."
            "inicio" -> AccessibilityService.GLOBAL_ACTION_HOME to "Pronto, tela inicial."
            "voltar" -> AccessibilityService.GLOBAL_ACTION_BACK to "Voltei."
            "recentes" -> AccessibilityService.GLOBAL_ACTION_RECENTS to "Abri os apps recentes."
            "notificacoes" -> AccessibilityService.GLOBAL_ACTION_NOTIFICATIONS to "Abri as notificações."
            "ajustes_rapidos" -> AccessibilityService.GLOBAL_ACTION_QUICK_SETTINGS to "Abri os ajustes rápidos."
            else -> return fail("Ação de tela desconhecida: $action")
        }
        return if (svc.performGlobalAction(code)) ok(said) else fail("O Android não deixou fazer isso agora.")
    }

    /** Rota no Google Maps (ou no navegador se não tiver o Maps). [mode]: carro, a_pe, bicicleta, transporte. */
    fun navigate(c: Context, destination: String, mode: String): JSONObject {
        if (destination.isBlank()) return fail("Para onde?")
        val m = when (mode) { "a_pe" -> "w"; "bicicleta" -> "b"; "transporte" -> "r"; else -> "d" }
        val maps = Intent(Intent.ACTION_VIEW, Uri.parse("google.navigation:q=" + Uri.encode(destination) + "&mode=$m"))
            .setPackage("com.google.android.apps.maps")
        try {
            start(c, maps)
        } catch (_: Exception) {
            val travel = when (m) { "w" -> "walking"; "b" -> "bicycling"; "r" -> "transit"; else -> "driving" }
            start(c, Intent(Intent.ACTION_VIEW, Uri.parse("https://www.google.com/maps/dir/?api=1&destination=" +
                Uri.encode(destination) + "&travelmode=$travel")))
        }
        val r = ok("Abrindo a rota para $destination.").put("_fim_da_conversa", true)
        if (locked(c)) r.put("_desbloquear", true)
        return r
    }

    private val SETTINGS_PAGES = mapOf(
        "wifi" to Settings.Panel.ACTION_WIFI, "internet" to Settings.Panel.ACTION_INTERNET_CONNECTIVITY,
        "bluetooth" to Settings.ACTION_BLUETOOTH_SETTINGS, "som" to Settings.ACTION_SOUND_SETTINGS,
        "volume" to Settings.Panel.ACTION_VOLUME, "tela" to Settings.ACTION_DISPLAY_SETTINGS,
        "bateria" to Intent.ACTION_POWER_USAGE_SUMMARY, "apps" to Settings.ACTION_APPLICATION_SETTINGS,
        "armazenamento" to Settings.ACTION_INTERNAL_STORAGE_SETTINGS,
        "localizacao" to Settings.ACTION_LOCATION_SOURCE_SETTINGS, "dados_moveis" to Settings.ACTION_DATA_ROAMING_SETTINGS,
        "nfc" to Settings.ACTION_NFC_SETTINGS, "idioma" to Settings.ACTION_LOCALE_SETTINGS,
        "data_hora" to Settings.ACTION_DATE_SETTINGS, "seguranca" to Settings.ACTION_SECURITY_SETTINGS,
        "acessibilidade" to Settings.ACTION_ACCESSIBILITY_SETTINGS, "geral" to Settings.ACTION_SETTINGS,
    )
    val SETTINGS_KEYS = SETTINGS_PAGES.keys.toList()

    fun openSettings(c: Context, page: String): JSONObject {
        val action = SETTINGS_PAGES[page] ?: Settings.ACTION_SETTINGS
        try {
            start(c, Intent(action))
        } catch (_: Exception) {
            start(c, Intent(Settings.ACTION_SETTINGS))
        }
        val r = ok(if (page == "wifi" || page == "internet") "Abri o painel do Wi-Fi: o Android só deixa ligar e desligar por ali."
            else "Abri os ajustes de ${page.replace('_', ' ')} do celular.")
        if (locked(c)) r.put("_desbloquear", true)
        return r
    }

    fun camera(c: Context, video: Boolean, selfie: Boolean): JSONObject {
        val i = Intent(if (video) MediaStore.INTENT_ACTION_VIDEO_CAMERA else MediaStore.INTENT_ACTION_STILL_IMAGE_CAMERA)
        if (selfie) {
            i.putExtra("android.intent.extras.CAMERA_FACING", 1)
            i.putExtra("android.intent.extras.LENS_FACING_FRONT", 1)
            i.putExtra("android.intent.extra.USE_FRONT_CAMERA", true)
        }
        start(c, i)
        val r = ok(if (video) "Abri a câmera de vídeo." else if (selfie) "Abri a câmera frontal." else "Abri a câmera.")
            .put("_fim_da_conversa", true)
        if (locked(c)) r.put("_desbloquear", true)
        return r
    }
}
