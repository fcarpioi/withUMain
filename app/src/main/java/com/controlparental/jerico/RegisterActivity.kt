package com.controlparental.jerico

import android.Manifest
import android.annotation.SuppressLint
import android.app.Dialog
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
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
import androidx.camera.core.ExperimentalGetImage
import androidx.camera.core.ImageAnalysis
import androidx.camera.core.ImageProxy
import androidx.camera.core.Preview
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.camera.view.PreviewView
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import androidx.core.content.edit
import com.google.android.material.imageview.ShapeableImageView
import com.google.firebase.auth.FirebaseAuth
import com.google.firebase.firestore.FirebaseFirestore
import com.google.firebase.firestore.GeoPoint
import com.google.firebase.firestore.SetOptions
import com.google.mlkit.vision.barcode.BarcodeScanner
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
    private lateinit var barcodeScanner: BarcodeScanner
    private var qrDeviceId: String? = null
    private var isProcessing = false
    private var isCameraStarted = false
    private val TAG = "RegisterActivity"
    private val servicePermissionsRequestCode = 102

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_register)
        Log.d("WithUBoot", "Boot diagnostics snapshot: ${BootDiagnostics.snapshot(this)}")

        auth = FirebaseAuth.getInstance()
        firestore = FirebaseFirestore.getInstance()
        sharedPreferences = getSharedPreferences("UserPrefs", Context.MODE_PRIVATE)
        sharedPreferences.edit { remove("password") }

        val savedDeviceId = sharedPreferences.getString("idDevice", null)

        if (auth.currentUser != null && savedDeviceId != null) {
            qrDeviceId = savedDeviceId
            DeviceIdHolder.deviceId = qrDeviceId

            // Cerrar solo si el servicio realmente arrancó.
            if (startBackgroundService()) {
                handleSuccessfulServiceStart()
                return
            }
            Toast.makeText(
                this,
                "Faltan permisos para iniciar el servicio en segundo plano",
                Toast.LENGTH_LONG
            ).show()
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

        previewView = findViewById(R.id.previewView)

        // Configurar animaciones
        val laserLine: View = findViewById(R.id.laserLine)
        val scanningDot: View = findViewById(R.id.scanningDot)

        val laserAnimation = AnimationUtils.loadAnimation(this, R.anim.professional_laser_scan)
        val pulseAnimation = AnimationUtils.loadAnimation(this, R.anim.scanning_pulse)

        laserLine.startAnimation(laserAnimation)
        scanningDot.startAnimation(pulseAnimation)

        cameraExecutor = Executors.newSingleThreadExecutor()
        barcodeScanner = BarcodeScanning.getClient(
            BarcodeScannerOptions.Builder()
                .setBarcodeFormats(Barcode.FORMAT_QR_CODE)
                .build()
        )

        if (allPermissionsGranted()) {
            startCamera()
        } else {
            ComplianceDisclosures.showQrCameraDisclosureIfNeeded(this) {
                ActivityCompat.requestPermissions(this, arrayOf(Manifest.permission.CAMERA), 101)
            }
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

            val imageAnalysis = ImageAnalysis.Builder()
                .setBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST)
                .build().also { analysis ->
                analysis.setAnalyzer(cameraExecutor) { imageProxy ->
                    processImageProxy(imageProxy)
                }
            }

            val cameraSelector = CameraSelector.DEFAULT_BACK_CAMERA

            try {
                cameraProvider.unbindAll()
                cameraProvider.bindToLifecycle(this, cameraSelector, preview, imageAnalysis)
                isCameraStarted = true
            } catch (e: Exception) {
                Log.e(TAG, "Error al iniciar la cámara: ${e.message}")
                isCameraStarted = false
            }
        }, ContextCompat.getMainExecutor(this))
    }

    @androidx.annotation.OptIn(ExperimentalGetImage::class)
    private fun processImageProxy(imageProxy: ImageProxy) {
        val mediaImage = imageProxy.image
        if (mediaImage == null) {
            imageProxy.close()
            return
        }

        val inputImage = InputImage.fromMediaImage(
            mediaImage,
            imageProxy.imageInfo.rotationDegrees
        )

        barcodeScanner.process(inputImage)
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
            sharedPreferences.edit { putString("idDevice", qrDeviceId) }

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
                    sharedPreferences.edit { putString("idDevice", scannedDeviceId) }
                    onSuccess()
                } else {
                    onFailure("Dispositivo no permitido para esta cuenta")
                }
            } else {
                // No pre-crear documento parcial: registerDeviceInFirestore() debe crearlo completo.
                sharedPreferences.edit { putString("idDevice", scannedDeviceId) }
                onSuccess()
            }
        }.addOnFailureListener { e ->
            onFailure("Error verificando el dispositivo: ${e.message}")
        }
    }

    @SuppressLint("HardwareIds")
    private fun registerDeviceInFirestore() {
        val user = auth.currentUser
        val safeQrDeviceId = qrDeviceId
        if (user != null && safeQrDeviceId != null) {
            val userId = user.uid
            val localDeviceId = Settings.Secure.getString(contentResolver, Settings.Secure.ANDROID_ID)
            val deviceRef = firestore.collection("users")
                .document(userId)
                .collection("devices")
                .document(safeQrDeviceId)

            deviceRef.get().addOnSuccessListener { document ->
                if (document.exists()) {
                    val documentDeviceId = document.getString("deviceId")
                    val storedLocalDeviceId = document.getString("localDeviceId")

                    // ✅ Si el ID del documento coincide con el campo deviceId, continúa sin más validaciones
                    if (documentDeviceId == qrDeviceId) {
                        Log.d("QRCodeDebug", "✅ ID del documento coincide con deviceId en Firestore")
                        updateDeviceData(deviceRef, localDeviceId, document)
                    } else if (storedLocalDeviceId != null && storedLocalDeviceId != localDeviceId) {
                        runOnUiThread {
                            Toast.makeText(this, "El código QR no corresponde a este dispositivo", Toast.LENGTH_SHORT).show()
                        }
                        isProcessing = false
                    } else {
                        updateDeviceData(deviceRef, localDeviceId, document)
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

    private fun updateDeviceData(
        deviceRef: com.google.firebase.firestore.DocumentReference,
        localDeviceId: String,
        existingDocument: com.google.firebase.firestore.DocumentSnapshot
    ) {
        val calendar = Calendar.getInstance()
        val today = calendar.time
        calendar.add(Calendar.HOUR_OF_DAY, 48)
        val twoDaysLater = calendar.time
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

        if (!existingDocument.contains("deviceId")) {
            deviceData["deviceId"] = qrDeviceId
        }
        if (!existingDocument.contains("from")) {
            deviceData["from"] = today
        }
        if (!existingDocument.contains("to")) {
            deviceData["to"] = twoDaysLater
        }
        if (!existingDocument.contains("trackApps")) {
            deviceData["trackApps"] = false
        }
        if (!existingDocument.contains("takePicture")) {
            deviceData["takePicture"] = false
        }
        if (!existingDocument.contains("requestUsagePermission")) {
            deviceData["requestUsagePermission"] = false
        }

        deviceRef.set(deviceData, SetOptions.merge()).addOnSuccessListener {
            val serviceStarted = startBackgroundService()
            if (serviceStarted) {
                Toast.makeText(this, "Dispositivo vinculado correctamente", Toast.LENGTH_SHORT).show()
                Handler(Looper.getMainLooper()).postDelayed({
                    finish()
                }, 1500)
            } else {
                Toast.makeText(
                    this,
                    "Faltan permisos para iniciar el servicio en segundo plano",
                    Toast.LENGTH_LONG
                ).show()
                isProcessing = false
            }
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
        calendar.add(Calendar.HOUR_OF_DAY, 48)
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
            val serviceStarted = startBackgroundService()
            if (serviceStarted) {
                Toast.makeText(this, "Dispositivo vinculado correctamente", Toast.LENGTH_SHORT).show()
                Handler(Looper.getMainLooper()).postDelayed({
                    finish()
                }, 1500)
            } else {
                Toast.makeText(
                    this,
                    "Faltan permisos para iniciar el servicio en segundo plano",
                    Toast.LENGTH_LONG
                ).show()
                isProcessing = false
            }
        }.addOnFailureListener { e ->
            runOnUiThread {
                Toast.makeText(this, "Error al crear el dispositivo: ${e.message}", Toast.LENGTH_SHORT).show()
            }
            isProcessing = false
        }
    }

    private fun logout() {
        auth.signOut()
        sharedPreferences.edit { clear() }
        startActivity(Intent(this, RegisterActivity::class.java))
        finish()
    }

    override fun onResume() {
        super.onResume()
        enableFullScreenMode()
        if (allPermissionsGranted() && !isCameraStarted) {
            startCamera()
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        barcodeScanner.close()
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
        if (requestCode == 101) {
            if (allPermissionsGranted()) {
                startCamera()
            } else {
                Toast.makeText(this, "Permiso de cámara denegado", Toast.LENGTH_LONG).show()
            }
            return
        }

        if (requestCode == servicePermissionsRequestCode) {
            val allGranted = grantResults.isNotEmpty() && grantResults.all { it == PackageManager.PERMISSION_GRANTED }
            if (allGranted) {
                ensureBackgroundLocationAndStartService()
            } else {
                Toast.makeText(this, "Faltan permisos. Abre Ajustes para habilitarlos.", Toast.LENGTH_LONG).show()
                openAppSettings()
            }
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
        message.text = getString(R.string.password_dialog_message_for_email, email)
        acceptButton.text = getString(R.string.button_accept)
        cancelButton.text = getString(R.string.button_cancel)
        acceptButton.setTextColor(ContextCompat.getColor(this, R.color.modernBackgroundSecondary))
        cancelButton.setTextColor(ContextCompat.getColor(this, R.color.modernTextSecondary))

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

    private fun startBackgroundService(): Boolean {
        try {
            val hasLocationPermission = ContextCompat.checkSelfPermission(
                this,
                Manifest.permission.ACCESS_FINE_LOCATION
            ) == PackageManager.PERMISSION_GRANTED
            val hasAudioPermission = ContextCompat.checkSelfPermission(
                this,
                Manifest.permission.RECORD_AUDIO
            ) == PackageManager.PERMISSION_GRANTED

            if (!hasLocationPermission || !hasAudioPermission) {
                Log.w(
                    "RegisterActivity",
                    "BackgroundService no iniciado: faltan permisos de ubicación o audio"
                )
                requestServicePermissionsIfNeeded()
                return false
            }

            val serviceIntent = Intent(this, BackgroundService::class.java)
            startForegroundService(serviceIntent)
            Log.d("RegisterActivity", "✅ BackgroundService iniciado correctamente")
            return true
        } catch (e: Exception) {
            Log.e("RegisterActivity", "❌ Error iniciando BackgroundService: ${e.message}")
            return false
        }
    }

    private fun requestServicePermissionsIfNeeded() {
        val permissionsToRequest = mutableListOf<String>()

        if (ContextCompat.checkSelfPermission(this, Manifest.permission.ACCESS_FINE_LOCATION) != PackageManager.PERMISSION_GRANTED) {
            permissionsToRequest += Manifest.permission.ACCESS_FINE_LOCATION
        }
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) {
            permissionsToRequest += Manifest.permission.RECORD_AUDIO
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
            ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) {
            permissionsToRequest += Manifest.permission.POST_NOTIFICATIONS
        }

        if (permissionsToRequest.isNotEmpty()) {
            ComplianceDisclosures.showCoreMonitoringDisclosureIfNeeded(this) {
                ActivityCompat.requestPermissions(
                    this,
                    permissionsToRequest.toTypedArray(),
                    servicePermissionsRequestCode
                )
            }
            return
        }

        ensureBackgroundLocationAndStartService()
    }

    private fun ensureBackgroundLocationAndStartService() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q &&
            ContextCompat.checkSelfPermission(this, Manifest.permission.ACCESS_BACKGROUND_LOCATION) != PackageManager.PERMISSION_GRANTED
        ) {
            showBackgroundLocationHelpDialog()
            return
        }

        if (startBackgroundService()) {
            Toast.makeText(this, "Servicio en segundo plano iniciado", Toast.LENGTH_SHORT).show()
            handleSuccessfulServiceStart()
        }
    }

    private fun openAppSettings() {
        val intent = Intent(
            Settings.ACTION_APPLICATION_DETAILS_SETTINGS,
            Uri.fromParts("package", packageName, null)
        )
        startActivity(intent)
    }

    private fun openBackgroundLocationPermissionSettings() {
        val directPermissionIntent = Intent("android.settings.APP_PERMISSION_SETTINGS").apply {
            putExtra("android.provider.extra.APP_PACKAGE", packageName)
            putExtra("android.provider.extra.PERMISSION_NAME", Manifest.permission.ACCESS_BACKGROUND_LOCATION)
        }
        if (directPermissionIntent.resolveActivity(packageManager) != null) {
            startActivity(directPermissionIntent)
            return
        }
        openAppSettings()
    }

    private fun showBackgroundLocationHelpDialog() {
        val dialog = Dialog(this)
        dialog.setContentView(R.layout.dialog_setup_guidance)
        dialog.setCancelable(false)

        dialog.window?.setLayout(
            (resources.displayMetrics.widthPixels * 0.88).toInt(),
            android.view.ViewGroup.LayoutParams.WRAP_CONTENT
        )
        dialog.window?.setBackgroundDrawableResource(android.R.color.transparent)

        dialog.findViewById<Button>(R.id.guidanceConfirmButton).setOnClickListener {
            dialog.dismiss()
            openBackgroundLocationPermissionSettings()
        }
        dialog.findViewById<Button>(R.id.guidanceCancelButton).setOnClickListener {
            dialog.dismiss()
        }

        dialog.show()
    }

    private fun handleSuccessfulServiceStart() {
        Handler(Looper.getMainLooper()).postDelayed({
            if (wasServiceHeartbeatSeenRecently()) {
                moveTaskToBack(true)
                finish()
            } else {
                showBackgroundExecutionHelpDialog()
            }
        }, 2000L)
    }

    private fun wasServiceHeartbeatSeenRecently(): Boolean {
        val prefs = getSharedPreferences("ServiceStatePrefs", Context.MODE_PRIVATE)
        val lastHeartbeat = prefs.getLong("last_service_heartbeat", 0L)
        val isRunning = prefs.getBoolean("service_running", false)
        val elapsed = System.currentTimeMillis() - lastHeartbeat
        return isRunning && elapsed in 0..15000
    }

    private fun openBackgroundExecutionSettings() {
        val candidates = mutableListOf<Intent>()

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            candidates += Intent("android.settings.APP_BATTERY_SETTINGS").apply {
                putExtra("app_package", packageName)
                putExtra("android.provider.extra.APP_PACKAGE", packageName)
                data = Uri.fromParts("package", packageName, null)
            }
            candidates += Intent("android.settings.APP_BATTERY_USAGE").apply {
                data = Uri.fromParts("package", packageName, null)
            }
        }

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            candidates += Intent(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS)
            candidates += Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS).apply {
                data = Uri.parse("package:$packageName")
            }
        }

        for (intent in candidates) {
            if (intent.resolveActivity(packageManager) != null) {
                startActivity(intent)
                return
            }
        }

        openAppSettings()
    }

    private fun showBackgroundExecutionHelpDialog() {
        androidx.appcompat.app.AlertDialog.Builder(this)
            .setTitle(R.string.background_execution_title)
            .setMessage(R.string.background_execution_message)
            .setCancelable(false)
            .setPositiveButton(R.string.open_settings) { _, _ ->
                openBackgroundExecutionSettings()
            }
            .setNegativeButton(R.string.not_now, null)
            .show()
    }
}
