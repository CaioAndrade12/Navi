package com.caio.navi

import android.annotation.SuppressLint
import android.media.AudioFormat
import android.media.AudioRecord
import android.media.MediaRecorder
import android.os.Handler
import android.os.Looper
import org.vosk.Model
import org.vosk.Recognizer
import java.nio.ByteBuffer
import java.nio.ByteOrder

/**
 * Microfone da palavra de ativação. Passa o áudio para o Vosk (offline) e guarda os últimos
 * segundos: assim, quando a pessoa fala "Navi, liga a TV" de uma vez, o pedido é recortado do
 * áudio guardado e ninguém precisa repetir depois do som.
 *
 * Os tempos que o Vosk devolve ("start"/"end" de cada palavra) contam desde o [start], igual a
 * este gravador, então dá para achar o trecho certo no áudio guardado.
 */
class WakeEar(
    model: Model,
    grammar: String,
    private val onPartial: (String) -> Unit,
    private val onFinal: (String) -> Unit,
    private val onError: (Exception) -> Unit,
) {
    companion object {
        const val RATE = 16000
        private const val KEEP_SECONDS = 15
    }

    private val recognizer = Recognizer(model, RATE.toFloat(), grammar).apply { setWords(true) }
    private val ring = ShortArray(RATE * KEEP_SECONDS)
    private var written = 0L // amostras gravadas desde o start (protegido por "ring")
    @Volatile private var running = false
    private var thread: Thread? = null
    private val main = Handler(Looper.getMainLooper())

    @SuppressLint("MissingPermission")
    fun start() {
        val min = AudioRecord.getMinBufferSize(RATE, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT)
        val mic = AudioRecord(MediaRecorder.AudioSource.VOICE_RECOGNITION, RATE, AudioFormat.CHANNEL_IN_MONO,
            AudioFormat.ENCODING_PCM_16BIT, maxOf(min, RATE)) // 0,5 s de folga
        if (mic.state != AudioRecord.STATE_INITIALIZED) {
            mic.release()
            throw IllegalStateException("microfone ocupado")
        }
        running = true
        thread = Thread({
            val buf = ShortArray(RATE / 10) // pedaços de 100 ms
            try {
                mic.startRecording()
                while (running) {
                    val n = mic.read(buf, 0, buf.size)
                    if (n < 0) throw IllegalStateException("erro $n no microfone")
                    if (n == 0) continue
                    synchronized(ring) {
                        for (i in 0 until n) ring[((written + i) % ring.size).toInt()] = buf[i]
                        written += n
                    }
                    if (recognizer.acceptWaveForm(buf, n)) {
                        val r = recognizer.result
                        main.post { if (running) onFinal(r) }
                    } else {
                        val p = recognizer.partialResult
                        main.post { if (running) onPartial(p) }
                    }
                }
            } catch (e: Exception) {
                if (running) main.post { onError(e) }
            } finally {
                try { mic.stop() } catch (_: Exception) {}
                mic.release()
            }
        }, "navi-wake").apply { start() }
    }

    /** Recorta o áudio entre [fromSec] e [toSec] (contados desde o start) e devolve um WAV. */
    fun wav(fromSec: Double, toSec: Double): ByteArray? {
        val pcm: ShortArray
        synchronized(ring) {
            val oldest = maxOf(0L, written - ring.size)
            val from = maxOf(oldest, (fromSec * RATE).toLong())
            val to = minOf(written, (toSec * RATE).toLong())
            if (to - from < RATE / 4) return null // menos de 0,25 s: não tem pedido aí
            pcm = ShortArray((to - from).toInt()) { i -> ring[((from + i) % ring.size).toInt()] }
        }
        return toWav(pcm)
    }

    /** Segundos gravados até agora. */
    fun nowSec(): Double = synchronized(ring) { written.toDouble() / RATE }

    fun stop() {
        running = false
        try { thread?.join(800) } catch (_: InterruptedException) {}
        thread = null
        try { recognizer.close() } catch (_: Exception) {}
    }

    private fun toWav(pcm: ShortArray): ByteArray {
        val data = ByteBuffer.allocate(pcm.size * 2).order(ByteOrder.LITTLE_ENDIAN)
        for (v in pcm) data.putShort(v)
        val body = data.array()
        val h = ByteBuffer.allocate(44).order(ByteOrder.LITTLE_ENDIAN)
        h.put("RIFF".toByteArray()); h.putInt(36 + body.size); h.put("WAVE".toByteArray())
        h.put("fmt ".toByteArray()); h.putInt(16); h.putShort(1); h.putShort(1)
        h.putInt(RATE); h.putInt(RATE * 2); h.putShort(2); h.putShort(16)
        h.put("data".toByteArray()); h.putInt(body.size)
        return h.array() + body
    }
}
