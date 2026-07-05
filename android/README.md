# HDRezka Пульт — Android-приложение

Клиент-«пульт» к плагину HDRezka на приставке (Enigma2). Телефон сам парсит
HDRezka (каталог, поиск, озвучки, сезоны, серии, ссылку на поток) и отправляет
**готовый URL** на приставку через её HTTP-агент. Дальше телефон работает как
пульт: пауза, перемотка ±15 c, громкость, без звука, стоп, смена серии/озвучки.

Каталог — адаптивная сетка постеров с бесконечной подгрузкой (число колонок
подстраивается под ширину экрана), «Продолжить просмотр» помнит озвучку/серию и
позицию и досылает перемотку, как только приставка начнёт играть.

Протокол общения с приставкой описан в [`../AGENT_API.md`](../AGENT_API.md).
Серверную часть (агент) реализует `agent.py` в корне репозитория.

## Стек

- **Kotlin + Jetpack Compose** (Material 3)
- **OkHttp** — сеть
- **Jsoup** — парсинг HTML (прямой аналог BeautifulSoup из питон-плагина,
  поэтому логика `core/HdRezkaApi.kt` повторяет `HdRezkaApi/api.py`)
- **Coil** — постеры
- Discovery приставок — UDP-broadcast `HDREZKA_DISCOVER`

## Структура

```
app/src/main/java/com/hdrezka/pult/
  core/      HdRezkaApi, HdRezkaSearch, Net, Mirrors, Models — парсер и сеть
  agent/     AgentClient, Discovery, AgentModels — мост к приставке
  data/      Prefs (настройки), History (продолжить просмотр)
  ui/        MainActivity, AppViewModel, Screen, Theme, Screens (Compose)
```

## Как собрать

Нужен **Android Studio** (Hedgehog/Iguana или новее) — он сам подтянет Android
SDK, Gradle и сгенерирует Gradle wrapper.

1. `File → Open` → выбрать папку **`android/`** (именно её, не корень репо).
2. Дождаться Gradle Sync (скачает зависимости).
3. Подключить телефон (USB-отладка) или запустить эмулятор.
4. `Run ▶`.

Сборка из терминала (если wrapper уже сгенерирован Android Studio или командой
`gradle wrapper` в папке `android/`):

```bash
cd android
./gradlew assembleDebug        # APK в app/build/outputs/apk/debug/
./gradlew installDebug         # собрать и поставить на подключённое устройство
```

## Что настроить

- **minSdk 24** (Android 7.0), **compileSdk/targetSdk 34** — при желании поменять
  в `app/build.gradle.kts`.
- `applicationId` — сейчас `com.hdrezka.pult`, поменяй при публикации.
- Приложение ходит в LAN по **HTTP** (агент приставки) — для этого включён
  `android:usesCleartextTraffic="true"` в манифесте.
- Первое, что делает пользователь: на главном экране «Выбрать приставку» →
  автопоиск по сети (UDP) или ручной ввод IP:порт. Порт по умолчанию **8123**
  (совпадает с настройкой агента на приставке).

## Известные ограничения / TODO

- Авторизация в аккаунте HDRezka не реализована (премиум-озвучки и контент,
  требующий логина, будут недоступны) — потребует отдельного экрана логина и
  проброса cookie в `core/Net.kt`.
- Токен-авторизация агента (см. раздел «Безопасность v2» в `AGENT_API.md`) пока
  не реализована ни на телефоне, ни на приставке.
