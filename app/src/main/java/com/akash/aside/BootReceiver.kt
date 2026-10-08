package com.akash.aside

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.provider.Settings
import com.akash.aside.overlay.CaptureService

/** Brings the dot back after a reboot. */
class BootReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != Intent.ACTION_BOOT_COMPLETED) return
        if (Prefs.wantDot(context) && Settings.canDrawOverlays(context)) CaptureService.start(context)
    }
}
