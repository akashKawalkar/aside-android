package com.akash.aside.ui

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.provider.Settings
import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Slider
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import kotlinx.coroutines.launch
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalLifecycleOwner
import androidx.compose.ui.unit.dp
import androidx.fragment.app.FragmentActivity
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import com.akash.aside.HomeShortcut
import com.akash.aside.Prefs
import com.akash.aside.ReminderScheduler
import com.akash.aside.capture.CaptureRepository
import com.akash.aside.overlay.CaptureService
import com.akash.aside.overlay.DotIcon
import kotlin.math.roundToInt

/** The other side of the main screen: status, the dot, the lock. */
@Composable
fun SettingsPage(activity: FragmentActivity, repo: CaptureRepository, modifier: Modifier = Modifier) {
    Column(
        modifier.verticalScroll(rememberScrollState()).padding(horizontal = 20.dp, vertical = 12.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        StatusSection(repo)
        HorizontalDivider()
        CloudSection(activity)
        HorizontalDivider()
        NightJobSection()
        HorizontalDivider()
        CaptureSection()
        HorizontalDivider()
        VoiceSection()
        HorizontalDivider()
        ReliabilitySection()
        HorizontalDivider()
        UsageSection()
        HorizontalDivider()
        BubbleSection()
        HorizontalDivider()
        AppearanceSection()
        HorizontalDivider()
        SecuritySection(activity)
        Spacer(Modifier.height(24.dp))
    }
}

@Composable
private fun Heading(text: String) = Text(text, style = MaterialTheme.typography.titleMedium)

@Composable
private fun StatusSection(repo: CaptureRepository) {
    val ctx = LocalContext.current
    val pending by remember { repo.observePending() }.collectAsState(0)
    val signedIn = Prefs.session(ctx) != null
    Heading("Status")
    Text(
        when {
            pending == 0 -> "Everything is saved."
            signedIn -> "$pending item${if (pending == 1) "" else "s"} waiting to be sent."
            else -> "$pending item${if (pending == 1) "" else "s"} saved on this phone, waiting for sign-in."
        },
        style = MaterialTheme.typography.bodyMedium,
    )
    val last = Prefs.lastSyncAt(ctx)
    if (last > 0) {
        Text(
            "Last sent " + java.time.Instant.ofEpochMilli(last).atZone(java.time.ZoneId.systemDefault())
                .format(java.time.format.DateTimeFormatter.ofPattern("d MMM, HH:mm")),
            style = MaterialTheme.typography.bodySmall,
        )
    }
    Prefs.lastSyncError(ctx)?.let { Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error) }
}

/** Sign in once; after that notes and tasks are sent by themselves. */
@Composable
private fun CloudSection(activity: FragmentActivity) {
    val app = activity.application as com.akash.aside.AsideApp
    val client = app.cloud
    val scope = androidx.compose.runtime.rememberCoroutineScope()
    var signedIn by remember { mutableStateOf(client.signedIn) }
    var email by remember { mutableStateOf("") }
    var password by remember { mutableStateOf("") }
    var busy by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }

    Heading("Cloud")
    when {
        !client.configured -> Text(
            "This build has no cloud address. Add supabase.url and supabase.anon_key to local.properties and rebuild.",
            style = MaterialTheme.typography.bodySmall,
        )
        signedIn -> {
            Text("Signed in as ${client.email}", style = MaterialTheme.typography.bodyMedium)
            Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                Button(onClick = { com.akash.aside.cloud.Sync.kick(activity) }) { Text("Send now") }
                OutlinedButton(onClick = { client.signOut(); signedIn = false }) { Text("Sign out") }
            }
        }
        else -> {
            androidx.compose.material3.OutlinedTextField(
                email, { email = it }, label = { Text("Email") }, singleLine = true, modifier = Modifier.fillMaxWidth(),
                keyboardOptions = androidx.compose.foundation.text.KeyboardOptions(keyboardType = androidx.compose.ui.text.input.KeyboardType.Email),
            )
            androidx.compose.material3.OutlinedTextField(
                password, { password = it }, label = { Text("Password") }, singleLine = true, modifier = Modifier.fillMaxWidth(),
                visualTransformation = androidx.compose.ui.text.input.PasswordVisualTransformation(),
                keyboardOptions = androidx.compose.foundation.text.KeyboardOptions(keyboardType = androidx.compose.ui.text.input.KeyboardType.Password),
            )
            Button(
                enabled = !busy && email.isNotBlank() && password.isNotEmpty(),
                onClick = {
                    busy = true; error = null
                    scope.launch {
                        try {
                            client.signIn(email, password)
                            signedIn = true; password = ""
                            com.akash.aside.cloud.Sync.kick(activity)
                        } catch (e: Exception) {
                            error = e.message ?: "Could not sign in"
                        } finally { busy = false }
                    }
                },
            ) { Text(if (busy) "Signing in…" else "Sign in") }
            error?.let { Text(it, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall) }
            Text("You sign in once; the phone keeps the session.", style = MaterialTheme.typography.bodySmall)
        }
    }
}

