package al.thana.widgets

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class MoneyTest {
    @Test fun full() = assertEquals("3,214,500 L", Money.full(3214500.0))
    @Test fun fullRoundsHalfUp() = assertEquals("981,024 L", Money.full(981023.95))
    @Test fun compactMillions() = assertEquals("3.2M L", Money.compact(3214500.0))
    @Test fun compactSmallStaysFull() = assertEquals("620,000 L", Money.compact(620000.0))
    @Test fun zeroIsRealZero() = assertEquals("0 L", Money.full(0.0))
    @Test fun nullIsDash() = assertEquals("—", Money.full(null))
    @Test fun pctSigned() = assertEquals("+8.2%", Money.pct(8.2))
    @Test fun pctNegative() = assertEquals("-47.4%", Money.pct(-47.4))
}

class SnapshotParseTest {
    private val sample = """
      {"owner":"Gentian Celo","generated_at":"2026-10-10T15:00:00Z",
       "data_freshness":{"sales":"2026-10-10","debts":"2026-10-06T06:53:53Z"},
       "health":{"score":0,"components":{"shitje":3,"likuiditet":45,"arketime":25}},
       "sales":{"today":981023.95,"same_day_last_week_full":1974929.09,"agents_today":6,"mtd":20973220,"mtd_delta_pct":-47.4,
                "series14":[{"d":"2026-10-09","v":1471093},{"d":"2026-10-10","v":981024}]},
       "receivables":{"total":150699644,"overdue":83213423,"overdue_pct":55.2,"overdue_customers":239,"dso_days":177,"dso_target":45,
                      "top_debtors":[{"customer_name":"TCC","maturuar":26106009,"last_payment_date":"2025-06-04"}]},
       "stock":{"low_count":297},
       "approvals":{"pending":388,"top":[{"id":"a1","action_type":"credit_critical_director","priority":"critical","reason":"x","estimated_value":null}]},
       "signals":{"last_7d":144,"top":[{"severity":"warn","title":"Rënie xhiro","detected_at":"2026-10-10T06:00:01Z"}]}}
    """.trimIndent()

    @Test fun parsesNumbers() {
        val s = Snapshot.parse(sample)
        assertEquals(981023.95, s.salesToday!!, 0.001)
        assertEquals(388, s.pending)
        assertEquals(2, s.series.size)
        assertEquals("TCC", s.debtors.first().name)
    }

    @Test fun healthZeroIsZeroNotMissing() = assertEquals(0, Snapshot.parse(sample).health)

    @Test fun missingValueStaysNull() {
        val s = Snapshot.parse("""{"generated_at":"x","sales":{}}""")
        assertNull(s.salesToday); assertNull(s.health); assertNull(s.salesDelta)
    }

    @Test fun nullEstimatedValueStaysNull() = assertNull(Snapshot.parse(sample).approvals.first().value)

    @Test(expected = IllegalArgumentException::class)
    fun rejectsInvalidSnapshot() { Snapshot.parse("""{"error":"x"}""") }

    @Test fun deltaVsLastWeek() = assertEquals(-50.3, Snapshot.parse(sample).salesDelta!!, 0.1)
}
