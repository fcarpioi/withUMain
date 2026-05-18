# Build, Pruebas y Operacion

## Requisitos

- JDK 17. El proyecto fija `org.gradle.java.home` en `gradle.properties`.
- Android SDK con `compileSdk 36`.
- Firebase configurado con `app/google-services.json`.
- Para release, `keystore.properties` y keystore local. No usar valores reales en archivos ejemplo.

## Comandos Gradle

```bash
./gradlew :app:assembleDebug
./gradlew :app:compileDebugKotlin
./gradlew :app:lintDebug
./gradlew test
./gradlew connectedAndroidTest
./gradlew clean
```

Uso recomendado:

- `assembleDebug`: genera APK debug.
- `compileDebugKotlin`: verificacion rapida de compilacion Kotlin.
- `lintDebug`: auditoria Android Lint con supresiones del proyecto.
- `test`: pruebas JVM locales.
- `connectedAndroidTest`: pruebas en emulador o dispositivo conectado.
- `clean`: limpia outputs de Gradle.

## Instalacion y diagnostico

Para pruebas manuales, compilar debug e instalar en un dispositivo con permisos suficientes:

```bash
./gradlew :app:assembleDebug
adb install -r app/build/outputs/apk/debug/app-debug.apk
adb logcat | rg "WithUBoot|BackgroundService|QRCodeDebug|AppUsageWorker"
```

`BootDiagnostics.snapshot(context)` aparece en logs de `MainActivity` y `RegisterActivity`, y ayuda a verificar receptor de arranque, inicio de servicio, worker y errores.

## Firebase

Reglas locales:

```bash
firebase deploy --only firestore:rules
firebase deploy --only storage
```

Antes de desplegar reglas, revisar rutas abiertas en `historydevices` y `subscriptions`. Si el entorno usa varios proyectos Firebase, confirmar `firebase use` o `.firebaserc` antes del deploy.

## Pruebas manuales minimas

1. Escanear QR valido y autenticar.
2. Verificar creacion/actualizacion de `users/{uid}/devices/{idDevice}`.
3. Activar `trackingEnabled` y validar `locations`.
4. Activar `takePhoto` y validar Storage + Firestore.
5. Activar `recordingEnabled` y validar subida de grabacion.
6. Reiniciar el dispositivo y confirmar `BootReceiver` + heartbeat.
