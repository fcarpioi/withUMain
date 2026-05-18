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
import androidx.core.app.NotificationCompat
import android.location.Location
import com.google.android.gms.location.*
import com.google.firebase.auth.FirebaseAuth
import com.google.firebase.firestore.FirebaseFirestore
import com.google.firebase.firestore.GeoPoint
import com.google.firebase.firestore.ListenerRegistration
import com.google.firebase.firestore.SetOptions
import android.util.Log
import androidx.core.content.ContextCompat
import android.Manifest
import java.util.Date

class ForegroundService : Service() {
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
            val deviceId = getStoredDeviceId() ?: run {
                Log.e("ForegroundService", "DeviceId no disponible; no se puede guardar ubicacion")
                return
            }
            val geoPoint = GeoPoint(location.latitude, location.longitude)
            val timestamp = Date()
            val locationData = hashMapOf(
                "location" to geoPoint,
                "timestamp" to timestamp
            )
            val deviceDocRef = firestore.collection("users")
                .document(user.uid)
                .collection("devices")
                .document(deviceId)

            deviceDocRef.collection("locations")
                .add(locationData)
                .addOnSuccessListener {
                    Log.d("ForegroundService", "Ubicación enviada con éxito a Firestore")
                }
                .addOnFailureListener {
                    Log.e("ForegroundService", "Error al enviar la ubicación a Firestore", it)
                }

            deviceDocRef.set(
                hashMapOf(
                    "lastCoordinate" to geoPoint,
                    "lastTimeStamp" to timestamp
                ),
                SetOptions.merge()
            ).addOnFailureListener {
                Log.e("ForegroundService", "Error al actualizar ultimo punto", it)
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
        val deviceId = getStoredDeviceId() ?: return

        firestoreListener = firestore.collection("users").document(userId)
            .collection("devices").document(deviceId)
            .addSnapshotListener { documentSnapshot, error ->
                if (error != null) {
                    Log.e("ForegroundService", "Error al escuchar cambios en Firestore", error)
                    return@addSnapshotListener
                }

                if (documentSnapshot != null && documentSnapshot.exists()) {
                    val locationUpdateInterval = documentSnapshot.getLong("locationUpdateInterval") ?: 15000L
                    trackingEnabled = documentSnapshot.getBoolean("trackingEnabled") ?: true
                    val recordingEnabled = documentSnapshot.getBoolean("recordingEnabled") ?: true

                    Log.d("ForegroundService", "Configuración actualizada: trackingEnabled=$trackingEnabled, recordingEnabled=$recordingEnabled, locationUpdateInterval=$locationUpdateInterval")

                    if (trackingEnabled) {
                        startLocationUpdates(locationUpdateInterval)
                    } else {
                        stopLocationUpdates()
                    }
                } else {
                    createDefaultDeviceDocument(userId, deviceId)
                }
            }
    }

    private fun createDefaultDeviceDocument(userId: String, deviceId: String) {
        val defaultData = hashMapOf(
            "locationUpdateInterval" to 15000L, // 15 segundos en milisegundos
            "trackingEnabled" to true,
            "recordingEnabled" to true
        )

        firestore.collection("users").document(userId)
            .collection("devices").document(deviceId)
            .set(defaultData, SetOptions.merge())
            .addOnSuccessListener {
                Log.d("ForegroundService", "Documento creado con valores predeterminados")
            }
            .addOnFailureListener { e ->
                Log.e("ForegroundService", "Error al crear el documento en Firestore", e)
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
            Log.d("ForegroundService", "Permisos de ubicación no concedidos")
            return
        }

        fusedLocationClient.requestLocationUpdates(locationRequest, locationCallback, Looper.getMainLooper())
        Log.d("ForegroundService", "Actualizaciones de ubicación iniciadas con intervalo de $locationUpdateInterval milisegundos")
    }

    private fun stopLocationUpdates() {
        fusedLocationClient.removeLocationUpdates(locationCallback)
        Log.d("ForegroundService", "Actualizaciones de ubicación detenidas")
    }

    private fun getStoredDeviceId(): String? {
        return getSharedPreferences("UserPrefs", Context.MODE_PRIVATE)
            .getString("idDevice", null)
    }

    private fun createNotification(): Notification {
        val channelId = "ForegroundServiceChannel"
        val channel = NotificationChannel(
            channelId,
            "Foreground Service",
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
