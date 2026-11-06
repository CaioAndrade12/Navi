package com.caio.navi

import android.content.Context
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/** Peças compartilhadas entre o serviço de voz e a tela de conversa. */
object NaviCore {
    data class Line(val fromUser: Boolean, val text: String, val time: String)

    @Volatile private var brainInstance: Brain? = null
    private val lines = ArrayList<Line>()

    /** Chamado (na thread principal) quando aparece uma fala nova na conversa. */
    @Volatile var onLine: ((Line) -> Unit)? = null

    fun brain(c: Context): Brain =
        brainInstance ?: synchronized(this) {
            brainInstance ?: Brain(c.applicationContext).also { brainInstance = it }
        }

    @Synchronized
    fun history(): List<Line> = ArrayList(lines)

    fun add(fromUser: Boolean, text: String) {
        val line = Line(fromUser, text, SimpleDateFormat("HH:mm", Locale.ROOT).format(Date()))
        synchronized(this) {
            lines += line
            while (lines.size > 120) lines.removeAt(0)
        }
        onLine?.invoke(line)
    }

    @Synchronized
    fun clear() {
        lines.clear()
        brainInstance?.reset()
    }
}
