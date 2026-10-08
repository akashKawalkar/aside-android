package com.akash.aside

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Bundle
import android.provider.Settings
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalLifecycleOwner
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import com.akash.aside.overlay.CaptureService
import com.akash.aside.ui.theme.AsideTheme

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent {
            AsideTheme {
                Scaffold(modifier = Modifier.fillMaxSize()) { inner ->
                    SetupScreen(Modifier.padding(inner).padding(20.dp))
                }
            }
        }
    }
}

/** Temporary home screen for the capture dot: the two grants it needs, and the on/off switch. */
@Composable
fun SetupScreen(modifier: Modifier = Modifier) {
    val ctx = LocalContext.current
    fun notifGranted() = ctx.checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) == PackageManager.PERMISSION_GRANTED
    var overlayOk by remember { mutableStateOf(Settings.canDrawOverlays(ctx)) }
    var notifOk by remember { mutableStateOf(notifGranted()) }
    var dotOn by remember { mutableStateOf(Prefs.wantDot(ctx)) }

    val lifecycle = LocalLifecycleOwner.current.lifecycle
    DisposableEffect(lifecycle) {
        val obs = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_RESUME) {
                overlayOk = Settings.canDrawOverlays(ctx)
                notifOk = notifGranted()
            }
        }
        lifecycle.addObserver(obs)
        onDispose { lifecycle.removeObserver(obs) }
    }
    val notifLauncher = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { notifOk = it }

    Column(modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Text("Aside", style = MaterialTheme.typography.headlineMedium)
        Text("Quick capture", style = MaterialTheme.typography.titleMedium)
        Spacer(Modifier.height(8.dp))

        PermissionRow("Display over other apps", overlayOk) {
            ctx.startActivity(Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION, Uri.parse("package:${ctx.packageName}")))
        }
        PermissionRow("Notifications", notifOk) { notifLauncher.launch(Manifest.permission.POST_NOTIFICATIONS) }

        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.SpaceBetween) {
            Text("Show the dot")
            Switch(
                checked = dotOn,
                enabled = overlayOk,
                onCheckedChange = { on ->
                    dotOn = on
                    Prefs.setWantDot(ctx, on)
                    if (on) CaptureService.start(ctx) else {
                        CaptureService.stop(ctx)
                        ReminderScheduler.cancel(ctx)
                    }
                },
            )
        }
    }
}

@Composable
private fun PermissionRow(label: String, granted: Boolean, onGrant: () -> Unit) {
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.SpaceBetween) {
        Text(label)
        if (granted) Text("Allowed", color = MaterialTheme.colorScheme.primary) else Button(onClick = onGrant) { Text("Allow") }
    }
}
