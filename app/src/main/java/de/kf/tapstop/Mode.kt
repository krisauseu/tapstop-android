package de.kf.tapstop

// Persisted names BLITZTEXT and PLUS also identify historical v0.5 statistics.
enum class Mode(@param:androidx.annotation.StringRes val labelRes: Int, val symbol: String) {
    BLITZTEXT(R.string.mode_raw, "≡"), PLUS(R.string.mode_plus, "+"),
    CHAT(R.string.mode_chat, "💬"), FORMAL(R.string.mode_formal, "✉");

    val usesRewrite: Boolean get() = this != BLITZTEXT
}
