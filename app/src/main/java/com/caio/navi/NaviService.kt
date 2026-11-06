package com.caio.navi

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.KeyguardManager
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.media.AudioAttributes
import android.media.AudioFocusRequest
import android.media.AudioManager
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
import org.vosk.android.StorageService

/**
 * Fica escutando a palavra "Navi" o tempo todo, offline (Vosk).
 * "Navi, liga a TV" dito de uma vez já vira pedido (o áudio do pedido é recortado do que foi ouvido);
 * "Navi" sozinho toca o som e escuta o pedido. Depois de responder, continua ouvindo uns segundos
 * (ignorando conversas que não são com ela) até a pessoa parar de falar ou agradecer.
 */
class NaviService : Service() {

    companion object {
        const val ACTION_STOP = "com.caio.navi.STOP"
        const val ACTION_TALK = "com.caio.navi.TALK"
        const val ACTION_STOP_TALK = "com.caio.navi.STOP_TALK"
        const val ACTION_SAY = "com.caio.navi.SAY"

        /**
         * Palavras parecidas com "Navi" que também entram na gramática do reconhecedor offline:
         * assim ele tem para onde "mandar" sons parecidos, em vez de forçar tudo a virar "Navi".
         * Nada de pedaços que juntos formam "na-vi" (como "na" e "vi"): eles roubavam o "Navi" de verdade.
         */
        private val DISTRACTORS = listOf(
            "nada", "nova", "novo", "nove", "neve", "navio", "naval", "navegar", "vida", "davi", "levi",
            "então", "isso", "aqui", "agora",
        )
        /** Quanto tempo esperar a pessoa voltar a falar depois de uma resposta. */
        private const val FOLLOW_UP_MS = 5500L
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

        /** Fala um aviso (lembrete, timer) se a Navi estiver ligada. */
        fun say(c: Context, text: String) {
            try {
                c.startService(Intent(c, NaviService::class.java).setAction(ACTION_SAY).putExtra("text", text))
            } catch (_: Exception) {
            }
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
    private var ear: WakeEar? = null
    private var words: List<String> = emptyList()
    private var lastTrigger = 0L
    private var loading = false

    private var speaker: Speaker? = null
    private var hearer: Listener? = null
    private var conversation: Job? = null
    private var recorderMode = false // reconhecedor do Android falhou: grava e manda o áudio pro Gemini
    private var quietUntil = 0L // ignora a palavra de ativação logo depois de uma conversa (eco dos sons)
    private val audio by lazy { getSystemService(AudioManager::class.java) }
    private val focusRequest by lazy {
        AudioFocusRequest.Builder(AudioManager.AUDIOFOCUS_GAIN_TRANSIENT)
            .setAudioAttributes(AudioAttributes.Builder()
                .setUsage(AudioAttributes.USAGE_ASSISTANT)
                .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH).build())
            .setOnAudioFocusChangeListener { }
            .build()
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        speaker = Speaker(this)
        hearer = Listener(this).also { l -> l.onPartial = { setStatus("Ouvindo: $it") } }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_SAY) {
            val text = intent.getStringExtra("text").orEmpty()
            if (!running) {
                stopSelf()
                return START_NOT_STICKY
            }
            if (text.isNotEmpty()) scope.launch { announce(text) }
            return START_STICKY
        }
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
        if (intent?.action == ACTION_TALK) startConversation(null)
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
        if (ear != null || conversation?.isActive == true) return
        try {
            val grammar = JSONArray(words + DISTRACTORS.filter { it !in words } + "[unk]").toString()
            val e = WakeEar(m, grammar,
                onPartial = { json -> if (wakeHit(json, final = false) != null) trigger(null) },
                onFinal = { json -> onWakeFinal(json) },
                onError = { ex ->
                    setStatus("Erro no microfone: ${ex.message}. Tentando de novo...")
                    restartListening(5)
                })
            e.start()
            ear = e
            setStatus("Escutando \"Navi\"...")
        } catch (e: Exception) {
            stopListening()
            setStatus("Microfone ocupado ou sem permissão. Tentando de novo...")
            restartListening(5)
        }
    }

    private fun stopListening() {
        try { ear?.stop() } catch (_: Exception) {}
        ear = null
    }

    private fun restartListening(delaySec: Int) {
        handler.removeCallbacksAndMessages(null)
        stopListening()
        handler.postDelayed({ if (running) startListening() }, delaySec * 1000L)
    }

    /** Onde o "Navi" terminou, onde a fala terminou e quantas palavras vieram depois (o pedido). */
    private class WakeHit(val wordEnd: Double, val speechEnd: Double, val after: Int)

