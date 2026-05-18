# Plan de Reingenieria

## Diagnostico

El proyecto funciona como una app Android con servicio foreground centralizado, pero `BackgroundService.kt` concentra demasiadas responsabilidades: ubicacion, camara, audio, alarmas, preferencias remotas, subida de archivos, uso de apps, boot recovery y diagnostico. Esto dificulta pruebas, permisos granulares y mantenimiento.

Tambien hay componentes duplicados o parcialmente legacy: `ForegroundService`, `LocationForegroundService`, `LocationManager`, `FirebaseManager`, `ScreenProjectionService`, `SilentCaptureActivity` y flujos de captura que no siempre estan conectados en el manifest activo.

## Objetivo tecnico

Separar responsabilidades sin cambiar comportamiento observable. Cada capability debe tener un manager o use case con API clara, pruebas propias y puntos de integracion controlados desde el servicio principal.

## Fase 1: estabilizacion

- Congelar comportamiento actual con pruebas manuales documentadas en `04-build-operacion.md`.
- Crear tests unitarios para validacion de QR/email y construccion de datos de dispositivo.
- Eliminar secretos placeholder como `TU_CLAVE_SECRETA_DE_FCM` del cliente y mover notificaciones push a backend.
- Revisar `historydevices` y `subscriptions` en reglas Firebase.

## Fase 2: extraccion de modulos internos

Extraer desde `BackgroundService`:

- `DeviceContextResolver`: resuelve `userId`, `deviceId`, preferencias y referencias Firestore.
- `RemoteCommandListener`: escucha el documento del dispositivo y emite comandos tipados.
- `LocationTracker`: gestiona `FusedLocationProviderClient`.
- `RecordingController`: ciclos de MediaRecorder y cola de subida.
- `PhotoCaptureController`: Camera2/CameraX y subida de fotos.
- `UsageStatsReporter`: permisos, WorkManager y persistencia de uso.
- `AlarmController`: sonido, reconocimiento de palabra clave y cooldown.

## Fase 3: modelo de datos

- Definir data classes para `DeviceDocument`, `LocationRecord`, `RecordingMetadata`, `PhotoMetadata` y `UsageRecord`.
- Centralizar nombres de campos Firestore en constantes.
- Unificar escritura de metadata para evitar diferencias entre flujos Camera2, CameraX y activities auxiliares.

## Fase 4: permisos y UX

- Crear una pantalla unica de estado de permisos.
- Separar permisos runtime, background location, battery optimization y usage access.
- Mostrar estados accionables y no intentar abrir ajustes repetidamente si ya se solicito.

## Fase 5: limpieza

- Decidir si `ForegroundService` y `LocationForegroundService` siguen vivos o son reemplazados.
- Eliminar clases comentadas/no registradas si no forman parte del producto.
- Reducir logs sensibles y normalizar tags.
- Documentar releases, ambientes Firebase y signing.
