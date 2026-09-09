# Descarga APK desde la Landing

## Ruta Canonica

Usar Firebase Storage para publicar el APK descargable:

```text
downloads/public/withu-familiar-latest.apk
```

URL publica para la landing:

```text
https://firebasestorage.googleapis.com/v0/b/withu-nextou.firebasestorage.app/o/downloads%2Fpublic%2Fwithu-familiar-latest.apk?alt=media
```

## Subir APK

No se compilo ni se desplego como parte de este cambio. Actualmente existe un APK debug en:

```text
app/build/outputs/apk/debug/app-debug.apk
```

Para publicar ese APK existente:

```bash
firebase deploy --only storage --project withu-nextou
gsutil -h "Content-Type: application/vnd.android.package-archive" \
  cp app/build/outputs/apk/debug/app-debug.apk \
  gs://withu-nextou.firebasestorage.app/downloads/public/withu-familiar-latest.apk
```

Para publicar un release, primero generarlo y subirlo con el mismo destino:

```bash
./gradlew :app:assembleRelease
firebase deploy --only storage --project withu-nextou
gsutil -h "Content-Type: application/vnd.android.package-archive" \
  cp app/build/outputs/apk/release/app-release.apk \
  gs://withu-nextou.firebasestorage.app/downloads/public/withu-familiar-latest.apk
```

## Instruccion para la Landing

El boton de descarga debe apuntar directamente a:

```html
<a
  href="https://firebasestorage.googleapis.com/v0/b/withu-nextou.firebasestorage.app/o/downloads%2Fpublic%2Fwithu-familiar-latest.apk?alt=media"
  download
>
  Descargar app Android
</a>
```

Si la landing usa una variable de entorno, configurar:

```text
ANDROID_APK_URL=https://firebasestorage.googleapis.com/v0/b/withu-nextou.firebasestorage.app/o/downloads%2Fpublic%2Fwithu-familiar-latest.apk?alt=media
```

## Notas

- Mantener siempre el alias `withu-familiar-latest.apk` para que la landing no cambie por version.
- Para versiones historicas se puede subir tambien `downloads/public/withu-familiar-1.0.0.apk`.
- Android pedira al usuario permitir instalaciones desde origenes desconocidos si la app no se instala desde Google Play.
