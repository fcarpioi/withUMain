# Alertas de Movimiento

## Objetivo

La app del menor usa el acelerometro mientras `BackgroundService` esta activo para detectar dos eventos de seguridad:

- `fall_detected`: posible caida, detectada por baja aceleracion seguida de impacto.
- `possible_snatch`: posible arrebato, detectado por aceleracion y cambio brusco de movimiento.

Android no ofrece una senal nativa de "telefono arrebatado"; este evento es una heuristica y puede requerir calibracion por dispositivo.

## Contrato Firestore

Campos remotos en `users/{uid}/devices/{deviceId}`:

- `fallDetectionEnabled`: habilita o deshabilita deteccion de caidas. Por defecto `true`.
- `snatchDetectionEnabled`: habilita o deshabilita deteccion de posible arrebato. Por defecto `true`.
- `motionAlertCooldownSeconds`: evita alertas repetidas. Minimo efectivo: 15 segundos.
- `lastSecurityEventType`, `lastSecurityEventAt`, `lastSecurityEventSeverity`: resumen del ultimo evento.

Eventos historicos:

```text
users/{uid}/devices/{deviceId}/securityEvents/{eventId}
```

Notificaciones para la app del padre:

```text
users/{uid}/notifications/{notificationId}
```

Cada notificacion incluye `eventType`, `severity`, `title`, `message` y `pushStatus: pending`.

## Push FCM

La funcion `functions/sendParentNotification` escucha nuevas notificaciones, lee los tokens en `users/{uid}/tokens` y envia FCM con Firebase Admin SDK. No pongas claves de servidor FCM dentro del APK.

## Instrucciones para validar

No se compilo ni desplego como parte de este cambio. Para hacerlo manualmente:

```bash
./gradlew :app:compileDebugKotlin
./gradlew :app:assembleDebug
cd functions && npm install
firebase deploy --only functions:sendParentNotification
```

En pruebas fisicas, empieza con `motionAlertCooldownSeconds` alto para evitar spam de alertas y calibra umbrales si hay falsos positivos.
