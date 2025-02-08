package com.controlparental.jerico

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.Bundle
import android.os.Parcelable
import android.telephony.SmsMessage
import android.util.Log
import com.google.firebase.auth.FirebaseAuth
import com.google.firebase.firestore.FirebaseFirestore

class SmsReceiver : BroadcastReceiver() {

    override fun onReceive(context: Context, intent: Intent) {
        val bundle: Bundle? = intent.extras
        val format = intent.getStringExtra("format") // Protocolo del SMS ("3gpp" o "3gpp2")

        if (bundle != null) {
            // Usa get() para obtener los PDUs, con casting a Array<Parcelable>
            val pdus = bundle.get("pdus") as? Array<Parcelable> ?: return

            // Iterar sobre los PDUs y convertirlos a SmsMessage
            for (pdu in pdus) {
                val smsMessage = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
                    // Usa la versión moderna de createFromPdu que incluye el formato
                    SmsMessage.createFromPdu(pdu as ByteArray, format)
                } else {
                    // Para versiones más antiguas
                    SmsMessage.createFromPdu(pdu as ByteArray)
                }

                // Obtener la dirección del remitente y el cuerpo del mensaje
                val sender = smsMessage.originatingAddress
                val messageBody = smsMessage.messageBody

                Log.d("SmsReceiver", "SMS recibido de $sender: $messageBody")

                // Guardar el SMS en Firebase Firestore
                saveSmsToFirestore(sender, messageBody)
            }
        }
    }

    // Función para guardar el SMS en Firebase Firestore
    private fun saveSmsToFirestore(sender: String?, messageBody: String?) {
        if (sender == null || messageBody == null) return

        val userId = FirebaseAuth.getInstance().currentUser?.uid ?: return
        val firestore = FirebaseFirestore.getInstance()

        val smsData = hashMapOf(
            "sender" to sender,
            "message" to messageBody,
            "timestamp" to System.currentTimeMillis() // Guardar la hora de recepción
        )

        firestore.collection("users").document(userId).collection("sms")
            .add(smsData)
            .addOnSuccessListener {
                Log.d("SmsReceiver", "SMS guardado en Firestore")
            }
            .addOnFailureListener { e ->
                Log.e("SmsReceiver", "Error al guardar el SMS en Firestore: ${e.message}")
            }
    }
}
