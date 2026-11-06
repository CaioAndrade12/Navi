package com.caio.navi

import android.app.KeyguardManager
import android.app.SearchManager
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.media.AudioManager
import android.media.MediaMetadata
import android.media.session.MediaController
import android.media.session.MediaSessionManager
import android.media.session.PlaybackState
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.SystemClock
import android.provider.MediaStore
import android.view.KeyEvent
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import org.json.JSONObject

/**
 * Música no celular: tocar por busca, controlar e saber o que está tocando.
 *
 * Caminho preferido: falar com a "sessão de mídia" do app (Spotify, YouTube Music...),
 * que funciona até com a tela desligada. Se o app não está aberto, ele é aberto com o
 * pedido "tocar a partir da busca" e a Navi confere se a música começou de verdade.
 */
object MediaControl {
    data class Player(val pkg: String, val label: String, val searchIntent: Boolean = true)

    val PLAYERS = listOf(
        Player("com.spotify.music", "Spotify"),
        Player("com.google.android.apps.youtube.music", "YouTube Music"),
        Player("deezer.android.app", "Deezer"),
        Player("com.amazon.mp3", "Amazon Music"),
        Player("com.apple.android.music", "Apple Music"),
        Player("com.soundcloud.android", "SoundCloud"),
        Player("com.google.android.youtube", "YouTube", searchIntent = false),
    )

    /** Pausar de novo depois que a conversa acabar (alguns apps voltam a tocar sozinhos). */
    @Volatile var pauseAfterConversation = false

    private fun isInstalled(c: Context, pkg: String) = try {
        c.packageManager.getPackageInfo(pkg, 0)
        true
    } catch (_: Exception) {
        false
    }

    fun installed(c: Context): List<Player> = PLAYERS.filter { isInstalled(c, it.pkg) }

    private fun key(s: String) = Brain.normalize(s).replace(" ", "")

    /** O app pedido na frase, o preferido nos ajustes ou o primeiro instalado. */
    fun choose(c: Context, wanted: String?): Player? {
        val inst = installed(c)
        if (!wanted.isNullOrBlank()) {
            val w = key(wanted)
            inst.firstOrNull { key(it.label) == w }?.let { return it }
            inst.firstOrNull { key(it.label).contains(w) || w.contains(key(it.label)) }?.let { return it }
        }
        val pref = Prefs.musicApp(c)
        inst.firstOrNull { it.pkg == pref }?.let { return it }
        return inst.firstOrNull { it.searchIntent } ?: inst.firstOrNull()
    }

    fun sessions(c: Context): List<MediaController> {
        if (!NaviNotificationService.isEnabled(c)) return emptyList()
        return try {
            c.getSystemService(MediaSessionManager::class.java)
                ?.getActiveSessions(ComponentName(c, NaviNotificationService::class.java)) ?: emptyList()
        } catch (_: Exception) {
            emptyList()
        }
    }

    private fun isPlaying(ctl: MediaController) = ctl.playbackState?.state == PlaybackState.STATE_PLAYING

    private fun appLabel(c: Context, pkg: String) = PLAYERS.firstOrNull { it.pkg == pkg }?.label ?: try {
        c.packageManager.getApplicationLabel(c.packageManager.getApplicationInfo(pkg, 0)).toString()
    } catch (_: Exception) {
        pkg
    }

    /** O que está tocando agora (ou o último que tocou). */
    fun nowPlaying(c: Context): JSONObject? {
        val all = sessions(c)
        val ctl = all.firstOrNull { isPlaying(it) } ?: all.firstOrNull() ?: return null
        val md = ctl.metadata
        val title = md?.getString(MediaMetadata.METADATA_KEY_TITLE).orEmpty()
        val artist = (md?.getString(MediaMetadata.METADATA_KEY_ARTIST)
            ?: md?.getString(MediaMetadata.METADATA_KEY_ALBUM_ARTIST)).orEmpty()
        return JSONObject()
            .put("app", appLabel(c, ctl.packageName))
            .put("musica", title)
            .put("artista", artist)
            .put("tocando", isPlaying(ctl))
    }

    private suspend fun waitPlaying(c: Context, pkg: String, ms: Long): Boolean {
        val am = c.getSystemService(AudioManager::class.java)
        val end = SystemClock.elapsedRealtime() + ms
        val canSeeSessions = NaviNotificationService.isEnabled(c)
        while (SystemClock.elapsedRealtime() < end) {
            if (canSeeSessions) {
                if (sessions(c).any { it.packageName == pkg && isPlaying(it) }) return true
            } else if (am?.isMusicActive == true) {
                return true
            }
            delay(400)
        }
        return false
    }

    private fun locked(c: Context) = try {
        c.getSystemService(KeyguardManager::class.java)?.isDeviceLocked == true
    } catch (_: Exception) {
        false
    }

    private fun backgroundHint(): String =
        if (Build.MANUFACTURER.equals("Xiaomi", true) || Build.BRAND.equals("POCO", true) || Build.BRAND.equals("Redmi", true))
            "Na Xiaomi, ative 'Exibir janelas pop-up em segundo plano' para a Navi (Ajustes da Navi > Xiaomi: pop-up)."
        else "Libere 'Sobrepor outros apps' nos Ajustes da Navi."

    private fun playingResult(c: Context, player: Player, query: String): JSONObject {
        val np = nowPlaying(c)
        val what = np?.let {
            val t = it.optString("musica")
            val a = it.optString("artista")
            when {
                t.isNotEmpty() && a.isNotEmpty() -> "$t, de $a"
                t.isNotEmpty() -> t
                else -> null
            }
        } ?: query
        return ok("Tocando $what no ${player.label}.").put("_fim_da_conversa", true)
    }

