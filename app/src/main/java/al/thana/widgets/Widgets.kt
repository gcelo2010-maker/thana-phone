package al.thana.widgets

import android.appwidget.AppWidgetManager
import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.LinearGradient
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RectF
import android.graphics.Shader
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.glance.GlanceId
import androidx.glance.GlanceModifier
import androidx.glance.Image
import androidx.glance.ImageProvider
import androidx.glance.action.Action
import androidx.glance.action.clickable
import androidx.glance.appwidget.GlanceAppWidget
import androidx.glance.appwidget.GlanceAppWidgetReceiver
import androidx.glance.appwidget.action.actionRunCallback
import androidx.glance.appwidget.action.actionStartActivity
import androidx.glance.appwidget.cornerRadius
import androidx.glance.appwidget.provideContent
import androidx.glance.background
import androidx.glance.layout.Alignment
import androidx.glance.layout.Column
import androidx.glance.layout.ColumnScope
import androidx.glance.layout.Row
import androidx.glance.layout.Spacer
import androidx.glance.layout.fillMaxSize
import androidx.glance.layout.fillMaxWidth
import androidx.glance.layout.height
import androidx.glance.layout.padding
import androidx.glance.layout.size
import androidx.glance.layout.width
import androidx.glance.text.FontFamily
import androidx.glance.text.FontWeight
import androidx.glance.text.Text
import androidx.glance.text.TextStyle
import androidx.glance.unit.ColorProvider

private fun c(x: Color) = ColorProvider(x)

private fun openApp(ctx: Context, tab: String? = null): Action =
    actionStartActivity(Intent(ctx, MainActivity::class.java).apply {
        flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP
        tab?.let { putExtra("tab", it) }
    })

private fun hhmm(iso: String?): String = iso?.let { runCatching {
    java.time.OffsetDateTime.parse(it).atZoneSameInstant(java.time.ZoneId.systemDefault()).toLocalTime().toString().take(5)
}.getOrNull() } ?: "—"

/** Kartela bazë: kur s'ka sesion → "Hyr në THA'NA"; kur s'ka snapshot → "—", jo 0. */
@Composable
private fun Card(ctx: Context, title: String, snap: Snapshot?, logged: Boolean, tab: String? = null,
                 body: @Composable ColumnScope.(Snapshot) -> Unit) {
    val click = when { !logged -> openApp(ctx); tab != null -> openApp(ctx, tab); else -> actionRunCallback<RefreshAction>() }
    Column(GlanceModifier.fillMaxSize().background(T.Surface).cornerRadius(24.dp).padding(14.dp).clickable(click)) {
        Text(title, style = TextStyle(color = c(T.Muted), fontSize = 11.sp, fontWeight = FontWeight.Bold))
        Spacer(GlanceModifier.height(6.dp))
        when {
            !logged -> {
                Text("Hyr në THA'NA", style = TextStyle(color = c(T.Brass), fontSize = 16.sp, fontWeight = FontWeight.Bold))
                Text("Prek për të hyrë me PIN", style = TextStyle(color = c(T.Muted), fontSize = 11.sp))
            }
            snap == null -> {
                Text("—", style = TextStyle(color = c(T.Ink), fontSize = 28.sp, fontFamily = FontFamily.Serif))
                Text("Në pritje të sinkronizimit", style = TextStyle(color = c(T.Muted), fontSize = 11.sp))
            }
            else -> body(snap)
        }
        Spacer(GlanceModifier.defaultWeight())
        if (logged && snap != null) Text("⟳ ${hhmm(snap.generatedAt)}", style = TextStyle(color = c(T.Muted), fontSize = 10.sp))
    }
}

@Composable private fun Big(v: String, col: Color = T.Ink) =
    Text(v, style = TextStyle(color = c(col), fontSize = 28.sp, fontFamily = FontFamily.Serif))

@Composable private fun Small(v: String, col: Color = T.Muted) =
    Text(v, style = TextStyle(color = c(col), fontSize = 12.sp))

private fun state(ctx: Context) = Repo.cached(ctx) to (Repo.token(ctx) != null)

