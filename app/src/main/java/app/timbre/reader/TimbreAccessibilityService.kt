package app.timbre.reader

import android.accessibilityservice.AccessibilityService
import android.animation.ValueAnimator
import android.content.Context
import android.content.SharedPreferences
import android.graphics.PixelFormat
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.os.VibrationEffect
import android.os.Vibrator
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.ViewConfiguration
import android.view.WindowManager
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo
import android.widget.FrameLayout
import kotlin.math.hypot

/**
 * Timbre's floating bubble, drawn as an accessibility overlay so it works on
 * top of every app without a separate "draw over apps" permission.
 *
 * Gestures:
 *  - drag: move the bubble (snaps to the nearest side edge; drag to the
 *    bottom X to hide it)
 *  - press & hold (arming arc → haptic): while held, any text you select in
 *    the app underneath is read aloud as you select it; release to stop
 *  - tap: read the current selection, or stop if already speaking
 *
 * Selection is captured from TYPE_VIEW_TEXT_SELECTION_CHANGED events, with an
 * on-demand window scan as fallback. Apps that hide content from
 * accessibility won't report selections — the in-app "Read with Timbre"
 * path covers those.
 */
class TimbreAccessibilityService : AccessibilityService(), TtsEngine.Listener {

    companion object {
        // VibrationEffect.EFFECT_* values (API-stable), inlined here so the
        // API-29 fields are never referenced on older devices / by lint.
        private const val HAPTIC_DOUBLE_CLICK = 1 // EFFECT_DOUBLE_CLICK
        private const val HAPTIC_TICK = 2         // EFFECT_TICK
        private const val HAPTIC_HEAVY_CLICK = 5  // EFFECT_HEAVY_CLICK
    }

    private lateinit var wm: WindowManager
    private lateinit var prefs: SharedPreferences
    private var engine: TtsEngine? = null

    private var bubbleWrap: FrameLayout? = null
    private var bubble: BubbleView? = null
    private var bubbleParams: WindowManager.LayoutParams? = null
    private var dismissWrap: FrameLayout? = null
    private var dismissView: BubbleDismissView? = null

    private val main = Handler(Looper.getMainLooper())

    // gesture state
    private var downX = 0f
    private var downY = 0f
    private var startWinX = 0
    private var startWinY = 0
    private var dragging = false
    private var armed = false
    private var overDismiss = false
    private val armRunnable = Runnable { arm() }

    // selection cache
    private var lastSelection: String = ""
    private var lastSelectionAt = 0L
    private val speakSelectionRunnable = Runnable { speakCachedSelection() }

    // idle tuck
    private val tuckRunnable = Runnable { tuck(true) }
    private var tucked = false

    private val prefListener =
        SharedPreferences.OnSharedPreferenceChangeListener { _, key ->
            if (key == "bubbleEnabled") {
                if (prefs.getBoolean("bubbleEnabled", true)) showBubble() else hideBubble()
            }
        }

    /* ------------------------------ lifecycle ------------------------------ */

    override fun onServiceConnected() {
        super.onServiceConnected()
        wm = getSystemService(Context.WINDOW_SERVICE) as WindowManager
        prefs = getSharedPreferences("timbre", Context.MODE_PRIVATE)
        prefs.registerOnSharedPreferenceChangeListener(prefListener)
        engine = TtsEngine(this, this)
        if (prefs.getBoolean("bubbleEnabled", true)) showBubble()
    }

    override fun onDestroy() {
        prefs.unregisterOnSharedPreferenceChangeListener(prefListener)
        main.removeCallbacksAndMessages(null)
        hideBubble()
        engine?.release()
        engine = null
        super.onDestroy()
    }

    override fun onInterrupt() {
        engine?.stop()
        bubble?.state = BubbleView.State.IDLE
    }

    /* ------------------------------ the bubble ------------------------------ */

    private fun dp(v: Float) = (v * resources.displayMetrics.density).toInt()

