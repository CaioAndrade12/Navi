package com.caio.navi

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.os.SystemClock
import org.json.JSONArray
import org.json.JSONObject
import org.vosk.Model
import org.vosk.Recognizer
import org.vosk.android.RecognitionListener
import org.vosk.android.SpeechService
import org.vosk.android.StorageService

/**
 * Fica escutando a palavra "Navi" o tempo todo, offline, e chama o Gemini quando ouve.
 * Enquanto o Gemini está aberto, solta o microfone para não atrapalhar.
 */
class NaviService : Service(), RecognitionListener {

    companion object {
        const val ACTION_STOP = "com.caio.navi.STOP"
        private const val CHANNEL = "navi_listen"
        private const val CHANNEL_ALERT = "navi_alert"
        private const val NOTIF_ID = 1
        private const val NOTIF_TAP_ID = 2

        @Volatile var running = false
            private set
        @Volatile var status = "Desligada"
            private set
        var listener: ((String) -> Unit)? = null

        fun start(c: Context) {
            c.startForegroundService(Intent(c, NaviService::class.java))
        }

        fun stop(c: Context) {
            c.startService(Intent(c, NaviService::class.java).setAction(ACTION_STOP))
        }

        fun createChannels(c: Context) {
            val nm = c.getSystemService(NotificationManager::class.java)
            nm.createNotificationChannel(
                NotificationChannel(CHANNEL, "Navi escutando", NotificationManager.IMPORTANCE_LOW)
                    .apply { setShowBadge(false) }
            )
            nm.createNotificationChannel(
                NotificationChannel(CHANNEL_ALERT, "Avisos da Navi", NotificationManager.IMPORTANCE_HIGH)
            )
        }

        /** Usado quando o Android não deixa ligar o microfone sozinho (ex.: logo após reiniciar). */
        fun notifyTapToStart(c: Context) {
            createChannels(c)
            val open = PendingIntent.getActivity(
                c, 10,
                Intent(c, MainActivity::class.java).putExtra(MainActivity.EXTRA_AUTOSTART, true),
                PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
            )
            val n = Notification.Builder(c, CHANNEL_ALERT)
                .setSmallIcon(R.drawable.ic_sparkle)
                .setContentTitle("Toque para ligar a Navi")
                .setContentText("O Android pede um toque para liberar o microfone.")
                .setContentIntent(open)
                .setAutoCancel(true)
                .build()
            c.getSystemService(NotificationManager::class.java).notify(NOTIF_TAP_ID, n)
        }
    }

