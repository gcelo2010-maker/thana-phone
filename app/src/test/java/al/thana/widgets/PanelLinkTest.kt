package al.thana.widgets

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class PanelLinkTest {
    @Test fun parsesDirectorLink() {
        val l = PanelLink.parse("https://thana-shitje.vercel.app/d/AbCdEfGhJkLm2345/w")!!
        assertEquals("d", l.role); assertEquals("AbCdEfGhJkLm2345", l.slug)
        assertEquals("https://thana-shitje.vercel.app/d/AbCdEfGhJkLm2345/w", l.base)
    }
    @Test fun acceptsWithoutWAndWithSpaces() = assertEquals("p", PanelLink.parse("  https://thana-shitje.vercel.app/p/AbCdEfGhJkLm2345/ ")!!.role)
    @Test fun rejectsOtherDomain() = assertNull(PanelLink.parse("https://evil.example/d/AbCdEfGhJkLm2345/w"))
    @Test fun rejectsLookalikeDomain() = assertNull(PanelLink.parse("https://thana-shitje.vercel.app.evil.com/d/AbCdEfGhJkLm2345/w"))
    @Test fun rejectsHttp() = assertNull(PanelLink.parse("http://thana-shitje.vercel.app/d/AbCdEfGhJkLm2345/w"))
    @Test fun rejectsShortSlug() = assertNull(PanelLink.parse("https://thana-shitje.vercel.app/d/short/w"))
    @Test fun rejectsBadRole() = assertNull(PanelLink.parse("https://thana-shitje.vercel.app/x/AbCdEfGhJkLm2345/w"))
    @Test fun idsPerRole() {
        assertEquals(listOf("W2.plan", "W6.collect", "W5.tasks", "W13.reports"), PanelLink("p", "x").ids)
        assertEquals(true, PanelLink("d", "x").ids.contains("W11.stockneg"))
        assertEquals(false, PanelLink("s", "x").ids.contains("W11.stockneg"))
    }
}
