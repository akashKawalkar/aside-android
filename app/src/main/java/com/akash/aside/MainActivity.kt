package com.akash.aside

import android.os.Bundle
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.fragment.app.FragmentActivity
import com.akash.aside.ui.AppRoot
import com.akash.aside.ui.LockState
import com.akash.aside.ui.theme.AsideTheme

class MainActivity : FragmentActivity() {
    private var locked by mutableStateOf(true)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent {
            AsideTheme {
                AppRoot(this, locked) {
                    locked = false
                    LockState.leftAt = System.currentTimeMillis()
                }
            }
        }
    }

    override fun onStart() {
        super.onStart()
        // Coming back within two minutes (or rotating) does not ask again.
        locked = Prefs.lockEnabled(this) && LockState.needsUnlock()
        // With the lock on, the app switcher must not show a picture of the notes.
        setRecentsScreenshotEnabled(!Prefs.lockEnabled(this))
        // Opening the app is a good moment to send anything waiting and see what is new.
        com.akash.aside.cloud.Sync.kick(this)
    }

    override fun onStop() {
        super.onStop()
        // Only an unlocked screen starts the grace period; leaving the lock screen must not unlock it.
        if (!locked) LockState.leftAt = System.currentTimeMillis()
    }
}