    private val handler = Handler(Looper.getMainLooper())
    private var model: Model? = null
    private var recognizer: Recognizer? = null
    private var speech: SpeechService? = null
    private var words: List<String> = emptyList()
    private var lastTrigger = 0L
    private var loading = false

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_STOP) {
            Prefs.setEnabled(this, false)
            stopForeground(STOP_FOREGROUND_REMOVE)
            stopSelf()
            return START_NOT_STICKY
        }

        createChannels(this)
        try {
            startForeground(NOTIF_ID, buildNotification("Preparando..."),
                ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE)
        } catch (e: Exception) {
            // Android 14+ bloqueia microfone iniciado em segundo plano (ex.: no boot).
            notifyTapToStart(this)
            stopSelf()
            return START_NOT_STICKY
        }
        getSystemService(NotificationManager::class.java).cancel(NOTIF_TAP_ID)

        if (!running) {
            running = true
            words = Prefs.words(this)
            loadModelAndListen()
        } else {
            // Ajustes podem ter mudado: reinicia a escuta com as palavras novas.
            words = Prefs.words(this)
            if (model != null) restartListening(0)
        }
        return START_STICKY
    }

    private fun loadModelAndListen() {
        if (model != null) { startListening(); return }
        if (loading) return
        loading = true
        setStatus("Carregando o reconhecimento de voz (só demora na 1ª vez)...")
        StorageService.unpack(this, "model-pt", "model",
            { m ->
                loading = false
                model = m
                if (running) startListening() else m.close()
            },
            { e ->
                loading = false
                setStatus("Erro ao carregar o modelo de voz: ${e.message}")
            })
    }

    private fun startListening() {
        val m = model ?: return
        if (speech != null) return
        try {
            val grammar = JSONArray(words + "[unk]").toString()
            val rec = Recognizer(m, 16000f, grammar)
            val s = SpeechService(rec, 16000f)
            recognizer = rec
            speech = s
            s.startListening(this)
            setStatus("Escutando \"Navi\"...")
        } catch (e: Exception) {
            stopListening()
            setStatus("Microfone ocupado ou sem permissão. Tentando de novo...")
            restartListening(5)
        }
    }

    private fun stopListening() {
        try { speech?.stop() } catch (_: Exception) {}
        try { speech?.shutdown() } catch (_: Exception) {}
        try { recognizer?.close() } catch (_: Exception) {}
        speech = null
        recognizer = null
    }

    private fun restartListening(delaySec: Int) {
        handler.removeCallbacksAndMessages(null)
        stopListening()
        handler.postDelayed({ if (running) startListening() }, delaySec * 1000L)
    }

    private fun heard(json: String, key: String): Boolean {
        val text = try { JSONObject(json).optString(key) } catch (_: Exception) { "" }
        if (text.isBlank()) return false
        return text.split(" ").any { it in words }
    }

    private fun trigger() {
        val now = SystemClock.elapsedRealtime()
        if (now - lastTrigger < 3000) return
        lastTrigger = now

        val pause = Prefs.pauseSec(this)
        // Solta o microfone para o Gemini poder escutar.
        handler.removeCallbacksAndMessages(null)
        stopListening()
        Prefs.logEvent(this, "ouvi \"Navi\" e chamei o assistente")
        setStatus("Chamei o Gemini! Volto a escutar em ${pause}s.")
        AssistLauncher.launchFromBackground(this)
        handler.postDelayed({ if (running) startListening() }, pause * 1000L)
    }

    // ---- RecognitionListener ----
    override fun onPartialResult(hypothesis: String?) {
        if (hypothesis != null && heard(hypothesis, "partial")) trigger()
    }

    override fun onResult(hypothesis: String?) {
        if (hypothesis != null && heard(hypothesis, "text")) trigger()
    }

    override fun onFinalResult(hypothesis: String?) {}

    override fun onError(exception: Exception?) {
        setStatus("Erro no microfone: ${exception?.message}. Tentando de novo...")
        restartListening(5)
    }

    override fun onTimeout() {
        restartListening(1)
    }

    // ---- Notificação e status ----
    private fun buildNotification(text: String): Notification {
        val open = PendingIntent.getActivity(
            this, 0, Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )
        val stop = PendingIntent.getService(
            this, 1, Intent(this, NaviService::class.java).setAction(ACTION_STOP),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )
        return Notification.Builder(this, CHANNEL)
            .setSmallIcon(R.drawable.ic_sparkle)
            .setContentTitle("Navi")
            .setContentText(text)
            .setContentIntent(open)
            .setOngoing(true)
            .addAction(Notification.Action.Builder(null, "Desligar", stop).build())
            .build()
    }

    private fun setStatus(text: String) {
        status = text
        if (running) {
            try {
                getSystemService(NotificationManager::class.java)
                    .notify(NOTIF_ID, buildNotification(text))
            } catch (_: Exception) {}
        }
        listener?.invoke(text)
    }

    override fun onDestroy() {
        running = false
        handler.removeCallbacksAndMessages(null)
        stopListening()
        if (!loading) {
            try { model?.close() } catch (_: Exception) {}
            model = null
        }
        status = "Desligada"
        listener?.invoke(status)
        super.onDestroy()
    }
}
