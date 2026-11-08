package al.thana.widgets

import android.content.Context
import android.content.Intent
import android.net.Uri
import org.json.JSONObject
import java.io.File
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder

/**
 * Paneli i shitjeve (THA'NA Widgets, /w) në telefon. Lidhja = linku personal + PIN (i njëjti si në shfletues).
 * Sesioni (cookie 14 ditë) ruhet i enkriptuar në Android Keystore; të dhënat lexohen VETËM me lejet e këtij përdoruesi.
 * Shifrat i llogarit serveri; telefoni vetëm shfaq.
 */
object Panel {
    enum class State { OFF, OK, AUTH, NETWORK }

    private fun link(ctx: Context): PanelLink? =
        Secure.get(ctx, "panel_r")?.let { r -> Secure.get(ctx, "panel_s")?.let { s -> PanelLink(r, s) } }

    fun connected(ctx: Context) = link(ctx) != null && Secure.get(ctx, "panel_cookie") != null
    fun roleName(ctx: Context) = link(ctx)?.let { PanelLink.ROLE_NAME[it.role] }

    /** Hyrja: POST /api/login {pin, r, slug} → Set-Cookie. Kthen null kur hyn, ose mesazhin e gabimit. */
    fun login(ctx: Context, raw: String, pin: String): String? {
        val l = PanelLink.parse(raw) ?: return "Linku nuk është i THA'NA (duhet https://thana-shitje.vercel.app/…/w)."
        if (!Regex("^\\d{4,6}$").matches(pin)) return "PIN-i ka 4–6 shifra."
        return try {
            val c = (URL("https://${PanelLink.HOST}/api/login").openConnection() as HttpURLConnection).apply {
                requestMethod = "POST"; connectTimeout = 15_000; readTimeout = 20_000; instanceFollowRedirects = false
                setRequestProperty("Content-Type", "application/json"); doOutput = true
            }
            c.outputStream.use { it.write(JSONObject().put("pin", pin).put("r", l.role).put("slug", l.slug).toString().toByteArray()) }
            val code = c.responseCode
            if (code != 200) {
                val msg = runCatching { JSONObject(c.errorStream.bufferedReader().readText()).optString("msg") }.getOrNull()
                return msg?.takeIf { it.isNotBlank() } ?: "Hyrja dështoi ($code)."
            }
            val cookie = c.headerFields.entries.filter { it.key.equals("Set-Cookie", true) }.flatMap { it.value }
                .map { it.substringBefore(';') }.firstOrNull { it.contains("_${l.slug}=") } ?: return "Serveri nuk ktheu sesion."
            Secure.put(ctx, "panel_r", l.role); Secure.put(ctx, "panel_s", l.slug); Secure.put(ctx, "panel_cookie", cookie)
            File(ctx.filesDir, "panel").deleteRecursively()
            null
        } catch (e: Exception) { "S'ka lidhje me internetin." }
    }

    fun logout(ctx: Context) {
        listOf("panel_r", "panel_s", "panel_cookie").forEach { Secure.put(ctx, it, null) }
        File(ctx.filesDir, "panel").deleteRecursively()
    }

    /** Merr widget-et e rolit një nga një (DB ka limit lidhjesh). 401 → sesioni skadoi (hyr sërish me PIN). */
    fun fetch(ctx: Context): State {
        val l = link(ctx) ?: return State.OFF
        val cookie = Secure.get(ctx, "panel_cookie") ?: return State.OFF
        val dir = File(ctx.filesDir, "panel").apply { mkdirs() }
        var state = State.OK
        for (id in l.ids) {
            try {
                val q = "r=${l.role}&s=${l.slug}&id=${URLEncoder.encode(id, "UTF-8")}&period=month"
                val c = (URL("${l.base}/api/w?$q").openConnection() as HttpURLConnection).apply {
                    connectTimeout = 15_000; readTimeout = 25_000; instanceFollowRedirects = false
                    setRequestProperty("Cookie", cookie); setRequestProperty("Accept", "application/json")
                }
                when (c.responseCode) {
                    200 -> { val t = c.inputStream.bufferedReader().readText(); val tmp = File(dir, "$id.tmp"); tmp.writeText(t); tmp.renameTo(File(dir, "$id.json")) }
                    401 -> { Secure.put(ctx, "panel_cookie", null); return State.AUTH }
                    else -> state = State.NETWORK
                }
            } catch (e: Exception) { state = State.NETWORK }
        }
        File(dir, "_at").writeText(System.currentTimeMillis().toString())
        return state
    }

    /** JSON-i i fundit i një widget-i (ose null). */
    fun cached(ctx: Context, id: String): JSONObject? =
        runCatching { JSONObject(File(File(ctx.filesDir, "panel"), "$id.json").readText()) }.getOrNull()

    fun syncedAt(ctx: Context): Long? = runCatching { File(File(ctx.filesDir, "panel"), "_at").readText().toLong() }.getOrNull()

    /** Hap panelin në shfletues direkt te faqja e widget-it (#open=<id>). */
    fun openIntent(ctx: Context, id: String?): Intent {
        val l = link(ctx)
        val url = if (l == null) "https://${PanelLink.HOST}/" else l.base + (id?.let { "#open=$it" } ?: "")
        return Intent(Intent.ACTION_VIEW, Uri.parse(url)).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
    }
}
