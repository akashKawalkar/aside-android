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

/** Keeps the dot on screen. Android requires a foreground service (and its quiet notification) for that. */
class CaptureService : Service() {
    private lateinit var wm: WindowManager
    private var dot: DotController? = null
    private var editor: EditorController? = null

    // Held in a field on purpose: SharedPreferences keeps listeners only weakly.
    private val prefListener = SharedPreferences.OnSharedPreferenceChangeListener { _, key ->
        if (key == Prefs.KEY_DOT_SIZE || key == Prefs.KEY_ICON_VERSION) dot?.refresh()
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
        val d = DotController(this, wm, onTap = { openEditor() }, onHide = { hideForNow() })
        dot = d
        editor = EditorController(this, wm, repo) { hadText ->
            d.show()
            if (hadText) d.pulse()
        }
        d.show()
        Prefs.raw(this).registerOnSharedPreferenceChangeListener(prefListener)
        ReminderScheduler.cancel(this)
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_OPEN -> openEditor()
            ACTION_HIDE -> hideForNow()
        }
        return START_STICKY
    }

    private fun openEditor() {
        dot?.remove()
        editor?.show()
    }

    /** Dragged to the X (or "Hide"): gone now, one quiet reminder in 15 minutes. */
    private fun hideForNow() {
        editor?.dismiss()
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
        dot?.remove()
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? = null

    companion object {
        const val ACTION_START = "com.akash.aside.START"
        const val ACTION_OPEN = "com.akash.aside.OPEN"
        const val ACTION_HIDE = "com.akash.aside.HIDE"

        fun start(ctx: Context) {
            ctx.startForegroundService(Intent(ctx, CaptureService::class.java).setAction(ACTION_START))
        }

        fun stop(ctx: Context) {
            ctx.stopService(Intent(ctx, CaptureService::class.java))
        }
    }
}
