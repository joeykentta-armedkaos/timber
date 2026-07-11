package app.timbre.reader

import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.graphics.Color
import android.os.Bundle
import android.text.Spannable
import android.text.SpannableString
import android.text.style.BackgroundColorSpan
import android.view.View
import android.widget.ImageButton
import android.widget.ScrollView
import android.widget.SeekBar
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import androidx.core.view.ViewCompat
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import android.provider.Settings
import androidx.core.view.updatePadding
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.google.android.material.materialswitch.MaterialSwitch
import com.google.android.material.slider.Slider

class MainActivity : AppCompatActivity(), TtsEngine.Listener {

    private lateinit var engine: TtsEngine
    private lateinit var prefs: android.content.SharedPreferences

    private lateinit var readerScroll: ScrollView
    private lateinit var readerText: TextView
    private lateinit var emptyState: View
    private lateinit var pasteBig: View
    private lateinit var pasteBtn: ImageButton
    private lateinit var voiceChip: View
    private lateinit var voiceChipLabel: TextView
    private lateinit var speedChip: View
    private lateinit var speedChipLabel: TextView
    private lateinit var backBtn: ImageButton
    private lateinit var playBtn: ImageButton
    private lateinit var stopBtn: ImageButton
    private lateinit var progress: SeekBar
    private lateinit var progressPct: TextView
    private lateinit var bubbleSwitch: MaterialSwitch
    private lateinit var bubbleSub: TextView

    private var text: String = ""
    private var engineReady = false
    private var pendingAutoPlay = false

    private val sentenceTint = Color.argb(18, 0xE8, 0xA3, 0x3D)   // 7% amber
    private val wordTint = Color.argb(71, 0xE8, 0xA3, 0x3D)       // 28% amber

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        WindowCompat.setDecorFitsSystemWindows(window, false)
        setContentView(R.layout.activity_main)
        prefs = getSharedPreferences("timbre", Context.MODE_PRIVATE)

        readerScroll = findViewById(R.id.readerScroll)
        readerText = findViewById(R.id.readerText)
        emptyState = findViewById(R.id.emptyState)
        pasteBig = findViewById(R.id.pasteBig)
        pasteBtn = findViewById(R.id.pasteBtn)
        voiceChip = findViewById(R.id.voiceChip)
        voiceChipLabel = findViewById(R.id.voiceChipLabel)
        speedChip = findViewById(R.id.speedChip)
        speedChipLabel = findViewById(R.id.speedChipLabel)
        backBtn = findViewById(R.id.backBtn)
        playBtn = findViewById(R.id.playBtn)
        stopBtn = findViewById(R.id.stopBtn)
        progress = findViewById(R.id.progress)
        progressPct = findViewById(R.id.progressPct)
        bubbleSwitch = findViewById(R.id.bubbleSwitch)
        bubbleSub = findViewById(R.id.bubbleSub)

        applyInsets()

        engine = TtsEngine(this, this)

        pasteBtn.setOnClickListener { pasteFromClipboard() }
        pasteBig.setOnClickListener { pasteFromClipboard() }
        playBtn.setOnClickListener { togglePlay() }
        stopBtn.setOnClickListener { engine.stop(); onStopped() }
        backBtn.setOnClickListener { if (text.isNotBlank()) engine.previousSentence() }
        voiceChip.setOnClickListener { showVoiceDialog() }
        speedChip.setOnClickListener { showSpeedDialog() }

