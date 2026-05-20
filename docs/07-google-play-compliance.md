# Google Play Compliance Review

Fecha de revision: 2026-05-20.

## Estado del APK

- `targetSdk 35`: cumple el requisito vigente de Google Play para nuevas apps y actualizaciones, que exige Android 15/API 35 o superior desde el 31 de agosto de 2025.
- `compileSdk 36`: el proyecto ya compila contra SDK 36. Android 16/API 36 debe probarse en dispositivo o emulador Android 16 antes de subir a produccion.
- `minSdk 29`: compatible con Android 10 o superior.
- `./gradlew :app:compileDebugKotlin`: aprobado.
- `./gradlew :app:lintDebug`: aprobado.
- `./gradlew :app:bundleRelease`: genera AAB firmado en `app/build/outputs/bundle/release/app-release.aab`.

## Cambios aplicados

- Se quitaron permisos amplios de fotos, video, audio y almacenamiento compartido porque la app usa camara/caches propios, no lectura persistente de galeria:
  - `READ_MEDIA_IMAGES`
  - `READ_MEDIA_VIDEO`
  - `READ_MEDIA_AUDIO`
  - `READ_MEDIA_VISUAL_USER_SELECTED`
  - `READ_EXTERNAL_STORAGE`
  - `WRITE_EXTERNAL_STORAGE`
- Se quito `requestLegacyExternalStorage`.
- Se quitaron permisos no usados en el codigo revisado:
  - `READ_PHONE_STATE`
  - `READ_GSERVICES`
  - `FOREGROUND_SERVICE_MEDIA_PROJECTION`
- Se agregaron disclosures antes de permisos/capacidades sensibles:
  - Camara para escaneo QR.
  - Ubicacion en segundo plano, microfono, camara remota, notificacion persistente y uso de apps.
  - Acceso de uso de aplicaciones antes de abrir ajustes.
- Se movieron textos hardcoded de permisos a `strings.xml` y `values-es/strings.xml`.
- Se agrego enlace visible a politica de privacidad en pantallas de inicio/vinculacion.
- Se renombro el tema de background a `Theme.WithUBackground` para evitar lenguaje que sugiera ocultamiento.
- Se aclaro la notificacion del servicio para indicar monitoreo parental activo.
- Se agrego heartbeat remoto (`serviceOnline`, `lastHeartbeatAt`) para que la app del padre pueda advertir si la app del menor no esta funcionando.

## Requisitos de Play Console pendientes fuera del codigo

- Confirmar en Play Console la politica de privacidad publicada: `https://withu-nextou.web.app/legal/politica-de-privacidad.html`.
- Completar Data Safety declarando ubicacion, microfono, camara, actividad de apps, archivos subidos, identificadores y uso de Firebase.
- Completar Permissions Declaration Form para `ACCESS_BACKGROUND_LOCATION`.
- Adjuntar video de 30 segundos o menos que muestre:
  - disclosure prominente dentro de la app;
  - solicitud de permiso runtime;
  - activacion de la funcion principal de ubicacion en segundo plano.
- Si la app se publica para menores o familias, revisar Google Play Families Policy y clasificacion de contenido.
- Subir a prueba interna el AAB firmado y revisar Pre-launch report antes de produccion.

## Riesgos residuales

- La app usa permisos altamente sensibles: ubicacion en segundo plano, microfono, camara, usage stats y arranque tras reinicio. La descripcion de Play debe presentar claramente que es una app de control parental y no una herramienta oculta.
- Las funciones de camara/audio remotas deben estar justificadas como seguridad parental y descritas en la politica de privacidad.
- El envio FCM no debe usar claves de servidor embebidas en el APK; cualquier envio push debe hacerse desde backend o Cloud Functions.
- Las reglas Firestore actuales tienen rutas abiertas para `historydevices` y `subscriptions`; antes de produccion deben cerrarse o justificarse.
- La app del padre debe mantener mensajes claros al activar rastreo, fotos, audio o uso de apps, porque esos comandos disparan funciones sensibles en el dispositivo del menor.

## Fuentes oficiales revisadas

- Google Play target API level requirements: apps nuevas/updates deben apuntar a API 35+ desde 2025-08-31.
- Google Play prominent disclosure and consent: disclosure dentro de la app antes de permisos/capacidades sensibles.
- Google Play background location guidance: disclosure debe incluir “location” y “background” / “when the app is closed” / equivalente.
- Google Play photo/video permissions policy: evitar `READ_MEDIA_IMAGES` y `READ_MEDIA_VIDEO` si no hay necesidad principal de acceso persistente a galeria.
- Android 16 behavior changes: probar Android 16 por cuotas de JobScheduler/FGS e interacciones de UX.
