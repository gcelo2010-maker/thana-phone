package al.thana.widgets

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

/** Unaza e Health 0–100. null → unazë bosh me "—". */
@Composable
fun HealthRing(score: Int?, size: Dp = 96.dp, stroke: Dp = 9.dp) {
    Box(Modifier.size(size).semantics { contentDescription = "Shëndeti i biznesit ${score ?: "pa të dhëna"}" },
        contentAlignment = Alignment.Center) {
        Canvas(Modifier.size(size)) {
            val s = stroke.toPx()
            val tl = Offset(s / 2, s / 2); val sz = Size(this.size.width - s, this.size.height - s)
            drawArc(T.Border, 0f, 360f, false, tl, sz, style = Stroke(s))
            if (score != null) drawArc(
                Brush.sweepGradient(listOf(T.Brass, T.Good, T.Brass)), -90f, 360f * score.coerceIn(0, 100) / 100f,
                false, tl, sz, style = Stroke(s, cap = StrokeCap.Round),
            )
        }
        Text(score?.toString() ?: "—", style = T.Display.copy(fontSize = (size.value * 0.36f).sp))
    }
}

/** Grafik i butë (Catmull-Rom) me mbushje gradient. */
@Composable
fun TrendChart(points: List<Point>, modifier: Modifier) {
    Canvas(modifier.semantics { contentDescription = "Shitjet për ${points.size} ditë" }) {
        if (points.size < 2) return@Canvas
        val pad = 6.dp.toPx()
        val max = points.maxOf { it.value }.coerceAtLeast(1.0)
        val xy = points.mapIndexed { i, p ->
            Offset(pad + i * (size.width - 2 * pad) / (points.size - 1),
                size.height - pad - (p.value / max * (size.height - 2 * pad)).toFloat())
        }
        for (k in 1..3) drawLine(T.Border, Offset(0f, size.height * k / 4), Offset(size.width, size.height * k / 4), 1f)
        val line = Path().apply {
            moveTo(xy[0].x, xy[0].y)
            for (i in 0 until xy.size - 1) {
                val p0 = xy[maxOf(i - 1, 0)]; val p1 = xy[i]; val p2 = xy[i + 1]; val p3 = xy[minOf(i + 2, xy.size - 1)]
                cubicTo(p1.x + (p2.x - p0.x) / 6, p1.y + (p2.y - p0.y) / 6, p2.x - (p3.x - p1.x) / 6, p2.y - (p3.y - p1.y) / 6, p2.x, p2.y)
            }
        }
        val fill = Path().apply { addPath(line); lineTo(xy.last().x, size.height); lineTo(xy[0].x, size.height); close() }
        drawPath(fill, Brush.verticalGradient(listOf(T.Brass.copy(alpha = 0.32f), T.Brass.copy(alpha = 0f))))
        drawPath(line, T.Brass, style = Stroke(2.5.dp.toPx(), cap = StrokeCap.Round))
        drawCircle(T.Brass, 4.dp.toPx(), xy.last())
    }
}

/** Shiritat e 14 ditëve (ekrani Shitje). */
@Composable
fun Bars(points: List<Point>, modifier: Modifier) {
    Canvas(modifier.semantics { contentDescription = "Shitjet ditore" }) {
        if (points.isEmpty()) return@Canvas
        val max = points.maxOf { it.value }.coerceAtLeast(1.0)
        val gap = 6.dp.toPx(); val w = (size.width - gap * (points.size - 1)) / points.size
        points.forEachIndexed { i, p ->
            val h = (p.value / max * size.height).toFloat()
            drawRoundRect(
                if (i == points.lastIndex) T.Brass else T.Good.copy(alpha = 0.75f),
                Offset(i * (w + gap), size.height - h), Size(w, h),
                androidx.compose.ui.geometry.CornerRadius(4.dp.toPx()),
            )
        }
    }
}
