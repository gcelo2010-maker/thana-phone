package al.thana.shitje

import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import androidx.compose.runtime.Composable
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.glance.GlanceId
import androidx.glance.GlanceModifier
import androidx.glance.Image
import androidx.glance.ImageProvider
import androidx.glance.action.clickable
import androidx.glance.appwidget.GlanceAppWidget
import androidx.glance.appwidget.GlanceAppWidgetReceiver
import androidx.glance.appwidget.action.actionStartActivity
import androidx.glance.appwidget.cornerRadius
import androidx.glance.appwidget.provideContent
import androidx.glance.appwidget.updateAll
import androidx.glance.background
import androidx.glance.layout.Alignment
import androidx.glance.layout.Column
import androidx.glance.layout.ColumnScope
import androidx.glance.layout.ContentScale
import androidx.glance.layout.Row
import androidx.glance.layout.Spacer
import androidx.glance.layout.fillMaxSize
import androidx.glance.layout.fillMaxWidth
import androidx.glance.layout.height
import androidx.glance.layout.padding
import androidx.glance.layout.size
import androidx.glance.layout.width
import androidx.glance.text.FontWeight
import androidx.glance.text.Text
import androidx.glance.text.TextStyle
import androidx.glance.unit.ColorProvider
import org.json.JSONObject
import java.text.DecimalFormat
import java.text.DecimalFormatSymbols
import java.util.Locale

/**
 * THA'NA Shitje · widget-et (dizajni i panelit: blu i errët, gradient cyan→lime, grafikë).
 * Shifrat i jep serveri (/w/api/w); telefoni vetëm i vizaton. Prekja hap VETËM faqen e widget-it.
 */
private fun cp(c: Int) = ColorProvider(androidx.compose.ui.graphics.Color(c))
private val NF = DecimalFormat("#,##0", DecimalFormatSymbols(Locale.US).apply { groupingSeparator = ' ' })
private fun n0(v: Double?) = if (v == null || v.isNaN()) "—" else NF.format(Math.round(v))
private fun JSONObject.d(k: String): Double? = if (has(k) && !isNull(k)) optDouble(k) else null
private fun dm(iso: String) = if (iso.length >= 10) iso.substring(8, 10) + "." + iso.substring(5, 7) else iso
private fun mil(v: Double?) = when { v == null -> "—"; Math.abs(v) >= 1e6 -> String.format(Locale.US, "%.1fM", v / 1e6); Math.abs(v) >= 1e3 -> String.format(Locale.US, "%.0fK", v / 1e3); else -> n0(v) }

class PState(val on: Boolean, val w: JSONObject?, val at: Long?, val expired: Boolean)
private fun pstate(ctx: Context, id: String) = PState(Panel.connected(ctx), Panel.cached(ctx, id), Panel.syncedAt(ctx), Panel.expired(ctx))
private val ACCENT by lazy { Viz.accent() }

// ---------- tipografia ----------
@Composable private fun Lbl(t: String) = Text(t.uppercase(), maxLines = 1, style = TextStyle(color = cp(P.MUTED), fontSize = 10.sp, fontWeight = FontWeight.Bold))
@Composable private fun Big(t: String, c: Int = P.INK, size: Int = 26) = Text(t, maxLines = 1, style = TextStyle(color = cp(c), fontSize = size.sp, fontWeight = FontWeight.Bold))
@Composable private fun Sub(t: String, c: Int = P.MUTED, size: Int = 11, lines: Int = 1) = Text(t, maxLines = lines, style = TextStyle(color = cp(c), fontSize = size.sp))
@Composable private fun Chip(t: String, c: Int) = Text(" $t ", style = TextStyle(color = cp(c), fontSize = 11.sp, fontWeight = FontWeight.Bold),
    modifier = GlanceModifier.background(cp(P.CARD2)).cornerRadius(10.dp).padding(horizontal = 6.dp, vertical = 2.dp))
@Composable private fun Pic(b: Bitmap, h: Dp, desc: String = "") = Image(ImageProvider(b), desc, GlanceModifier.fillMaxWidth().height(h), contentScale = ContentScale.FillBounds)
@Composable private fun Gap(h: Int = 6) = Spacer(GlanceModifier.height(h.dp))
@Composable private fun KV(l: String, r: String, c: Int = P.INK) = Row(GlanceModifier.fillMaxWidth()) {
    Text(l, maxLines = 1, modifier = GlanceModifier.defaultWeight(), style = TextStyle(color = cp(P.INK), fontSize = 12.sp))
    Text(r, maxLines = 1, style = TextStyle(color = cp(c), fontSize = 12.sp, fontWeight = FontWeight.Bold))
}

