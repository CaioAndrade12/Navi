package com.caio.navi

import android.content.Context
import android.os.Build
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONArray
import org.json.JSONObject
import java.security.MessageDigest
import java.security.SecureRandom
import java.util.concurrent.TimeUnit
import javax.crypto.Mac
import javax.crypto.spec.SecretKeySpec

/**
 * Fala com o agente "navi-pc" que roda no Windows (pasta pc/ do repositório).
 * O agente diz quais ações sabe fazer (GET /tools) e o celular repassa para o Gemini.
 *
 * Segurança: no pareamento o PC entrega uma chave secreta de 256 bits. Depois disso a chave
 * nunca mais trafega: cada pedido vai assinado (HMAC-SHA256) com horário e um número único.
 */
object PcClient {
    const val PORT = 8765
    private val JSON_TYPE = "application/json; charset=utf-8".toMediaType()
    private val random = SecureRandom()
    private val http = OkHttpClient.Builder()
        .connectTimeout(3, TimeUnit.SECONDS)
        .readTimeout(70, TimeUnit.SECONDS)
        .build()

    @Volatile private var cachedTools: List<Tool>? = null

    private fun hostPort(h: String) = if (h.contains(":")) h else "$h:$PORT"

    private fun base(c: Context): String {
        val h = Prefs.pcHost(c)
        if (h.isEmpty() || Prefs.pcKey(c).isEmpty()) {
            throw IllegalStateException("O PC ainda não foi pareado. Abra os ajustes da Navi, toque em 'Procurar PC' e pareie.")
        }
        return "http://" + hostPort(h)
    }

    private fun hex(b: ByteArray) = b.joinToString("") { "%02x".format(it) }

    private fun sha256(b: ByteArray) = hex(MessageDigest.getInstance("SHA-256").digest(b))

    private fun hexToBytes(s: String) = ByteArray(s.length / 2) { i -> s.substring(i * 2, i * 2 + 2).toInt(16).toByte() }

    /** Cabeçalhos de autenticação do pedido. */
    private fun signedHeaders(c: Context, method: String, path: String, body: ByteArray): Map<String, String> {
        val key = hexToBytes(Prefs.pcKey(c))
        val ts = (System.currentTimeMillis() / 1000).toString()
        val nonce = ByteArray(12).also { random.nextBytes(it) }.let { hex(it) }
        val msg = "$method\n$path\n$ts\n$nonce\n${sha256(body)}"
        val mac = Mac.getInstance("HmacSHA256").apply { init(SecretKeySpec(key, "HmacSHA256")) }
        return mapOf(
            "X-Navi-Device" to sha256(key).take(16),
            "X-Navi-Ts" to ts,
            "X-Navi-Nonce" to nonce,
            "X-Navi-Sig" to hex(mac.doFinal(msg.toByteArray())),
        )
    }

    private fun execute(req: Request): JSONObject {
        http.newCall(req).execute().use { r ->
            val text = r.body?.string().orEmpty()
            val json = try { JSONObject(text) } catch (_: Exception) { null }
            if (!r.isSuccessful) {
                throw IllegalStateException(json?.optString("erro")?.ifEmpty { null } ?: "O PC respondeu com erro ${r.code}.")
            }
            return json ?: throw IllegalStateException("Resposta estranha do PC.")
        }
    }

    /** Testa se há um agente da Navi em host:porta. Devolve as informações dele ou null. */
    suspend fun ping(host: String): JSONObject? = withContext(Dispatchers.IO) {
        try {
            execute(Request.Builder().url("http://${hostPort(host)}/ping").build())
                .takeIf { it.optString("app") == "navi-pc" }
        } catch (_: Exception) {
            null
        }
    }

    suspend fun discover(): List<Pair<String, JSONObject>> {
        val out = mutableListOf<Pair<String, JSONObject>>()
        for (h in Lan.scan(PORT)) ping(h)?.let { out += "$h:$PORT" to it }
        return out
    }

    /** Pareia com o código de 8 dígitos que o PC mostra. Guarda a chave recebida. */
    suspend fun pair(c: Context, host: String, code: String): String = withContext(Dispatchers.IO) {
        val body = JSONObject().put("code", code.filter { it.isDigit() })
            .put("device", "${Build.MANUFACTURER} ${Build.MODEL}").toString().toRequestBody(JSON_TYPE)
        val resp = execute(Request.Builder().url("http://${hostPort(host)}/pair").post(body).build())
        val key = resp.optString("key")
        if (key.length != 64) throw IllegalStateException("O PC não devolveu uma chave válida.")
        Prefs.setPcHost(c, hostPort(host))
        Prefs.setPcKey(c, key)
        Prefs.setPcName(c, resp.optString("name"))
        resp.optString("mac").takeIf { it.isNotEmpty() }?.let { Prefs.setPcMac(c, it) }
        val n = refreshTools(c)
        "Pareado com ${resp.optString("name", "o PC")}! Ele ensinou $n ações para a Navi."
    }

    fun unpair(c: Context) {
        Prefs.setPcKey(c, "")
        Prefs.setPcName(c, "")
        clearTools(c)
    }

    /** Baixa a lista de ações do PC e guarda (para funcionar mesmo com o PC desligado). */
    suspend fun refreshTools(c: Context): Int = withContext(Dispatchers.IO) {
        val url = base(c) + "/tools"
        val req = Request.Builder().url(url)
        signedHeaders(c, "GET", "/tools", ByteArray(0)).forEach { (k, v) -> req.header(k, v) }
        val json = execute(req.build())
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
        try {
            val bytes = JSONObject().put("name", name).put("args", args).toString().toByteArray()
            val req = Request.Builder().url(base(c) + "/run").post(bytes.toRequestBody(JSON_TYPE))
            signedHeaders(c, "POST", "/run", bytes).forEach { (k, v) -> req.header(k, v) }
            execute(req.build())
        } catch (e: java.io.IOException) {
            fail("O PC não respondeu. Ele está ligado, com o navi-pc aberto e no mesmo Wi-Fi? " +
                "(pc_ligar pode ligá-lo se o Wake-on-LAN estiver ativo)")
        } catch (e: IllegalStateException) {
            fail(e.message ?: "Erro no PC.")
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
                feature = "pc",
                personal = true,
                effect = name != "pc_status",
                untrusted = name == "pc_powershell",
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
