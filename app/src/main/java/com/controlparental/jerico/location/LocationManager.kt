// File: com/controlparental/jerico/location/LocationManager.kt
package com.controlparental.jerico.location

import android.annotation.SuppressLint
import android.content.Context
import android.location.Location
import android.os.Handler
import android.os.Looper
import android.util.Log
import com.google.android.gms.location.*
import com.google.firebase.firestore.FirebaseFirestore
import com.google.firebase.firestore.GeoPoint
import java.util.Date

class LocationManager(private val context: Context) {
    private val fusedLocationClient = LocationServices.getFusedLocationProviderClient(context)
    private var locationCallback: LocationCallback? = null
    private var lastUpdateTimestamp: Long = 0
    var updateInterval: Long = 10000L  // Por defecto 10 segundos

    @SuppressLint("MissingPermission")
    fun startLocationUpdates(userId: String, deviceId: String, onLocationUpdate: ((GeoPoint, Date) -> Unit)? = null) {
        val locationRequest = LocationRequest.Builder(Priority.PRIORITY_HIGH_ACCURACY, updateInterval)
            .setMinUpdateIntervalMillis(updateInterval)
            .build()

        locationCallback = object : LocationCallback() {
            override fun onLocationResult(locationResult: LocationResult) {
                for (location in locationResult.locations) {
                    Log.d("LocationManager", "Location: ${location.latitude}, ${location.longitude}")
                    sendLocationToFirestore(userId, deviceId, location)
                    onLocationUpdate?.invoke(GeoPoint(location.latitude, location.longitude), Date())
                }
            }
        }
        fusedLocationClient.requestLocationUpdates(locationRequest, locationCallback!!, Looper.getMainLooper())
    }

    fun stopLocationUpdates() {
        locationCallback?.let {
            fusedLocationClient.removeLocationUpdates(it)
            Log.d("LocationManager", "Location updates stopped")
        }
    }

    private fun sendLocationToFirestore(userId: String, deviceId: String, location: Location) {
        val currentTime = System.currentTimeMillis()
        if (currentTime - lastUpdateTimestamp >= updateInterval) {
            lastUpdateTimestamp = currentTime
            val geoPoint = GeoPoint(location.latitude, location.longitude)
            val timestamp = Date()
            // Aquí podrías llamar a FirebaseManager para actualizar la ubicación,
            // o enviar la ubicación directamente a una subcolección.
            val firestore = FirebaseFirestore.getInstance()
            firestore.collection("users").document(userId)
                .collection("devices").document(deviceId)
                .collection("locations")
                .add(hashMapOf("location" to geoPoint, "timestamp" to timestamp))
                .addOnSuccessListener {
                    Log.d("LocationManager", "Location recorded for device: $deviceId")
                }
                .addOnFailureListener { e ->
                    Log.e("LocationManager", "Error saving location: ${e.message}")
                }
        }
    }
}