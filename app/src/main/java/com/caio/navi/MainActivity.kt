package com.caio.navi

import android.Manifest
import android.app.Activity
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.drawable.GradientDrawable
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import android.view.Gravity
import android.view.View
import android.view.inputmethod.EditorInfo
import android.widget.Button
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch

/** Tela principal: conversa com a Navi (falando ou digitando). */
class MainActivity : Activity() {

    companion object {
        const val EXTRA_AUTOSTART = "autostart"
        private const val REQ_PERMS = 1
    }

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
    private lateinit var status: TextView
    private lateinit var toggle: Button
    private lateinit var chat: LinearLayout
    private lateinit var scroll: ScrollView
    private lateinit var input: EditText
    private lateinit var hint: TextView
    private var startAfterPermission = false
    private var talkAfterPermission = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)
        NaviService.createChannels(this)

        status = findViewById(R.id.status)
        toggle = findViewById(R.id.toggle)
        chat = findViewById(R.id.chat)
        scroll = findViewById(R.id.scroll)
        input = findViewById(R.id.input)
        hint = findViewById(R.id.setupHint)

        toggle.setOnClickListener {
            if (NaviService.running) NaviService.stop(this) else startNavi()
        }
        findViewById<Button>(R.id.settings).setOnClickListener {
            startActivity(Intent(this, SettingsActivity::class.java))
        }
        hint.setOnClickListener { startActivity(Intent(this, SettingsActivity::class.java)) }
        findViewById<Button>(R.id.send).setOnClickListener { sendTyped() }
        input.setOnEditorActionListener { _, actionId, _ ->
            if (actionId == EditorInfo.IME_ACTION_SEND) { sendTyped(); true } else false
        }
        findViewById<Button>(R.id.mic).setOnClickListener {
            if (!hasMic()) {
                talkAfterPermission = true
                requestRuntimePerms()
            } else {
                Prefs.setEnabled(this, true)
                NaviService.talk(this)
            }
        }

        if (intent?.getBooleanExtra(EXTRA_AUTOSTART, false) == true) startNavi()
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        if (intent.getBooleanExtra(EXTRA_AUTOSTART, false)) startNavi()
    }

    private fun sendTyped() {
        val text = input.text.toString().trim()
        if (text.isEmpty()) return
        input.setText("")
        NaviCore.add(true, text)
        if (Prefs.geminiKey(this).isEmpty()) {
            NaviCore.add(false, "Preciso da chave da Gemini API para pensar. Coloque nos Ajustes (é grátis).")
            return
        }
        status.text = "Pensando..."
        scope.launch {
            val reply = NaviCore.brain(this@MainActivity).handle(text)
            NaviCore.add(false, reply.text)
            refreshStatus()
        }
    }

    private fun bubble(line: NaviCore.Line): View {
        val tv = TextView(this)
        tv.text = line.text
        tv.textSize = 16f
        tv.setTextIsSelectable(true)
        val pad = (12 * resources.displayMetrics.density).toInt()
        tv.setPadding(pad, pad * 2 / 3, pad, pad * 2 / 3)
        tv.maxWidth = (resources.displayMetrics.widthPixels * 0.8).toInt()
        tv.background = GradientDrawable().apply {
            cornerRadius = pad * 1.4f
            setColor(if (line.fromUser) 0x554FA3FF else 0x22888888)
        }
        val lp = LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT
        )
        lp.gravity = if (line.fromUser) Gravity.END else Gravity.START
        lp.topMargin = pad / 2
        tv.layoutParams = lp
        return tv
    }

    private fun addBubble(line: NaviCore.Line) {
        chat.addView(bubble(line))
        scroll.post { scroll.fullScroll(View.FOCUS_DOWN) }
    }

    private fun reloadChat() {
        chat.removeAllViews()
        val lines = NaviCore.history()
        if (lines.isEmpty()) {
            addBubble(NaviCore.Line(false,
                "Oi, ${Prefs.userName(this).ifEmpty { "tudo bem" }}! Diga \"Navi\" ou toque no microfone. " +
                    "Posso controlar a TV e o PC, ler e mandar mensagens, ver seus e-mails e responder o que você quiser.", ""))
        }
        lines.forEach { addBubble(it) }
    }

    private fun hasMic() =
        checkSelfPermission(Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED

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
            Toast.makeText(this, "Dica: libere \"sobrepor outros apps\" nos Ajustes para a Navi abrir apps com a tela em outro app.",
                Toast.LENGTH_LONG).show()
        }
    }

    override fun onRequestPermissionsResult(requestCode: Int, permissions: Array<out String>, grantResults: IntArray) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (hasMic()) {
            if (startAfterPermission) startNavi()
            if (talkAfterPermission) { Prefs.setEnabled(this, true); NaviService.talk(this) }
        }
        startAfterPermission = false
        talkAfterPermission = false
        refreshStatus()
    }

    override fun onResume() {
        super.onResume()
        NaviService.listener = { text -> runOnUiThread { refreshStatus(text) } }
        NaviCore.onLine = { line -> runOnUiThread { addBubble(line) } }
        reloadChat()
        refreshStatus()
    }

    override fun onPause() {
        super.onPause()
        NaviService.listener = null
        NaviCore.onLine = null
    }

    override fun onDestroy() {
        scope.cancel()
        super.onDestroy()
    }

    private fun refreshStatus(text: String = NaviService.status) {
        status.text = if (NaviService.running) text else "Desligada — toque em Ligar para ela ouvir \"Navi\""
        toggle.text = if (NaviService.running) "Desligar" else "Ligar"
        hint.visibility = if (Prefs.geminiKey(this).isEmpty()) View.VISIBLE else View.GONE
    }
}
