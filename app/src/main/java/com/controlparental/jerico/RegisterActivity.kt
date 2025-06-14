package com.controlparental.jerico

import android.Manifest
import android.annotation.SuppressLint
import android.app.Dialog
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.provider.Settings
import android.util.Log
import android.view.View
import android.view.animation.AnimationUtils
import android.view.WindowInsets
import android.view.WindowInsetsController
import android.widget.ImageView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.camera.core.CameraSelector
import androidx.camera.core.ImageAnalysis
import androidx.camera.core.ImageProxy
import androidx.camera.core.Preview
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.camera.view.PreviewView
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import com.google.android.material.imageview.ShapeableImageView
import com.google.firebase.auth.FirebaseAuth
import com.google.firebase.firestore.FirebaseFirestore
import com.google.firebase.firestore.GeoPoint
import com.google.firebase.firestore.SetOptions
import com.google.mlkit.vision.barcode.common.Barcode
import com.google.mlkit.vision.barcode.BarcodeScannerOptions
import com.google.mlkit.vision.barcode.BarcodeScanning
import com.google.mlkit.vision.common.InputImage
import org.json.JSONObject
import java.util.Calendar
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import android.content.Context
import android.content.SharedPreferences
import android.widget.Button
import android.widget.EditText
import android.widget.TextView

class RegisterActivity : AppCompatActivity() {

