package com.caio.navi

import android.content.Context
import android.text.Html
import android.util.Log
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.MultipartBody
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONArray
import org.json.JSONObject
import java.net.URLDecoder
import java.net.URLEncoder
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.TimeUnit

/**
 * Escolhe qual cérebro responde. O Gemini vem primeiro; se ele estiver sem cota, fora do ar ou
 * travado, a Navi passa sozinha para os reservas (Groq e um terceiro à escolha) sem perder a
 * conversa. Quem falhou fica em pausa pelo tempo que a própria API pedir (ou um tempo padrão).
 *
 * O histórico fica sempre no formato do Gemini; para os outros ele é convertido na hora
 * (formato "chat completions", que Groq, Cerebras, OpenRouter e Mistral entendem).
 */
object Llm {
    data class Preset(val label: String, val base: String, val models: List<String>, val keyUrl: String, val about: String)

    val PRESETS = linkedMapOf(
        "groq" to Preset("Groq", "https://api.groq.com/openai/v1",
            listOf("openai/gpt-oss-120b", "llama-3.3-70b-versatile"), "https://console.groq.com/keys",
            "rapidíssimo, também entende áudio"),
        "openrouter" to Preset("OpenRouter", "https://openrouter.ai/api/v1", listOf("openrouter/free"),
            "https://openrouter.ai/settings/keys", "escolhe sozinho entre vários modelos grátis"),
        "mistral" to Preset("Mistral", "https://api.mistral.ai/v1", listOf("mistral-small-latest"),
            "https://console.mistral.ai/api-keys", "europeu, plano grátis para testar"),
    )

    /** Opções para o terceiro cérebro (o segundo é sempre o Groq). */
    val THIRD = listOf("openrouter", "mistral")

    /** [id] = provedor (groq, cerebras...); [slot] = provedor + número da chave, para pausar cada chave separada. */
    private class Backup(val id: String, val label: String, val base: String, val key: String, val models: List<String>,
                         val slot: String = id)

    private const val SKIP_SIGNATURE = "skip_thought_signature_validator"
    private const val UA = "Mozilla/5.0 (Linux; Android 14) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/124.0 Mobile Safari/537.36"
    private val JSON_TYPE = "application/json; charset=utf-8".toMediaType()
    private val pausedUntil = ConcurrentHashMap<String, Long>()
    private val pauseWhy = ConcurrentHashMap<String, String>()
    @Volatile private var noThinking = false

    /** Último cérebro que respondeu, para mostrar nos ajustes. */
    @Volatile var lastUsed: String = ""
        private set

    /** Com reservas cadastrados não vale esperar 90 s por um Gemini travado. */
    private val quick: OkHttpClient by lazy { Gemini.http.newBuilder().readTimeout(30, TimeUnit.SECONDS).build() }

    /** Todos os reservas com chave, na ordem: Groq, Cerebras, OpenRouter, Mistral (cada chave vira uma opção). */
    private fun backups(c: Context): List<Backup> {
        val out = mutableListOf<Backup>()
        for ((id, p) in PRESETS) {
            val keys = Prefs.providerKeys(c, id)
            val custom = Prefs.providerModel(c, id)
            keys.forEachIndexed { i, key ->
                val label = if (keys.size > 1) "${p.label} chave ${i + 1}" else p.label
                out += Backup(id, label, p.base, key, if (custom.isNotEmpty()) listOf(custom) else p.models, "$id#${i + 1}")
            }
        }
        return out
    }

    fun hasBackups(c: Context) = backups(c).isNotEmpty()

    // ------------------------------------------------------------------ modelos do Gemini

    /** Se a lista da chave não puder ser lida: os apelidos "mais recente" do Google, do melhor ao mais leve. */
    private val GEMINI_DEFAULTS = listOf("gemini-flash-latest", "gemini-flash-lite-latest", "gemini-pro-latest")
    @Volatile private var geminiList: List<String> = emptyList()
    @Volatile private var geminiListAt = 0L

