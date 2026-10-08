package com.akash.aside

import android.app.Activity
import android.content.Intent
import android.os.Bundle
import android.widget.Toast
import com.akash.aside.data.Kind
import kotlinx.coroutines.launch

/** "Share to Aside" from any app: the shared text is saved as a note straight away. */
class ShareActivity : Activity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val text = intent?.takeIf { it.action == Intent.ACTION_SEND }?.getStringExtra(Intent.EXTRA_TEXT).orEmpty()
        if (text.isNotBlank()) {
            val repo = (application as AsideApp).repo
            AppScope.launch { repo.commit(repo.newId(), text, Kind.NOTE, null) }
            Toast.makeText(this, "Saved", Toast.LENGTH_SHORT).show()
        }
        finish()
    }
}