/** Karta: shirit gradient sipër, titulli + çipi, trupi, ora e sinkronizimit. */
@Composable
private fun Card(ctx: Context, title: String, id: String, st: PState, chip: Pair<String, Int>? = null, body: @Composable ColumnScope.(JSONObject) -> Unit) {
    val click = actionStartActivity(Intent(ctx, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP).putExtra("open", id))
    Column(GlanceModifier.fillMaxSize().background(cp(P.CARD)).cornerRadius(22.dp).clickable(click)) {
        Image(ImageProvider(ACCENT), "", GlanceModifier.fillMaxWidth().height(3.dp), contentScale = ContentScale.FillBounds)
        Column(GlanceModifier.fillMaxSize().padding(horizontal = 14.dp, vertical = 10.dp)) {
            Row(GlanceModifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                Row(GlanceModifier.defaultWeight()) { Lbl(title) }
                if (chip != null && st.on && st.w != null && !st.expired) Chip(chip.first, chip.second)
            }
            Gap(6)
            when {
                !st.on -> { Big("Hyr", P.MINT, 18); Sub("Prek për t'u lidhur me linkun dhe PIN-in", lines = 2) }
                st.expired -> { Big("Sesioni skadoi", P.YELLOW, 16); Sub("Prek dhe hyr sërish me PIN") }
                st.w == null || !st.w.has("data") -> { Big("—", P.MUTED); Sub("Në pritje të sinkronizimit") }
                else -> body(st.w.getJSONObject("data"))
            }
            Spacer(GlanceModifier.defaultWeight())
            st.at?.let { Sub("⟳ " + java.text.SimpleDateFormat("HH:mm", Locale.US).format(java.util.Date(it)), P.GREY, 9) }
        }
    }
}

private fun chipOf(st: PState, f: (JSONObject) -> Pair<String, Int>?): Pair<String, Int>? = st.w?.optJSONObject("data")?.let { runCatching { f(it) }.getOrNull() }

// ---------- Plani · unazë ----------
class PlanPanelWidget : GlanceAppWidget() {
    override suspend fun provideGlance(context: Context, id: GlanceId) {
        val st = pstate(context, "W2.plan"); val d = st.w?.optJSONObject("data")
        val base = d?.optJSONObject("plan")?.d("base"); val kg = d?.optJSONObject("realized")?.d("kg")
        val per = st.w?.optJSONObject("period"); val el = per?.d("elapsed_days"); val wd = per?.d("working_days")
        val ring = Viz.ring(if (base != null && kg != null && base > 0) kg / base else 0.0, if (el != null && wd != null && wd > 0) el / wd else null)
        provideContent {
            Card(context, "Plani i muajit", "W2.plan", st, chipOf(st) { "${n0(it.d("pct_base"))}%" to P.light(it.optString("light")) }) { x ->
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Image(ImageProvider(ring), "plani", GlanceModifier.size(64.dp))
                    Spacer(GlanceModifier.width(12.dp))
                    Column {
                        Big("${n0(kg)}", P.INK, 24); Sub("nga ${n0(base)} kg")
                        Sub("Projeksioni ${n0(x.d("projection_kg"))} kg", P.MINT)
                    }
                }
            }
        }
    }
}

