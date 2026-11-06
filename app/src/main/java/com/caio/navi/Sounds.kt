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
import java.util.concurrent.ConcurrentHashMap
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.exp
import kotlin.math.sin

/**
 * Sons curtos da Navi, gerados na hora (sem arquivos de áudio).
 * Os sons "fada" são arpejos originais com timbre suave de sininho, curtos para não atrasar a escuta.
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
    val VOLUME_OPTIONS = linkedMapOf("0" to "Baixo", "1" to "Médio", "2" to "Alto")

    private const val RATE = 44100

    /** Força do som (fração do máximo) para cada nível. */
    private fun gain(c: Context) = when (Prefs.soundVolume(c)) { 0 -> 0.10; 1 -> 0.20; else -> 0.34 }
    private fun ttsVolume(c: Context) = when (Prefs.soundVolume(c)) { 0 -> 0.45f; 1 -> 0.7f; else -> 1f }
    private fun beepVolume(c: Context) = when (Prefs.soundVolume(c)) { 0 -> 18; 1 -> 32; else -> 50 }

    private data class Note(val at: Double, val freq: Double, val dur: Double, val amp: Double = 1.0)

    /** Mistura notas com timbre de sino suave: fundamental + pouco harmônico, ataque rápido e decaimento. */
    private fun render(notes: List<Note>, volume: Double): ShortArray {
        val total = notes.maxOf { it.at + it.dur } + 0.03
        val buf = DoubleArray((total * RATE).toInt())
        for (n in notes) {
            val start = (n.at * RATE).toInt()
            val len = (n.dur * RATE).toInt()
            for (i in 0 until len) {
                val idx = start + i
                if (idx >= buf.size) break
                val t = i.toDouble() / RATE
                val attack = if (t < 0.008) t / 0.008 else 1.0
                val env = attack * exp(-t * 5.5 / n.dur)
                val v = sin(2 * PI * n.freq * t) + 0.12 * sin(2 * PI * n.freq * 2.0 * t) +
                    0.03 * sin(2 * PI * n.freq * 3.01 * t)
                buf[idx] += v * env * n.amp
            }
        }
        val peak = buf.maxOf { abs(it) }.coerceAtLeast(1e-6)
        val g = volume * 32767.0 / peak
        val fade = minOf(buf.size, RATE / 40) // rampa no fim para não estalar
        return ShortArray(buf.size) { i ->
            val f = if (i > buf.size - fade) (buf.size - i).toDouble() / fade else 1.0
            (buf[i] * g * f).toInt().coerceIn(-32767, 32767).toShort()
        }
    }

    // Uma oitava abaixo da versão antiga (mais suave): Mi maior.
    private const val E5 = 659.3
    private const val GS5 = 830.6
    private const val B5 = 987.8
    private const val CS6 = 1108.7
    private const val E6 = 1318.5
    private const val GS6 = 1661.2

    private val NOTES = mapOf(
        "fada_up" to listOf(Note(0.00, E5, 0.16, 0.8), Note(0.045, GS5, 0.16, 0.8), Note(0.09, B5, 0.18, 0.85),
            Note(0.135, E6, 0.26, 0.9)),
        "fada_down" to listOf(Note(0.00, E6, 0.16, 0.7), Note(0.05, CS6, 0.17, 0.75), Note(0.10, B5, 0.18, 0.8),
            Note(0.15, GS5, 0.20, 0.8), Note(0.20, E5, 0.30, 0.85)),
        "sino" to listOf(Note(0.00, GS6, 0.30, 0.8), Note(0.09, E6, 0.38, 0.85)),
    )
    private val cache = ConcurrentHashMap<String, ShortArray>()

    private fun samples(id: String, volume: Double): ShortArray =
        cache.getOrPut("$id@$volume") { render(NOTES.getValue(id), volume) }

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
            delay(samples.size * 1000L / RATE + 20)
        } catch (_: Exception) {
        } finally {
            try { track.stop() } catch (_: Exception) {}
            track.release()
        }
    }

    private suspend fun beep(c: Context) {
        try {
            val tg = ToneGenerator(AudioManager.STREAM_MUSIC, beepVolume(c))
            tg.startTone(ToneGenerator.TONE_PROP_ACK, 100)
            delay(160)
            tg.release()
        } catch (_: Exception) {
        }
    }

    private suspend fun playId(c: Context, id: String, start: Boolean, speaker: Speaker?) {
        when (id) {
            "fada" -> play(samples(if (start) "fada_up" else "fada_down", gain(c)))
            "sino" -> play(samples("sino", gain(c)))
            "bipe" -> beep(c)
            "oi" -> speaker?.speak("Oi?", ttsVolume(c))
            "ate_logo" -> speaker?.speak("Até logo!", ttsVolume(c))
        }
    }

    /** Som de quando a Navi começa a ouvir. Só volta quando terminar (para o microfone não ouvir o som). */
    suspend fun playStart(c: Context, speaker: Speaker?) = playId(c, Prefs.startSound(c), true, speaker)

    /** Som de quando a Navi para de ouvir e volta a esperar "Navi". */
    suspend fun playEnd(c: Context, speaker: Speaker?) = playId(c, Prefs.endSound(c), false, speaker)

    /** Para o botão "Ouvir" dos ajustes. */
    suspend fun preview(c: Context, id: String, start: Boolean, speaker: Speaker?) = playId(c, id, start, speaker)
}
