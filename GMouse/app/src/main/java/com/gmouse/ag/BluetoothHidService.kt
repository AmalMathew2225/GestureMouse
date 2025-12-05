package com.gmouse.ag

import android.app.*
import android.content.Context
import android.content.Intent
import android.os.IBinder
import androidx.core.app.NotificationCompat

class BluetoothHidService : Service() {
    companion object {
        private const val NOTIFICATION_ID = 1
        private const val CHANNEL_ID = "gmouse_hid_service"
        var hidCore: BluetoothHidCore? = null

        fun start(context: Context) {
            val intent = Intent(context, BluetoothHidService::class.java)
            context.startForegroundService(intent)
        }
        
        fun stop(context: Context) {
            context.stopService(Intent(context, BluetoothHidService::class.java))
        }
    }

    override fun onCreate() {
        super.onCreate()
        createNotificationChannel()
        startForeground(NOTIFICATION_ID, createNotification())
        hidCore = BluetoothHidCore(this)
        hidCore?.start()
    }

    override fun onDestroy() {
        super.onDestroy()
        hidCore?.stop()
        hidCore = null
    }

    override fun onBind(intent: Intent?): IBinder? = null

    private fun createNotificationChannel() {
        val channel = NotificationChannel(
            CHANNEL_ID,
            "GMouse HID Service",
            NotificationManager.IMPORTANCE_LOW
        )
        channel.description = "Keeps GMouse running for Bluetooth HID"
        val notificationManager = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        notificationManager.createNotificationChannel(channel)
    }

    private fun createNotification(): Notification {
        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle("GMouse")
            .setContentText("HID service running")
            .setSmallIcon(android.R.drawable.ic_menu_compass)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .build()
    }
}
