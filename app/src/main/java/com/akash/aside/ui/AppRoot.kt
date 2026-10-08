package com.akash.aside.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.PrimaryTabRow
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Tab
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.fragment.app.FragmentActivity
import com.akash.aside.AsideApp
import kotlinx.coroutines.launch

/** Two sides of one screen, joined by a swipe: Notes on the left, Settings on the right. */
@Composable
fun AppRoot(activity: FragmentActivity, locked: Boolean, onUnlocked: () -> Unit) {
    if (locked) {
        LockScreen(activity, onUnlocked)
        return
    }
    val repo = (activity.application as AsideApp).repo
    val pager = rememberPagerState { 2 }
    val scope = rememberCoroutineScope()
    Scaffold(modifier = Modifier.fillMaxSize()) { inner ->
        Column(Modifier.padding(inner)) {
            PrimaryTabRow(selectedTabIndex = pager.currentPage) {
                Tab(selected = pager.currentPage == 0, onClick = { scope.launch { pager.animateScrollToPage(0) } }, text = { Text("Notes") })
                Tab(selected = pager.currentPage == 1, onClick = { scope.launch { pager.animateScrollToPage(1) } }, text = { Text("Settings") })
            }
            HorizontalPager(state = pager, modifier = Modifier.fillMaxSize()) { page ->
                if (page == 0) NotesPage(repo, (activity.application as AsideApp).cloud) else SettingsPage(activity, repo)
            }
        }
    }
}

@Composable
private fun LockScreen(activity: FragmentActivity, onUnlocked: () -> Unit) {
    var tried by remember { mutableStateOf(false) }
    fun ask() = Lock.authenticate(activity) { ok -> if (ok) onUnlocked() }
    // Ask once on arrival; the button is there if the prompt is dismissed.
    LaunchedEffect(Unit) { if (!tried) { tried = true; ask() } }
    Scaffold(modifier = Modifier.fillMaxSize()) { inner ->
        Column(
            Modifier.fillMaxSize().padding(inner).padding(24.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp, Alignment.CenterVertically),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Text("Aside is locked", style = MaterialTheme.typography.titleLarge)
            Button(onClick = { ask() }) { Text("Unlock") }
        }
    }
}
