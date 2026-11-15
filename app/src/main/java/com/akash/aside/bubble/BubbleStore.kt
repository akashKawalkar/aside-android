package com.akash.aside.bubble

import android.content.Context
import com.akash.aside.Prefs
import org.json.JSONArray
import org.json.JSONObject
import java.time.LocalDate

/** What the bubble remembers between runs: today's plan, the sleep guess, which sheets were answered. */
object BubbleStore {
    private fun sp(c: Context) = Prefs.raw(c)

    // --- settings ---
    fun enabled(c: Context) = sp(c).getBoolean("bubble_enabled", true)
    fun setEnabled(c: Context, v: Boolean) = sp(c).edit().putBoolean("bubble_enabled", v).apply()

    /** The apps whose time counts toward the amber and red ring. */
    fun flagged(c: Context): Set<String> = sp(c).getStringSet("flagged_apps", null) ?: BubbleLogic.DEFAULT_FLAGGED
    fun setFlagged(c: Context, v: Set<String>) = sp(c).edit().putStringSet("flagged_apps", v).apply()

    // --- plan blocks (fetched with every sync, so the "Plan now" line works offline) ---
    fun plan(c: Context): List<PlanBlock> = runCatching {
        val arr = JSONArray(sp(c).getString("plan_cache", "[]"))
        (0 until arr.length()).map { arr.getJSONObject(it).let { o -> PlanBlock(o.getString("t"), o.getLong("s"), o.getLong("e")) } }
    }.getOrDefault(emptyList())

    fun setPlan(c: Context, blocks: List<PlanBlock>) {
        val arr = JSONArray()
        for (b in blocks) arr.put(JSONObject().put("t", b.title).put("s", b.startMs).put("e", b.endMs))
        sp(c).edit().putString("plan_cache", arr.toString()).apply()
    }

    // --- sleep guess, for the morning sheet ---
    class Sleep(val day: LocalDate, val startMs: Long, val wakeMs: Long)

    /** The server's guess, but only if it was made for `day`. */
    fun sleepFor(c: Context, day: LocalDate): Sleep? {
        val p = sp(c)
        if (p.getString("sleep_day", null) != day.toString()) return null
        val s = p.getLong("sleep_start", 0L)
        val w = p.getLong("sleep_wake", 0L)
        return if (s > 0 && w > s) Sleep(day, s, w) else null
    }

    fun setSleep(c: Context, day: LocalDate, startMs: Long, wakeMs: Long) =
        sp(c).edit().putString("sleep_day", day.toString()).putLong("sleep_start", startMs).putLong("sleep_wake", wakeMs).apply()

    /** The last day a sleep guess was asked for, so the morning does not ask the server every 15 minutes once it has an answer. */
    fun sleepAskedDay(c: Context): String? = sp(c).getString("sleep_asked", null)
    fun setSleepAskedDay(c: Context, d: String) = sp(c).edit().putString("sleep_asked", d).apply()

    // --- anomaly question ---
    fun anomalyDay(c: Context): LocalDate? = sp(c).getString("anomaly_day", null)?.let(LocalDate::parse)
    fun setAnomalyDay(c: Context, d: String?) = sp(c).edit().apply { if (d == null) remove("anomaly_day") else putString("anomaly_day", d) }.apply()
    fun anomalyAnswered(c: Context): LocalDate? = sp(c).getString("anomaly_answered", null)?.let(LocalDate::parse)
    fun setAnomalyAnswered(c: Context, d: LocalDate) = sp(c).edit().putString("anomaly_answered", d.toString()).apply()
    fun anomalyWaiting(c: Context): Boolean = anomalyDay(c)?.let { it != anomalyAnswered(c) } ?: false

    // --- daily sheets answered ---
    fun morningDone(c: Context): LocalDate? = sp(c).getString("morning_done", null)?.let(LocalDate::parse)
    fun eveningDone(c: Context): LocalDate? = sp(c).getString("evening_done", null)?.let(LocalDate::parse)
    fun setMorningDone(c: Context, d: LocalDate) = sp(c).edit().putString("morning_done", d.toString()).apply()
    fun setEveningDone(c: Context, d: LocalDate) = sp(c).edit().putString("evening_done", d.toString()).apply()

    // --- distraction prompts today ---
    fun promptsToday(c: Context, day: LocalDate): Int =
        if (sp(c).getString("prompt_day", null) == day.toString()) sp(c).getInt("prompt_count", 0) else 0

    fun addPrompt(c: Context, day: LocalDate) =
        sp(c).edit().putString("prompt_day", day.toString()).putInt("prompt_count", promptsToday(c, day) + 1).apply()

    /** After an answer the ring starts counting from zero again. */
    fun ringResetAt(c: Context) = sp(c).getLong("ring_reset_at", 0L)
    fun setRingResetAt(c: Context, v: Long) = sp(c).edit().putLong("ring_reset_at", v).apply()
}