    private lateinit var auth: FirebaseAuth
    private lateinit var firestore: FirebaseFirestore
    private lateinit var previewView: PreviewView
    private lateinit var cameraExecutor: ExecutorService
    private lateinit var sharedPreferences: SharedPreferences
    private var qrDeviceId: String? = null
    private var isProcessing = false
    private val TAG = "RegisterActivity"

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_register)

        sharedPreferences = getSharedPreferences("UserPrefs", Context.MODE_PRIVATE)

        val savedEmail = sharedPreferences.getString("email", null)
        val savedPassword = sharedPreferences.getString("password", null)
        val savedDeviceId = sharedPreferences.getString("idDevice", null)

        if (savedEmail != null && savedPassword != null && savedDeviceId != null) {
            qrDeviceId = savedDeviceId
            DeviceIdHolder.deviceId = qrDeviceId

            // Iniciar servicio y cerrar la actividad inmediatamente
            startBackgroundService()

            // Cerrar la actividad inmediatamente sin mostrar mensaje
            finish()
            return
        }

        val logoImageView: ImageView = findViewById(R.id.roundedImageView)
        val fadeInAnimation = android.view.animation.AnimationUtils.loadAnimation(this, R.anim.fade_in)
        logoImageView.startAnimation(fadeInAnimation)
        logoImageView.isClickable = true
        logoImageView.isFocusable = true
        logoImageView.setOnClickListener {
            Log.d("RegisterActivity", "Logo presionado para activar alarma")
            val intent = Intent(this, BackgroundService::class.java).apply {
                action = BackgroundService.ACTION_TRIGGER_ALARM
            }
            startService(intent)
        }

        enableFullScreenMode()
        supportActionBar?.hide()

        auth = FirebaseAuth.getInstance()
        firestore = FirebaseFirestore.getInstance()

        previewView = findViewById(R.id.previewView)

        // Configurar animaciones
        val laserLine: View = findViewById(R.id.laserLine)
        val scanningDot: View = findViewById(R.id.scanningDot)

        val laserAnimation = AnimationUtils.loadAnimation(this, R.anim.professional_laser_scan)
        val pulseAnimation = AnimationUtils.loadAnimation(this, R.anim.scanning_pulse)

        laserLine.startAnimation(laserAnimation)
        scanningDot.startAnimation(pulseAnimation)

        cameraExecutor = Executors.newSingleThreadExecutor()

        // Configurar botón de debug
        val debugButton = findViewById<Button>(R.id.debugButton)
        debugButton.setOnClickListener {
            showDebugDialog()
        }

        if (allPermissionsGranted()) {
            startCamera()
        } else {
            ActivityCompat.requestPermissions(this, arrayOf(Manifest.permission.CAMERA), 101)
        }
    }

    private fun allPermissionsGranted() =
        ContextCompat.checkSelfPermission(this, Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED

    private fun startCamera() {
        val cameraProviderFuture = ProcessCameraProvider.getInstance(this)
        cameraProviderFuture.addListener({
            val cameraProvider = cameraProviderFuture.get()

            val preview = Preview.Builder().build().also {
                it.setSurfaceProvider(previewView.surfaceProvider)
            }

            val imageAnalysis = ImageAnalysis.Builder().build().also { analysis ->
                analysis.setAnalyzer(cameraExecutor) { imageProxy ->
                    processImageProxy(imageProxy)
                }
            }

            val cameraSelector = CameraSelector.DEFAULT_BACK_CAMERA

            try {
                cameraProvider.unbindAll()
                cameraProvider.bindToLifecycle(this, cameraSelector, preview, imageAnalysis)
            } catch (e: Exception) {
                Log.e(TAG, "Error al iniciar la cámara: ${e.message}")
            }
        }, ContextCompat.getMainExecutor(this))
    }

    private fun processImageProxy(imageProxy: ImageProxy) {
        val nv21 = imageProxyToNV21(imageProxy)
        val inputImage = InputImage.fromByteArray(
            nv21,
            imageProxy.width,
            imageProxy.height,
            imageProxy.imageInfo.rotationDegrees,
            InputImage.IMAGE_FORMAT_NV21
        )
        val options = BarcodeScannerOptions.Builder()
            .setBarcodeFormats(Barcode.FORMAT_QR_CODE)
            .build()
        val scanner = BarcodeScanning.getClient(options)
        scanner.process(inputImage)
            .addOnSuccessListener { barcodes ->
                for (barcode in barcodes) {
                    val rawValue = barcode.rawValue
                    if (rawValue != null && !isProcessing) {
                        isProcessing = true
                        processQRCode(rawValue)
                        break
                    }
                }
            }
            .addOnFailureListener { e ->
                Log.e(TAG, "Error al procesar la imagen: ${e.message}")
            }
            .addOnCompleteListener {
                imageProxy.close()
            }
    }

    private fun imageProxyToNV21(imageProxy: ImageProxy): ByteArray {
        val yBuffer = imageProxy.planes[0].buffer
        val uBuffer = imageProxy.planes[1].buffer
        val vBuffer = imageProxy.planes[2].buffer

        val ySize = yBuffer.remaining()
        val uSize = uBuffer.remaining()
        val vSize = vBuffer.remaining()

        val nv21 = ByteArray(ySize + uSize + vSize)
        yBuffer.get(nv21, 0, ySize)

        val uBytes = ByteArray(uSize)
        uBuffer.get(uBytes)
        val vBytes = ByteArray(vSize)
        vBuffer.get(vBytes)

        var offset = ySize
        val length = minOf(uBytes.size, vBytes.size)
        for (i in 0 until length) {
            nv21[offset++] = vBytes[i]
            nv21[offset++] = uBytes[i]
        }
        return nv21
    }

    @SuppressLint("HardwareIds")
    private fun processQRCode(data: String) {
        Log.d("QRCodeDebug", "🧩 QR detectado: $data")
        Log.d("QRCodeDebug", "🔍 Longitud del QR: ${data.length}")
        Log.d("QRCodeDebug", "🔍 Primeros 100 caracteres: ${data.take(100)}")

        try {
            // Limpiar el string de posibles caracteres invisibles
            val cleanedData = data.trim().replace("\u0000", "").replace("\uFEFF", "")
            Log.d("QRCodeDebug", "🧹 Datos limpiados: $cleanedData")

            val jsonObject = JSONObject(cleanedData)

            // Verificar que existen las claves necesarias
            if (!jsonObject.has("email")) {
                throw Exception("El QR no contiene el campo 'email'")
            }
            if (!jsonObject.has("idDevice")) {
                throw Exception("El QR no contiene el campo 'idDevice'")
            }

            val rawEmail = jsonObject.getString("email")
            qrDeviceId = jsonObject.getString("idDevice")

            // Limpiar y validar el email
            val email = cleanAndValidateEmail(rawEmail)

            Log.d("QRCodeDebug", "📧 Email extraído: '$rawEmail'")
            Log.d("QRCodeDebug", "📧 Email limpio: '$email'")
            Log.d("QRCodeDebug", "📱 idDevice extraído: '$qrDeviceId'")

            DeviceIdHolder.deviceId = qrDeviceId
            sharedPreferences.edit().putString("idDevice", qrDeviceId).apply()

            promptForPassword(email)
        } catch (e: Exception) {
            Log.e(TAG, "❌ Error al procesar el QR: ${e.message}", e)
            Log.e(TAG, "❌ Stack trace completo:", e)
            runOnUiThread {
                Toast.makeText(this, "Error al procesar el código QR: ${e.message}", Toast.LENGTH_LONG).show()
            }
            isProcessing = false
        }
    }

    private fun cleanAndValidateEmail(rawEmail: String): String {
        // Limpiar caracteres invisibles y espacios
        val cleanedEmail = rawEmail.trim()
            .replace("\u0000", "") // Null character
            .replace("\uFEFF", "") // BOM
            .replace("\u200B", "") // Zero-width space
            .replace("\u00A0", " ") // Non-breaking space
            .replace(Regex("\\s+"), "") // Multiple spaces

        Log.d("QRCodeDebug", "🔍 Email original: '$rawEmail'")
        Log.d("QRCodeDebug", "🔍 Email limpio: '$cleanedEmail'")
        Log.d("QRCodeDebug", "🔍 Longitud original: ${rawEmail.length}, limpio: ${cleanedEmail.length}")

        // Validar formato básico de email
        if (!isValidEmail(cleanedEmail)) {
            throw Exception("El email '$cleanedEmail' no tiene un formato válido")
        }

        return cleanedEmail
    }

    private fun isValidEmail(email: String): Boolean {
        val emailPattern = Regex("^[A-Za-z0-9+_.-]+@[A-Za-z0-9.-]+\\.[A-Za-z]{2,}$")
        return emailPattern.matches(email)
    }

    private fun loginUser(email: String, password: String) {
        Log.d("QRCodeDebug", "🔐 Intentando login con email: '$email'")
        Log.d("QRCodeDebug", "🔐 Password length: ${password.length}")

        auth.signInWithEmailAndPassword(email, password)
            .addOnCompleteListener(this) { task ->
                if (task.isSuccessful) {
                    Log.d("QRCodeDebug", "✅ Login exitoso")
                    val userId = auth.currentUser?.uid ?: return@addOnCompleteListener
                    Log.d("QRCodeDebug", "👤 User ID: $userId")

                    verifyDeviceAccess(userId, qrDeviceId ?: "",
                        onSuccess = {
                            Log.d("QRCodeDebug", "✅ Verificación de dispositivo exitosa")
                            sharedPreferences.edit().apply {
                                putString("email", email)
                                putString("password", password)
                                apply()
                            }
                            registerDeviceInFirestore()
                        },
                        onFailure = { errorMessage ->
                            Log.e("QRCodeDebug", "❌ Error en verificación de dispositivo: $errorMessage")
                            runOnUiThread {
                                Toast.makeText(this, errorMessage, Toast.LENGTH_LONG).show()
                            }
                            isProcessing = false
                        }
                    )
                } else {
                    val exception = task.exception
                    Log.e("QRCodeDebug", "❌ Error de autenticación: ${exception?.message}")
                    Log.e("QRCodeDebug", "❌ Tipo de excepción: ${exception?.javaClass?.simpleName}")

                    runOnUiThread {
                        val errorMessage = when {
                            exception?.message?.contains("badly formatted") == true ->
                                "El email '$email' no tiene un formato válido"
                            exception?.message?.contains("no user record") == true ->
                                "No existe una cuenta con el email '$email'"
                            exception?.message?.contains("wrong password") == true ->
                                "La contraseña es incorrecta"
                            exception?.message?.contains("too many requests") == true ->
                                "Demasiados intentos. Intenta más tarde"
                            else -> "Error de autenticación: ${exception?.message}"
                        }
                        Toast.makeText(this, errorMessage, Toast.LENGTH_LONG).show()
                    }
                    isProcessing = false
                }
            }
    }

    private fun verifyDeviceAccess(userId: String, scannedDeviceId: String, onSuccess: () -> Unit, onFailure: (String) -> Unit) {
        val storedDeviceId = sharedPreferences.getString("idDevice", null)
        val deviceDocRef = firestore.collection("users")
            .document(userId)
            .collection("devices")
            .document(scannedDeviceId)

        deviceDocRef.get().addOnSuccessListener { document ->
            if (document.exists()) {
                if (storedDeviceId == null || storedDeviceId == scannedDeviceId) {
                    sharedPreferences.edit().putString("idDevice", scannedDeviceId).apply()
                    onSuccess()
                } else {
                    onFailure("Dispositivo no permitido para esta cuenta")
                }
            } else {
                deviceDocRef.set(mapOf("createdAt" to System.currentTimeMillis()))
                    .addOnSuccessListener {
                        sharedPreferences.edit().putString("idDevice", scannedDeviceId).apply()
                        onSuccess()
                    }
                    .addOnFailureListener { e ->
                        onFailure("Error registrando el dispositivo: ${e.message}")
                    }
            }
        }.addOnFailureListener { e ->
            onFailure("Error verificando el dispositivo: ${e.message}")
        }
    }

    @SuppressLint("HardwareIds")
    private fun registerDeviceInFirestore() {
        val user = auth.currentUser
        if (user != null && qrDeviceId != null) {
            val userId = user.uid
            val localDeviceId = Settings.Secure.getString(contentResolver, Settings.Secure.ANDROID_ID)
            val deviceRef = firestore.collection("users")
                .document(userId)
                .collection("devices")
                .document(qrDeviceId!!)

            deviceRef.get().addOnSuccessListener { document ->
                if (document.exists()) {
                    val documentDeviceId = document.getString("deviceId")
                    val storedLocalDeviceId = document.getString("localDeviceId")

                    // ✅ Si el ID del documento coincide con el campo deviceId, continúa sin más validaciones
                    if (documentDeviceId == qrDeviceId) {
                        Log.d("QRCodeDebug", "✅ ID del documento coincide con deviceId en Firestore")
                        updateDeviceData(deviceRef, localDeviceId)
                    } else if (storedLocalDeviceId != null && storedLocalDeviceId != localDeviceId) {
                        runOnUiThread {
                            Toast.makeText(this, "El código QR no corresponde a este dispositivo", Toast.LENGTH_SHORT).show()
                        }
                        isProcessing = false
                    } else {
                        updateDeviceData(deviceRef, localDeviceId)
                    }
                } else {
                    createDeviceData(deviceRef, localDeviceId)
                }
            }.addOnFailureListener { e ->
                runOnUiThread {
                    Toast.makeText(this, "Error al obtener el dispositivo: ${e.message}", Toast.LENGTH_SHORT).show()
                }
                isProcessing = false
            }
        } else {
            isProcessing = false
        }
    }

    private fun updateDeviceData(deviceRef: com.google.firebase.firestore.DocumentReference, localDeviceId: String) {
        val calendar = Calendar.getInstance()
        val today = calendar.time
        val deviceData = hashMapOf(
            "deviceName" to Build.MODEL,
            "lastCoordinate" to GeoPoint(8.983333, -79.516667),
            "locationUpdateInterval" to 15000,
            "recordingEnabled" to false,
            "trackingEnabled" to false,
            "battery" to 0,
            "isDefault" to true,
            "sound" to false,
            "takePhoto" to false,
            "localDeviceId" to localDeviceId,
            "linkedAt" to today,
        )

        deviceRef.set(deviceData, SetOptions.merge()).addOnSuccessListener {
            // Iniciar el servicio de fondo
            startBackgroundService()

            // Mostrar mensaje de confirmación
            Toast.makeText(this, "Dispositivo vinculado correctamente", Toast.LENGTH_SHORT).show()

            // Cerrar la actividad completamente después de un breve delay
            Handler(Looper.getMainLooper()).postDelayed({
                finish()
            }, 1500) // Esperar 1.5 segundos para que se vea el mensaje
        }.addOnFailureListener { e ->
            runOnUiThread {
                Toast.makeText(this, "Error al actualizar el dispositivo: ${e.message}", Toast.LENGTH_SHORT).show()
            }
            isProcessing = false
        }
    }

    private fun createDeviceData(deviceRef: com.google.firebase.firestore.DocumentReference, localDeviceId: String) {
        val calendar = Calendar.getInstance()
        val today = calendar.time
        calendar.add(Calendar.HOUR, 48)
        val twoDaysLater = calendar.time

        val deviceData = hashMapOf(
            "deviceId" to qrDeviceId,
            "deviceName" to Build.MODEL,
            "lastCoordinate" to GeoPoint(8.983333, -79.516667),
            "locationUpdateInterval" to 15000,
            "recordingEnabled" to false,
            "trackingEnabled" to false,
            "battery" to 0,
            "isDefault" to true,
            "sound" to false,
            "takePhoto" to false,
            "to" to twoDaysLater,
            "from" to today,
            "localDeviceId" to localDeviceId,
            "trackApps" to false,
            "takePicture" to false,
            "requestUsagePermission" to false,
            "linkedAt" to today,
        )

        deviceRef.set(deviceData).addOnSuccessListener {
            // Iniciar el servicio de fondo
            startBackgroundService()

            // Mostrar mensaje de confirmación
            Toast.makeText(this, "Dispositivo vinculado correctamente", Toast.LENGTH_SHORT).show()

            // Cerrar la actividad completamente después de un breve delay
            Handler(Looper.getMainLooper()).postDelayed({
                finish()
            }, 1500) // Esperar 1.5 segundos para que se vea el mensaje
        }.addOnFailureListener { e ->
            runOnUiThread {
                Toast.makeText(this, "Error al crear el dispositivo: ${e.message}", Toast.LENGTH_SHORT).show()
            }
            isProcessing = false
        }
    }

    private fun logout() {
        auth.signOut()
        sharedPreferences.edit().clear().apply()
        startActivity(Intent(this, RegisterActivity::class.java))
        finish()
    }

    override fun onResume() {
        super.onResume()
        enableFullScreenMode()
    }

    override fun onDestroy() {
        super.onDestroy()
        cameraExecutor.shutdown()
    }

    private fun enableFullScreenMode() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            window.insetsController?.let { controller ->
                controller.hide(WindowInsets.Type.systemBars())
                controller.systemBarsBehavior = WindowInsetsController.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
            }
        } else {
            @Suppress("DEPRECATION")
            window.decorView.systemUiVisibility = (
                    View.SYSTEM_UI_FLAG_FULLSCREEN or
                            View.SYSTEM_UI_FLAG_HIDE_NAVIGATION or
                            View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY
                    )
        }
    }

    override fun onRequestPermissionsResult(
        requestCode: Int, permissions: Array<out String>, grantResults: IntArray
    ) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (requestCode == 101 && allPermissionsGranted()) {
            startCamera()
        }
    }

    private fun promptForPassword(email: String) {
        val dialog = Dialog(this)
        dialog.setContentView(R.layout.dialog_password_input)
        dialog.setCancelable(false)

        // Configurar el tamaño del diálogo
        val window = dialog.window
        window?.setLayout(
            (resources.displayMetrics.widthPixels * 0.9).toInt(), // 90% del ancho de pantalla
            android.view.ViewGroup.LayoutParams.WRAP_CONTENT
        )
        window?.setBackgroundDrawableResource(android.R.color.transparent)

        val title = dialog.findViewById<TextView>(R.id.dialogTitle)
        val message = dialog.findViewById<TextView>(R.id.dialogMessage)
        val input = dialog.findViewById<EditText>(R.id.passwordInput)
        val acceptButton = dialog.findViewById<Button>(R.id.acceptButton)
        val cancelButton = dialog.findViewById<Button>(R.id.cancelButton)

        title.text = getString(R.string.password_dialog_title)
        message.text = "Ingresa la contraseña para:\n$email"

        acceptButton.setOnClickListener {
            val password = input.text.toString()
            if (password.isNotBlank()) {
                loginUser(email, password)
                dialog.dismiss()
            } else {
                Toast.makeText(this, getString(R.string.password_empty_error), Toast.LENGTH_SHORT).show()
            }
        }

        cancelButton.setOnClickListener {
            Toast.makeText(this, getString(R.string.password_cancelled), Toast.LENGTH_SHORT).show()
            isProcessing = false
            dialog.dismiss()
        }

        dialog.show()
    }

    private fun showDebugDialog() {
        val dialog = Dialog(this)
        dialog.setContentView(R.layout.dialog_password_input)
        dialog.setCancelable(true)

        // Configurar el tamaño del diálogo
        val window = dialog.window
        window?.setLayout(
            (resources.displayMetrics.widthPixels * 0.9).toInt(), // 90% del ancho de pantalla
            android.view.ViewGroup.LayoutParams.WRAP_CONTENT
        )
        window?.setBackgroundDrawableResource(android.R.color.transparent)

        val title = dialog.findViewById<TextView>(R.id.dialogTitle)
        val message = dialog.findViewById<TextView>(R.id.dialogMessage)
        val input = dialog.findViewById<EditText>(R.id.passwordInput)
        val acceptButton = dialog.findViewById<Button>(R.id.acceptButton)
        val cancelButton = dialog.findViewById<Button>(R.id.cancelButton)

        title.text = "Debug QR"
        message.text = "Ingresa el contenido del QR para probar:"
        input.hint = "Ejemplo: {\"email\":\"test@example.com\",\"idDevice\":\"123\"}"
        input.inputType = android.text.InputType.TYPE_CLASS_TEXT or android.text.InputType.TYPE_TEXT_FLAG_MULTI_LINE

        acceptButton.text = "Probar"
        acceptButton.setOnClickListener {
            val qrContent = input.text.toString()
            if (qrContent.isNotBlank()) {
                Log.d("QRCodeDebug", "🧪 Probando QR manualmente: $qrContent")
                isProcessing = false // Reset processing flag
                processQRCode(qrContent)
                dialog.dismiss()
            } else {
                Toast.makeText(this, "Ingresa el contenido del QR", Toast.LENGTH_SHORT).show()
            }
        }

        cancelButton.setOnClickListener {
            dialog.dismiss()
        }

        dialog.show()
    }

    private fun startBackgroundService() {
        try {
            val serviceIntent = Intent(this, BackgroundService::class.java)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                startForegroundService(serviceIntent)
            } else {
                startService(serviceIntent)
            }
            Log.d("RegisterActivity", "✅ BackgroundService iniciado correctamente")
        } catch (e: Exception) {
            Log.e("RegisterActivity", "❌ Error iniciando BackgroundService: ${e.message}")
        }
    }
}