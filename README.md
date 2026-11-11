# NMEA Bridge Android — mDNS Discovery

Минимальное приложение: Android **NsdManager** обнаруживает HTTP-сервис ESP8266 через mDNS/DNS-SD, проверяет HTTP и открывает Web GUI в Android WebView.

## Версии

- **v0.1.0 — Java**, первая экспериментальная, проверена на реальном Android-телефоне. Исходники зафиксированы в ветке [android-v0.1.0-java](../../tree/android-v0.1.0-java). Рабочая GitHub Actions сборка: [run 41442694805](../../actions/runs/41442694805).
- **v0.2.0 — Kotlin** в ветке \`main\`. Это перенос Java-приложения без изменений логики и дизайна.

## Поведение v0.2.0

1. Поиск \`_http._tcp.\` через Android NsdManager (multicast lock).
2. Список всех найденных HTTP-сервисов; имена с "nmea"/"bridge" проверяются автоматически.
3. Последовательное DNS-SD разрешение в IPv4 и порт.
4. Проверка HTTP (таймауты подключения и чтения 2200 мс, успех HTTP 200–399).
5. Открытие WebView, поддержка кнопки назад и повторного поиска.

Разрешения и настройки Android, applicationId \`org.nmeabridge.mdns\`, targetSdk 34, minSdk 23 остаются прежними. Прошивку ESP менять не требуется, если сервис уже опубликован через \`MDNS.addService("http", "tcp", 80)\`.