// ---------- Ekipi · shirita kundrejt pritjes ----------
class TeamPanelWidget : GlanceAppWidget() {
    override suspend fun provideGlance(context: Context, id: GlanceId) {
        val st = pstate(context, "W8.team"); val d = st.w?.optJSONObject("data")
        val a = d?.optJSONArray("agents"); val list = (0 until (a?.length() ?: 0)).map { a!!.getJSONObject(it) }
            .filter { (it.optJSONObject("plan")?.d("base") ?: 0.0) > 0 }.sortedBy { it.d("pct_expected") ?: 0.0 }.take(5)
        val vals = list.map { (it.d("pct_expected") ?: 0.0).coerceAtLeast(0.0) }
        val bars = Viz.hbars(vals, vals.map { P.pct(it) }, maxOf(110.0, vals.maxOrNull() ?: 0.0), 100.0, rowH = 22, gap = 34)
        provideContent {
            Card(context, "Ekipi · kundrejt pritjes", "W8.team", st, chipOf(st) { val t = it.optJSONObject("total")!!; val p = t.d("expected_kg")?.let { e -> if (e > 0) t.d("realized_kg")!! / e * 100 else null }; "${n0(p)}%" to P.pct(p) }) { x ->
                val t = x.optJSONObject("total")
                Sub("${n0(t?.d("realized_kg"))} / ${n0(t?.d("base"))} kg · duhen ${n0(t?.d("need_per_day"))} kg/ditë", P.INK, 12)
                Gap(6)
                Row(GlanceModifier.fillMaxWidth()) {
                    Column(GlanceModifier.width(110.dp)) { list.forEach { Text(it.optString("name", it.optString("code")), maxLines = 1, style = TextStyle(color = cp(P.INK), fontSize = 11.sp)) } }
                    Column(GlanceModifier.defaultWeight().padding(top = 3.dp)) { Image(ImageProvider(bars), "ekipi", GlanceModifier.fillMaxWidth().height((list.size * 14).dp), contentScale = ContentScale.FillBounds) }
                    Column(GlanceModifier.width(40.dp), horizontalAlignment = Alignment.End) { vals.forEach { Text("${n0(it)}%", style = TextStyle(color = cp(P.pct(it)), fontSize = 11.sp, fontWeight = FontWeight.Bold)) } }
                }
            }
        }
    }
}

// ---------- Stoku negativ · shirita të kuq ----------
class StockNegPanelWidget : GlanceAppWidget() {
    override suspend fun provideGlance(context: Context, id: GlanceId) {
        val st = pstate(context, "W11.stockneg"); val d = st.w?.optJSONObject("data")
        val w = d?.optJSONArray("warehouses"); val list = (0 until minOf(4, w?.length() ?: 0)).map { w!!.getJSONObject(it) }
        val vals = list.map { Math.abs(it.d("value") ?: 0.0) }
        val bars = Viz.hbars(vals, vals.map { P.RED }, vals.maxOrNull() ?: 1.0, rowH = 18, gap = 46)
        provideContent {
            Card(context, "Stoku negativ", "W11.stockneg", st, chipOf(st) { "${it.optJSONObject("totals")!!.optInt("skus")} art." to P.RED }) { x ->
                Big(mil(x.optJSONObject("totals")?.d("value")) + " L", P.RED, 22)
                Gap(4)
                list.forEachIndexed { i, it -> KV(it.optString("name").replace("Magazina ", ""), mil(it.d("value")), P.RED)
                    Image(ImageProvider(Viz.hbars(listOf(vals[i]), listOf(P.RED), vals.maxOrNull() ?: 1.0, rowH = 14, gap = 0)), "", GlanceModifier.fillMaxWidth().height(4.dp), contentScale = ContentScale.FillBounds)
                    Gap(3) }
            }
        }
    }
}

// ---------- Arkëtimet · e maturuar vs jo ----------
class CollectPanelWidget : GlanceAppWidget() {
    override suspend fun provideGlance(context: Context, id: GlanceId) {
        val st = pstate(context, "W6.collect"); val t = st.w?.optJSONObject("data")?.optJSONObject("totals")
        val bar = Viz.stacked(listOf((t?.d("matured") ?: 0.0) to P.RED, (t?.d("not_matured") ?: 0.0) to P.CYAN))
        provideContent {
            Card(context, "Arkëtimet", "W6.collect", st, chipOf(st) { val h = it.optJSONObject("totals")!!.optInt("high_risk"); "$h ≥60 ditë" to if (h > 0) P.RED else P.MINT }) { x ->
                Big(mil(t?.d("matured")) + " L", P.INK, 22); Sub("të maturuara · ${t?.optInt("customers") ?: "—"} klientë")
                Gap(6); Pic(bar, 8.dp)
                Gap(4); Row { Sub("● maturuar  ", P.RED, 10); Sub("● pa maturuar ${mil(t?.d("not_matured"))}", P.CYAN, 10) }
                val it = x.optJSONArray("items"); if (it != null && it.length() > 0) { Gap(4); val c = it.getJSONObject(0); KV(c.optJSONObject("customer")?.optString("name") ?: "—", mil(c.d("matured")), P.RED) }
            }
        }
    }
}

