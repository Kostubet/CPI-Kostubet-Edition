# CPI Kostubet Edition

**Frosted и Liquid Glass для ColorOS** · модуль LSPosed на Kotlin

[![Kostubet Topics in Telegram](https://img.shields.io/badge/Telegram-Kostubet%20Topics-229ED9?style=for-the-badge&logo=telegram&logoColor=white)](https://t.me/KostubetTopics)

[Русский](#русский) · [English](#english) · [Releases](https://github.com/Kostubet/CPI-Kostubet-Edition/releases) · [Actions](https://github.com/Kostubet/CPI-Kostubet-Edition/actions)

## Русский

Модуль LSPosed на Kotlin, который добавляет Frosted и Liquid Glass для иконок лаунчера. Красивый фон иконок с гибкой настройкой. Форк [CherryPick Icons](https://github.com/immorgue/CherryPick-Icons).

### Возможности

- Стекло для иконок, папок, виджетов, докбара и индикатора страниц. Для разных элементов доступны свои настройки.
- Стекло для баннеров уведомлений, развёрнутых «Живых оповещений», панели громкости и элементов экрана блокировки.
- Живое и статичное размытие, преломление, окрашивание под обои, краевое освещение и контуры.
- Наклон при открытии и закрытии приложений на штатной шкале анимации ColorOS.
- Предпросмотр стекла, сохранение именованных настроек и экспорт диагностики. Интерфейс на русском и английском.

### Установка и совместимость

1. Скачайте подписанный APK из [Releases](https://github.com/Kostubet/CPI-Kostubet-Edition/releases).
2. Установите его и включите **CPI Kostubet Edition** в LSPosed или Vector.
3. Выберите системный лаунчер в области действия модуля. Для эффектов уведомлений, громкости и экрана блокировки выберите также **System UI**.
4. Для оформления иконок выберите **Flux** в настройках ColorOS. После изменения области действия перезапустите соответствующий процесс.

Основная конфигурация разработки — OnePlus 13, ColorOS 17 и системный лаунчер 17.3.12. Доступность отдельных эффектов зависит от версии прошивки и лаунчера. [Подробнее об установке](INSTALL.md#русский)

### Сборка и публикация

Понадобятся JDK 17 и Android SDK API 35. Укажите SDK через `ANDROID_HOME` или `local.properties`.

~~~sh
./gradlew :app:assembleRelease :app:lintRelease :app:testReleaseUnitTest
~~~

На Windows используйте `gradlew.bat`. Gradle записывает APK в `app/build/outputs/apk/release/app-release.apk`.

GitHub Actions запускает сборку, lint и unit-тесты при изменениях `main` и в pull request. В отчёте запуска доступны APK `cpi-preview` и результаты проверок `android-checks`. APK из CI подписан тестовым ключом для пробной установки. Подписанные обновления находятся в Releases.

Для новой публикации обновите `versionName` и `versionCode`, отправьте коммит и поставьте соответствующий тег. После проверок Actions подпишет APK и опубликует предварительный релиз с файлом SHA-256.

~~~sh
git tag v0.2.78-build328
git push origin v0.2.78-build328
~~~

Для подписи используются секреты GitHub Actions `CPI_SIGNING_KEYSTORE`, `CPI_SIGNING_STORE_PASSWORD` и `CPI_SIGNING_KEY_PASSWORD`. Переменные `CPI_SIGNING_KEY_ALIAS` и `CPI_SIGNING_CERT_SHA256` задают псевдоним и отпечаток сертификата. Для обновления установленного CPI нужен прежний сертификат.

### Исходники и поддержка

- [Архитектура рендера](ARCHITECTURE.md) и [настройки материалов](CUSTOM_GLASS.md).
- [Как предложить изменения](CONTRIBUTING.md#русский).
- [Сообщить об ошибке](https://github.com/Kostubet/CPI-Kostubet-Edition/issues/new/choose) или обсудить её в [Kostubet Topics](https://t.me/KostubetTopics).
- [Лицензия GPL v3](COPYING), [благодарности и сторонний код](THIRD_PARTY_NOTICES.md), [поддержать разработку](https://pay.cloudtips.ru/p/e6230fb4).

## English

An LSPosed module written in Kotlin that brings Frosted and Liquid Glass design to launcher icons. A beautiful icon backdrop engine with nice customisation. A fork of [CherryPick Icons](https://github.com/immorgue/CherryPick-Icons).

- Frosted and Liquid Glass for icons, folders, widgets, the dock and page indicators.
- Glass settings for notification banners, expanded Live Alerts, the volume panel and lock screen elements.
- Live wallpaper blur, refraction, wallpaper tint, edge highlights and outlines.
- App launch and return tilt driven by the stock ColorOS transition.
- Material previews, named settings profiles and diagnostic export.

### Installation

Install the signed APK from [Releases](https://github.com/Kostubet/CPI-Kostubet-Edition/releases). Enable the module in LSPosed or Vector and select the system launcher. Select **System UI** for system overlays and the lock screen. Enable the stock **Flux** icon style for icon styling. The main development device is the OnePlus 13 on ColorOS 17 with launcher 17.3.12. Feature availability depends on the firmware. [Full installation guide](INSTALL.md#english)

### Build and release

Use JDK 17 and Android SDK API 35. Run `./gradlew :app:assembleRelease :app:lintRelease :app:testReleaseUnitTest` or use `gradlew.bat` on Windows. [GitHub Actions](https://github.com/Kostubet/CPI-Kostubet-Edition/actions) runs these checks and uploads a test APK. A tag of the form `v<versionName>-build<versionCode>` publishes a signed prerelease and SHA-256 checksum after the checks pass.

[Contribute](CONTRIBUTING.md#english) · [Report a bug](https://github.com/Kostubet/CPI-Kostubet-Edition/issues/new/choose) · [Telegram forum](https://t.me/KostubetTopics) · [GPL v3](COPYING) · [Credits](THIRD_PARTY_NOTICES.md)
