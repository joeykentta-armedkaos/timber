package app.timbre.reader

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.view.View

/** The drag-to-dismiss target: graphite disc, dual keyline, cream X. */
class BubbleDismissView(context: Context) : View(context) {

    var dropHighlighted = false
        set(v) { field = v; invalidate() }

    private val paint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val graphite = Color.parseColor("#18181D")
    private val cream = Color.parseColor("#F4F2EC")
    private val red = Color.parseColor("#E06C5F")

    override fun onDraw(canvas: Canvas) {
        val cx = width / 2f
        val cy = height / 2f
        val r = width * 0.40f

        paint.style = Paint.Style.FILL
        paint.color = graphite
        canvas.drawCircle(cx, cy, r, paint)

        paint.style = Paint.Style.STROKE
        paint.strokeWidth = dp(1f)
        paint.color = Color.BLACK
        paint.alpha = 102
        canvas.drawCircle(cx, cy, r + dp(1f), paint)
        paint.color = Color.WHITE
        paint.alpha = 46
        canvas.drawCircle(cx, cy, r, paint)

        paint.style = Paint.Style.STROKE
        paint.strokeWidth = dp(2.4f)
        paint.strokeCap = Paint.Cap.ROUND
        paint.color = if (dropHighlighted) red else cream
        paint.alpha = 255
        val a = r * 0.42f
        canvas.drawLine(cx - a, cy - a, cx + a, cy + a, paint)
        canvas.drawLine(cx - a, cy + a, cx + a, cy - a, paint)
    }

    private fun dp(v: Float) = v * resources.displayMetrics.density
}
