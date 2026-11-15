package com.akash.aside.usage

import com.akash.aside.data.UsageSession
import java.security.MessageDigest

/** What the phone reports, boiled down to what matters. */
data class RawEvent(val timeMs: Long, val type: Type, val pkg: String) {
    enum class Type { RESUME, PAUSE, SCREEN_OFF }
}

/**
 * Turns the phone's raw "app came to the front / went away / screen went off" events into sessions: one app in
 * front at a time, ignored apps never counted, tiny blips dropped, and a quick hop between two screens of the same
 * app joined back into one session. Pure, so it is tested without a phone.
 */
class SessionBuilder(
    private val ignored: (String) -> Boolean,
    private val minMs: Long = 3_000,
    private val joinGapMs: Long = 2_000,
) {
    /** `openSince` is the start of the app that is still in front at the end of the events (not a session yet). */
    data class Result(val closed: List<Span>, val openSince: Long?, val openPkg: String? = null)
    data class Span(val pkg: String, val startMs: Long, val endMs: Long)

    fun build(events: List<RawEvent>): Result {
        val out = ArrayList<Span>()
        var curPkg: String? = null
        var curStart = 0L

        fun close(at: Long) {
            val p = curPkg ?: return
            if (at - curStart >= minMs) out += Span(p, curStart, at)
            curPkg = null
        }

        for (e in events.sortedBy { it.timeMs }) {
            when (e.type) {
                RawEvent.Type.RESUME -> {
                    if (curPkg == e.pkg) continue
                    close(e.timeMs)
                    if (!ignored(e.pkg)) { curPkg = e.pkg; curStart = e.timeMs }
                }
                RawEvent.Type.PAUSE -> if (curPkg == e.pkg) close(e.timeMs)
                RawEvent.Type.SCREEN_OFF -> close(e.timeMs)
            }
        }
        return Result(join(out), if (curPkg != null) curStart else null, curPkg)
    }

    private fun join(spans: List<Span>): List<Span> {
        val merged = ArrayList<Span>()
        for (s in spans) {
            val last = merged.lastOrNull()
            if (last != null && last.pkg == s.pkg && s.startMs - last.endMs <= joinGapMs) merged[merged.size - 1] = last.copy(endMs = s.endMs)
            else merged += s
        }
        return merged
    }

    companion object {
        fun key(pkg: String, startMs: Long): String =
            MessageDigest.getInstance("SHA-1").digest("$pkg|$startMs".toByteArray()).joinToString("") { "%02x".format(it) }

        fun toSession(s: Span) = UsageSession(key(s.pkg, s.startMs), s.pkg, s.startMs, s.endMs, UsageSession.PENDING)
    }
}
