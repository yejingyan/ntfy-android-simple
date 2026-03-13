package com.ntfy.wear

import android.app.Application
import com.ntfy.wear.db.StorageManager
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob

class NtfyApplication : Application() {
    
    val applicationScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    
    lateinit var storageManager: StorageManager
        private set
    
    override fun onCreate() {
        super.onCreate()
        storageManager = StorageManager(this)
    }
}