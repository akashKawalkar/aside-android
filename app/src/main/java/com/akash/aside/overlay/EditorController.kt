package com.akash.aside.overlay

import android.app.DatePickerDialog
import android.content.ClipboardManager
import android.content.Context
import android.os.Handler
import android.os.Looper
import android.text.Editable
import android.text.InputType
import android.text.TextWatcher
import android.view.Gravity
import android.view.KeyEvent
import android.view.ViewGroup
import android.view.WindowInsets
import android.view.WindowManager
import android.view.inputmethod.EditorInfo
import android.view.inputmethod.InputMethodManager
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.TextView
import android.view.ContextThemeWrapper
import android.graphics.PixelFormat
import com.akash.aside.AppScope
import com.akash.aside.capture.CaptureRepository
import com.akash.aside.capture.Prefix
import com.akash.aside.data.Kind
import kotlinx.coroutines.launch
import java.time.LocalDate
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale

/**
 * The input window: opens over any app with the keyboard up. There is no save button; every pause in typing is stored,
 * and closing the window (tap outside, Back) keeps what was written.
 */
class EditorController(
    private val ctx: Context,
    private val wm: WindowManager,
    private val repo: CaptureRepository,
    private val onClosed: (hadText: Boolean) -> Unit,
) {
    private val handler = Handler(Looper.getMainLooper())
    private val imm = ctx.getSystemService(Context.INPUT_METHOD_SERVICE) as InputMethodManager

    private var root: Root? = null
    private var edit: EditText? = null
    private var id = ""
    private var chip = Kind.NOTE
    private var dueChoice = Due.NONE
    private var pickedDate: LocalDate? = null

    private val kindChips = mutableMapOf<String, TextView>()
    private val dueChips = mutableMapOf<Due, TextView>()
    private var dueRow: LinearLayout? = null

    private enum class Due { NONE, TODAY, TOMORROW, PICK }

    private class Root(ctx: Context, val onBack: () -> Unit) : FrameLayout(ctx) {
        override fun dispatchKeyEvent(e: KeyEvent): Boolean {
            if (e.keyCode == KeyEvent.KEYCODE_BACK) {
                if (e.action == KeyEvent.ACTION_UP) onBack()
                return true
            }
            return super.dispatchKeyEvent(e)
        }
    }

    val isShowing get() = root != null

    private val saveRunnable = Runnable { saveNow() }

    private var voice: VoiceInput? = null
    private var voiceBase = ""
    private var status: TextView? = null

    /** `voice = true` (long-press on the dot) opens it already listening, with no keyboard. */
    fun show(kind: String = Kind.NOTE, voice: Boolean = false) {
        if (root != null) { if (voice) startVoice() else raiseKeyboard(); return }
        id = repo.newId()
        chip = kind
        dueChoice = Due.NONE
        pickedDate = null

        val r = Root(ctx) { dismiss() }
        r.setBackgroundColor(Ui.SCRIM)
        r.setOnClickListener { dismiss() }

        val card = LinearLayout(ctx).apply {
            orientation = LinearLayout.VERTICAL
            background = Ui.rounded(Ui.CARD, Ui.dp(ctx, 20))
            setPadding(Ui.dp(ctx, 14), Ui.dp(ctx, 12), Ui.dp(ctx, 14), Ui.dp(ctx, 12))
            isClickable = true // taps inside the card must not close it
        }
        card.addView(kindRow())
        val et = editText()
        edit = et
        card.addView(et, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply {
            topMargin = Ui.dp(ctx, 8)
        })
        val st = TextView(ctx).apply {
            textSize = 13f
            setTextColor(Ui.ACCENT)
            visibility = android.view.View.GONE
        }
        status = st
        card.addView(st)
        card.addView(bottomRow(), LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply {
            topMargin = Ui.dp(ctx, 6)
        })

        val lp = FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT, Gravity.TOP).apply {
            setMargins(Ui.dp(ctx, 12), Ui.statusBarHeight(ctx) + Ui.dp(ctx, 8), Ui.dp(ctx, 12), 0)
        }
        r.addView(card, lp)

        val params = WindowManager.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT,
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
            WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN,
            PixelFormat.TRANSLUCENT,
        ).apply {
            softInputMode = (if (voice) WindowManager.LayoutParams.SOFT_INPUT_STATE_HIDDEN
            else WindowManager.LayoutParams.SOFT_INPUT_STATE_ALWAYS_VISIBLE) or WindowManager.LayoutParams.SOFT_INPUT_ADJUST_RESIZE
        }
        wm.addView(r, params)
        root = r
        renderChips()
        if (voice) startVoice() else raiseKeyboard()
    }

    // --- voice ---

    private fun say(text: String?) {
        status?.apply { this.text = text.orEmpty(); visibility = if (text.isNullOrEmpty()) android.view.View.GONE else android.view.View.VISIBLE }
    }

    private fun startVoice() {
        val et = edit ?: return
        if (ctx.checkSelfPermission(android.Manifest.permission.RECORD_AUDIO) != android.content.pm.PackageManager.PERMISSION_GRANTED) {
            say("Allow the microphone for Aside (Settings -> Voice) to talk")
            raiseKeyboard()
            return
        }
        voiceBase = et.text.toString()
        val v = voice ?: VoiceInput(ctx, object : VoiceInput.Listener {
            override fun onPartial(text: String) = fill(text)
            override fun onFinal(text: String) { fill(text); say(null); dismiss() }          // stopped speaking: keep it and close
            override fun onError(message: String, nothingHeard: Boolean) {
                if (nothingHeard && et.text.isBlank()) {
                    say(message)
                    handler.postDelayed({ dismiss() }, 1200)
                } else {
                    say(message)
                    raiseKeyboard()                                                              // fall back to typing
                }
            }
        }).also { voice = it }
        say("Listening…")
        v.start()
    }

    private fun fill(spoken: String) {
        val et = edit ?: return
        et.setText(com.akash.aside.capture.VoiceText.merge(voiceBase, spoken))
        et.setSelection(et.text.length)
    }

    /** Closes the window and keeps what was typed. */
    fun dismiss() {
        val r = root ?: return
        val et = edit
        val raw = et?.text?.toString().orEmpty()
        val kind = chip
        val due = dueEpoch()
        val myId = id
        handler.removeCallbacksAndMessages(null)
        voice?.stop(); voice = null; status = null
        if (et != null) imm.hideSoftInputFromWindow(et.windowToken, 0)
        runCatching { wm.removeView(r) }
        root = null
        edit = null
        kindChips.clear(); dueChips.clear(); dueRow = null
        AppScope.launch { repo.commit(myId, raw, kind, due) }
        onClosed(Prefix.parse(raw).text.isNotBlank())
    }

    // --- pieces ---

    private fun chipView(label: String, onClick: () -> Unit) = TextView(ctx).apply {
        text = label
        textSize = 13f
        gravity = Gravity.CENTER
        setPadding(Ui.dp(ctx, 14), Ui.dp(ctx, 7), Ui.dp(ctx, 14), Ui.dp(ctx, 7))
        setOnClickListener { onClick() }
    }

    private fun style(v: TextView, selected: Boolean) {
        v.background = Ui.rounded(if (selected) Ui.ACCENT else Ui.CHIP, Ui.dp(ctx, 16))
        v.setTextColor(if (selected) Ui.ON_ACCENT else Ui.MUTED)
    }

    private fun kindRow() = LinearLayout(ctx).apply {
        orientation = LinearLayout.HORIZONTAL
        for ((kind, label) in listOf(Kind.NOTE to "Note", Kind.JOURNAL to "Journal", Kind.TASK to "Task")) {
            val c = chipView(label) { pickKind(kind) }
            kindChips[kind] = c
            addView(c, LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply {
                marginEnd = Ui.dp(ctx, 8)
            })
        }
    }

    private fun editText() = EditText(ctx).apply {
        hint = "Write… (j: journal, t: task, p: next plan, r: rule)"
        setHintTextColor(Ui.MUTED)
        setTextColor(Ui.TEXT)
        textSize = 17f
        minLines = 3
        maxLines = 8
        gravity = Gravity.TOP or Gravity.START
        background = null
        setPadding(Ui.dp(ctx, 2), Ui.dp(ctx, 6), Ui.dp(ctx, 2), Ui.dp(ctx, 6))
        inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_MULTI_LINE or InputType.TYPE_TEXT_FLAG_CAP_SENTENCES
        imeOptions = EditorInfo.IME_FLAG_NO_EXTRACT_UI or EditorInfo.IME_FLAG_NO_FULLSCREEN
        addTextChangedListener(object : TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, a: Int, b: Int, c: Int) {}
            override fun onTextChanged(s: CharSequence?, a: Int, b: Int, c: Int) {}
            override fun afterTextChanged(s: Editable?) {
                Prefix.parse(s?.toString().orEmpty()).kind?.let { if (it != chip) { chip = it; renderChips() } }
                if (chip == Kind.PLAN) say("For the next plan only") else if (chip == Kind.RULE) say("Standing rule, saved for review")
                handler.removeCallbacks(saveRunnable)
                handler.postDelayed(saveRunnable, 250)
            }
        })
    }

    private fun bottomRow() = LinearLayout(ctx).apply {
        orientation = LinearLayout.HORIZONTAL
        gravity = Gravity.CENTER_VERTICAL
        val paste = chipView("Paste") { pasteClipboard() }.also { style(it, false) }
        addView(paste)
        val speak = chipView("Speak") { if (voice?.listening == true) { voice?.stop(); say(null); raiseKeyboard() } else startVoice() }.also { style(it, false) }
        addView(speak, LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply { marginStart = Ui.dp(ctx, 6) })
        val due = LinearLayout(ctx).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.END }
        for ((d, label) in listOf(Due.TODAY to "Today", Due.TOMORROW to "Tomorrow", Due.PICK to "Pick", Due.NONE to "No date")) {
            val c = chipView(label) { pickDue(d) }
            dueChips[d] = c
            due.addView(c, LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply {
                marginStart = Ui.dp(ctx, 6)
            })
        }
        dueRow = due
        addView(due, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
    }

    private fun renderChips() {
        for ((k, v) in kindChips) style(v, k == chip)
        for ((d, v) in dueChips) {
            style(v, d == dueChoice)
            if (d == Due.PICK) v.text = pickedDate?.format(DateTimeFormatter.ofPattern("EEE d MMM", Locale.getDefault())) ?: "Pick"
        }
        dueRow?.visibility = if (chip == Kind.TASK) android.view.View.VISIBLE else android.view.View.GONE
    }

    // --- actions ---

    private fun pickKind(kind: String) {
        chip = kind
        // An explicit tap overrides a typed prefix, so drop the prefix instead of letting it win later.
        edit?.let { et ->
            val raw = et.text.toString()
            if (Prefix.parse(raw).kind != null) {
                et.setText(Prefix.parse(raw).text)
                et.setSelection(et.text.length)
            }
        }
        renderChips()
        saveNow()
    }

    private fun pickDue(d: Due) {
        if (d != Due.PICK) {
            dueChoice = d
            renderChips()
            saveNow()
            return
        }
        val base = pickedDate ?: LocalDate.now()
        val dialog = DatePickerDialog(
            ContextThemeWrapper(ctx, android.R.style.Theme_DeviceDefault_Dialog_Alert),
            { _, y, m, day ->
                pickedDate = LocalDate.of(y, m + 1, day)
                dueChoice = Due.PICK
                renderChips()
                saveNow()
                raiseKeyboard()
            },
            base.year, base.monthValue - 1, base.dayOfMonth,
        )
        dialog.window?.setType(WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY)
        dialog.show()
    }

    private fun pasteClipboard() {
        val et = edit ?: return
        val cm = ctx.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
        val clip = cm.primaryClip?.takeIf { it.itemCount > 0 }?.getItemAt(0)?.coerceToText(ctx)?.toString()
        if (clip.isNullOrEmpty()) return
        val start = et.selectionStart.coerceAtLeast(0)
        val end = et.selectionEnd.coerceAtLeast(start)
        et.text.replace(start, end, clip)
        raiseKeyboard()
    }

    private fun dueEpoch(): Long? {
        val date = when (dueChoice) {
            Due.NONE -> return null
            Due.TODAY -> LocalDate.now()
            Due.TOMORROW -> LocalDate.now().plusDays(1)
            Due.PICK -> pickedDate ?: return null
        }
        return date.atTime(23, 59).atZone(ZoneId.systemDefault()).toInstant().toEpochMilli()
    }

    private fun saveNow() {
        val et = edit ?: return
        val raw = et.text.toString()
        val kind = chip
        val due = dueEpoch()
        val myId = id
        AppScope.launch { repo.saveDraft(myId, raw, kind, due) }
    }

    /** Overlay windows are fussy about the keyboard, so ask a few times until it is actually up. */
    private fun raiseKeyboard() {
        val et = edit ?: return
        et.requestFocus()
        for (delay in longArrayOf(0, 120, 300, 600)) {
            et.postDelayed({
                val e = edit ?: return@postDelayed
                val visible = e.rootWindowInsets?.isVisible(WindowInsets.Type.ime()) == true
                if (!visible) {
                    e.requestFocus()
                    @Suppress("DEPRECATION")
                    if (delay >= 600) imm.toggleSoftInput(InputMethodManager.SHOW_FORCED, 0)
                    else imm.showSoftInput(e, InputMethodManager.SHOW_IMPLICIT)
                }
            }, delay)
        }
    }
}
