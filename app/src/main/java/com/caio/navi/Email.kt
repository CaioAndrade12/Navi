package com.caio.navi

import android.content.Context
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.util.Properties
import javax.mail.Folder
import javax.mail.Message
import javax.mail.Multipart
import javax.mail.Part
import javax.mail.Session
import javax.mail.Transport
import javax.mail.UIDFolder
import javax.mail.internet.InternetAddress
import javax.mail.internet.MimeMessage
import javax.mail.search.AndTerm
import javax.mail.search.BodyTerm
import javax.mail.search.FlagTerm
import javax.mail.search.FromStringTerm
import javax.mail.search.OrTerm
import javax.mail.search.SearchTerm
import javax.mail.search.SubjectTerm
import javax.mail.Flags

/**
 * Gmail por IMAP/SMTP usando uma "senha de app" do Google
 * (Conta Google > Segurança > Verificação em duas etapas > Senhas de app).
 */
object Email {

    private fun props(): Properties = Properties().apply {
        put("mail.store.protocol", "imaps")
        put("mail.imaps.host", "imap.gmail.com")
        put("mail.imaps.port", "993")
        put("mail.imaps.connectiontimeout", "12000")
        put("mail.imaps.timeout", "20000")
        put("mail.smtp.host", "smtp.gmail.com")
        put("mail.smtp.port", "465")
        put("mail.smtp.auth", "true")
        put("mail.smtp.ssl.enable", "true")
        put("mail.smtp.connectiontimeout", "12000")
        put("mail.smtp.timeout", "20000")
    }

    private fun creds(c: Context): Pair<String, String> {
        val user = Prefs.emailAddress(c)
        val pass = Prefs.emailPassword(c)
        if (user.isEmpty() || pass.isEmpty()) {
            throw IllegalStateException("O Gmail ainda não foi configurado. Coloque seu e-mail e uma senha de app nos ajustes da Navi.")
        }
        return user to pass
    }

    private fun <T> withInbox(c: Context, mode: Int, block: (Folder) -> T): T {
        val (user, pass) = creds(c)
        val store = Session.getInstance(props()).getStore("imaps")
        try {
            store.connect("imap.gmail.com", user, pass)
        } catch (e: javax.mail.AuthenticationFailedException) {
            throw IllegalStateException("O Gmail recusou o login. Confira o e-mail e a senha de app (não é a senha normal).")
        }
        try {
            val inbox = store.getFolder("INBOX")
            inbox.open(mode)
            try {
                return block(inbox)
            } finally {
                try { inbox.close(false) } catch (_: Exception) {}
            }
        } finally {
            try { store.close() } catch (_: Exception) {}
        }
    }

    private fun fromOf(m: Message): String {
        val a = m.from?.firstOrNull() as? InternetAddress ?: return m.from?.firstOrNull()?.toString().orEmpty()
        return a.personal?.takeIf { it.isNotBlank() } ?: a.address.orEmpty()
    }

    /** Lista e-mails da caixa de entrada (mais recentes primeiro). */
    suspend fun list(c: Context, unreadOnly: Boolean, from: String?, search: String?, limit: Int): JSONObject =
        withContext(Dispatchers.IO) {
            withInbox(c, Folder.READ_ONLY) { inbox ->
                val terms = mutableListOf<SearchTerm>()
                if (unreadOnly) terms += FlagTerm(Flags(Flags.Flag.SEEN), false)
                if (!from.isNullOrBlank()) terms += FromStringTerm(from)
                if (!search.isNullOrBlank()) terms += OrTerm(SubjectTerm(search), BodyTerm(search))
                val total = inbox.messageCount
                val msgs: List<Message> = if (terms.isEmpty()) {
                    if (total == 0) emptyList()
                    else inbox.getMessages(maxOf(1, total - limit + 1), total).toList()
                } else {
                    val term = if (terms.size == 1) terms[0] else AndTerm(terms.toTypedArray())
                    inbox.search(term).toList().takeLast(limit)
                }
                val uidFolder = inbox as UIDFolder
                val arr = JSONArray()
                for (m in msgs.asReversed()) {
                    arr.put(JSONObject()
                        .put("id", uidFolder.getUID(m).toString())
                        .put("de", fromOf(m))
                        .put("assunto", m.subject.orEmpty())
                        .put("data", m.sentDate?.let { Msg.timeLabel(it.time) }.orEmpty())
                        .put("lido", m.isSet(Flags.Flag.SEEN)))
                }
                JSONObject().put("status", "ok").put("emails", arr)
                    .put("nao_lidos_na_caixa", inbox.unreadMessageCount)
            }
        }

