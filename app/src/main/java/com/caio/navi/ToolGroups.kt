package com.caio.navi

import org.json.JSONArray
import org.json.JSONObject

/**
 * Manda para a IA só as ferramentas do assunto do pedido, em vez de todas (~80, uns 8 mil tokens).
 * Se faltar alguma, a própria IA chama [LOAD] e o grupo entra na hora, na mesma rodada.
 */
object ToolGroups {
    const val LOAD = "carregar_ferramentas"

    class Group(val id: String, val about: String, val has: (String) -> Boolean, val words: Regex)

    private val PC_SETTINGS = setOf(
        "pc_saida_som", "pc_volume_app", "pc_microfone", "pc_brilho", "pc_monitor", "pc_monitor_entrada", "pc_tela",
        "pc_projetar", "pc_modo_escuro", "pc_configuracoes", "pc_plano_energia", "pc_tempo_tela", "pc_bluetooth",
        "pc_powershell", "pc_desligar_tela",
    )
    private val MUSIC = setOf("celular_tocar_musica", "celular_midia", "celular_tocando")

    /** A ordem importa: a primeira regra que pega o nome da ferramenta decide o grupo dela. */
    val GROUPS = listOf(
        Group("tv", "TV Samsung: ligar, volume, apps, HDMI, canais, teclas, YouTube", { it.startsWith("tv_") },
            Regex("\\b(tv|televisao|televisor|netflix|globoplay|prime video|disney|hbo|crunchyroll|paramount|hdmi|canal|canais)\\b")),
        Group("pc_ajustes", "PC: saída de som, volume por programa, microfone, brilho, monitor, resolução, telas, energia, bluetooth, configurações",
            { it in PC_SETTINGS },
            Regex("\\b(som|fone|fones|headset|caixa|caixas|alto falante|microfone|mic|brilho|contraste|monitor|tela|resolucao|hz|hertz|projet\\w*|duplic\\w*|estend\\w*|escuro|bluetooth|energia|suspend\\w*|configura\\w*|ajuste\\w*|powershell|mixer|hdr|desempenho|economia)\\b")),
        Group("pc", "PC: abrir programas, jogos e sites, pesquisar, YouTube, janelas, volume, mídia, digitar, atalhos, desligar",
            { it.startsWith("pc_") },
            Regex("\\b(pc|computador|notebook|windows|steam|epic|jogo|jogos|game|games|navegador|chrome|edge|firefox|discord|site|sites|link|programa|programas|janela|janelas|desktop|area de trabalho)\\b")),
        Group("mensagens", "mensagens e e-mail: ler, responder, WhatsApp, Instagram, Gmail",
            { it.startsWith("mensage") || it == "whatsapp_enviar" || it.startsWith("email_") },
            Regex("\\b(mensage\\w*|msg|whats\\w*|zap|wpp|email|emails|e mail|gmail|insta\\w*|direct|dm|manda|mande|mandar|envia|envie|enviar|responde|responda|responder|escreve|escreva|notifica\\w*|recado|conversa)\\b")),
        Group("musica", "música no celular: tocar, pausar, próxima, o que está tocando", { it in MUSIC },
            Regex("\\b(toca|tocar|toque|musica|musicas|spotify|deezer|pausa|pause|pausar|continua|continuar|proxima|anterior|playlist|album|banda|cantor\\w*|ouvir|tocando|podcast|faixa)\\b")),
        Group("tempo", "alarmes, timers e lembretes",
            { it == "celular_alarme" || it == "celular_timer" || it.startsWith("lembrete_") },
            Regex("\\b(alarme|despert\\w*|timer|temporizador|cronometro|lembr\\w*|avisa|avise|minutos?|horas?|segundos?|amanha|daqui)\\b")),
        Group("celular", "celular: apps, ligações, lanterna, bateria, volume, brilho, modo silencioso, tela, rotas, ajustes, câmera",
            { it.startsWith("celular_") },
            Regex("\\b(ligacao|telefon\\w*|liga pra|liga para|ligar pra|ligar para|chama|lanterna|bateria|celular|telefone|app|aplicativo|volume|brilho|tela|print|captura|silencio\\w*|vibra\\w*|perturbe|wifi|wi fi|bluetooth|camera|foto|selfie|rota|caminho|navega\\w*|mapa|bloqueia|trava|ajustes|configura\\w*)\\b")),
    )
    val IDS = GROUPS.map { it.id }

