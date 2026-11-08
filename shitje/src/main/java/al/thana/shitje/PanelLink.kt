package al.thana.shitje

/** Linku personal i panelit të shitjeve: https://thana-shitje.vercel.app/<p|s|d|b>/<slug>[/w...] (pa varësi Android → testohet në JVM). */
data class PanelLink(val role: String, val slug: String) {
    val base get() = "https://$HOST/$role/$slug/w"

    /** Widget-et që kanë kuptim për këtë rol (si te paneli). */
    val ids: List<String> get() = when (role) {   // të njëjtat karta si paneli i rolit
        "p" -> listOf("W1.route", "W2.plan", "W7.daily", "W14.tiers", "W12.stock", "W6.collect", "W5.tasks", "W9.chat", "W3.product", "W13.reports")
        "s" -> listOf("W8.team", "W14.tiers", "W5.tasks", "W6.collect", "W12.stock", "W9.chat", "W2.plan", "W3.product", "W13.reports")
        "d" -> listOf("W8.team", "W2.plan", "W14.tiers", "W5.tasks", "W6.collect", "W11.stockneg", "W9.chat", "W3.product", "W13.reports")
        else -> listOf("W10.distributor", "W14.tiers", "W12.stock", "W11.stockneg", "W2.plan", "W3.product", "W13.reports")
    }

    companion object {
        const val HOST = "thana-shitje.vercel.app"
        private val RE = Regex("""^https://thana-shitje\.vercel\.app/([psdb])/([A-Za-z0-9]{12,64})(?:/w)?/?(?:[?#].*)?$""")

        /** Pranon vetëm domain-in e THA'NA; çdo gjë tjetër → null. */
        fun parse(raw: String?): PanelLink? {
            val m = RE.matchEntire(raw?.trim() ?: return null) ?: return null
            return PanelLink(m.groupValues[1], m.groupValues[2])
        }

        val ROLE_NAME = mapOf("p" to "Agjent", "s" to "Supervizor", "d" to "Drejtor", "b" to "Distributor")
    }
}
