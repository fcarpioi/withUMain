package com.controlparental.jerico

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.IBinder
import androidx.core.app.NotificationCompat
import android.util.Log

class NotificationService : Service() {

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        // Crear la notificación de primer plano
        val notification = createNotification()
        startForeground(1, notification)

        Log.d("NotificationService", "Servicio en primer plano iniciado.")

        // Detener el servicio si no se necesita más trabajo en segundo plano
        stopForeground(true)
        stopSelf()

        return START_NOT_STICKY
    }

    private fun createNotification(): Notification {
        val channelId = "your_channel_id"
        val channelName = "Your Channel Name"
        val notificationManager = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(channelId, channelName, NotificationManager.IMPORTANCE_DEFAULT)
            notificationManager.createNotificationChannel(channel)
        }

        return NotificationCompat.Builder(this, channelId)
            .setContentTitle("Aplicación Instalada")
            .setContentText("La aplicación se ha instalado correctamente y está en ejecución.")
            .setSmallIcon(R.drawable.ic_launcher_foreground) // Asegúrate de que este ícono existe en res/drawable
            .build()
    }

    override fun onBind(intent: Intent?): IBinder? {
        return null
    }
}
