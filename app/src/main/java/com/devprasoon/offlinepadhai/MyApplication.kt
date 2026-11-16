package com.devprasoon.offlinepadhai

import android.app.Application
import android.util.Log
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch

class MyApplication : Application() {
    override fun onCreate() {
        super.onCreate()
        CoroutineScope(Dispatchers.IO).launch {
            try {
                val engine = TutorEngine(applicationContext)
                if (engine.isModelPresent() && !engine.isReady()) {
                    engine.init()
                    Log.i("MyApplication", "Model pre-warmed on startup")
                }
            } catch (e: Exception) {
                Log.w("MyApplication", "Model pre-warm skipped/failed", e)
            }
        }
    }
}
