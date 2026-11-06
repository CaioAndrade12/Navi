package com.caio.navi

import android.content.Context
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONArray
import org.json.JSONObject
import java.util.concurrent.TimeUnit

/**
 * Fala com o agente "navi-pc" que roda no Windows (pasta pc/ do repositório).
 * O agente diz quais ações sabe fazer (GET /tools) e o celular repassa para o Gemini.
 * Assim, para ensinar coisa nova ao PC, basta mexer no agente, sem recompilar o app.
 */
object PcClient {
    const val PORT = 8765
    private val JSON_TYPE = "application/json; charset=utf-8".toMediaType()
    private val http = OkHttpClient.Builder()
        .connectTimeout(3, TimeUnit.SECONDS)
        .readTimeout(70, TimeUnit.SECONDS)
        .build()

    @Volatile private var cachedTools: List<Tool>? = null

    private fun base(c: Context): String {
        val h = Prefs.pcHost(c)
        if (h.isEmpty()) throw IllegalStateException("O PC ainda não foi pareado. Abra os ajustes da Navi e toque em 'Procurar PC'.")
        return "http://" + (if (h.contains(":")) h else "$h:$PORT")
    }

    private fun get(url: String, token: String?): JSONObject {
        val b = Request.Builder().url(url)
        if (token != null) b.header("X-Navi-Token", token)
        http.newCall(b.build()).execute().use { r ->
            val text = r.body?.string().orEmpty()
            if (r.code == 401) throw IllegalStateException("O código de pareamento do PC está errado.")
            if (!r.isSuccessful) throw IllegalStateException("O PC respondeu com erro ${r.code}.")
            return JSONObject(text)
        }
    }

    /** Testa se há um agente da Navi em host:porta. Devolve as informações dele ou null. */
    suspend fun ping(host: String): JSONObject? = withContext(Dispatchers.IO) {
        try {
            val h = if (host.contains(":")) host else "$host:$PORT"
            get("http://$h/ping", null).takeIf { it.optString("app") == "navi-pc" }
        } catch (_: Exception) {
            null
        }
    }

    suspend fun discover(): List<Pair<String, JSONObject>> {
        val out = mutableListOf<Pair<String, JSONObject>>()
        for (h in Lan.scan(PORT)) ping(h)?.let { out += "$h:$PORT" to it }
        return out
    }

    /** Baixa a lista de ações do PC e guarda (para funcionar mesmo com o PC desligado). */
    suspend fun refreshTools(c: Context): Int = withContext(Dispatchers.IO) {
        val json = get(base(c) + "/tools", Prefs.pcToken(c))
        c.getSharedPreferences("navi", Context.MODE_PRIVATE).edit()
            .putString("pc_tools", json.optJSONArray("tools")?.toString() ?: "[]").apply()
        json.optString("mac").takeIf { it.isNotEmpty() }?.let { Prefs.setPcMac(c, it) }
        cachedTools = null
        tools(c).size
    }

    fun clearTools(c: Context) {
        c.getSharedPreferences("navi", Context.MODE_PRIVATE).edit().remove("pc_tools").apply()
        cachedTools = null
    }

    suspend fun run(c: Context, name: String, args: JSONObject): JSONObject = withContext(Dispatchers.IO) {
        val body = JSONObject().put("name", name).put("args", args).toString().toRequestBody(JSON_TYPE)
        val req = Request.Builder().url(base(c) + "/run")
            .header("X-Navi-Token", Prefs.pcToken(c))
            .post(body).build()
        try {
            http.newCall(req).execute().use { r ->
                if (r.code == 401) return@withContext fail("O código de pareamento do PC está errado. Pareie de novo nos ajustes.")
                val text = r.body?.string().orEmpty()
                try { JSONObject(text) } catch (_: Exception) { fail("Resposta estranha do PC: ${text.take(100)}") }
            }
        } catch (e: java.io.IOException) {
            fail("O PC não respondeu. Ele está ligado, com o navi-pc aberto e no mesmo Wi-Fi? (pc_ligar pode ligá-lo se o Wake-on-LAN estiver ativo)")
        }
    }

    /** Ferramentas do PC, montadas a partir da lista que o agente informou. */
    fun tools(c: Context): List<Tool> {
        cachedTools?.let { return it }
        val raw = c.getSharedPreferences("navi", Context.MODE_PRIVATE).getString("pc_tools", "[]") ?: "[]"
        val arr = try { JSONArray(raw) } catch (_: Exception) { JSONArray() }
        val list = (0 until arr.length()).mapNotNull { i ->
            val t = arr.optJSONObject(i) ?: return@mapNotNull null
            val name = t.optString("name")
            if (!name.startsWith("pc_")) return@mapNotNull null
            val confirmText = t.optString("confirm", "")
            val confirmFn: ((JSONObject) -> String?)? =
                if (confirmText.isEmpty() || confirmText == "false") null
                else { args: JSONObject ->
                    fill(if (confirmText == "true") "Posso fazer isso no PC ($name)?" else confirmText, args)
                }
            Tool(
                name = name,
                description = t.optString("description"),
                params = t.optJSONObject("parameters"),
                confirm = confirmFn,
                alwaysConfirm = t.optBoolean("confirm_always", false),
                run = { args -> run(c, name, args) },
            )
        }
        cachedTools = list
        return list
    }

    /** Troca {arg} pelo valor do argumento na frase de confirmação. */
    private fun fill(template: String, args: JSONObject): String {
        var s = template
        for (k in args.keys()) s = s.replace("{$k}", args.opt(k)?.toString().orEmpty())
        return s.replace(Regex("\\{\\w+}"), "")
    }
}
