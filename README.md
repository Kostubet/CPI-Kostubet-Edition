# CPI Kostubet Edition

**Frosted и Liquid Glass для ColorOS** · модуль LSPosed на Kotlin

Зимнее оформление добавляет снег на страницы приложения и праздничную мелодию с мягкой вибрацией при зажатии баннера CPI. Оба эффекта отключаются отдельно в **О модуле → Настройки**. Прежние звук и вибрация сохранены и возвращаются при отключении праздничной мелодии.

[![Kostubet Topics in Telegram](https://img.shields.io/badge/Telegram-Kostubet%20Topics-229ED9?style=for-the-badge&logo=telegram&logoColor=white)](https://t.me/KostubetTopics)

[Русский](#русский) · [English](#english) · [Releases](https://github.com/Kostubet/CPI-Kostubet-Edition/releases) · [Actions](https://github.com/Kostubet/CPI-Kostubet-Edition/actions)

[Изменения в 0.3.0 / What's new in 0.3.0](CHANGELOG.md)

## Русский

Модуль LSPosed на Kotlin, который добавляет Frosted и Liquid Glass для иконок лаунчера. Красивый фон иконок с гибкой настройкой. Форк [CherryPick Icons](https://github.com/immorgue/CherryPick-Icons).

### Возможности

- Разделы «Функций» сгруппированы в System Launcher, System UI и общие настройки. Поиск по русским и английским названиям показывает найденную настройку под названием раздела.
- В «О модуле → Настройки → Оформление COUI» можно переключаться между оригинальным COUI и COUI 2.0 независимо от светлой или тёмной темы.
- Стекло для иконок, папок, виджетов, докбара и индикатора страниц. Для разных элементов доступны свои настройки.
- Стекло для баннеров уведомлений, развёрнутых «Живых оповещений», панели громкости и элементов экрана блокировки.
- Независимое стекло центра управления и центра уведомлений ColorOS 17 с живым фоном, настройками отдельных компонентов и предпросмотром.
- Размытие и его прозрачность под выбором материала, опциональная оптика AndroidLiquidGlass, окрашивание под обои, краевое освещение и контуры. Живой фон обновляется с частотой дисплея до 120 к/с.
- Эффект «джина» при открытии и закрытии приложений. Деформация окна следует за системным переходом ColorOS.
- Предпросмотр стекла, сохранение именованных настроек и экспорт диагностики. Конфигом можно поделиться в JSON и посмотреть его оформление на обоях и системных элементах устройства получателя перед применением. Интерфейс на русском и английском.
- Для предпросмотра виджетов и папок подходят системные образцы, даже если элемента нет на рабочем столе. Кадры живых обоев обновляются в открытом предпросмотре, когда прошивка предоставляет их модулю.
- Вкладка «Камера» открывает экспериментальный iOS Smooth Video Zoom для задней камеры в режиме записи. Для сборки OnePlus 13 IN `CPH2649_17.0.0.102(EX01)` проверяется версия `com.oplus.camera` 7.006.144 и сигнатуры контроллера.

### Установка и совместимость

В разделе «Анимации приложений iOS 27» можно отдельно включить движок ba31k. Пока он включён, действуют его параметры. После отключения возвращается прежний движок, если он был включён. Анимации шторки требуют области лаунчера, а прозрачный фон шторки — области System UI. Движок оставляет штатный переход на прошивках, где системный метод не удаётся подтвердить.

1. Скачайте подписанный APK из [Releases](https://github.com/Kostubet/CPI-Kostubet-Edition/releases).
2. Установите его и включите **CPI Kostubet Edition** в LSPosed. Совместимость отдельных функций с Vector ограничена.
3. Выберите системный лаунчер в области действия модуля. Для эффектов уведомлений, громкости и экрана блокировки выберите также **System UI**.
4. Для оформления иконок выберите **Flux** в настройках ColorOS. После изменения области действия перезапустите соответствующий процесс.

Основная конфигурация разработки — OnePlus 13, ColorOS 17 и системный лаунчер 17.3.12. Доступность отдельных эффектов зависит от версии прошивки и лаунчера. [Подробнее об установке](INSTALL.md#русский)

Для плавного зума выберите штатную «Камеру» в области CPI в LSPosed и запустите её заново. В CPI откройте «Камера → iOS Smooth Video Zoom», включите сглаживание и выберите нужные виды управления. График реагирует на ручные настройки и меняет шкалу времени под выбранную траекторию. Запишите короткое приближение и отдаление внутри диапазона одного объектива на 1080p и 4K при 30 и 60 FPS. Сравните сохранённые ролики с выключенной функцией. Для проверки быстрого разворота смените направление зума в середине движения. Переходы между объективами выполняет штатная камера. Числа в диагностике доступны при включённом сборе метрик, если камера возвращает коэффициент в метаданных кадров.

В «Функции → Стекло центра управления и уведомлений» выберите панель, включите стекло и настройте материал. Оба эффекта по умолчанию выключены и требуют области **System UI**. У каждого поддерживаемого элемента можно переопределить радиус размытия и непрозрачность либо вернуть системное оформление.

Фон центра управления использует системную анимацию ColorOS. У него свои настройки радиуса размытия, яркости и затемнения.

У стекла «Мягкий свет» независимые профили для центра управления, центра уведомлений, уведомлений и развёрнутых активностей на экране блокировки и умной боковой панели. Для первых трёх выберите область **System UI**, для боковой панели — **Умная боковая панель** (`com.coloros.smartsidebar`). После первого включения перезапустите соответствующее системное приложение. Кнопка перезапуска боковой панели находится вверху её раздела. Для плиток и уведомлений в шторке «Мягкий свет» — единственный стиль. В остальных разделах его можно выбрать в меню «Тип стекла» и настроить ширину зоны преломления отдельно.

Откройте **О модуле → Обновления**, чтобы проверить опубликованные сборки, включить уведомления и установить подписанный APK прямо из CPI. Android может запросить разрешение на установку или показать окно подтверждения.

В каждом разделе стекла настройки «Размытие» и «Прозрачность размытия» находятся сразу после выбора материала. Прозрачность определяет, сколько исходного фона видно через размытый слой. У нового движка остаются настройки оптики. Живой фон обновляется автоматически с целью до 120 к/с. Фактическая частота зависит от дисплея и того, как быстро система отдаёт новые кадры.

Настройки материала сгруппированы по размытию, качеству и частоте обновления, а также оптике. Уровень размытия нового движка регулируется до 200%. Яркость фона можно менять отдельно от размытия и непрозрачности стекла.

### Сборка и публикация

Понадобятся JDK 21 и Android SDK API 37. Укажите SDK через `ANDROID_HOME` или `local.properties`. Интерфейс настроек использует официальные библиотеки COUI 2.0.0 из Maven Central. [Исходники COUI](https://github.com/suqi8/coui/tree/v2.0.0)

~~~sh
./gradlew :app:assembleRelease :app:lintRelease :app:testDebugUnitTest
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

Winter decoration adds snowfall throughout the app and a festive melody with gentle haptics when holding the CPI banner. Both effects have separate switches in **About → Settings**. Disabling the festive melody restores the original audio and vibration.

An LSPosed module written in Kotlin that brings Frosted and Liquid Glass design to launcher icons. A beautiful icon backdrop engine with nice customisation. A fork of [CherryPick Icons](https://github.com/immorgue/CherryPick-Icons).

- Frosted and Liquid Glass for icons, folders, widgets, the dock and page indicators.
- In About → Settings → COUI appearance, choose Original COUI or COUI 2.0 independently of light or dark mode.
- Glass settings for notification banners, expanded Live Alerts, the volume panel and lock screen elements.
- Independent glass controls and live material previews for the original ColorOS 17 Control and Notification Centers.
- Each glass section puts Blur and Blur transparency directly below the material picker. AndroidLiquidGlass optics are optional. Moving backdrops target up to 120 FPS, subject to the display and capture performance.
- Live wallpaper blur, refraction, wallpaper tint, edge highlights and outlines.
- Genie-style window deformation during app launch and return, driven by the stock ColorOS transition.
- Material previews, named settings profiles and diagnostic export. Share settings as JSON and preview them with the recipient’s wallpaper and system elements before applying.
- Widget and folder previews can use system samples without adding anything to the home screen. An open preview refreshes live wallpaper frames when the firmware makes them available.
- The Camera tab contains experimental iOS Smooth Video Zoom for rear-camera recording. Compatibility checks cover stock Camera 7.006.144 on OnePlus 13 IN `CPH2649_17.0.0.102(EX01)`.

### Installation

The iOS 27 App Animations page has a separate ba31k engine with independent settings. Disabling it returns to the previously enabled engine. The shade motion uses launcher scope, and the transparent shade uses System UI scope. Firmware with an unrecognized native transition retains the stock behavior.

Install the signed APK from [Releases](https://github.com/Kostubet/CPI-Kostubet-Edition/releases). Enable the module in LSPosed and select the system launcher. Some features have limited compatibility with Vector. Select **System UI** for system overlays and the lock screen. Enable the stock **Flux** icon style for icon styling. The main development device is the OnePlus 13 on ColorOS 17 with launcher 17.3.12. Feature availability depends on the firmware. [Full installation guide](INSTALL.md#english)

To test smooth zoom, add stock Camera to the LSPosed scope and reopen it. In CPI, open **Camera → iOS Smooth Video Zoom**, turn it on, and select the controls to smooth. The graph updates with manual adjustments and scales its time axis to the trajectory. Compare saved recordings at 1080p and 4K, at 30 and 60 FPS, while zooming within one lens range and changing direction mid-zoom. Lens switches use the stock camera path.

Soft-light glass has independent profiles for control center, notification shade, lock-screen notifications and expanded live activities, and Smart Sidebar. Select **System UI** in LSPosed for the first three, or **Smart Sidebar** (`com.coloros.smartsidebar`) for the sidebar. Restart the affected system app after enabling the effect for the first time. The Smart Sidebar settings have a restart button. Soft Light is the only card material for Control Center and Notification Center. In other glass sections, choose it under Glass type to adjust the refraction width.

Open **About → Updates** to check releases, receive update notifications, and install a signed build from CPI. Android may ask for installation permission or confirmation.

### Build and release

The settings interface uses the official COUI 2.0.0 Android libraries from Maven Central. [COUI source](https://github.com/suqi8/coui/tree/v2.0.0)

Use JDK 21 and Android SDK API 37. Run `./gradlew :app:assembleRelease :app:lintRelease :app:testDebugUnitTest` or use `gradlew.bat` on Windows. [GitHub Actions](https://github.com/Kostubet/CPI-Kostubet-Edition/actions) runs these checks and uploads a test APK. A tag of the form `v<versionName>-build<versionCode>` publishes a signed prerelease and SHA-256 checksum after the checks pass.

[Contribute](CONTRIBUTING.md#english) · [Report a bug](https://github.com/Kostubet/CPI-Kostubet-Edition/issues/new/choose) · [Telegram forum](https://t.me/KostubetTopics) · [GPL v3](COPYING) · [Credits](THIRD_PARTY_NOTICES.md)
