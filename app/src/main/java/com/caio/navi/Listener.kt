package com.caio.navi

import android.annotation.SuppressLint
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.media.AudioFormat
import android.media.AudioRecord
import android.media.MediaRecorder
import android.os.Bundle
import android.speech.RecognitionListener
import android.speech.RecognizerIntent
import android.speech.SpeechRecognizer
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import java.io.ByteArrayOutputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.coroutines.resume
import kotlin.math.sqrt

/** O que foi ouvido: texto, ou erro do reconhecedor (para cair no modo gravação). */
data class Heard(val text: String?, val error: Int? = null) {
    val silent get() = text.isNullOrBlank() && (error == null ||
        error == SpeechRecognizer.ERROR_NO_MATCH || error == SpeechRecognizer.ERROR_SPEECH_TIMEOUT)
    val broken get() = text.isNullOrBlank() && !silent
}

/** Escuta um comando falado (reconhecimento de voz do Android, em pt-BR). */
class Listener(context: Context) {
    private val ctx = context.applicationContext
    private var recognizer: SpeechRecognizer? = null
    var onPartial: ((String) -> Unit)? = null

    fun available(): Boolean = SpeechRecognizer.isRecognitionAvailable(ctx)

    private fun create(): SpeechRecognizer? {
        recognizer?.let { return it }
        val r = try {
            if (available()) SpeechRecognizer.createSpeechRecognizer(ctx)
            else SpeechRecognizer.createSpeechRecognizer(ctx, ComponentName(
                "com.google.android.googlequicksearchbox",
                "com.google.android.voicesearch.serviceapi.GoogleRecognitionService"))
        } catch (_: Exception) {
            null
        }
        recognizer = r
        return r
    }

    /** Precisa ser chamado na thread principal. */
    suspend fun listen(): Heard = withContext(Dispatchers.Main) {
        val r = create() ?: return@withContext Heard(null, SpeechRecognizer.ERROR_CLIENT)
        suspendCancellableCoroutine { cont ->
            var finished = false
            fun finish(h: Heard) {
                if (finished) return
                finished = true
                if (cont.isActive) cont.resume(h)
            }
            r.setRecognitionListener(object : RecognitionListener {
                override fun onReadyForSpeech(params: Bundle?) {}
                override fun onBeginningOfSpeech() {}
                override fun onRmsChanged(rmsdB: Float) {}
                override fun onBufferReceived(buffer: ByteArray?) {}
                override fun onEndOfSpeech() {}
                override fun onEvent(eventType: Int, params: Bundle?) {}
                override fun onError(error: Int) { finish(Heard(null, error)) }
                override fun onResults(results: Bundle?) {
                    val list = results?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)
                    finish(Heard(list?.firstOrNull()?.trim()))
                }
                override fun onPartialResults(partialResults: Bundle?) {
                    val list = partialResults?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)
                    list?.firstOrNull()?.takeIf { it.isNotBlank() }?.let { onPartial?.invoke(it) }
                }
            })
            val intent = Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH)
                .putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
                .putExtra(RecognizerIntent.EXTRA_LANGUAGE, "pt-BR")
                .putExtra(RecognizerIntent.EXTRA_LANGUAGE_PREFERENCE, "pt-BR")
                .putExtra(RecognizerIntent.EXTRA_PARTIAL_RESULTS, true)
                .putExtra(RecognizerIntent.EXTRA_MAX_RESULTS, 1)
                .putExtra(RecognizerIntent.EXTRA_CALLING_PACKAGE, ctx.packageName)
                .putExtra(RecognizerIntent.EXTRA_SPEECH_INPUT_COMPLETE_SILENCE_LENGTH_MILLIS, 1500L)
            try {
                r.startListening(intent)
            } catch (_: Exception) {
                finish(Heard(null, SpeechRecognizer.ERROR_CLIENT))
            }
            cont.invokeOnCancellation {
                try { r.cancel() } catch (_: Exception) {}
            }
        }
    }

    fun stop() {
        try { recognizer?.cancel() } catch (_: Exception) {}
    }

    fun destroy() {
        try { recognizer?.destroy() } catch (_: Exception) {}
        recognizer = null
    }
}

/**
 * Plano B: grava a fala direto do microfone (até a pessoa parar de falar)
 * e devolve um WAV para mandar ao Gemini, que entende áudio.
 */
object VoiceRecorder {
    private const val RATE = 16000

    @SuppressLint("MissingPermission")
    fun record(maxMs: Int = 12_000, waitSpeechMs: Int = 6_000): ByteArray? {
        val minBuf = AudioRecord.getMinBufferSize(RATE, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT)
        val rec = try {
            AudioRecord(MediaRecorder.AudioSource.VOICE_RECOGNITION, RATE, AudioFormat.CHANNEL_IN_MONO,
                AudioFormat.ENCODING_PCM_16BIT, maxOf(minBuf, RATE))
        } catch (_: Exception) {
            return null
        }
        if (rec.state != AudioRecord.STATE_INITIALIZED) {
            rec.release()
            return null
        }
        val frame = ShortArray(RATE / 50) // 20 ms
        val pcm = ByteArrayOutputStream()
        val preroll = ArrayDeque<ShortArray>()
        var noise = 0.0
        var frames = 0
        var started = false
        var loud = 0
        var quiet = 0
        try {
            rec.startRecording()
            while (true) {
                val n = rec.read(frame, 0, frame.size)
                if (n <= 0) break
                frames++
                var sum = 0.0
                for (i in 0 until n) sum += frame[i].toDouble() * frame[i]
                val rms = sqrt(sum / n)
                if (frames <= 15) { // primeiros 300 ms: mede o ruído do ambiente
                    noise += rms / 15
                    continue
                }
                val threshold = maxOf(noise * 2.5, 450.0)
                val chunk = frame.copyOf(n)
                if (!started) {
                    preroll.addLast(chunk)
                    if (preroll.size > 15) preroll.removeFirst()
                    loud = if (rms > threshold) loud + 1 else 0
                    if (loud >= 3) {
                        started = true
                        preroll.forEach { write(pcm, it) }
                        preroll.clear()
                    } else if (frames * 20 > waitSpeechMs) {
                        return null // ninguém falou
                    }
                } else {
                    write(pcm, chunk)
                    quiet = if (rms < threshold * 0.8) quiet + 1 else 0
                    if (quiet >= 45 || frames * 20 > maxMs) break // 900 ms de silêncio
                }
            }
        } catch (_: Exception) {
            return null
        } finally {
            try { rec.stop() } catch (_: Exception) {}
            rec.release()
        }
        if (!started) return null
        return wav(pcm.toByteArray())
    }

    private fun write(out: ByteArrayOutputStream, s: ShortArray) {
        val bb = ByteBuffer.allocate(s.size * 2).order(ByteOrder.LITTLE_ENDIAN)
        for (v in s) bb.putShort(v)
        out.write(bb.array())
    }

    private fun wav(pcm: ByteArray): ByteArray {
        val h = ByteBuffer.allocate(44).order(ByteOrder.LITTLE_ENDIAN)
        h.put("RIFF".toByteArray()); h.putInt(36 + pcm.size); h.put("WAVE".toByteArray())
        h.put("fmt ".toByteArray()); h.putInt(16); h.putShort(1); h.putShort(1)
        h.putInt(RATE); h.putInt(RATE * 2); h.putShort(2); h.putShort(16)
        h.put("data".toByteArray()); h.putInt(pcm.size)
        return h.array() + pcm
    }
}
