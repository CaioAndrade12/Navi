package com.caio.navi

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.media.AudioManager
import android.media.ToneGenerator
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.os.SystemClock
import android.os.VibrationEffect
import android.os.Vibrator
import android.speech.SpeechRecognizer
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import org.vosk.Model
import org.vosk.Recognizer
import org.vosk.android.RecognitionListener
import org.vosk.android.SpeechService
import org.vosk.android.StorageService

/**
 * Fica escutando a palavra "Navi" o tempo todo, offline (Vosk).
 * Quando ouve, solta o microfone, escuta o pedido, pensa (Gemini), responde em voz alta
 * e continua a conversa por alguns segundos sem precisar repetir "Navi".
 */
class NaviService : Service(), RecognitionListener {

    companion object {
        const val ACTION_STOP = "com.caio.navi.STOP"
        const val ACTION_TALK = "com.caio.navi.TALK"
        const val ACTION_STOP_TALK = "com.caio.navi.STOP_TALK"
        private const val CHANNEL = "navi_listen"
        private const val CHANNEL_ALERT = "navi_alert"
        private const val NOTIF_ID = 1
        private const val NOTIF_TAP_ID = 2

        @Volatile var running = false
            private set
        @Volatile var status = "Desligada"
            private set
        @Volatile var listener: ((String) -> Unit)? = null
        /** true enquanto uma conversa por voz está acontecendo. */
        @Volatile var talking = false
            private set

        fun start(c: Context) {
            c.startForegroundService(Intent(c, NaviService::class.java))
        }

        fun stop(c: Context) {
            c.startService(Intent(c, NaviService::class.java).setAction(ACTION_STOP))
        }

        /** Para a conversa e a fala na hora. */
        fun stopTalking(c: Context) {
            c.startService(Intent(c, NaviService::class.java).setAction(ACTION_STOP_TALK))
        }

        /** Começa uma conversa agora, sem precisar dizer "Navi". */
        fun talk(c: Context) {
            c.startForegroundService(Intent(c, NaviService::class.java).setAction(ACTION_TALK))
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
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
    private var model: Model? = null
    private var recognizer: Recognizer? = null
    private var speech: SpeechService? = null
    private var words: List<String> = emptyList()
    private var lastTrigger = 0L
    private var loading = false

    private var speaker: Speaker? = null
    private var hearer: Listener? = null
    private var conversation: Job? = null
    private var recorderMode = false // reconhecedor do Android falhou: grava e manda o áudio pro Gemini

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        speaker = Speaker(this)
        hearer = Listener(this).also { l -> l.onPartial = { setStatus("Ouvindo: $it") } }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_STOP_TALK) {
            speaker?.stop()
            hearer?.stop()
            conversation?.cancel()
            return START_STICKY
        }
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

        words = Prefs.words(this)
        if (!running) {
            running = true
            loadModelAndListen()
            refreshPcToolsQuietly()
        } else if (model != null && conversation?.isActive != true) {
            // Ajustes podem ter mudado: reinicia a escuta com as palavras novas.
            restartListening(0)
        }
        if (intent?.action == ACTION_TALK) startConversation()
        return START_STICKY
    }

    private fun refreshPcToolsQuietly() {
        if (Prefs.pcHost(this).isEmpty()) return
        scope.launch(Dispatchers.IO) { try { PcClient.refreshTools(this@NaviService) } catch (_: Exception) {} }
    }

    // ---------------- Palavra de ativação (Vosk) ----------------

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
        if (speech != null || conversation?.isActive == true) return
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
        if (now - lastTrigger < 2500) return
        lastTrigger = now

