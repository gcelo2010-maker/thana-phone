package al.thana.widgets

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.net.HttpURLConnection
import java.net.URL
import java.util.UUID

/**
 * Klienti i THA'NA. Android vetëm paraqet dhe kërkon; e vërteta mbetet te Supabase.
 * - lexim: Edge Function owner-snapshot (read-only)
 * - aprovime: RPC sv_action_approve / sv_action_reject (e njëjta rrugë si PWA; backend-i kyç veprimin dhe kontrollon statusin)
 * - AI: Edge Function brain-chat (SSE)
 */
object Repo {
    enum class Fetch { OK, AUTH, NETWORK, NO_SESSION }
    data class Result(val ok: Boolean, val message: String)

    private const val CACHE = "snapshot.json"

    fun token(ctx: Context) = Secure.get(ctx, "token")
    fun ownerName(ctx: Context) = Secure.get(ctx, "name")

    fun logout(ctx: Context) {
        Secure.clear(ctx)
        File(ctx.filesDir, CACHE).delete()   // vlerat e mbrojtura hiqen edhe nga widget-et
        File(ctx.filesDir, MEMO).delete()    // kujtesa e bisedës me zë
    }

    const val MEMO = "voice_memo.json"

    /** Kujtesa e bisedës me zë (vetëm në telefon): pyetjet dhe përgjigjet e fundit, mbahet edhe pas mbylljes. */
    fun loadMemo(ctx: Context): List<Turn> = runCatching {
        val a = JSONArray(File(ctx.filesDir, MEMO).readText())
        (0 until a.length()).map { a.getJSONObject(it).let { o -> Turn(o.getBoolean("u"), o.getString("t")) } }
    }.getOrElse { emptyList() }

    fun saveMemo(ctx: Context, memo: List<Turn>) {
        val a = JSONArray()
        memo.takeLast(40).forEach { a.put(JSONObject().put("u", it.mine).put("t", it.text.take(2000))) }
        runCatching {
            val tmp = File(ctx.filesDir, "$MEMO.tmp"); tmp.writeText(a.toString()); tmp.renameTo(File(ctx.filesDir, MEMO))
        }
    }

    private fun open(path: String, method: String = "GET"): HttpURLConnection =
        (URL("${BuildConfig.SUPABASE_URL}$path").openConnection() as HttpURLConnection).apply {
            requestMethod = method
            connectTimeout = 15_000; readTimeout = 30_000
            setRequestProperty("apikey", BuildConfig.ANON_KEY)
            setRequestProperty("Authorization", "Bearer ${BuildConfig.ANON_KEY}")
            setRequestProperty("Content-Type", "application/json")
        }

    private fun HttpURLConnection.send(body: JSONObject) {
        doOutput = true
        outputStream.use { it.write(body.toString().toByteArray()) }
    }

    private fun HttpURLConnection.text(): String =
        (if (responseCode in 200..299) inputStream else errorStream)?.bufferedReader()?.use { it.readText() } ?: ""

    /** null = sukses; përndryshe mesazhi i gabimit për përdoruesin. */
    fun login(ctx: Context, username: String, pin: String): String? = try {
        val c = open("/rest/v1/rpc/pin_login", "POST")
        c.send(JSONObject().put("p_username", username.trim()).put("p_pin", pin))
        val j = JSONObject(c.text())
        when {
            !j.optBoolean("success") -> j.optString("error", "Hyrja dështoi.")
            j.optJSONObject("user")?.optString("role") !in setOf("director", "admin") ->
                "Kjo llogari nuk ka rol drejtori ose administratori."
            else -> {
                Secure.put(ctx, "token", j.getString("token"))
                Secure.put(ctx, "name", j.optJSONObject("user")?.optString("full_name"))
                null
            }
        }
    } catch (e: Exception) { "Nuk u lidh me THA'NA. Kontrollo internetin." }

    fun fetch(ctx: Context): Fetch {
        val tok = token(ctx) ?: return Fetch.NO_SESSION
        return try {
            val c = open("/functions/v1/owner-snapshot")
            c.setRequestProperty("x-session-token", tok)
            when (c.responseCode) {
                in 200..299 -> {
                    val body = c.text()
                    Snapshot.parse(body)                       // validon para se të shkruajë
                    val tmp = File(ctx.filesDir, "$CACHE.tmp")
                    tmp.writeText(body)
                    tmp.renameTo(File(ctx.filesDir, CACHE))    // shkrim atomik
                    Fetch.OK
                }
                401, 403 -> { logout(ctx); Fetch.AUTH }
                else -> Fetch.NETWORK
            }
        } catch (e: Exception) { Fetch.NETWORK }
    }

    fun cached(ctx: Context): Snapshot? = try {
        File(ctx.filesDir, CACHE).takeIf { it.exists() }?.readText()?.let { Snapshot.parse(it) }
    } catch (e: Exception) { null }

