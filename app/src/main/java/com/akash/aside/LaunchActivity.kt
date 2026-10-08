package com.akash.aside

import android.app.Activity
import android.os.Bundle
import android.provider.Settings
import android.widget.Toast
import com.akash.aside.data.Kind
import com.akash.aside.overlay.CaptureService

/** Invisible doorway for the icon shortcuts: opens the input window for a note, journal or task, then goes away. */
class LaunchActivity : Activity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        if (Settings.canDrawOverlays(this)) {
            CaptureService.open(this, intent.getStringExtra(CaptureService.EXTRA_KIND) ?: Kind.NOTE)
        } else {
            Toast.makeText(this, "Allow \"Display over other apps\" for Aside first", Toast.LENGTH_LONG).show()
        }
        finish()
    }
}
