package com.akash.aside.overlay

import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.SharedPreferences
import android.content.pm.ServiceInfo
import android.content.res.Configuration
import android.os.IBinder
import android.provider.Settings
import android.view.WindowManager
import com.akash.aside.AsideApp
import com.akash.aside.Notifications
import com.akash.aside.Prefs
import com.akash.aside.ReminderScheduler
import com.akash.aside.bubble.BubbleWatcher
import com.akash.aside.bubble.QuestionController
import com.akash.aside.data.Kind

/** Keeps the dot on screen. Android requires a foreground service (and its quiet notification) for that. */
class CaptureService : Service() {
    private lateinit var wm: WindowManager
    private var dot: DotController? = null
    private var editor: EditorController? = null
    private var bubble: BubbleWatcher? = null
    private var questions: QuestionController? = null

    // Held in a field on purpose: SharedPreferences keeps listeners only weakly.
    private val prefListener = SharedPreferences.OnSharedPreferenceChangeListener { _, key ->
        if (key == Prefs.KEY_DOT_SIZE || key == Prefs.KEY_ICON_VERSION) dot?.refresh()
        // A sync brought a sleep guess or an anomaly, or the settings changed: color the ring now.
        if (key == "bubble_enabled" || key == "flagged_apps" || key == "sleep_day" || key == "anomaly_day") bubble?.refresh()
    }

    override fun onCreate() {
        super.onCreate()
        Notifications.ensureChannels(this)
        startForeground(Notifications.ID_STATUS, Notifications.status(this), ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE)
        if (!Settings.canDrawOverlays(this)) {
            stopSelf()
            return
        }
        wm = getSystemService(Context.WINDOW_SERVICE) as WindowManager
        val repo = (application as AsideApp).repo
        val d = DotController(this, wm, onTap = { onDotTap() }, onHide = { hideForNow() }, onLongPress = { openEditor(voice = true) })
        dot = d
        editor = EditorController(this, wm, repo) { hadText ->
            d.show()
            if (hadText) d.pulse()
        }
        val b = BubbleWatcher(this, d, (application as AsideApp).db.answerDao())
        bubble = b
        questions = QuestionController(this, wm, onAnswer = { kind, payload -> b.answered(kind, payload) }, onNotNow = { b.notNow(it) }, onClosed = { d.show() })
        d.show()
        b.start()
        Prefs.raw(this).registerOnSharedPreferenceChangeListener(prefListener)
        ReminderScheduler.cancel(this)
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_OPEN -> openEditor(intent.getStringExtra(EXTRA_KIND) ?: Kind.NOTE)
            ACTION_HIDE -> hideForNow()
        }
        return START_STICKY
    }

    /** A colored dot opens its question sheet; a plain one opens the input window. */
    private fun onDotTap() {
        val ask = bubble?.currentAsk()
        if (ask == null) {
            openEditor()
            return
        }
        bubble?.opened()
        dot?.remove()
        questions?.show(ask)
    }

    private fun openEditor(kind: String = Kind.NOTE, voice: Boolean = false) {
        dot?.remove()
        editor?.show(kind, voice)
    }

    /** Dragged to the X (or "Hide"): gone now, one quiet reminder in 15 minutes. */
    private fun hideForNow() {
        editor?.dismiss()
        questions?.dismiss()
        dot?.remove()
        ReminderScheduler.scheduleIn15(this)
        stopSelf()
    }

    override fun onConfigurationChanged(newConfig: Configuration) {
        super.onConfigurationChanged(newConfig)
        dot?.reposition()
    }

    override fun onDestroy() {
        Prefs.raw(this).unregisterOnSharedPreferenceChangeListener(prefListener)
        editor?.dismiss() // keeps whatever was being typed
        bubble?.stop()
        questions?.dismiss()
        dot?.remove()
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? = null

    companion object {
        const val ACTION_START = "com.akash.aside.START"
        const val ACTION_OPEN = "com.akash.aside.OPEN"
        const val ACTION_HIDE = "com.akash.aside.HIDE"
        const val EXTRA_KIND = "kind"

        fun start(ctx: Context) {
            ctx.startForegroundService(Intent(ctx, CaptureService::class.java).setAction(ACTION_START))
        }

        /** Starts the service if needed and opens the input window (used by the tile and the icon shortcuts). */
        fun open(ctx: Context, kind: String = Kind.NOTE) {
            ctx.startForegroundService(
                Intent(ctx, CaptureService::class.java).setAction(ACTION_OPEN).putExtra(EXTRA_KIND, kind)
            )
        }

        fun stop(ctx: Context) {
            ctx.stopService(Intent(ctx, CaptureService::class.java))
        }
    }
}
