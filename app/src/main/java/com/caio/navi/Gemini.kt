package com.caio.navi

import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONArray
import org.json.JSONObject
import java.util.concurrent.TimeUnit

class GeminiException(message: String, val code: Int = 0) : Exception(message)

/** Cliente mínimo da Gemini API (REST, generateContent). */
object Gemini {
    private const val BASE = "https://generativelanguage.googleapis.com/v1beta/models/"
    private val JSON_TYPE = "application/json; charset=utf-8".toMediaType()

    val http: OkHttpClient = OkHttpClient.Builder()
        .connectTimeout(15, TimeUnit.SECONDS)
        .readTimeout(90, TimeUnit.SECONDS)
        .writeTimeout(60, TimeUnit.SECONDS)
        .build()

    /** Chama generateContent e devolve a resposta inteira. Lança GeminiException em erro. */
    fun generate(key: String, model: String, body: JSONObject): JSONObject {
        if (key.isBlank()) throw GeminiException("Falta a chave da Gemini API nos ajustes.", 401)
        val req = Request.Builder()
            .url("$BASE$model:generateContent")
            .header("x-goog-api-key", key)
            .post(body.toString().toRequestBody(JSON_TYPE))
            .build()
        try {
            http.newCall(req).execute().use { r ->
                val text = r.body?.string().orEmpty()
                if (!r.isSuccessful) {
                    val msg = try {
                        JSONObject(text).getJSONObject("error").optString("message", text)
                    } catch (_: Exception) {
                        text.take(300)
                    }
                    throw GeminiException(msg.ifBlank { "HTTP ${r.code}" }, r.code)
                }
                return JSONObject(text)
            }
        } catch (e: GeminiException) {
            throw e
        } catch (e: Exception) {
            throw GeminiException("Sem conexão com o Gemini (${e.javaClass.simpleName}).", -1)
        }
    }

    /** Conteúdo do modelo na primeira resposta candidata (ou null se veio vazio/bloqueado). */
    fun firstContent(resp: JSONObject): JSONObject? {
        val cands = resp.optJSONArray("candidates") ?: return null
        if (cands.length() == 0) return null
        return cands.getJSONObject(0).optJSONObject("content")
    }

    fun finishReason(resp: JSONObject): String =
        resp.optJSONArray("candidates")?.optJSONObject(0)?.optString("finishReason").orEmpty()

    /** Junta as partes de texto (ignorando "pensamentos"). */
    fun textOf(content: JSONObject?): String {
        val parts = content?.optJSONArray("parts") ?: return ""
        val sb = StringBuilder()
        for (i in 0 until parts.length()) {
            val p = parts.getJSONObject(i)
            if (p.optBoolean("thought", false)) continue
            val t = p.optString("text", "")
            if (t.isNotEmpty()) sb.append(t)
        }
        return sb.toString().trim()
    }

    fun functionCalls(content: JSONObject?): List<JSONObject> {
        val parts = content?.optJSONArray("parts") ?: return emptyList()
        val out = mutableListOf<JSONObject>()
        for (i in 0 until parts.length()) {
            val fc = parts.getJSONObject(i).optJSONObject("functionCall") ?: continue
            out += fc
        }
        return out
    }

    fun userText(text: String) = JSONObject()
        .put("role", "user")
        .put("parts", JSONArray().put(JSONObject().put("text", text)))

    /** Transcreve um áudio WAV curto (usado quando o reconhecedor de voz do celular não funciona). */
    fun transcribe(key: String, model: String, wav: ByteArray): String {
        val parts = JSONArray()
            .put(JSONObject().put("inlineData", JSONObject()
                .put("mimeType", "audio/wav")
                .put("data", android.util.Base64.encodeToString(wav, android.util.Base64.NO_WRAP))))
            .put(JSONObject().put("text",
                "Transcreva exatamente o que foi falado neste áudio, em português do Brasil. " +
                    "Responda só com o texto falado, sem comentários. Se não houver fala, responda vazio."))
        val body = JSONObject()
            .put("contents", JSONArray().put(JSONObject().put("role", "user").put("parts", parts)))
            .put("generationConfig", JSONObject().put("temperature", 0))
        return textOf(firstContent(generate(key, model, body))).trim().trim('"')
    }

    /** Pesquisa no Google pelo próprio Gemini e devolve resposta curta + fontes. */
    fun webSearch(key: String, model: String, query: String): JSONObject {
        val body = JSONObject()
            .put("contents", JSONArray().put(userText(
                "Pesquise na web e responda em português do Brasil, em no máximo 4 frases, " +
                    "com números e datas quando houver: $query"
            )))
            .put("tools", JSONArray().put(JSONObject().put("google_search", JSONObject())))
        val resp = generate(key, model, body)
        val content = firstContent(resp)
        val sources = JSONArray()
        val chunks = resp.optJSONArray("candidates")?.optJSONObject(0)
            ?.optJSONObject("groundingMetadata")?.optJSONArray("groundingChunks")
        if (chunks != null) {
            for (i in 0 until minOf(chunks.length(), 4)) {
                val web = chunks.getJSONObject(i).optJSONObject("web") ?: continue
                sources.put(web.optString("title"))
            }
        }
        return JSONObject().put("resposta", textOf(content)).put("fontes", sources)
    }
}
