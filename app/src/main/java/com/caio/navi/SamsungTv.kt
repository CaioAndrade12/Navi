package com.caio.navi

import android.annotation.SuppressLint
import android.content.Context
import android.util.Base64
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import org.json.JSONArray
import org.json.JSONObject
import java.security.SecureRandom
import java.security.cert.X509Certificate
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.TimeUnit
import javax.net.ssl.SSLContext
import javax.net.ssl.TrustManager
import javax.net.ssl.X509TrustManager

class TvException(message: String) : Exception(message)

/**
 * Controle da TV Samsung (Tizen 2016+, ex.: MU6100) pela rede de casa:
 * WebSocket na porta 8002 (seguro, com token) ou 8001, e REST na 8001.
 * Na primeira conexão a TV mostra um pedido de permissão na tela: é só aceitar.
 */
@SuppressLint("CustomX509TrustManager", "TrustAllX509TrustManager", "BadHostnameVerifier")
object SamsungTv {
    private val NAME_B64 = Base64.encodeToString("Navi".toByteArray(), Base64.NO_WRAP)

    // Apps conhecidos, usados se a TV não devolver a lista de instalados.
    private val KNOWN_APPS = listOf(
        App("111299001912", "YouTube", 2),
        App("11101200001", "Netflix", 2),
        App("3201512006785", "Prime Video", 2),
        App("3201901017640", "Disney+", 2),
        App("3201606009684", "Spotify", 2),
        App("org.tizen.browser", "Internet", 4),
    )

    data class App(val id: String, val name: String, val type: Int)

    private class Session {
        @Volatile var ws: WebSocket? = null
        @Volatile var open = true
        val connected = CompletableDeferred<String>() // "" = conectou; texto = erro
        val waiters = ConcurrentHashMap<String, CompletableDeferred<JSONObject>>()

        fun send(text: String): Boolean = ws?.send(text) == true
        fun close() {
            open = false
            ws?.cancel()
        }
    }

    private val mutex = Mutex()
    private var session: Session? = null
    private var appsCache: List<App> = emptyList()

    /** Cliente que aceita o certificado próprio da TV (só é usado para falar com a TV). */
    private val client: OkHttpClient by lazy {
        val trustAll = object : X509TrustManager {
            override fun checkClientTrusted(chain: Array<X509Certificate>?, authType: String?) {}
            override fun checkServerTrusted(chain: Array<X509Certificate>?, authType: String?) {}
            override fun getAcceptedIssuers(): Array<X509Certificate> = arrayOf()
        }
        val ssl = SSLContext.getInstance("TLS").apply { init(null, arrayOf<TrustManager>(trustAll), SecureRandom()) }
        OkHttpClient.Builder()
            .sslSocketFactory(ssl.socketFactory, trustAll)
            .hostnameVerifier { _, _ -> true }
            .connectTimeout(3, TimeUnit.SECONDS)
            .readTimeout(0, TimeUnit.MILLISECONDS)
            .pingInterval(20, TimeUnit.SECONDS)
            .build()
    }

    private val rest: OkHttpClient by lazy {
        OkHttpClient.Builder().connectTimeout(2, TimeUnit.SECONDS).readTimeout(4, TimeUnit.SECONDS).build()
    }

    // ---------------- REST ----------------

    /** Informações da TV (nome, modelo, MAC). null se a TV não respondeu (desligada/fora da rede). */
    suspend fun info(ip: String): JSONObject? = withContext(Dispatchers.IO) {
        try {
            rest.newCall(Request.Builder().url("http://$ip:8001/api/v2/").build()).execute().use { r ->
                if (!r.isSuccessful) return@withContext null
                JSONObject(r.body?.string().orEmpty())
            }
        } catch (_: Exception) {
            null
        }
    }

    suspend fun isOn(c: Context): Boolean {
        val ip = Prefs.tvIp(c)
        if (ip.isEmpty()) return false
        val i = info(ip) ?: return false
        // Modelos mais novos informam o estado; os de 2017 só respondem quando ligados.
        val state = i.optJSONObject("device")?.optString("PowerState").orEmpty()
        return state.isEmpty() || state.equals("on", true)
    }

    /** Procura TVs Samsung na rede. Devolve pares (ip, nome). */
    suspend fun discover(): List<Pair<String, String>> {
        val hosts = Lan.scan(8001)
        val out = mutableListOf<Pair<String, String>>()
        for (h in hosts) {
            val dev = info(h)?.optJSONObject("device") ?: continue
            val name = dev.optString("name").ifEmpty { dev.optString("modelName", "Samsung TV") }
            out += h to name
        }
        return out
    }

