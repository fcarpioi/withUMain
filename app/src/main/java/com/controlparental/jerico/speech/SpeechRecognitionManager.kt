// File: com/controlparental/jerico/speech/SpeechRecognitionManager.kt
package com.controlparental.jerico.speech

import android.content.Context
import android.content.Intent
import android.os.Handler
import android.os.Looper
import android.speech.RecognitionListener
import android.speech.RecognizerIntent
import android.speech.SpeechRecognizer
import android.util.Log
import java.util.Locale

class SpeechRecognitionManager(private val context: Context) {

    private var speechRecognizer: SpeechRecognizer? = null
    private var recognizerIntent: Intent? = null
    var isListening: Boolean = false
        private set

    // Callback para que el servicio coordinador se entere de palabras clave
    var onKeywordDetected: ((String) -> Unit)? = null

    fun initialize() {
        speechRecognizer = SpeechRecognizer.createSpeechRecognizer(context)
        recognizerIntent = Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).apply {
            putExtra(
                RecognizerIntent.EXTRA_LANGUAGE_MODEL,
                RecognizerIntent.LANGUAGE_MODEL_FREE_FORM
            )
            putExtra(RecognizerIntent.EXTRA_LANGUAGE, Locale.getDefault().toLanguageTag())
            putExtra(RecognizerIntent.EXTRA_PARTIAL_RESULTS, true)
        }

        speechRecognizer?.setRecognitionListener(object : RecognitionListener {
            override fun onReadyForSpeech(params: android.os.Bundle?) {
                Log.d("SpeechRecManager", "Listo para escuchar")
                isListening = true
            }
            override fun onBeginningOfSpeech() {
                Log.d("SpeechRecManager", "Inicio de discurso")
            }
            override fun onRmsChanged(rmsdB: Float) {}
            override fun onBufferReceived(buffer: ByteArray?) {}
            override fun onEndOfSpeech() {
                Log.d("SpeechRecManager", "Fin del discurso")
                isListening = false
            }
            override fun onResults(results: android.os.Bundle?) {
                isListening = false
                val matches = results?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)
                matches?.forEach { result ->
                    Log.d("SpeechRecManager", "Palabra detectada: $result")
                    if (containsKeyword(result)) {
                        onKeywordDetected?.invoke(result)
                    }
                }
                restartListening()
            }
            override fun onPartialResults(partialResults: android.os.Bundle?) {}
            override fun onEvent(eventType: Int, params: android.os.Bundle?) {}
            override fun onError(error: Int) {
                Log.e("SpeechRecManager", "Error en reconocimiento de voz: $error")
                isListening = false
                val delay = when (error) {
                    SpeechRecognizer.ERROR_NO_MATCH -> {
                        Log.w("SpeechRecManager", "No match found (ERROR_NO_MATCH): $error")
                        500L
                    }
                    SpeechRecognizer.ERROR_AUDIO -> 1500L
                    SpeechRecognizer.ERROR_NETWORK, SpeechRecognizer.ERROR_NETWORK_TIMEOUT -> 3000L
                    SpeechRecognizer.ERROR_TOO_MANY_REQUESTS -> 5000L
                    SpeechRecognizer.ERROR_SERVER -> 3000L
                    else -> 1000L
                }
                Handler(Looper.getMainLooper()).postDelayed({
                    startListening()
                }, delay)
            }
        })
    }

    private fun containsKeyword(text: String): Boolean {
        val keywords = listOf("ayuda", "emergencia", "socorro", "help")
        return keywords.any { text.contains(it, ignoreCase = true) }
    }

    fun startListening() {
        if (!isListening) {
            speechRecognizer?.startListening(recognizerIntent)
            Log.d("SpeechRecManager", "Escucha de voz activada")
            isListening = true
        }
    }

    fun stopListening() {
        if (isListening) {
            speechRecognizer?.stopListening()
            Log.d("SpeechRecManager", "Escucha de voz detenida")
            isListening = false
        }
    }

    private fun restartListening() {
        Log.d("SpeechRecManager", "Reiniciando escucha de voz")
        stopListening()
        Handler(Looper.getMainLooper()).postDelayed({
            startListening()
        }, 500)
    }

    fun destroy() {
        speechRecognizer?.destroy()
        speechRecognizer = null
    }
}