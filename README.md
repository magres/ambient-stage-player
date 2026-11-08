# ambient-stage-player

Самодостаточный Android-компонент (библиотека) для kiosk-эфиров: единый GL-пайплайн
(OpenGL ES 2.0), который рисует **размытый ambient-фон + резкое видео по центру** из
одного кадра одного плеера, плюс фото-слайдшоу с кроссфейдом. Используется приложением
[shower-kiosk](https://github.com/magres/shower-kiosk) (подключается как git submodule),
но живёт и standalone.

## Возможности (Features)

- Единый GL-композит: оба слоя (фон и видео) рисуются из одного `SurfaceTexture` в одном
  `onDrawFrame` — рассинхрон физически невозможен, декодер один (нагрузка вдвое ниже,
  чем в схеме с двумя плеерами)
- Режимы отображения видео:
  - `fill` — размытый живой фон на весь экран + необрезанное видео по центру (TikTok-style);
  - `crop` — центр-кроп без полос;
  - `fit` — letterbox на чёрном
- Кроссфейд видео↔видео с чередованием двух слотов-плееров (A/B) + плавный аудио-фейд
- Фото-слайдшоу с кроссфейдом (Glide), настраиваемая длительность слайда
- Skip-on-error: битый файл не валит эфир (делегат ошибки)
- Resume-закладки: пауза/возврат продолжает с той же позиции (ролик + позиция в ране)
- Чистое завершение: `pause()` / `resume()` / `release()` для любых lifecycle-сценариев
- Требования: Android 6.0+ (API 23), GL ES 2.0 + `GL_OES_EGL_image_external` (есть на всех
  устройствах с видеодекодером)

## Архитектура (Pipeline)

```
ExoPlayer (один на момент) → SurfaceTexture (OES, слот 0/1)
        ↓ onDrawFrame (GLSurfaceView)
        ├─ blur-пассы:  cover-crop → downscale FBO 192×108 → H/V гаусс (σ≈1.6) → весь экран
        └─ sharp-пасс:  сэмпл того же OES-кадра → аспект-fit по центру (letterbox)
Кроссфейд: alpha-блендинг пассов двух слотов (u_fade 0..1, smoothstep)
```

## Публичный API (Public API)

| Класс/метод | Назначение |
|---|---|
| `StageGLView(context)` | GLSurfaceView-компонент; положить в layout под остальные view |
| `StageGLView.lastSurfaceTexture(slot)` | SurfaceTexture слота (0/1) — обернуть в `Surface` и отдать плееру |
| `StageGLView.show() / startRendering()` | показать и включить рендер |
| `StageGLView.stopRendering()` | скрыть/остановить рендер (контекст и ST переживают) |
| `StageGLView.setMode("fill"|"crop"|"fit")` | режим отображения |
| `StageGLView.setFadeAlpha(t: Float)` | альфа второго слота при кроссфейде (0..1) |
| `StageGLView.setActiveSlot(slot)` | какой слот рисуется основным |
| `StageGLView.setSlotAspect(slot, w, h, rot)` | пропорции/поворот видео слота (из `onVideoSizeChanged`) |
| `StageGLView.slotHasFrame(slot)` | пришёл ли уже кадр (для старта фейда) |
| `StageGLView.release()` | полный снос (только при уничтожении хоста) |
| `StagePlayer(view, glView, photoLayer, overlay, status)` | движок эфира (плееры, фейды, фото) |
| `StagePlayer.setConfig(...)` | параметры: photoSeconds, transitionMs, fit-режимы, mute, videoFade, audioFade |
| `StagePlayer.startVideoRun(run, crossfade)` / `startPhotoRun(run, slideMs)` | исполнение рана |
| `StagePlayer.awaitVideoRunEnd() / awaitPhotoRunEnd()` | suspend до конца рана |
| `StagePlayer.pause() / resume()` | lifecycle: HOME-пауза с закладкой и восстановление |
| `StagePlayer.errorDelegate` | callback битых файлов (blacklist на стороне хоста) |
| `RunGrouper.group(items)` | группировка плейлиста в раны (VideoRun/PhotoRun) |
| `StageItem(relPath, name, type, uri)` + `StageItemFactory.typeOf(name)` | модель медиа |

## Интеграция (Integration)

```
git submodule add https://github.com/magres/ambient-stage-player.git stage-player
```

`settings.gradle.kts`:
```kotlin
include(":stage-player")
project(":stage-player").projectDir = file("stage-player")
```

`app/build.gradle.kts`:
```kotlin
dependencies { implementation(project(":stage-player")) }
```

Минимальный пример:
```kotlin
val gl = findViewById<StageGLView>(R.id.gl_ambient)
val engine = StagePlayer(playerView, gl, photoLayer, overlay, statusText)
engine.setConfig(7.0, 800, "crop", "fill", mute = false, "crossfade", aFade = true, 250)
engine.errorDelegate = { item -> blacklist.add(item.relPath) }

val st = gl.lastSurfaceTexture(0)!!          // GL готов после show()
player.setVideoSurface(Surface(st))          // единственный плеер
player.setMediaItems(items.map { MediaItem.fromUri(it.uri) })
player.prepare(); player.playWhenReady = true
```

## Сборка и тесты (Build & Tests)

```
gradlew.bat assembleDebug
gradlew.bat testDebugUnitTest   # 11 тестов (RunGrouper, StageItemType)
```

## Ограничения (Limitations)

- Кроссфейд требует два активных декодера только на время фейда (обе ST получают кадры);
  между фейдами играет один плеер.
- NPOT mipmap-фильтрация не используется (GL ES 2.0) — блюр через FBO + гаусс.
- Поведение ST-matrix у разных вендоров может отличаться для повёрнутых видео — аспект
  учитывает `unappliedRotationDegrees`, но экзотические повороты стоит проверить.
- Glide — `implementation`-зависимость (не торчит в API).

## Лицензия

Свободное использование для внутренних задач.
