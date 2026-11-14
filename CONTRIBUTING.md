# Как предложить изменения · Contributing

[Русский](#русский) · [English](#english)

## Русский

В отчёте об ошибке укажите модель устройства, версию прошивки, версию лаунчера и номер сборки CPI. Опишите, как воспроизвести сбой и какой результат ожидали. Если прикладываете диагностику, проверьте её на личные данные. [Сообщить об ошибке](https://github.com/Kostubet/CPI-Kostubet-Edition/issues/new/choose)

Возьмите за основу `dev` и направляйте туда pull requests с изменениями. `beta` используется для тестовых сборок, `main` — для релизов. Для проверки локальной сборки понадобятся JDK 17 и Android SDK API 35. На Windows замените `./gradlew` на `.\gradlew.bat`. [Архитектура модуля](ARCHITECTURE.md)

~~~sh
./gradlew :app:assembleRelease :app:lintRelease :app:testReleaseUnitTest
~~~

В pull request опишите, что изменилось и как вы это проверили. Для изменений лаунчера или System UI укажите прошивки и устройства, на которых проводили проверку.

## English

Include the device model, firmware version, launcher version, and CPI build number in bug reports. Describe the steps to reproduce the issue and the expected result. Check diagnostic files for personal information before attaching them. [Report a bug](https://github.com/Kostubet/CPI-Kostubet-Edition/issues/new/choose)

Base changes on `dev` and target it for contribution pull requests. Use `beta` for test builds and `main` for releases. Local builds need JDK 17 and Android SDK API 35. Use `.\gradlew.bat` on Windows. [Module architecture](ARCHITECTURE.md)

In your pull request, describe what changed and how you verified it. For launcher or System UI changes, include the devices and firmware versions you tested on.