        bubbleSwitch.setOnCheckedChangeListener { _, checked ->
            // suppressSwitch guards programmatic changes (isPressed is unreliable
            // for TalkBack/keyboard toggles and re-entrant setChecked calls).
            if (suppressSwitch) return@setOnCheckedChangeListener
            if (checked && !isBubbleServiceEnabled()) {
                // Can't enable programmatically — send the user to settings.
                // Leave the pref ON so the bubble appears once they enable it.
                suppressSwitch = true
                bubbleSwitch.isChecked = false
                suppressSwitch = false
                prefs.edit().putBoolean("bubbleEnabled", true).apply()
                startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS))
                toast(getString(R.string.bubble_sub_off))
            } else {
                prefs.edit().putBoolean("bubbleEnabled", checked).apply()
                refreshBubbleRow()
            }
        }

        progress.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(sb: SeekBar?, value: Int, fromUser: Boolean) {
                if (fromUser) updatePct(value)
            }
            override fun onStartTrackingTouch(sb: SeekBar?) {}
            override fun onStopTrackingTouch(sb: SeekBar?) {
                if (text.isBlank() || !engineReady) return
                applySavedVoiceAndRate()
                val target = engine.sentenceAtChar(progress.progress)
                engine.playFrom(target)
                setPlayIcon(playing = true)
            }
        })

        updateSpeedChip()
        if (savedInstanceState != null) {
            // Recreated (rotation, fold, etc.) — restore text + position, stay paused.
            val saved = savedInstanceState.getString("text", "") ?: ""
            if (saved.isNotBlank()) {
                setText(saved)
                engine.restorePosition(savedInstanceState.getInt("sentence", 0))
            }
        } else {
            handleIntent(intent)
        }
        renderText()
    }

    override fun onSaveInstanceState(outState: Bundle) {
        super.onSaveInstanceState(outState)
        outState.putString("text", text)
        outState.putInt("sentence", engine.currentSentence)
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        handleIntent(intent)
    }

    override fun onResume() {
        super.onResume()
        refreshBubbleRow()
    }

    /* --------------------------- floating bubble --------------------------- */

    private var suppressSwitch = false

    private fun isBubbleServiceEnabled(): Boolean {
        val flat = Settings.Secure.getString(
            contentResolver, Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES
        ) ?: return false
        return flat.split(':').any {
            it.equals("$packageName/.TimbreAccessibilityService", ignoreCase = true) ||
                it.equals("$packageName/${TimbreAccessibilityService::class.java.name}", ignoreCase = true)
        }
    }

    private fun refreshBubbleRow() {
        val serviceOn = isBubbleServiceEnabled()
        val bubbleOn = serviceOn && prefs.getBoolean("bubbleEnabled", true)
        suppressSwitch = true
        bubbleSwitch.isChecked = bubbleOn
        suppressSwitch = false
        bubbleSub.text = when {
            bubbleOn -> getString(R.string.bubble_sub_on)
            serviceOn -> getString(R.string.bubble_hidden_hint)
            else -> getString(R.string.bubble_sub_off)
        }
    }

    override fun onDestroy() {
        engine.release()
        super.onDestroy()
    }

    /* ------------------------------ intents ------------------------------ */

    private fun handleIntent(intent: Intent?) {
        val incoming: CharSequence? = when (intent?.action) {
            Intent.ACTION_PROCESS_TEXT ->
                intent.getCharSequenceExtra(Intent.EXTRA_PROCESS_TEXT)
            Intent.ACTION_SEND ->
                intent.getCharSequenceExtra(Intent.EXTRA_TEXT)
            else -> null
        }
        if (!incoming.isNullOrBlank()) {
            setText(incoming.toString())
            // Auto-play: this is the "select → read" path.
            if (engineReady) startPlayback() else pendingAutoPlay = true
        }
    }

    /* ------------------------------ text state ------------------------------ */

    private fun setText(t: String) {
        text = t.trim()
        engine.setText(text)
        progress.max = maxOf(1, text.length)
        progress.progress = 0
        updatePct(0)
        renderText()
    }

    private fun renderText(sentence: IntRange? = null, word: IntRange? = null) {
        val empty = text.isBlank()
        emptyState.visibility = if (empty) View.VISIBLE else View.GONE
        readerScroll.visibility = if (empty) View.GONE else View.VISIBLE
        if (empty) return

        // Ranges are exclusive-end: built with `start until end`.
        val span = SpannableString(text)
        sentence?.let {
            if (it.first < it.last + 1 && it.last < text.length) span.setSpan(
                BackgroundColorSpan(sentenceTint), it.first, it.last + 1,
                Spannable.SPAN_EXCLUSIVE_EXCLUSIVE
            )
        }
        word?.let {
            if (it.first < it.last + 1 && it.last < text.length) span.setSpan(
                BackgroundColorSpan(wordTint), it.first, it.last + 1,
                Spannable.SPAN_EXCLUSIVE_EXCLUSIVE
            )
        }
        readerText.text = span
        // post: with wrap_content width (tablet layout) the TextView's layout is
        // null right after setText; scroll after the pending layout pass.
        word?.let { w -> readerText.post { scrollToOffset(w.first) } }
    }

    private fun scrollToOffset(offset: Int) {
        val layout = readerText.layout ?: return
        val line = layout.getLineForOffset(offset.coerceIn(0, readerText.text.length))
        val y = layout.getLineTop(line)
        val viewportH = readerScroll.height
        val target = (y - viewportH / 3).coerceAtLeast(0)
        if (kotlin.math.abs(readerScroll.scrollY - target) > viewportH / 4) {
            readerScroll.smoothScrollTo(0, target)
        }
    }

    private fun pasteFromClipboard() {
        val cm = getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
        val clip = cm.primaryClip?.getItemAt(0)?.coerceToText(this)
        if (clip.isNullOrBlank()) {
            toast(getString(R.string.clipboard_empty))
            return
        }
        engine.stop()
        setPlayIcon(playing = false)
        setText(clip.toString())
    }

    /* ------------------------------ playback ------------------------------ */

    private fun togglePlay() {
        if (text.isBlank()) { pasteFromClipboard(); return }
        when {
            engine.isSpeaking -> { engine.pause(); setPlayIcon(playing = false) }
            engine.isPaused -> {
                if (!engineReady) return
                applySavedVoiceAndRate()
                engine.resume()
                setPlayIcon(playing = true)
            }
            else -> startPlayback()
        }
    }

    private fun startPlayback() {
        if (text.isBlank() || !engineReady) return
        applySavedVoiceAndRate()
        engine.playFrom(0)
        setPlayIcon(playing = true)
    }

    private fun onStopped() {
        setPlayIcon(playing = false)
        activeSentence = null
        progress.progress = 0
        updatePct(0)
        renderText()
    }

    private fun setPlayIcon(playing: Boolean) {
        playBtn.setImageResource(if (playing) R.drawable.ic_pause else R.drawable.ic_play)
        playBtn.contentDescription =
            getString(if (playing) R.string.cd_pause else R.string.cd_play)
    }

    private fun updatePct(chars: Int) {
        val pct = if (text.isEmpty()) 0 else (chars * 100 / maxOf(1, text.length))
        progressPct.text = getString(R.string.percent, pct)
    }

    /* --------------------------- engine callbacks --------------------------- */

    override fun onReady() {
        runOnUiThread {
            engineReady = true
            applySavedVoiceAndRate()
            updateVoiceChip()
            if (pendingAutoPlay) { pendingAutoPlay = false; startPlayback() }
        }
    }

    private var activeSentence: IntRange? = null

    override fun onSentenceStart(sentenceIndex: Int, start: Int, end: Int) {
        runOnUiThread {
            activeSentence = start until end
            renderText(sentence = activeSentence)
            progress.progress = start
            updatePct(start)
        }
    }

    override fun onWord(start: Int, end: Int) {
        runOnUiThread {
            renderText(sentence = activeSentence, word = start until end)
            progress.progress = end
            updatePct(end)
        }
    }

    override fun onFinished() {
        runOnUiThread { onStopped() }
    }

    override fun onError(message: String) {
        runOnUiThread {
            toast(message)
            setPlayIcon(playing = false)
        }
    }

    /* ------------------------------ dialogs ------------------------------ */

    private fun showVoiceDialog() {
        if (!engineReady) return
        val voices = engine.availableVoices()
        val labels = voices.map { it.label }.toTypedArray()
        val savedName = prefs.getString("voiceName", "") ?: ""
        val checked = voices.indexOfFirst { it.voice?.name == savedName }
            .let { if (it < 0) 0 else it }
        MaterialAlertDialogBuilder(this)
            .setTitle(R.string.voice)
            .setSingleChoiceItems(labels, checked) { dialog, which ->
                val v = voices[which]
                prefs.edit().putString("voiceName", v.voice?.name ?: "").apply()
                engine.setVoice(v.voice)
                updateVoiceChip()
                if (engine.isSpeaking) engine.playFrom(engine.currentSentence)
                dialog.dismiss()
            }
            .setNegativeButton(android.R.string.cancel, null)
            .show()
    }

    private fun showSpeedDialog() {
        val view = layoutInflater.inflate(R.layout.dialog_speed, null)
        val speed = view.findViewById<Slider>(R.id.speedSlider)
        val pitch = view.findViewById<Slider>(R.id.pitchSlider)
        speed.value = prefs.getFloat("rate", 1.0f).coerceIn(0.5f, 3.0f)
        pitch.value = prefs.getFloat("pitch", 1.0f).coerceIn(0.5f, 2.0f)
        MaterialAlertDialogBuilder(this)
            .setTitle(R.string.speed_and_pitch)
            .setView(view)
            .setPositiveButton(android.R.string.ok) { _, _ ->
                prefs.edit()
                    .putFloat("rate", speed.value)
                    .putFloat("pitch", pitch.value)
                    .apply()
                applySavedVoiceAndRate()
                updateSpeedChip()
                if (engine.isSpeaking) engine.playFrom(engine.currentSentence)
            }
            .setNegativeButton(android.R.string.cancel, null)
            .show()
    }

    private fun applySavedVoiceAndRate() {
        engine.setRate(prefs.getFloat("rate", 1.0f))
        engine.setPitch(prefs.getFloat("pitch", 1.0f))
        engine.setVoice(engine.findVoiceByName(prefs.getString("voiceName", "")))
    }

    private fun updateVoiceChip() {
        val savedName = prefs.getString("voiceName", "") ?: ""
        val voices = engine.availableVoices()
        val match = voices.firstOrNull { it.voice?.name == savedName }
        voiceChipLabel.text = match?.label ?: getString(R.string.system_default)
    }

    private fun updateSpeedChip() {
        val rate = prefs.getFloat("rate", 1.0f)
        speedChipLabel.text = getString(R.string.speed_value, rate)
    }

    /* ------------------------------ misc ------------------------------ */

    private fun applyInsets() {
        val root = findViewById<View>(R.id.root)
        ViewCompat.setOnApplyWindowInsetsListener(root) { v, insets ->
            val bars = insets.getInsets(WindowInsetsCompat.Type.systemBars())
            v.updatePadding(top = bars.top, bottom = bars.bottom)
            insets
        }
    }

    private fun toast(msg: String) =
        android.widget.Toast.makeText(this, msg, android.widget.Toast.LENGTH_SHORT).show()
}
