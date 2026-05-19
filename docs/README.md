# Documentacion del Proyecto

Esta carpeta contiene la reingenieria documental del proyecto Android `Jerico` / `withU`. El objetivo es dejar una vista tecnica clara para mantenimiento, depuracion, auditorias y futuras refactorizaciones.

## Indice

- [Arquitectura](01-arquitectura.md): modulos, responsabilidades y dependencias principales.
- [Flujos de ejecucion](02-flujos-ejecucion.md): arranque, vinculacion, servicio en segundo plano y comandos remotos.
- [Datos y Firebase](03-datos-firebase.md): modelo Firestore, rutas Storage, reglas y estado local.
- [Build, pruebas y operacion](04-build-operacion.md): comandos Gradle, instalacion, diagnostico y despliegue de reglas.
- [Plan de reingenieria](05-plan-reingenieria.md): deuda tecnica detectada y propuesta por fases.
- [Riesgos y seguridad](06-riesgos-seguridad.md): permisos sensibles, privacidad, configuracion y controles recomendados.
- [Google Play compliance](07-google-play-compliance.md): revision de politicas, permisos, disclosures y pendientes de Play Console.

## Lectura recomendada

Para entender el sistema completo, leer en este orden:

1. `01-arquitectura.md`
2. `02-flujos-ejecucion.md`
3. `03-datos-firebase.md`
4. `06-riesgos-seguridad.md`
5. `05-plan-reingenieria.md`
6. `07-google-play-compliance.md`

## Fuentes revisadas

La documentacion se basa en `app/build.gradle`, `settings.gradle.kts`, `AndroidManifest.xml`, `BackgroundService.kt`, `RegisterActivity.kt`, `MainActivity.kt`, `BootReceiver.kt`, `BackgroundServiceWorker.kt`, `AppUsageWorker.kt`, `FirebaseManager.kt`, `LocationManager.kt`, `firestore.rules` y `storage.rules`.
