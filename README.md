# NMEA Bridge — Android mDNS Discovery

Тестовое приложение для Android: ищет HTTP-сервисы через системный DNS-SD/mDNS API `NsdManager`, определяет адрес NMEA Bridge и проверяет его HTTP-сервер. Если сервер отвечает — открывает WebView с интерфейсом ESP8266.

Никаких сканирований подсети и дополнительных UDP-портов для обнаружения.

## Что нужно для эксперимента

1. Включить точку доступа Android и подключить к ней NMEA Bridge (ESP8266, STA).
2. В прошивке ESP должен быть опубликован HTTP-сервис DNS-SD, например:
   ```cpp
   if (MDNS.begin("nmea-bridge")) MDNS.addService("http", "tcp", 80);
   // В loop(): MDNS.update();
   ```
3. Запустить Android-приложение: оно покажет обнаруженные сервисы и откроет подходящий HTTP-сервер. Кнопка повторного поиска позволяет повторить эксперимент.

**Важно:** Android-телефон, сам работающий в режиме hotspot, может не получать mDNS-сообщения со стороны клиентов. Это проверяется экспериментально.

## APK без Android Studio

Сборка GitHub Actions: вкладка **Actions → Build NMEA Bridge APK**. После успешного выполнения скачайте artifact `NMEA-Bridge-mDNS-debug-APK`, в котором находится `app-debug.apk`. Команда Gradle: `gradle :app:assembleDebug`. Приложение тестовое и не подписано релизным ключом (debug signing).