    /**
     * Modelos que a chave pode usar, do melhor para o pior pensando em uma assistente de voz:
     * Flash mais novo primeiro (rápido e esperto), depois Flash-Lite, e o Pro só no fim (lento e com
     * pouca cota grátis). A lista vem do próprio Gemini e é atualizada uma vez por dia.
     */
    fun geminiModels(c: Context): List<String> {
        val now = System.currentTimeMillis()
        if (geminiList.isNotEmpty() && now - geminiListAt < 24 * 3_600_000L) return geminiList
        val key = Prefs.geminiKey(c)
        val found = if (key.isEmpty()) emptyList() else try {
            val req = Request.Builder()
                .url("https://generativelanguage.googleapis.com/v1beta/models?pageSize=200")
                .header("x-goog-api-key", key).build()
            quick.newCall(req).execute().use { r ->
                if (!r.isSuccessful) emptyList() else {
                    val arr = JSONObject(r.body?.string().orEmpty()).optJSONArray("models") ?: JSONArray()
                    (0 until arr.length()).mapNotNull { i ->
                        val m = arr.getJSONObject(i)
                        val methods = m.optJSONArray("supportedGenerationMethods")?.toString().orEmpty()
                        val name = m.optString("name").removePrefix("models/")
                        name.takeIf { "generateContent" in methods }
                    }
                }
            }
        } catch (_: Exception) {
            emptyList()
        }
        val bad = Regex("preview|exp|tts|image|live|audio|embedding|vision|learnlm|gemma|aqa|robotics|computer|nano|thinking")
        val ranked = found.filter { it.startsWith("gemini-") && !bad.containsMatchIn(it) }
            .mapNotNull { name ->
                val m = Regex("gemini-(\\d+(?:\\.\\d+)?)-(flash|pro)(-lite)?(?:-(\\d+|latest))?$").find(name)
                if (m == null) {
                    // apelidos "gemini-flash-latest" etc.
                    when (name) {
                        "gemini-flash-latest" -> name to 1000.0
                        "gemini-flash-lite-latest" -> name to 500.0
                        "gemini-pro-latest" -> name to 10.0
                        else -> null
                    }
                } else {
                    val version = m.groupValues[1].toDoubleOrNull() ?: 0.0
                    val family = when {
                        m.groupValues[2] == "flash" && m.groupValues[3].isEmpty() -> 600.0
                        m.groupValues[2] == "flash" -> 300.0
                        else -> 0.0 // pro
                    }
                    name to family + version * 10
                }
            }
            .sortedByDescending { it.second }.map { it.first }.distinct()
        val list = ranked.ifEmpty { GEMINI_DEFAULTS }
        geminiList = list
        geminiListAt = if (ranked.isEmpty()) now - 23 * 3_600_000L else now // sem lista: tenta de novo em 1 h
        return list
    }

    /** Os melhores modelos que não estão em pausa em nenhuma chave (até 5). */
    private fun geminiTop(c: Context): List<String> {
        ensure(c)
        val all = geminiModels(c)
        val keys = Prefs.geminiKeys(c).size.coerceAtLeast(1)
        val usable = all.filter { m -> (1..keys).any { !paused("gemini#$it:$m") } }
        return (usable.ifEmpty { all }).take(5)
    }

    /**
     * Abre a conexão com o cérebro enquanto a pessoa ainda está falando: com internet ruim, só
     * o "aperto de mão" da conexão segura leva quase um segundo. A conversa depois reaproveita.
     */
    fun warmUp(c: Context) {
        val hosts = mutableListOf<String>()
        if (Prefs.geminiKey(c).isNotEmpty()) hosts += "https://generativelanguage.googleapis.com/"
        if (Prefs.providerKey(c, "groq").isNotEmpty()) hosts += "https://api.groq.com/"
        for (h in hosts) Thread {
            try {
                quick.newCall(Request.Builder().url(h).head().build()).execute().close()
            } catch (_: Exception) {
            }
        }.start()
    }

    // ------------------------------------------------------------------ pausas

    /** As pausas ficam salvas: depois de reiniciar, a Navi não volta a tentar modelo que não serve. */
    @Volatile private var store: android.content.SharedPreferences? = null

    private fun ensure(c: Context) {
        if (store != null) return
        val sp = c.applicationContext.getSharedPreferences("navi_pausas", Context.MODE_PRIVATE)
        val now = System.currentTimeMillis()
        for ((k, v) in sp.all) {
            val parts = v.toString().split("|", limit = 2)
            val until = parts[0].toLongOrNull() ?: continue
            if (until > now) {
                pausedUntil[k] = until
                pauseWhy[k] = parts.getOrElse(1) { "pausa" }
            }
        }
        store = sp
    }

