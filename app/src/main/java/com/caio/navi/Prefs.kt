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

    /** Valor guardado criptografado (migra sozinho o que estava em texto puro). */
    private fun secret(c: Context, k: String): String {
        val raw = str(c, k)
        if (raw.isNotEmpty() && !Secrets.isEncrypted(raw)) put(c, k, Secrets.encrypt(raw))
        return Secrets.decrypt(raw)
    }

    private fun putSecret(c: Context, k: String, v: String) = put(c, k, Secrets.encrypt(v))
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
    fun geminiKey(c: Context) = secret(c, "gemini_key").trim()
    fun setGeminiKey(c: Context, v: String) = putSecret(c, "gemini_key", v.trim())
    fun geminiModel(c: Context) = str(c, "gemini_model", DEFAULT_MODEL).trim().ifEmpty { DEFAULT_MODEL }
    fun setGeminiModel(c: Context, v: String) = put(c, "gemini_model", v.trim())
    fun userName(c: Context) = str(c, "user_name", "Caio").trim()
    fun setUserName(c: Context, v: String) = put(c, "user_name", v.trim())

    /**
     * Depois de responder, continua ouvindo por alguns segundos sem precisar dizer "Navi"
     * (ignorando falas que não forem com ela). Desligado: só continua quando a Navi pergunta algo.
     */
    fun followUp(c: Context) = bool(c, "follow_up2", false)
    fun setFollowUp(c: Context, v: Boolean) = putBool(c, "follow_up2", v)

    /** Sensibilidade da palavra de ativação: 0 = baixa (quase nunca sem querer), 1 = normal, 2 = alta. */
    fun wakeSensitivity(c: Context) = sp(c).getInt("wake_sens", 1).coerceIn(0, 2)
    fun setWakeSensitivity(c: Context, v: Int) = sp(c).edit().putInt("wake_sens", v.coerceIn(0, 2)).apply()

    /** Sons ao começar e ao parar de ouvir (ids em Sounds.START_OPTIONS / END_OPTIONS). */
    fun startSound(c: Context) = str(c, "sound_start", "fada")
    fun setStartSound(c: Context, v: String) = put(c, "sound_start", v)
    fun endSound(c: Context) = str(c, "sound_end", "fada")
    fun setEndSound(c: Context, v: String) = put(c, "sound_end", v)

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
    /** Chave secreta recebida no pareamento (256 bits, em hexadecimal). */
    fun pcKey(c: Context) = secret(c, "pc_key").trim()
    fun setPcKey(c: Context, v: String) = putSecret(c, "pc_key", v.trim())
    fun pcName(c: Context) = str(c, "pc_name")
    fun setPcName(c: Context, v: String) = put(c, "pc_name", v)
    fun pcMac(c: Context) = str(c, "pc_mac").trim()
    fun setPcMac(c: Context, v: String) = put(c, "pc_mac", v.trim())

    // ---- Gmail ----
    fun emailAddress(c: Context) = str(c, "email").trim()
    fun setEmailAddress(c: Context, v: String) = put(c, "email", v.trim())
    fun emailPassword(c: Context) = secret(c, "email_pass").replace(" ", "")
    fun setEmailPassword(c: Context, v: String) = putSecret(c, "email_pass", v.replace(" ", ""))

    // ---- Privacidade e acesso por app ----
    /** Recursos que podem ser desligados. A chave é usada nas ferramentas (Tool.feature). */
    val FEATURES = linkedMapOf(
        "whatsapp" to "WhatsApp",
        "instagram" to "Instagram",
        "gmail" to "Gmail",
        "telegram" to "Telegram",
        "sms" to "SMS",
        "tv" to "TV",
        "pc" to "PC",
        "ligacoes" to "Ligações",
        "web" to "Pesquisa na internet",
        "memoria" to "Memória de longo prazo",
    )
    val MESSAGING = listOf("whatsapp", "instagram", "gmail", "telegram", "sms")

    fun featureEnabled(c: Context, f: String) = bool(c, "feat_$f", true)
    fun setFeatureEnabled(c: Context, f: String, v: Boolean) = putBool(c, "feat_$f", v)
    fun anyMessagingEnabled(c: Context) = MESSAGING.any { featureEnabled(c, it) }
    fun disabledFeatures(c: Context) = FEATURES.filterKeys { !featureEnabled(c, it) }.values.toList()

    /** Com o celular bloqueado, não mexe em mensagens, e-mails, ligações, PC nem memórias. */
    fun lockProtection(c: Context) = bool(c, "lock_protect", true)
    fun setLockProtection(c: Context, v: Boolean) = putBool(c, "lock_protect", v)

    // ---- Música no celular ----
    /** Pacote do app de música preferido ("" = escolher sozinha). */
    fun musicApp(c: Context) = str(c, "music_app")
    fun setMusicApp(c: Context, v: String) = put(c, "music_app", v)

    // ---- Lembretes e timers ----
    fun remindersRaw(c: Context) = str(c, "reminders", "[]")
    fun setRemindersRaw(c: Context, v: String) = put(c, "reminders", v)

    // ---- Memória de longo prazo ----
    fun memories(c: Context): List<String> = try {
        val a = JSONArray(str(c, "memories", "[]"))
        (0 until a.length()).map { a.getString(it) }
    } catch (_: Exception) {
        emptyList()
    }

    fun setMemories(c: Context, list: List<String>) = put(c, "memories", JSONArray(list.takeLast(60)).toString())
}
