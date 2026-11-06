package com.caio.navi

import android.content.Context
import android.hardware.ConsumerIrManager

/**
 * Controle remoto por infravermelho usando o emissor IR do celular (o Poco F5 Pro tem).
 * Protocolo Samsung32: cabeçalho 4500/4500 µs, bit 1 = 560/1690, bit 0 = 560/560, 38 kHz.
 */
object IrRemote {
    // Códigos Samsung (32 bits). Os de HDMI1-4 são "discretos" e podem não existir em todo modelo.
    val SAMSUNG = mapOf(
        "KEY_POWER" to 0xE0E040BFL,
        "POWER_ON" to 0xE0E09966L,
        "POWER_OFF" to 0xE0E019E6L,
        "KEY_VOLUP" to 0xE0E0E01FL,
        "KEY_VOLDOWN" to 0xE0E0D02FL,
        "KEY_MUTE" to 0xE0E0F00FL,
        "KEY_SOURCE" to 0xE0E0807FL,
        "KEY_CHUP" to 0xE0E048B7L,
        "KEY_CHDOWN" to 0xE0E008F7L,
        "KEY_UP" to 0xE0E006F9L,
        "KEY_DOWN" to 0xE0E08679L,
        "KEY_LEFT" to 0xE0E0A659L,
        "KEY_RIGHT" to 0xE0E046B9L,
        "KEY_ENTER" to 0xE0E016E9L,
        "KEY_RETURN" to 0xE0E01AE5L,
        "KEY_EXIT" to 0xE0E0B44BL,
        "KEY_HOME" to 0xE0E09E61L,
        "KEY_PLAY" to 0xE0E0E21DL,
        "KEY_PAUSE" to 0xE0E052ADL,
        "KEY_HDMI" to 0xE0E0D12EL,
        "KEY_HDMI1" to 0xE0E09768L,
        "KEY_HDMI2" to 0xE0E07D82L,
        "KEY_HDMI3" to 0xE0E043BCL,
        "KEY_HDMI4" to 0xE0E0A35CL,
    )

    private fun manager(c: Context): ConsumerIrManager? = try {
        c.getSystemService(Context.CONSUMER_IR_SERVICE) as? ConsumerIrManager
    } catch (_: Exception) {
        null
    }

    fun available(c: Context): Boolean = manager(c)?.hasIrEmitter() == true

    private fun samsungPattern(code: Long): IntArray {
        val p = ArrayList<Int>(68)
        p += 4500; p += 4500
        for (bit in 31 downTo 0) {
            p += 560
            p += if ((code shr bit) and 1L == 1L) 1690 else 560
        }
        p += 560
        p += 40000 // pausa no fim
        return p.toIntArray()
    }

    /** Envia uma tecla Samsung pelo infravermelho. Retorna false se não deu. */
    fun sendSamsung(c: Context, key: String, repeat: Int = 1): Boolean {
        val code = SAMSUNG[key] ?: return false
        val m = manager(c) ?: return false
        if (!m.hasIrEmitter()) return false
        return try {
            repeat(repeat.coerceIn(1, 30)) {
                m.transmit(38000, samsungPattern(code))
                Thread.sleep(120)
            }
            true
        } catch (_: Exception) {
            false
        }
    }
}
