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

    /** Places API keys start with "AIza" and are 39 characters; anything else is almost certainly a paste error. */
    fun looksValid(key: String): Boolean {
        val k = key.trim()
        return k.length == 39 && k.startsWith("AIza") && k.all { it.isLetterOrDigit() || it == '-' || it == '_' }
    }
}