    private fun persist(slot: String) {
        val sp = store ?: return
        val until = pausedUntil[slot] ?: return
        if (until - System.currentTimeMillis() > 2 * 60_000L) sp.edit().putString(slot, "$until|${pauseWhy[slot]}").apply()
    }

    private fun paused(slot: String) = (pausedUntil[slot] ?: 0L) > System.currentTimeMillis()

    /** 429 com "limit: 0" = esse modelo não existe no plano grátis desta chave (não é cota acabada). */
    private fun notInPlan(e: GeminiException) = e.code == 429 && Regex("limit:\\s*0\\b").containsMatchIn(e.message.orEmpty())

    private fun pause(slot: String, e: GeminiException) {
        Log.w("Navi", "cérebro $slot falhou (${e.code}): ${e.message?.take(300)}")
        val ms = pauseFor(e)
        if (ms > 0) {
            pausedUntil[slot] = System.currentTimeMillis() + ms
            pauseWhy[slot] = when {
                notInPlan(e) -> "não disponível no seu plano"
                e.code == 429 || e.code == 413 -> "limite de uso"
                e.code == 401 || e.code == 403 -> "chave recusada"
                e.code == 404 -> "modelo não encontrado"
                e.code == -1 -> "sem conexão"
                else -> "fora do ar"
            }
            persist(slot)
        }
    }

    private fun pauseFor(e: GeminiException): Long {
        val msg = e.message.orEmpty()
        return when {
            notInPlan(e) -> 7 * 24 * 3_600_000L
            e.code == 429 || e.code == 413 -> retryAfter(msg) ?: if (Regex(
                    "per ?day|daily|PerDay|RPD|TPD|tokens per day|requests per day", RegexOption.IGNORE_CASE
                ).containsMatchIn(msg)) 60 * 60_000L else 60_000L
            e.code == 401 -> 10 * 60_000L
            e.code == 403 || e.code == 404 -> 3 * 24 * 3_600_000L // modelo sem permissão ou que não existe
            e.code == -1 || e.code >= 500 -> 20_000L
            else -> 0L
        }
    }

    /** "Please retry in 41.2s", "try again in 7m12.5s", "(retry in 30s)" -> milissegundos. */
    fun retryAfter(msg: String): Long? {
        val m = Regex("(?i)(?:retry|try again)\\D{0,12}?((?:\\d+(?:\\.\\d+)?\\s*(?:ms|h|m|s)\\s*)+)").find(msg) ?: return null
        var total = 0.0
        Regex("(\\d+(?:\\.\\d+)?)\\s*(ms|h|m|s)").findAll(m.groupValues[1]).forEach { u ->
            val v = u.groupValues[1].toDouble()
            total += when (u.groupValues[2]) {
                "h" -> v * 3_600_000
                "m" -> v * 60_000
                "s" -> v * 1000
                else -> v
            }
        }
        return if (total > 0) (total.toLong() + 1000).coerceIn(5_000L, 24 * 3_600_000L) else null
    }

    private fun label(slot: String): String {
        val id = slot.substringBefore(":")
        val model = slot.substringAfter(":")
        val provider = id.substringBefore("#")
        val n = id.substringAfter("#", "")
        val name = if (provider.startsWith("gemini")) "Gemini" else PRESETS[provider]?.label ?: provider
        return "$name${if (n.isNotEmpty() && n != "1") " chave $n" else ""} ($model)"
    }

    /** Texto para os ajustes: quem respondeu por último e quem está em pausa. */
    fun status(): String {
        val now = System.currentTimeMillis()
        val pausas = pausedUntil.filter { it.value > now }.map { (slot, until) ->
            val min = ((until - now) / 60_000L).coerceAtLeast(1)
            "${label(slot)}: ${pauseWhy[slot] ?: "pausa"} (mais $min min)"
        }
        return buildString {
            append(if (lastUsed.isEmpty()) "Nenhum cérebro usado ainda desde que o app abriu." else "Último a responder: $lastUsed.")
            if (pausas.isNotEmpty()) append("\nEm pausa: " + pausas.joinToString("; "))
        }
    }

    // ------------------------------------------------------------------ conversa

