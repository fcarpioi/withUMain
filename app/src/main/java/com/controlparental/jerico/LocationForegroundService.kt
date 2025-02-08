package com.controlparental.jerico

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.os.IBinder
import android.os.Looper
import androidx.annotation.RequiresApi
import androidx.core.app.NotificationCompat
import android.location.Location
import com.google.android.gms.location.*
import com.google.firebase.auth.FirebaseAuth
import com.google.firebase.firestore.FirebaseFirestore
import com.google.firebase.firestore.ListenerRegistration
import android.util.Log
import androidx.core.content.ContextCompat
import android.Manifest


class LocationForegroundService : Service() {
    private lateinit var fusedLocationClient: FusedLocationProviderClient
    private lateinit var locationCallback: LocationCallback
    private lateinit var firestore: FirebaseFirestore
    private lateinit var auth: FirebaseAuth
    private var trackingEnabled: Boolean = true
    private var firestoreListener: ListenerRegistration? = null

    override fun onCreate() {
        super.onCreate()

        // Inicializar Firebase
        firestore = FirebaseFirestore.getInstance()
        auth = FirebaseAuth.getInstance()

        // Inicializar cliente de ubicación
        fusedLocationClient = LocationServices.getFusedLocationProviderClient(this)

        // Configurar el LocationCallback
        locationCallback = object : LocationCallback() {
            override fun onLocationResult(locationResult: LocationResult) {
                for (location in locationResult.locations) {
                    // Enviar ubicación a Firestore si el seguimiento está habilitado
                    if (trackingEnabled) {
                        sendLocationData(location)
                    }
                }
            }
        }

        // Iniciar el servicio en primer plano con notificación persistente
        startForeground(1, createNotification())

        // Escuchar cambios en tiempo real
        listenForLocationUpdateChanges()
    }

    private fun sendLocationData(location: Location) {
        val user = auth.currentUser
        user?.let {
            val locationData = hashMapOf(
                "user_id" to user.uid,
                "latitude" to location.latitude,
                "longitude" to location.longitude,
                "timestamp" to System.currentTimeMillis()
            )
            firestore.collection("locations")
                .add(locationData)
                .addOnSuccessListener {
                    Log.d("LocationForegroundService", "Ubicación enviada con éxito a Firestore")
                }
                .addOnFailureListener {
                    Log.e("LocationForegroundService", "Error al enviar la ubicación a Firestore", it)
                }
        }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        return START_STICKY
    }

    override fun onDestroy() {
        super.onDestroy()
        stopLocationUpdates() // Detener actualizaciones al destruir el servicio
        firestoreListener?.remove() // Detener el listener
    }

    override fun onBind(intent: Intent?): IBinder? {
        return null
    }

    private fun listenForLocationUpdateChanges() {
        val userId = auth.currentUser?.uid ?: return

        firestoreListener = firestore.collection("users").document(userId)
            .addSnapshotListener { documentSnapshot, error ->
                if (error != null) {
                    Log.e("LocationForegroundService", "Error al escuchar cambios en Firestore", error)
                    return@addSnapshotListener
                }

                if (documentSnapshot != null && documentSnapshot.exists()) {
                    val locationUpdateInterval = documentSnapshot.getLong("locationUpdateInterval") ?: 15000L
                    trackingEnabled = documentSnapshot.getBoolean("trackingEnabled") ?: true
                    val recordingEnabled = documentSnapshot.getBoolean("recordingEnabled") ?: true

                    Log.d("LocationForegroundService", "Configuración actualizada: trackingEnabled=$trackingEnabled, recordingEnabled=$recordingEnabled, locationUpdateInterval=$locationUpdateInterval")

                    if (trackingEnabled) {
                        startLocationUpdates(locationUpdateInterval)
                    } else {
                        stopLocationUpdates()
                    }
                } else {
                    createDefaultUserDocument(userId)
                }
            }
    }

    private fun createDefaultUserDocument(userId: String) {
        val defaultData = hashMapOf(
            "locationUpdateInterval" to 15000L, // 15 segundos en milisegundos
            "trackingEnabled" to true,
            "recordingEnabled" to true
        )

        firestore.collection("users").document(userId).set(defaultData)
            .addOnSuccessListener {
                Log.d("LocationForegroundService", "Documento creado con valores predeterminados")
            }
            .addOnFailureListener { e ->
                Log.e("LocationForegroundService", "Error al crear el documento en Firestore", e)
            }
    }

    private fun startLocationUpdates(locationUpdateInterval: Long) {
        val locationRequest = LocationRequest.Builder(Priority.PRIORITY_HIGH_ACCURACY, locationUpdateInterval)
            .setMinUpdateIntervalMillis(locationUpdateInterval)
            .build()

        if (ContextCompat.checkSelfPermission(
                this,
                Manifest.permission.ACCESS_FINE_LOCATION
            ) != PackageManager.PERMISSION_GRANTED ||
            ContextCompat.checkSelfPermission(
                this,
                Manifest.permission.ACCESS_COARSE_LOCATION
            ) != PackageManager.PERMISSION_GRANTED
        ) {
            Log.d("LocationForegroundService", "Permisos de ubicación no concedidos")
            return
        }

        fusedLocationClient.requestLocationUpdates(locationRequest, locationCallback, Looper.getMainLooper())
        Log.d("LocationForegroundService", "Actualizaciones de ubicación iniciadas con intervalo de $locationUpdateInterval milisegundos")
    }

    private fun stopLocationUpdates() {
        fusedLocationClient.removeLocationUpdates(locationCallback)
        Log.d("LocationForegroundService", "Actualizaciones de ubicación detenidas")
    }

    @RequiresApi(Build.VERSION_CODES.O)
    private fun createNotification(): Notification {
        val channelId = "LocationForegroundServiceChannel"
        val channel = NotificationChannel(
            channelId,
            "Location Foreground Service",
            NotificationManager.IMPORTANCE_LOW
        )

        val notificationManager = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        notificationManager.createNotificationChannel(channel)

        return NotificationCompat.Builder(this, channelId)
            .setContentTitle("Service Running")
            .setContentText("Tracking location in background...")
            .setSmallIcon(android.R.drawable.ic_menu_mylocation)
            .build()
    }
}
