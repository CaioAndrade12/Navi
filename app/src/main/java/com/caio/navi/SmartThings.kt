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
 * Segunda via da TV: a nuvem da Samsung (SmartThings). Usada só quando a TV não responde pela
 * rede de casa (ex.: desligada, ou o controle pela rede travou). Precisa de um token pessoal,
 * criado em account.smartthings.com/tokens.
 */
object SmartThings {
    private const val API = "https://api.smartthings.com/v1"
    const val TOKEN_URL = "https://account.smartthings.com/tokens"
    private val JSON_TYPE = "application/json; charset=utf-8".toMediaType()
    private val http = OkHttpClient.Builder()
        .connectTimeout(6, TimeUnit.SECONDS).readTimeout(10, TimeUnit.SECONDS).build()

    fun configured(c: Context) = Prefs.stToken(c).isNotEmpty()

    private fun call(c: Context, method: String, path: String, body: JSONObject? = null): JSONObject {
        val token = Prefs.stToken(c)
        if (token.isEmpty()) throw TvException("SmartThings não configurado (falta o token nos ajustes).")
        val b = Request.Builder().url(API + path).header("Authorization", "Bearer $token")
        if (body != null) b.method(method, body.toString().toRequestBody(JSON_TYPE)) else b.get()
        try {
            http.newCall(b.build()).execute().use { r ->
                val text = r.body?.string().orEmpty()
                if (r.code == 401 || r.code == 403) throw TvException("O token do SmartThings foi recusado. Gere outro nos ajustes.")
                if (!r.isSuccessful) {
                    val msg = try {
                        JSONObject(text).optJSONObject("error")?.optString("message").orEmpty()
                    } catch (_: Exception) {
                        ""
                    }
                    throw TvException("SmartThings respondeu ${r.code}${if (msg.isNotEmpty()) ": $msg" else ""}.")
                }
                return if (text.isBlank()) JSONObject() else JSONObject(text)
            }
        } catch (e: TvException) {
            throw e
        } catch (e: Exception) {
            throw TvException("Sem internet para falar com o SmartThings (${e.javaClass.simpleName}).")
        }
    }

    /** Acha a TV na conta (e guarda o id). Devolve (id, nome). */
    suspend fun findTv(c: Context): Pair<String, String> = withContext(Dispatchers.IO) {
        val items = call(c, "GET", "/devices").optJSONArray("items") ?: JSONArray()
        var best: JSONObject? = null
        for (i in 0 until items.length()) {
            val d = items.getJSONObject(i)
            val caps = d.toString()
            val isTv = d.optJSONObject("ocf")?.optString("deviceType") == "oic.d.tv" ||
                caps.contains("\"tvChannel\"") || caps.contains("samsungvd") ||
                d.optString("deviceTypeName").contains("TV", true)
            if (isTv) { best = d; break }
        }
        val tv = best ?: throw TvException("Não achei nenhuma TV na sua conta SmartThings. Adicione a TV no app SmartThings.")
        val id = tv.optString("deviceId")
        val name = tv.optString("label").ifEmpty { tv.optString("name", "TV") }
        Prefs.setStDevice(c, id)
        id to name
    }

    private suspend fun deviceId(c: Context): String =
        Prefs.stDevice(c).ifEmpty { findTv(c).first }

    suspend fun command(c: Context, capability: String, command: String, args: JSONArray = JSONArray()) =
        withContext(Dispatchers.IO) {
            val cmd = JSONObject().put("component", "main").put("capability", capability)
                .put("command", command).put("arguments", args)
            call(c, "POST", "/devices/${deviceId(c)}/commands",
                JSONObject().put("commands", JSONArray().put(cmd)))
            Unit
        }

    suspend fun status(c: Context): JSONObject = withContext(Dispatchers.IO) {
        call(c, "GET", "/devices/${deviceId(c)}/components/main/status")
    }

    suspend fun power(c: Context, on: Boolean) = command(c, "switch", if (on) "on" else "off")

    suspend fun volume(c: Context): Int? = try {
        status(c).optJSONObject("audioVolume")?.optJSONObject("volume")?.optInt("value")
    } catch (_: Exception) {
        null
    }

    suspend fun setVolume(c: Context, level: Int) = command(c, "audioVolume", "setVolume", JSONArray().put(level.coerceIn(0, 100)))
    suspend fun mute(c: Context, on: Boolean) = command(c, "audioMute", if (on) "mute" else "unmute")
    suspend fun input(c: Context, source: String) = command(c, "mediaInputSource", "setInputSource", JSONArray().put(source))

    private fun key(s: String) = s.uppercase().replace(" ", "").replace("_", "")

    /** Entrada atual (ex.: "HDMI1"), lida da TV pela nuvem. */
    suspend fun currentInput(c: Context): String? = try {
        val st = status(c)
        (st.optJSONObject("mediaInputSource") ?: st.optJSONObject("samsungvd.mediaInputSource"))
            ?.optJSONObject("inputSource")?.optString("value")?.ifEmpty { null }
    } catch (_: Exception) {
        null
    }

    /**
     * Troca para a entrada pedida ("HDMI1".."HDMI4") usando o nome que a própria TV informa,
     * e confere se trocou de verdade. Devolve a entrada em que a TV ficou.
     */
    suspend fun setInputChecked(c: Context, wanted: String): String {
        val st = status(c)
        val names = mutableListOf<String>()
        for (cap in listOf("mediaInputSource", "samsungvd.mediaInputSource")) {
            val arr = st.optJSONObject(cap)?.optJSONObject("supportedInputSources")?.optJSONArray("value") ?: continue
            for (i in 0 until arr.length()) {
                val v = arr.opt(i)
                names += if (v is JSONObject) v.optString("id").ifEmpty { v.optString("name") } else v.toString()
            }
        }
        val target = names.firstOrNull { key(it) == key(wanted) } ?: wanted
        var lastError: Exception? = null
        for (cap in listOf("mediaInputSource", "samsungvd.mediaInputSource")) {
            try {
                command(c, cap, "setInputSource", JSONArray().put(target))
                repeat(4) {
                    kotlinx.coroutines.delay(1000)
                    val now = currentInput(c)
                    if (now != null && key(now) == key(target)) return now
                }
            } catch (e: Exception) {
                lastError = e
            }
        }
        throw TvException(lastError?.message ?: "A TV não trocou para $wanted pela SmartThings.")
    }
    suspend fun channel(c: Context, number: Int) = command(c, "tvChannel", "setTvChannel", JSONArray().put(number.toString()))
    suspend fun launchApp(c: Context, appId: String) = command(c, "custom.launchapp", "launchApp", JSONArray().put(appId))

    /** Teclas do controle ("KEY_HOME" -> "HOME"); nem toda TV aceita pela nuvem. */
    suspend fun keys(c: Context, keys: List<String>) {
        for (k in keys) command(c, "samsungvd.remoteControl", "send", JSONArray().put(k.removePrefix("KEY_")))
    }
}
