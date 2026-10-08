package com.akash.aside.capture

import com.akash.aside.data.Kind

/** `j: ...`, `t: ...`, `n: ...` (or the long words) at the start of the text pick the type and are not saved. */
object Prefix {
    data class Result(val kind: String?, val text: String)

    private val rx = Regex("""^\s*(journal|j|task|t|note|n)\s*:\s*""", RegexOption.IGNORE_CASE)

    fun parse(raw: String): Result {
        val m = rx.find(raw) ?: return Result(null, raw)
        val kind = when (m.groupValues[1].lowercase()) {
            "journal", "j" -> Kind.JOURNAL
            "task", "t" -> Kind.TASK
            else -> Kind.NOTE
        }
        return Result(kind, raw.substring(m.range.last + 1))
    }
}
