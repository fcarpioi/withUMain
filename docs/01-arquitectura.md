# Arquitectura

## Vista general

El proyecto es una app Android de un solo modulo (`:app`) escrita en Kotlin, con vistas XML. Usa Firebase Authentication, Firestore y Storage para vincular dispositivos, recibir configuracion remota y subir telemetria o archivos.

Configuracion principal:

- Namespace: `com.controlparental.jerico`
- Application ID: `com.withu.app`
- `compileSdk 36`, `targetSdk 35`, `minSdk 29`
- Kotlin `2.1.20`, Java/Kotlin JVM target `17`
- Gradle Android Plugin gestionado en `gradle/libs.versions.toml`

## Estructura

- `app/src/main/java/com/controlparental/jerico/`: Activities, services, receivers y utilidades principales.
- `app/src/main/java/com/controlparental/jerico/`: Activities, services, receivers, disclosures y utilidades activas.
- `app/src/main/res/layout/`: pantallas y dialogos XML.
- `app/src/main/res/values*`: strings, colores, estilos y temas.
- `firebase.json`, `firestore.rules`, `storage.rules`: configuracion Firebase.

## Componentes principales

- `SplashActivity`: pantalla inicial y redireccion a `MainActivity`.
- `MainActivity`: valida sesion Firebase, solicita permisos base e inicia `BackgroundService`.
- `RegisterActivity`: escanea QR con ML Kit, autentica por email/password, vincula `idDevice` y arranca el servicio.
- `BackgroundService`: servicio foreground central. Gestiona ubicacion, bateria, grabacion, fotos, alarma, uso de apps, preferencias remotas y heartbeat local.
- `BootReceiver`: arranca el servicio tras `BOOT_COMPLETED` o `MY_PACKAGE_REPLACED` y agenda un worker de recuperacion.
- `BackgroundServiceWorker`: reintenta arrancar el servicio desde WorkManager.
- `BootDiagnostics`: registra marcas de diagnostico en `SharedPreferences`.

## Dependencias relevantes

La app usa CameraX, ML Kit Barcode Scanning, Play Services Location, WorkManager, Firebase Auth/Firestore/Storage/App Check, Material y AppCompat.
