package com.caio.navi

import android.Manifest
import android.app.Activity
import android.app.AlertDialog
import android.content.ComponentName
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.PowerManager
import android.provider.Settings
import android.widget.Button
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.RadioGroup
import android.widget.Switch
import android.widget.TextView
import android.widget.Toast
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject

/** Ajustes: chave do Gemini, privacidade, TV, PC, Gmail, permissões e palavra de ativação. */
class SettingsActivity : Activity() {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
    private fun et(id: Int) = findViewById<EditText>(id)
    private fun sw(id: Int) = findViewById<Switch>(id)
    private fun btn(id: Int, action: () -> Unit) = findViewById<Button>(id).setOnClickListener { action() }
    private val dp get() = resources.displayMetrics.density

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_settings)
        load()
        buildAccessList()

        btn(R.id.btnGetKey) { open(Intent(Intent.ACTION_VIEW, Uri.parse("https://aistudio.google.com/apikey"))) }
        btn(R.id.btnAppPassword) { open(Intent(Intent.ACTION_VIEW, Uri.parse("https://myaccount.google.com/apppasswords"))) }
        btn(R.id.btnTestBrain) { testBrain() }
        btn(R.id.btnGroqKey) { open(Intent(Intent.ACTION_VIEW, Uri.parse(Llm.PRESETS.getValue("groq").keyUrl))) }
        btn(R.id.btnThirdKey) {
            val p = Llm.PRESETS[shownThird] ?: Llm.PRESETS.getValue("cerebras")
            open(Intent(Intent.ACTION_VIEW, Uri.parse(p.keyUrl)))
        }
        btn(R.id.btnTestBrains) { busy("Testando os cérebros") { withContext(Dispatchers.IO) { Llm.testAll(this@SettingsActivity) } } }
        btn(R.id.btnFindTv) { findTv() }
        btn(R.id.btnTestTv) { testTv() }
        btn(R.id.btnStToken) { open(Intent(Intent.ACTION_VIEW, Uri.parse(SmartThings.TOKEN_URL))) }
        btn(R.id.btnTestSt) {
            busy("Procurando a TV na SmartThings") {
                val (_, name) = SmartThings.findTv(this@SettingsActivity)
                val vol = SmartThings.volume(this@SettingsActivity)
                "Achei a TV \"$name\" na SmartThings." + (if (vol != null) " Volume agora: $vol." else "")
            }
        }
        btn(R.id.btnFindPc) { findPc() }
        btn(R.id.btnPairPc) { pairPc() }
        btn(R.id.btnRefreshPc) { busy("Testando o PC") { PcClient.diagnose(this) } }
        btn(R.id.btnPlayStart) { save(); scope.launch { Sounds.preview(this@SettingsActivity, Prefs.startSound(this@SettingsActivity), true, previewSpeaker()) } }
        btn(R.id.btnPlayEnd) { save(); scope.launch { Sounds.preview(this@SettingsActivity, Prefs.endSound(this@SettingsActivity), false, previewSpeaker()) } }
        btn(R.id.btnUnpairPc) {
            PcClient.unpair(this)
            refresh()
            toast("PC despareado. No PC, use 'Desparear todos os celulares' para apagar a chave lá também.")
        }
        btn(R.id.btnTestEmail) { testEmail() }

        btn(R.id.btnMic) { requestRuntimePerms() }
        btn(R.id.btnNotifAccess) { open(Intent(Settings.ACTION_NOTIFICATION_LISTENER_SETTINGS)) }
        btn(R.id.btnAccessibility) { open(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS)) }
        btn(R.id.btnOverlay) { open(Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION, Uri.parse("package:$packageName"))) }
        btn(R.id.btnWriteSettings) { open(Intent(Settings.ACTION_MANAGE_WRITE_SETTINGS, Uri.parse("package:$packageName"))) }
        btn(R.id.btnDnd) { open(Intent(Settings.ACTION_NOTIFICATION_POLICY_ACCESS_SETTINGS)) }
        btn(R.id.btnBattery) {
            open(Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS, Uri.parse("package:$packageName")))
        }
        btn(R.id.btnXiaomi) {
            open(Intent("miui.intent.action.APP_PERM_EDITOR")
                .setClassName("com.miui.securitycenter", "com.miui.permcenter.permissions.PermissionsEditorActivity")
                .putExtra("extra_pkgname", packageName))
        }
        btn(R.id.btnAutostart) {
            open(Intent().setComponent(
                ComponentName("com.miui.securitycenter", "com.miui.permcenter.autostart.AutoStartManagementActivity")))
        }
        btn(R.id.btnClearMem) {
            Prefs.setMemories(this, emptyList())
            refresh()
            toast("Memórias apagadas")
        }
        btn(R.id.btnClearChat) {
            NaviCore.clear()
            toast("Conversa limpa")
        }
    }

    override fun onResume() {
        super.onResume()
        refresh()
    }

    override fun onPause() {
        super.onPause()
        save()
    }

    override fun onDestroy() {
        scope.cancel()
        speakerForPreview?.shutdown()
        super.onDestroy()
    }

    private var speakerForPreview: Speaker? = null
    private fun previewSpeaker(): Speaker = speakerForPreview ?: Speaker(this).also { speakerForPreview = it }

    /** Preenche um RadioGroup com opções (id → texto) e marca a atual. Ids de view = posição + 1000. */
    private fun fillRadios(groupId: Int, options: Map<String, String>, current: String) {
        val g = findViewById<RadioGroup>(groupId)
        g.removeAllViews()
        options.entries.forEachIndexed { i, (key, label) ->
            val rb = android.widget.RadioButton(this)
            rb.id = 1000 + i
            rb.text = label
            rb.tag = key
            rb.setTextColor(getColor(R.color.navi_text))
            g.addView(rb)
            if (key == current) g.check(rb.id)
        }
        if (g.checkedRadioButtonId == -1 && g.childCount > 0) g.check(1000)
    }

    private fun checkedKey(groupId: Int): String? {
        val g = findViewById<RadioGroup>(groupId)
        return g.findViewById<android.widget.RadioButton>(g.checkedRadioButtonId)?.tag as? String
    }

    /** Uma chave liga/desliga para cada app/recurso. Vale na hora. */
    private fun buildAccessList() {
        val box = findViewById<LinearLayout>(R.id.accessList)
        for ((key, label) in Prefs.FEATURES) {
            val s = Switch(this)
            s.text = label
            s.textSize = 15f
            s.setTextColor(getColor(R.color.navi_text))
            s.minHeight = (44 * dp).toInt()
            s.isChecked = Prefs.featureEnabled(this, key)
            s.setOnCheckedChangeListener { _, on ->
                Prefs.setFeatureEnabled(this, key, on)
                if (!on && key in Prefs.MESSAGING) MessageStore.purgeApp(this, key)
                if (!on && key == "memoria") toast("A Navi não vai usar nem guardar memórias (as já salvas continuam lá até você apagar).")
            }
            val lp = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT)
            lp.topMargin = (4 * dp).toInt()
            s.layoutParams = lp
            box.addView(s)
        }
    }

    private fun load() {
        et(R.id.geminiKey).setText(Prefs.geminiKey(this))
        et(R.id.geminiModel).setText(Prefs.geminiModel(this))
        et(R.id.userName).setText(Prefs.userName(this))
        sw(R.id.followUp).isChecked = Prefs.followUp(this)
        sw(R.id.confirm).isChecked = Prefs.confirmActions(this)
        sw(R.id.lockProtect).isChecked = Prefs.lockProtection(this)
        et(R.id.tvIp).setText(Prefs.tvIp(this))
        et(R.id.stToken).setText(Prefs.stToken(this))
        et(R.id.pcHost).setText(Prefs.pcHost(this))
        et(R.id.email).setText(Prefs.emailAddress(this))
        et(R.id.emailPass).setText(Prefs.emailPassword(this))
        et(R.id.words).setText(Prefs.wordsRaw(this))
        sw(R.id.vibrate).isChecked = Prefs.vibrate(this)
        sw(R.id.ownBrain).isChecked = Prefs.ownBrain(this)
        findViewById<RadioGroup>(R.id.mode).check(
            when (Prefs.mode(this)) {
                Prefs.MODE_ASSIST -> R.id.modeAssist
                Prefs.MODE_GEMINI -> R.id.modeGemini
                else -> R.id.modeVoice
            }
        )
        et(R.id.pause).setText(Prefs.pauseSec(this).toString())
        val players = linkedMapOf("" to "Automático (o primeiro instalado)")
        MediaControl.installed(this).forEach { players[it.pkg] = it.label }
        fillRadios(R.id.musicApps, players, Prefs.musicApp(this))
        et(R.id.groqKey).setText(Prefs.providerKey(this, "groq"))
        val third = findViewById<RadioGroup>(R.id.thirdProvider)
        third.setOnCheckedChangeListener(null)
        fillRadios(R.id.thirdProvider,
            Llm.THIRD.associateWith { id -> Llm.PRESETS.getValue(id).let { "${it.label} — ${it.about}" } },
            Prefs.thirdProvider(this))
        showThird(checkedKey(R.id.thirdProvider) ?: Prefs.thirdProvider(this))
        third.setOnCheckedChangeListener { _, _ ->
            saveThird()
            checkedKey(R.id.thirdProvider)?.let { id ->
                Prefs.setThirdProvider(this, id)
                showThird(id)
            }
        }
        findViewById<android.widget.TextView>(R.id.brainsInfo).text = Llm.status()
        findViewById<RadioGroup>(R.id.wakeSens).check(
            when (Prefs.wakeSensitivity(this)) { 0 -> R.id.sensLow; 2 -> R.id.sensHigh; else -> R.id.sensNormal })
        fillRadios(R.id.soundVolume, Sounds.VOLUME_OPTIONS, Prefs.soundVolume(this).toString())
        fillRadios(R.id.startSound, Sounds.START_OPTIONS, Prefs.startSound(this))
        fillRadios(R.id.endSound, Sounds.END_OPTIONS, Prefs.endSound(this))
    }

    /** Qual terceiro cérebro os campos de chave/modelo estão mostrando agora. */
    private var shownThird = ""

    private fun showThird(id: String) {
        shownThird = id
        val p = Llm.PRESETS[id] ?: return
        et(R.id.thirdKey).setText(Prefs.providerKey(this, id))
        et(R.id.thirdKey).hint = "Chave do ${p.label}"
        et(R.id.thirdModel).setText(Prefs.providerModel(this, id))
        et(R.id.thirdModel).hint = "Modelo (vazio = ${p.models.first()})"
        findViewById<Button>(R.id.btnThirdKey).text = "Pegar chave grátis do ${p.label}"
    }

    private fun saveThird() {
        if (shownThird.isEmpty()) return
        Prefs.setProviderKey(this, shownThird, et(R.id.thirdKey).text.toString())
        Prefs.setProviderModel(this, shownThird, et(R.id.thirdModel).text.toString())
    }

    private fun save() {
        Prefs.setProviderKey(this, "groq", et(R.id.groqKey).text.toString())
        saveThird()
        Prefs.setGeminiKey(this, et(R.id.geminiKey).text.toString())
        Prefs.setGeminiModel(this, et(R.id.geminiModel).text.toString().ifBlank { Prefs.DEFAULT_MODEL })
        Prefs.setUserName(this, et(R.id.userName).text.toString())
        Prefs.setFollowUp(this, sw(R.id.followUp).isChecked)
        Prefs.setConfirmActions(this, sw(R.id.confirm).isChecked)
        Prefs.setLockProtection(this, sw(R.id.lockProtect).isChecked)
        val newTvIp = et(R.id.tvIp).text.toString().trim()
        if (newTvIp != Prefs.tvIp(this)) {
            Prefs.setTvIp(this, newTvIp)
            Prefs.setTvToken(this, "")
            Prefs.setTvPort(this, 0)
        }
        val newSt = et(R.id.stToken).text.toString().trim()
        if (newSt != Prefs.stToken(this)) {
            Prefs.setStToken(this, newSt)
            Prefs.setStDevice(this, "")
        }
        val newHost = et(R.id.pcHost).text.toString().trim()
        if (newHost.isNotEmpty() || Prefs.pcKey(this).isEmpty()) Prefs.setPcHost(this, newHost)
        Prefs.setEmailAddress(this, et(R.id.email).text.toString())
        Prefs.setEmailPassword(this, et(R.id.emailPass).text.toString())
        val oldWords = Prefs.wordsRaw(this)
        Prefs.setWords(this, et(R.id.words).text.toString().ifBlank { Prefs.DEFAULT_WORDS })
        Prefs.setVibrate(this, sw(R.id.vibrate).isChecked)
        Prefs.setOwnBrain(this, sw(R.id.ownBrain).isChecked)
        Prefs.setMode(this, when (findViewById<RadioGroup>(R.id.mode).checkedRadioButtonId) {
            R.id.modeAssist -> Prefs.MODE_ASSIST
            R.id.modeGemini -> Prefs.MODE_GEMINI
            else -> Prefs.MODE_VOICE
        })
        Prefs.setPauseSec(this, et(R.id.pause).text.toString().toIntOrNull() ?: 12)
        checkedKey(R.id.musicApps)?.let { Prefs.setMusicApp(this, it) }
        Prefs.setWakeSensitivity(this, when (findViewById<RadioGroup>(R.id.wakeSens).checkedRadioButtonId) {
            R.id.sensLow -> 0
            R.id.sensHigh -> 2
            else -> 1
        })
        checkedKey(R.id.soundVolume)?.toIntOrNull()?.let { Prefs.setSoundVolume(this, it) }
        checkedKey(R.id.startSound)?.let { Prefs.setStartSound(this, it) }
        checkedKey(R.id.endSound)?.let { Prefs.setEndSound(this, it) }
        if (NaviService.running && oldWords != Prefs.wordsRaw(this)) NaviService.start(this) // reaplica as palavras
    }

    // ---------------- Testes ----------------

    private fun busy(label: String, block: suspend () -> String) {
        save()
        val dialog = AlertDialog.Builder(this).setMessage("$label...").setCancelable(false).show()
        scope.launch {
            val msg = try {
                block()
            } catch (e: Exception) {
                "Não deu certo: ${e.message ?: e.javaClass.simpleName}"
            }
            dialog.dismiss()
            AlertDialog.Builder(this@SettingsActivity).setMessage(msg).setPositiveButton("OK", null).show()
            refresh()
        }
    }

    private fun testBrain() = busy("Perguntando ao Gemini") {
        withContext(Dispatchers.IO) {
            val body = JSONObject().put("contents", JSONArray().put(
                Gemini.userText("Responda só com uma frase curta e simpática em português: diga que a Navi está funcionando.")))
            val resp = Gemini.generate(Prefs.geminiKey(this@SettingsActivity), Prefs.geminiModel(this@SettingsActivity), body)
            "Funcionou! O Gemini respondeu:\n\n" + Gemini.textOf(Gemini.firstContent(resp))
        }
    }

    private fun findTv() {
        val dialog = AlertDialog.Builder(this).setMessage("Procurando a TV na rede...").setCancelable(false).show()
        scope.launch {
            val found = try { SamsungTv.discover() } catch (_: Exception) { emptyList() }
            dialog.dismiss()
            when {
                found.isEmpty() -> toast("Não achei nenhuma TV Samsung. Ela está ligada e no mesmo Wi-Fi?")
                found.size == 1 -> pickTv(found[0].first, found[0].second)
                else -> AlertDialog.Builder(this@SettingsActivity).setTitle("Qual TV?")
                    .setItems(found.map { "${it.second} (${it.first})" }.toTypedArray()) { _, i ->
                        pickTv(found[i].first, found[i].second)
                    }.show()
            }
        }
    }

    private fun pickTv(ip: String, name: String) {
        et(R.id.tvIp).setText(ip)
        scope.launch {
            SamsungTv.remember(this@SettingsActivity, ip)
            toast("TV encontrada: $name. Agora toque em Testar e aceite o pedido na TV.")
            refresh()
        }
    }

    private fun testTv() = busy("Conectando na TV (aceite o pedido que aparecer na tela dela)") {
        val apps = SamsungTv.apps(this)
        "Conectado na TV! Ela tem ${apps.size} apps, por exemplo: " + apps.take(6).joinToString { it.name }
    }

    private fun findPc() {
        val dialog = AlertDialog.Builder(this).setMessage("Procurando o PC na rede...").setCancelable(false).show()
        scope.launch {
            val found = try { PcClient.discover() } catch (_: Exception) { emptyList() }
            dialog.dismiss()
            if (found.isEmpty()) {
                toast("Não achei o NaviPC. Ele está aberto no PC e no mesmo Wi-Fi? (libere no Firewall, rede privada)")
                return@launch
            }
            val labels = found.map {
                val p = if (it.second.optBoolean("pairing")) " — pronto para parear" else ""
                "${it.second.optString("name", "PC")} (${it.first})$p"
            }.toTypedArray()
            AlertDialog.Builder(this@SettingsActivity).setTitle("Qual PC?")
                .setItems(labels) { _, i ->
                    et(R.id.pcHost).setText(found[i].first)
                    toast("Agora digite o código de 8 dígitos que aparece no PC e toque em Parear.")
                }.show()
        }
    }

    private fun pairPc() {
        val host = et(R.id.pcHost).text.toString().trim()
        val code = et(R.id.pcCode).text.toString().filter { it.isDigit() }
        if (host.isEmpty()) { toast("Primeiro toque em Procurar PC (ou digite o endereço)."); return }
        if (code.length != 8) { toast("O código tem 8 dígitos. No PC: ícone ✦ > Parear um celular."); return }
        busy("Pareando com o PC") {
            val msg = PcClient.pair(this, host, code)
            et(R.id.pcCode).setText("")
            msg
        }
    }

    private fun testEmail() = busy("Entrando no Gmail") {
        val r = Email.list(this, unreadOnly = true, from = null, search = null, limit = 1)
        "Gmail conectado! Você tem ${r.optInt("nao_lidos_na_caixa")} e-mails não lidos."
    }

    // ---------------- Permissões ----------------

    private fun granted(p: String) = checkSelfPermission(p) == PackageManager.PERMISSION_GRANTED

    private fun requestRuntimePerms() {
        val list = mutableListOf(
            Manifest.permission.RECORD_AUDIO,
            Manifest.permission.READ_CONTACTS,
            Manifest.permission.CALL_PHONE,
        )
        if (Build.VERSION.SDK_INT >= 33) list += Manifest.permission.POST_NOTIFICATIONS
        requestPermissions(list.toTypedArray(), 2)
    }

    override fun onRequestPermissionsResult(requestCode: Int, permissions: Array<out String>, grantResults: IntArray) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        refresh()
    }

    private fun refresh() {
        val pm = getSystemService(PowerManager::class.java)
        fun mark(ok: Boolean) = if (ok) "✅" else "⚠️"
        findViewById<TextView>(R.id.brainsInfo).text = Llm.status()
        findViewById<TextView>(R.id.perms).text = buildString {
            appendLine("${mark(granted(Manifest.permission.RECORD_AUDIO))}  Microfone")
            appendLine("${mark(Build.VERSION.SDK_INT < 33 || granted(Manifest.permission.POST_NOTIFICATIONS))}  Notificações")
            appendLine("${mark(granted(Manifest.permission.READ_CONTACTS))}  Contatos (mandar mensagem pelo nome)")
            appendLine("${mark(granted(Manifest.permission.CALL_PHONE))}  Ligações")
            appendLine("${mark(NaviNotificationService.isEnabled(this@SettingsActivity))}  Acesso às notificações (ler mensagens)")
            appendLine("${mark(NaviAccessibilityService.isEnabled(this@SettingsActivity))}  Acessibilidade (enviar WhatsApp, travar tela, print)")
            appendLine("${mark(Settings.System.canWrite(this@SettingsActivity))}  Alterar configurações (brilho do celular)")
            appendLine("${mark(getSystemService(android.app.NotificationManager::class.java).isNotificationPolicyAccessGranted)}  Não perturbe (modo silencioso)")
            appendLine("${mark(Settings.canDrawOverlays(this@SettingsActivity))}  Sobrepor outros apps")
            appendLine("${mark(pm.isIgnoringBatteryOptimizations(packageName))}  Sem restrição de bateria")
            append("${mark(SmartThings.configured(this@SettingsActivity))}  SmartThings (segunda via da TV)")
        }
        val tvIp = Prefs.tvIp(this)
        findViewById<TextView>(R.id.tvInfo).text = when {
            tvIp.isEmpty() -> "Nenhuma TV configurada."
            Prefs.tvToken(this).isNotEmpty() || Prefs.tvPort(this) != 0 ->
                "✅ TV em $tvIp (pareada)" + macLabel(Prefs.tvMac(this))
            else -> "⚠️ TV em $tvIp, ainda não pareada: toque em Testar" + macLabel(Prefs.tvMac(this))
        }
        val paired = Prefs.pcKey(this).isNotEmpty()
        findViewById<TextView>(R.id.pcInfo).text = if (!paired) "Nenhum PC pareado."
        else "✅ Pareado com ${Prefs.pcName(this).ifEmpty { "o PC" }} em ${Prefs.pcHost(this)} — " +
            "${PcClient.tools(this).size} ações" + macLabel(Prefs.pcMac(this))
        val mem = Prefs.memories(this)
        findViewById<TextView>(R.id.memories).text =
            if (mem.isEmpty()) "Nada ainda. Diga, por exemplo: \"Navi, lembra que o HDMI 2 é o videogame\"."
            else mem.joinToString("\n") { "• $it" }
    }

    private fun macLabel(mac: String) = if (mac.isEmpty()) "" else "\nMAC: $mac"

    private fun open(intent: Intent) {
        try {
            startActivity(intent)
        } catch (e: Exception) {
            try {
                startActivity(Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.parse("package:$packageName")))
            } catch (_: Exception) {
                toast("Não consegui abrir essa tela.")
            }
        }
    }

    private fun toast(s: String) = Toast.makeText(this, s, Toast.LENGTH_LONG).show()
}