@Composable
private fun CaptureSection() {
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
                dotOn = Prefs.wantDot(ctx)
            }
        }
        lifecycle.addObserver(obs)
        onDispose { lifecycle.removeObserver(obs) }
    }
    val notifLauncher = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { notifOk = it }

    Heading("Quick capture")
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

/** Size and picture of the dot, and a home-screen shortcut with a picture of the user's choice. */
@Composable
private fun AppearanceSection() {
    val ctx = LocalContext.current
    var sizeDp by remember { mutableFloatStateOf(Prefs.dotSizeDp(ctx).toFloat()) }
    var icon by remember { mutableStateOf(DotIcon.load(ctx)) }
    val iconPicker = rememberLauncherForActivityResult(ActivityResultContracts.GetContent()) { uri ->
        if (uri != null) {
            if (DotIcon.import(ctx, uri)) icon = DotIcon.load(ctx)
            else Toast.makeText(ctx, "That picture could not be read", Toast.LENGTH_SHORT).show()
        }
    }
    val shortcutPicker = rememberLauncherForActivityResult(ActivityResultContracts.GetContent()) { uri ->
        if (uri != null) {
            val bitmap = DotIcon.decode(ctx, uri)
            val ok = bitmap != null && HomeShortcut.pin(ctx, bitmap)
            if (!ok) Toast.makeText(ctx, "Could not add the shortcut", Toast.LENGTH_SHORT).show()
        }
    }

    Heading("Dot")
    Box(Modifier.fillMaxWidth().height(100.dp), contentAlignment = Alignment.Center) {
        val bmp = icon
        if (bmp != null) {
            Image(bmp.asImageBitmap(), contentDescription = "Your dot icon", modifier = Modifier.size(sizeDp.dp))
        } else {
            Box(
                Modifier.size(sizeDp.dp).clip(CircleShape).background(MaterialTheme.colorScheme.primary),
                contentAlignment = Alignment.Center,
            ) { Text("+", color = MaterialTheme.colorScheme.onPrimary, style = MaterialTheme.typography.titleLarge) }
        }
    }
    Slider(
        value = sizeDp,
        onValueChange = { sizeDp = it },
        onValueChangeFinished = { Prefs.setDotSizeDp(ctx, sizeDp.roundToInt()) },
        valueRange = Prefs.SIZE_MIN.toFloat()..Prefs.SIZE_MAX.toFloat(),
    )
    Text("Size ${sizeDp.roundToInt()}. Drag the dot anywhere; it stays where you drop it.", style = MaterialTheme.typography.bodySmall)
    Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
        Button(onClick = { iconPicker.launch("image/*") }) { Text("Choose icon") }
        if (icon != null) OutlinedButton(onClick = { DotIcon.clear(ctx); icon = null }) { Text("Use default") }
    }
    if (HomeShortcut.supported(ctx)) {
        OutlinedButton(onClick = { shortcutPicker.launch("image/*") }) { Text("Add home-screen shortcut with my picture") }
        Text(
            "Android does not let an app change its own icon, so this puts a separate shortcut with your picture on the home screen.",
            style = MaterialTheme.typography.bodySmall,
        )
    }
}

@Composable
private fun SecuritySection(activity: FragmentActivity) {
    var lockOn by remember { mutableStateOf(Prefs.lockEnabled(activity)) }
    Heading("Privacy")
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.SpaceBetween) {
        Column(Modifier.weight(1f)) {
            Text("Lock this app")
            Text(
                "Fingerprint or phone PIN to read your notes. The dot always works without it.",
                style = MaterialTheme.typography.bodySmall,
            )
        }
        Switch(
            checked = lockOn,
            onCheckedChange = { on ->
                if (on) {
                    Prefs.setLockEnabled(activity, true); lockOn = true
                } else {
                    // Turning the lock off needs the lock.
                    Lock.authenticate(activity, "Turn off the lock") { ok ->
                        if (ok) { Prefs.setLockEnabled(activity, false); lockOn = false }
                    }
                }
            },
        )
    }
    if (!Lock.available(activity)) {
        Text("This phone has no screen lock set up, so there is nothing to ask for.", style = MaterialTheme.typography.bodySmall)
    }
}

@Composable
private fun PermissionRow(label: String, granted: Boolean, onGrant: () -> Unit) {
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.SpaceBetween) {
        Text(label)
        if (granted) Text("Allowed", color = MaterialTheme.colorScheme.primary) else Button(onClick = onGrant) { Text("Allow") }
    }
}