// ---------- Detyrat · segmente statusi ----------
class TasksPanelWidget : GlanceAppWidget() {
    override suspend fun provideGlance(context: Context, id: GlanceId) {
        val st = pstate(context, "W5.tasks"); val s = st.w?.optJSONObject("data")?.optJSONObject("by_status")
        fun g(k: String) = s?.optDouble(k, 0.0) ?: 0.0
        val bar = Viz.stacked(listOf(g("done") to P.MINT, (g("assigned") + g("in_progress")) to P.CYAN, g("deferred") to P.YELLOW, (g("not_done") + g("need_help")) to P.RED))
        provideContent {
            Card(context, "Detyrat", "W5.tasks", st, chipOf(st) { "${it.optInt("open")} hapur" to P.CYAN }) { x ->
                Big("${x.optInt("open")}", P.INK, 26); Sub("të hapura · ${n0(g("done"))} u bënë")
                Gap(6); Pic(bar, 8.dp); Gap(4)
                Row { Sub("● bërë ", P.MINT, 10); Sub("● hapur ", P.CYAN, 10); Sub("● shtyrë ", P.YELLOW, 10); Sub("● s'u bë", P.RED, 10) }
                val a = x.optJSONArray("by_agent"); val l = (0 until (a?.length() ?: 0)).map { a!!.getJSONObject(it) }.sortedByDescending { it.optInt("undone") }.take(2)
                if (l.isNotEmpty()) Gap(4); l.forEach { KV(it.optString("name", it.optString("code")), "${it.optInt("undone")}", P.YELLOW) }
            }
        }
    }
}

// ---------- Raportet ----------
class ReportsPanelWidget : GlanceAppWidget() {
    override suspend fun provideGlance(context: Context, id: GlanceId) {
        val st = pstate(context, "W13.reports")
        provideContent {
            Card(context, "Raportet", "W13.reports", st, chipOf(st) { "${it.optJSONArray("items")!!.length()}" to P.MINT }) { x ->
                val a = x.optJSONArray("items")
                if (a == null || a.length() == 0) Sub("Ende pa raporte nga Grok.", lines = 2)
                else { val r = a.getJSONObject(0)
                    Row(verticalAlignment = Alignment.CenterVertically) { Text(r.optString("label"), maxLines = 1, style = TextStyle(color = cp(P.INK), fontSize = 14.sp, fontWeight = FontWeight.Bold)); Spacer(GlanceModifier.width(6.dp)); Chip(dm(r.optString("period_date")), P.MINT) }
                    Sub(r.optString("summary", r.optString("title")), P.MUTED, 11, 2) }
            }
        }
    }
}

// ---------- Ecuria e produktit · linjat ----------
class ProductPanelWidget : GlanceAppWidget() {
    override suspend fun provideGlance(context: Context, id: GlanceId) {
        val st = pstate(context, "W3.product"); val d = st.w?.optJSONObject("data")
        val a = d?.optJSONArray("lines"); val l = (0 until (a?.length() ?: 0)).map { a!!.getJSONObject(it) }.sortedByDescending { it.d("realized_kg") ?: 0.0 }.take(5)
        val vals = l.map { it.d("realized_kg") ?: 0.0 }
        val bars = Viz.hbars(vals, l.map { P.light(it.optString("light")) }, vals.maxOrNull() ?: 1.0, rowH = 22, gap = 34)
        provideContent {
            Card(context, "Ecuria e produktit", "W3.product", st, chipOf(st) { val m = it.optJSONObject("totals")!!.d("mom_pct"); (if ((m ?: 0.0) >= 0) "+" else "") + n0(m) + "% m/m" to if ((m ?: 0.0) >= 0) P.MINT else P.RED }) { x ->
                Sub("${n0(x.optJSONObject("totals")?.d("realized_kg"))} kg · ${n0(x.optJSONObject("totals")?.d("pct_base"))}% e bazës", P.INK, 12); Gap(6)
                Row(GlanceModifier.fillMaxWidth()) {
                    Column(GlanceModifier.width(96.dp)) { l.forEach { Text(it.optString("line"), maxLines = 1, style = TextStyle(color = cp(P.INK), fontSize = 11.sp)) } }
                    Column(GlanceModifier.defaultWeight().padding(top = 3.dp)) { Image(ImageProvider(bars), "linjat", GlanceModifier.fillMaxWidth().height((l.size * 14).dp), contentScale = ContentScale.FillBounds) }
                    Column(GlanceModifier.width(54.dp), horizontalAlignment = Alignment.End) { vals.forEach { Text(n0(it), style = TextStyle(color = cp(P.MUTED), fontSize = 11.sp)) } }
                }
            }
        }
    }
}