    /**
     * Decide se a frase ouvida pelo reconhecedor offline foi mesmo um chamado: "Navi" no começo da
     * fala (no máximo depois de um "ô"/"ei" curtinho) e com confiança suficiente para a sensibilidade.
     * O resto da frase pode ser o pedido.
     */
    private fun wakeHit(json: String, final: Boolean): WakeHit? {
        if (SystemClock.elapsedRealtime() < quietUntil) return null
        val o = try { JSONObject(json) } catch (_: Exception) { return null }
        val sens = Prefs.wakeSensitivity(this)
        if (!final) {
            // Só no modo "alta": age assim que ouve "Navi" sozinho (mais rápido, mas sem pedido junto).
            val p = o.optString("partial").trim()
            return if (sens == 2 && p in words) WakeHit(-1.0, -1.0, 0) else null
        }
        val res = o.optJSONArray("result") ?: return null
        val ws = (0 until res.length()).mapNotNull { res.optJSONObject(it) }
        val idx = ws.indexOfFirst { it.optString("word") in words }
        if (idx < 0 || idx > 1) return null
        val w = ws[idx]
        val minConf = when (sens) { 0 -> 0.85; 1 -> 0.6; else -> 0.4 }
        if (w.optDouble("conf", 1.0) < minConf) return null
        if (idx == 1) {
            val before = ws[0]
            val longBefore = before.optDouble("end") - before.optDouble("start") > 0.6
            val gap = w.optDouble("start") - before.optDouble("end")
            if (longBefore || gap > 0.5) return null // era o meio de outra frase
        }
        return WakeHit(w.optDouble("end"), ws.last().optDouble("end", w.optDouble("end")), ws.size - idx - 1)
    }

    /** Resultado final do Vosk: "Navi" sozinho chama; "Navi, <pedido>" já leva o áudio do pedido. */
    private fun onWakeFinal(json: String) {
        val hit = wakeHit(json, final = true) ?: return
        val command = if (hit.after >= 1 && hit.speechEnd - hit.wordEnd >= 0.4) {
            ear?.wav(hit.wordEnd + 0.03, hit.speechEnd + 0.35)
        } else null
        trigger(command)
    }