    /** Salva a TV escolhida e guarda o MAC (para ligar pela rede). */
    suspend fun remember(c: Context, ip: String) {
        Prefs.setTvIp(c, ip)
        Prefs.setTvToken(c, "")
        Prefs.setTvPort(c, 0)
        val mac = info(ip)?.optJSONObject("device")?.optString("wifiMac").orEmpty()
        if (mac.isNotEmpty()) Prefs.setTvMac(c, mac)
        mutex.withLock { session?.close(); session = null }
        appsCache = emptyList()
    }

    // ---------------- WebSocket ----------------

    private suspend fun connect(c: Context): Session = mutex.withLock {
        session?.let { if (it.open) return@withLock it }
        val ip = Prefs.tvIp(c)
        if (ip.isEmpty()) throw TvException("A TV ainda não foi configurada. Toque em 'Procurar TV' nos ajustes da Navi.")
        val ports = if (Prefs.tvPort(c) == 8001) listOf(8001, 8002) else listOf(8002, 8001)
        var lastError = "A TV não respondeu. Ela está ligada e no mesmo Wi-Fi?"
        for (port in ports) {
            val firstTime = Prefs.tvToken(c).isEmpty()
            val s = open(c, ip, port)
            val err = withTimeoutOrNull(if (firstTime) 30_000L else 6_000L) { s.connected.await() }
                ?: "A TV não confirmou a conexão (porta $port). Se apareceu um pedido na tela da TV, aceite."
            if (err.isEmpty()) {
                Prefs.setTvPort(c, port)
                session = s
                return@withLock s
            }
            s.close()
            lastError = err
        }
        throw TvException(lastError)
    }