// ---------- Chat ----------
class ChatPanelWidget : GlanceAppWidget() {
    override suspend fun provideGlance(context: Context, id: GlanceId) {
        val st = pstate(context, "W9.chat")
        provideContent {
            Card(context, "Chat", "W9.chat", st, chipOf(st) { val u = it.optInt("unread"); "$u pa përgjigje" to if (u > 0) P.YELLOW else P.MINT }) { x ->
                val a = x.optJSONArray("threads")
                if (a == null || a.length() == 0) Sub("Asnjë bisedë këtë muaj.")
                for (i in 0 until minOf(3, a?.length() ?: 0)) { val t = a!!.getJSONObject(i)
                    val c = when (t.optString("kind")) { "collection" -> P.YELLOW; "visit" -> P.CYAN; "photo" -> P.LIME; else -> P.MINT }
                    Row(verticalAlignment = Alignment.Top) { Text("●", style = TextStyle(color = cp(c), fontSize = 12.sp)); Spacer(GlanceModifier.width(6.dp))
                        Column { Text(t.optString("title"), maxLines = 1, style = TextStyle(color = cp(P.INK), fontSize = 12.sp, fontWeight = FontWeight.Bold)); Sub(t.optString("preview"), P.MUTED, 11) } }
                    Gap(3) }
            }
        }
    }
}

// ---------- Gjendja e magazinës ----------
class StockPanelWidget : GlanceAppWidget() {
    override suspend fun provideGlance(context: Context, id: GlanceId) {
        val st = pstate(context, "W12.stock"); val w = st.w?.optJSONObject("data")?.optJSONArray("warehouses")
        val x0 = w?.optJSONObject(0)?.optJSONObject("totals")
        val bar = Viz.stacked(listOf(((x0?.optInt("skus") ?: 0) - (x0?.optInt("neg_skus") ?: 0)).toDouble() to P.MINT, (x0?.optInt("neg_skus") ?: 0).toDouble() to P.RED))
        provideContent {
            Card(context, "Gjendja e magazinës", "W12.stock", st) { _ ->
                if (w == null || w.length() == 0) Sub("Pa magazinë të caktuar.")
                else { val x = w.getJSONObject(0)
                    Text(x.optString("name").replace("Magazina ", ""), maxLines = 1, style = TextStyle(color = cp(P.INK), fontSize = 14.sp, fontWeight = FontWeight.Bold))
                    Big(mil(x0?.d("value")) + " L", P.INK, 22); Sub("${x0?.optInt("skus")} artikuj · ${n0(x0?.d("kg"))} kg")
                    Gap(6); Pic(bar, 8.dp); Gap(3)
                    if ((x0?.optInt("neg_skus") ?: 0) > 0) Sub("● ${x0?.optInt("neg_skus")} artikuj në minus", P.RED, 10) }
            }
        }
    }
}

// ---------- Rruga · pikat e ndalesave ----------
class RoutePanelWidget : GlanceAppWidget() {
    override suspend fun provideGlance(context: Context, id: GlanceId) {
        val st = pstate(context, "W1.route"); val d = st.w?.optJSONObject("data")
        val s = d?.optJSONArray("stops"); val stats = (0 until (s?.length() ?: 0)).map { s!!.getJSONObject(it).optString("status") }
        val nextPos = d?.optJSONObject("next")?.optInt("pos") ?: -1
        val dots = Viz.dots(stats, nextPos - 1)
        provideContent {
            Card(context, "Rruga", "W1.route", st, chipOf(st) { "${it.optInt("done")}/${it.optInt("total")}" to P.MINT }) { x ->
                Sub(dm(x.optString("date")), P.MUTED, 11)
                x.optJSONObject("next")?.let { n -> val c = n.optJSONObject("customer")
                    Text("Tani · ${c?.optString("name") ?: "—"}", maxLines = 1, style = TextStyle(color = cp(P.MINT), fontSize = 15.sp, fontWeight = FontWeight.Bold)); Sub(c?.optString("city") ?: "") }
                Gap(8); Pic(dots, 16.dp)
            }
        }
    }
}

