package dev.owl.app.sync

import android.app.Service
import android.content.Intent
import android.os.IBinder

/** Placeholder; foreground sync loop is implemented in the sync layer. */
class SyncService : Service() {
    override fun onBind(intent: Intent?): IBinder? = null
}