// ---------- bitmaps (Glance s'vizaton dot vija; i bëjmë si imazh) ----------
object Draw {
    fun ring(score: Int?, size: Int = 220): Bitmap {
        val b = Bitmap.createBitmap(size, size, Bitmap.Config.ARGB_8888)
        val cv = Canvas(b); val stroke = size * 0.1f
        val r = RectF(stroke, stroke, size - stroke, size - stroke)
        val p = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE; strokeWidth = stroke; strokeCap = Paint.Cap.ROUND }
        p.color = T.Border.toArgb(); cv.drawArc(r, 0f, 360f, false, p)
        if (score != null) {
            p.shader = LinearGradient(0f, size.toFloat(), size.toFloat(), 0f, T.Brass.toArgb(), T.Good.toArgb(), Shader.TileMode.CLAMP)
            cv.drawArc(r, -90f, 360f * score.coerceIn(0, 100) / 100f, false, p)
        }
        return b
    }

    fun trend(points: List<Point>, w: Int = 640, h: Int = 240): Bitmap {
        val b = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
        if (points.size < 2) return b
        val cv = Canvas(b); val pad = 12f
        val max = points.maxOf { it.value }.coerceAtLeast(1.0)
        val xy = points.mapIndexed { i, p ->
            (pad + i * (w - 2 * pad) / (points.size - 1)) to (h - pad - (p.value / max * (h - 2 * pad)).toFloat())
        }
        val path = Path().apply {
            moveTo(xy[0].first, xy[0].second)
            for (i in 0 until xy.size - 1) {   // kurbë e butë (Catmull-Rom → Bézier)
                val p0 = xy[maxOf(i - 1, 0)]; val p1 = xy[i]; val p2 = xy[i + 1]; val p3 = xy[minOf(i + 2, xy.size - 1)]
                cubicTo(p1.first + (p2.first - p0.first) / 6, p1.second + (p2.second - p0.second) / 6,
                    p2.first - (p3.first - p1.first) / 6, p2.second - (p3.second - p1.second) / 6, p2.first, p2.second)
            }
        }
        val fill = Path(path).apply { lineTo(xy.last().first, h.toFloat()); lineTo(xy[0].first, h.toFloat()); close() }
        cv.drawPath(fill, Paint(Paint.ANTI_ALIAS_FLAG).apply {
            shader = LinearGradient(0f, 0f, 0f, h.toFloat(), (T.Brass.toArgb() and 0x00FFFFFF) or 0x55000000, 0, Shader.TileMode.CLAMP)
        })
        cv.drawPath(path, Paint(Paint.ANTI_ALIAS_FLAG).apply {
            style = Paint.Style.STROKE; strokeWidth = 5f; color = T.Brass.toArgb(); strokeCap = Paint.Cap.ROUND
        })
        cv.drawCircle(xy.last().first, xy.last().second, 8f, Paint(Paint.ANTI_ALIAS_FLAG).apply { color = T.Brass.toArgb() })
        return b
    }
}

// 1×1 / 2×1 · Shitje sot
class SalesWidget : GlanceAppWidget() {
    override suspend fun provideGlance(context: Context, id: GlanceId) {
        val (s, logged) = state(context)
        provideContent {
            Card(context, "SHITJET SOT", s, logged) { snap ->
                Big(Money.compact(snap.salesToday))
                val d = snap.salesDelta
                if (d != null) Small((if (d >= 0) "▲ " else "▼ ") + Money.pct(d) + " vs java e kaluar", if (d >= 0) T.Good else T.Bad)
                Small("Muaji " + Money.compact(snap.mtd))
            }
        }
    }
}

// 2×1 · Business Health
class HealthWidget : GlanceAppWidget() {
    override suspend fun provideGlance(context: Context, id: GlanceId) {
        val (s, logged) = state(context)
        val ring = Draw.ring(s?.health)
        provideContent {
            Card(context, "SHËNDETI I BIZNESIT", s, logged) { snap ->
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Column(horizontalAlignment = Alignment.CenterHorizontally) {
                        Image(ImageProvider(ring), "Health ${snap.health ?: "—"}", GlanceModifier.size(64.dp))
                        Text(snap.health?.toString() ?: "—", style = TextStyle(color = c(T.Ink), fontSize = 20.sp, fontFamily = FontFamily.Serif))
                    }
                    Spacer(GlanceModifier.width(12.dp))
                    Column {
                        Small("Shitje  ${snap.hSales ?: "—"}")
                        Small("Likuiditet  ${snap.hLiquidity ?: "—"}")
                        Small("Arkëtime  ${snap.hCollections ?: "—"}")
                    }
                }
            }
        }
    }
}

