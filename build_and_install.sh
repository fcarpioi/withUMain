#!/bin/bash

# Salir si ocurre algún error
set -e

# Nombre del APK generado
APK_PATH="app/build/outputs/apk/debug/app-debug.apk"

# Paso 1: Verificar si hay dispositivo conectado
echo "🔍 Verificando dispositivos conectados..."
if ! adb get-state 1>/dev/null 2>&1; then
    echo "❌ No hay dispositivo/emulador conectado. Abre uno y vuelve a intentar."
    exit 1
fi

# Paso 2: Compilar el APK
echo "🔨 Compilando APK en modo Debug..."
./gradlew assembleDebug

# Paso 3: Instalar el APK en el dispositivo
echo "📦 Instalando APK en el dispositivo..."
adb install -r "$APK_PATH"

# Paso 4: Lanzar la app
echo "🚀 Iniciando la app..."
adb shell monkey -p com.controlparental.jerico -c android.intent.category.LAUNCHER 1

echo "✅ Proceso completado exitosamente."