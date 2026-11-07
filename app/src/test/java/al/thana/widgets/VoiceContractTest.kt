package al.thana.widgets

import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Kontrata klient ↔ voice-session v19 (WO-CLIENT-V094). Mutacionet që e bëjnë KUQ: flamuri `async_tools` i dërguar edhe për OpenAI
 * ose i hequr për Gemini; `scheduling` i dërguar në mënyrën e vjetër; parazgjedhje tjetër nga WHEN_IDLE; ri-lidhje pa pritje ose > 3.
 */
class VoiceContractTest {
    @Test fun geminiDeclaresAsyncTools() {
        val b = VoiceContract.sessionBody("0.9.5", "gemini")
        assertEquals("0.9.5", b.getString("client")); assertEquals("gemini", b.getString("engine"))
        assertTrue(b.getBoolean("async_tools"))
    }

    @Test fun openaiDoesNotDeclareAsyncTools() {
        val b = VoiceContract.sessionBody("0.9.5", "openai")
        assertFalse(b.has("async_tools"))
    }

    @Test fun serverDecidesAsync() {
        assertFalse(VoiceContract.asyncTools(null))
        assertFalse(VoiceContract.asyncTools(JSONObject("""{"engine":"gemini"}""")))               // serveri i vjetër / pa flamur → bllokues
        assertFalse(VoiceContract.asyncTools(JSONObject("""{"async_tools":false}""")))
        assertTrue(VoiceContract.asyncTools(JSONObject("""{"async_tools":true}""")))
    }

    @Test fun schedulingOnlyInAsyncMode() {
        assertNull(VoiceContract.schedulingFor(JSONObject("""{"async_tools":false}"""), "thana_mendo"))
        assertNull(VoiceContract.schedulingFor(null, "thana_mendo"))
    }

    @Test fun schedulingFromServerMapWithWhenIdleDefault() {
        val cfg = JSONObject("""{"async_tools":true,"function_response_scheduling":{"thana_mendo":"WHEN_IDLE"}}""")
        assertEquals("WHEN_IDLE", VoiceContract.schedulingFor(cfg, "thana_mendo"))
        assertEquals("WHEN_IDLE", VoiceContract.schedulingFor(cfg, "thana_panorama"))                // mungon në hartë → parazgjedhja
        assertEquals("INTERRUPT", VoiceContract.schedulingFor(JSONObject("""{"async_tools":true,"function_response_scheduling":{"x":"INTERRUPT"}}"""), "x"))
        assertEquals("WHEN_IDLE", VoiceContract.schedulingFor(JSONObject("""{"async_tools":true,"function_response_scheduling":{"x":"BOGUS"}}"""), "x"))
    }

    @Test fun toolResponseShape() {
        val legacy = VoiceContract.toolResponse("c1", "thana_mendo", "Pra 20,9 milion.", null)
        val fr = legacy.getJSONObject("toolResponse").getJSONArray("functionResponses").getJSONObject(0)
        assertEquals("c1", fr.getString("id")); assertEquals("thana_mendo", fr.getString("name"))
        assertEquals("Pra 20,9 milion.", fr.getJSONObject("response").getString("result"))
        assertFalse(fr.has("scheduling"))
        val async = VoiceContract.toolResponse("c2", "thana_mendo", "x", "WHEN_IDLE")
        assertEquals("WHEN_IDLE", async.getJSONObject("toolResponse").getJSONArray("functionResponses").getJSONObject(0).getString("scheduling"))
    }

    @Test fun reconnectBackoffAndCap() {
        assertEquals(3, VoiceContract.MAX_RECONNECTS)
        assertEquals(1000L, VoiceContract.backoffMs(1)); assertEquals(2000L, VoiceContract.backoffMs(2)); assertEquals(4000L, VoiceContract.backoffMs(3))
        assertEquals(4000L, VoiceContract.backoffMs(9))
        assertEquals(20_000L, VoiceContract.CONNECT_TIMEOUT_MS)
    }
}
