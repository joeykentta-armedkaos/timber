package app.timbre.reader

import android.accessibilityservice.AccessibilityService
import android.animation.ValueAnimator
import android.content.Context
import android.content.SharedPreferences
import android.content.res.Configuration
import android.graphics.Color
import android.graphics.PixelFormat
import android.graphics.Rect
import android.graphics.drawable.GradientDrawable
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
import android.widget.TextView
import android.widget.Toast
import kotlin.math.hypot
import kotlin.math.max
import kotlin.math.min

/**
 * Timbre's floating bubble, drawn as an accessibility overlay so it works on
  * top of every app without a separate "draw over apps" permission.
   *
    * Gestures:
     *  - drag: move the bubble (snaps to the nearest side edge; drag to the
      *    bottom X to hide it)
       *  - press & hold (arming arc → haptic), then release: enters READ MODE.
        *    Tap any text on screen and it is read aloud (hit-testing the
         *    accessibility node under the tap — the same approach Google's
          *    Select to Speak uses; it does NOT depend on text selection, which
           *    most apps never report to accessibility services). Tap the bubble
            *    to exit read mode.
             *  - tap: read the currently selected text if any app reported one, or
              *    stop if already speaking
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
 
     // read mode (Select-to-Speak style: tap a paragraph to hear it)
     private var readMode = false
     private var readOverlay: FrameLayout? = null
 
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
              exitReadMode(silent = true)
              hideBubble()
              engine?.release()
              engine = null
              super.onDestroy()
     }
 
     override fun onInterrupt() {
              engine?.stop()
              bubble?.state = BubbleView.State.IDLE
     }

     override fun onConfigurationChanged(newConfig: Configuration) {
      super.onConfigurationChanged(newConfig)
      // After rotation the saved x/y can land outside the new screen
      // bounds (the window uses FLAG_LAYOUT_NO_LIMITS, so the system
      // will happily park it off-screen). Re-clamp once the new
      // display metrics are in effect.
      main.postDelayed({ clampBubbleToScreen() }, 150)
     }

     private fun clampBubbleToScreen() {
      val wrap = bubbleWrap ?: return
      val params = bubbleParams ?: return
      tucked = false
      wrap.animate().cancel()
      wrap.alpha = 1f
      wrap.scaleX = 1f
      wrap.scaleY = 1f
      val size = if (wrap.width > 0) wrap.width else dp(64f)
      // Snap to the nearest side edge of the NEW orientation.
      val toRight = params.x + size / 2 > screenWidth() / 2
      val targetX = if (toRight) screenWidth() - size - dp(6f) else dp(6f)
      params.x = targetX.coerceAtLeast(0)
      untuckedX = params.x
      params.y = params.y.coerceIn(dp(24f), (screenHeight() - size - dp(24f)).coerceAtLeast(dp(24f)))
      runCatching { wm.updateViewLayout(wrap, params) }
      prefs.edit().putInt("bubbleX", params.x).putInt("bubbleY", params.y).apply()
      scheduleTuck()
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
                      // Clamp the restored position — a location saved in another
      // orientation may be outside the current screen.
      params.x = prefs.getInt("bubbleX", screenWidth() - size - dp(6f))
      .coerceIn(0, (screenWidth() - size).coerceAtLeast(0))
      params.y = prefs.getInt("bubbleY", screenHeight() / 3)
      .coerceIn(dp(24f), (screenHeight() - size - dp(24f)).coerceAtLeast(dp(24f)))
      
              wrap.setOnTouchListener { _, ev -> onBubbleTouch(ev) }
      
              wm.addView(wrap, params)
              bubbleWrap = wrap
              bubble = view
              bubbleParams = params
              scheduleTuck()
     }
 
     private fun hideBubble() {
              main.removeCallbacks(tuckRunnable)
              exitReadMode(silent = true)
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
                                                            // 3/4 of the system long-press timeout: snappier arming while
                            // still scaling with the user's accessibility timing settings.
                            val holdMs = (ViewConfiguration.getLongPressTimeout() * 3L / 4L).coerceAtLeast(250L)
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
                                                                 armed -> enterReadMode()
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
                           // createOneShot is the most universally supported effect; some
                           // devices silently ignore createPredefined().
                           val ms = if (effect == HAPTIC_HEAVY_CLICK) 40L else 20L
                           v.vibrate(VibrationEffect.createOneShot(ms, VibrationEffect.DEFAULT_AMPLITUDE))
              }
     }
 
     /* ------------------------------ read mode ------------------------------ */
 
     private fun enterReadMode() {
              armed = false
              if (readMode) return
              readMode = true
              bubble?.state = BubbleView.State.ARMED
      
              val overlay = FrameLayout(this)
              overlay.setBackgroundColor(Color.parseColor("#0D000000")) // faint scrim
      
              val hint = TextView(this)
              hint.text = "Tap any text to hear it • tap the bubble to exit"
              hint.setTextColor(Color.parseColor("#F4F2EC"))
              hint.textSize = 13f
              hint.setPadding(dp(16f), dp(10f), dp(16f), dp(10f))
              val bg = GradientDrawable()
              bg.cornerRadius = dp(20f).toFloat()
              bg.setColor(Color.parseColor("#E618181D"))
              hint.background = bg
              val hlp = FrameLayout.LayoutParams(
                           FrameLayout.LayoutParams.WRAP_CONTENT,
                           FrameLayout.LayoutParams.WRAP_CONTENT,
                           Gravity.TOP or Gravity.CENTER_HORIZONTAL,
                       )
              hlp.topMargin = dp(56f)
              overlay.addView(hint, hlp)
      
              val params = WindowManager.LayoutParams(
                           WindowManager.LayoutParams.MATCH_PARENT,
                           WindowManager.LayoutParams.MATCH_PARENT,
                           WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY,
                           WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE
                               or WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN,
                           PixelFormat.TRANSLUCENT,
                       )
              var tapX = 0f
              var tapY = 0f
              overlay.setOnTouchListener { _, ev ->
                           when (ev.actionMasked) {
                                            MotionEvent.ACTION_DOWN -> { tapX = ev.rawX; tapY = ev.rawY }
                                            MotionEvent.ACTION_UP -> {
                                                                 val moved = hypot(ev.rawX - tapX, ev.rawY - tapY)
                                                                 if (moved < ViewConfiguration.get(this).scaledTouchSlop * 2) {
                                                                                          onReadModeTap(ev.rawX, ev.rawY)
                                                                 }
                                            }
                           }
                           true
              }
              runCatching { wm.addView(overlay, params) }
              readOverlay = overlay
              haptic(HAPTIC_TICK)
     }
 
     private fun exitReadMode(silent: Boolean = false) {
              if (!readMode) return
              readMode = false
              readOverlay?.let { runCatching { wm.removeView(it) } }
              readOverlay = null
              engine?.stop()
              bubble?.state = BubbleView.State.IDLE
              if (!silent) haptic(HAPTIC_TICK)
              scheduleTuck()
     }
 
     private fun onReadModeTap(x: Float, y: Float) {
              // A tap on (or near) the bubble exits read mode.
              val bp = bubbleParams
              val size = dp(64f)
              val pad = dp(10f)
              if (bp != null &&
                            x >= bp.x - pad && x <= bp.x + size + pad &&
                            y >= bp.y - pad && y <= bp.y + size + pad
                          ) {
                           exitReadMode()
                           return
              }
              val text = findTextAt(x.toInt(), y.toInt())
              if (text.isNullOrBlank()) {
                           haptic(HAPTIC_DOUBLE_CLICK)
                           toast("Timbre: no text found there")
                           return
              }
              speakText(text)
     }
 
     /**
          * Hit-test the accessibility tree for the smallest node under (x, y)
               * that carries text — the same strategy Select to Speak uses. Falls
                    * back to contentDescription when a node has no text.
                         */
     private fun findTextAt(x: Int, y: Int): String? {
              var bestText: String? = null
              var bestArea = Long.MAX_VALUE
              val r = Rect()
      
              fun visit(node: AccessibilityNodeInfo?, depth: Int) {
                           if (node == null || depth > 28) return
                           if (node.packageName?.toString() == packageName) return // skip our own overlay
                           node.getBoundsInScreen(r)
                                       val contains = r.contains(x, y)
                                       if (contains) {
                                            val t = node.text?.toString()?.takeIf { it.isNotBlank() }
                                                ?: node.contentDescription?.toString()?.takeIf { it.isNotBlank() }
                                            if (t != null) {
                                                                 val area = r.width().toLong() * r.height().toLong()
                                                                 if (area in 1 until bestArea) {
                                                                                          bestArea = area
                                                                                          bestText = t
                                                                 }
                                            }
                           }
                                       // Prune: only descend into subtrees that can contain the tap.
                                       // (Nodes with empty bounds are descended anyway — some containers
                                       // report no bounds while their children do.) Each getChild() is a
                                       // cross-process call, so skipping off-point branches is the
                                       // difference between ~30 and ~3000 IPC round-trips on a busy page.
                                       if (contains || r.isEmpty) {
                                        for (i in 0 until node.childCount) visit(node.getChild(i), depth + 1)
                                       }
              }
      
              visit(rootInActiveWindow, 0)
              if (bestText == null) {
                           for (w in windows) {
                                            visit(w.root ?: continue, 0)
                                            if (bestText != null) break
                           }
              }
              return bestText
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
              if (on && (armed || readMode || engine?.isSpeaking == true || dragging)) return
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
              // Normalize: Android reports start > end for right-to-left drags.
              val s = min(node.textSelectionStart, node.textSelectionEnd)
              val e = max(node.textSelectionStart, node.textSelectionEnd)
              if (!text.isNullOrEmpty() && s in 0 until e && e <= text.length) {
                           val sel = text.substring(s, e)
                           if (sel == lastSelection) return
                           lastSelection = sel
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
              // Prefer a live scan (what's selected right now), else a fresh cache
              // (selection made in the last 30s) for apps that dropped the visual
              // selection when focus shifted.
              val scanned = findSelectionInAllWindows()
              val fresh = System.currentTimeMillis() - lastSelectionAt < 30_000
              val text = when {
                           !scanned.isNullOrBlank() -> scanned
                           fresh && lastSelection.isNotBlank() -> lastSelection
                           else -> null
              }
              if (text.isNullOrBlank()) {
                           haptic(HAPTIC_DOUBLE_CLICK) // "nothing found"
                           toast("Timbre: no selected text found")
                           return
              }
              speakText(text)
     }
 
     private fun toast(msg: String) {
              runCatching { Toast.makeText(this, msg, Toast.LENGTH_SHORT).show() }
     }
 
     /** Scan the active window first, then every interactive window. */
     private fun findSelectionInAllWindows(): String? {
              findSelectedText(rootInActiveWindow)?.let { return it }
              for (w in windows) {
                           val root = w.root ?: continue
                           findSelectedText(root)?.let { return it }
              }
              return null
     }
 
     /** Depth-first scan for a node with an active text selection. */
     private fun findSelectedText(root: AccessibilityNodeInfo?, depth: Int = 0): String? {
              if (root == null || depth > 24) return null
              val t = root.text?.toString()
              // Normalize: Android reports start > end for right-to-left drags.
              val s = min(root.textSelectionStart, root.textSelectionEnd)
              val e = max(root.textSelectionStart, root.textSelectionEnd)
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
 
         /** Last voice name applied to the engine — avoids re-enumerating the
          *  installed voices (a slow cross-process query) on every single read. */
          private var appliedVoiceName: String? = null

 private fun speakText(text: String) {
  val e = engine
  if (e == null) {
   toast("Timbre: speech engine not ready")
   return
  }
  e.setRate(prefs.getFloat("rate", 1.0f))
  e.setPitch(prefs.getFloat("pitch", 1.0f))
  val wanted = prefs.getString("voiceName", "") ?: ""
  if (wanted != appliedVoiceName) {
   e.setVoice(e.findVoiceByName(wanted))
   appliedVoiceName = wanted
  }
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
              bubble?.state = if (readMode) BubbleView.State.ARMED else BubbleView.State.IDLE
              if (!readMode) scheduleTuck()
     }
 
     override fun onError(message: String) {
              toast("Timbre: speech error — $message")
              bubble?.state = if (readMode) BubbleView.State.ARMED else BubbleView.State.IDLE
     }
}
