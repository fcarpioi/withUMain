package com.controlparental.jerico

import android.content.Context
import android.content.Intent
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import android.net.NetworkRequest
import android.util.Log
import androidx.core.content.ContextCompat

class ConnectivityReceiver(private val context: Context) {

    private val connectivityManager = context.getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager

    // Registrar un callback para detectar cambios de conectividad
    fun registerNetworkCallback() {
        val networkRequest = NetworkRequest.Builder()
            .addCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
            .build()

        connectivityManager.registerNetworkCallback(networkRequest, object : ConnectivityManager.NetworkCallback() {
            override fun onAvailable(network: Network) {
                Log.d("ConnectivityReceiver", "Network available, starting service...")

                // Iniciar el servicio en segundo plano cuando la red esté disponible
                val serviceIntent = Intent(context, BackgroundService::class.java)
                ContextCompat.startForegroundService(context, serviceIntent)
            }

            override fun onLost(network: Network) {
                Log.d("ConnectivityReceiver", "Network lost")
                // Manejar la pérdida de conexión si es necesario
            }
        })
    }

    // Método para anular el registro del callback si es necesario
    fun unregisterNetworkCallback() {
        try {
            connectivityManager.unregisterNetworkCallback(ConnectivityManager.NetworkCallback())
            Log.d("ConnectivityReceiver", "NetworkCallback unregistered")
        } catch (e: Exception) {
            Log.e("ConnectivityReceiver", "Error while unregistering NetworkCallback: ${e.message}")
        }
    }
}
