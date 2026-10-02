
# AlReaderX Sync

**Синхронизация библиотеки AlReaderX между телефоном и читалкой по Wi-Fi или Bluetooth.**

Android-приложение для двусторонней синхронизации каталогов AlReaderX и книг между смартфоном и устройством для чтения. Работает через Wi-Fi LAN с автоматическим fallback на Bluetooth, если Wi-Fi недоступен.

----------

## Возможности

-   **Синхронизация каталогов AlReaderX и Books** между телефоном и читалкой.
    
-   **Wi-Fi LAN (основной транспорт)** — быстрое TCP-соединение через локальную сеть.
    
-   **Bluetooth (fallback)** — RFCOMM-соединение, если Wi-Fi недоступен или заблокирован (AP isolation, firewall).
    
-   **Два режима синхронизации:**
    
    -   **Замена** (`FULL_REPLACE`) — приводит содержимое приёмника в точное соответствие с источником. Лишние файлы удаляются.
        
    -   **Объединение** (`MERGE_NEWEST_WINS`) — добавляет новые файлы, не удаляет существующие.
        
-   **Два направления:**
    
    -   **Телефон → Ридер**
        
    -   **Ридер → Телефон**
        
-   **Foreground service** — синхронизация продолжается при сворачивании приложения.
    
-   **Уведомление о прогрессе** с возможностью отмены.
    
-   **Гибкая настройка транспорта** — режим «Автоматически» или «Только Bluetooth».
    
-   **Настраиваемые retry/timeout** для каждого транспорта.
    
-   **Книги внутри AlReaderX** — опция для случаев, когда библиотека вложена в каталог AlReaderX.
    
-   **Поддержка Android 8.0+** (API 26+) и **Android 15** (edge-to-edge).
    

----------

## Архитектура

### Роли устройства

Приложение работает на двух устройствах:

-   **PHONE (Телефон)** — инициирует синхронизацию, управляет параметрами.
    
-   **READER (Ридер)** — запускает сервер, ждёт подключения телефона.
    

### Транспорты

Транспорт

Порт

Приоритет

Когда используется

**Wi-Fi LAN**

TCP, динамический

1

По умолчанию, если Wi-Fi включён на обоих устройствах

**Bluetooth**

RFCOMM, UUID `8ce255c0-...`

2

Fallback при недоступности Wi-Fi

### Протокол синхронизации

1.  **Handshake (Bluetooth RFCOMM)** — обмен `HandshakeRequest` / `HandshakeResponse`. Телефон передаёт список задач, режим, направление. Ридер отвечает своим IP и портом.
    
2.  **Открытие транспорта** — телефон подключается по Wi-Fi LAN (или Bluetooth, если Wi-Fi недоступен).
    
3.  **Обмен манифестами** — источник передаёт список файлов (путь, размер, время модификации).
    
4.  **Планирование** — приёмник строит план: что передать, что удалить.
    
5.  **Передача файлов** — источник отправляет запрошенные файлы.
    
6.  **Завершение** — обмен `Complete`, `Ping`/`Pong` для гарантии доставки последних байтов.
    

### Компоненты

