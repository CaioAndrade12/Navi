package com.caio.navi

import android.content.Context
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/** Ajustes salvos do app. */
object Prefs {
    // "nave" em português do Brasil soa como "navi", então ajuda o reconhecimento.
    const val DEFAULT_WORDS = "navi, nave"

    const val MODE_VOICE = 0     // assistente padrão (o mesmo que o "Ok Google" abre)
    const val MODE_ASSIST = 1    // gesto de assistente
    const val MODE_GEMINI = 2    // abrir o app do Gemini

    private fun sp(c: Context) = c.getSharedPreferences("navi", Context.MODE_PRIVATE)

    fun words(c: Context): List<String> =
        (sp(c).getString("words", DEFAULT_WORDS) ?: DEFAULT_WORDS)
            .split(",", ";", "\n")
            .map { it.trim().lowercase(Locale.ROOT) }
            .filter { it.isNotEmpty() }
            .ifEmpty { listOf("navi", "nave") }

    fun wordsRaw(c: Context) = sp(c).getString("words", DEFAULT_WORDS) ?: DEFAULT_WORDS
    fun setWords(c: Context, v: String) = sp(c).edit().putString("words", v).apply()

    fun mode(c: Context) = sp(c).getInt("mode", MODE_VOICE)
    fun setMode(c: Context, v: Int) = sp(c).edit().putInt("mode", v).apply()

    fun pauseSec(c: Context) = sp(c).getInt("pause", 12).coerceIn(3, 120)
    fun setPauseSec(c: Context, v: Int) = sp(c).edit().putInt("pause", v).apply()

    fun vibrate(c: Context) = sp(c).getBoolean("vibrate", true)
    fun setVibrate(c: Context, v: Boolean) = sp(c).edit().putBoolean("vibrate", v).apply()

    /** Se o usuário deixou a Navi ligada (para religar depois de reiniciar). */
    fun enabled(c: Context) = sp(c).getBoolean("enabled", false)
    fun setEnabled(c: Context, v: Boolean) = sp(c).edit().putBoolean("enabled", v).apply()

    fun lastEvent(c: Context) = sp(c).getString("last", "") ?: ""
    fun logEvent(c: Context, text: String) {
        val time = SimpleDateFormat("dd/MM HH:mm:ss", Locale.ROOT).format(Date())
        sp(c).edit().putString("last", "$time — $text").apply()
    }
}
