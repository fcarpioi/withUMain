# Integracion con App del Padre

## Repositorios

- App del menor: `/Users/fernandocarpio/AndroidStudioProjects/withUMain`.
- App del padre: `/Users/fernandocarpio/AndroidStudioProjects/with_u`.
- Hosting padre: `https://withu-nextou.web.app/app/`.
- Politica de privacidad: `https://withu-nextou.web.app/legal/politica-de-privacidad.html`.

## Contrato Firestore

La app del padre controla el dispositivo desde `users/{uid}/devices/{deviceId}`. La app del menor escucha ese documento y ejecuta comandos remotos:

- `trackingEnabled`: ubicacion en segundo plano.
- `takePhoto`: captura remota.
- `recordingEnabled`: grabacion por ciclos.
- `sound`: alarma.
- `trackApps`: estadisticas de uso de apps.
- `requestUsagePermission`: apertura de ajustes de Usage Access.

La app del menor reporta disponibilidad con `serviceOnline` y `lastHeartbeatAt`. La app del padre debe considerar el servicio inactivo si no hay heartbeat reciente y mostrar un mensaje para activar o abrir la app del menor.

## Cambios recientes en la app del padre

- No muestra QR durante la carga inicial si todavia esta consultando dispositivos.
- Solo muestra QR cuando Firestore confirma que no existe dispositivo vinculado.
- Muestra advertencia si la app del menor no reporta heartbeat reciente.
- La pantalla de login/crear cuenta reduce espacios y fuerza scroll cuando aparece el teclado movil.
- La web fue compilada y desplegada en Firebase Hosting con `./scripts/deploy_app_web.sh withu-nextou`.

## Validacion conjunta

1. Vincular un dispositivo del menor con QR.
2. Abrir la app del padre y confirmar que entra al home sin quedarse en QR.
3. Activar rastreo y verificar una ubicacion nueva en `devices/{deviceId}/locations`.
4. Revisar `serviceOnline: true` y `lastHeartbeatAt` actualizado.
5. Detener o bloquear la app del menor y confirmar que el padre muestra aviso de inactividad.
6. Probar login en Android con teclado visible para confirmar que los campos no quedan tapados.