    /** Aprovimi kalon te backend-i; suksesi tregohet vetëm kur backend-i e konfirmon. */
    fun decide(ctx: Context, actionId: String, approve: Boolean, note: String): Result = try {
        val tok = token(ctx) ?: return Result(false, "Sesioni ka mbaruar. Hyr përsëri.")
        val c = if (approve) {
            open("/rest/v1/rpc/sv_action_approve", "POST").also {
                it.send(JSONObject().put("p_session_token", tok).put("p_action_id", actionId)
                    .put("p_modifications", JSONObject.NULL).put("p_note", note.ifBlank { "Aprovuar nga THA'NA Phone" }))
            }
        } else {
            open("/rest/v1/rpc/sv_action_reject", "POST").also {
                it.send(JSONObject().put("p_session_token", tok).put("p_action_id", actionId)
                    .put("p_reason", note.ifBlank { "Refuzuar nga THA'NA Phone" }))
            }
        }
        val code = c.responseCode
        val body = c.text()
        val j = runCatching { JSONObject(body) }.getOrNull()
        when {
            code !in 200..299 -> Result(false, j?.optString("message") ?: "Backend-i nuk e pranoi kërkesën ($code).")
            j == null -> Result(false, "Përgjigje e papritur nga backend-i.")
            j.optString("status") == "error" -> Result(false, j.optString("message", "Veprimi nuk u krye."))
            else -> Result(true, if (approve) "U aprovua · konfirmuar nga THA'NA" else "U refuzua · konfirmuar nga THA'NA")
        }
    } catch (e: Exception) { Result(false, "Nuk u lidh. Asgjë nuk u ndryshua.") }

    /** Hap një bisedë live me zë: kthen çelësin e përkohshëm Gemini + konfigurimin, ose {"error": ...}. */
    fun voiceSession(ctx: Context, engine: String = "openai"): JSONObject = try {
        val tok = token(ctx) ?: throw IllegalStateException("Sesioni ka mbaruar. Hyr përsëri.")
        val c = open("/functions/v1/voice-session", "POST")
        c.setRequestProperty("x-session-token", tok)
        c.send(JSONObject().put("client", BuildConfig.VERSION_NAME).put("engine", engine))
        val body = c.text()
        runCatching { JSONObject(body) }.getOrElse { JSONObject().put("error", "Përgjigje e papritur (${c.responseCode}).") }
            .also { if (c.responseCode == 401) logout(ctx) }
    } catch (e: IllegalStateException) { JSONObject().put("error", e.message)
    } catch (e: Exception) { JSONObject().put("error", "Nuk u lidh me THA'NA. Kontrollo internetin.") }

    /** Mendja e bisedës me zë (voice-tool → Claude + THA'NA + web). Kthen tekstin që do lexohet me zë. */
    fun voiceTool(ctx: Context, name: String, args: JSONObject, history: List<Turn>): String = try {
        val tok = token(ctx) ?: throw IllegalStateException("Sesioni ka mbaruar. Hyr përsëri.")
        val c = open("/functions/v1/voice-tool", "POST")
        c.setRequestProperty("x-session-token", tok)
        c.readTimeout = 90_000
        val h = JSONArray()
        history.forEach { h.put(JSONObject().put("role", if (it.mine) "user" else "assistant").put("text", it.text)) }
        c.send(JSONObject().put("name", name).put("args", args).put("history", h))
        val j = runCatching { JSONObject(c.text()) }.getOrNull()
        if (c.responseCode == 401) logout(ctx)
        j?.optString("result")?.takeIf { it.isNotBlank() } ?: j?.optString("error")?.takeIf { it.isNotBlank() }
            ?: "Nuk mora përgjigje tani. Provo përsëri."
    } catch (e: IllegalStateException) { e.message ?: "Sesioni ka mbaruar."
    } catch (e: Exception) { "Nuk u lidh me THA'NA. Kontrollo internetin." }

    /** Pyetje te THA'NA AI. onText merr tekstin e grumbulluar ndërkohë që vjen. Kthen session_id. */
    fun ask(ctx: Context, question: String, sessionId: String?, onText: (String) -> Unit): String? {
        val tok = token(ctx) ?: run { onText("Sesioni ka mbaruar. Hyr përsëri."); return sessionId }
        return try {
            val c = open("/functions/v1/brain-chat", "POST")
            c.readTimeout = 120_000
            c.setRequestProperty("x-session-token", tok)
            c.setRequestProperty("Accept", "text/event-stream")
            val body = JSONObject().put("message", question).put("session_token", tok).put("stream", true)
                .put("client_request_id", UUID.randomUUID().toString())
            sessionId?.let { body.put("session_id", it) }
            c.send(body)
            if (c.responseCode !in 200..299) {
                val j = runCatching { JSONObject(c.text()) }.getOrNull()
                onText(j?.optString("reply")?.takeIf { it.isNotBlank() } ?: "THA'NA nuk u përgjigj (${c.responseCode}).")
                return sessionId
            }
            if (c.contentType?.contains("json") == true) {        // backend-i ktheu JSON në vend të SSE
                val j = JSONObject(c.text())
                onText(j.optString("reply").ifBlank { "THA'NA nuk ktheu përgjigje." })
                return j.optString("session_id").ifBlank { sessionId }
            }
            var sid = sessionId
            val sb = StringBuilder()
            var event = ""
            c.inputStream.bufferedReader().useLines { lines ->
                for (line in lines) {
                    when {
                        line.startsWith("event:") -> event = line.removePrefix("event:").trim()
                        line.startsWith("data:") -> {
                            val j = runCatching { JSONObject(line.removePrefix("data:").trim()) }.getOrNull() ?: continue
                            when (event) {
                                "session", "done" -> j.optString("session_id").takeIf { it.isNotBlank() }?.let { sid = it }
                                "text" -> { sb.append(j.optString("text")); onText(sb.toString()) }
                                "error" -> onText(j.optString("message", j.optString("error", "Gabim nga THA'NA AI.")))
                            }
                        }
                    }
                }
            }
            if (sb.isEmpty()) onText("THA'NA nuk ktheu përgjigje.")
            sid
        } catch (e: Exception) { onText("Nuk u lidh me THA'NA AI."); sessionId }
    }
}
