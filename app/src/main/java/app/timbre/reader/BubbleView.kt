package app.timbre.reader

import android.animation.ValueAnimator
import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.RectF
import android.view.View
import android.view.animation.LinearInterpolator
import android.view.animation.PathInterpolator

/**
 * The floating Timbre bubble. Draws everything itself (disc, dual keyline,
 * waveform bars, arming arc, breathing halo) so state changes are cheap.
 *
 * States: IDLE (dark, still) → ARMING (amber rim arc sweeps while the hold
 * timer runs) → ARMED (dark, breathing amber halo) → SPEAKING (amber disc,
 * bars dancing at unequal rates). Each state is readable without color
 * vision: still / sweep / breathe / dance.
 */
class BubbleView(context: Context) : View(context) {

    enum class State { IDLE, ARMING, ARMED, SPEAKING }

    var state: State = State.IDLE
        set(value) {
            if (field == value) return
            field = value
            restartAnimators()
            invalidate()
        }

    private val amber = Color.parseColor("#E8A33D")
    private val graphite = Color.parseColor("#18181D")
    private val cream = Color.parseColor("#F4F2EC")
    private val onAmber = Color.parseColor("#17130A")

    private val paint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val rect = RectF()

    /** 0..1 sweep of the arming arc. */
    var arcProgress = 0f
        set(v) { field = v; invalidate() }

    private var haloT = 0f      // 0..1 breathing phase
    private var barPhases = floatArrayOf(0f, 0f, 0f, 0f)

    private var haloAnim: ValueAnimator? = null
    private val barAnims = mutableListOf<ValueAnimator>()

    // Bar geometry in a 24-unit viewport (matches the brand mark).
    private data class Bar(val cx: Float, val h: Float, val outer: Boolean)
    private val bars = listOf(
        Bar(4.3f, 5.6f, true),
        Bar(9.4f, 12.8f, false),
        Bar(14.5f, 18.4f, false),
        Bar(19.6f, 9.2f, true),
    )

    private fun restartAnimators() {
        haloAnim?.cancel(); haloAnim = null
        barAnims.forEach { it.cancel() }; barAnims.clear()

        if (state == State.ARMED) {
            haloAnim = ValueAnimator.ofFloat(0f, 1f).apply {
                duration = 1100
                interpolator = PathInterpolator(0.4f, 0f, 0.2f, 1f)
                repeatMode = ValueAnimator.REVERSE
                repeatCount = ValueAnimator.INFINITE
                addUpdateListener { haloT = it.animatedValue as Float; invalidate() }
                start()
            }
        }
        if (state == State.SPEAKING) {
            val durations = longArrayOf(420, 520, 610, 470)
            for (i in 0..3) {
                barAnims += ValueAnimator.ofFloat(0.45f, 1f).apply {
                    duration = durations[i]
                    interpolator = PathInterpolator(0.4f, 0f, 0.2f, 1f)
                    repeatMode = ValueAnimator.REVERSE
                    repeatCount = ValueAnimator.INFINITE
                    startDelay = (i * 90).toLong()
                    addUpdateListener { v ->
                        barPhases[i] = v.animatedValue as Float
                        invalidate()
                    }
                    start()
                }
            }
        }
        if (state != State.ARMING) arcProgress = 0f
    }

    override fun onDetachedFromWindow() {
        haloAnim?.cancel()
        barAnims.forEach { it.cancel() }
        super.onDetachedFromWindow()
    }

    override fun onDraw(canvas: Canvas) {
        val w = width.toFloat()
        val cx = w / 2f
        val cy = height / 2f
        val discR = w * 0.40f   // disc radius; leaves room for halo in the view

        // breathing halo (armed) — sized to stay inside the view bounds
        if (state == State.ARMED) {
            val haloR = discR + dp(2f) + dp(2f) * haloT
            paint.style = Paint.Style.STROKE
            paint.strokeWidth = dp(4f)
            paint.color = amber
            paint.alpha = (0.35f * 255 * (1f - 0.35f * haloT)).toInt()
            canvas.drawCircle(cx, cy, haloR, paint)
        }

        // disc
        paint.style = Paint.Style.FILL
        paint.color = if (state == State.SPEAKING) amber else graphite
        paint.alpha = 255
        canvas.drawCircle(cx, cy, discR, paint)

        // dual keyline: outer dark ring then light ring (survives any wallpaper)
        paint.style = Paint.Style.STROKE
        paint.strokeWidth = dp(1f)
        paint.color = Color.BLACK
        paint.alpha = 102 // 40%
        canvas.drawCircle(cx, cy, discR + dp(1f), paint)
        paint.color = Color.WHITE
        paint.alpha = if (state == State.SPEAKING) 64 else 46 // 25% / 18%
        canvas.drawCircle(cx, cy, discR, paint)

        // arming arc: 2dp amber sweep from 12 o'clock
        if (state == State.ARMING && arcProgress > 0f) {
            paint.style = Paint.Style.STROKE
            paint.strokeWidth = dp(2f)
            paint.strokeCap = Paint.Cap.ROUND
            paint.color = amber
            paint.alpha = 255
            val r = discR - dp(1f)
            rect.set(cx - r, cy - r, cx + r, cy + r)
            canvas.drawArc(rect, -90f, 360f * arcProgress, false, paint)
        }

        // waveform bars in a 24-unit space scaled to ~42% of the disc
        val scale = (discR * 1.05f) / 12f
        paint.style = Paint.Style.FILL
        for ((i, bar) in bars.withIndex()) {
            val animScale = if (state == State.SPEAKING) barPhases[i].coerceAtLeast(0.45f) else 1f
            val h = bar.h * scale * animScale
            val bw = 2.6f * scale
            val x = cx + (bar.cx - 12f) * scale
            paint.color = when {
                state == State.SPEAKING -> onAmber
                bar.outer -> amber
                else -> cream
            }
            rect.set(x - bw / 2f, cy - h / 2f, x + bw / 2f, cy + h / 2f)
            canvas.drawRoundRect(rect, bw / 2f, bw / 2f, paint)
        }
    }

    /** Runs the arming arc over the hold-timeout window. */
    fun startArmingArc(durationMs: Long) {
        state = State.ARMING
        ValueAnimator.ofFloat(0f, 1f).apply {
            duration = durationMs
            interpolator = LinearInterpolator()
            addUpdateListener {
                if (state == State.ARMING) arcProgress = it.animatedValue as Float
            }
            start()
        }
    }

    private fun dp(v: Float) = v * resources.displayMetrics.density
}
