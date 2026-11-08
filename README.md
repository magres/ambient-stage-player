# ambient-stage-player

Компонент плеерного стенда для Android-kiosk: один GL-слой (OpenGL ES 2.0), который рисует
**размытый фон + поверх резкое видео** (режим `fill`), либо letterbox/center-crop, плюс
фото-слайдшоу с кроссфейдом. Плюс «эфирная» механика: слоты A/B ExoPlayer, кроссфейд видео
со звуком, skip-on-error, закладки resume (переживают HOME), black-fade маски между ранами.

Компонент самодостаточен: не зависит от хост-приложения, получает конфиг через публичный
API и элементы плейлиста в виде [StageItem](#публичный-api). Использован в приложении
[shower-kiosk](https://github.com/magres/shower-kiosk) (подключён как git submodule).

```kotlin
val glView = findViewById<StageGLView>(R.id.gl_ambient)
val engine = StagePlayer(playerView, glView, photoLayer, overlayBlack, statusText)
engine.setConfig(7.0, 800, "fit", "fill", false, "crossfade", true, 250)
engine.errorDelegate = { item -> blacklist.add(item.relPath) }
scope.launch {
    engine.fadeOut()
    engine.startVideoRun(Run.VideoRun(items), crossfade = true)
    engine.fadeIn()
    engine.awaitVideoRunEnd()
}
```

## Возможности (Features)

- **Единая GL-композиция** (`StageGLView`, GL ES 2.0): OES-кадр плеера → cover-crop → FBO
  192×108 → раздельный gaussian-блюр (H/V, 9-tap, σ≈1.6) → растяжка на экран (фон),
  поверх — резкое aspect-fit видео из того же кадра. 4 шейдер-программы, 2 слота
  SurfaceTexture (кроссфейд), ленивые FBO-пары.
- **Режимы видео**: `fill` (блюр-фон + резкое видео по центру), `fit` (letterbox на чёрном),
  `crop` (center-crop на весь экран).
- **Механика эфира** (`StagePlayer`): два чередующихся ExoPlayer-слота, гладкий кроссфейд
  видео (GL-альфа + smoothstep) с аудио-оверлепом по центру стыка, skip-on-error
  (errorDelegate → блокировка битого файла), ResumeMark-закладки после HOME, hard-cut
  режим `videoFade=none`, фото-слайдшоу на Glide с кроссфейдом.
- **Раны**: `RunGrouper.group()` режет плейлист на связные видео-раны и фото-раны —
  внутри рана нет затемнений, между ранами — black-fade.

## Публичный API (Public API)

Пакет `shower.stage`. Все классы задокументированы KDoc.

### `StagePlayer` — движок эфира

| Метод | Описание |
|---|---|
| `setConfig(photoSeconds, trMs, pFit, vFit, mute, videoFade, aFade, aOverlapMs)` | параметры эфира; `videoFade` инвалидируется в `none` |
| `startVideoRun(Run.VideoRun, crossfade, resumeMark?)` *(suspend)* | готовит видео-ран под маской; возвращает после первого кадра |
| `startPhotoRun(Run.PhotoRun, slideMs, startIdx?)` *(suspend)* | фото-ран; возвращает после кроссфейда первого слайда |
| `awaitVideoRunEnd()` / `awaitPhotoRunEnd()` *(suspend)* | дождаться конца текущего рана |
| `fadeOut()` / `fadeIn()` *(suspend)* | анимация black-маски |
| `blackoutNow()` | мгновенное затемнение |
| `takeVideoBookmark(expected)` / `takePhotoBookmark(expected)` | закладка resume, если путь тот же (иначе null) |
| `pause()` / `resume()` | мягкая пауза (HOME): плееры снесены, GL/ST живы, закладки сохранены |
| `stopAll()` / `release()` | останов плееров / полное освобождение |
| `errorDelegate` | колбэк битого элемента (skip-on-error) |
| `lastPlayed` | последний реально играющий элемент |
| `setUpdatingText(text)` | статусная строка (`"Синхронизация…"` и т.п.) |
| `applyVisualScales()` | переприменить scaleType фото + режим GL |

### `StageGLView` — GL-слой (`View`)

- `show()`, `startRendering()`, `stopRendering()`, `release()`
- `setMode("fill" | "fit" | "crop")`, `normalizeMode(m)`
- `setSlotAspect(slot, w, h, rotDegrees)`, `setVideoAspect(w, h, rot)`
- `setActiveSlot(slot)`, `setFadeAlpha(0..1)`, `activeSlot()`
- `slotHasFrame(slot)`, `lastSurfaceTexture(slot)`

### Данные и логи

- `StageItem(relPath, name, type, uri)` — элемент плейлиста; `StageItemFactory.typeOf(name)`
  — тип по расширению (видео: `mp4 webm mkv mov m4v avi`; фото: `jpg jpeg png webp bmp`;
  `*.part` → null).
- `Run.VideoRun` / `Run.PhotoRun`, `RunGrouper.group(items)` — раны и группировка.
- `StageLog` — лёгкий логгер: `tag` (по умолчанию `StagePlayer`), `level`, `i/w/e/v/d()`,
  `StageLogListener` (подписка хоста на весь трафик логов).
- `PhotoSink(a, b, layer)` — два чередующихся ImageView для фото-кроссфейда.

## Подключение (Integration)

### Gradle (git submodule)

```bash
git clone --recurse-submodules https://github.com/magres/shower-kiosk.git
```

В настройках хоста:

```kotlin
// settings.gradle.kts
include(":stage-player")
project(":stage-player").projectDir = file("stage-player")

// build.gradle.kts (:app)
dependencies {
    implementation(project(":stage-player"))
}
```

`androidx.media3-*` и `kotlinx-coroutines` выставлены компонентом как `api`
(используются в сигнатурах публичного API) — хост получает их транзитивно;
Glide спрятан как `implementation`.

### Требования

- `minSdk 23`, `compileSdk 34`, Java 17, AGP 8.5.2, Kotlin 2.0.10.
- View-иерархия хоста: `PlayerView` (texture_view), `StageGLView`, контейнер для фото,
  black-overlay `View`, `TextView` статуса.

## Сборка и тесты (Build & Tests)

```bash
git clone https://github.com/magres/ambient-stage-player.git
cd ambient-stage-player
# создайте local.properties: sdk.dir=C:/path/to/android-sdk
gradlew assembleDebug
gradlew testDebugUnitTest   # 11 юнит-тестов: RunGrouperTest, StageItemTypeTest
```

## Структура репозитория (Structure)

```
src/main/java/shower/stage/
  StageGLView.kt      # GL-композиция: блюр-фон + резкое видео, 2 слота, FBO
  StagePlayer.kt      # движок эфира: ExoPlayer A/B, кроссфейд, фото, закладки
  StageItem.kt        # StageItem + StageMediaType + StageItemFactory.typeOf
  StageRun.kt         # Run (Video/Photo) + RunGrouper
  StageLog.kt         # логгер + StageLogListener
  PhotoSink.kt        # двойной ImageView для фото-кроссфейда
src/test/java/shower/stage/
  RunGrouperTest.kt   # группировка ранов (6 тестов)
  StageItemTypeTest.kt# расширения, .part, кейс (5 тестов)
```

## Лицензия

Приватный проект. Код в репозитории — по договорённости с владельцем.
