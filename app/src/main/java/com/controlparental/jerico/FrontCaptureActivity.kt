package com.controlparental.jerico

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.util.Log
import android.util.Size
import androidx.appcompat.app.AppCompatActivity
import androidx.camera.core.CameraSelector
import androidx.camera.core.ImageCapture
import androidx.camera.core.ImageCaptureException
import androidx.camera.core.Preview
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.camera.view.PreviewView
import androidx.core.content.ContextCompat
import com.google.firebase.firestore.FirebaseFirestore
import com.google.firebase.firestore.FieldValue
import com.google.firebase.storage.FirebaseStorage
import com.google.firebase.storage.StorageMetadata
import java.io.File
import java.util.Date

class FrontCaptureActivity : AppCompatActivity() {

    companion object {
        const val EXTRA_USER_ID = "extra_user_id"
        const val EXTRA_DEVICE_ID = "extra_device_id"
    }

    private var imageCapture: ImageCapture? = null
    private var hasFinished = false

    private lateinit var userId: String
    private lateinit var deviceId: String
    private val firestore by lazy { FirebaseFirestore.getInstance() }
    private val storage by lazy { FirebaseStorage.getInstance() }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        val previewView = PreviewView(this).apply {
            implementationMode = PreviewView.ImplementationMode.COMPATIBLE
        }
        setContentView(previewView)

        userId = intent.getStringExtra(EXTRA_USER_ID).orEmpty()
        deviceId = intent.getStringExtra(EXTRA_DEVICE_ID).orEmpty()
        if (userId.isBlank() || deviceId.isBlank()) {
            val prefs = getSharedPreferences("UserPrefs", Context.MODE_PRIVATE)
            val fallbackDeviceId = prefs.getString("idDevice", "").orEmpty()
            if (userId.isBlank() || fallbackDeviceId.isBlank()) {
                Log.e("FrontCaptureActivity", "Missing userId/deviceId, aborting capture")
                finishSafely()
                return
            }
            deviceId = fallbackDeviceId
        }

        if (ContextCompat.checkSelfPermission(this, Manifest.permission.CAMERA) != PackageManager.PERMISSION_GRANTED) {
            updateTakePhotoField(false)
            finishSafely()
            return
        }

        val cameraProviderFuture = ProcessCameraProvider.getInstance(this)
        cameraProviderFuture.addListener({
            try {
                val cameraProvider = cameraProviderFuture.get()
                val preview = Preview.Builder().build().also {
                    it.surfaceProvider = previewView.surfaceProvider
                }

                imageCapture = ImageCapture.Builder()
                    .setTargetResolution(Size(1280, 720))
                    .setCaptureMode(ImageCapture.CAPTURE_MODE_MINIMIZE_LATENCY)
                    .build()

                val cameraSelector = CameraSelector.DEFAULT_FRONT_CAMERA
                cameraProvider.unbindAll()
                cameraProvider.bindToLifecycle(this, cameraSelector, preview, imageCapture)

                Handler(Looper.getMainLooper()).postDelayed({
                    takePhoto()
                }, 900)
            } catch (e: Exception) {
                Log.e("FrontCaptureActivity", "CameraX setup failed", e)
                updateTakePhotoField(false)
                finishSafely()
            }
        }, ContextCompat.getMainExecutor(this))
    }

    private fun takePhoto() {
        val capture = imageCapture ?: run {
            updateTakePhotoField(false)
            finishSafely()
            return
        }

        val outDir = File(getExternalFilesDir(null), "remote_captures").apply { mkdirs() }
        val outFile = File(outDir, "photo_${System.currentTimeMillis()}.jpg")
        val outputOptions = ImageCapture.OutputFileOptions.Builder(outFile).build()

        capture.takePicture(
            outputOptions,
            ContextCompat.getMainExecutor(this),
            object : ImageCapture.OnImageSavedCallback {
                override fun onImageSaved(outputFileResults: ImageCapture.OutputFileResults) {
                    uploadPhoto(outFile)
                }

                override fun onError(exception: ImageCaptureException) {
                    Log.e("FrontCaptureActivity", "takePicture failed: ${exception.message}")
                    updateTakePhotoField(false)
                    finishSafely()
                }
            }
        )
    }

    private fun uploadPhoto(file: File) {
        val storageRef = storage.reference.child("photos/$userId/${file.name}")
        val metadata = StorageMetadata.Builder()
            .setContentType("image/jpeg")
            .build()
        val debugCopy = File(file.parentFile, "last_capture_activity.jpg")
        try {
            file.copyTo(debugCopy, overwrite = true)
            Log.d("FrontCaptureActivity", "Debug local copy saved: ${debugCopy.absolutePath} (${debugCopy.length()} bytes)")
        } catch (e: Exception) {
            Log.e("FrontCaptureActivity", "Failed to save debug local copy: ${e.message}")
        }
        storageRef.putFile(Uri.fromFile(file), metadata)
            .addOnSuccessListener {
                storageRef.downloadUrl
                    .addOnSuccessListener { uri ->
                        savePhotoUrl(
                            url = uri.toString(),
                            fileName = file.name,
                            fileSizeBytes = file.length()
                        )
                        updateTakePhotoField(false)
                        file.delete()
                        finishSafely()
                    }
                    .addOnFailureListener { e ->
                        Log.e("FrontCaptureActivity", "downloadUrl failed: ${e.message}")
                        updateTakePhotoField(false)
                        finishSafely()
                    }
            }
            .addOnFailureListener { e ->
                Log.e("FrontCaptureActivity", "upload failed: ${e.message}")
                updateTakePhotoField(false)
                finishSafely()
            }
    }

    private fun savePhotoUrl(url: String, fileName: String, fileSizeBytes: Long) {
        val photoData = mapOf(
            "url" to url,
            "isView" to false,
            "timestamp" to FieldValue.serverTimestamp(),
            "localTimestamp" to Date(),
            "captureSource" to "activity_camerax_front",
            "fileName" to fileName,
            "fileSizeBytes" to fileSizeBytes
        )
        firestore.collection("users")
            .document(userId)
            .collection("devices")
            .document(deviceId)
            .collection("photos")
            .add(photoData)
            .addOnSuccessListener {
                Log.d("FrontCaptureActivity", "Photo URL saved successfully")
            }
            .addOnFailureListener { e ->
                Log.e("FrontCaptureActivity", "Failed to save photo URL: ${e.message}")
            }
    }

    private fun updateTakePhotoField(value: Boolean) {
        firestore.collection("users")
            .document(userId)
            .collection("devices")
            .document(deviceId)
            .update("takePhoto", value)
            .addOnSuccessListener {
                Log.d("FrontCaptureActivity", "takePhoto field updated to $value")
            }
            .addOnFailureListener { e ->
                Log.e("FrontCaptureActivity", "Failed to update takePhoto field: ${e.message}")
            }
    }

    private fun finishSafely() {
        if (hasFinished) return
        hasFinished = true
        finish()
        overridePendingTransition(0, 0)
    }
}