    /** Responde no formato do Gemini (candidates/content/parts), venha a resposta de quem vier. */
    /** [tools] vai para o Gemini; [lightTools] (só o assunto) para os reservas, que têm pouco token por minuto. */
    fun chat(
        c: Context, system: String, history: MutableList<JSONObject>, tools: List<Tool>,
        lightTools: List<Tool> = tools, thinking: String = "low",
    ): JSONObject {
        ensure(c)
        val backups = backups(c)
        val geminiKeys = Prefs.geminiKeys(c)
        val slots = mutableListOf<Pair<String, () -> JSONObject>>()
        // Cada chave do Gemini (modelo principal e o Lite) antes de ir para os outros provedores.
        geminiKeys.forEachIndexed { i, key ->
            for (model in geminiTop(c)) {
                val more = backups.isNotEmpty() || geminiKeys.size > 1
                slots += "gemini#${i + 1}:$model" to { geminiChat(key, model, system, history, tools, more, thinking) }
            }
        }
        for (b in backups) for (model in b.models) {
            slots += "${b.slot}:$model" to { openAiChat(c, b, model, system, history, lightTools, thinking) }
        }
        if (slots.isEmpty()) throw GeminiException("Falta a chave da Gemini API nos ajustes.", 401)

        val errors = mutableListOf<GeminiException>()
        var tried = 0
        var skipGemini = false
        for ((slot, call) in slots) {
            if (paused(slot) || (skipGemini && slot.startsWith("gemini#"))) continue
            tried++
            try {
                return call().also { lastUsed = label(slot) }
            } catch (e: GeminiException) {
                errors += e
                pause(slot, e)
                // Pedido recusado (400): o outro modelo do Gemini recusaria igual; vai direto aos reservas.
                if (slot.startsWith("gemini#") && e.code == 400) skipGemini = true
            }
        }
        if (tried == 0) {
            // Todos em pausa: tenta o que sai da pausa primeiro, em vez de nem tentar.
            val (slot, call) = slots.minBy { pausedUntil[it.first] ?: 0L }
            try {
                return call().also { lastUsed = label(slot) }
            } catch (e: GeminiException) {
                errors += e
                pause(slot, e)
            }
        }
        throw summarize(errors)
    }

    private fun summarize(errors: List<GeminiException>): GeminiException {
        if (errors.isEmpty()) return GeminiException("Nenhum cérebro disponível agora.", 503)
        if (errors.all { it.code == -1 }) return errors.first()
        errors.firstOrNull { it.code == 429 || it.code == 413 }?.let { return GeminiException(it.message ?: "limite", 429) }
        return errors.firstOrNull { it.code == 401 || it.code == 403 } ?: errors.first()
    }

    private fun geminiChat(
        key: String, model: String, system: String, history: List<JSONObject>, tools: List<Tool>, quickTimeout: Boolean,
        thinking: String,
    ): JSONObject {
        fun body(): JSONObject {
            val decls = JSONArray()
            for (t in tools) decls.put(compact(t.declaration(), keepGeminiFormat = true))
            val gen = JSONObject().put("temperature", 0.7).put("maxOutputTokens", 4096)
            if (!noThinking) gen.put("thinkingConfig", JSONObject().put("thinkingLevel", thinking))
            return JSONObject()
                .put("systemInstruction", JSONObject().put("parts", JSONArray().put(JSONObject().put("text", system))))
                .put("contents", JSONArray(history))
                .put("tools", JSONArray().put(JSONObject().put("functionDeclarations", decls)))
                .put("generationConfig", gen)
        }
        val client = if (quickTimeout) quick else Gemini.http
        return try {
            Gemini.generate(key, model, body(), client)
        } catch (e: GeminiException) {
            if (e.code == 400 && !noThinking && e.message.orEmpty().contains("think", true)) {
                noThinking = true
                Gemini.generate(key, model, body(), client)
            } else throw e
        }
    }

    private fun openAiChat(
        c: Context, b: Backup, model: String, system: String, history: MutableList<JSONObject>, tools: List<Tool>,
        thinking: String,
    ): JSONObject {
        transcribeAudioParts(c, history)
        val recent = history.subList(backupStart(history), history.size)
        val decls = JSONArray()
        for (tool in tools) decls.put(JSONObject().put("type", "function").put("function", compact(tool.declaration(), false)))
        val body = JSONObject()
            .put("model", model)
            .put("messages", toMessages(system, recent, b.id))
            .put("temperature", 0.6)
            .put(maxTokensField(b.id), 2048)
        if (decls.length() > 0) body.put("tools", decls).put("tool_choice", "auto")
        if (model.contains("gpt-oss") && (b.id == "groq" || b.id == "cerebras")) {
            body.put("reasoning_effort", if (thinking == "medium") "medium" else "low")
        }
        return fromOpenAi(post("${b.base}/chat/completions", b.key, body))
    }

