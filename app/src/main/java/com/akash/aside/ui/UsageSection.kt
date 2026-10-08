package com.akash.aside.ui

import android.content.Intent
import android.net.Uri
import android.provider.Settings
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Checkbox
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.collectAsState
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
import com.akash.aside.AsideApp
import com.akash.aside.Prefs
import com.akash.aside.usage.UsageCollector

/** Which apps were in front today. Only the app names are kept (no titles, no content), and ignored apps are never counted. */
@Composable
fun UsageSection() {
    val ctx = LocalContext.current
    val app = ctx.applicationContext as AsideApp
    var access by remember { mutableStateOf(UsageCollector.hasAccess(ctx)) }
    var enabled by remember { mutableStateOf(Prefs.usageEnabled(ctx)) }
    var picking by remember { mutableStateOf(false) }
    val waiting by remember { app.db.usageDao().observePending() }.collectAsState(0)

    val lifecycle = LocalLifecycleOwner.current.lifecycle
    DisposableEffect(lifecycle) {
        val obs = LifecycleEventObserver { _, e -> if (e == Lifecycle.Event.ON_RESUME) access = UsageCollector.hasAccess(ctx) }
        lifecycle.addObserver(obs)
        onDispose { lifecycle.removeObserver(obs) }
    }

    Text("App usage", style = MaterialTheme.typography.titleMedium)
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.SpaceBetween) {
        Column(Modifier.weight(1f)) {
            Text("Track which apps I use")
            Text("App names only, never what is on screen.", style = MaterialTheme.typography.bodySmall)
        }
        Switch(checked = enabled, onCheckedChange = { enabled = it; Prefs.setUsageEnabled(ctx, it) })
    }
    if (!access) {
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.SpaceBetween) {
            Text("Usage access")
            Button(onClick = {
                ctx.startActivity(Intent(Settings.ACTION_USAGE_ACCESS_SETTINGS, Uri.parse("package:${ctx.packageName}")))
            }) { Text("Allow") }
        }
        Text("Android needs you to switch this on yourself: find Aside in the list and turn it on.", style = MaterialTheme.typography.bodySmall)
    } else {
        Text(
            if (waiting == 0) "Usage access is on. Nothing is waiting to be sent." else "Usage access is on. $waiting sessions waiting to be sent.",
            style = MaterialTheme.typography.bodySmall,
        )
    }
    OutlinedButton(onClick = { picking = true }) { Text("Apps to ignore") }
    if (picking) IgnoreDialog { picking = false }
}

/** Banking apps are ticked at first; tick or untick anything. */
@Composable
private fun IgnoreDialog(onDone: () -> Unit) {
    val ctx = LocalContext.current
    val apps = remember { UsageCollector.launchableApps(ctx) }
    var ignored by remember { mutableStateOf(UsageCollector.ignoredSet(ctx).filter { p -> apps.any { it.pkg == p } }.toSet()) }
    var query by remember { mutableStateOf("") }
    val shown = remember(query) { apps.filter { query.isBlank() || it.label.contains(query, ignoreCase = true) || it.pkg.contains(query, ignoreCase = true) } }

    AlertDialog(
        onDismissRequest = onDone,
        title = { Text("Apps to ignore") },
        text = {
            Column {
                OutlinedTextField(query, { query = it }, placeholder = { Text("Search apps") }, singleLine = true, modifier = Modifier.fillMaxWidth())
                LazyColumn(Modifier.height(380.dp).padding(top = 8.dp)) {
                    items(shown, key = { it.pkg }) { a ->
                        Row(
                            Modifier.fillMaxWidth().clickable { ignored = if (a.pkg in ignored) ignored - a.pkg else ignored + a.pkg },
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Checkbox(checked = a.pkg in ignored, onCheckedChange = { ignored = if (it) ignored + a.pkg else ignored - a.pkg })
                            Text(a.label)
                        }
                    }
                }
            }
        },
        confirmButton = { TextButton(onClick = { Prefs.setIgnoredApps(ctx, ignored); onDone() }) { Text("Save") } },
        dismissButton = { TextButton(onClick = onDone) { Text("Cancel") } },
    )
}
