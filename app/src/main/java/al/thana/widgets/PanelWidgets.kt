package al.thana.widgets

import android.content.Context
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.glance.GlanceId
import androidx.glance.GlanceModifier
import androidx.glance.Image
import androidx.glance.ImageProvider
import androidx.compose.ui.graphics.toArgb
import androidx.glance.action.clickable
import androidx.glance.appwidget.GlanceAppWidget
import androidx.glance.appwidget.action.actionStartActivity
import androidx.glance.appwidget.cornerRadius
import androidx.glance.appwidget.provideContent
import androidx.glance.appwidget.updateAll
import androidx.glance.background
import androidx.glance.layout.Column
import androidx.glance.layout.ColumnScope
import androidx.glance.layout.Row
import androidx.glance.layout.Spacer
import androidx.glance.layout.fillMaxSize
import androidx.glance.layout.fillMaxWidth
import androidx.glance.layout.height
import androidx.glance.layout.padding
import androidx.glance.layout.width
import androidx.glance.text.FontFamily
import androidx.glance.text.FontWeight
import androidx.glance.text.Text
import androidx.glance.text.TextStyle
import androidx.glance.unit.ColorProvider
import org.json.JSONObject
import java.text.DecimalFormat
import java.text.DecimalFormatSymbols
import java.util.Locale

/** Widget-et e panelit të shitjeve (/w). Vetëm shfaqin JSON-in e serverit; prekja hap faqen përkatëse në panel. */
private fun cp(x: Color) = ColorProvider(x)
private val NF = DecimalFormat("#,##0", DecimalFormatSymbols(Locale.US).apply { groupingSeparator = ' ' })
private fun n0(v: Double?) = if (v == null || v.isNaN()) "—" else NF.format(Math.round(v))
private fun JSONObject.d(k: String): Double? = if (has(k) && !isNull(k)) optDouble(k) else null
private fun lightColor(l: String?) = when (l) { "green" -> T.Good; "amber", "yellow" -> T.Warn; "red" -> T.Bad; else -> T.Muted }

@Composable private fun PBig(v: String, col: Color = T.Ink) = Text(v, style = TextStyle(color = cp(col), fontSize = 26.sp, fontFamily = FontFamily.Serif))
@Composable private fun PSmall(v: String, col: Color = T.Muted, size: Int = 12) = Text(v, maxLines = 2, style = TextStyle(color = cp(col), fontSize = size.sp))
@Composable private fun PRow(l: String, r: String, col: Color = T.Ink) = Row(GlanceModifier.fillMaxWidth()) {
    Text(l, maxLines = 1, modifier = GlanceModifier.defaultWeight(), style = TextStyle(color = cp(T.Ink), fontSize = 12.sp))
    Text(r, style = TextStyle(color = cp(col), fontSize = 12.sp, fontWeight = FontWeight.Bold))
}

/** Shirit progresi si imazh (Glance s'ka progress me ngjyrë të lirë). */
private fun barBitmap(frac: Double, col: Color, w: Int = 600, h: Int = 16): android.graphics.Bitmap {
    val b = android.graphics.Bitmap.createBitmap(w, h, android.graphics.Bitmap.Config.ARGB_8888); val cv = android.graphics.Canvas(b)
    val p = android.graphics.Paint(android.graphics.Paint.ANTI_ALIAS_FLAG); val r = h / 2f
    p.color = T.Border.toArgb(); cv.drawRoundRect(android.graphics.RectF(0f, 0f, w.toFloat(), h.toFloat()), r, r, p)
    val f = frac.coerceIn(0.0, 1.0).toFloat(); if (f > 0) { p.color = col.toArgb(); cv.drawRoundRect(android.graphics.RectF(0f, 0f, maxOf(h.toFloat(), w * f), h.toFloat()), r, r, p) }
    return b
}
@Composable private fun PBar(frac: Double, col: Color) =
    Image(ImageProvider(barBitmap(frac, col)), "progres", GlanceModifier.fillMaxWidth().height(6.dp))

