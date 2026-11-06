package com.caio.navi

import android.Manifest
import android.app.Activity
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Color
import android.graphics.drawable.GradientDrawable
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import android.view.Gravity
import android.view.View
import android.view.inputmethod.EditorInfo
import android.widget.EditText
import android.widget.ImageButton
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
        private val SUGGESTIONS = listOf(
            "Que horas são?", "Liga a TV", "Lê minhas mensagens", "Volume do PC em 30",
            "Vai chover amanhã?", "Abre o YouTube na TV",
        )
    }

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
    private lateinit var status: TextView
    private lateinit var statusDot: View
    private lateinit var toggle: TextView
    private lateinit var chat: LinearLayout
    private lateinit var scroll: ScrollView
    private lateinit var input: EditText
    private lateinit var hint: TextView
    private lateinit var mic: ImageButton
    private lateinit var suggestions: View
    private var startAfterPermission = false
    private var talkAfterPermission = false
    private var thinking = false

    private val dp get() = resources.displayMetrics.density

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)
        NaviService.createChannels(this)

        status = findViewById(R.id.status)
        statusDot = findViewById(R.id.statusDot)
        toggle = findViewById(R.id.toggle)
        chat = findViewById(R.id.chat)
        scroll = findViewById(R.id.scroll)
        input = findViewById(R.id.input)
        hint = findViewById(R.id.setupHint)
        mic = findViewById(R.id.mic)
        suggestions = findViewById(R.id.suggestionsScroll)

        toggle.setOnClickListener { if (NaviService.running) NaviService.stop(this) else startNavi() }
        findViewById<View>(R.id.settings).setOnClickListener { startActivity(Intent(this, SettingsActivity::class.java)) }
        hint.setOnClickListener { startActivity(Intent(this, SettingsActivity::class.java)) }
        findViewById<View>(R.id.send).setOnClickListener { sendTyped(input.text.toString()) }
        input.setOnEditorActionListener { _, actionId, _ ->
            if (actionId == EditorInfo.IME_ACTION_SEND) { sendTyped(input.text.toString()); true } else false
        }
        mic.setOnClickListener { onMic() }
        buildSuggestions()

        if (intent?.getBooleanExtra(EXTRA_AUTOSTART, false) == true) startNavi()
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        if (intent.getBooleanExtra(EXTRA_AUTOSTART, false)) startNavi()
    }

    private fun onMic() {
        when {
            NaviService.talking -> NaviService.stopTalking(this)
            !hasMic() -> { talkAfterPermission = true; requestRuntimePerms() }
            Prefs.geminiKey(this).isEmpty() -> startActivity(Intent(this, SettingsActivity::class.java))
            else -> { Prefs.setEnabled(this, true); NaviService.talk(this) }
        }
    }

    private fun buildSuggestions() {
        val box = findViewById<LinearLayout>(R.id.suggestions)
        for (s in SUGGESTIONS) {
            val chip = TextView(this)
            chip.text = s
            chip.textSize = 14f
            chip.setTextColor(getColor(R.color.navi_text))
            chip.setBackgroundResource(R.drawable.bg_chip)
            chip.setPadding((14 * dp).toInt(), (8 * dp).toInt(), (14 * dp).toInt(), (8 * dp).toInt())
            val lp = LinearLayout.LayoutParams(LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT)
            lp.marginEnd = (8 * dp).toInt()
            chip.layoutParams = lp
            chip.setOnClickListener { sendTyped(s) }
            box.addView(chip)
        }
    }

    private fun sendTyped(raw: String) {
        val text = raw.trim()
        if (text.isEmpty() || thinking) return
        input.setText("")
        NaviCore.add(true, text)
        if (Prefs.geminiKey(this).isEmpty()) {
            NaviCore.add(false, "Preciso da chave da Gemini API para pensar. Toque no aviso lá em cima para configurar (é grátis).")
            return
        }
        thinking = true
        refreshStatus("Pensando...")
        scope.launch {
            val reply = NaviCore.brain(this@MainActivity).handle(text)
            NaviCore.add(false, reply.text)
            thinking = false
            refreshStatus()
        }
    }

    private fun bubble(line: NaviCore.Line): View {
        val box = LinearLayout(this)
        box.orientation = LinearLayout.VERTICAL
        box.setBackgroundResource(if (line.fromUser) R.drawable.bg_bubble_user else R.drawable.bg_bubble_navi)
        val pad = (14 * dp).toInt()
        box.setPadding(pad, (10 * dp).toInt(), pad, (8 * dp).toInt())

        val tv = TextView(this)
        tv.text = line.text
        tv.textSize = 16f
        tv.setTextColor(if (line.fromUser) Color.WHITE else getColor(R.color.navi_text))
        tv.setLineSpacing(2 * dp, 1f)
        tv.setTextIsSelectable(true)
        tv.maxWidth = (resources.displayMetrics.widthPixels * 0.74).toInt()
        box.addView(tv)

        if (line.time.isNotEmpty()) {
            val time = TextView(this)
            time.text = line.time
            time.textSize = 11f
            time.alpha = 0.6f
            time.setTextColor(if (line.fromUser) Color.WHITE else getColor(R.color.navi_text2))
            val tlp = LinearLayout.LayoutParams(LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT)
            tlp.gravity = Gravity.END
            tlp.topMargin = (2 * dp).toInt()
            time.layoutParams = tlp
            box.addView(time)
        }

        val lp = LinearLayout.LayoutParams(LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT)
        lp.gravity = if (line.fromUser) Gravity.END else Gravity.START
        lp.topMargin = (10 * dp).toInt()
        box.layoutParams = lp
        return box
    }

    private fun addBubble(line: NaviCore.Line) {
        chat.addView(bubble(line))
        suggestions.visibility = View.GONE
        scroll.post { scroll.fullScroll(View.FOCUS_DOWN) }
    }

    private fun reloadChat() {
        chat.removeAllViews()
        val lines = NaviCore.history()
        if (lines.isEmpty()) {
            val name = Prefs.userName(this)
            chat.addView(bubble(NaviCore.Line(false,
                "Oi${if (name.isNotEmpty()) ", $name" else ""}! Diga \"Navi\" ou toque no microfone. " +
                    "Controlo a TV e o PC, leio e mando mensagens, vejo seus e-mails e respondo o que você quiser.", "")))
        }
        lines.forEach { chat.addView(bubble(it)) }
        suggestions.visibility = if (lines.isEmpty()) View.VISIBLE else View.GONE
        scroll.post { scroll.fullScroll(View.FOCUS_DOWN) }
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
            if (talkAfterPermission) onMic()
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
        val running = NaviService.running
        val shown = when {
            thinking -> "Pensando..."
            running -> text
            else -> "Desligada — toque em Ligar para ela ouvir \"Navi\""
        }
        status.text = shown
        val color = when {
            !running && !thinking -> getColor(R.color.navi_text2)
            NaviService.talking || thinking -> getColor(R.color.navi_accent)
            shown.startsWith("Erro") || shown.contains("Falta") -> getColor(R.color.navi_warn)
            else -> getColor(R.color.navi_ok)
        }
        statusDot.background = GradientDrawable().apply { shape = GradientDrawable.OVAL; setColor(color) }
        toggle.text = if (running) "Desligar" else "Ligar"
        mic.setBackgroundResource(if (NaviService.talking) R.drawable.bg_mic_stop else R.drawable.bg_mic)
        mic.setImageResource(if (NaviService.talking) R.drawable.ic_stop else R.drawable.ic_mic)
        hint.visibility = if (Prefs.geminiKey(this).isEmpty()) View.VISIBLE else View.GONE
    }
}
