package com.caio.navi

import android.content.Context
import android.media.AudioAttributes
import android.media.AudioFocusRequest
import android.media.AudioManager
import android.speech.tts.TextToSpeech
import android.speech.tts.UtteranceProgressListener
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.withTimeoutOrNull
import java.util.Locale
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap

/** A voz da Navi (texto para fala em português do Brasil). */
class Speaker(context: Context) {
    private val ctx = context.applicationContext
    private val ready = CompletableDeferred<Boolean>()
    private val done = ConcurrentHashMap<String, CompletableDeferred<Unit>>()
    private val audio = ctx.getSystemService(AudioManager::class.java)
    private val attrs = AudioAttributes.Builder()
        .setUsage(AudioAttributes.USAGE_ASSISTANT)
        .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
        .build()
    private val focus = AudioFocusRequest.Builder(AudioManager.AUDIOFOCUS_GAIN_TRANSIENT_MAY_DUCK)
        .setAudioAttributes(attrs)
        .build()

    private val tts: TextToSpeech

    init {
        // Adiado com post() para garantir que "tts" já foi atribuído quando o retorno chegar.
        val listener = TextToSpeech.OnInitListener { status ->
            android.os.Handler(android.os.Looper.getMainLooper()).post { onInit(status) }
        }
        val google = "com.google.android.tts"
        val hasGoogle = try {
            ctx.packageManager.getPackageInfo(google, 0); true
        } catch (_: Exception) {
            false
        }
        tts = if (hasGoogle) TextToSpeech(ctx, listener, google) else TextToSpeech(ctx, listener)
    }

    private fun onInit(status: Int) {
        if (status != TextToSpeech.SUCCESS) {
            ready.complete(false)
            return
        }
        val r = tts.setLanguage(Locale("pt", "BR"))
        if (r == TextToSpeech.LANG_MISSING_DATA || r == TextToSpeech.LANG_NOT_SUPPORTED) {
            tts.setLanguage(Locale("pt"))
        }
        tts.setAudioAttributes(attrs)
        tts.setSpeechRate(1.12f)
        tts.setOnUtteranceProgressListener(object : UtteranceProgressListener() {
            override fun onStart(utteranceId: String?) {}
            override fun onDone(utteranceId: String?) { finish(utteranceId) }
            @Deprecated("Deprecated in Java")
            override fun onError(utteranceId: String?) { finish(utteranceId) }
            override fun onError(utteranceId: String?, errorCode: Int) { finish(utteranceId) }
            override fun onStop(utteranceId: String?, interrupted: Boolean) { finish(utteranceId) }
        })
        ready.complete(true)
    }

    private fun finish(id: String?) {
        if (id != null) done.remove(id)?.complete(Unit)
    }

    /** Fala o texto e só volta quando terminar (ou se for interrompida). [volume]: 0 a 1. */
    suspend fun speak(text: String, volume: Float = 1f) {
        val clean = text.trim().take(3500)
        if (clean.isEmpty()) return
        if (withTimeoutOrNull(6000) { ready.await() } != true) return
        val id = UUID.randomUUID().toString()
        val d = CompletableDeferred<Unit>()
        done[id] = d
        try { audio?.requestAudioFocus(focus) } catch (_: Exception) {}
        try {
            val params = if (volume < 1f) android.os.Bundle().apply {
                putFloat(TextToSpeech.Engine.KEY_PARAM_VOLUME, volume.coerceIn(0f, 1f))
            } else null
            tts.speak(clean, TextToSpeech.QUEUE_FLUSH, params, id)
            // Tempo máximo generoso: ~80 ms por caractere + folga.
            withTimeoutOrNull(5000L + clean.length * 80L) { d.await() }
        } finally {
            done.remove(id)
            try { audio?.abandonAudioFocusRequest(focus) } catch (_: Exception) {}
        }
    }

    fun stop() {
        try { tts.stop() } catch (_: Exception) {}
        done.values.forEach { it.complete(Unit) }
        done.clear()
    }

    fun shutdown() {
        stop()
        try { tts.shutdown() } catch (_: Exception) {}
    }
}