/** Gjendja lexohet JASHTË kompozimit (skedarë/Keystore), si te widget-et e tjera. */
class PState(val on: Boolean, val w: JSONObject?, val at: Long?)
private fun pstate(ctx: Context, id: String) = PState(Panel.connected(ctx), Panel.cached(ctx, id), Panel.syncedAt(ctx))

@Composable
private fun PCard(ctx: Context, title: String, id: String, st: PState, body: @Composable ColumnScope.(JSONObject) -> Unit) {
    val on = st.on; val w = st.w
    val click = if (on) actionStartActivity(Panel.openIntent(ctx, id)) else actionStartActivity(android.content.Intent(ctx, MainActivity::class.java).addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK).putExtra("tab", "cilesime"))
    Column(GlanceModifier.fillMaxSize().background(T.Surface).cornerRadius(24.dp).padding(14.dp).clickable(click)) {
        Text(title, style = TextStyle(color = cp(T.Muted), fontSize = 11.sp, fontWeight = FontWeight.Bold))
        Spacer(GlanceModifier.height(6.dp))
        when {
            !on -> { Text("Lidh panelin", style = TextStyle(color = cp(T.Brass), fontSize = 16.sp, fontWeight = FontWeight.Bold)); PSmall("Cilësime → Paneli i shitjeve (linku + PIN)") }
            w == null || !w.has("data") -> { PBig("—"); PSmall("Në pritje të sinkronizimit") }
            else -> body(w.getJSONObject("data"))
        }
        Spacer(GlanceModifier.defaultWeight())
        st.at?.let { PSmall("⟳ " + java.text.SimpleDateFormat("HH:mm", Locale.US).format(java.util.Date(it)), size = 10) }
    }
}

// 2×1 · Plani
class PlanPanelWidget : GlanceAppWidget() {
    override suspend fun provideGlance(context: Context, id: GlanceId) {
        val st = pstate(context, "W2.plan")
        provideContent { PCard(context, "PLANI I MUAJIT", "W2.plan", st) { d ->
            val base = d.optJSONObject("plan")?.d("base"); val kg = d.optJSONObject("realized")?.d("kg"); val pct = d.d("pct_base")
            PBig("${n0(kg)} kg", lightColor(d.optString("light")))
            PSmall("nga ${n0(base)} kg · ${pct?.let { n0(it) } ?: "—"}% · projeksioni ${n0(d.d("projection_kg"))}")
            Spacer(GlanceModifier.height(6.dp)); PBar((pct ?: 0.0) / 100, lightColor(d.optString("light")))
        }
    } }
}

// 4×2 · Ekipi
class TeamPanelWidget : GlanceAppWidget() {
    override suspend fun provideGlance(context: Context, id: GlanceId) {
        val st = pstate(context, "W8.team")
        provideContent { PCard(context, "EKIPI · KUNDREJT PRITJES", "W8.team", st) { d ->
            val t = d.optJSONObject("total")
            PSmall("${n0(t?.d("realized_kg"))} / ${n0(t?.d("base"))} kg · duhen ${n0(t?.d("need_per_day"))} kg/ditë", T.Ink)
            Spacer(GlanceModifier.height(4.dp))
            val a = d.optJSONArray("agents"); val list = (0 until (a?.length() ?: 0)).map { a!!.getJSONObject(it) }
                .filter { (it.optJSONObject("plan")?.d("base") ?: 0.0) > 0 }.sortedBy { it.d("pct_expected") ?: 0.0 }.take(4)
            for (x in list) { val p = x.d("pct_expected"); PRow(x.optString("name", "Agjenti " + x.optString("code")), "${p?.let { n0(it) } ?: "—"}%", if ((p ?: 0.0) >= 95) T.Good else if ((p ?: 0.0) >= 70) T.Warn else T.Bad) }
        }
    } }
}

