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
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import org.json.JSONArray
import org.json.JSONObject
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.InetAddress
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
    suspend fun openApp(c: Context, name: String): String = try {
        openAppLocal(c, name)
    } catch (e: TvException) {
        val wanted = Brain.normalize(name).replace(" ", "")
        val known = KNOWN_APPS.firstOrNull { Brain.normalize(it.name).replace(" ", "").contains(wanted) }
        if (!SmartThings.configured(c) || known == null) throw e
        SmartThings.launchApp(c, known.id)
        known.name
    }

    private suspend fun openAppLocal(c: Context, name: String): String {
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

    /** Liga a TV: Wake-on-LAN pela rede de casa; se não acordar, pela nuvem (SmartThings). */
    suspend fun powerOn(c: Context): String {
        if (isOn(c)) return "A TV já está ligada."
        val mac = Prefs.tvMac(c)
        val wol = mac.isNotEmpty() && Lan.wakeOnLan(mac)
        if (wol) repeat(3) {
            delay(1500)
            if (isOn(c)) return "TV ligada."
        }
        if (SmartThings.configured(c)) {
            SmartThings.power(c, true)
            repeat(6) {
                delay(1500)
                if (isOn(c)) return "TV ligada."
            }
            return "Mandei ligar pela SmartThings. Ela deve ligar em instantes."
        }
        if (!wol) throw TvException("Não sei ligar a TV pela rede (falta o MAC dela). Configure o SmartThings nos ajustes.")
        return "Mandei o sinal de ligar pela rede, mas a TV não respondeu. Ative 'Ligar com o celular' na TV ou configure o SmartThings."
    }

    suspend fun powerOff(c: Context): String {
        if (isOn(c)) {
            try {
                keys(c, listOf("KEY_POWER"))
                return "TV desligada."
            } catch (_: Exception) {
            }
        }
        if (SmartThings.configured(c)) {
            SmartThings.power(c, false)
            return "TV desligada."
        }
        return "A TV já parece estar desligada."
    }

    /** Aperta teclas pela rede de casa; se a TV não responder, tenta pela SmartThings. */
    suspend fun keysWithFallback(c: Context, keys: List<String>): String = try {
        keys(c, keys)
        "ok"
    } catch (e: TvException) {
        if (!SmartThings.configured(c)) throw e
        SmartThings.keys(c, keys)
        "ok"
    }

    // ---------------- Volume exato e estado (UPnP RenderingControl, porta 9197) ----------------

    private const val RC_SERVICE = "urn:schemas-upnp-org:service:RenderingControl:1"
    @Volatile private var rcControl: Pair<String, String>? = null // (ip da TV, endereço do controle)

    data class VolumeInfo(val level: Int, val muted: Boolean?)

    private fun httpGet(url: String): String? = try {
        rest.newCall(Request.Builder().url(url).build()).execute().use { r ->
            if (r.isSuccessful) r.body?.string() else null
        }
    } catch (_: Exception) {
        null
    }

    private fun parseControlUrl(location: String, xml: String): String? {
        for (m in Regex("<service>(.*?)</service>", RegexOption.DOT_MATCHES_ALL).findAll(xml)) {
            val block = m.groupValues[1]
            if (!block.contains("RenderingControl")) continue
            val ctl = Regex("<controlURL>\\s*(.*?)\\s*</controlURL>").find(block)?.groupValues?.get(1) ?: continue
            if (ctl.startsWith("http")) return ctl
            val base = Regex("<URLBase>\\s*(.*?)\\s*</URLBase>").find(xml)?.groupValues?.get(1)?.trimEnd('/')
                ?: Regex("^(https?://[^/]+)").find(location)?.groupValues?.get(1)
                ?: return null
            return base + (if (ctl.startsWith("/")) ctl else "/$ctl")
        }
        return null
    }

    /** Pergunta direto para a TV (SSDP) onde fica a descrição UPnP dela. */
    private fun ssdpLocation(ip: String): String? = try {
        DatagramSocket().use { s ->
            s.soTimeout = 1500
            val msg = ("M-SEARCH * HTTP/1.1\r\nHOST: 239.255.255.250:1900\r\nMAN: \"ssdp:discover\"\r\n" +
                "MX: 1\r\nST: $RC_SERVICE\r\n\r\n").toByteArray()
            s.send(DatagramPacket(msg, msg.size, InetAddress.getByName(ip), 1900))
            val buf = ByteArray(4096)
            val end = System.currentTimeMillis() + 1800
            var loc: String? = null
            while (loc == null && System.currentTimeMillis() < end) {
                val p = DatagramPacket(buf, buf.size)
                try {
                    s.receive(p)
                } catch (_: java.net.SocketTimeoutException) {
                    break
                }
                if (p.address.hostAddress != ip) continue
                loc = Regex("(?im)^location:\\s*(\\S+)").find(String(p.data, 0, p.length))?.groupValues?.get(1)
            }
            loc
        }
    } catch (_: Exception) {
        null
    }

    /** Endereço do controle de volume da TV (lido da descrição UPnP dela). */
    private fun renderingControlUrl(ip: String): String? {
        rcControl?.let { if (it.first == ip) return it.second }
        // 1) Descrição padrão das Samsung (porta 9197).
        val padrao = "http://$ip:9197/dmr"
        val xml = httpGet(padrao)
        if (xml != null) {
            val url = parseControlUrl(padrao, xml) ?: "http://$ip:9197/upnp/control/RenderingControl1"
            rcControl = ip to url
            return url
        }
        // 2) Pergunta para a TV onde está a descrição (SSDP).
        val loc = ssdpLocation(ip) ?: return null
        val xml2 = httpGet(loc) ?: return null
        val url = parseControlUrl(loc, xml2) ?: return null
        rcControl = ip to url
        return url
    }

    private fun soap(url: String, action: String, args: String): String {
        val body = "<?xml version=\"1.0\" encoding=\"utf-8\"?>" +
            "<s:Envelope xmlns:s=\"http://schemas.xmlsoap.org/soap/envelope/\" " +
            "s:encodingStyle=\"http://schemas.xmlsoap.org/soap/encoding/\"><s:Body>" +
            "<u:$action xmlns:u=\"$RC_SERVICE\"><InstanceID>0</InstanceID><Channel>Master</Channel>$args</u:$action>" +
            "</s:Body></s:Envelope>"
        val req = Request.Builder().url(url)
            .header("SOAPACTION", "\"$RC_SERVICE#$action\"")
            .post(body.toRequestBody("text/xml; charset=utf-8".toMediaType()))
            .build()
        rest.newCall(req).execute().use { r ->
            val text = r.body?.string().orEmpty()
            if (!r.isSuccessful) throw TvException("A TV recusou o comando de volume ($action, HTTP ${r.code}).")
            return text
        }
    }

    /** Volume atual da TV (0 a 100) e se está no mudo. null se a TV não informou. */
    suspend fun volume(c: Context): VolumeInfo? = withContext(Dispatchers.IO) {
        val ip = Prefs.tvIp(c)
        if (ip.isEmpty()) return@withContext null
        val url = renderingControlUrl(ip) ?: return@withContext null
        try {
            val v = Regex("<CurrentVolume>(\\d+)</CurrentVolume>").find(soap(url, "GetVolume", ""))
                ?.groupValues?.get(1)?.toInt() ?: return@withContext null
            val m = try {
                Regex("<CurrentMute>(\\w+)</CurrentMute>").find(soap(url, "GetMute", ""))
                    ?.groupValues?.get(1)?.let { it == "1" || it.equals("true", true) }
            } catch (_: Exception) {
                null
            }
            VolumeInfo(v, m)
        } catch (_: Exception) {
            rcControl = null
            null
        }
    }

    private suspend fun upnpSet(c: Context, action: String, arg: String): Boolean = withContext(Dispatchers.IO) {
        val url = renderingControlUrl(Prefs.tvIp(c)) ?: return@withContext false
        try {
            soap(url, action, arg)
            true
        } catch (_: Exception) {
            rcControl = null
            false
        }
    }

    /** Segura uma tecla (como manter o dedo no botão do controle). */
    private suspend fun holdKey(c: Context, key: String, ms: Long) {
        fun cmd(kind: String) = JSONObject().put("method", "ms.remote.control").put("params", JSONObject()
            .put("Cmd", kind).put("DataOfCmd", key).put("Option", "false").put("TypeOfRemote", "SendRemoteKey"))
        send(c, cmd("Press"))
        try {
            delay(ms)
        } finally {
            send(c, cmd("Release"))
        }
    }

    /** Coloca o volume num número exato. */
    private suspend fun setVolume(c: Context, level: Int, before: VolumeInfo?): String {
        val target = level.coerceIn(0, 100)
        if (upnpSet(c, "SetVolume", "<DesiredVolume>$target</DesiredVolume>")) {
            if (before?.muted == true) upnpSet(c, "SetMute", "<DesiredMute>0</DesiredMute>")
            val now = volume(c)?.level ?: target
            return "Volume da TV em $now."
        }
        // Sem o controle exato: zera segurando o botão e sobe contando.
        holdKey(c, "KEY_VOLDOWN", 6000)
        delay(400)
        if (target > 0) keys(c, List(target) { "KEY_VOLUP" }, gapMs = 160)
        return "Coloquei o volume da TV em mais ou menos $target (esta TV não informou o volume, então fui pelo botão)."
    }

    /** consultar, definir, aumentar, diminuir, mudo, tirar_mudo. */
    /** Volume pela rede de casa; se a TV não responder por ela, pela SmartThings. */
    suspend fun volumeAction(c: Context, action: String, amount: Int?): JSONObject {
        val local = try {
            volumeLocal(c, action, amount)
        } catch (e: TvException) {
            if (!SmartThings.configured(c)) throw e
            null
        }
        if (local != null && (local.optString("status") == "ok" || !SmartThings.configured(c))) return local
        return volumeCloud(c, action, amount)
    }

    private suspend fun volumeCloud(c: Context, action: String, amount: Int?): JSONObject {
        val now = SmartThings.volume(c)
        return when (action) {
            "consultar" -> if (now != null) ok("O volume da TV está em $now.").put("volume", now)
            else fail("A TV não informou o volume.")
            "definir" -> {
                val v = (amount ?: return fail("Diga um número de 0 a 100.")).coerceIn(0, 100)
                SmartThings.setVolume(c, v)
                ok("Volume da TV em $v.")
            }
            "aumentar", "diminuir" -> {
                val delta = (amount ?: 3).coerceIn(1, 50) * (if (action == "aumentar") 1 else -1)
                val v = ((now ?: 15) + delta).coerceIn(0, 100)
                SmartThings.setVolume(c, v)
                ok("Volume da TV em $v.")
            }
            "mudo", "tirar_mudo" -> {
                SmartThings.mute(c, action == "mudo")
                ok(if (action == "mudo") "TV no mudo." else "Som da TV ligado.")
            }
            else -> fail("Ação de volume desconhecida: $action")
        }
    }

    private suspend fun volumeLocal(c: Context, action: String, amount: Int?): JSONObject {
        val info = volume(c)
        return when (action) {
            "consultar" -> if (info != null) {
                ok("O volume da TV está em ${info.level}" + (if (info.muted == true) ", no mudo" else "") + ".")
                    .put("volume", info.level).put("mudo", info.muted == true)
            } else {
                fail(if (isOn(c)) "Esta TV não informou o volume." else "Não consegui falar com a TV. Ela está ligada?")
            }
            "definir" -> if (amount == null) fail("Diga um número de 0 a 100.") else ok(setVolume(c, amount, info))
            "aumentar", "diminuir" -> {
                val delta = (amount ?: 3).coerceIn(1, 50) * (if (action == "aumentar") 1 else -1)
                if (info != null) {
                    ok(setVolume(c, info.level + delta, info))
                } else {
                    val key = if (delta > 0) "KEY_VOLUP" else "KEY_VOLDOWN"
                    val r = keysWithFallback(c, List(kotlin.math.abs(delta)) { key })
                    ok(if (r == "ok") "Volume da TV ajustado." else r)
                }
            }
            "mudo", "tirar_mudo" -> {
                val wantMute = action == "mudo"
                if (upnpSet(c, "SetMute", "<DesiredMute>${if (wantMute) 1 else 0}</DesiredMute>")) {
                    ok(if (wantMute) "TV no mudo." else "Som da TV ligado.")
                } else if (info?.muted == wantMute) {
                    ok(if (wantMute) "A TV já está no mudo." else "O som da TV já está ligado.")
                } else {
                    val r = keysWithFallback(c, listOf("KEY_MUTE"))
                    ok(if (r == "ok") "Apertei o mudo da TV." else r)
                }
            }
            else -> fail("Ação de volume desconhecida: $action")
        }
    }

    /** Qual app está aberto na tela da TV (YouTube, Netflix...). null se não deu para saber. */
    suspend fun visibleApp(c: Context): String? = withContext(Dispatchers.IO) {
        val ip = Prefs.tvIp(c)
        if (ip.isEmpty()) return@withContext null
        val candidates = (appsCache.ifEmpty { KNOWN_APPS }).take(40)
        coroutineScope {
            candidates.map { app ->
                async {
                    val j = httpGet("http://$ip:8001/api/v2/applications/${app.id}")?.let {
                        try { JSONObject(it) } catch (_: Exception) { null }
                    }
                    if (j?.optBoolean("visible") == true) app.name else null
                }
            }.awaitAll().firstOrNull { it != null }
        }
    }

    /** Toca um vídeo do YouTube na TV (protocolo DIAL, o mesmo do "transmitir"). */
    suspend fun playYouTube(c: Context, videoId: String): Boolean = withContext(Dispatchers.IO) {
        val ip = Prefs.tvIp(c)
        val dial = try {
            val req = Request.Builder().url("http://$ip:8080/ws/apps/YouTube")
                .post("v=$videoId".toRequestBody("text/plain; charset=utf-8".toMediaType())).build()
            rest.newCall(req).execute().use { it.code in 200..299 }
        } catch (_: Exception) {
            false
        }
        if (dial) return@withContext true
        try {
            send(c, emit("ed.apps.launch", JSONObject().put("appId", "111299001912")
                .put("action_type", "DEEP_LINK").put("metaTag", "v=$videoId")))
            true
        } catch (_: Exception) {
            false
        }
    }
}