```
app/src/main/java/ru/samorez/alreaderxsync/
├── MainActivity.kt                    # Главный экран (Phone + Reader)
├── SettingsActivity.kt                # Экран настроек
├── data/
│   ├── DeviceRole.kt                  # PHONE / READER
│   ├── SyncMode.kt                    # FULL_REPLACE / MERGE_NEWEST_WINS
│   ├── SyncDirection.kt               # PHONE_TO_READER / READER_TO_PHONE
│   ├── SyncState.kt                   # Состояние синхронизации
│   ├── SyncResult.kt                  # Результат синхронизации
│   ├── TransportConfig.kt             # Конфигурация транспортов
│   └── TransportMode.kt               # AUTO / BLUETOOTH_ONLY
├── protocol/
│   ├── SyncMessage.kt                 # JSON-сообщения протокола
│   └── SyncProtocol.kt                # Чтение/запись сообщений
├── service/
│   ├── SyncService.kt                 # Foreground-service (Phone)
│   └── SyncServerService.kt           # Foreground-service (Reader)
├── storage/
│   ├── SettingsRepository.kt          # SharedPreferences
│   └── DirectoryAccessManager.kt      # SAF-каталоги
├── sync/
│   ├── SyncRepository.kt              # Логика синхронизации
│   ├── SyncPlanner.kt                 # Планирование
│   └── DocumentTreeWalker.kt          # Обход каталогов
├── transport/
│   ├── SyncTransport.kt               # Интерфейс транспорта
│   ├── WifiLanTransport.kt            # TCP
│   ├── BluetoothTransport.kt          # RFCOMM
│   ├── BluetoothPairingHelper.kt      # Handshake + pairing
│   ├── WifiHelper.kt                  # IP-адрес, проверка Wi-Fi
│   └── WifiDirectTransport.kt         # Wi-Fi Direct (заглушка)
└── ui/
    ├── MainViewModel.kt
    └── SyncViewModel.kt
```

----------

## Установка

### Требования

-   **Android Studio** Ladybug (2024.2.1) или новее
    
-   **JDK 17**
    
-   **Android SDK 35** (compileSdk), **minSdk 26**
    
-   Два Android-устройства (телефон и читалка) с **Android 8.0+**
    

### Сборка

bash

git clone https://github.com/<ваш-username>/alreaderx-sync.git
cd alreaderx-sync
./gradlew :app:assembleDebug

APK появится в `app/build/outputs/apk/debug/app-debug.apk`.

### Установка на устройства

bash

adb install -r app/build/outputs/apk/debug/app-debug.apk

Установите на **оба** устройства.

----------

## Настройка

### Первый запуск

1.  При запуске приложение попросит **выбрать роль**: «Телефон» или «Ридер».
    
2.  Откройте **Настройки** (шестерёнка в правом верхнем углу).
    
3.  Выберите каталоги:
    
    -   **AlReaderX** — основной каталог (SAF).
        
    -   **Books** — каталог книг (если книги лежат отдельно).
        
    -   **Книги внутри AlReaderX** — отметьте, если Books вложен в AlReaderX.
        
4.  Выберите режим транспорта:
    
    -   **Автоматически** — Wi-Fi LAN → Bluetooth fallback.
        
    -   **Только Bluetooth** — сразу Bluetooth.
        
5.  При необходимости настройте retry/timeout для каждого транспорта.
    

### Сопряжение

1.  На **Ридере** нажмите **Start Server** (запустится foreground service с уведомлением).
    
2.  На **Телефоне** нажмите **Сменить устройство**, выберите Ридер из списка (или сопрягите).
    
3.  Телефон запомнит MAC-адрес Ридера.
    

### Синхронизация

1.  Убедитесь, что сервер на Ридере запущен.
    
2.  На **Телефоне** выберите режим и направление.
    
3.  Нажмите **Синхронизировать**.
    
4.  Следите за прогрессом в уведомлении и на главном экране.
    
5.  При необходимости нажмите **Отменить** (в уведомлении или на экране).
    

----------

## Разрешения

### Bluetooth

-   `BLUETOOTH`, `BLUETOOTH_ADMIN` (Android ≤ 11)
    
-   `BLUETOOTH_SCAN`, `BLUETOOTH_CONNECT` (Android 12+)
    

### Wi-Fi

-   `ACCESS_WIFI_STATE`, `CHANGE_WIFI_STATE`, `ACCESS_NETWORK_STATE`
    

### Foreground service

-   `FOREGROUND_SERVICE`, `FOREGROUND_SERVICE_DATA_SYNC`
    
-   `POST_NOTIFICATIONS` (Android 13+)
    

### Хранилище

-   `READ_EXTERNAL_STORAGE` (Android ≤ 12), SAF через `DocumentFile` API.
    

### WakeLock

-   `WAKE_LOCK` — удерживается во время синхронизации.
    

----------

