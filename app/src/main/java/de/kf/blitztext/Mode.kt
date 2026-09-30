package de.kf.blitztext

// Persisted names BLITZTEXT and PLUS also identify historical v0.5 statistics.
enum class Mode(val label: String, val symbol: String) {
    BLITZTEXT("Roh", "≡"), PLUS("Plus", "+"), CHAT("Chat", "💬"), FORMAL("Formal", "✉");

    val usesRewrite: Boolean get() = this != BLITZTEXT
}
