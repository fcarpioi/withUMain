# Repository Guidelines

## Project Structure & Module Organization

This is a single-module Android app. Core Kotlin source lives in `app/src/main/java/com/controlparental/jerico`. The active runtime is centered on Activities, receivers, disclosure helpers, `BackgroundService`, and boot recovery. XML layouts are in `app/src/main/res/layout`, drawables and launcher assets are under `drawable/` and `mipmap-*`, localized strings are in `values/` and `values-es/`, and raw assets are in `raw/`.

Unit tests belong in `app/src/test/java/...`; device tests belong in `app/src/androidTest/java/...`. Firebase configuration is at the repo root: `firebase.json`, `firestore.rules`, and `storage.rules`.

## Build, Test, and Development Commands

- `./gradlew :app:assembleDebug` builds a debug APK for local install and smoke testing.
- `./gradlew :app:compileDebugKotlin` quickly checks Kotlin compilation.
- `./gradlew :app:lintDebug` runs Android lint with the project’s configured suppressions.
- `./gradlew test` runs local JVM tests.
- `./gradlew connectedAndroidTest` runs instrumentation tests on a connected emulator or device.
- `./gradlew clean` removes Gradle build outputs.

Use the checked-in Gradle wrapper rather than a system Gradle install.

## Coding Style & Naming Conventions

Use Kotlin with Java 17 compatibility. Follow standard Android/Kotlin style: 4-space indentation, `PascalCase` for classes and activities/services, `camelCase` for functions and properties, and uppercase snake case for constants. Keep package paths aligned with `com.controlparental.jerico`.

Prefer existing patterns: Activities, Services, Receivers, WorkManager workers, Firebase APIs, and XML resources. Add user-facing text to `strings.xml` rather than hardcoding it.

## Testing Guidelines

Add local tests for pure Kotlin logic in `app/src/test/java` and instrumentation tests for Android framework, permissions, receivers, services, camera, or Firebase behavior in `app/src/androidTest/java`. Name test files after the class or behavior under test, for example `BackgroundServiceTest.kt`.

Run `./gradlew test` before submitting logic changes and `./gradlew connectedAndroidTest` when behavior depends on Android APIs.

## Commit & Pull Request Guidelines

Recent commits use short, imperative, lowercase summaries, sometimes with phase prefixes, for example `harden speech recognizer lifecycle to avoid audio hijack`. Keep commits focused and avoid mixing cleanup with behavior changes.

Pull requests should include a concise description, testing performed, related issue or task, and screenshots or screen recordings for UI changes. Call out permission, background service, Firebase rule, or signing/config changes explicitly.

## Security & Configuration Tips

Do not commit real signing secrets or local machine paths. Use `keystore.properties.example` as the template and keep `keystore.properties`, release keystores, logs, and temporary debug captures out of shared changes unless intentionally required.

## Documentation Map

Before larger changes, read `docs/README.md` and the relevant deep-dive:

- `docs/01-arquitectura.md` for modules, dependencies, and responsibilities.
- `docs/02-flujos-ejecucion.md` for startup, QR linking, background service, and remote commands.
- `docs/03-datos-firebase.md` for Firestore, Storage, and local preferences.
- `docs/04-build-operacion.md` for Gradle, adb, Firebase deploy, and manual checks.
- `docs/05-plan-reingenieria.md` for refactoring phases.
- `docs/06-riesgos-seguridad.md` for privacy, permissions, and secret handling.
- `docs/07-google-play-compliance.md` for Play policy, sensitive permissions, and release blockers.
- `docs/08-integracion-app-padre.md` for the parent app contract, hosting, heartbeat, and cross-app validation.
