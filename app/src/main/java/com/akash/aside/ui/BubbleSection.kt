package com.akash.aside.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import com.akash.aside.bubble.BubbleStore

/** The glow around the dot, the apps that feed it, and what each typed prefix does. */
@Composable
fun BubbleSection() {
    val ctx = LocalContext.current
    var enabled by remember { mutableStateOf(BubbleStore.enabled(ctx)) }
    var picking by remember { mutableStateOf(false) }

    Text("Dot glow and questions", style = MaterialTheme.typography.titleMedium)
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.SpaceBetween) {
        Column(Modifier.weight(1f)) {
            Text("Make the dot glow when I am stuck on flagged apps")
            Text(
                "Amber glow after about 10 minutes in the last 30, red after about 30. Blue when a question is waiting. " +
                    "No sound, no notification: tap the dot when you want to answer, or choose Not now to dismiss it. " +
                    "Switch this off for no glow at all. Needs Usage access.",
                style = MaterialTheme.typography.bodySmall,
            )
        }
        Switch(checked = enabled, onCheckedChange = { enabled = it; BubbleStore.setEnabled(ctx, it) })
    }
    OutlinedButton(onClick = { picking = true }) { Text("Flagged apps") }
    if (picking) {
        AppPickerDialog("Flagged apps", BubbleStore.flagged(ctx), { BubbleStore.setFlagged(ctx, it) }) { picking = false }
    }

    Text("What you can type", style = MaterialTheme.typography.titleSmall)
    Text(
        "Start any note with a prefix:\n" +
            "n: note      j: journal      t: task\n" +
            "p: an instruction for the next plan only (for example \"no calls after 6\")\n" +
            "r: a standing rule, saved for you to review and then moved into your preferences\n" +
            "It works the same from the dot, voice typing and the sheet's text box.",
        style = MaterialTheme.typography.bodySmall,
    )
}
