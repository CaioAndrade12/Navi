package com.caio.navi

import android.content.Context
import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioManager
import android.media.AudioTrack
import android.media.ToneGenerator
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.exp
import kotlin.math.sin

/**
 * Sons curtos da Navi, gerados na hora (sem arquivos de áudio).
 * Os sons "fada" são arpejos originais com timbre de sininho.
 */
object Sounds {
    val START_OPTIONS = linkedMapOf(
        "fada" to "Brilho de fada (subindo)",
        "sino" to "Sininho",
        "bipe" to "Bipe",
        "oi" to "Falar \"Oi?\"",
        "nenhum" to "Nenhum",
    )
    val END_OPTIONS = linkedMapOf(
        "fada" to "Brilho de fada (descendo)",
        "sino" to "Sininho",
        "ate_logo" to "Falar \"Até logo\"",
        "nenhum" to "Nenhum",
    )

    private const val RATE = 44100

    private data class Note(val at: Double, val freq: Double, val dur: Double, val amp: Double = 1.0)

    /** Mistura notas com timbre de sino: fundamental + harmônicos, ataque rápido e decaimento exponencial. */
    private fun render(notes: List<Note>, volume: Double): ShortArray {
        val total = notes.maxOf { it.at + it.dur } + 0.05
        val buf = DoubleArray((total * RATE).toInt())
        for (n in notes) {
            val start = (n.at * RATE).toInt()
            val len = (n.dur * RATE).toInt()
            for (i in 0 until len) {
                val idx = start + i
                if (idx >= buf.size) break
                val t = i.toDouble() / RATE
                val attack = if (t < 0.005) t / 0.005 else 1.0
                val env = attack * exp(-t * 6.0 / n.dur)
                val v = sin(2 * PI * n.freq * t) + 0.30 * sin(2 * PI * n.freq * 2.0 * t) +
                    0.10 * sin(2 * PI * n.freq * 3.02 * t)
                buf[idx] += v * env * n.amp
            }
        }
        val peak = buf.maxOf { abs(it) }.coerceAtLeast(1e-6)
        val gain = volume * 32767.0 / peak
        // Pequena rampa no fim para não estalar.
        val fade = minOf(buf.size, RATE / 50)
        return ShortArray(buf.size) { i ->
            val f = if (i > buf.size - fade) (buf.size - i).toDouble() / fade else 1.0
            (buf[i] * gain * f).toInt().coerceIn(-32767, 32767).toShort()
        }
    }

    // Frequências (Hz) de notas da escala de Mi maior, bem agudas: soam como brilho.
    private const val E6 = 1318.5
    private const val GS6 = 1661.2
    private const val B6 = 1975.5
    private const val CS7 = 2217.5
    private const val E7 = 2637.0
    private const val GS7 = 3322.4

    private val fairyUp by lazy {
        render(listOf(
            Note(0.00, E6, 0.30, 0.8), Note(0.05, GS6, 0.30, 0.8), Note(0.10, B6, 0.30, 0.8),
            Note(0.15, E7, 0.45, 0.9), Note(0.21, GS7, 0.40, 0.45), Note(0.21, E7 * 1.003, 0.40, 0.35),
        ), 0.55)
    }
    private val fairyDown by lazy {
        render(listOf(
            Note(0.00, GS7, 0.28, 0.5), Note(0.05, E7, 0.30, 0.7), Note(0.10, CS7, 0.30, 0.7),
            Note(0.15, B6, 0.32, 0.7), Note(0.21, E6, 0.50, 0.8),
        ), 0.5)
    }
    private val chime by lazy {
        render(listOf(Note(0.00, B6, 0.55, 0.9), Note(0.12, E6, 0.70, 0.9)), 0.5)
    }

    private suspend fun play(samples: ShortArray) = withContext(Dispatchers.IO) {
        val attrs = AudioAttributes.Builder()
            .setUsage(AudioAttributes.USAGE_ASSISTANT)
            .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION)
            .build()
        val fmt = AudioFormat.Builder()
            .setSampleRate(RATE)
            .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
            .setChannelMask(AudioFormat.CHANNEL_OUT_MONO)
            .build()
        val track = try {
            AudioTrack.Builder()
                .setAudioAttributes(attrs)
                .setAudioFormat(fmt)
                .setBufferSizeInBytes(samples.size * 2)
                .setTransferMode(AudioTrack.MODE_STATIC)
                .build()
        } catch (_: Exception) {
            return@withContext
        }
        try {
            track.write(samples, 0, samples.size)
            track.play()
            delay(samples.size * 1000L / RATE + 40)
        } catch (_: Exception) {
        } finally {
            try { track.stop() } catch (_: Exception) {}
            track.release()
        }
    }

    private suspend fun beep() {
        try {
            val tg = ToneGenerator(AudioManager.STREAM_MUSIC, 45)
            tg.startTone(ToneGenerator.TONE_PROP_ACK, 120)
            delay(220)
            tg.release()
        } catch (_: Exception) {
        }
    }

    /** Som de quando a Navi começa a ouvir. Só volta quando terminar (para o microfone não ouvir o som). */
    suspend fun playStart(c: Context, speaker: Speaker?) {
        when (Prefs.startSound(c)) {
            "fada" -> play(fairyUp)
            "sino" -> play(chime)
            "bipe" -> beep()
            "oi" -> speaker?.speak("Oi?")
            else -> {}
        }
    }

    /** Som de quando a Navi para de ouvir e volta a esperar "Navi". */
    suspend fun playEnd(c: Context, speaker: Speaker?) {
        when (Prefs.endSound(c)) {
            "fada" -> play(fairyDown)
            "sino" -> play(chime)
            "ate_logo" -> speaker?.speak("Até logo!")
            else -> {}
        }
    }

    /** Para o botão "Ouvir" dos ajustes. */
    suspend fun preview(c: Context, id: String, start: Boolean, speaker: Speaker?) {
        when (id) {
            "fada" -> play(if (start) fairyUp else fairyDown)
            "sino" -> play(chime)
            "bipe" -> beep()
            "oi" -> speaker?.speak("Oi?")
            "ate_logo" -> speaker?.speak("Até logo!")
        }
    }
}
