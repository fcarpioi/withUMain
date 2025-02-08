package com.controlparental.jerico

import android.content.Intent
import android.os.Bundle
import android.view.KeyEvent
import android.view.View
import android.view.WindowManager
import android.graphics.Color
import android.graphics.drawable.ColorDrawable
import androidx.appcompat.app.AppCompatActivity

class FakeShutdownActivity : AppCompatActivity() {

        override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        window.addFlags(WindowManager.LayoutParams.FLAG_FULLSCREEN)
        setContentView(R.layout.activity_fake_shutdown)

        // Apagar el brillo de la pantalla
        val layoutParams = window.attributes
        layoutParams.screenBrightness = 0f // Brillo en 0
        window.attributes = layoutParams
    }

    // Método para bloquear la interacción del usuario
    private fun blockUserInteraction() {
        // Desactivar eventos táctiles en la ventana actual
        window.setFlags(
            WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE,
            WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE
        )
    }

    // Detectar cuando el botón de volumen se mantiene presionado
    override fun onKeyLongPress(keyCode: Int, event: KeyEvent?): Boolean {
        if (keyCode == KeyEvent.KEYCODE_VOLUME_DOWN) {
            // Si el botón de volumen bajo fue mantenido presionado, desbloqueamos
            unlockScreen()
            return true
        }
        return super.onKeyLongPress(keyCode, event)
    }

    // Método para desbloquear la pantalla y restaurar la interacción del usuario
    private fun unlockScreen() {
        // Limpia la bandera de no tocar para permitir la interacción de nuevo
        window.clearFlags(WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE)

        // Opcionalmente, puedes cerrar esta actividad y volver a la anterior
        finish()
    }

    override fun onBackPressed() {
        // Llama a la implementación de la superclase para evitar problemas con el sistema
        super.onBackPressed()

        // Tu código adicional, si es necesario
    }

    override fun onDestroy() {
        super.onDestroy()

        // Opcional: Si necesitas restaurar la interacción táctil al cerrar la actividad
        window.clearFlags(WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE)
    }
}
