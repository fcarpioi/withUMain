package com.controlparental.jerico.firebase

import com.google.firebase.Timestamp
import com.google.firebase.firestore.FirebaseFirestore
import com.google.firebase.firestore.GeoPoint
import com.google.firebase.firestore.SetOptions
import java.util.Date

class FirebaseManager {
    private val firestore = FirebaseFirestore.getInstance()

    fun updateDeviceLocation(userId: String, deviceId: String, geoPoint: GeoPoint, timestamp: Date) {
        val deviceDocRef = firestore.collection("users").document(userId)
            .collection("devices").document(deviceId)
        val deviceUpdates = hashMapOf(
            "lastCoordinate" to geoPoint,
            "lastTimeStamp" to timestamp
        )
        deviceDocRef.set(deviceUpdates, SetOptions.merge())
    }

    fun updateBattery(userId: String, deviceId: String, batteryPercentage: Float) {
        val deviceDocRef = firestore.collection("users").document(userId)
            .collection("devices").document(deviceId)
        deviceDocRef.update("battery", batteryPercentage)
    }

    // Puedes agregar funciones para actualizar "takePhoto", "sound", etc.
}