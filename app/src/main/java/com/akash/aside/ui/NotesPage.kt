package com.akash.aside.ui

import android.app.DatePickerDialog
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.akash.aside.capture.CaptureRepository
import com.akash.aside.capture.Search
import com.akash.aside.cloud.SupabaseClient
import kotlinx.coroutines.delay
import com.akash.aside.data.Item
import com.akash.aside.data.Kind
import kotlinx.coroutines.launch
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale

private val stamp = DateTimeFormatter.ofPattern("d MMM, HH:mm", Locale.getDefault())
private val dueFormat = DateTimeFormatter.ofPattern("EEE d MMM", Locale.getDefault())

private fun label(kind: String) = when (kind) {
    Kind.JOURNAL -> "Journal"
    Kind.TASK -> "Task"
    else -> "Note"
}

/** Everything captured, newest first, with word search. Tap an item to change it. */
@Composable
fun NotesPage(repo: CaptureRepository, client: SupabaseClient, modifier: Modifier = Modifier) {
    val items by remember { repo.observeVisible() }.collectAsState(emptyList())
    var query by remember { mutableStateOf("") }
    var editing by remember { mutableStateOf<Item?>(null) }
    val shown = remember(items, query) { items.filter { Search.matchesAny(it.text, query) } }

    // Words typed in the search box are also asked of the cloud, which holds every note, including those written on the laptop.
    var cloud by remember { mutableStateOf<List<SupabaseClient.CloudNote>>(emptyList()) }
    LaunchedEffect(query) {
        cloud = emptyList()
        if (query.isBlank() || !client.signedIn) return@LaunchedEffect
        delay(400)
        cloud = try { client.searchNotes(query) } catch (_: Exception) { emptyList() }
    }
    val localIds = remember(items) { items.mapTo(HashSet()) { it.id } }
    val cloudOnly = remember(cloud, localIds) { cloud.filter { it.clientId == null || it.clientId !in localIds } }

    Column(modifier.fillMaxSize()) {
        OutlinedTextField(
            value = query, onValueChange = { query = it },
            placeholder = { Text("Search words") }, singleLine = true,
            modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp),
        )
        if (shown.isEmpty() && cloudOnly.isEmpty()) {
            Text(
                if (query.isBlank()) "Nothing here yet. Tap the dot to write something." else "No matches.",
                style = MaterialTheme.typography.bodyMedium,
                modifier = Modifier.padding(16.dp),
            )
        } else {
            LazyColumn(contentPadding = androidx.compose.foundation.layout.PaddingValues(16.dp, 4.dp, 16.dp, 24.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                items(shown, key = { it.id }) { item -> ItemRow(item) { editing = item } }
                if (cloudOnly.isNotEmpty()) {
                    item(key = "cloud-header") {
                        Text("Elsewhere", style = MaterialTheme.typography.labelMedium, modifier = Modifier.padding(top = 8.dp))
                    }
                    items(cloudOnly.size) { i ->
                        val n = cloudOnly[i]
                        ItemRow(Item(n.clientId ?: "cloud-$i", n.kind, n.text, null, "synced", n.createdAt, n.createdAt)) {}
                    }
                }
            }
        }
    }
    editing?.let { EditItem(it, repo) { editing = null } }
}

@Composable
private fun ItemRow(item: Item, onClick: () -> Unit) {
    Card(Modifier.fillMaxWidth().clickable(onClick = onClick)) {
        Column(Modifier.padding(14.dp)) {
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                Text(label(item.kind), style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.primary)
                Text(
                    Instant.ofEpochMilli(item.createdAt).atZone(ZoneId.systemDefault()).format(stamp),
                    style = MaterialTheme.typography.labelSmall,
                )
            }
            Spacer(Modifier.height(4.dp))
            Text(item.text, maxLines = 4, overflow = TextOverflow.Ellipsis)
            if (item.kind == Kind.TASK && item.dueAt != null) {
                Text(
                    "Due " + Instant.ofEpochMilli(item.dueAt).atZone(ZoneId.systemDefault()).format(dueFormat),
                    style = MaterialTheme.typography.labelSmall,
                )
            }
        }
    }
}

/** Change the words, switch between note / journal / task, set a date, or delete. */
@Composable
private fun EditItem(item: Item, repo: CaptureRepository, onDone: () -> Unit) {
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()
    var text by remember { mutableStateOf(item.text) }
    var kind by remember { mutableStateOf(item.kind) }
    var due by remember { mutableStateOf(item.dueAt?.let { Instant.ofEpochMilli(it).atZone(ZoneId.systemDefault()).toLocalDate() }) }
    var confirmDelete by remember { mutableStateOf(false) }

    fun epoch(d: LocalDate?) = d?.atTime(23, 59)?.atZone(ZoneId.systemDefault())?.toInstant()?.toEpochMilli()

    AlertDialog(
        onDismissRequest = onDone,
        title = { Text("Edit") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    for (k in listOf(Kind.NOTE, Kind.JOURNAL, Kind.TASK)) {
                        FilterChip(selected = kind == k, onClick = { kind = k }, label = { Text(label(k)) })
                    }
                }
                OutlinedTextField(text, { text = it }, modifier = Modifier.fillMaxWidth(), minLines = 3, maxLines = 8)
                if (kind == Kind.TASK) {
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        OutlinedButton(onClick = {
                            val base = due ?: LocalDate.now()
                            DatePickerDialog(ctx, { _, y, m, d -> due = LocalDate.of(y, m + 1, d) }, base.year, base.monthValue - 1, base.dayOfMonth).show()
                        }) { Text(due?.format(dueFormat) ?: "Set date") }
                        if (due != null) TextButton(onClick = { due = null }) { Text("No date") }
                    }
                }
            }
        },
        confirmButton = {
            Button(onClick = {
                scope.launch { repo.edit(item.id, text, kind, epoch(due)) }
                onDone()
            }) { Text("Save") }
        },
        dismissButton = {
            Row {
                TextButton(onClick = { confirmDelete = true }) { Text("Delete", color = MaterialTheme.colorScheme.error) }
                TextButton(onClick = onDone) { Text("Cancel") }
            }
        },
    )
    if (confirmDelete) {
        AlertDialog(
            onDismissRequest = { confirmDelete = false },
            title = { Text("Delete this?") },
            confirmButton = {
                TextButton(onClick = { scope.launch { repo.delete(item.id) }; confirmDelete = false; onDone() }) {
                    Text("Delete", color = MaterialTheme.colorScheme.error)
                }
            },
            dismissButton = { TextButton(onClick = { confirmDelete = false }) { Text("Keep") } },
        )
    }
}
