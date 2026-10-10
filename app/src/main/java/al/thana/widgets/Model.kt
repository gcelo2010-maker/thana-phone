package al.thana.widgets

import org.json.JSONArray
import org.json.JSONObject
import java.math.BigDecimal
import java.math.RoundingMode
import java.text.DecimalFormat
import java.text.DecimalFormatSymbols
import java.util.Locale

/** Formatimi i parave. Vlera e backend-it është e vërteta; këtu vetëm paraqitet. null → "—". */
object Money {
    private val sym = DecimalFormatSymbols(Locale.US).apply { groupingSeparator = ','; decimalSeparator = '.' }

    fun full(v: Double?): String {
        if (v == null) return "—"
        val f = DecimalFormat("#,##0", sym).apply { roundingMode = RoundingMode.HALF_UP }
        return "${f.format(BigDecimal.valueOf(v))} L"
    }

    fun compact(v: Double?): String {
        if (v == null) return "—"
        val b = BigDecimal.valueOf(v)
        return if (b.abs() >= BigDecimal(1_000_000)) "${b.divide(BigDecimal(1_000_000), 1, RoundingMode.HALF_UP).toPlainString()}M L"
        else full(v)
    }

    fun pct(v: Double?, signed: Boolean = true): String {
        if (v == null) return "—"
        val s = BigDecimal.valueOf(v).setScale(1, RoundingMode.HALF_UP).toPlainString()
        return if (signed && v > 0) "+$s%" else "$s%"
    }
}

data class Point(val day: String, val value: Double)
data class Debtor(val name: String, val amount: Double?, val lastPayment: String?)
data class Approval(
    val id: String, val type: String, val priority: String, val reason: String,
    val value: Double?, val createdAt: String?, val customer: String?, val agent: String?,
)
data class Signal(val severity: String, val title: String, val at: String?)

/** Snapshot i plotë nga owner-snapshot. Fushat që mungojnë mbeten null (jo 0). */
data class Snapshot(
    val owner: String?,
    val generatedAt: String?,
    val salesDate: String?,
    val debtsFreshness: String?,
    val health: Int?,
    val hSales: Int?, val hLiquidity: Int?, val hCollections: Int?,
    val salesToday: Double?, val salesLastWeek: Double?, val agentsToday: Int?,
    val mtd: Double?, val mtdDelta: Double?, val series: List<Point>,
    val recvTotal: Double?, val overdue: Double?, val overduePct: Double?, val overdueCustomers: Int?,
    val dso: Double?, val dsoTarget: Double?, val debtors: List<Debtor>,
    val stockLow: Int?, val pending: Int?, val approvals: List<Approval>,
    val signals7d: Int?, val signals: List<Signal>,
) {
    val salesDelta: Double?
        get() = if (salesToday != null && salesLastWeek != null && salesLastWeek > 0)
            (salesToday - salesLastWeek) / salesLastWeek * 100 else null

    companion object {
        private fun JSONObject?.d(k: String): Double? = if (this != null && has(k) && !isNull(k)) optDouble(k).takeIf { !it.isNaN() } else null
        private fun JSONObject?.i(k: String): Int? = d(k)?.toInt()
        private fun JSONObject?.s(k: String): String? = if (this != null && has(k) && !isNull(k)) optString(k) else null
        private fun JSONObject?.arr(k: String): JSONArray = this?.optJSONArray(k) ?: JSONArray()
        private inline fun <T> JSONArray.map(f: (JSONObject) -> T): List<T> = (0 until length()).map { f(getJSONObject(it)) }

        /** Hedh përjashtim nëse JSON-i nuk është snapshot i vlefshëm — atëherë cache-i i vjetër mbetet. */
        fun parse(raw: String): Snapshot {
            val j = JSONObject(raw)
            require(j.has("generated_at") && j.has("sales")) { "snapshot i pavlefshëm" }
            val h = j.optJSONObject("health"); val hc = h?.optJSONObject("components")
            val s = j.optJSONObject("sales"); val r = j.optJSONObject("receivables")
            val a = j.optJSONObject("approvals"); val g = j.optJSONObject("signals")
            val fr = j.optJSONObject("data_freshness")
            return Snapshot(
                owner = j.s("owner"), generatedAt = j.s("generated_at"),
                salesDate = fr.s("sales"), debtsFreshness = fr.s("debts"),
                health = h.i("score"), hSales = hc.i("shitje"), hLiquidity = hc.i("likuiditet"), hCollections = hc.i("arketime"),
                salesToday = s.d("today"), salesLastWeek = s.d("same_day_last_week_full"), agentsToday = s.i("agents_today"),
                mtd = s.d("mtd"), mtdDelta = s.d("mtd_delta_pct"),
                series = s.arr("series14").map { Point(it.optString("d"), it.optDouble("v")) },
                recvTotal = r.d("total"), overdue = r.d("overdue"), overduePct = r.d("overdue_pct"),
                overdueCustomers = r.i("overdue_customers"), dso = r.d("dso_days"), dsoTarget = r.d("dso_target"),
                debtors = r.arr("top_debtors").map { Debtor(it.optString("customer_name"), it.d("maturuar"), it.s("last_payment_date")) },
                stockLow = j.optJSONObject("stock").i("low_count"),
                pending = a.i("pending"),
                approvals = a.arr("top").map {
                    Approval(it.optString("id"), it.optString("action_type"), it.optString("priority"),
                        it.optString("reason"), it.d("estimated_value"), it.s("created_at"),
                        it.s("customer_name"), it.s("assigned_to_agent_code"))
                },
                signals7d = g.i("last_7d"),
                signals = g.arr("top").map { Signal(it.optString("severity"), it.optString("title"), it.s("detected_at")) },
            )
        }
    }
}
