package al.thana.shitje

import android.content.Context
import android.webkit.CookieManager
import org.json.JSONObject
import java.io.File
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder

/**
 * THA'NA Shitje: lidhja me panelin personal /w. Hyrja = linku + PIN (POST /api/login, si në shfletues).
 * Sesioni ruhet i enkriptuar (Keystore) dhe ndahet me WebView-n; widget-et lexojnë me të njëjtat leje si paneli.
 */
object Panel {
    enum class State { OFF, OK, AUTH, NETWORK }

    fun link(ctx: Context): PanelLink? =
        Secure.get(ctx, "r")?.let { r -> Secure.get(ctx, "s")?.let { s -> PanelLink(r, s) } }

    fun connected(ctx: Context) = link(ctx) != null
    fun roleName(ctx: Context) = link(ctx)?.let { PanelLink.ROLE_NAME[it.role] }

    /** Cookie aktual: ai i WebView-s (rifreskohet kur përdoruesi rihyn me PIN aty), ose i ruajturi. */
    private fun cookie(ctx: Context, l: PanelLink): String? {
        val web = runCatching { CookieManager.getInstance().getCookie(l.base) }.getOrNull()
            ?.split(';')?.map { it.trim() }?.firstOrNull { it.substringBefore('=').endsWith("_${l.slug}") }
        return web ?: Secure.get(ctx, "cookie")
    }

    /** null = u lidh; përndryshe mesazhi i gabimit. */
    fun login(ctx: Context, raw: String, pin: String): String? {
        val l = PanelLink.parse(raw) ?: return "Linku nuk është i THA'NA. Duhet të fillojë me https://thana-shitje.vercel.app/"
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
            val raw = c.headerFields.entries.filter { it.key.equals("Set-Cookie", true) }.flatMap { it.value }
                .firstOrNull { it.substringBefore('=').endsWith("_${l.slug}") } ?: return "Serveri nuk ktheu sesion."
            Secure.put(ctx, "r", l.role); Secure.put(ctx, "s", l.slug); Secure.put(ctx, "cookie", raw.substringBefore(';'))
            CookieManager.getInstance().apply { setAcceptCookie(true); setCookie("https://${PanelLink.HOST}", raw); flush() }
            File(ctx.filesDir, "panel").deleteRecursively()
            null
        } catch (e: Exception) { "S'ka lidhje me internetin." }
    }

    fun logout(ctx: Context) {
        Secure.clear(ctx)
        CookieManager.getInstance().apply { removeAllCookies(null); flush() }
        File(ctx.filesDir, "panel").deleteRecursively()
    }

    /** Widget-et e rolit, një nga një (DB ka limit lidhjesh). */
    fun fetch(ctx: Context): State {
        val l = link(ctx) ?: return State.OFF
        val ck = cookie(ctx, l) ?: return State.AUTH
        val dir = File(ctx.filesDir, "panel").apply { mkdirs() }
        var state = State.OK
        for (id in l.ids) {
            try {
                val q = "r=${l.role}&s=${l.slug}&id=${URLEncoder.encode(id, "UTF-8")}&period=month"
                val c = (URL("${l.base}/api/w?$q").openConnection() as HttpURLConnection).apply {
                    connectTimeout = 15_000; readTimeout = 25_000; instanceFollowRedirects = false
                    setRequestProperty("Cookie", ck); setRequestProperty("Accept", "application/json")
                }
                when (c.responseCode) {
                    200 -> { val t = c.inputStream.bufferedReader().readText(); val tmp = File(dir, "$id.tmp"); tmp.writeText(t); tmp.renameTo(File(dir, "$id.json")) }
                    401 -> { File(dir, "_auth").writeText("1"); return State.AUTH }
                    else -> state = State.NETWORK
                }
            } catch (e: Exception) { state = State.NETWORK }
        }
        File(dir, "_auth").delete()
        File(dir, "_at").writeText(System.currentTimeMillis().toString())
        return state
    }

    fun expired(ctx: Context) = File(File(ctx.filesDir, "panel"), "_auth").exists()
    fun cached(ctx: Context, id: String): JSONObject? = runCatching { JSONObject(File(File(ctx.filesDir, "panel"), "$id.json").readText()) }.getOrNull()
    fun syncedAt(ctx: Context): Long? = runCatching { File(File(ctx.filesDir, "panel"), "_at").readText().toLong() }.getOrNull()
}