    /** Sempre vão junto: são pequenas e servem para qualquer assunto. */
    private val CORE = setOf("pesquisar_web", "memoria_salvar", "memoria_apagar", "pc_ligar")
    private val OPEN = Regex("\\b(abre|abrir|abra|fecha|fechar|feche|inicia|iniciar|executa)\\b")
    private val PC_WORDS = GROUPS.first { it.id == "pc" }.words
    private val TV_WORDS = GROUPS.first { it.id == "tv" }.words

    fun groupOf(tool: String): String? = GROUPS.firstOrNull { it.has(tool) }?.id

    /**
     * Escolhe as ferramentas: grupos cujas palavras aparecem no pedido (e na fala anterior), grupos
     * usados nas últimas rodadas (para "aumenta mais", "fecha ele") e os que a IA pediu ([extra]).
     */
    fun select(all: List<Tool>, history: List<JSONObject>, extra: Set<String>, text: String): List<Tool> {
        val t = Brain.normalize((text + " " + lastUserText(history, text)).replace(Brain.FOLLOW_UP_NOTE, " "))
        val wanted = extra.toMutableSet()
        for (g in GROUPS) if (g.id != "pc_ajustes" && g.words.containsMatchIn(t)) wanted += g.id
        recentCalls(history).forEach { groupOf(it)?.let { g -> wanted += g } }
        val pcContext = PC_WORDS.containsMatchIn(t) || "pc" in wanted || "pc_ajustes" in wanted
        if (pcContext && GROUPS.first { it.id == "pc_ajustes" }.words.containsMatchIn(t)) wanted += "pc_ajustes"
        if (OPEN.containsMatchIn(t) && !pcContext && !TV_WORDS.containsMatchIn(t)) wanted += "celular"
        if (wanted.isEmpty()) wanted += listOf("celular", "musica", "tempo")
        val chosen = all.filter { tool ->
            val g = groupOf(tool.name)
            tool.name in CORE || g == null || g in wanted
        }
        val missing = GROUPS.filter { it.id !in wanted && all.any { tool -> it.has(tool.name) && tool.name !in CORE } }
        return if (missing.isEmpty()) chosen else chosen + loader(missing)
    }

    /** A ferramenta que carrega mais grupos (só lista os que ficaram de fora). */
    private fun loader(missing: List<Group>): Tool = Tool(
        LOAD,
        "Carrega mais ferramentas quando a que você precisa não está na lista. Grupos: " +
            missing.joinToString("; ") { "${it.id} (${it.about})" } + ".",
        Schema.obj("grupos" to JSONObject().put("type", "array").put("description", "grupos a carregar")
            .put("items", JSONObject().put("type", "string").put("format", "enum")
                .put("enum", JSONArray(missing.map { it.id }))), required = listOf("grupos")),
    ) { _ -> ok() }

    private fun isUserText(c: JSONObject): Boolean {
        val first = c.optJSONArray("parts")?.optJSONObject(0)
        return c.optString("role") == "user" && first != null && !first.has("functionResponse")
    }

    /** Fala anterior do usuário (contexto para "e no PC?"), sem repetir a atual. */
    private fun lastUserText(h: List<JSONObject>, current: String): String {
        var seen = 0
        for (i in h.indices.reversed()) {
            val c = h[i]
            if (!isUserText(c)) continue
            val parts = c.optJSONArray("parts") ?: continue
            val txt = (0 until parts.length()).joinToString(" ") { parts.optJSONObject(it)?.optString("text").orEmpty() }
            if (seen == 0 && txt.contains(current)) { seen++; continue }
            return txt
        }
        return ""
    }

    /** Ferramentas chamadas nas últimas 2 rodadas. */
    private fun recentCalls(h: List<JSONObject>): Set<String> {
        val out = mutableSetOf<String>()
        var userTurns = 0
        for (i in h.indices.reversed()) {
            val c = h[i]
            if (isUserText(c) && ++userTurns > 3) break
            val parts = c.optJSONArray("parts") ?: continue
            for (j in 0 until parts.length()) {
                parts.optJSONObject(j)?.optJSONObject("functionCall")?.let { out += it.optString("name") }
            }
        }
        return out
    }
}
