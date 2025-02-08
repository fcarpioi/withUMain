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

class ChargerService : Service() {

    override fun onCreate() {
        super.onCreate()

        // Iniciar el servicio en primer plano
        startForeground(1, createNotification())
        Log.d("ChargerService", "Servicio de cargador iniciado en primer plano.")
        
        // Aquí puedes ejecutar cualquier tarea que necesites mientras el cargador está conectado
        // ...
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        return START_STICKY
    }

    override fun onDestroy() {
        super.onDestroy()
        Log.d("ChargerService", "Servicio de cargador detenido.")
    }

    override fun onBind(intent: Intent?): IBinder? {
        return null
    }

    // Crear la notificación necesaria para el ForegroundService
    private fun createNotification(): Notification {
        val channelId = "ChargerServiceChannel"
        val channelName = "Charger Service"

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                channelId,
                channelName,
                NotificationManager.IMPORTANCE_LOW
            )

            val manager = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
            manager.createNotificationChannel(channel)
        }

        return NotificationCompat.Builder(this, channelId)
            .setContentTitle("Cargador Conectado")
            .setContentText("Ejecutando tareas mientras el dispositivo está cargando.")
            .setSmallIcon(android.R.drawable.ic_menu_compass) // Usa tu propio ícono aquí
            .build()
    }
}
