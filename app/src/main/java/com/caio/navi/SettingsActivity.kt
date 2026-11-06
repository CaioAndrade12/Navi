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
import android.widget.CheckBox
import android.widget.EditText
import android.widget.RadioGroup
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

/** Ajustes: chave do Gemini, TV, PC, Gmail, permissões e palavra de ativação. */
class SettingsActivity : Activity() {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
    private fun et(id: Int) = findViewById<EditText>(id)
    private fun cb(id: Int) = findViewById<CheckBox>(id)
    private fun btn(id: Int, action: () -> Unit) = findViewById<Button>(id).setOnClickListener { action() }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_settings)
        load()

        btn(R.id.save) { save(); toast("Ajustes salvos") }
        btn(R.id.btnGetKey) { open(Intent(Intent.ACTION_VIEW, Uri.parse("https://aistudio.google.com/apikey"))) }
        btn(R.id.btnAppPassword) { open(Intent(Intent.ACTION_VIEW, Uri.parse("https://myaccount.google.com/apppasswords"))) }
        btn(R.id.btnTestBrain) { testBrain() }
        btn(R.id.btnFindTv) { findTv() }
        btn(R.id.btnTestTv) { testTv() }
        btn(R.id.btnFindPc) { findPc() }
        btn(R.id.btnTestPc) { testPc() }
        btn(R.id.btnTestEmail) { testEmail() }

        btn(R.id.btnMic) { requestRuntimePerms() }
        btn(R.id.btnNotifAccess) { open(Intent(Settings.ACTION_NOTIFICATION_LISTENER_SETTINGS)) }
        btn(R.id.btnAccessibility) { open(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS)) }
        btn(R.id.btnOverlay) { open(Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION, Uri.parse("package:$packageName"))) }
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

    override fun onDestroy() {
        scope.cancel()
        super.onDestroy()
    }

    private fun load() {
        et(R.id.geminiKey).setText(Prefs.geminiKey(this))
        et(R.id.geminiModel).setText(Prefs.geminiModel(this))
        et(R.id.userName).setText(Prefs.userName(this))
        cb(R.id.followUp).isChecked = Prefs.followUp(this)
        cb(R.id.confirm).isChecked = Prefs.confirmActions(this)
        et(R.id.tvIp).setText(Prefs.tvIp(this))
        cb(R.id.irEnabled).isChecked = Prefs.irEnabled(this)
        et(R.id.pcHost).setText(Prefs.pcHost(this))
        et(R.id.pcToken).setText(Prefs.pcToken(this))
        et(R.id.email).setText(Prefs.emailAddress(this))
        et(R.id.emailPass).setText(Prefs.emailPassword(this))
        et(R.id.words).setText(Prefs.wordsRaw(this))
        cb(R.id.vibrate).isChecked = Prefs.vibrate(this)
        cb(R.id.ownBrain).isChecked = Prefs.ownBrain(this)
        findViewById<RadioGroup>(R.id.mode).check(
            when (Prefs.mode(this)) {
                Prefs.MODE_ASSIST -> R.id.modeAssist
                Prefs.MODE_GEMINI -> R.id.modeGemini
                else -> R.id.modeVoice
            }
        )
        et(R.id.pause).setText(Prefs.pauseSec(this).toString())
    }

    private fun save() {
        Prefs.setGeminiKey(this, et(R.id.geminiKey).text.toString())
        Prefs.setGeminiModel(this, et(R.id.geminiModel).text.toString().ifBlank { Prefs.DEFAULT_MODEL })
        Prefs.setUserName(this, et(R.id.userName).text.toString())
        Prefs.setFollowUp(this, cb(R.id.followUp).isChecked)
        Prefs.setConfirmActions(this, cb(R.id.confirm).isChecked)
        val newTvIp = et(R.id.tvIp).text.toString().trim()
        if (newTvIp != Prefs.tvIp(this)) {
            Prefs.setTvIp(this, newTvIp)
            Prefs.setTvToken(this, "")
            Prefs.setTvPort(this, 0)
        }
        Prefs.setIrEnabled(this, cb(R.id.irEnabled).isChecked)
        val newHost = et(R.id.pcHost).text.toString().trim()
        if (newHost != Prefs.pcHost(this) && newHost.isEmpty()) PcClient.clearTools(this)
        Prefs.setPcHost(this, newHost)
        Prefs.setPcToken(this, et(R.id.pcToken).text.toString())
        Prefs.setEmailAddress(this, et(R.id.email).text.toString())
        Prefs.setEmailPassword(this, et(R.id.emailPass).text.toString())
        Prefs.setWords(this, et(R.id.words).text.toString().ifBlank { Prefs.DEFAULT_WORDS })
        Prefs.setVibrate(this, cb(R.id.vibrate).isChecked)
        Prefs.setOwnBrain(this, cb(R.id.ownBrain).isChecked)
        Prefs.setMode(this, when (findViewById<RadioGroup>(R.id.mode).checkedRadioButtonId) {
            R.id.modeAssist -> Prefs.MODE_ASSIST
            R.id.modeGemini -> Prefs.MODE_GEMINI
            else -> Prefs.MODE_VOICE
        })
        Prefs.setPauseSec(this, et(R.id.pause).text.toString().toIntOrNull() ?: 12)
        if (NaviService.running) NaviService.start(this) // reaplica as palavras
        refresh()
    }

    // ---------------- Testes ----------------

    private fun busy(label: String, block: suspend () -> String) {
        save()
        val dialog = AlertDialog.Builder(this).setMessage("$label...").setCancelable(false).show()
        scope.launch {
            val msg = try {
                block()
            } catch (e: Exception) {
                "Erro: ${e.message ?: e.javaClass.simpleName}"
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
                toast("Não achei o navi-pc. Ele está aberto no PC e no mesmo Wi-Fi? (libere no Firewall do Windows)")
                return@launch
            }
            val labels = found.map { "${it.second.optString("name", "PC")} (${it.first})" }.toTypedArray()
            AlertDialog.Builder(this@SettingsActivity).setTitle("Qual PC?")
                .setItems(labels) { _, i ->
                    et(R.id.pcHost).setText(found[i].first)
                    toast("Agora digite o código de pareamento que aparece no PC e toque em Parear.")
                }.show()
        }
    }

    private fun testPc() = busy("Pareando com o PC") {
        val n = PcClient.refreshTools(this)
        "Pareado! O PC ensinou $n ações para a Navi."
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
        fun mark(ok: Boolean) = if (ok) "✅" else "❌"
        findViewById<TextView>(R.id.perms).text = buildString {
            appendLine("${mark(granted(Manifest.permission.RECORD_AUDIO))} Microfone")
            appendLine("${mark(Build.VERSION.SDK_INT < 33 || granted(Manifest.permission.POST_NOTIFICATIONS))} Notificações")
            appendLine("${mark(granted(Manifest.permission.READ_CONTACTS))} Contatos (mandar mensagem pelo nome)")
            appendLine("${mark(granted(Manifest.permission.CALL_PHONE))} Ligações")
            appendLine("${mark(NaviNotificationService.isEnabled(this@SettingsActivity))} Acesso às notificações (ler mensagens)")
            appendLine("${mark(NaviAccessibilityService.isEnabled(this@SettingsActivity))} Acessibilidade (enviar WhatsApp)")
            appendLine("${mark(Settings.canDrawOverlays(this@SettingsActivity))} Sobrepor outros apps")
            appendLine("${mark(pm.isIgnoringBatteryOptimizations(packageName))} Sem restrição de bateria")
            appendLine("${mark(IrRemote.available(this@SettingsActivity))} Infravermelho do celular")
            append("ℹ️ Na Xiaomi, ative também \"Inicialização automática\" e \"Exibir janelas pop-up em segundo plano\".")
        }
        val tvIp = Prefs.tvIp(this)
        findViewById<TextView>(R.id.tvInfo).text = when {
            tvIp.isEmpty() -> "Nenhuma TV configurada."
            Prefs.tvToken(this).isNotEmpty() || Prefs.tvPort(this) != 0 ->
                "TV em $tvIp (pareada, porta ${Prefs.tvPort(this)})" + macLabel(Prefs.tvMac(this))
            else -> "TV em $tvIp (ainda não pareada: toque em Testar)" + macLabel(Prefs.tvMac(this))
        }
        val pcCount = PcClient.tools(this).size
        findViewById<TextView>(R.id.pcInfo).text = if (Prefs.pcHost(this).isEmpty()) "Nenhum PC pareado."
        else "PC em ${Prefs.pcHost(this)} — $pcCount ações conhecidas" + macLabel(Prefs.pcMac(this))
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
