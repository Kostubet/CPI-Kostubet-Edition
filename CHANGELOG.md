# CPI Kostubet Edition 0.3.0

[Русский](#русский) · [English](#english)

## Русский

### Сборка 424

- Добавлен снег во всём интерфейсе приложения. Снежинки оседают на краях карточек, поиска, кнопок и панели вкладок, образуют небольшие сугробы, сползают и плавно тают.
- Зажатие главного баннера на 0,5 секунды воспроизводит приложенную праздничную мелодию. Нежные аппаратные постукивания разной силы следуют атакам нот; последняя нота завершается плавным слайдом.
- В настройках приложения появились отдельные выключатели снега и праздничной мелодии. Исходные звук, фейерверки и вибрация сохранены; выключение мелодии возвращает прежний звук и вибрацию.
- Снег не перехватывает нажатия, останавливается в фоне и снижает частоту в режиме энергосбережения. Воспроизведение и вибрация отменяются при уходе со страницы баннера.

### Сборка 423

- Ползунки яркости и громкости получают «Мягкий свет» и в развёрнутом режиме. Штатное заполнение ползунков сохраняется.
- Боковая панель использует стекло в своих отдельных процессах. В её настройках добавлена кнопка перезапуска.
- Центры управления и уведомлений используют только «Мягкий свет». Настройки общего фона центра управления сохранены.
- В остальных разделах «Мягкий свет» появился в меню «Тип стекла» с отдельной шириной зоны преломления.

### Сборка 422

- «Мягкий свет» использует системное стекло ColorOS 17. Профили центра управления, центра уведомлений, уведомлений и активностей на экране блокировки и «Умной боковой панели» настраиваются отдельно.
- Приложенный профиль стал основой заводских настроек. При обновлении сохраняются уже записанные значения.
- В «О модуле» появились проверка релизов GitHub, уведомления и установка подписанного APK внутри приложения.

### Сборка 421

- Нижняя панель инструментов Haptic Studio размывает прокручиваемое содержимое за вкладками на поддерживаемых устройствах.

### Сборка 420

- В редакторе Haptic Studio появились отдельные дорожки звука и вибрации, кнопки отмены и повтора рядом с воспроизведением и нижняя панель инструментов.
- Изменения огибающей и перенос точки в паузу сохраняют исходный тактильный канал рингтона. Тихие участки используют образец исходного сигнала, а усиление получает ограничение пиков с плавным переходом.

### Сборка 419

- Предпросмотр штатного рингтона воспроизводит исходный тактильный канал через системное аудио. Изменения интенсивности, затухания и сдвига применяются к исходному сигналу.

### Сборка 418

- Haptic Studio импортирует встроенную дорожку вибрации рингтона как редактируемую огибающую. Можно выбрать рингтон или взять текущий.
- В редакторе можно перетаскивать точки, вставлять щелчки и слайды с курсора или настучать ритм. Предпросмотр использует системные щелчки на поддерживаемых моторах.
- Автосинхронизация выбирает реальные атаки звука, исключает тактильный канал из звукового анализа и сохраняет короткие акценты при воспроизведении.

### Сборка 417

- В настройках можно выбрать оригинальное оформление COUI или COUI 2.0 независимо от светлой и тёмной темы. «Общие», «Цвета» и «Стиль» в разделах функций сохраняют оформление COUI 2.0.

### Сборка 416

- Поиск и нижняя панель снова используют кромку, подсветку и упругое движение COUI. Заголовок «Функции» возвращается синхронно с поиском.
- Меню выбора использует системное размытие ColorOS при поддержке композитором, с цветом текущего экрана в запасном режиме. У карточек настроек тонкая обводка по форме COUI.
- Строки с предпросмотром пружинят при отпускании пальца.

### Сборка 415

- Иконки обновляют стекло после загрузки кадра обоев. Растянутые иконки сохраняют привязку стекла при перерисовке.
- Поиск и кнопка перезапуска стали контрастнее, стрелка «Назад» читается в обеих темах. В нижней панели три вкладки. Камера доступна в «Функциях», а метка Open-Beta снова отображается текстом.
- Меню выбора завершает анимацию COUI перед применением настройки.
- Видеозум быстрее набирает скорость и плавнее продолжает движение после пропущенного кадра. Переключение объективов ведёт штатная камера.

### Сборка 414

- Поиск и нижняя панель используют системное размытие ColorOS, когда композитор его предоставляет. Запасная заливка соответствует теме CPI.
- Сужение поиска пересчитывает только капсулу, а фоны карточек сохраняют форму COUI без отдельных композиций.
- Внутренние вкладки используют SegmentButton из COUI 2.0 с перетаскиванием бегунка.

### Сборка 413

- Интерфейс настроек CPI переведён на COUI 2.0. Обновлены панели навигации, поиск, карточки, переключатели, ползунки, меню выбора и диалоги, включая импорт, экспорт и «Ваши настройки».
- Раздел «О модуле», выбор приложений для предпросмотра и управление воспроизведением в Haptic Studio используют компоненты COUI 2.0.

### Сборка 412

- Сглаживание видеозума соблюдает максимальную скорость и мягче тормозит у цели. При остановке кадров управление возвращается штатной камере до следующего кадра. График обновляется при движении ползунков и показывает всю траекторию.
- Отключение раздела «Иконки» возвращает штатные подложки растянутых ярлыков. Когда папки наследуют настройки иконок, движок и его параметры совпадают на рабочем столе и в предпросмотре.
- Поиск показывает часы и виджеты экрана блокировки в их разделе.
- Импорт распознаёт старый идентификатор стекла Kyant в конфигах CherryPick и переносит его в настройки нового движка.
- Предпросмотры переиспользуют уже загруженные обои и пропускают вычисление размытия при выключенном стекле.

### Сборка 411

- Новая вкладка «Камера» с экспериментальным сглаживанием видеозума, пятью режимами, ручными параметрами, расчётным графиком и диагностикой. Контракт привязан к штатной камере OnePlus 13 IN 7.006.144. Функция по умолчанию выключена.

### Сборка 406

- Строка функции разворачивается в карточку предпросмотра и плавно возвращается на место. Скорость свайпа влияет на длину отскока.
- Размер обычных иконок рассчитывается от штатных размеров ColorOS. У растянутых ярлыков подложка и содержимое масштабируются вместе, а у закрытых папок масштаб учитывается при анимации перестановки.

### Сборка 405

- Стекло иконок в открытой папке получает фон панели папки, включая штатную подложку ColorOS.
- Раздел «Анимации iOS» использует один движок. Наклон и деформация имеют отдельные регулировки.
- Настройки стекла сгруппированы во вкладках COUI «Общие», «Цвета» и «Стиль» с общим предпросмотром. Группы настроек открываются по нажатию. В общих настройках иконок оставлен размер.
- Удалено затемнение под логотипами. Скругление линзы «Живых оповещений» влияет на оптику внутри системной формы карточки.

### Сборка 404

Сборка **404**. Изменения относительно последней публичной сборки **0.2.78 build 329**. APK обновляет установленный CPI с тем же сертификатом и сохраняет настройки.

### Стекло и рабочий стол

- **Жидкое стекло (новый движок)** доступно в выборе материала для иконок, обычных и больших папок, виджетов, докбара, индикатора страниц, баннеров, «Живых оповещений», панели громкости, экрана блокировки и обеих шторок. Оптика на базе AndroidLiquidGlass добавляет настройки силы преломления, ширины края, блика и высоты линзы.
- Оба движка используют общую подготовку размытия. Сразу под типом стекла доступны **Размытие** и **Прозрачность размытия**. Старые значения переносятся при обновлении. У поддерживаемых материалов можно менять яркость фона, а через синхронизацию можно настроить общие параметры стекла сразу для нескольких поверхностей.
- Иконки внутри открытой папки берут фон её панели. У обычных и растянутых папок общий профиль. Большие папки можно оформить отдельно. Сброс настроек папок восстанавливает насыщенность и прозрачность.
- Докбар поддерживает независимое стекло, регулировку ширины, симметричной высоты, скругления, насыщенности и затемнения. У индикатора страниц отдельное стекло и настройка постоянного отображения.
- У виджетов фон, текст и значки настраиваются раздельно. Цвет текста и значков можно связать с цветом фона, а подпись под виджетом сохраняет системный цвет. Для фотографий доступны высота и плавность нижнего перехода прозрачности. Стекло и тонировка возвращаются плавно при выходе из приложения, открытого из виджета. Виджеты часов на рабочем столе исключены из обработки.
- Для оформления доступны перекраска под обои, ручные цветовые настройки, длина и направление краевого света, отдельные параметры чёрного контура, его толщина и порядок наложения блика. Обновлены форму блика и сглаживание кромок.

### System UI и экран блокировки

- **Центр управления** и **центр уведомлений ColorOS 17** получили независимые настройки Liquid Glass и живые предпросмотры. Поддерживаются совместимые плитки, плеер, дорожки громкости и яркости, карточки уведомлений и их действия. Материалы отдельных компонентов могут наследоваться от панели или переопределяться. При отсутствии совместимого системного контракта остаётся штатное оформление.
- У фона центра управления отдельные настройки размытия, прозрачности, яркости и затемнения. При наличии штатного размытия используется цепочка рендера ColorOS. Пресеты материала позволяют быстро настроить каждую панель.
- У баннеров свои настройки стекла, тени, блика, подложки и скоростей появления и закрытия. Тестовый баннер запускается из верхней панели раздела. Стекло «Живых оповещений» применяется к развёрнутой карточке.
- У панели громкости отдельный раздел с настройками материала, яркости, высоты и блика. Панель и баннеры используют отдельные кадры фона, когда открыты одновременно.
- Обновлено стекло цифр часов, PIN-клавиатуры, виджетов, ярлыков, стопки уведомлений и капсулы их счётчика. Можно менять скругление уведомлений и контур часов. У переходов из полноэкранного AOD и на рабочий стол согласовано появление стекла. Размытие AOD применяется к обоям. Доступны настройки анимации разблокировки.

### Анимации приложений

- Новый движок iOS с эффектом **Genie** синхронизирует перспективную деформацию окна и плавающей иконки со штатным переходом. У открытия и закрытия свои параметры скорости. Доступны скругление «каплей», траектория, пружина и размытие рабочего стола на поддерживаемых прошивках.
- Прежний движок наклона остаётся отдельной функцией. Улучшено восстановление слоя иконки после быстрого возврата и освобождение графических буферов. Для части лаунчеров ColorOS 16 есть отдельный адаптер. При несовместимости сохраняется системный переход.

### Интерфейс и конфиги

- Интерфейс переведён на COUI и готовые компоненты ONextBox. Обновлены навигация, жест «Назад», верхнее системное размытие, переключатели, ползунки и диалоги. Предпросмотр раздела открывается зажатием и закрывается жестом с возвратом к строке. Есть светлая и тёмная темы, а также режим «Как в системе».
- Каталог группирует функции по System Launcher, System UI и общим настройкам. Поиск индексирует параметры активных страниц на русском и английском. У разделов есть независимые выключатели. Панель перезапуска и кнопка тестового уведомления доступны сверху.
- Предпросмотры иконок, папок, виджетов, дока, баннеров, «Живых оповещений» и громкости используют системные образцы, когда прошивка их предоставляет. Можно выбрать приложения для предпросмотра иконок и фотографию из галереи. Поддерживается обновление кадров живых обоев.
- «Ваши настройки» сохраняют именованные профили. В списке можно переключаться между ними, менять порядок и удалять выбранные профили. Экспорт и импорт JSON показывают название, описание, автора и предпросмотр на обоях получателя. Импорт учитывает старые режимы цвета и прежний диапазон размытия докбара.
- Экспорт диагностики собирает ZIP с журналами и доступными APK системного лаунчера и System UI. Обновлены сведения о модуле и состоянии его загрузки.

### Производительность и совместимость

- Живое стекло обновляется с частотой дисплея до **120 к/с**. Для экранов 165 Гц есть отдельный режим равномерной подачи кадров. При энергосбережении и перегреве тяжёлые запросы замедляются. При свайпе координаты фона следуют за кадром лаунчера.
- Шторка захватывает область вокруг видимых элементов. Докбар и индикатор страниц делят один обработанный фон при совпадающих настройках. После отключения эффектов освобождаются буферы. Добавлены резервное оформление при сбоях и пауза между повторными запросами фона.
- В локальном замере программного размытия кадра 720×1440 временные аллокации снизились с **12–18 МиБ до ~4 МиБ** при совпадении результата пиксель в пиксель. Это замер алгоритма на ПК.
- Основная конфигурация разработки — **OnePlus 13, ColorOS 17**. Набор системных эффектов зависит от версии System UI и лаунчера. Стекло обеих шторок рассчитано на ColorOS 17. Для его работы включите **System UI** в области LSPosed, а для стекла иконок выберите **Flux** в ColorOS. [Инструкция по установке](https://github.com/Kostubet/CPI-Kostubet-Edition/blob/main/INSTALL.md#русский)

## English

### Build 424

- Added snowfall across the app. Flakes settle on cards, search, buttons and the navigation bar, form small snow caps, slide down and fade away.
- Holding the home banner for 0.5 seconds plays the supplied festive melody. Gentle hardware taps follow note attacks at varying strengths; the final note ends with a smooth slide.
- Separate app settings control snowfall and the festive melody. The original audio, fireworks and vibration remain available; disabling the melody restores the original audio and haptics.
- Snow does not intercept touch, pauses in the background and reduces its frame rate in battery saver. Leaving the banner page stops audio and vibration.

### Build 423

- Soft Light follows control-center brightness and volume tracks in their expanded states, keeping the stock progress fills.
- Smart Sidebar applies its material in the separate sidebar processes and has a restart button.
- Soft Light is the only card material for Control Center and Notification Center. Independent control-center background adjustments remain.
- Other glass sections can select Soft Light under Glass type and adjust their own refraction width.

### Build 422

- Soft-light glass uses the ColorOS 17 system material. Control center, notification shade, lock-screen notifications and live activities, and Smart Sidebar have independent profiles.
- The supplied profile provides factory defaults. Already saved values survive the update.
- About now has GitHub release checks, update alerts, and in-app installation of signed APKs.

### Build 421

- The Haptic Studio toolbar blurs the scrolling content behind its tabs on supported devices.

### Build 420

- The Haptic Studio editor has separate audio and haptic tracks, undo and redo next to playback, and a bottom toolbar.
- Envelope edits and points moved into a silent region keep the ringtone's native haptic channel. Quiet regions reuse a sample of the original signal, and gain boosts have smooth peak limiting.

### Build 419

- Ringtone preview plays the original haptic channel through Android audio. Strength, fade and timing offset edits operate on the original signal.

### Build 418

- Haptic Studio imports a ringtone's embedded haptic track as an editable envelope. Pick a ringtone or use the current one.
- Drag timeline points, insert clicks and slides at the playhead, or tap out a rhythm. Preview uses system click effects when supported.
- Auto-sync follows audio attacks, excludes the haptic channel from audio analysis, and preserves short accents during playback.

### Build 417

- Choose Original COUI or COUI 2.0 in settings independently of light and dark mode. The General, Colors and Style tabs in feature pages keep the COUI 2.0 design.

### Build 416

- Search and bottom navigation use COUI edge lighting, touch highlights, and elastic motion again. The Features title returns in sync with search.
- Choice menus use ColorOS compositor blur when supported, with a page-matched fallback. Settings cards have a subtle outline that follows the COUI shape.
- Preview rows spring back on release.

### Build 415

- Icon glass refreshes when a wallpaper frame becomes available. Stretched icons keep their live backdrop binding across redraws.
- Search and restart controls have clearer contrast. The back arrow is readable in both themes, the bottom bar has three tabs, and Camera remains in Features. About shows the Open-Beta label in full.
- Choice menus complete their COUI transition before applying the selection.
- Video zoom responds sooner and resumes more smoothly after a skipped frame. Lens switches remain under the stock camera's control.

### Build 414

- The search field and bottom bar use ColorOS compositor blur where available, with CPI's theme colors as a fallback.
- Resizing the search field lays out just the capsule. Cards keep the COUI shape without separate background compositions.
- Section tabs use COUI 2.0 SegmentButton with a draggable selection indicator.

### Build 413

- Migrated the CPI settings interface to COUI 2.0. Updated navigation bars, search, cards, switches, sliders, selection menus and dialogs, including import, export and saved settings.
- The About screen, preview app picker and Haptic Studio playback controls use COUI 2.0 components.

### Build 412

- Video zoom smoothing respects the speed limit and decelerates more smoothly. If preview frames stall, zoom falls back to the stock camera until frames resume. The graph updates while sliders move and shows the entire trajectory.
- Disabling Icons restores the stock backgrounds of stretched shortcuts. Folders that inherit icon settings use the same engine and parameters on the home screen and in the preview.
- Search returns lock-screen clocks and widgets under Lock screen.
- Import maps the old Kyant material ID in CherryPick configs to the new engine settings.
- Previews reuse loaded wallpapers and skip blur processing when glass is off.

### Build 411

- Added a Camera tab with experimental video zoom smoothing, five modes, manual tuning, a simulated trajectory and diagnostics. The hook targets stock Camera 7.006.144 on OnePlus 13 IN. Off by default.

### Build 406

- Feature rows expand into their preview cards and smoothly return to the original position. Swipe velocity affects the overshoot distance.
- Regular icons size relative to the native ColorOS dimensions. Stretched shortcuts scale their backgrounds and contents together, and closed folders keep the selected scale during reorder animations.

### Build 405

- Glass icons in open folders sample the folder panel, including the stock ColorOS surface.
- iOS Animations uses one engine, with separate tilt and deformation controls.
- Glass settings use COUI General, Colors, and Style tabs with a shared preview. Setting groups start collapsed. Icon size is in General.
- Removed logo shading. The Live Alerts lens corner setting adjusts the optics inside the native card outline.

### Build 404

Build **404**. Changes since the latest public **0.2.78 build 329**. The APK updates an existing CPI installation signed with the same certificate and preserves its settings.

### Glass and home screen

- **Liquid Glass (New engine)** is available in the material picker for icons, folders, large folders, widgets, the dock, page indicator, heads-up banners, Live Alerts, volume, lock screen and both shades. AndroidLiquidGlass optics add refraction strength, edge width, highlights and lens height.
- Both engines share the prepared blur. **Blur** and **Blur transparency** are directly below the material picker, with migration for existing settings. Supported materials offer backdrop brightness and shared settings can be synchronized across surfaces.
- Icons in open folders sample the folder panel. Standard and stretched folders share a profile, while large folders can have their own. Folder resets restore saturation and transparency.
- The dock has independent glass with width, symmetric height, rounding, saturation and dimming controls. The page indicator also has its own glass and a keep-visible option.
- Widget background and element tinting are independent. Text and icons can follow the widget background, and launcher labels retain their stock color. Photo widgets include height and softness controls for their bottom fade. Widget glass and tint fade back in on app return. Home-screen clock widgets are excluded.
- Added wallpaper-based and manual tint controls, adjustable edge-light length and direction, and separate black outline settings with thickness and highlight layering. Refined highlight shape and edge antialiasing.

### System UI and lock screen

- **ColorOS 17 Control Center** and **Notification Center** have independent materials and live previews. Supported tiles, media controls, slider tracks, notification cards and actions can inherit or override panel styles. Unsupported surfaces retain stock styling.
- The Control Center background has independent blur, transparency, brightness and dimming. CPI uses the native ColorOS blur path where available. Material presets are available for both shades.
- Heads-up banners have independent glass, shadow, highlight, background and entry/exit speed controls, with a test button at the top of the page. Live Alerts use glass for their expanded cards.
- The volume panel has its own material, brightness, height and highlight controls. Banners and volume keep separate backdrop frames when open together.
- Updated glass for lock-screen clock digits, PIN buttons, widgets, shortcuts, notification stacks and the notification-count capsule. Added notification corner and clock outline controls. Glass follows wake and unlock transitions, and full-screen AOD blur applies to the wallpaper. Unlock animation controls are available separately.

### App animations

- The new iOS engine synchronizes **Genie** window deformation and floating icon perspective with the native transition. Opening and closing have separate speeds. Droplet corners, trajectory, spring and desktop blur are available on compatible firmware.
- The legacy tilt engine remains selectable separately. Improved floating icon recovery and graphics buffer cleanup. An adapter supports selected ColorOS 16 launchers, with the stock transition as fallback.

### Interface and configuration

- Rebuilt the UI with COUI and ONextBox components. Updated navigation, predictive back, native top blur, switches, sliders and dialogs. Long-press opens scrollable section previews, and swiping to dismiss animates the card back into its row. Supports light, dark and system themes.
- The catalog groups Launcher, System UI and shared options. Search indexes active settings in Russian and English. Sections have independent switches, and restart and test-banner actions sit at the top.
- Previews for icons, folders, widgets, dock, banners, Live Alerts and volume use system samples where available. Select installed apps for icon previews and a gallery photo for the photo widget preview. Live wallpaper previews refresh when frames are available.
- **Your Settings** saves named profiles with switching, reordering and multi-select deletion. JSON import and export include a title, description, author and a preview on the recipient’s wallpaper. Imports support legacy color settings and dock radii.
- Diagnostic ZIP export includes logs and available System Launcher and System UI APKs. Updated module information and runtime status.

### Performance and compatibility

- Live glass follows the display up to **120 FPS**. An optional cadence mode is available for 165 Hz screens. Battery Saver and severe thermal conditions throttle capture. Wallpaper sampling follows the launcher’s screen position during a swipe.
- Shade captures are cropped around visible surfaces. The dock and page indicator share processed frames when their settings match. Buffers are released when effects turn off, with stock rendering and retry backoff on capture failure.
- In a local JVM benchmark on a 720×1440 image, temporary allocations for software blur dropped from **12–18 MiB to ~4 MiB** with pixel-identical output. This is a desktop measurement of the algorithm.
- The main development configuration is **OnePlus 13 on ColorOS 17**. Available effects depend on the launcher and System UI builds. Glass for both shades targets ColorOS 17. Add **System UI** to the LSPosed scope and enable the **Flux** icon style for icon glass. [Installation guide](https://github.com/Kostubet/CPI-Kostubet-Edition/blob/main/INSTALL.md#english)
