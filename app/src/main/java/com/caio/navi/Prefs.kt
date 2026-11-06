package com.caio.navi

import android.content.Context
import org.json.JSONArray
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/** Ajustes salvos do app. */
object Prefs {
    // "nave" em português do Brasil soa como "navi", então ajuda o reconhecimento.
    const val DEFAULT_WORDS = "navi, nave"
    const val DEFAULT_MODEL = "gemini-flash-latest"
    const val FALLBACK_MODEL = "gemini-3.5-flash-lite"

    // Como abrir o Gemini quando o modo "abrir o app do Gemini" está ativo
    const val MODE_VOICE = 0     // assistente padrão (o mesmo que o "Ok Google" abre)
    const val MODE_ASSIST = 1    // gesto de assistente
    const val MODE_GEMINI = 2    // abrir o app do Gemini

    private fun sp(c: Context) = c.getSharedPreferences("navi", Context.MODE_PRIVATE)

    private fun str(c: Context, k: String, d: String = "") = sp(c).getString(k, d) ?: d
    private fun put(c: Context, k: String, v: String) = sp(c).edit().putString(k, v).apply()
    private fun bool(c: Context, k: String, d: Boolean) = sp(c).getBoolean(k, d)
    private fun putBool(c: Context, k: String, v: Boolean) = sp(c).edit().putBoolean(k, v).apply()

    // ---- Palavra de ativação ----
    fun words(c: Context): List<String> =
        str(c, "words", DEFAULT_WORDS)
            .split(",", ";", "\n")
            .map { it.trim().lowercase(Locale.ROOT) }
            .filter { it.isNotEmpty() }
            .ifEmpty { listOf("navi", "nave") }

    fun wordsRaw(c: Context) = str(c, "words", DEFAULT_WORDS)
    fun setWords(c: Context, v: String) = put(c, "words", v)

    /** true = a própria Navi responde (Gemini API); false = só abre o app do Gemini (modo antigo). */
    fun ownBrain(c: Context) = bool(c, "own_brain", true)
    fun setOwnBrain(c: Context, v: Boolean) = putBool(c, "own_brain", v)

    fun mode(c: Context) = sp(c).getInt("mode", MODE_VOICE)
    fun setMode(c: Context, v: Int) = sp(c).edit().putInt("mode", v).apply()

    fun pauseSec(c: Context) = sp(c).getInt("pause", 12).coerceIn(3, 120)
    fun setPauseSec(c: Context, v: Int) = sp(c).edit().putInt("pause", v).apply()

    fun vibrate(c: Context) = bool(c, "vibrate", true)
    fun setVibrate(c: Context, v: Boolean) = putBool(c, "vibrate", v)

    /** Se o usuário deixou a Navi ligada (para religar depois de reiniciar). */
    fun enabled(c: Context) = bool(c, "enabled", false)
    fun setEnabled(c: Context, v: Boolean) = putBool(c, "enabled", v)

    fun lastEvent(c: Context) = str(c, "last")
    fun logEvent(c: Context, text: String) {
        val time = SimpleDateFormat("dd/MM HH:mm:ss", Locale.ROOT).format(Date())
        put(c, "last", "$time — $text")
    }

    // ---- Cérebro (Gemini API) ----
    fun geminiKey(c: Context) = str(c, "gemini_key").trim()
    fun setGeminiKey(c: Context, v: String) = put(c, "gemini_key", v.trim())
    fun geminiModel(c: Context) = str(c, "gemini_model", DEFAULT_MODEL).trim().ifEmpty { DEFAULT_MODEL }
    fun setGeminiModel(c: Context, v: String) = put(c, "gemini_model", v.trim())
    fun userName(c: Context) = str(c, "user_name", "Caio").trim()
    fun setUserName(c: Context, v: String) = put(c, "user_name", v.trim())

    /** Depois de responder, continua ouvindo um pouco sem precisar dizer "Navi". */
    fun followUp(c: Context) = bool(c, "follow_up", true)
    fun setFollowUp(c: Context, v: Boolean) = putBool(c, "follow_up", v)

    /** Pergunta antes de enviar mensagens, ligar, desligar o PC etc. */
    fun confirmActions(c: Context) = bool(c, "confirm", true)
    fun setConfirmActions(c: Context, v: Boolean) = putBool(c, "confirm", v)

    // ---- TV Samsung ----
    fun tvIp(c: Context) = str(c, "tv_ip").trim()
    fun setTvIp(c: Context, v: String) = put(c, "tv_ip", v.trim())
    fun tvMac(c: Context) = str(c, "tv_mac").trim()
    fun setTvMac(c: Context, v: String) = put(c, "tv_mac", v.trim())
    fun tvToken(c: Context) = str(c, "tv_token")
    fun setTvToken(c: Context, v: String) = put(c, "tv_token", v)
    fun tvPort(c: Context) = sp(c).getInt("tv_port", 0)
    fun setTvPort(c: Context, v: Int) = sp(c).edit().putInt("tv_port", v).apply()
    fun irEnabled(c: Context) = bool(c, "ir", true)
    fun setIrEnabled(c: Context, v: Boolean) = putBool(c, "ir", v)

    // ---- PC ----
    /** Endereço do agente do PC, ex.: 192.168.0.15:8765 */
    fun pcHost(c: Context) = str(c, "pc_host").trim()
    fun setPcHost(c: Context, v: String) = put(c, "pc_host", v.trim())
    fun pcToken(c: Context) = str(c, "pc_token").trim()
    fun setPcToken(c: Context, v: String) = put(c, "pc_token", v.trim())
    fun pcMac(c: Context) = str(c, "pc_mac").trim()
    fun setPcMac(c: Context, v: String) = put(c, "pc_mac", v.trim())

    // ---- Gmail ----
    fun emailAddress(c: Context) = str(c, "email").trim()
    fun setEmailAddress(c: Context, v: String) = put(c, "email", v.trim())
    fun emailPassword(c: Context) = str(c, "email_pass").replace(" ", "")
    fun setEmailPassword(c: Context, v: String) = put(c, "email_pass", v.replace(" ", ""))

    // ---- Memória de longo prazo ----
    fun memories(c: Context): List<String> = try {
        val a = JSONArray(str(c, "memories", "[]"))
        (0 until a.length()).map { a.getString(it) }
    } catch (_: Exception) {
        emptyList()
    }

    fun setMemories(c: Context, list: List<String>) = put(c, "memories", JSONArray(list.takeLast(60)).toString())
}