    private fun open(c: Context, ip: String, port: Int): Session {
        val token = Prefs.tvToken(c)
        val url = if (port == 8002) {
            "wss://$ip:8002/api/v2/channels/samsung.remote.control?name=$NAME_B64" +
                if (token.isNotEmpty()) "&token=$token" else ""
        } else {
            "ws://$ip:8001/api/v2/channels/samsung.remote.control?name=$NAME_B64"
        }
        val s = Session()
        val listener = object : WebSocketListener() {
            override fun onMessage(webSocket: WebSocket, text: String) {
                val msg = try { JSONObject(text) } catch (_: Exception) { return }
                when (val event = msg.optString("event")) {
                    "ms.channel.connect" -> {
                        val t = msg.optJSONObject("data")?.optString("token").orEmpty()
                        if (t.isNotEmpty()) Prefs.setTvToken(c, t)
                        s.connected.complete("")
                    }
                    "ms.channel.unauthorized" -> s.connected.complete(
                        "A TV recusou a conexão. Na TV, vá em Configurações > Geral > Gerenciador de dispositivos externos > " +
                            "Gerenciador de conexão de dispositivos e permita a Navi."
                    )
                    "ms.channel.timeOut" -> s.connected.complete("O pedido de permissão na TV expirou. Tente de novo e aceite na tela da TV.")
                    else -> s.waiters.remove(event)?.complete(msg)
                }
            }

            override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) {
                s.open = false
                s.connected.complete("Não consegui conectar na TV (porta $port): ${t.javaClass.simpleName}.")
                session.takeIf { it === s }?.let { session = null }
            }

            override fun onClosed(webSocket: WebSocket, code: Int, reason: String) {
                s.open = false
                s.connected.complete("A TV fechou a conexão.")
                session.takeIf { it === s }?.let { session = null }
            }
        }
        s.ws = client.newWebSocket(Request.Builder().url(url).build(), listener)
        return s
    }

    private suspend fun send(c: Context, payload: JSONObject) {
        var s = connect(c)
        if (!s.send(payload.toString())) {
            s.close()
            mutex.withLock { if (session === s) session = null }
            s = connect(c)
            if (!s.send(payload.toString())) throw TvException("Perdi a conexão com a TV.")
        }
    }

    private fun keyPayload(key: String) = JSONObject()
        .put("method", "ms.remote.control")
        .put("params", JSONObject()
            .put("Cmd", "Click")
            .put("DataOfCmd", key)
            .put("Option", "false")
            .put("TypeOfRemote", "SendRemoteKey"))

    private fun emit(event: String, data: JSONObject? = null): JSONObject {
        val params = JSONObject().put("event", event).put("to", "host")
        if (data != null) params.put("data", data)
        return JSONObject().put("method", "ms.channel.emit").put("params", params)
    }

    /** Aperta teclas do controle (ex.: KEY_HOME, KEY_VOLUP) pela rede. */
    suspend fun keys(c: Context, keys: List<String>, gapMs: Long = 350) {
        for ((i, k) in keys.withIndex()) {
            send(c, keyPayload(k))
            if (i < keys.size - 1) delay(gapMs)
        }
    }

    /** Digita um texto no teclado que estiver aberto na TV (ex.: busca do YouTube). */
    suspend fun type(c: Context, text: String) {
        val b64 = Base64.encodeToString(text.toByteArray(), Base64.NO_WRAP)
        send(c, JSONObject().put("method", "ms.remote.control").put("params", JSONObject()
            .put("Cmd", b64).put("DataOfCmd", "base64").put("TypeOfRemote", "SendInputString")))
        delay(300)
        send(c, JSONObject().put("method", "ms.remote.control").put("params", JSONObject()
            .put("TypeOfRemote", "SendInputEnd")))
    }

    suspend fun apps(c: Context): List<App> {
        if (appsCache.isNotEmpty()) return appsCache
        val s = connect(c)
        val waiter = CompletableDeferred<JSONObject>()
        s.waiters["ed.installedApp.get"] = waiter
        send(c, emit("ed.installedApp.get"))
        val resp = withTimeoutOrNull(5000) { waiter.await() }
        s.waiters.remove("ed.installedApp.get")
        val list = resp?.optJSONObject("data")?.optJSONArray("data") ?: JSONArray()
        val found = (0 until list.length()).mapNotNull { i ->
            val a = list.optJSONObject(i) ?: return@mapNotNull null
            App(a.optString("appId"), a.optString("name"), a.optInt("app_type", 2))
        }.filter { it.id.isNotEmpty() && it.name.isNotEmpty() }
        appsCache = found
        return found.ifEmpty { KNOWN_APPS }
    }

    /** Abre um app pelo nome (aproximado): "youtube", "netflix", "globoplay"... */
    suspend fun openApp(c: Context, name: String): String {
        val all = apps(c)
        val wanted = Brain.normalize(name).replace(" ", "")
        val app = all.firstOrNull { Brain.normalize(it.name).replace(" ", "") == wanted }
            ?: all.firstOrNull { Brain.normalize(it.name).replace(" ", "").contains(wanted) }
            ?: all.firstOrNull { wanted.contains(Brain.normalize(it.name).replace(" ", "")) }
            ?: KNOWN_APPS.firstOrNull { Brain.normalize(it.name).replace(" ", "").contains(wanted) }
            ?: throw TvException("Não achei o app \"$name\" na TV. Apps instalados: " + all.joinToString { it.name })
        val type = if (app.type == 4) "NATIVE_LAUNCH" else "DEEP_LINK"
        send(c, emit("ed.apps.launch", JSONObject().put("appId", app.id).put("action_type", type).put("metaTag", "")))
        // Reforço pela API REST (alguns modelos preferem esse caminho).
        withContext(Dispatchers.IO) {
            try {
                rest.newCall(Request.Builder()
                    .url("http://${Prefs.tvIp(c)}:8001/api/v2/applications/${app.id}")
                    .post(ByteArray(0).toRequestBody())
                    .build()).execute().close()
            } catch (_: Exception) {
            }
        }
        return app.name
    }

    /** Liga a TV: Wake-on-LAN (se a TV permitir) + infravermelho do celular. */
    suspend fun powerOn(c: Context): String {
        if (isOn(c)) return "A TV já está ligada."
        val mac = Prefs.tvMac(c)
        val wol = mac.isNotEmpty() && Lan.wakeOnLan(mac)
        val ir = Prefs.irEnabled(c) && IrRemote.sendSamsung(c, "POWER_ON")
        if (!wol && !ir) throw TvException("Não tenho como ligar a TV: falta o MAC dela e o infravermelho está indisponível.")
        repeat(10) {
            delay(1500)
            if (isOn(c)) return "TV ligada."
            if (wol && it == 3) Lan.wakeOnLan(mac)
        }
        return if (ir) "Mandei o sinal de ligar pelo infravermelho. Se não ligou, aponte o celular para a TV."
        else "Mandei o sinal de ligar pela rede, mas a TV não respondeu. Ative 'Ligar com o celular' nas configurações de rede da TV."
    }

    suspend fun powerOff(c: Context): String {
        if (isOn(c)) {
            try {
                keys(c, listOf("KEY_POWER"))
                return "TV desligada."
            } catch (_: Exception) {
            }
        }
        if (Prefs.irEnabled(c) && IrRemote.sendSamsung(c, "POWER_OFF")) return "Mandei o sinal de desligar pelo infravermelho."
        return "A TV já parece estar desligada."
    }

    /** Aperta teclas pela rede; se a TV não responder, tenta pelo infravermelho. */
    suspend fun keysWithFallback(c: Context, keys: List<String>): String = try {
        keys(c, keys)
        "ok"
    } catch (e: TvException) {
        if (Prefs.irEnabled(c) && keys.all { IrRemote.SAMSUNG.containsKey(it) }) {
            keys.forEach { IrRemote.sendSamsung(c, it) }
            "Enviado pelo infravermelho (a TV não respondeu pela rede)."
        } else {
            throw e
        }
    }
}
