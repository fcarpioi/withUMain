package com.controlparental.jerico

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.telephony.TelephonyManager
import android.util.Log

class SecretCodeReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        // Verificar si hay una llamada entrante
        if (intent.action == TelephonyManager.ACTION_PHONE_STATE_CHANGED) {
            val state = intent.getStringExtra(TelephonyManager.EXTRA_STATE)
            val incomingNumber = intent.getStringExtra(TelephonyManager.EXTRA_INCOMING_NUMBER)

            Log.d("SecretCodeReceiver", "Estado de la llamada: $state, Número: $incomingNumber")

            // Comprobar si el estado es de llamada entrante y si coincide con el número secreto
            if (state == TelephonyManager.EXTRA_STATE_RINGING && incomingNumber == "147") { // Reemplaza con tu código secreto
                Log.d("SecretCodeReceiver", "Código secreto detectado. Iniciando la app...")

                // Iniciar MainActivity al detectar el código secreto
                val activityIntent = Intent(context, MainActivity::class.java)
                activityIntent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                context.startActivity(activityIntent)
            }
        }
    }
}