    private fun showBubble() {
        if (bubbleWrap != null) return
        val size = dp(64f) // 52dp disc + halo padding, ≥48dp touch target
        val wrap = FrameLayout(this)
        val view = BubbleView(this)
        wrap.addView(view, FrameLayout.LayoutParams(size, size))

        val params = WindowManager.LayoutParams(
            size, size,
            WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE
                or WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL
                or WindowManager.LayoutParams.FLAG_SPLIT_TOUCH
                or WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN
                or WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS,
            PixelFormat.TRANSLUCENT,
        )
        params.gravity = Gravity.TOP or Gravity.START
        params.x = prefs.getInt("bubbleX", screenWidth() - size - dp(6f))
        params.y = prefs.getInt("bubbleY", screenHeight() / 3)

        wrap.setOnTouchListener { _, ev -> onBubbleTouch(ev) }

        wm.addView(wrap, params)
        bubbleWrap = wrap
        bubble = view
        bubbleParams = params
        scheduleTuck()
    }

    private fun hideBubble() {
        main.removeCallbacks(tuckRunnable)
        hideDismissTarget()
        bubbleWrap?.let { runCatching { wm.removeView(it) } }
        bubbleWrap = null
        bubble = null
        bubbleParams = null
    }

    private fun screenWidth() = resources.displayMetrics.widthPixels
    private fun screenHeight() = resources.displayMetrics.heightPixels

