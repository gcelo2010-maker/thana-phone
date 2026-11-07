package al.thana.widgets

import org.json.JSONArray
import org.json.JSONObject

/**
 * Kontrata klient ↔ `voice-session` / Gemini Live (THA'NA OS, deka-portal `supabase/functions/voice-session/index.ts`, prod v19+).
 * Logjikë e pastër, pa Android, që testohet me JUnit (VoiceContractTest).
 *
 * v0.9.5 (WO-CLIENT-V094, 07/11/2026): serveri hap mjetet NON_BLOCKING + prompt-in e lirë VETËM kur klienti dërgon
 * `async_tools: true` — ky skedar është vendi i vetëm ku deklarohet ajo aftësi dhe ku lexohet përgjigjja e serverit.
 */
object VoiceContract {
    /** Ri-lidhje automatike: maksimumi 3 tentativa (më parë 5, pa pritje) — pastaj mesazh në shqip, kurrë lak i heshtur. */
    const val MAX_RECONNECTS = 3
    /** Lidhja që nuk hapet brenda kësaj kohe kthehet në Idle (defekti i 07/11 01:24: aplikacioni mbetej «duke menduar»). */
    const val CONNECT_TIMEOUT_MS = 20_000L
    const val DEFAULT_SCHEDULING = "WHEN_IDLE"
    private val SCHEDULINGS = setOf("WHEN_IDLE", "INTERRUPT", "SILENT")

    /** Trupi i kërkesës `voice-session`. `async_tools: true` vetëm për Gemini: rruga OpenAI nuk ka mjete asinkrone. */
    fun sessionBody(version: String, engine: String): JSONObject =
        JSONObject().put("client", version).put("engine", engine).also { if (engine == "gemini") it.put("async_tools", true) }

    /** A e hapi serveri mënyrën asinkrone për këtë sesion (vetëm kur klienti e kërkoi dhe serveri e pranoi). */
    fun asyncTools(cfg: JSONObject?): Boolean = cfg?.optBoolean("async_tools", false) == true

    /**
     * `scheduling` për rezultatin e një mjeti: nga harta `function_response_scheduling` e serverit, parazgjedhje WHEN_IDLE
     * (zëri mbaron fjalinë që po thotë, pastaj e lexon). Null kur sesioni s'është asinkron: fusha nuk dërgohet fare (sjellja e vjetër).
     */
    fun schedulingFor(cfg: JSONObject?, tool: String): String? {
        if (!asyncTools(cfg)) return null
        val s = cfg?.optJSONObject("function_response_scheduling")?.optString(tool, "").orEmpty()
        return if (s in SCHEDULINGS) s else DEFAULT_SCHEDULING
    }

    /** Mesazhi `toolResponse` i Gemini Live për një mjet të përfunduar. */
    fun toolResponse(id: String, name: String, result: String, scheduling: String?): JSONObject {
        val fr = JSONObject().put("id", id).put("name", name).put("response", JSONObject().put("result", result))
        scheduling?.let { fr.put("scheduling", it) }
        return JSONObject().put("toolResponse", JSONObject().put("functionResponses", JSONArray().put(fr)))
    }

    /** Pritja para ri-lidhjes `attempt` (1-based): 1 s, 2 s, 4 s. */
    fun backoffMs(attempt: Int): Long = 1000L shl (attempt - 1).coerceIn(0, 2)
}
