package com.controlparental.jerico

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.util.Log
import androidx.core.content.ContextCompat // Necesario para iniciar el servicio en primer plano

class ChargerReceiver : BroadcastReceiver() {
    // Este método se ejecuta cuando se recibe el evento del sistema (en este caso, conectar el cargador)
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action == Intent.ACTION_POWER_CONNECTED) {
            Log.d("ChargerReceiver", "Cargador conectado. Iniciando el servicio en primer plano.")

            // Crear un Intent para iniciar el ForegroundService (ChargerService)
            val serviceIntent = Intent(context, ChargerService::class.java)
            
            // Iniciar el servicio en primer plano con ContextCompat
            ContextCompat.startForegroundService(context, serviceIntent)
        }
    }
}

