# Riesgos y Seguridad

## Permisos sensibles

La app solicita ubicacion fina y background, microfono, camara, notificaciones, media, arranque al iniciar, foreground service y usage stats. Estos permisos requieren consentimiento claro, finalidad legitima y controles visibles para el usuario o administrador autorizado.

Evitar cualquier uso sin autorizacion explicita. Las funciones de ubicacion, audio, camara, screenshots, uso de apps y arranque en segundo plano pueden tener implicaciones legales y de privacidad.

## Datos sensibles

Datos generados:

- Ubicaciones y timestamps.
- Fotos, screenshots y grabaciones.
- Uso de aplicaciones.
- SMS si el receiver se activa en manifest futuro.
- Tokens FCM y notificaciones.

Recomendaciones:

- Minimizar retencion y definir expiracion por tipo de dato.
- Mantener una politica de privacidad visible dentro de la app y en Play Console: `https://withu-nextou.web.app/legal/politica-de-privacidad.html`.
- No registrar emails, passwords, tokens, URLs firmadas ni rutas privadas en logs.
- Cifrar datos locales si se guardan credenciales o identificadores persistentes.
- Evitar subir archivos si el usuario o dispositivo no tiene suscripcion/permiso vigente.

## Firebase

Las reglas principales usan ownership por `request.auth.uid`, pero hay aperturas:

- `historydevices/{document=**}` permite lectura y creacion sin auth.
- `subscriptions/{document=**}` permite lectura y escritura sin auth.

Antes de produccion, reemplazar esas reglas por validaciones autenticadas o mover esas operaciones a Cloud Functions.

## Push y backend

`BackgroundService.kt` contiene un placeholder de server key FCM. Las claves de servidor nunca deben vivir en el APK. El envio push debe hacerse desde backend o Cloud Functions con credenciales protegidas.

## Firma y configuracion

No versionar:

- `keystore.properties`
- `*.keystore`
- `local.properties`
- logs, crash dumps y capturas temporales
- `tmp_debug/`

`keystore.properties.example` debe ser la unica referencia compartida para variables de firma.

## Cumplimiento operativo

Mantener documentado:

- Quien puede activar comandos remotos.
- Que permisos acepta el usuario y cuando.
- Como se revoca acceso.
- Donde se almacenan datos y por cuanto tiempo.
- Como se elimina un dispositivo y sus archivos en Firestore/Storage.
- Que la notificacion foreground y los textos de disclosure expliquen que se trata de monitoreo parental visible, no una app oculta.
