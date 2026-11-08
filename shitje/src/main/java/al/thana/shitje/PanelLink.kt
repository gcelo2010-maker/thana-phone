package al.thana.shitje

/** Linku personal i panelit të shitjeve: https://thana-shitje.vercel.app/<p|s|d|b>/<slug>[/w...] (pa varësi Android → testohet në JVM). */
data class PanelLink(val role: String, val slug: String) {
    val base get() = "https://$HOST/$role/$slug/w"

    /** Widget-et që kanë kuptim për këtë rol (si te paneli). */
    val ids: List<String> get() = when (role) {
        "p" -> listOf("W2.plan", "W6.collect", "W5.tasks", "W13.reports")
        "s" -> listOf("W2.plan", "W8.team", "W6.collect", "W5.tasks", "W13.reports")
        "d" -> listOf("W2.plan", "W8.team", "W11.stockneg", "W6.collect", "W5.tasks", "W13.reports")
        else -> listOf("W2.plan", "W11.stockneg", "W13.reports")
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