        if (!Prefs.ownBrain(this) || Prefs.geminiKey(this).isEmpty()) {
            // Modo antigo (ou sem chave do Gemini): abre o assistente do Google.
            val pause = Prefs.pauseSec(this)
            handler.removeCallbacksAndMessages(null)
            stopListening()
            Prefs.logEvent(this, "ouvi \"Navi\" e chamei o Gemini")
            setStatus("Chamei o Gemini! Volto a escutar em ${pause}s.")
            AssistLauncher.launchFromBackground(this)
            handler.postDelayed({ if (running) startListening() }, pause * 1000L)
            return
        }
        startConversation()
    }

    // ---------------- Conversa ----------------

    private fun startConversation() {
        if (conversation?.isActive == true) return
        if (Prefs.geminiKey(this).isEmpty()) {
            setStatus("Falta a chave do Gemini nos ajustes.")
            return
        }
        handler.removeCallbacksAndMessages(null)
        stopListening()
        Prefs.logEvent(this, "conversa iniciada")
        talking = true
        conversation = scope.launch {
            try {
                delay(250) // dá tempo do microfone ser liberado
                beep()
                var turns = 0
                while (running && turns < 12) {
                    turns++
                    setStatus("Ouvindo...")
                    val (text, audio) = hear() ?: break
                    NaviCore.add(true, text ?: "🎤 (áudio)")
                    setStatus("Pensando...")
                    val reply = NaviCore.brain(this@NaviService).handle(text, audio)
                    NaviCore.add(false, reply.text)
                    setStatus("Falando...")
                    speaker?.speak(reply.text)
                    val brain = NaviCore.brain(this@NaviService)
                    if (!(Prefs.followUp(this@NaviService) || reply.expectReply || brain.hasPendingQuestion())) break
                    delay(150)
                }
            } catch (e: kotlinx.coroutines.CancellationException) {
                throw e
            } catch (e: Exception) {
                setStatus("Erro na conversa: ${e.message}")
            } finally {
                withContext(kotlinx.coroutines.NonCancellable) {
                    conversation = null
                    talking = false
                    setStatus(if (running) "Escutando \"Navi\"..." else "Desligada")
                    if (running) {
                        delay(300)
                        startListening()
                    }
                }
            }
        }
    }

    /** Escuta uma fala. Devolve (texto, áudio) ou null se a pessoa não falou nada. */
    private suspend fun hear(): Pair<String?, ByteArray?>? {
        if (!recorderMode) {
            val h = hearer?.listen() ?: Heard(null, SpeechRecognizer.ERROR_CLIENT)
            if (!h.text.isNullOrBlank()) return h.text to null
            if (h.silent) return null
            // O reconhecedor do celular não funcionou: passa a gravar o áudio e mandar pro Gemini.
            recorderMode = true
            hearer?.destroy()
            delay(200)
        }
        val wav = withContext(Dispatchers.IO) { VoiceRecorder.record() } ?: return null
        setStatus("Entendendo o áudio...")
        val text = withContext(Dispatchers.IO) {
            try {
                Gemini.transcribe(Prefs.geminiKey(this@NaviService), Prefs.geminiModel(this@NaviService), wav)
            } catch (_: Exception) {
                null
            }
        }
        if (text != null && text.isBlank()) return null // ninguém falou de verdade
        return if (text != null) text to null else null to wav
    }

    private fun beep() {
        if (Prefs.vibrate(this)) {
            try {
                getSystemService(Vibrator::class.java)
                    ?.vibrate(VibrationEffect.createOneShot(50, VibrationEffect.DEFAULT_AMPLITUDE))
            } catch (_: Exception) {}
        }
        try {
            val tg = ToneGenerator(AudioManager.STREAM_MUSIC, 45)
            tg.startTone(ToneGenerator.TONE_PROP_ACK, 120)
            handler.postDelayed({ tg.release() }, 400)
        } catch (_: Exception) {}
    }

    // ---- RecognitionListener (Vosk) ----
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
        val talk = PendingIntent.getService(
            this, 2, Intent(this, NaviService::class.java).setAction(ACTION_TALK),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )
        return Notification.Builder(this, CHANNEL)
            .setSmallIcon(R.drawable.ic_sparkle)
            .setContentTitle("Navi")
            .setContentText(text)
            .setContentIntent(open)
            .setOngoing(true)
            .addAction(Notification.Action.Builder(null, "Falar", talk).build())
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
        conversation?.cancel()
        scope.cancel()
        handler.removeCallbacksAndMessages(null)
        stopListening()
        speaker?.shutdown()
        hearer?.destroy()
        if (!loading) {
            try { model?.close() } catch (_: Exception) {}
            model = null
        }
        status = "Desligada"
        listener?.invoke(status)
        super.onDestroy()
    }
}
