package com.akash.aside.capture

/** Word search: an item matches when it contains ANY of the typed words (case-insensitive). Empty search matches all. */
object Search {
    fun words(query: String): List<String> =
        query.lowercase().split(Regex("\\s+")).filter { it.isNotBlank() }

    fun matchesAny(text: String, query: String): Boolean {
        val ws = words(query)
        if (ws.isEmpty()) return true
        val t = text.lowercase()
        return ws.any { t.contains(it) }
    }
}
