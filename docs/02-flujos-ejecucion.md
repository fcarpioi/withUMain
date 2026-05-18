# Flujos de Ejecucion

## Arranque normal

1. `SplashActivity` muestra la pantalla inicial durante unos segundos.
2. `MainActivity` comprueba `FirebaseAuth.currentUser`.
3. Si no hay usuario autenticado, abre `RegisterActivity`.
4. Si hay usuario, solicita permisos de ubicacion, audio, camara y notificaciones segun version Android.
5. Si los permisos criticos estan listos, inicia `BackgroundService` como foreground service.
6. La app verifica el heartbeat en `ServiceStatePrefs`; si el servicio responde, manda la tarea al fondo.

## Vinculacion por QR

`RegisterActivity` abre CameraX y ML Kit para leer un QR JSON con `email` e `idDevice`. Despues:

1. Limpia y valida el email.
2. Pide password en `dialog_password_input`.
3. Ejecuta `signInWithEmailAndPassword`.
4. Verifica o crea `users/{uid}/devices/{idDevice}`.
5. Guarda `idDevice` y `email` en `UserPrefs`.
6. Solicita permisos de servicio y arranca `BackgroundService`.

Campos iniciales del dispositivo: `deviceId`, `deviceName`, `localDeviceId`, `lastCoordinate`, `locationUpdateInterval`, `recordingEnabled`, `trackingEnabled`, `battery`, `sound`, `takePhoto`, `trackApps`, `takePicture`, `requestUsagePermission`, `from`, `to` y `linkedAt`.

## Servicio en segundo plano

`BackgroundService` inicializa Firebase, ubicacion, WorkManager, camara, audio, receptores y listeners Firestore. Publica un heartbeat local con:

- `ServiceStatePrefs.service_running`
- `ServiceStatePrefs.last_service_heartbeat`
- `ServiceStatePrefs.last_service_stopped`

Tambien escucha cambios del documento `users/{uid}/devices/{deviceId}` para activar o desactivar funciones.

## Comandos remotos por Firestore

El documento del dispositivo funciona como panel de control remoto:

- `trackingEnabled`: inicia o detiene ubicacion.
- `recordingEnabled`: inicia o detiene ciclos de grabacion.
- `takePhoto`: dispara captura y subida de foto.
- `sound`: reproduce alarma y luego resetea el campo.
- `trackApps`: sube estadisticas de uso de apps.
- `requestUsagePermission`: abre ajustes de Usage Access cuando aplica.
- `locationUpdateInterval`: ajusta frecuencia de ubicacion.

## Recuperacion tras reinicio

`BootReceiver` escucha `BOOT_COMPLETED` y `MY_PACKAGE_REPLACED`. Intenta arrancar `BackgroundService` con razon `boot` y agenda `BootBackgroundServiceRecovery` con `BackgroundServiceWorker` despues de 20 segundos. `BootDiagnostics` guarda marcas para diagnosticar si el receptor, servicio o worker llegaron a ejecutarse.

## Capturas, audio y uso de apps

Las grabaciones se guardan temporalmente en almacenamiento externo de la app y se suben a Storage. Las fotos se capturan con Camera2/CameraX y se registran en Firestore. El uso de apps requiere `PACKAGE_USAGE_STATS` y se persiste bajo la subcoleccion `usage`.