    /** Groq e Cerebras usam o nome novo; OpenRouter e Mistral, o antigo. */
    private fun maxTokensField(provider: String) =
        if (provider == "groq" || provider == "cerebras") "max_completion_tokens" else "max_tokens"

    private fun post(url: String, key: String, body: JSONObject): JSONObject {
        val req = Request.Builder()
            .url(url)
            .header("Authorization", "Bearer $key")
            .header("X-Title", "Navi")
            .post(body.toString().toRequestBody(JSON_TYPE))
            .build()
        try {
            quick.newCall(req).execute().use { r ->
                val text = r.body?.string().orEmpty()
                if (!r.isSuccessful) {
                    var msg = try {
                        val j = JSONObject(text)
                        j.optJSONObject("error")?.optString("message")?.takeIf { it.isNotBlank() }
                            ?: j.optString("message", text)
                    } catch (_: Exception) {
                        text.take(300)
                    }
                    if (msg.isBlank()) msg = "HTTP ${r.code}"
                    r.header("retry-after")?.toDoubleOrNull()?.let { msg += " (retry in ${it}s)" }
                    throw GeminiException(msg.take(400), r.code)
                }
                return JSONObject(text)
            }
        } catch (e: GeminiException) {
            throw e
        } catch (e: Exception) {
            throw GeminiException("Sem conexão com o cérebro reserva (${e.javaClass.simpleName}).", -1)
        }
    }

    // ------------------------------------------------------------------ conversão de formatos

    /** Os reservas recebem só o fim da conversa (sempre começando numa fala da pessoa). */
    private fun backupStart(h: List<JSONObject>): Int {
        fun isUserText(c: JSONObject): Boolean {
            val first = c.optJSONArray("parts")?.optJSONObject(0)
            return c.optString("role") == "user" && first != null && !first.has("functionResponse")
        }
        val from = maxOf(0, h.size - 16)
        for (i in from until h.size) if (isUserText(h[i])) return i
        for (i in from - 1 downTo 0) if (isUserText(h[i])) return i
        return 0
    }

    /** Ids de 9 letras/números (o Mistral exige esse formato; os outros aceitam). */
    private fun callId(i: Int, j: Int) = "c" + (i * 50 + j).toString().padStart(8, '0')

    private fun toolMsg(id: String, name: String, content: String, provider: String): JSONObject {
        val m = JSONObject().put("role", "tool").put("tool_call_id", id).put("content", content.take(6000))
        if (provider == "mistral") m.put("name", name)
        return m
    }

    private fun toMessages(system: String, h: List<JSONObject>, provider: String): JSONArray {
        val msgs = JSONArray().put(JSONObject().put("role", "system").put("content", system))
        val waiting = ArrayDeque<Pair<String, String>>()
        fun closeWaiting() {
            while (waiting.isNotEmpty()) {
                val (id, name) = waiting.removeFirst()
                msgs.put(toolMsg(id, name, "{\"status\":\"sem resposta\"}", provider))
            }
        }
        h.forEachIndexed { i, c ->
            val parts = c.optJSONArray("parts") ?: JSONArray()
            if (c.optString("role") == "model") {
                closeWaiting()
                val text = StringBuilder()
                val calls = JSONArray()
                for (j in 0 until parts.length()) {
                    val p = parts.optJSONObject(j) ?: continue
                    if (p.optBoolean("thought", false)) continue
                    if (p.has("text")) text.append(p.optString("text"))
                    val fc = p.optJSONObject("functionCall") ?: continue
                    val id = callId(i, calls.length())
                    val name = fc.optString("name")
                    calls.put(JSONObject().put("id", id).put("type", "function").put("function", JSONObject()
                        .put("name", name)
                        .put("arguments", (fc.optJSONObject("args") ?: JSONObject()).toString())))
                    waiting.addLast(id to name)
                }
                if (text.isBlank() && calls.length() == 0) return@forEachIndexed
                val m = JSONObject().put("role", "assistant")
                    .put("content", if (text.isBlank()) JSONObject.NULL else text.toString().trim())
                if (calls.length() > 0) m.put("tool_calls", calls)
                msgs.put(m)
            } else {
                val text = StringBuilder()
                for (j in 0 until parts.length()) {
                    val p = parts.optJSONObject(j) ?: continue
                    val fr = p.optJSONObject("functionResponse")
                    if (fr != null) {
                        val w = waiting.removeFirstOrNull() ?: continue // resposta sem chamada: ignora
                        msgs.put(toolMsg(w.first, w.second, (fr.optJSONObject("response") ?: JSONObject()).toString(), provider))
                        continue
                    }
                    if (p.has("text")) text.append(p.optString("text")).append(' ')
                    if (p.has("inlineData")) text.append("(mandou um áudio que não deu para transcrever) ")
                }
                if (text.isNotBlank()) {
                    closeWaiting()
                    msgs.put(JSONObject().put("role", "user").put("content", text.toString().trim()))
                }
            }
        }
        closeWaiting()
        return msgs
    }

