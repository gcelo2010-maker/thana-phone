package al.thana.shitje

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.DashPathEffect
import android.graphics.LinearGradient
import android.graphics.Paint
import android.graphics.RectF
import android.graphics.Shader

/** Paleta e panelit THA'NA (board.html :root) + grafikët e widget-eve si bitmap (Glance s'vizaton vetë). */
object P {
    const val BG = 0xFF08131D.toInt(); const val CARD = 0xFF0F2130.toInt(); const val CARD2 = 0xFF132A3B.toInt(); const val LINE = 0xFF1C3647.toInt()
    const val INK = 0xFFF2F7FA.toInt(); const val MUTED = 0xFF8FA6B5.toInt()
    const val CYAN = 0xFF1FC8E8.toInt(); const val MINT = 0xFF3FE0B5.toInt(); const val LIME = 0xFF7BE36A.toInt()
    const val YELLOW = 0xFFF0C36B.toInt(); const val RED = 0xFFEC8A86.toInt(); const val GREY = 0xFF6F8A99.toInt()
    fun light(l: String?) = when (l) { "green" -> MINT; "amber", "yellow" -> YELLOW; "red" -> RED; else -> GREY }
    fun pct(p: Double?) = when { p == null -> GREY; p >= 95 -> MINT; p >= 70 -> YELLOW; else -> RED }
}