    private fun onBubbleTouch(ev: MotionEvent): Boolean {
        val params = bubbleParams ?: return false
        val view = bubble ?: return false
        when (ev.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                untuck()
                main.removeCallbacks(tuckRunnable)
                downX = ev.rawX; downY = ev.rawY
                startWinX = params.x; startWinY = params.y
                dragging = false
                overDismiss = false
                val holdMs = ViewConfiguration.getLongPressTimeout().toLong()
                view.startArmingArc(holdMs)
                main.postDelayed(armRunnable, holdMs)
                return true
            }
            MotionEvent.ACTION_MOVE -> {
                val dx = ev.rawX - downX
                val dy = ev.rawY - downY
                val slop = ViewConfiguration.get(this).scaledTouchSlop
                if (!dragging && !armed && hypot(dx, dy) > slop) {
                    dragging = true
                    tucked = false
                    main.removeCallbacks(armRunnable)
                    view.state = if (engine?.isSpeaking == true)
                        BubbleView.State.SPEAKING else BubbleView.State.IDLE
                    showDismissTarget()
                }
                if (dragging) {
                    params.x = startWinX + dx.toInt()
                    params.y = startWinY + dy.toInt()
                    runCatching { wm.updateViewLayout(bubbleWrap, params) }
                    updateDismissHover(ev.rawX, ev.rawY)
                }
                return true
            }
            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                main.removeCallbacks(armRunnable)
                when {
                    dragging -> {
                        if (overDismiss) {
                            prefs.edit().putBoolean("bubbleEnabled", false).apply()
                            // prefListener hides the bubble
                        } else {
                            snapToEdge()
                        }
                        view.state = if (engine?.isSpeaking == true)
                            BubbleView.State.SPEAKING else BubbleView.State.IDLE
                        hideDismissTarget()
                    }
                    armed -> disarm()
                    else -> {
                        // quick tap
                        onTap()
                    }
                }
                dragging = false
                scheduleTuck()
                return true
            }
        }
        return false
    }

    private fun arm() {
        armed = true
        bubble?.state = BubbleView.State.ARMED
        haptic(HAPTIC_HEAVY_CLICK)
        // If the user selected text just before holding, start with that.
        main.removeCallbacks(speakSelectionRunnable)
        main.postDelayed(speakSelectionRunnable, 350)
    }

    private fun disarm() {
        armed = false
        main.removeCallbacks(speakSelectionRunnable)
        engine?.stop()
        bubble?.state = BubbleView.State.IDLE
        haptic(HAPTIC_TICK)
    }

    private fun onTap() {
        val e = engine ?: return
        if (e.isSpeaking) {
            e.stop()
            bubble?.state = BubbleView.State.IDLE
        } else {
            bubble?.state = BubbleView.State.IDLE
            speakCurrentSelection()
        }
    }

    private fun haptic(effect: Int) {
        runCatching {
            @Suppress("DEPRECATION")
            val v = getSystemService(Context.VIBRATOR_SERVICE) as Vibrator
            if (Build.VERSION.SDK_INT >= 29) {
                v.vibrate(VibrationEffect.createPredefined(effect))
            } else {
                v.vibrate(VibrationEffect.createOneShot(20, VibrationEffect.DEFAULT_AMPLITUDE))
            }
        }
    }

    /* --------------------------- tuck + dismiss --------------------------- */

    private fun scheduleTuck() {
        main.removeCallbacks(tuckRunnable)
        main.postDelayed(tuckRunnable, 3000)
    }

    private var untuckedX = 0

    private fun tuck(on: Boolean) {
        val wrap = bubbleWrap ?: return
        val params = bubbleParams ?: return
        if (on && (armed || engine?.isSpeaking == true || dragging)) return
        if (tucked == on) return
        tucked = on
        val onRight = params.x + wrap.width / 2 > screenWidth() / 2
        if (on) untuckedX = params.x
        val targetX = when {
            !on -> untuckedX
            onRight -> untuckedX + dp(26f)
            else -> untuckedX - dp(26f)
        }
        animateWindowX(params.x, targetX)
        wrap.animate().alpha(if (on) 0.55f else 1f).setDuration(250).start()
    }

    private fun untuck() {
        if (tucked) tuck(false) else bubbleWrap?.animate()?.alpha(1f)?.setDuration(150)?.start()
    }

    private fun animateWindowX(from: Int, to: Int) {
        val params = bubbleParams ?: return
        val wrap = bubbleWrap ?: return
        ValueAnimator.ofInt(from, to).apply {
            duration = 250
            addUpdateListener {
                params.x = it.animatedValue as Int
                runCatching { wm.updateViewLayout(wrap, params) }
            }
            start()
        }
    }

    private fun snapToEdge() {
        val params = bubbleParams ?: return
        val wrap = bubbleWrap ?: return
        val toRight = params.x + wrap.width / 2 > screenWidth() / 2
        val targetX = if (toRight) screenWidth() - wrap.width - dp(6f) else dp(6f)
        val anim = ValueAnimator.ofInt(params.x, targetX)
        anim.duration = 180
        anim.addUpdateListener {
            params.x = it.animatedValue as Int
            runCatching { wm.updateViewLayout(wrap, params) }
        }
        anim.start()
        untuckedX = targetX
        params.y = params.y.coerceIn(dp(24f), screenHeight() - wrap.height - dp(24f))
        prefs.edit().putInt("bubbleX", targetX).putInt("bubbleY", params.y).apply()
    }

    private fun showDismissTarget() {
        if (dismissWrap != null) return
        val size = dp(64f)
        val wrap = FrameLayout(this)
        val v = BubbleDismissView(this)
        wrap.addView(v, FrameLayout.LayoutParams(size, size))
        val params = WindowManager.LayoutParams(
            size, size,
            WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE
                or WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL
                or WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN,
            PixelFormat.TRANSLUCENT,
        )
        params.gravity = Gravity.TOP or Gravity.START
        params.x = (screenWidth() - size) / 2
        params.y = screenHeight() - size - dp(96f)
        wrap.alpha = 0f
        wm.addView(wrap, params)
        wrap.animate().alpha(1f).setDuration(150).start()
        dismissWrap = wrap
        dismissView = v
    }

    private fun updateDismissHover(rawX: Float, rawY: Float) {
        val wrap = dismissWrap ?: return
        val cx = (screenWidth()) / 2f
        val cy = screenHeight() - dp(96f) - dp(32f).toFloat()
        val near = hypot(rawX - cx, rawY - cy) < dp(100f)
        if (near != overDismiss) {
            overDismiss = near
            dismissView?.dropHighlighted = near
            wrap.animate().scaleX(if (near) 1.15f else 1f)
                .scaleY(if (near) 1.15f else 1f).setDuration(120).start()
            if (near) haptic(HAPTIC_TICK)
        }
    }

    private fun hideDismissTarget() {
        dismissWrap?.let { w ->
            w.animate().alpha(0f).setDuration(120).withEndAction {
                runCatching { wm.removeView(w) }
            }.start()
        }
        dismissWrap = null
        dismissView = null
        overDismiss = false
    }

    /* --------------------------- selection capture --------------------------- */

    override fun onAccessibilityEvent(event: AccessibilityEvent) {
        if (event.eventType != AccessibilityEvent.TYPE_VIEW_TEXT_SELECTION_CHANGED) return
        val node = event.source ?: return
        val text = node.text?.toString()
        val s = node.textSelectionStart
        val e = node.textSelectionEnd
        if (!text.isNullOrEmpty() && s in 0 until e && e <= text.length) {
            lastSelection = text.substring(s, e)
            lastSelectionAt = System.currentTimeMillis()
            if (armed) {
                // live re-speak as the selection grows, debounced
                main.removeCallbacks(speakSelectionRunnable)
                main.postDelayed(speakSelectionRunnable, 450)
            }
        }
    }

    private fun speakCachedSelection() {
        if (lastSelection.isBlank()) return
        if (System.currentTimeMillis() - lastSelectionAt > 30_000) return
        speakText(lastSelection)
    }

    private fun speakCurrentSelection() {
        // Prefer a fresh cache (selection made in the last 30s), else scan.
        val fresh = System.currentTimeMillis() - lastSelectionAt < 30_000
        val text = if (fresh && lastSelection.isNotBlank()) lastSelection
        else findSelectedText(rootInActiveWindow)
        if (text.isNullOrBlank()) {
            haptic(HAPTIC_DOUBLE_CLICK) // "nothing found"
            return
        }
        speakText(text)
    }

    /** Depth-first scan for a node with an active text selection. */
    private fun findSelectedText(root: AccessibilityNodeInfo?, depth: Int = 0): String? {
        if (root == null || depth > 24) return null
        val t = root.text?.toString()
        val s = root.textSelectionStart
        val e = root.textSelectionEnd
        if (!t.isNullOrEmpty() && s in 0 until e && e <= t.length) {
            val sel = t.substring(s, e)
            if (sel.isNotBlank()) return sel
        }
        for (i in 0 until root.childCount) {
            val found = findSelectedText(root.getChild(i), depth + 1)
            if (found != null) return found
        }
        return null
    }

    private fun speakText(text: String) {
        val e = engine ?: return
        e.setRate(prefs.getFloat("rate", 1.0f))
        e.setPitch(prefs.getFloat("pitch", 1.0f))
        e.setVoice(e.findVoiceByName(prefs.getString("voiceName", "")))
        e.setText(text)
        e.playFrom(0)
    }

    /* --------------------------- TtsEngine.Listener --------------------------- */

    override fun onReady() {}

    override fun onSentenceStart(sentenceIndex: Int, start: Int, end: Int) {
        bubble?.state = BubbleView.State.SPEAKING
    }

    override fun onWord(start: Int, end: Int) {}

    override fun onFinished() {
        bubble?.state = if (armed) BubbleView.State.ARMED else BubbleView.State.IDLE
        if (!armed) scheduleTuck()
    }

    override fun onError(message: String) {
        bubble?.state = if (armed) BubbleView.State.ARMED else BubbleView.State.IDLE
    }
}