    private fun fromOpenAi(resp: JSONObject): JSONObject {
        val choice = resp.optJSONArray("choices")?.optJSONObject(0)
            ?: throw GeminiException("O cérebro reserva respondeu vazio.", 502)
        val msg = choice.optJSONObject("message") ?: JSONObject()
        val parts = JSONArray()
        val text = if (msg.isNull("content")) "" else msg.optString("content")
            .replace(Regex("(?s)<think>.*?</think>"), "").trim()
        if (text.isNotEmpty()) parts.put(JSONObject().put("text", text))
        val calls = msg.optJSONArray("tool_calls")
        var signed = false
        if (calls != null) for (i in 0 until calls.length()) {
            val f = calls.optJSONObject(i)?.optJSONObject("function") ?: continue
            val args = try {
                JSONObject(f.optString("arguments").ifBlank { "{}" })
            } catch (_: Exception) {
                JSONObject()
            }
            val part = JSONObject().put("functionCall", JSONObject().put("name", f.optString("name")).put("args", args))
            // O Gemini exige "assinatura" nas chamadas da rodada atual; esta é a oficial para chamadas de fora.
            if (!signed) {
                part.put("thoughtSignature", SKIP_SIGNATURE)
                signed = true
            }
            parts.put(part)
        }
        val reason = when (choice.optString("finish_reason")) {
            "length" -> "MAX_TOKENS"
            "content_filter" -> "SAFETY"
            else -> "STOP"
        }
        return JSONObject().put("candidates", JSONArray().put(JSONObject()
            .put("content", JSONObject().put("role", "model").put("parts", parts))
            .put("finishReason", reason)))
    }

    // ------------------------------------------------------------------ declarações enxutas

    private fun shortText(s: String, max: Int): String {
        if (s.length <= max) return s
        val cut = s.lastIndexOf(". ", max)
        return if (cut > max / 2) s.substring(0, cut + 1) else s.substring(0, max).trimEnd() + "…"
    }

    /** Descrição curta e parâmetros com descrições curtas: o mesmo sentido com bem menos tokens. */
    private fun compact(d: JSONObject, keepGeminiFormat: Boolean): JSONObject {
        val params = d.optJSONObject("parameters")
        val out = JSONObject().put("name", d.optString("name"))
            .put("description", shortText(d.optString("description"), 200))
        if (params != null) out.put("parameters", cleanSchema(params, keepGeminiFormat))
        else if (!keepGeminiFormat) out.put("parameters", JSONObject().put("type", "object").put("properties", JSONObject()))
        return out
    }

    /** Encurta as descrições; para os reservas também tira o que é só do Gemini ("format": "enum"). */
    private fun cleanSchema(s: JSONObject, keepGeminiFormat: Boolean): JSONObject {
        val o = JSONObject()
        for (k in s.keys()) {
            val v = s.get(k)
            when {
                k == "format" && v == "enum" && !keepGeminiFormat -> Unit
                k == "description" && v is String -> o.put(k, shortText(v, 90))
                v is JSONObject -> o.put(k, cleanSchema(v, keepGeminiFormat))
                else -> o.put(k, v)
            }
        }
        return o
    }

    // ------------------------------------------------------------------ áudio

