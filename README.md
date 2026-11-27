# Cast Craft — AirPlay-приёмник для Google TV / Android TV

Приложение превращает телевизор с Google TV / Android TV в приёмник AirPlay:
iPhone, iPad или Mac показывают экран на ТВ через стандартный
**Пункт управления → Повтор экрана**, без приложений на iPhone.

## Как пользоваться

1. Установите APK на телевизор (см. ниже).
2. Откройте **Cast Craft** на ТВ. На экране появится имя приёмника.
3. Телевизор и iPhone должны быть в одной сети Wi‑Fi / LAN.
4. На iPhone: Пункт управления → Повтор экрана → выберите имя телевизора.

Приёмник работает, пока приложение открыто на экране.

## Установка APK

APK собирается в GitHub Actions: вкладка **Actions → Android build →** последний
запуск → артефакт **CastCraft-apk**. Его можно поставить так:

```sh
adb connect <IP телевизора>
adb install -r app-release.apk
```

или через «Downloader» / любой файловый менеджер на ТВ (включите установку
из неизвестных источников).

## Что поддерживается

- Повтор экрана (H.264, до 1080p, 60 к/с), поворот экрана iPhone.
- Звук при повторе экрана (AAC‑ELD), громкость с iPhone.
- Звук из музыкальных приложений (AAC; ALAC — если в ТВ есть декодер).
- Без пароля и PIN: подключиться может любое устройство в сети.

Пока не поддерживается: HEVC/4K, передача видео по ссылке (HLS, «AirPlay»
из YouTube и т. п.), фоновая работа, когда приложение свёрнуто.

## Как устроено

| Часть | Где | Что делает |
|---|---|---|
| Протокол AirPlay (RTSP, pairing, FairPlay, расшифровка RTP) | `app/src/main/cpp/third_party/uxplay/lib` | библиотека из [UxPlay](https://github.com/FDH2/UxPlay) без изменений |
| mDNS / Bonjour | `dnssd_android.c` + `AirPlayServer.java` | те же TXT-записи, что у UxPlay, регистрация через `NsdManager` |
| JNI | `jni_bridge.c` | запуск сервера, передача кадров и звука в Java |
| Видео | `VideoDecoder.java` | H.264 → `MediaCodec` → `SurfaceView`, минимальная задержка |
| Звук | `AudioPlayer.java` | AAC‑ELD/AAC/ALAC → `MediaCodec` → `AudioTrack` |

Зависимости собираются из исходников: [libplist](https://github.com/libimobiledevice/libplist)
2.6.0 (в репозитории) и OpenSSL 3.0 (скачивается и собирается
`scripts/build-openssl.sh`).

## Сборка локально

Нужны Android SDK, NDK 27.2.12479018, JDK 17, `perl` и `make` (Linux/macOS).

```sh
export ANDROID_NDK_ROOT=$ANDROID_HOME/ndk/27.2.12479018
scripts/build-openssl.sh      # один раз; Gradle запускает его сам, если нужно
./gradlew assembleRelease
```

## Лицензия

GPL‑3.0 (см. `LICENSE`), так как используется код UxPlay (GPL‑3.0).
Лицензии сторонних частей лежат рядом с ними: UxPlay — `third_party/uxplay/LICENSE`
(части lib — LGPL‑2.1, playfair — GPL‑3.0, llhttp — MIT), libplist — LGPL‑2.1
(`third_party/libplist/COPYING`), OpenSSL — Apache‑2.0.
AirPlay — товарный знак Apple Inc.; проект с Apple не связан.
