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

    /** Sfondi i kartës si paneli: gradient blu i errët + shkëlqim i butë cyan në cep (si widget-et premium). */
    fun cardBg(w: Int = 800, h: Int = 520): Bitmap = bmp(w, h).also { b ->
        val cv = Canvas(b)
        cv.drawRect(0f, 0f, w.toFloat(), h.toFloat(), paint().apply { shader = LinearGradient(0f, 0f, w * 0.6f, h.toFloat(), 0xFF16324A.toInt(), P.BG, Shader.TileMode.CLAMP) })
        cv.drawCircle(w * 0.92f, h * 0.05f, w * 0.55f, paint().apply { shader = android.graphics.RadialGradient(w * 0.92f, h * 0.05f, w * 0.55f, 0x331FC8E8, 0x001FC8E8, Shader.TileMode.CLAMP) })
        cv.drawCircle(w * 0.05f, h * 1.0f, w * 0.45f, paint().apply { shader = android.graphics.RadialGradient(w * 0.05f, h * 1.0f, w * 0.45f, 0x227BE36A, 0x007BE36A, Shader.TileMode.CLAMP) })
    }

    /** Shirit i hollë me gradient (thekso kartën, si paneli). */
    fun accent(w: Int = 600, h: Int = 8): Bitmap = bmp(w, h).also { b ->
        Canvas(b).drawRoundRect(RectF(0f, 0f, w.toFloat(), h.toFloat()), h / 2f, h / 2f, paint().apply { shader = grad(0f, w.toFloat()) })
    }

    /** Donut me segmente me ngjyra (nivelet e klientëve). */
    fun donut(parts: List<Pair<Double, Int>>, size: Int = 260): Bitmap = bmp(size, size).also { b ->
        val cv = Canvas(b); val s = size * 0.14f; val r = RectF(s, s, size - s, size - s)
        cv.drawArc(r, 0f, 360f, false, paint(P.LINE).apply { style = Paint.Style.STROKE; strokeWidth = s })
        val tot = parts.sumOf { it.first }.takeIf { it > 0 } ?: return@also
        var a = -90f; for ((v, c) in parts) { val sw = (v / tot * 360).toFloat(); if (sw > 0) cv.drawArc(r, a + 1f, maxOf(0.5f, sw - 2f), false, paint(c).apply { style = Paint.Style.STROKE; strokeWidth = s }); a += sw }
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

    private fun text(c: Int, size: Float, mono: Boolean = false, bold: Boolean = false) = paint(c).apply {
        textSize = size; typeface = android.graphics.Typeface.create(if (mono) android.graphics.Typeface.MONOSPACE else android.graphics.Typeface.SANS_SERIF, if (bold) android.graphics.Typeface.BOLD else android.graphics.Typeface.NORMAL) }
    private fun fmt(v: Double, dec: Int = 0): String { val s = java.text.DecimalFormat(if (dec > 0) "#,##0.0" else "#,##0", java.text.DecimalFormatSymbols(java.util.Locale.US)).format(v); return s.replace(",", " ").replace(".", ",") }

    /** Progresi i muajit si te paneli: gjurmë e errët, mbushje gradient, shenjë e bardhë te «pritej deri sot». */
    fun progress(frac: Double, expected: Double?, w: Int = 1000, h: Int = 28): Bitmap = bmp(w, h).also { b ->
        val cv = Canvas(b); val r = h / 2f
        cv.drawRoundRect(RectF(0f, 0f, w.toFloat(), h.toFloat()), r, r, paint(0xFF15303F.toInt()))
        val x = (w * frac.coerceIn(0.0, 1.0)).toFloat(); if (x > 0) cv.drawRoundRect(RectF(0f, 0f, maxOf(x, h.toFloat()), h.toFloat()), r, r, paint().apply { shader = grad(0f, maxOf(x, h.toFloat())) })
        expected?.let { val ex = (w * it.coerceIn(0.0, 1.0)).toFloat(); cv.drawRoundRect(RectF(ex - 3f, 0f, ex + 3f, h.toFloat()), 3f, 3f, paint(0xCCF2F7FA.toInt())) }
    }

    /** «Trendi · kumulativ kundrejt planit»: plani i proratuar (vijë e ndërprerë) + realizimi kumulativ (vijë gradient me sipërfaqe). */
    fun cumulative(points: List<Pair<Int, Double>>, monthDays: Int, base: Double, w: Int = 1000, h: Int = 440): Bitmap = bmp(w, h).also { b ->
        val cv = Canvas(b); val l = 30f; val r = w - 30f; val t = 46f; val btm = h - 46f
        val maxY = maxOf(base, points.lastOrNull()?.second ?: 0.0, 1.0)
        fun X(d: Int) = l + (r - l) * (d - 1).coerceAtLeast(0) / maxOf(1, monthDays - 1)
        fun Y(v: Double) = (btm - (btm - t) * (v / maxY)).toFloat()
        cv.drawLine(l, btm, r, btm, paint(P.LINE).apply { strokeWidth = 3f })
        cv.drawLine(X(1), Y(0.0), X(monthDays), Y(base), paint(P.MUTED).apply { strokeWidth = 5f; style = Paint.Style.STROKE; pathEffect = DashPathEffect(floatArrayOf(18f, 14f), 0f) })
        cv.drawText("plan ${fmt(base)}", r, t - 12f, text(P.MUTED, 30f).apply { textAlign = Paint.Align.RIGHT })
        cv.drawText("1", l, h - 8f, text(P.MUTED, 28f, mono = true)); cv.drawText("$monthDays", r, h - 8f, text(P.MUTED, 28f, mono = true).apply { textAlign = Paint.Align.RIGHT })
        if (points.isEmpty()) return@also
        val path = android.graphics.Path().apply { moveTo(X(1), Y(0.0)); points.forEach { lineTo(X(it.first), Y(it.second)) } }
        val last = points.last(); val area = android.graphics.Path(path).apply { lineTo(X(last.first), btm); lineTo(X(1), btm); close() }
        cv.drawPath(area, paint().apply { shader = LinearGradient(0f, Y(last.second), 0f, btm, 0x553FE0B5, 0x003FE0B5, Shader.TileMode.CLAMP) })
        cv.drawPath(path, paint().apply { style = Paint.Style.STROKE; strokeWidth = 8f; strokeJoin = Paint.Join.ROUND; strokeCap = Paint.Cap.ROUND; shader = grad(X(1), X(last.first)) })
        cv.drawCircle(X(last.first), Y(last.second), 13f, paint(P.LIME))
        val lbl = fmt(last.second, 1); val tp = text(P.INK, 32f, bold = true)
        val tx = (X(last.first) + 22f).coerceAtMost(r - tp.measureText(lbl)); cv.drawText(lbl, tx, Y(last.second) + 11f, tp)
    }

    /** «Ditë pas dite»: kolona gradient me vlerën sipër dhe datën poshtë (si paneli). */
    fun dayBars(labels: List<String>, vals: List<Double>, pace: Double?, w: Int = 1000, h: Int = 460): Bitmap = bmp(w, h).also { b ->
        if (vals.isEmpty()) return@also
        val cv = Canvas(b); val top = 50f; val btm = h - 50f; val n = vals.size; val gap = 18f; val bw = (w - gap * (n - 1)) / n
        val m = maxOf(vals.maxOrNull() ?: 1.0, pace ?: 0.0, 1e-9); val vt = text(P.MUTED, if (n > 8) 22f else 28f, mono = true).apply { textAlign = Paint.Align.CENTER }
        vals.forEachIndexed { i, v -> val x = i * (bw + gap); val y = (btm - (btm - top) * (v / m)).toFloat().coerceAtMost(btm - 8f)
            cv.drawRoundRect(RectF(x, y, x + bw, btm), 16f, 16f, paint().apply { shader = LinearGradient(0f, y, 0f, btm, P.LIME, P.CYAN, Shader.TileMode.CLAMP) })
            cv.drawText(fmt(v, if (v < 1000) 1 else 0), x + bw / 2, y - 12f, vt)
            cv.drawText(labels.getOrElse(i) { "" }, x + bw / 2, h - 10f, vt) }
        pace?.let { val y = (btm - (btm - top) * (it / m)).toFloat(); cv.drawLine(0f, y, w.toFloat(), y, paint(P.YELLOW).apply { strokeWidth = 4f; pathEffect = DashPathEffect(floatArrayOf(16f, 12f), 0f) }) }
    }
}