object Viz {
    private fun bmp(w: Int, h: Int) = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
    private fun paint(c: Int = P.INK) = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = c }
    private fun grad(x0: Float, x1: Float) = LinearGradient(x0, 0f, x1, 0f, P.CYAN, P.LIME, Shader.TileMode.CLAMP)

    /** Shirit i hollë me gradient (thekso kartën, si paneli). */
    fun accent(w: Int = 600, h: Int = 8): Bitmap = bmp(w, h).also { b ->
        Canvas(b).drawRoundRect(RectF(0f, 0f, w.toFloat(), h.toFloat()), h / 2f, h / 2f, paint().apply { shader = grad(0f, w.toFloat()) })
    }

    /** Unazë: realizimi (gradient) + shenja e pritjes deri sot (e bardhë). */
    fun ring(frac: Double, expected: Double?, size: Int = 260, color: Int? = null): Bitmap = bmp(size, size).also { b ->
        val cv = Canvas(b); val s = size * 0.11f; val r = RectF(s, s, size - s, size - s)
        cv.drawArc(r, 0f, 360f, false, paint(P.LINE).apply { style = Paint.Style.STROKE; strokeWidth = s })
        val p = paint().apply { style = Paint.Style.STROKE; strokeWidth = s; strokeCap = Paint.Cap.ROUND
            if (color != null) this.color = color else shader = android.graphics.SweepGradient(size / 2f, size / 2f, intArrayOf(P.CYAN, P.LIME, P.CYAN), null) }
        val f = frac.coerceIn(0.0, 1.0).toFloat(); if (f > 0) cv.drawArc(r, -90f, 360f * f, false, p)
        expected?.let { e -> val a = Math.toRadians(-90.0 + 360 * e.coerceIn(0.0, 1.0)); val c = size / 2f; val ri = size / 2f - s * 1.7f; val ro = size / 2f - s * 0.3f
            cv.drawLine(c + ri * Math.cos(a).toFloat(), c + ri * Math.sin(a).toFloat(), c + ro * Math.cos(a).toFloat(), c + ro * Math.sin(a).toFloat(),
                paint(P.INK).apply { strokeWidth = s * 0.28f; strokeCap = Paint.Cap.ROUND }) }
    }

    /** Shirita horizontalë (një për rresht): vlera/maksimumi, ngjyrë për rresht, vijë 100% opsionale. */
    fun hbars(vals: List<Double>, colors: List<Int>, max: Double, ref: Double? = null, w: Int = 900, rowH: Int = 34, gap: Int = 26): Bitmap {
        val n = maxOf(1, vals.size); val h = n * rowH + (n - 1) * gap
        return bmp(w, h).also { b -> val cv = Canvas(b); val m = maxOf(max, 1e-9)
            vals.forEachIndexed { i, v -> val y = i * (rowH + gap).toFloat(); val rr = rowH / 2f
                cv.drawRoundRect(RectF(0f, y, w.toFloat(), y + rowH), rr, rr, paint(P.LINE))
                val x = (w * (v / m).coerceIn(0.0, 1.0)).toFloat(); if (x > 0) cv.drawRoundRect(RectF(0f, y, maxOf(x, rowH.toFloat()), y + rowH), rr, rr, paint(colors.getOrElse(i) { P.MINT }))
            }
            ref?.let { r -> val x = (w * (r / m).coerceIn(0.0, 1.0)).toFloat()
                cv.drawLine(x, 0f, x, h.toFloat(), paint(P.INK).apply { strokeWidth = 4f; alpha = 150; pathEffect = DashPathEffect(floatArrayOf(10f, 8f), 0f) }) }
        }
    }

    /** Kolona ditore me gradient + vija e ritmit të nevojshëm. */
    fun columns(vals: List<Double>, pace: Double?, w: Int = 900, h: Int = 260): Bitmap = bmp(w, h).also { b ->
        if (vals.isEmpty()) return@also
        val cv = Canvas(b); val m = maxOf(vals.maxOrNull() ?: 1.0, pace ?: 0.0, 1e-9); val n = vals.size; val gap = 14f; val bw = (w - gap * (n - 1)) / n
        vals.forEachIndexed { i, v -> val x = i * (bw + gap); val top = (h - h * (v / m)).toFloat().coerceAtMost(h - 6f)
            cv.drawRoundRect(RectF(x, top, x + bw, h.toFloat()), 10f, 10f, paint().apply { shader = LinearGradient(0f, top, 0f, h.toFloat(), P.LIME, P.CYAN, Shader.TileMode.CLAMP) }) }
        pace?.let { val y = (h - h * (it / m)).toFloat(); cv.drawLine(0f, y, w.toFloat(), y, paint(P.YELLOW).apply { strokeWidth = 4f; pathEffect = DashPathEffect(floatArrayOf(14f, 10f), 0f) }) }
    }

    /** Shirit i ndarë në segmente (p.sh. statuset e detyrave, detyrimet). */
    fun stacked(parts: List<Pair<Double, Int>>, w: Int = 900, h: Int = 30): Bitmap = bmp(w, h).also { b ->
        val cv = Canvas(b); val tot = parts.sumOf { it.first }.takeIf { it > 0 } ?: return@also
        val r = h / 2f; cv.drawRoundRect(RectF(0f, 0f, w.toFloat(), h.toFloat()), r, r, paint(P.LINE))
        var x = 0f; val path = android.graphics.Path().apply { addRoundRect(RectF(0f, 0f, w.toFloat(), h.toFloat()), r, r, android.graphics.Path.Direction.CW) }
        cv.save(); cv.clipPath(path)
        for ((v, c) in parts) { val ww = (w * v / tot).toFloat(); if (ww > 0) cv.drawRect(x, 0f, x + ww, h.toFloat(), paint(c)); x += ww + 3f }
        cv.restore()
    }

    /** Pikat e rrugës: e kryer (mint), e radhës (gradient, më e madhe), në pritje (kontur). */
    fun dots(statuses: List<String>, nextIdx: Int, w: Int = 900, h: Int = 56): Bitmap = bmp(w, h).also { b ->
        val n = statuses.size; if (n == 0) return@also
        val cv = Canvas(b); val step = w.toFloat() / n; val cy = h / 2f; val r = minOf(step * 0.32f, h * 0.36f)
        cv.drawLine(step / 2, cy, w - step / 2, cy, paint(P.LINE).apply { strokeWidth = 5f })
        statuses.forEachIndexed { i, s -> val cx = step * i + step / 2
            when {
                s == "done" -> cv.drawCircle(cx, cy, r, paint(P.MINT))
                s == "not_done" -> cv.drawCircle(cx, cy, r, paint(P.RED))
                i == nextIdx -> cv.drawCircle(cx, cy, r * 1.2f, paint().apply { shader = grad(cx - r, cx + r) })
                else -> { cv.drawCircle(cx, cy, r, paint(P.CARD)); cv.drawCircle(cx, cy, r, paint(P.GREY).apply { style = Paint.Style.STROKE; strokeWidth = 4f }) }
            } }
    }
}
