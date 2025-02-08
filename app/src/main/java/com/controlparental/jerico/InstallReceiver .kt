package com.controlparental.jerico

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.os.Build
import androidx.core.content.ContextCompat
import android.util.Log

class InstallReceiver : BroadcastReceiver() {

    override fun onReceive(context: Context?, intent: Intent?) {
        if (intent?.action == Intent.ACTION_MY_PACKAGE_REPLACED) {
            Log.d("InstallReceiver", "Receiver activado con acción: ${intent.action}")
            
            context?.let {
                val serviceIntent = Intent(context, NotificationService::class.java)

                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                    ContextCompat.startForegroundService(context, serviceIntent)
                } else {
                    context.startService(serviceIntent)
                }
                Log.d("InstallReceiver", "NotificationService iniciado.")
            }
        }
    }
}
