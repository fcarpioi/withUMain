# Datos y Firebase

## Firestore

Raiz principal:

```text
users/{userId}
  devices/{deviceId}
    locations/{locationId}
    recordings/{recordingId}
    photos/{photoId}
    screenshots/{screenshotId}
    usage/{packageId}
      stats/usageData
  notifications/{notificationId}
  tokens/{tokenId}
  sms/{smsId}
  payments/{paymentId}
```

Campos relevantes de `users/{userId}/devices/{deviceId}`:

- Identidad: `deviceId`, `deviceName`, `localDeviceId`, `isDefault`.
- Estado: `battery`, `lastCoordinate`, `lastTimeStamp`, `linkedAt`, `from`, `to`, `serviceOnline`, `lastHeartbeatAt`.
- Control remoto: `trackingEnabled`, `recordingEnabled`, `takePhoto`, `sound`, `trackApps`, `requestUsagePermission`.
- Configuracion: `locationUpdateInterval`.

`serviceOnline` y `lastHeartbeatAt` son el contrato actual para que la app del padre detecte si el servicio del menor esta vivo. Considerar stale si `lastHeartbeatAt` supera el umbral definido por la app del padre; actualmente se usa una ventana operativa de aproximadamente 5 minutos.

Colecciones legacy:

- `locations/{locationId}`: permite compatibilidad con datos antiguos.
- `historydevices/{...}`: lectura y creacion abiertas en reglas actuales.
- `subscriptions/{...}`: lectura y escritura abiertas en reglas actuales.

## Storage

Rutas usadas:

```text
photos/{userId}/{fileName}
recordings/{userId}/{deviceId}/{fileName}
screenshots/{userId}/{fileName}
```

Las reglas limitan fotos y screenshots a imagenes menores de 20 MB. Las grabaciones permiten audio, video u octet-stream hasta 150 MB.

## Estado local

`SharedPreferences` usadas:

- `UserPrefs`: `idDevice`, `email`.
- `ServiceStatePrefs`: heartbeat y estado del servicio.
- `AppPrefs`: marca `usagePermissionRequested`.
- `BootDiagnostics`: marcas de arranque, errores y worker.
- `ScreenCapturePrefs`: datos de MediaProjection para capturas de pantalla.

`DeviceIdHolder.deviceId` mantiene el `idDevice` en memoria de proceso, pero no reemplaza `UserPrefs`.

## Reglas de seguridad

`firestore.rules` restringe la rama principal a `request.auth.uid == userId`. Hay excepciones abiertas para `historydevices` y `subscriptions`; deben revisarse antes de produccion si contienen datos sensibles o si pueden ser abusadas.

`storage.rules` exige autenticacion y propiedad por `userId`. Las rutas fuera de `photos`, `recordings` y `screenshots` quedan denegadas.
