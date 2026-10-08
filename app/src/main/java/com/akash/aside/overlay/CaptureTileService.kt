package com.akash.aside.overlay

import android.provider.Settings
import android.service.quicksettings.TileService
import com.akash.aside.data.Kind

/** The Quick Settings tile: one tap from the notification shade opens the input window. */
class CaptureTileService : TileService() {
    override fun onClick() {
        super.onClick()
        if (Settings.canDrawOverlays(this)) CaptureService.open(this, Kind.NOTE)
    }
}
