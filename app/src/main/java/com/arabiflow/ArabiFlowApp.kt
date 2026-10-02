package com.arabiflow

import android.app.Application
import android.app.NotificationChannel
import android.app.NotificationManager
import android.os.Build
import com.arabiflow.data.ConversionDatabase

class ArabiFlowApp : Application() {
    val db by lazy { ConversionDatabase.get(this) }
    override fun onCreate() {
        super.onCreate()
        if (Build.VERSION.SDK_INT >= 26) {
            getSystemService(NotificationManager::class.java).createNotificationChannel(
                NotificationChannel("conversion", "معالجة ملفات APK", NotificationManager.IMPORTANCE_LOW)
            )
        }
    }
}
