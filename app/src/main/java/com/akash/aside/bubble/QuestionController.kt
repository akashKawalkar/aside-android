package com.akash.aside.bubble

import android.app.AlertDialog
import android.app.TimePickerDialog
import android.content.Context
import android.content.pm.PackageManager
import android.graphics.PixelFormat
import android.os.Handler
import android.os.Looper
import android.text.InputType
import android.view.ContextThemeWrapper
import android.view.Gravity
import android.view.KeyEvent
import android.view.View
import android.view.ViewGroup
import android.view.WindowManager
import android.view.inputmethod.EditorInfo
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.TextView
import com.akash.aside.capture.VoiceText
import com.akash.aside.overlay.Ui
import com.akash.aside.overlay.VoiceInput
import org.json.JSONArray
import org.json.JSONObject
import java.time.Instant
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.LocalTime
import java.time.ZoneId
import java.time.format.DateTimeFormatter

/** What the sheet asks. Each carries what it needs to draw itself. */
sealed interface Ask {
    data class Distraction(val planLine: String, val minutes: Int, val apps: String, val level: String) : Ask
    data class Sleep(val day: LocalDate, val guessStartMs: Long?, val guessWakeMs: Long?) : Ask
    data class Evening(val day: LocalDate) : Ask
    data class Anomaly(val day: LocalDate) : Ask
    data class TaskCheck(val title: String, val startMs: Long, val slipCount: Int) : Ask
    data class Debrief(val title: String, val endMs: Long) : Ask
}

/**
 * The question sheets, opened by tapping a glowing dot. Plain Views over any app, like the input window.
 * Closing without answering (tap outside, Back) saves nothing; the glow stays until it is answered or dismissed.
 * `onAnswer(kind, payload)` is called once, when the user answers; `onNotNow(ask)` when they choose "Not now".
 */