    /** Toca uma música, artista, álbum ou playlist. */
    suspend fun play(c: Context, query: String, appWanted: String?): JSONObject {
        if (query.isBlank()) return control(c, "continuar")
        val player = choose(c, appWanted) ?: return playOnYouTube(c, query)
        if (!player.searchIntent) return playOnYouTube(c, query)

        // 1) App já aberto: pede pela sessão de mídia dele (funciona até com a tela apagada).
        sessions(c).firstOrNull { it.packageName == player.pkg }?.let { ctl ->
            ctl.transportControls.playFromSearch(query, Bundle())
            if (waitPlaying(c, player.pkg, 6000)) return playingResult(c, player, query)
        }

        // 2) Abre o app com o pedido "tocar a partir da busca".
        val intent = Intent(MediaStore.INTENT_ACTION_MEDIA_PLAY_FROM_SEARCH)
            .setPackage(player.pkg)
            .putExtra(MediaStore.EXTRA_MEDIA_FOCUS, "vnd.android.cursor.item/*")
            .putExtra(SearchManager.QUERY, query)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        val launched = try {
            withContext(Dispatchers.Main) { c.startActivity(intent) }
            true
        } catch (_: Exception) {
            false
        }
        if (launched && waitPlaying(c, player.pkg, 9000)) return playingResult(c, player, query)

        // 3) O app abriu mas não começou: pede pela sessão (que agora existe) ou manda "play".
        sessions(c).firstOrNull { it.packageName == player.pkg }?.let { ctl ->
            ctl.transportControls.playFromSearch(query, Bundle())
            if (waitPlaying(c, player.pkg, 5000)) return playingResult(c, player, query)
            ctl.transportControls.play()
            if (waitPlaying(c, player.pkg, 3000)) return playingResult(c, player, query)
        }

        if (locked(c)) {
            // Com o celular bloqueado o Android não deixa abrir o app: a Navi pede para desbloquear e tenta de novo.
            return fail("Para abrir o ${player.label} preciso que o celular esteja desbloqueado.").put("_desbloquear", true)
        }
        if (!launched) return fail("Não consegui abrir o ${player.label}.")
        if (!NaviNotificationService.isEnabled(c)) {
            return ok("Pedi para o ${player.label} tocar \"$query\". Para eu confirmar e controlar a música, " +
                "libere o acesso às notificações nos Ajustes da Navi.")
        }
        return fail("O ${player.label} não começou a tocar. ${backgroundHint()}")
    }

    /** Sem app de música (ou pedido no YouTube): toca o primeiro vídeo da busca no app do YouTube. */
    suspend fun playOnYouTube(c: Context, query: String): JSONObject {
        val video = YouTube.first(query)
        val uri = if (video != null) Uri.parse("https://www.youtube.com/watch?v=${video.id}")
        else Uri.parse("https://www.youtube.com/results?search_query=" + Uri.encode(query))
        val intent = Intent(Intent.ACTION_VIEW, uri).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        if (isInstalled(c, "com.google.android.youtube")) intent.setPackage("com.google.android.youtube")
        return try {
            withContext(Dispatchers.Main) { c.startActivity(intent) }
            if (video != null && waitPlaying(c, "com.google.android.youtube", 8000)) {
                ok("Tocando ${video.title} no YouTube.").put("_fim_da_conversa", true)
            } else if (locked(c)) {
                fail("Para abrir o YouTube preciso que o celular esteja desbloqueado.").put("_desbloquear", true)
            } else if (video != null) {
                ok("Abri ${video.title} no YouTube.").put("_fim_da_conversa", true)
            } else {
                ok("Abri a busca de \"$query\" no YouTube.")
            }
        } catch (_: Exception) {
            fail("Não consegui abrir o YouTube. ${backgroundHint()}")
        }
    }

    /** pausar, continuar, proxima, anterior, parar. */
    fun control(c: Context, action: String): JSONObject {
        val all = sessions(c)
        val ctl = all.firstOrNull { isPlaying(it) } ?: all.firstOrNull()
        if (action == "pausar" || action == "parar") pauseAfterConversation = true
        if (ctl != null) {
            val t = ctl.transportControls
            when (action) {
                "pausar" -> t.pause()
                "continuar" -> t.play()
                "proxima" -> t.skipToNext()
                "anterior" -> t.skipToPrevious()
                "parar" -> t.stop()
                else -> return fail("Ação desconhecida: $action")
            }
            val app = appLabel(c, ctl.packageName)
            return ok(when (action) {
                "pausar" -> "Pausei o $app."
                "continuar" -> "Voltei a tocar no $app."
                "proxima" -> "Próxima música no $app."
                "anterior" -> "Música anterior no $app."
                else -> "Parei o $app."
            }).put("_fim_da_conversa", action != "pausar" && action != "parar")
        }
        // Sem acesso às sessões: manda a tecla de mídia (vai para o app que tocou por último).
        val code = when (action) {
            "pausar" -> KeyEvent.KEYCODE_MEDIA_PAUSE
            "continuar" -> KeyEvent.KEYCODE_MEDIA_PLAY
            "proxima" -> KeyEvent.KEYCODE_MEDIA_NEXT
            "anterior" -> KeyEvent.KEYCODE_MEDIA_PREVIOUS
            "parar" -> KeyEvent.KEYCODE_MEDIA_STOP
            else -> return fail("Ação desconhecida: $action")
        }
        val am = c.getSystemService(AudioManager::class.java) ?: return fail("Sem controle de áudio.")
        am.dispatchMediaKeyEvent(KeyEvent(KeyEvent.ACTION_DOWN, code))
        am.dispatchMediaKeyEvent(KeyEvent(KeyEvent.ACTION_UP, code))
        return ok("Feito.")
    }
}
