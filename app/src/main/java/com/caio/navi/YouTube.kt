package com.caio.navi

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONObject
import java.net.URLEncoder
import java.util.concurrent.TimeUnit

/** Acha o primeiro vídeo de uma busca no YouTube (sem chave de API). */
object YouTube {
    data class Video(val id: String, val title: String)

    private val http = OkHttpClient.Builder()
        .connectTimeout(8, TimeUnit.SECONDS)
        .readTimeout(12, TimeUnit.SECONDS)
        .build()

    suspend fun first(query: String): Video? = withContext(Dispatchers.IO) {
        try {
            val url = "https://www.youtube.com/results?search_query=" + URLEncoder.encode(query, "UTF-8") + "&hl=pt-BR&gl=BR"
            val req = Request.Builder().url(url)
                .header("User-Agent", "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 " +
                    "(KHTML, like Gecko) Chrome/124.0 Safari/537.36")
                .header("Accept-Language", "pt-BR,pt;q=0.9")
                .header("Cookie", "CONSENT=YES+1")
                .build()
            val html = http.newCall(req).execute().use { it.body?.string().orEmpty() }
            val m = Regex("\"videoRenderer\":\\{\"videoId\":\"([\\w-]{11})\"").find(html) ?: return@withContext null
            val tail = html.substring(m.range.last, minOf(html.length, m.range.last + 4000))
            val rawTitle = Regex("\"title\":\\{\"runs\":\\[\\{\"text\":\"(.*?)\"\\}").find(tail)?.groupValues?.get(1)
            val title = try {
                if (rawTitle != null) JSONObject("{\"t\":\"$rawTitle\"}").getString("t") else query
            } catch (_: Exception) {
                rawTitle ?: query
            }
            Video(m.groupValues[1], title)
        } catch (_: Exception) {
            null
        }
    }
}