    /** Transcreve uma fala (Whisper do Groq, rápido e grátis; senão o Gemini). null = ninguém conseguiu. */
    fun transcribe(c: Context, wav: ByteArray): String? {
        Prefs.providerKeys(c, "groq").forEachIndexed { i, groqKey ->
            val slot = "groq#${i + 1}:whisper"
            if (!paused(slot)) {
                try {
                    return whisper(groqKey, wav)
                } catch (e: GeminiException) {
                    pause(slot, e)
                }
            }
        }
        Prefs.geminiKeys(c).forEachIndexed { i, key ->
            for (model in geminiTop(c)) {
                val slot = "gemini#${i + 1}:$model"
                if (paused(slot)) continue
                try {
                    return Gemini.transcribe(key, model, wav)
                } catch (e: GeminiException) {
                    pause(slot, e)
                    if (e.code == 400) break
                }
            }
        }
        return null
    }

    /** Whisper do Groq. Devolve "" quando o áudio é só silêncio/barulho. */
    private fun whisper(key: String, wav: ByteArray): String {
        val body = MultipartBody.Builder().setType(MultipartBody.FORM)
            .addFormDataPart("model", "whisper-large-v3-turbo")
            .addFormDataPart("language", "pt")
            .addFormDataPart("temperature", "0")
            .addFormDataPart("response_format", "verbose_json")
            .addFormDataPart("file", "fala.wav", wav.toRequestBody("audio/wav".toMediaType()))
            .build()
        val req = Request.Builder().url("https://api.groq.com/openai/v1/audio/transcriptions")
            .header("Authorization", "Bearer $key").post(body).build()
        try {
            quick.newCall(req).execute().use { r ->
                val text = r.body?.string().orEmpty()
                if (!r.isSuccessful) {
                    val msg = try {
                        JSONObject(text).optJSONObject("error")?.optString("message") ?: text
                    } catch (_: Exception) {
                        text.take(200)
                    }
                    throw GeminiException(msg.ifBlank { "HTTP ${r.code}" }, r.code)
                }
                val j = JSONObject(text)
                val segs = j.optJSONArray("segments")
                if (segs != null && segs.length() > 0 &&
                    (0 until segs.length()).all { segs.getJSONObject(it).optDouble("no_speech_prob", 0.0) > 0.6 }
                ) return ""
                val said = j.optString("text").trim()
                // Frases que o Whisper "inventa" quando só tem silêncio.
                val n = Brain.normalize(said)
                if (n.contains("amara org") || n.contains("inscreva se") || n.startsWith("legenda")) return ""
                return said
            }
        } catch (e: GeminiException) {
            throw e
        } catch (e: Exception) {
            throw GeminiException("Sem conexão com o Groq (${e.javaClass.simpleName}).", -1)
        }
    }

    /** Antes de mandar para um reserva, troca áudios do histórico por texto (se der). */
    private fun transcribeAudioParts(c: Context, history: MutableList<JSONObject>) {
        val groq = Prefs.providerKeys(c, "groq").withIndex().firstOrNull { !paused("groq#${it.index + 1}:whisper") }?.value.orEmpty()
        for (content in history) {
            val parts = content.optJSONArray("parts") ?: continue
            for (j in 0 until parts.length()) {
                val p = parts.optJSONObject(j) ?: continue
                val data = p.optJSONObject("inlineData")?.optString("data").orEmpty()
                if (data.isEmpty()) continue
                if (groq.isEmpty()) return
                val said = try {
                    whisper(groq, android.util.Base64.decode(data, android.util.Base64.NO_WRAP))
                } catch (e: GeminiException) {
                    pause("groq#1:whisper", e)
                    return
                }
                parts.put(j, JSONObject().put("text", if (said.isBlank()) "(áudio sem fala)" else "(falou por voz) $said"))
            }
        }
    }

    // ------------------------------------------------------------------ busca na web

    /** Pesquisa: Google pelo Gemini; se ele estiver fora, trechos do DuckDuckGo para o cérebro resumir. */
    fun webSearch(c: Context, query: String): JSONObject {
        Prefs.geminiKeys(c).forEachIndexed { i, key ->
            for (model in geminiTop(c)) {
                val slot = "gemini-busca#${i + 1}:$model"
                if (paused(slot)) continue
                try {
                    return Gemini.webSearch(key, model, query)
                } catch (e: GeminiException) {
                    pause(slot, e)
                    if (e.code == 400) break
                }
            }
        }
        return duckSearch(query)
    }