// 2×2 · Borxhe
class DebtWidget : GlanceAppWidget() {
    override suspend fun provideGlance(context: Context, id: GlanceId) {
        val (s, logged) = state(context)
        provideContent {
            Card(context, "TË ARKËTUESHME", s, logged, tab = "financa") { snap ->
                Big(Money.compact(snap.recvTotal))
                Small("Me afat të kaluar " + Money.compact(snap.overdue), T.Bad)
                Small("Paguajnë pas ${snap.dso?.toInt() ?: "—"} ditësh", if ((snap.dso ?: 0.0) > (snap.dsoTarget ?: 45.0)) T.Warn else T.Good)
            }
        }
    }
}

// 4×1 · Aprovime
class ApprovalsWidget : GlanceAppWidget() {
    override suspend fun provideGlance(context: Context, id: GlanceId) {
        val (s, logged) = state(context)
        provideContent {
            Card(context, "APROVIME NË PRITJE", s, logged, tab = "aprovime") { snap ->
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Big(snap.pending?.toString() ?: "—", T.Brass)
                    Spacer(GlanceModifier.width(10.dp))
                    Small("${snap.approvals.count { it.priority == "critical" || it.priority == "high" }} me prioritet të lartë")
                }
            }
        }
    }
}

// 4×2 · Alarme
class AlertsWidget : GlanceAppWidget() {
    override suspend fun provideGlance(context: Context, id: GlanceId) {
        val (s, logged) = state(context)
        provideContent {
            Card(context, "SINJALE", s, logged) { snap ->
                if (snap.signals.isEmpty()) Small("Asgjë urgjente", T.Good)
                snap.signals.take(4).forEach { a ->
                    Row(GlanceModifier.fillMaxWidth().padding(vertical = 2.dp)) {
                        Text("● ", style = TextStyle(color = c(if (a.severity == "critical") T.Bad else T.Warn), fontSize = 12.sp))
                        Text(a.title, maxLines = 1, style = TextStyle(color = c(T.Ink), fontSize = 13.sp))
                    }
                }
            }
        }
    }
}

// 2×2 / 4×2 · Shitjet 14 ditë
class TrendWidget : GlanceAppWidget() {
    override suspend fun provideGlance(context: Context, id: GlanceId) {
        val (s, logged) = state(context)
        val bmp = Draw.trend(s?.series ?: emptyList())
        provideContent {
            Card(context, "SHITJET · 14 DITË", s, logged, tab = "shitje") { snap ->
                Small("Muaji " + Money.compact(snap.mtd) + "  " + Money.pct(snap.mtdDelta), if ((snap.mtdDelta ?: 0.0) >= 0) T.Good else T.Bad)
                Spacer(GlanceModifier.height(6.dp))
                Image(ImageProvider(bmp), "Shitjet 14 ditë", GlanceModifier.fillMaxWidth().height(80.dp))
            }
        }
    }
}

// 4×1 · Pyet THA'NA
class AskWidget : GlanceAppWidget() {
    override suspend fun provideGlance(context: Context, id: GlanceId) {
        provideContent {
            Row(
                GlanceModifier.fillMaxSize().background(T.Surface).cornerRadius(32.dp).padding(14.dp).clickable(openApp(context, "ai")),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text("✦", style = TextStyle(color = c(T.Brass), fontSize = 22.sp))
                Spacer(GlanceModifier.width(10.dp))
                Text("Pyet THA'NA…", style = TextStyle(color = c(T.Muted), fontSize = 15.sp))
            }
        }
    }
}

// ---------- Receivers ----------
abstract class BaseReceiver : GlanceAppWidgetReceiver() {
    override fun onUpdate(context: Context, mgr: AppWidgetManager, ids: IntArray) {
        super.onUpdate(context, mgr, ids)
        Sync.schedule(context)
        Sync.now(context)
    }
}

class SalesReceiver : BaseReceiver() { override val glanceAppWidget: GlanceAppWidget = SalesWidget() }
class HealthReceiver : BaseReceiver() { override val glanceAppWidget: GlanceAppWidget = HealthWidget() }
class DebtReceiver : BaseReceiver() { override val glanceAppWidget: GlanceAppWidget = DebtWidget() }
class ApprovalsReceiver : BaseReceiver() { override val glanceAppWidget: GlanceAppWidget = ApprovalsWidget() }
class AlertsReceiver : BaseReceiver() { override val glanceAppWidget: GlanceAppWidget = AlertsWidget() }
class TrendReceiver : BaseReceiver() { override val glanceAppWidget: GlanceAppWidget = TrendWidget() }
class AskReceiver : BaseReceiver() { override val glanceAppWidget: GlanceAppWidget = AskWidget() }
