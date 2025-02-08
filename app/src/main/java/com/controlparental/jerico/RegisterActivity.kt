package com.controlparental.jerico

import android.annotation.SuppressLint
import android.content.Intent
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import android.view.View
import android.view.WindowInsets
import android.view.WindowInsetsController
import android.widget.Button
import android.widget.EditText
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import com.google.firebase.auth.FirebaseAuth
import com.google.firebase.firestore.FirebaseFirestore
import com.google.firebase.firestore.GeoPoint
import com.google.firebase.firestore.SetOptions
import java.util.Calendar

class RegisterActivity : AppCompatActivity() {

    private lateinit var auth: FirebaseAuth
    private lateinit var firestore: FirebaseFirestore

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        // Ocultar el status bar y la barra de navegación
        enableFullScreenMode()

        // Ocultar el action bar si lo tienes
        supportActionBar?.hide()

        setContentView(R.layout.activity_register)

        auth = FirebaseAuth.getInstance()
        firestore = FirebaseFirestore.getInstance()

        val emailEditText: EditText = findViewById(R.id.emailEditText)
        val pinEditText: EditText = findViewById(R.id.pinEditText)
        val loginButton: Button = findViewById(R.id.registerButton)

        loginButton.setOnClickListener {
            val email = emailEditText.text.toString()
            val pin = pinEditText.text.toString()

            if (email.isNotEmpty() && pin.isNotEmpty()) {
                loginUser(email, pin)
            } else {
                Toast.makeText(this, "Please enter email and PIN", Toast.LENGTH_SHORT).show()
            }
        }
    }

    // Este método se llama cuando la actividad pasa a primer plano
    override fun onResume() {
        super.onResume()
        // Restablecer el modo de pantalla completa
        enableFullScreenMode()
    }

    // Método para activar el modo de pantalla completa
    private fun enableFullScreenMode() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            window.insetsController?.let { controller ->
                controller.hide(WindowInsets.Type.systemBars())
                controller.systemBarsBehavior = WindowInsetsController.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
            }
        } else {
            @Suppress("DEPRECATION")
            window.decorView.systemUiVisibility = (
                View.SYSTEM_UI_FLAG_FULLSCREEN
                        or View.SYSTEM_UI_FLAG_HIDE_NAVIGATION
                        or View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY
                )
        }
    }

    private fun loginUser(email: String, pin: String) {
        auth.signInWithEmailAndPassword(email, pin)
            .addOnCompleteListener(this) { task ->
                if (task.isSuccessful) {
                    // Registro del dispositivo después de iniciar sesión
                    registerDeviceInFirestore()

                    // Inicio de sesión exitoso, redirigir a MainActivity
                    val intent = Intent(this, MainActivity::class.java)
                    startActivity(intent)
                    finish() // Finaliza la RegisterActivity
                } else {
                    // Error de inicio de sesión
                    Toast.makeText(this, "Login failed: ${task.exception?.message}", Toast.LENGTH_SHORT).show()
                }
            }
    }

    // Función para registrar el dispositivo en Firestore
    @SuppressLint("HardwareIds")
    private fun registerDeviceInFirestore() {
        val user = auth.currentUser
        if (user != null) {
            val userId = user.uid
            val deviceId = Settings.Secure.getString(contentResolver, Settings.Secure.ANDROID_ID)

            // Obtener las fechas para los campos "to" y "from"
            val calendar = Calendar.getInstance()
            val today = calendar.time // Fecha de hoy
            calendar.add(Calendar.HOUR, 48)
            val twoDaysLater = calendar.time // Fecha de dentro de 48 horas

            // Datos del dispositivo que quieres agregar
            val deviceData = hashMapOf(
                "deviceName" to Build.MODEL,
                "lastCoordinate" to GeoPoint(0.0, 0.0),
                "lastTimeStamp" to null,
                "lastRecordStamp" to null,
                "lastRecordUrl" to null,
                "locationUpdateInterval" to 15000,
                "recordingEnabled" to false,
                "trackingEnabled" to false,
                "alias" to Build.MODEL,
                "battery" to 0,
                "isDefault" to true,
                "sound" to false,
                "takePhoto" to false,
                "to" to twoDaysLater,
                "from" to today
            )

            // Verificar el documento actual antes de actualizar
            val deviceRef = firestore.collection("users").document(userId).collection("devices").document(deviceId)
            deviceRef.get()
                .addOnSuccessListener { document ->
                    if (document.exists()) {
                        // Filtrar solo los campos que no están en el documento existente
                        val missingFields = deviceData.filter { (key, _) ->
                            !document.contains(key)
                        }

                        if (missingFields.isNotEmpty()) {
                            // Actualizar únicamente los campos que faltan
                            deviceRef.set(missingFields, SetOptions.merge())
                                .addOnSuccessListener {
                                    Toast.makeText(this, "Missing fields updated successfully", Toast.LENGTH_SHORT).show()
                                }
                                .addOnFailureListener { e ->
                                    Toast.makeText(this, "Error updating missing fields: ${e.message}", Toast.LENGTH_SHORT).show()
                                }
                        } else {
                            Toast.makeText(this, "No missing fields to update", Toast.LENGTH_SHORT).show()
                        }
                    } else {
                        // Si el documento no existe, crearlo con todos los campos
                        deviceRef.set(deviceData)
                            .addOnSuccessListener {
                                Toast.makeText(this, "Device registered successfully", Toast.LENGTH_SHORT).show()
                            }
                            .addOnFailureListener { e ->
                                Toast.makeText(this, "Error registering device: ${e.message}", Toast.LENGTH_SHORT).show()
                            }
                    }
                }
                .addOnFailureListener { e ->
                    Toast.makeText(this, "Error fetching document: ${e.message}", Toast.LENGTH_SHORT).show()
                }
        }
    }


}