// ---------- Sot (agjenti) · kolonat ditore ----------
class DailyPanelWidget : GlanceAppWidget() {
    override suspend fun provideGlance(context: Context, id: GlanceId) {
        val st = pstate(context, "W7.daily"); val d = st.w?.optJSONObject("data")
        val bd = d?.optJSONObject("by_day"); val vals = bd?.keys()?.asSequence()?.sorted()?.map { bd.optDouble(it, 0.0) }?.toList()?.takeLast(10) ?: emptyList()
        val cols = Viz.columns(vals, d?.optJSONObject("need_per_day")?.d("base"))
        provideContent {
            Card(context, "Sot", "W7.daily", st, chipOf(st) { "${it.optInt("visits_done")}/${it.optInt("visits_total")} viz." to P.CYAN }) { x ->
                Big("${n0(x.d("kg_today"))} kg", P.INK, 22)
                val m = x.optJSONObject("month"); Sub("Muaji ${n0(m?.d("kg"))} / ${n0(m?.d("base"))} kg")
                Gap(6); Pic(cols, 34.dp)
            }
        }
    }
}

// ---------- Magazina ime (distributori) · unazë ----------
class DistPanelWidget : GlanceAppWidget() {
    override suspend fun provideGlance(context: Context, id: GlanceId) {
        val st = pstate(context, "W10.distributor"); val d = st.w?.optJSONObject("data")
        val base = d?.optJSONObject("plan")?.d("base"); val kg = d?.d("kg"); val exp = d?.d("expected_kg")
        val ring = Viz.ring(if (base != null && kg != null && base > 0) kg / base else 0.0, if (base != null && exp != null && base > 0) exp / base else null)
        provideContent {
            Card(context, "Magazina ime", "W10.distributor", st, chipOf(st) { val m = it.d("mom_pct"); (if ((m ?: 0.0) >= 0) "+" else "") + n0(m) + "%" to if ((m ?: 0.0) >= 0) P.MINT else P.RED }) { x ->
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Image(ImageProvider(ring), "magazina", GlanceModifier.size(64.dp)); Spacer(GlanceModifier.width(12.dp))
                    Column { Big(n0(kg), P.INK, 24); Sub("nga ${n0(base)} kg · ${x.optInt("buyers")} blerës") }
                }
                x.optJSONArray("insights")?.optJSONObject(0)?.let { Gap(4); Sub(it.optString("text"), P.MINT, 11, 2) }
            }
        }
    }
}

object PanelWidgets {
    suspend fun updateAll(c: Context) {
        PlanPanelWidget().updateAll(c); TeamPanelWidget().updateAll(c); StockNegPanelWidget().updateAll(c)
        CollectPanelWidget().updateAll(c); TasksPanelWidget().updateAll(c); ReportsPanelWidget().updateAll(c)
        ProductPanelWidget().updateAll(c); ChatPanelWidget().updateAll(c); StockPanelWidget().updateAll(c)
        RoutePanelWidget().updateAll(c); DailyPanelWidget().updateAll(c); DistPanelWidget().updateAll(c)
    }
}

abstract class BaseReceiver : GlanceAppWidgetReceiver() {
    override fun onUpdate(context: Context, mgr: android.appwidget.AppWidgetManager, ids: IntArray) {
        super.onUpdate(context, mgr, ids); Sync.schedule(context); Sync.now(context)
    }
}
class PlanPanelReceiver : BaseReceiver() { override val glanceAppWidget: GlanceAppWidget = PlanPanelWidget() }
class TeamPanelReceiver : BaseReceiver() { override val glanceAppWidget: GlanceAppWidget = TeamPanelWidget() }
class StockNegPanelReceiver : BaseReceiver() { override val glanceAppWidget: GlanceAppWidget = StockNegPanelWidget() }
class CollectPanelReceiver : BaseReceiver() { override val glanceAppWidget: GlanceAppWidget = CollectPanelWidget() }
class TasksPanelReceiver : BaseReceiver() { override val glanceAppWidget: GlanceAppWidget = TasksPanelWidget() }
class ReportsPanelReceiver : BaseReceiver() { override val glanceAppWidget: GlanceAppWidget = ReportsPanelWidget() }
class ProductPanelReceiver : BaseReceiver() { override val glanceAppWidget: GlanceAppWidget = ProductPanelWidget() }
class ChatPanelReceiver : BaseReceiver() { override val glanceAppWidget: GlanceAppWidget = ChatPanelWidget() }
class StockPanelReceiver : BaseReceiver() { override val glanceAppWidget: GlanceAppWidget = StockPanelWidget() }
class RoutePanelReceiver : BaseReceiver() { override val glanceAppWidget: GlanceAppWidget = RoutePanelWidget() }
class DailyPanelReceiver : BaseReceiver() { override val glanceAppWidget: GlanceAppWidget = DailyPanelWidget() }
class DistPanelReceiver : BaseReceiver() { override val glanceAppWidget: GlanceAppWidget = DistPanelWidget() }