// 2×2 · Stoku negativ
class StockNegPanelWidget : GlanceAppWidget() {
    override suspend fun provideGlance(context: Context, id: GlanceId) {
        val st = pstate(context, "W11.stockneg")
        provideContent { PCard(context, "STOKU NEGATIV", "W11.stockneg", st) { d ->
            val t = d.optJSONObject("totals")
            PBig(n0(t?.d("value")) + " L", T.Bad); PSmall("${t?.optInt("skus") ?: "—"} artikuj · ${t?.optInt("warehouses") ?: "—"} magazina")
            Spacer(GlanceModifier.height(4.dp))
            val w = d.optJSONArray("warehouses"); for (i in 0 until minOf(3, w?.length() ?: 0)) { val x = w!!.getJSONObject(i); PRow(x.optString("name"), n0(x.d("value")), T.Bad) }
        }
    } }
}

// 2×2 · Arkëtimet
class CollectPanelWidget : GlanceAppWidget() {
    override suspend fun provideGlance(context: Context, id: GlanceId) {
        val st = pstate(context, "W6.collect")
        provideContent { PCard(context, "ARKËTIMET", "W6.collect", st) { d ->
            val t = d.optJSONObject("totals")
            PBig(n0(t?.d("matured")) + " L"); PSmall("të maturuara · ${t?.optInt("customers") ?: "—"} klientë")
            PSmall("${t?.optInt("high_risk") ?: 0} pa pagesë ≥ 60 ditë", T.Bad)
            val arr = d.optJSONArray("items"); for (i in 0 until minOf(2, arr?.length() ?: 0)) { val x = arr!!.getJSONObject(i); PRow(x.optJSONObject("customer")?.optString("name") ?: "—", n0(x.d("matured"))) }
        }
    } }
}

// 2×2 · Detyrat
class TasksPanelWidget : GlanceAppWidget() {
    override suspend fun provideGlance(context: Context, id: GlanceId) {
        val st = pstate(context, "W5.tasks")
        provideContent { PCard(context, "DETYRAT", "W5.tasks", st) { d ->
            PBig("${d.optInt("open")}"); PSmall("të hapura")
            val a = d.optJSONArray("by_agent"); val list = (0 until (a?.length() ?: 0)).map { a!!.getJSONObject(it) }.sortedByDescending { it.optInt("undone") }.take(3)
            for (x in list) PRow(x.optString("name", x.optString("code")), "${x.optInt("undone")}", T.Warn)
        }
    } }
}

// 4×1 · Raportet
class ReportsPanelWidget : GlanceAppWidget() {
    override suspend fun provideGlance(context: Context, id: GlanceId) {
        val st = pstate(context, "W13.reports")
        provideContent { PCard(context, "RAPORTET", "W13.reports", st) { d ->
            val arr = d.optJSONArray("items")
            if (arr == null || arr.length() == 0) PSmall("Ende pa raporte nga Grok.")
            else { val x = arr.getJSONObject(0); Text(x.optString("label") + " · " + x.optString("period_date").takeLast(5), style = TextStyle(color = cp(T.Ink), fontSize = 13.sp, fontWeight = FontWeight.Bold)); PSmall(x.optString("summary", x.optString("title"))) }
        }
    } }
}

object PanelWidgets {
    suspend fun updateAll(c: Context) {
        PlanPanelWidget().updateAll(c); TeamPanelWidget().updateAll(c); StockNegPanelWidget().updateAll(c)
        CollectPanelWidget().updateAll(c); TasksPanelWidget().updateAll(c); ReportsPanelWidget().updateAll(c)
    }
}

class PlanPanelReceiver : BaseReceiver() { override val glanceAppWidget: GlanceAppWidget = PlanPanelWidget() }
class TeamPanelReceiver : BaseReceiver() { override val glanceAppWidget: GlanceAppWidget = TeamPanelWidget() }
class StockNegPanelReceiver : BaseReceiver() { override val glanceAppWidget: GlanceAppWidget = StockNegPanelWidget() }
class CollectPanelReceiver : BaseReceiver() { override val glanceAppWidget: GlanceAppWidget = CollectPanelWidget() }
class TasksPanelReceiver : BaseReceiver() { override val glanceAppWidget: GlanceAppWidget = TasksPanelWidget() }
class ReportsPanelReceiver : BaseReceiver() { override val glanceAppWidget: GlanceAppWidget = ReportsPanelWidget() }
