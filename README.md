# CobrosPyme

App Android para registrar clientes, deudas y enviar recordatorios de cobro por WhatsApp.

## Incluye en esta versión

- Panel principal con total por cobrar, cobrado, vencidos y clientes.
- Registro local de clientes.
- Registro local de cobros/deudas.
- Marcar cobros como pagados.
- Recordatorio de deuda por WhatsApp.
- Datos guardados localmente en el teléfono.
- Compilación automática de APK desde GitHub Actions.

## Paquete Android

`cl.negociospyme.cobros`

## Compilar en GitHub

1. Sube todos los archivos de este proyecto a un repositorio GitHub.
2. Entra a la pestaña **Actions**.
3. Abre **Android Build**.
4. Presiona **Run workflow** si no se ejecutó automáticamente.
5. Al terminar, entra al trabajo completado y descarga el artefacto **CobrosPyme-debug-apk**.

El APK generado queda en `app/build/outputs/apk/debug/app-debug.apk`.

## Requisitos del proyecto

- Android Gradle Plugin 9.4.0
- Gradle 9.6
- JDK 17
- compileSdk / targetSdk 37
- minSdk 24
- Jetpack Compose BOM 2026.08.00
