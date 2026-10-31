# Paper Bot Binance — versión Android

Port nativo (Kotlin) de `bot_binance_paper.py`. Misma estrategia (tendencia 4H + recuperación EMA20 en 1H),
mismos 4 pares, mismos parámetros, mismo capital simulado (591 USDT). Solo simulación: sin API keys ni órdenes.

## Compilar gratis el APK (GitHub Actions)
1. Crea una cuenta en github.com y un repositorio nuevo (puede ser privado).
2. Sube TODO el contenido de esta carpeta (incluida la carpeta oculta `.github`). En la web: "Add file → Upload files";
   arrastra también `.github/workflows/build-apk.yml` (si no te deja subir carpetas ocultas, crea el archivo a mano
   con "Add file → Create new file" y el nombre `.github/workflows/build-apk.yml`).
3. Pestaña **Actions** → "Build APK" → se ejecuta solo (o "Run workflow"). Tarda ~4-6 min.
4. Entra a la ejecución terminada → **Artifacts → PaperBot-apk** → descarga el ZIP → dentro está `app-debug.apk`.
5. Pásalo al móvil, ábrelo y permite "instalar apps desconocidas".

## Primer uso en el móvil
1. Abre la app → **INICIAR BOT** → acepta las notificaciones.
2. Pulsa **Permitir uso de batería sin restricciones** y acepta (imprescindible para que no lo duerma Android).
3. Pulsa **Probar notificación** para confirmar que las alertas llegan.
4. En Xiaomi/Huawei/Samsung/Oppo: Ajustes → Apps → Paper Bot → Batería → "Sin restricciones" y activa "Inicio automático".
   Más info por marca: https://dontkillmyapp.com

## Qué hace en segundo plano
- Servicio en primer plano (notificación fija con equity y botón Detener) + wake lock parcial.
- Cada 60 s analiza velas 1H/4H cerradas; cada 10 s actualiza precios.
- Notificación en: SEÑAL, ENTRADA, TAKE PROFIT PARCIAL, STOP/TRAILING, CIERRE, y error de conexión (máx. 1 cada 15 min por par).
- Guarda el estado y se reanuda solo tras reiniciar el móvil (si estaba activo).
- CSV de eventos y operaciones: carpeta Android/data/com.carlos.paperbot/files/ (ruta visible en la app).

## Cambiar parámetros
Edita los valores de `BotConfig` al inicio de `app/src/main/java/com/carlos/paperbot/Core.kt` y vuelve a subir/commit;
Actions genera un APK nuevo. Si cambias el capital inicial, usa "Reiniciar cuenta simulada".

## Diferencias con la versión de escritorio
Sin gráfico, sin backtest de 6 meses y sin WhatsApp/Twilio (lo reemplazan las notificaciones del móvil).
Añadida la notificación de SEÑAL (la de escritorio solo avisaba al entrar).
