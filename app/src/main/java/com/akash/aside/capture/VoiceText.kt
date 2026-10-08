package com.akash.aside.capture

/** Puts spoken words after whatever was already written, with one space between and no stray spaces at the ends. */
object VoiceText {
    fun merge(base: String, spoken: String): String {
        val said = spoken.trim()
        if (said.isEmpty()) return base
        val head = base.trimEnd()
        return if (head.isEmpty()) said else "$head $said"
    }
}