    private fun trigger(command: ByteArray?) {
        val now = SystemClock.elapsedRealtime()
        if (now - lastTrigger < 2500) return
        lastTrigger = now

        if (!Prefs.ownBrain(this) || !hasBrain()) {
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
        startConversation(command)
    }

    private fun hasBrain() = Prefs.geminiKey(this).isNotEmpty() || Llm.hasBackups(this)

    // ---------------- Conversa ----------------

    /** [command]: áudio do pedido dito junto com o "Navi" (ou null para escutar agora). */
    private fun startConversation(command: ByteArray? = null) {
        if (conversation?.isActive == true) return
        if (!hasBrain()) {
            setStatus("Falta a chave do Gemini nos ajustes.")
            return
        }
        handler.removeCallbacksAndMessages(null)
        stopListening()
        Prefs.logEvent(this, "conversa iniciada")
        talking = true
        conversation = scope.launch {
            var hadFocus = false
            try {
                delay(60) // o microfone do "Navi" já foi solto em stopListening()
                // Pausa a música/vídeo enquanto a Navi escuta (volta sozinha no fim).
                hadFocus = try {
                    audio?.requestAudioFocus(focusRequest) == AudioManager.AUDIOFOCUS_REQUEST_GRANTED
                } catch (_: Exception) {
                    false
                }
                vibrate()
                val brain = NaviCore.brain(this@NaviService)
                // "Navi, liga a TV" de uma vez: transcreve o pedido enquanto o som toca.
                var firstText: String? = null
                if (command != null) {
                    setStatus("Entendendo...")
                    val sound = launch { Sounds.playStart(this@NaviService, null) }
                    firstText = withContext(Dispatchers.IO) {
                        try { Llm.transcribe(this@NaviService, command) } catch (_: Exception) { null }
                    }?.trim()?.ifEmpty { null }
                    sound.join()
                } else {
                    Sounds.playStart(this@NaviService, speaker)
                }
                var turns = 0
                var followUp = false
                while (running && turns < 20) {
                    turns++
                    val ready = firstText
                    firstText = null
                    val heardNow = if (ready != null) ready to null else {
                        if (turns == 1 && command != null) Sounds.playStart(this@NaviService, speaker) // não entendeu o pedido junto
                        setStatus(if (followUp) "Ouvindo (fale sem dizer Navi)..." else "Ouvindo...")
                        hear(if (followUp) FOLLOW_UP_MS else 7000L) ?: break
                    }
                    val (text, audioWav) = heardNow
                    NaviCore.add(true, text ?: "🎤 (áudio)")
                    setStatus("Pensando...")
                    var reply = brain.handle(text, audioWav, followUp = followUp)
                    while (true) {
                        deliver(reply)
                        if (!reply.waitUnlock) break
                        setStatus("Esperando você desbloquear o celular...")
                        val unlocked = waitUnlocked(45_000)
                        setStatus("Pensando...")
                        reply = brain.handle(if (unlocked) "(pronto, desbloqueei)" else "(não desbloqueei)")
                    }
                    if (reply.endConversation || reply.silent) break
                    val asked = reply.expectReply || brain.hasPendingQuestion()
                    if (!asked && !Prefs.followUp(this@NaviService)) break
                    // Se a Navi perguntou algo, a próxima fala é a resposta; se não, pode nem ser com ela.
                    followUp = !asked
                    delay(80)
                }
            } catch (e: kotlinx.coroutines.CancellationException) {
                throw e
            } catch (e: Exception) {
                setStatus("Erro na conversa: ${e.message}")
            } finally {
                withContext(kotlinx.coroutines.NonCancellable) {
                    try {
                        if (running && audio?.isMusicActive != true) Sounds.playEnd(this@NaviService, speaker)
                    } catch (_: Exception) {
                    }
                    if (hadFocus) try { audio?.abandonAudioFocusRequest(focusRequest) } catch (_: Exception) {}
                    if (MediaControl.pauseAfterConversation) {
                        MediaControl.pauseAfterConversation = false
                        delay(700)
                        try { MediaControl.control(this@NaviService, "pausar") } catch (_: Exception) {}
                        MediaControl.pauseAfterConversation = false
                    }
                    conversation = null
                    talking = false
                    quietUntil = SystemClock.elapsedRealtime() + 1200
                    setStatus(if (running) "Escutando \"Navi\"..." else "Desligada")
                    if (running) {
                        delay(150)
                        startListening()
                    }
                }
            }
        }
    }

    /** Mostra a resposta na conversa e fala. */
    private suspend fun deliver(reply: Reply) {
        if (reply.silent || reply.text.isBlank()) return
        NaviCore.add(false, reply.text)
        setStatus("Falando...")
        speaker?.speak(reply.text)
    }

    private suspend fun waitUnlocked(ms: Long): Boolean {
        val km = getSystemService(KeyguardManager::class.java) ?: return false
        val end = SystemClock.elapsedRealtime() + ms
        while (SystemClock.elapsedRealtime() < end) {
            if (!km.isDeviceLocked) return true
            delay(400)
        }
        return !km.isDeviceLocked
    }

    /** Aviso falado fora de uma conversa (lembretes, timers). */
    private suspend fun announce(text: String) {
        NaviCore.add(false, text)
        if (conversation?.isActive == true) return // já está conversando: só mostra na tela
        handler.removeCallbacksAndMessages(null)
        stopListening()
        try {
            Sounds.playStart(this, null)
            speaker?.speak(text)
        } finally {
            quietUntil = SystemClock.elapsedRealtime() + 1500
            if (running && conversation?.isActive != true) startListening()
        }
    }

    /** Escuta uma fala. Devolve (texto, áudio) ou null se a pessoa não falou nada. */
    private suspend fun hear(startTimeoutMs: Long): Pair<String?, ByteArray?>? {
        if (!recorderMode) {
            val h = hearer?.listen(startTimeoutMs) ?: Heard(null, SpeechRecognizer.ERROR_CLIENT)
            if (!h.text.isNullOrBlank()) return h.text to null
            if (h.silent) return null
            // O reconhecedor do celular não funcionou: passa a gravar o áudio e mandar pro Gemini.
            recorderMode = true
            hearer?.destroy()
            delay(200)
        }
        val wav = withContext(Dispatchers.IO) {
            VoiceRecorder.record(waitSpeechMs = startTimeoutMs.toInt())
        } ?: return null
        setStatus("Entendendo o áudio...")
        val text = withContext(Dispatchers.IO) {
            try {
                Llm.transcribe(this@NaviService, wav)
            } catch (_: Exception) {
                null
            }
        }
        if (text != null && text.isBlank()) return null // ninguém falou de verdade
        return if (text != null) text to null else null to wav
    }

    private fun vibrate() {
        if (!Prefs.vibrate(this)) return
        try {
            getSystemService(Vibrator::class.java)
                ?.vibrate(VibrationEffect.createOneShot(40, VibrationEffect.DEFAULT_AMPLITUDE))
        } catch (_: Exception) {
        }
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
