package com.akash.aside

import android.app.Application
import androidx.room.Room
import com.akash.aside.capture.CaptureRepository
import com.akash.aside.cloud.Sync
import com.akash.aside.cloud.SupabaseClient
import com.akash.aside.data.AppDb
import com.akash.aside.data.MIGRATION_1_2
import com.akash.aside.data.MIGRATION_2_3
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch

/** Work that must finish even if the screen or service that started it goes away. */
object AppScope : CoroutineScope {
    override val coroutineContext = SupervisorJob() + Dispatchers.IO
}

class AsideApp : Application() {
    val db: AppDb by lazy { Room.databaseBuilder(this, AppDb::class.java, "aside.db").addMigrations(MIGRATION_1_2, MIGRATION_2_3).build() }
    val cloud: SupabaseClient by lazy { SupabaseClient(this) }
    val repo: CaptureRepository by lazy { CaptureRepository(db.itemDao(), onChanged = { Sync.kick(this) }) }

    override fun onCreate() {
        super.onCreate()
        // Anything typed when the app died is kept (or cleared if empty).
        AppScope.launch { repo.recoverOpen() }
        Sync.schedulePeriodic(this)
        runCatching { Prefs.sealOldSecrets(this) }
    }
}