    private fun duckSearch(query: String): JSONObject {
        val url = "https://html.duckduckgo.com/html/?kl=br-pt&q=" + URLEncoder.encode(query, "UTF-8")
        val req = Request.Builder().url(url).header("User-Agent", UA).header("Accept-Language", "pt-BR,pt;q=0.9").build()
        val page = try {
            quick.newCall(req).execute().use { it.body?.string().orEmpty() }
        } catch (e: Exception) {
            throw GeminiException("Sem conexão para pesquisar (${e.javaClass.simpleName}).", -1)
        }
        val re = Regex(
            "<a[^>]*class=\"result__a\"[^>]*href=\"([^\"]+)\"[^>]*>(.*?)</a>.*?class=\"result__snippet\"[^>]*>(.*?)</(?:a|div)>",
            RegexOption.DOT_MATCHES_ALL,
        )
        val results = JSONArray()
        for (m in re.findAll(page)) {
            var href = Html.fromHtml(m.groupValues[1], Html.FROM_HTML_MODE_LEGACY).toString()
            if (href.contains("uddg=")) {
                href = URLDecoder.decode(href.substringAfter("uddg=").substringBefore("&"), "UTF-8")
            }
            if (href.contains("duckduckgo.com") || href.contains("ad_domain=")) continue
            results.put(JSONObject()
                .put("titulo", Html.fromHtml(m.groupValues[2], Html.FROM_HTML_MODE_LEGACY).toString().trim())
                .put("trecho", Html.fromHtml(m.groupValues[3], Html.FROM_HTML_MODE_LEGACY).toString().trim())
                .put("site", href.substringAfter("://").substringBefore("/").removePrefix("www.")))
            if (results.length() >= 5) break
        }
        if (results.length() == 0) throw GeminiException("A pesquisa na web não respondeu agora.", 503)
        return JSONObject().put("resultados", results)
            .put("como_usar", "Responda com base nestes trechos (podem estar incompletos); diga o site se ajudar.")
    }

    // ------------------------------------------------------------------ teste nos ajustes

    /** Faz uma pergunta curtinha para cada cérebro cadastrado e diz quem respondeu. */
    fun testAll(c: Context): String {
        val lines = mutableListOf<String>()
        val ask = "Responda só: ok"
        val geminiKeys = Prefs.geminiKeys(c)
        if (geminiKeys.isEmpty()) lines += "⚪ Gemini: sem chave"
        ensure(c)
        geminiKeys.forEachIndexed { i, key ->
            val name = if (geminiKeys.size > 1) "Gemini chave ${i + 1}" else "Gemini"
            var working: String? = null
            val failed = mutableListOf<String>()
            for (model in geminiModels(c).take(8)) {
                val slot = "gemini#${i + 1}:$model"
                val t0 = System.currentTimeMillis()
                try {
                    val body = JSONObject().put("contents", JSONArray().put(Gemini.userText(ask)))
                    Gemini.generate(key, model, body, quick)
                    pausedUntil.remove(slot)
                    store?.edit()?.remove(slot)?.apply()
                    working = "$model (${System.currentTimeMillis() - t0} ms)"
                    break
                } catch (e: GeminiException) {
                    pause(slot, e)
                    failed += "$model: ${pauseWhy[slot] ?: e.message?.take(60)}"
                }
            }
            lines += if (working != null) "✅ $name vai usar $working" else "❌ $name: nenhum modelo funcionou agora"
            if (failed.isNotEmpty()) lines += "   (pulados: ${failed.joinToString("; ")})"
        }
        val backups = backups(c)
        if (backups.isEmpty()) lines += "⚪ Nenhum cérebro reserva cadastrado"
        for (b in backups) {
            val model = b.models.first()
            val t0 = System.currentTimeMillis()
            lines += try {
                val body = JSONObject().put("model", model).put(maxTokensField(b.id), 300)
                    .put("messages", JSONArray().put(JSONObject().put("role", "user").put("content", ask)))
                if (model.contains("gpt-oss") && (b.id == "groq" || b.id == "cerebras")) body.put("reasoning_effort", "low")
                post("${b.base}/chat/completions", b.key, body)
                pausedUntil.remove("${b.slot}:$model")
                "✅ ${b.label} ($model): ok em ${System.currentTimeMillis() - t0} ms"
            } catch (e: GeminiException) {
                "❌ ${b.label} ($model): ${e.message?.take(120)}"
            }
        }
        return lines.joinToString("\n")
    }
}
