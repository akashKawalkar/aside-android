package com.akash.aside.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import com.akash.aside.Prefs
import com.akash.aside.watch.GitHub
import kotlinx.coroutines.launch

/**
 * The night job makes tomorrow's schedule on GitHub. If it has not by 21:10 (or by 08:00 for today), the phone starts it.
 * That needs a GitHub token that can do only that, which lives only on this phone.
 */
@Composable
fun NightJobSection() {
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()
    var hasToken by remember { mutableStateOf(!Prefs.githubToken(ctx).isNullOrBlank()) }
    var entry by remember { mutableStateOf("") }
    var message by remember { mutableStateOf<String?>(null) }
    var busy by remember { mutableStateOf(false) }

    Text("Night job", style = MaterialTheme.typography.titleMedium)
    Text(
        "If tomorrow's schedule does not exist by 21:10, this phone starts the night job. You get a notification if the job fails even after its retry.",
        style = MaterialTheme.typography.bodySmall,
    )
    if (hasToken) {
        Text("A GitHub token is saved on this phone.", style = MaterialTheme.typography.bodyMedium)
        Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            Button(enabled = !busy, onClick = {
                busy = true
                scope.launch {
                    message = when (val r = GitHub.check(Prefs.githubToken(ctx).orEmpty())) {
                        GitHub.Outcome.Ok -> "The token works."
                        is GitHub.Outcome.Rejected -> r.message
                        is GitHub.Outcome.Transient -> "Could not reach GitHub: ${r.message}"
                    }
                    busy = false
                }
            }) { Text(if (busy) "Checking…" else "Check token") }
            OutlinedButton(onClick = { Prefs.setGithubToken(ctx, null); hasToken = false; message = null }) { Text("Remove") }
        }
    } else {
        OutlinedTextField(
            entry, { entry = it }, label = { Text("GitHub token") }, singleLine = true, modifier = Modifier.fillMaxWidth(),
            visualTransformation = PasswordVisualTransformation(), keyboardOptions = androidx.compose.foundation.text.KeyboardOptions(keyboardType = KeyboardType.Password),
        )
        Button(enabled = entry.isNotBlank(), onClick = { Prefs.setGithubToken(ctx, entry); entry = ""; hasToken = true; message = "Saved." }) { Text("Save token") }
    }
    message?.let { Text(it, style = MaterialTheme.typography.bodySmall) }
    Prefs.watchNote(ctx)?.let { Text(it, style = MaterialTheme.typography.bodySmall) }
}
