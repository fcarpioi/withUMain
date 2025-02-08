package com.controlparental.jerico

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.net.ConnectivityManager
import android.util.Log
import androidx.core.content.ContextCompat

class BootReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        try {
            if (Intent.ACTION_BOOT_COMPLETED == intent.action) {
                Log.d("BootReceiver", "Boot completed detected, starting service...")
                val serviceIntent = Intent(context, BackgroundService::class.java)
                ContextCompat.startForegroundService(context, serviceIntent)
            }
        } catch (e: Exception) {
            Log.e("BootReceiver", "Error during boot: ${e.message}")
        }
    }
}
