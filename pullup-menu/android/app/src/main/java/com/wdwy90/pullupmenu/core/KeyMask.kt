package com.wdwy90.pullupmenu.core

/** Shows an API key without revealing it: only the last four characters, never the whole key. */
object KeyMask {
    private const val DOTS = "••••••••"

    fun mask(key: String?): String {
        val k = key?.trim().orEmpty()
        if (k.isEmpty()) return "Not set"
        // Short keys would be mostly revealed by their last four characters.
        return if (k.length < 12) DOTS else DOTS + k.takeLast(4)
    }

    /** What a screen reader says for [mask]: the same characters it shows, spelled out, and no more. */
    fun spoken(key: String?): String {
        val k = key?.trim().orEmpty()
        return when {
            k.isEmpty() -> "not set"
            k.length < 12 -> "hidden"
            else -> "hidden, ends in " + k.takeLast(4).toCharArray().joinToString(" ")
        }
    }

    /** Places API keys start with "AIza" and are 39 characters; anything else is almost certainly a paste error. */
    fun looksValid(key: String): Boolean {
        val k = key.trim()
        return k.length == 39 && k.startsWith("AIza") && k.all { it.isLetterOrDigit() || it == '-' || it == '_' }
    }
}