    /** Abre um e-mail pelo id (marca como lido). */
    suspend fun read(c: Context, id: String): JSONObject = withContext(Dispatchers.IO) {
        withInbox(c, Folder.READ_WRITE) { inbox ->
            val uid = id.toLongOrNull() ?: return@withInbox fail("id de e-mail inválido")
            val m = (inbox as UIDFolder).getMessageByUID(uid) ?: return@withInbox fail("E-mail não encontrado.")
            val body = textOf(m).replace(Regex("\\s+"), " ").trim()
            m.setFlag(Flags.Flag.SEEN, true)
            JSONObject().put("status", "ok")
                .put("de", fromOf(m))
                .put("assunto", m.subject.orEmpty())
                .put("data", m.sentDate?.let { Msg.timeLabel(it.time) }.orEmpty())
                .put("texto", body.take(3500))
        }
    }

    private fun textOf(p: Part): String {
        return try {
            when {
                p.isMimeType("text/plain") -> p.content?.toString().orEmpty()
                p.isMimeType("text/html") -> stripHtml(p.content?.toString().orEmpty())
                p.isMimeType("multipart/alternative") -> {
                    val mp = p.content as Multipart
                    var html = ""
                    for (i in 0 until mp.count) {
                        val bp = mp.getBodyPart(i)
                        if (bp.isMimeType("text/plain")) return bp.content?.toString().orEmpty()
                        if (bp.isMimeType("text/html")) html = stripHtml(bp.content?.toString().orEmpty())
                    }
                    html
                }
                p.isMimeType("multipart/*") -> {
                    val mp = p.content as Multipart
                    (0 until mp.count).map { textOf(mp.getBodyPart(it)) }.firstOrNull { it.isNotBlank() }.orEmpty()
                }
                else -> ""
            }
        } catch (_: Exception) {
            ""
        }
    }

    private fun stripHtml(html: String) = html
        .replace(Regex("(?is)<(script|style).*?</\\1>"), " ")
        .replace(Regex("<[^>]+>"), " ")
        .replace("&nbsp;", " ").replace("&amp;", "&").replace("&lt;", "<").replace("&gt;", ">")
        .replace("&quot;", "\"").replace("&#39;", "'")

    /** Envia um e-mail. [to] pode ser um endereço ou o nome de um contato. */
    suspend fun send(c: Context, to: String, subject: String, body: String): JSONObject = withContext(Dispatchers.IO) {
        val (user, pass) = creds(c)
        val address = if (to.contains("@")) to.trim()
        else Contacts.findEmails(c, to).firstOrNull()?.second
            ?: return@withContext fail("Não achei o e-mail de \"$to\" nos contatos. Diga o endereço.")
        val session = Session.getInstance(props())
        val msg = MimeMessage(session).apply {
            setFrom(InternetAddress(user))
            setRecipients(Message.RecipientType.TO, InternetAddress.parse(address))
            setSubject(subject, "UTF-8")
            setText(body, "UTF-8")
        }
        try {
            Transport.send(msg, user, pass)
        } catch (e: javax.mail.AuthenticationFailedException) {
            return@withContext fail("O Gmail recusou o login. Confira a senha de app nos ajustes.")
        }
        ok("E-mail enviado para $address.")
    }
}