class QuestionController(
    private val ctx: Context,
    private val wm: WindowManager,
    private val onAnswer: (kind: String, payload: JSONObject) -> Unit,
    private val onNotNow: (Ask) -> Unit,
    private val onClosed: () -> Unit,
) {
    private val handler = Handler(Looper.getMainLooper())
    private val zone: ZoneId get() = ZoneId.systemDefault()
    private val clock = DateTimeFormatter.ofPattern("HH:mm")

    private var root: FrameLayout? = null
    private var voice: VoiceInput? = null
    private var note: EditText? = null
    private var speakStatus: TextView? = null
    private var voiceBase = ""

    val isShowing get() = root != null

    private class Root(ctx: Context, val onBack: () -> Unit) : FrameLayout(ctx) {
        override fun dispatchKeyEvent(e: KeyEvent): Boolean {
            if (e.keyCode == KeyEvent.KEYCODE_BACK) {
                if (e.action == KeyEvent.ACTION_UP) onBack()
                return true
            }
            return super.dispatchKeyEvent(e)
        }
    }

    fun show(ask: Ask) {
        if (root != null) return
        val card = LinearLayout(ctx).apply {
            orientation = LinearLayout.VERTICAL
            background = Ui.rounded(Ui.CARD, Ui.dp(ctx, 20))
            setPadding(Ui.dp(ctx, 16), Ui.dp(ctx, 14), Ui.dp(ctx, 16), Ui.dp(ctx, 14))
            isClickable = true
        }
        when (ask) {
            is Ask.Distraction -> distraction(card, ask)
            is Ask.Sleep -> sleep(card, ask)
            is Ask.Evening -> evening(card, ask)
            is Ask.Anomaly -> anomaly(card, ask)
            is Ask.TaskCheck -> taskCheck(card, ask)
            is Ask.Debrief -> debrief(card, ask)
        }
        card.addView(text("Not now", 14f, Ui.MUTED).apply {
            gravity = Gravity.END
            setPadding(Ui.dp(ctx, 8), Ui.dp(ctx, 10), Ui.dp(ctx, 8), Ui.dp(ctx, 4))
            setOnClickListener { onNotNow(ask); dismiss() }
        }, lp(top = 8))
        val r = Root(ctx) { dismiss() }
        r.setBackgroundColor(Ui.SCRIM)
        r.setOnClickListener { dismiss() }
        r.addView(card, FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT, Gravity.TOP).apply {
            setMargins(Ui.dp(ctx, 12), Ui.statusBarHeight(ctx) + Ui.dp(ctx, 8), Ui.dp(ctx, 12), 0)
        })
        val params = WindowManager.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT,
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
            WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN,
            PixelFormat.TRANSLUCENT,
        ).apply { softInputMode = WindowManager.LayoutParams.SOFT_INPUT_STATE_HIDDEN or WindowManager.LayoutParams.SOFT_INPUT_ADJUST_RESIZE }
        wm.addView(r, params)
        root = r
    }

    /** Closes without saving anything. */
    fun dismiss() {
        val r = root ?: return
        handler.removeCallbacksAndMessages(null)
        voice?.stop(); voice = null
        runCatching { wm.removeView(r) }
        root = null; note = null; speakStatus = null
        onClosed()
    }

    private fun answer(kind: String, payload: JSONObject) {
        onAnswer(kind, payload)
        dismiss()
    }

    // --- distraction ---

    private fun distraction(card: LinearLayout, a: Ask.Distraction) {
        card.addView(text(a.planLine, 15f, Ui.TEXT))
        card.addView(text("${a.apps}: ${a.minutes} min", 15f, Ui.MUTED), lp(top = 4))
        val chips = row()
        val reasons = listOf("break" to "Break", "avoiding" to "Avoiding something", "useful" to "Actually useful", "other" to "Other")
        // Two rows of two keep the labels readable on a narrow phone.
        card.addView(chips, lp(top = 12))
        val second = row()
        card.addView(second, lp(top = 8))
        reasons.forEachIndexed { i, (key, label) ->
            val c = chip(label) {
                val p = JSONObject().put("reason", key).put("minutes", a.minutes).put("apps", a.apps).put("level", a.level)
                    .put("plan", a.planLine)
                note?.text?.toString()?.trim()?.takeIf { it.isNotEmpty() }?.let { p.put("text", it) }
                answer(PHONE_DISTRACTION, p)
            }
            (if (i < 2) chips else second).addView(c, weighted())
        }
        val et = EditText(ctx).apply {
            hint = "Say or type anything for the planner…"
            setHintTextColor(Ui.MUTED)
            setTextColor(Ui.TEXT)
            textSize = 15f
            background = null
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_MULTI_LINE or InputType.TYPE_TEXT_FLAG_CAP_SENTENCES
            imeOptions = EditorInfo.IME_FLAG_NO_EXTRACT_UI or EditorInfo.IME_FLAG_NO_FULLSCREEN
            maxLines = 4
        }
        note = et
        val mic = chip("Speak") { startVoice() }.also { it.background = Ui.rounded(Ui.CHIP, Ui.dp(ctx, 16)); it.setTextColor(Ui.MUTED) }
        val bottom = row().apply { gravity = Gravity.CENTER_VERTICAL }
        bottom.addView(et, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        bottom.addView(mic)
        card.addView(bottom, lp(top = 10))
        val st = text("", 13f, Ui.ACCENT).apply { visibility = View.GONE }
        speakStatus = st
        card.addView(st)
    }

    private fun startVoice() {
        val et = note ?: return
        if (ctx.checkSelfPermission(android.Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) {
            say("Allow the microphone for Aside (Settings -> Voice) to talk")
            return
        }
        voiceBase = et.text.toString()
        val v = voice ?: VoiceInput(ctx, object : VoiceInput.Listener {
            override fun onPartial(text: String) { et.setText(VoiceText.merge(voiceBase, text)); et.setSelection(et.text.length) }
            override fun onFinal(text: String) { et.setText(VoiceText.merge(voiceBase, text)); et.setSelection(et.text.length); say(null) }
            override fun onError(message: String, nothingHeard: Boolean) = say(message)
        }).also { voice = it }
        say("Listening…")
        v.start()
    }

    private fun say(t: String?) {
        speakStatus?.apply { text = t.orEmpty(); visibility = if (t.isNullOrEmpty()) View.GONE else View.VISIBLE }
    }

    // --- morning: sleep ---

    private fun sleep(card: LinearLayout, a: Ask.Sleep) {
        val start = a.guessStartMs
        val wake = a.guessWakeMs
        if (start != null && wake != null) {
            card.addView(text("Looks like you slept ${hhmm(start)} to ${hhmm(wake)}. Right?", 16f, Ui.TEXT))
            val r = row()
            r.addView(chip("Yes") { answer(PHONE_SLEEP, sleepPayload(a.day, start, wake, edited = false)) }, weighted())
            r.addView(chip("Change") { pickSleep(a.day, start, wake) }, weighted())
            card.addView(r, lp(top = 12))
        } else {
            card.addView(text("When did you sleep?", 16f, Ui.TEXT))
            val r = row()
            r.addView(chip("Set times") { pickSleep(a.day, null, null) }, weighted())
            card.addView(r, lp(top = 12))
        }
    }

    private fun sleepPayload(day: LocalDate, startMs: Long, wakeMs: Long, edited: Boolean) = JSONObject()
        .put("day", day.toString())
        .put("sleep_start", Instant.ofEpochMilli(startMs).toString())
        .put("wake", Instant.ofEpochMilli(wakeMs).toString())
        .put("minutes", ((wakeMs - startMs) / 60_000).toInt())
        .put("edited", edited)

    /** Bedtime first, then wake time. Bedtime later in the day than the wake time means the evening before. */
    private fun pickSleep(day: LocalDate, startMs: Long?, wakeMs: Long?) {
        val zs = startMs?.let { Instant.ofEpochMilli(it).atZone(zone).toLocalTime() } ?: LocalTime.of(1, 0)
        val zw = wakeMs?.let { Instant.ofEpochMilli(it).atZone(zone).toLocalTime() } ?: LocalTime.of(9, 0)
        pickTime("Went to sleep at", zs) { bed ->
            pickTime("Woke up at", zw) { wake ->
                val wakeAt = LocalDateTime.of(LocalDate.now(), wake)
                val bedAt = LocalDateTime.of(LocalDate.now(), bed).let { if (it.isAfter(wakeAt)) it.minusDays(1) else it }
                answer(PHONE_SLEEP, sleepPayload(day, bedAt.atZone(zone).toInstant().toEpochMilli(), wakeAt.atZone(zone).toInstant().toEpochMilli(), edited = true))
            }
        }
    }

    private fun pickTime(title: String, initial: LocalTime, done: (LocalTime) -> Unit) {
        val d = TimePickerDialog(
            ContextThemeWrapper(ctx, android.R.style.Theme_DeviceDefault_Dialog_Alert),
            { _, h, m -> done(LocalTime.of(h, m)) }, initial.hour, initial.minute, true,
        )
        d.setTitle(title)
        d.window?.setType(WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY)
        d.show()
    }

    // --- evening: workout, meditation, naps, planned bedtime ---

    private fun evening(card: LinearLayout, a: Ask.Evening) {
        val picked = HashMap<String, String>()
        val naps = JSONArray()
        var napNone = false
        card.addView(text("Today", 16f, Ui.TEXT))
        fun yesPartlyNo(label: String, key: String) {
            card.addView(text(label, 14f, Ui.MUTED), lp(top = 10))
            val r = row()
            val chips = HashMap<String, TextView>()
            for ((v, l) in listOf("yes" to "Yes", "partly" to "Partly", "no" to "No")) {
                val c = chip(l) {
                    picked[key] = v
                    for ((k, view) in chips) paint(view, k == v)
                }
                chips[v] = c
                paint(c, false)
                r.addView(c, weighted())
            }
            card.addView(r, lp(top = 4))
        }
        yesPartlyNo("Workout done?", "workout")
        yesPartlyNo("Meditation done?", "meditation")

        card.addView(text("Any naps today?", 14f, Ui.MUTED), lp(top = 10))
        val napRow = row()
        val napText = text("", 13f, Ui.ACCENT).apply { visibility = View.GONE }
        lateinit var none: TextView
        none = chip("None") {
            napNone = !napNone                                      // tap again to take it back
            if (napNone) { while (naps.length() > 0) naps.remove(0); napText.visibility = View.GONE }
            paint(none, napNone)
        }
        paint(none, false)
        val add = chip("Add nap") {
            pickTime("Nap started at", LocalTime.of(14, 0)) { t ->
                val lengths = intArrayOf(15, 30, 45, 60, 90, 120)
                val dlg = AlertDialog.Builder(ContextThemeWrapper(ctx, android.R.style.Theme_DeviceDefault_Dialog_Alert))
                    .setTitle("How long?")
                    .setItems(lengths.map { "$it min" }.toTypedArray()) { _, i ->
                        naps.put(JSONObject().put("start", t.format(clock)).put("minutes", lengths[i]))
                        napNone = false
                        paint(none, false)
                        napText.text = (0 until naps.length()).joinToString("  ") { n -> naps.getJSONObject(n).let { o -> "${o.getString("start")} (${o.getInt("minutes")}m)" } }
                        napText.visibility = View.VISIBLE
                    }.create()
                dlg.window?.setType(WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY)
                dlg.show()
            }
        }
        napRow.addView(none, weighted())
        napRow.addView(add, weighted())
        card.addView(napRow, lp(top = 4))
        card.addView(napText, lp(top = 4))

        card.addView(text("When do you plan to sleep tonight?", 14f, Ui.MUTED), lp(top = 10))
        var intended: LocalTime? = null
        lateinit var sleepChip: TextView
        sleepChip = chip("Set time") {
            pickTime("I plan to sleep at", intended ?: LocalTime.of(1, 0)) { t ->
                intended = t
                sleepChip.text = t.format(clock)
                paint(sleepChip, true)
            }
        }
        paint(sleepChip, false)
        card.addView(sleepChip, lp(top = 4))

        val done = chip("Done") {
            val p = JSONObject().put("day", a.day.toString())
            intended?.let { p.put("intended_sleep", BubbleLogic.intendedSleep(a.day, it, zone).toString()) }
            for ((k, v) in picked) p.put(k, v)                     // unanswered stays out: unknown, never "missed"
            if (napNone || naps.length() > 0) p.put("naps", naps)
            answer(PHONE_EVENING, p)
        }
        paint(done, true)
        card.addView(done, lp(top = 14))
    }

    // --- anomaly ---

    private fun anomaly(card: LinearLayout, a: Ask.Anomaly) {
        card.addView(text("Both devices were quiet on ${a.day}. What was it?", 16f, Ui.TEXT))
        val r = row()
        for ((k, l) in listOf("travel" to "Travel", "sick" to "Sick", "festival" to "Festival", "normal" to "Normal")) {
            r.addView(chip(l) { answer(PHONE_ANOMALY, JSONObject().put("for_day", a.day.toString()).put("label", k)) }, weighted())
        }
        card.addView(r, lp(top = 12))
    }

    // --- task check (10 min before scheduled task) ---

    private fun taskCheck(card: LinearLayout, a: Ask.TaskCheck) {
        val slip = if (a.slipCount > 0) " (slipped ${a.slipCount}x)" else ""
        val minsAway = ((a.startMs - System.currentTimeMillis()) / 60_000L).toInt()
        val when_ = if (minsAway > 0) "is up in $minsAway min" else "is starting now"
        card.addView(text("${a.title} $when_$slip. Where does it stand?", 16f, Ui.TEXT))

        val chips = row()
        val second = row()
        card.addView(chips, lp(top = 12))
        card.addView(second, lp(top = 8))

        val options = listOf(
            "already_done" to "Already done",
            "starting_now" to "Starting now",
            "blocked" to "Blocked / need info",
            "not_today" to "Not today",
        )

        var selectedKey: String? = null
        val chipViews = HashMap<String, TextView>()

        // Input field for voice / typed notes
        val et = EditText(ctx).apply {
            hint = "Say or type anything for the planner…"
            setHintTextColor(Ui.MUTED)
            setTextColor(Ui.TEXT)
            textSize = 15f
            background = null
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_MULTI_LINE or InputType.TYPE_TEXT_FLAG_CAP_SENTENCES
            imeOptions = EditorInfo.IME_FLAG_NO_EXTRACT_UI or EditorInfo.IME_FLAG_NO_FULLSCREEN
            maxLines = 4
        }
        note = et

        val submitBtn = chip("Submit") {
            val key = selectedKey ?: "note"
            val p = JSONObject()
                .put("title", a.title)
                .put("status", key)
                .put("block_start", a.startMs)
                .put("slip_count", a.slipCount)
            note?.text?.toString()?.trim()?.takeIf { it.isNotEmpty() }?.let { p.put("text", it) }
            answer(PHONE_TASK_CHECK, p)
        }.apply {
            paint(this, true)
            visibility = View.GONE
        }

        options.forEachIndexed { i, (key, label) ->
            lateinit var c: TextView
            c = chip(label) {
                if (key == "blocked") {
                    selectedKey = key
                    for ((k, v) in chipViews) paint(v, k == key)
                    et.hint = "What's in the way?"
                    et.requestFocus()
                    submitBtn.visibility = View.VISIBLE
                } else {
                    val p = JSONObject()
                        .put("title", a.title)
                        .put("status", key)
                        .put("block_start", a.startMs)
                        .put("slip_count", a.slipCount)
                    et.text?.toString()?.trim()?.takeIf { it.isNotEmpty() }?.let { p.put("text", it) }
                    answer(PHONE_TASK_CHECK, p)
                }
            }
            chipViews[key] = c
            paint(c, false)
            (if (i < 2) chips else second).addView(c, weighted())
        }

        val mic = chip("Speak") { startVoice() }.also { it.background = Ui.rounded(Ui.CHIP, Ui.dp(ctx, 16)); it.setTextColor(Ui.MUTED) }
        val bottom = row().apply { gravity = Gravity.CENTER_VERTICAL }
        bottom.addView(et, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        bottom.addView(mic)
        card.addView(bottom, lp(top = 10))
        card.addView(submitBtn, lp(top = 8))

        et.addTextChangedListener(object : android.text.TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {
                submitBtn.visibility = if (s?.isNotBlank() == true || selectedKey == "blocked") View.VISIBLE else View.GONE
            }
            override fun afterTextChanged(s: android.text.Editable?) {}
        })

        val st = text("", 13f, Ui.ACCENT).apply { visibility = View.GONE }
        speakStatus = st
        card.addView(st)
    }

    // --- deep work debrief (after deep work wraps up) ---

    private fun debrief(card: LinearLayout, a: Ask.Debrief) {
        card.addView(text("${a.title} wrapped up. Done, or continuing tomorrow?", 16f, Ui.TEXT))

        val chips = row()
        card.addView(chips, lp(top = 12))

        val options = listOf(
            "continue_tomorrow" to "Continue tomorrow",
            "finished" to "Finished",
            "paused" to "Paused / Shelved",
        )

        var selectedOutcome = "continue_tomorrow"
        val chipViews = HashMap<String, TextView>()

        val et = EditText(ctx).apply {
            hint = "What did you get done? Say or type…"
            setHintTextColor(Ui.MUTED)
            setTextColor(Ui.TEXT)
            textSize = 15f
            background = null
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_MULTI_LINE or InputType.TYPE_TEXT_FLAG_CAP_SENTENCES
            imeOptions = EditorInfo.IME_FLAG_NO_EXTRACT_UI or EditorInfo.IME_FLAG_NO_FULLSCREEN
            maxLines = 4
        }
        note = et

        options.forEach { (key, label) ->
            lateinit var c: TextView
            c = chip(label) {
                selectedOutcome = key
                for ((k, v) in chipViews) paint(v, k == key)
            }
            chipViews[key] = c
            paint(c, key == selectedOutcome)
            chips.addView(c, weighted())
        }

        val mic = chip("Speak") { startVoice() }.also { it.background = Ui.rounded(Ui.CHIP, Ui.dp(ctx, 16)); it.setTextColor(Ui.MUTED) }
        val bottom = row().apply { gravity = Gravity.CENTER_VERTICAL }
        bottom.addView(et, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        bottom.addView(mic)
        card.addView(bottom, lp(top = 10))

        val saveBtn = chip("Save debrief") {
            val p = JSONObject()
                .put("title", a.title)
                .put("outcome", selectedOutcome)
                .put("block_end", a.endMs)
            et.text?.toString()?.trim()?.takeIf { it.isNotEmpty() }?.let { p.put("text", it) }
            answer(PHONE_DEBRIEF, p)
        }
        paint(saveBtn, true)
        card.addView(saveBtn, lp(top = 8))

        val st = text("", 13f, Ui.ACCENT).apply { visibility = View.GONE }
        speakStatus = st
        card.addView(st)
    }

    // --- small view helpers ---

    private fun text(t: String, size: Float, color: Int) = TextView(ctx).apply { text = t; textSize = size; setTextColor(color) }

    private fun row() = LinearLayout(ctx).apply { orientation = LinearLayout.HORIZONTAL }

    private fun lp(top: Int = 0) = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply { topMargin = Ui.dp(ctx, top) }

    private fun weighted() = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f).apply { marginEnd = Ui.dp(ctx, 6) }

    private fun chip(label: String, onClick: () -> Unit) = TextView(ctx).apply {
        text = label
        textSize = 14f
        gravity = Gravity.CENTER
        setPadding(Ui.dp(ctx, 10), Ui.dp(ctx, 9), Ui.dp(ctx, 10), Ui.dp(ctx, 9))
        background = Ui.rounded(Ui.CHIP, Ui.dp(ctx, 16))
        setTextColor(Ui.TEXT)
        setOnClickListener { onClick() }
    }

    private fun paint(v: TextView, selected: Boolean) {
        v.background = Ui.rounded(if (selected) Ui.ACCENT else Ui.CHIP, Ui.dp(ctx, 16))
        v.setTextColor(if (selected) Ui.ON_ACCENT else Ui.TEXT)
    }

    private fun hhmm(ms: Long) = Instant.ofEpochMilli(ms).atZone(zone).format(clock)

    companion object {
        const val PHONE_DISTRACTION = "distraction"
        const val PHONE_SLEEP = "sleep"
        const val PHONE_EVENING = "evening"
        const val PHONE_ANOMALY = "anomaly"
        const val PHONE_TASK_CHECK = "task_check"
        const val PHONE_DEBRIEF = "debrief"
    }
}