## Особенности реализации

### Гарантия доставки последнего файла (Ping/Pong)

После отправки `Complete` источник отправляет сообщение `Ping`. Приёмник отвечает `Pong`. Только после получения `Pong` источник закрывает сокет. Это гарантирует, что все байты из Bluetooth-буфера доставлены до `close()`.

**Зачем:** Bluetooth-стек Android не гарантирует, что `close()` дождётся опустошения буфера. Без `Ping/Pong` последний файл может потеряться.

### Проверка loopback

При handshake Ридер отправляет `readerIp` только если это **реальный** IP-адрес (не `127.0.0.1` и не пустая строка). Если Wi-Fi отключён, `readerIp = ""`, и Телефон сразу переходит на Bluetooth, не тратя время на бесполезную попытку Wi-Fi LAN.

### Задержка 500 мс перед Bluetooth

После handshake Ридеру нужно время, чтобы открыть `BluetoothServerSocket`. Телефон ждёт 500 мс перед Bluetooth-подключением, чтобы не опередить Ридер и не получить `bt socket closed`.

### Retry и таймауты

-   **Wi-Fi LAN:** по умолчанию 3 попытки × 1 сек.
    
-   **Bluetooth:** по умолчанию 3 попытки × 1 сек.
    
-   Настраиваются в Settings → Ретраи и таймауты.
    

### Кириллица в путях

`SyncProtocol.readLineFromStream` читает байты в `ByteArrayOutputStream` и декодирует **один раз** через UTF-8. Это критично для кириллических путей: побайтовое `b.toChar()` разрывает многобайтовые последовательности UTF-8.

### Edge-to-edge

На Android 15+ контент рисуется под системными панелями. `WindowInsets` обрабатываются программно: `WindowCompat.setDecorFitsSystemWindows(window, false)` + `ViewCompat.setOnApplyWindowInsetsListener` на корневом `LinearLayout`.

----------

## Известные ограничения

-   **Wi-Fi Direct** не реализован (заглушка `WifiDirectTransport`).
    
-   **Bluetooth — медленный** (~100–300 КБ/с). Для больших библиотек предпочтителен Wi-Fi LAN.
    
-   **Файлы > 100 МБ** по Bluetooth могут передаваться несколько минут.
    
-   **Отмена на стороне Ридера** возможна только через остановку сервера (кнопка Stop Server).
    
-   **Формат `DocumentFile.length()`** может не совпадать с реальным размером файла на некоторых провайдерах. В этом случае синхронизация сообщит об ошибке.
    

----------

## Разработка

### Стек

-   **Kotlin** — 100% кода.
    
-   **Coroutines + Flow** — асинхронность.
    
-   **ViewModel (AndroidX)** — MVVM.
    
-   **AppCompat + Material Components** — UI.
    
-   **Kotlinx Serialization** — JSON-протокол.
    
-   **SAF (Storage Access Framework)** — доступ к каталогам.
    

### Зависимости

kotlin

implementation("androidx.appcompat:appcompat:1.6.1")
implementation("androidx.core:core-ktx:1.12.0")
implementation("com.google.android.material:material:1.11.0")
implementation("androidx.activity:activity-ktx:1.8.0")
implementation("androidx.lifecycle:lifecycle-viewmodel-ktx:2.7.0")
implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.7.3")
implementation("org.jetbrains.kotlinx:kotlinx-serialization-json:1.6.0")

### Сборка release

bash

./gradlew :app:assembleRelease

Требуется настроить signing config в `app/build.gradle.kts`.

----------

## Лицензия

MIT License

----------

## Благодарности

-   [AlReaderX](https://play.google.com/store/apps/details?id=com.neverland.alreaderext) — читалка, для которой создан этот проект.
    
-   Всем, кто тестировал и находил баги.
    

----------

## Контрибьютинг

Pull-request'ы приветствуются. Для крупных изменений — сначала откройте issue.

Перед PR:

bash

./gradlew :app:lintDebug
./gradlew :app:testDebugUnitTest
