package com.caio.navi

import android.Manifest
import android.app.Activity
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

class MainActivity : Activity() {

    companion object {
        const val EXTRA_AUTOSTART = "autostart"
        private const val REQ_PERMS = 1
    }

    private lateinit var status: TextView
    private lateinit var toggle: Button
    private lateinit var perms: TextView
    private lateinit var last: TextView
    private var startAfterPermission = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)
        NaviService.createChannels(this)

        status = findViewById(R.id.status)
        toggle = findViewById(R.id.toggle)
        perms = findViewById(R.id.perms)
        last = findViewById(R.id.last)

        toggle.setOnClickListener {
            if (NaviService.running) {
                NaviService.stop(this)
            } else {
                startNavi()
            }
        }

        findViewById<Button>(R.id.test).setOnClickListener {
            if (!AssistLauncher.launchNow(this)) {
                Toast.makeText(this, "Nenhum assistente encontrado. Confira se o Gemini está instalado.", Toast.LENGTH_LONG).show()
            }
        }

        findViewById<Button>(R.id.btnMic).setOnClickListener { requestRuntimePerms() }
        findViewById<Button>(R.id.btnOverlay).setOnClickListener {
            open(Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION, Uri.parse("package:$packageName")))
        }
        findViewById<Button>(R.id.btnBattery).setOnClickListener {
            open(Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS, Uri.parse("package:$packageName")))
        }
        findViewById<Button>(R.id.btnXiaomi).setOnClickListener {
            open(
                Intent("miui.intent.action.APP_PERM_EDITOR")
                    .setClassName("com.miui.securitycenter", "com.miui.permcenter.permissions.PermissionsEditorActivity")
                    .putExtra("extra_pkgname", packageName)
            )
        }
        findViewById<Button>(R.id.btnAutostart).setOnClickListener {
            open(
                Intent().setComponent(
                    ComponentName("com.miui.securitycenter", "com.miui.permcenter.autostart.AutoStartManagementActivity")
                )
            )
        }

        setupSettings()

        if (intent?.getBooleanExtra(EXTRA_AUTOSTART, false) == true) startNavi()
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        if (intent.getBooleanExtra(EXTRA_AUTOSTART, false)) startNavi()
    }

    private fun setupSettings() {
        val words = findViewById<EditText>(R.id.words)
        val mode = findViewById<RadioGroup>(R.id.mode)
        val pause = findViewById<EditText>(R.id.pause)
        val vibrate = findViewById<CheckBox>(R.id.vibrate)

        words.setText(Prefs.wordsRaw(this))
        mode.check(
            when (Prefs.mode(this)) {
                Prefs.MODE_ASSIST -> R.id.modeAssist
                Prefs.MODE_GEMINI -> R.id.modeGemini
                else -> R.id.modeVoice
            }
        )
        pause.setText(Prefs.pauseSec(this).toString())
        vibrate.isChecked = Prefs.vibrate(this)

        findViewById<Button>(R.id.save).setOnClickListener {
            Prefs.setWords(this, words.text.toString().ifBlank { Prefs.DEFAULT_WORDS })
            Prefs.setMode(
                this, when (mode.checkedRadioButtonId) {
                    R.id.modeAssist -> Prefs.MODE_ASSIST
                    R.id.modeGemini -> Prefs.MODE_GEMINI
                    else -> Prefs.MODE_VOICE
                }
            )
            Prefs.setPauseSec(this, pause.text.toString().toIntOrNull() ?: 12)
            Prefs.setVibrate(this, vibrate.isChecked)
            if (NaviService.running) NaviService.start(this) // reaplica as palavras
            Toast.makeText(this, "Ajustes salvos", Toast.LENGTH_SHORT).show()
        }
    }

    private fun hasMic() =
        checkSelfPermission(Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED

    private fun hasNotif() =
        Build.VERSION.SDK_INT < 33 ||
            checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) == PackageManager.PERMISSION_GRANTED

    private fun requestRuntimePerms() {
        val list = mutableListOf(Manifest.permission.RECORD_AUDIO)
        if (Build.VERSION.SDK_INT >= 33) list += Manifest.permission.POST_NOTIFICATIONS
        requestPermissions(list.toTypedArray(), REQ_PERMS)
    }

    private fun startNavi() {
        if (!hasMic()) {
            startAfterPermission = true
            requestRuntimePerms()
            return
        }
        Prefs.setEnabled(this, true)
        NaviService.start(this)
        if (!Settings.canDrawOverlays(this)) {
            Toast.makeText(
                this,
                "Falta permitir \"sobrepor outros apps\", senão o Gemini não abre com a tela em outro app.",
                Toast.LENGTH_LONG
            ).show()
        }
    }

    override fun onRequestPermissionsResult(requestCode: Int, permissions: Array<out String>, grantResults: IntArray) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        refresh()
        if (startAfterPermission && hasMic()) startNavi()
        startAfterPermission = false
    }

    override fun onResume() {
        super.onResume()
        NaviService.listener = { text -> runOnUiThread { refresh(text) } }
        refresh()
    }

    override fun onPause() {
        super.onPause()
        NaviService.listener = null
    }

    private fun refresh(text: String = NaviService.status) {
        status.text = if (NaviService.running) text else "Desligada"
        toggle.text = if (NaviService.running) "Desligar a Navi" else "Ligar a Navi"
        val ev = Prefs.lastEvent(this)
        last.text = if (ev.isEmpty()) "" else "Última ativação: $ev"

        val pm = getSystemService(PowerManager::class.java)
        fun mark(ok: Boolean) = if (ok) "✅" else "❌"
        perms.text = buildString {
            appendLine("${mark(hasMic())} Microfone")
            appendLine("${mark(hasNotif())} Notificações")
            appendLine("${mark(Settings.canDrawOverlays(this@MainActivity))} Sobrepor outros apps")
            appendLine("${mark(pm.isIgnoringBatteryOptimizations(packageName))} Sem restrição de bateria")
            append("ℹ️ Na Xiaomi, ative também \"Inicialização automática\" e \"Exibir janelas pop-up em segundo plano\".")
        }
    }

    private fun open(intent: Intent) {
        try {
            startActivity(intent)
        } catch (e: Exception) {
            // Se a tela específica não existir, abre as informações do app.
            try {
                startActivity(Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.parse("package:$packageName")))
            } catch (_: Exception) {
                Toast.makeText(this, "Não consegui abrir essa tela.", Toast.LENGTH_SHORT).show()
            }
        }
    }
}
