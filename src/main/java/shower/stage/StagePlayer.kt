package shower.stage

import android.view.View
import android.widget.FrameLayout
import android.graphics.SurfaceTexture
import android.widget.ImageView
import android.widget.TextView
import androidx.media3.common.MediaItem
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.ui.PlayerView
import androidx.media3.exoplayer.ExoPlayer
import com.bumptech.glide.Glide
import com.bumptech.glide.load.engine.GlideException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlin.coroutines.resume

/**
 * «Эфир» компонента:
 *  - fadeOut маска → смена рана → fadeIn (ответственность хоста/директора);
 *  - видео: ExoPlayer рисует в SurfaceTexture единственного [StageGLView],
 *    который в одном onDrawFrame рисует и размытый фон, и резкое видео (fill/fit/crop);
 *  - фото — кроссфейд; между ранами — black-fade маской [overlay];
 *  - skip-on-error через [errorDelegate] (хост блокирует битые файлы);
 *  - ResumeMark-закладки: pause()/resume() переживают HOME.
 */
class StagePlayer(
    view: PlayerView,
    glView: StageGLView?,
    photoLayer: FrameLayout,
    overlay: View,
    statusText: TextView
) {
    private val appView = view
    private val glView = glView
    private val photoLayer = photoLayer
    private val overlayBlack = overlay
    private val statusText = statusText

    private var transitionMs = 800L
    private var photoFit: String = "fit"
    private var videoFit: String = "fill"
    private var videoMute: Boolean = false
    private var audioFade: Boolean = true
    private var audioOverlapMs: Long = 250L

    /** Один из двух чередующихся плееров (слот 0/1). */
    private inner class Slot(val index: Int) {
        var player: ExoPlayer? = null
        var surface: android.view.Surface? = null
        var boundSt: SurfaceTexture? = null
    }

    private val slots = arrayOf(Slot(0), Slot(1))

    /** Слот, который рисуется как основной сейчас (0/1). */
    private var activeSlot = 0

    private var sink: PhotoSink? = null
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
    private var endJob: Job? = null

    /** Хост получает битый элемент (skip-on-error: решает — блокировать/пропустить). */
    var errorDelegate: ((StageItem) -> Unit)? = null

    /** Последний элемент, реально отданный в воспроизведение. */
    var lastPlayed: StageItem? = null
        private set

    /** Относительно чего продолжить прерванный ран. */
    data class ResumeMark(val idx: Int, val posMs: Long)

    private data class VideoMark(val relPaths: List<String>, val idx: Int, val posMs: Long)
    private data class PhotoMark(val relPaths: List<String>, val idx: Int)

    private var videoBookmark: VideoMark? = null
    private var photoBookmark: PhotoMark? = null
    // «живое» состояние активного рана — читается при pause() до снесения плееров (всё на main-треде)
    private var playRelPaths: List<String>? = null
    private var playCrossfade = true
    private var playIdx = 0
    private var playSlot = 0
    private var fadeTargetIdx = -1
    private var photoRelPaths: List<String>? = null
    private var photoIdx = 0

    /** Мягкая пауза (Home): GL/ST живут, плееры снесены, насос не исполняет команды. */
    @Volatile
    var paused = false
        private set

    /** Мягкая пауза: закладки «где остановился», плееры снесены, GL-слой жив. */
    fun pause() {
        if (paused) return
        paused = true
        StageLog.i("engine pause()")
        captureVideoBookmark()
        capturePhotoBookmark()
        endJob?.cancel()
        endJob = null
        for (s in slots) {
            runCatching { s.player?.release() }
            s.player = null
            runCatching { s.surface?.release() }
            s.surface = null
            s.boundSt = null
        }
        glView?.stopRendering()
    }

    /** Выход из мягкой паузы: закладки остаются, воспроизведение продолжат сами вызовы хоста. */
    fun resume() {
        if (!paused) return
        paused = false
        StageLog.i("engine resume()")
        setUpdatingText(null)
        glView?.show()
    }

    /** pause(): зафиксировать «где остановился» видео-ран (во время кроссфейда — цель фейда). */
    private fun captureVideoBookmark() {
        val paths = playRelPaths ?: return
        if (paths.isEmpty()) return
        val ft = fadeTargetIdx
        val slot = if (ft >= 0) 1 - playSlot else if (playCrossfade) playSlot else 0
        val p = slots[slot].player ?: return
        var idx = when {
            ft >= 0 -> ft // фейд шёл: текущим считаем цель фейда
            playCrossfade -> playIdx
            else -> p.currentMediaItemIndex
        }
        var posMs = p.currentPosition.coerceAtLeast(0L)
        if (p.playerError != null) {
            // битый item — закладка на следующий с нуля
            idx += 1
            posMs = 0L
        } else if (posMs > 0) {
            val dur = p.duration
            if (dur > 0 && posMs >= dur - 250) { // досмотрели почти до конца → следующий с 0
                idx += 1
                posMs = 0L
            }
        }
        if (idx >= paths.size) {
            StageLog.i("resume bookmark: ран доигрывается — закладки нет")
            return
        }
        videoBookmark = VideoMark(paths, idx, posMs)
        StageLog.i("resume bookmark: video idx=$idx pos=${posMs}ms of ${paths.size}")
    }

    private fun capturePhotoBookmark() {
        val paths = photoRelPaths ?: return
        val idx = photoIdx.coerceIn(0, paths.size - 1)
        photoBookmark = PhotoMark(paths, idx)
        StageLog.i("resume bookmark: photo idx=$idx of ${paths.size}")
    }

    /**
     * Насос перед стартом рана: вернуть закладку, если ран тот же (сверка relPathов),
     * и погасить её. Вернуть null — старт с начала.
     */
    fun takeVideoBookmark(expected: List<String>): ResumeMark? {
        val b = videoBookmark ?: return null
        videoBookmark = null
        if (b.relPaths.size != expected.size || b.relPaths.zip(expected).any { (a, e) -> a != e }) {
            StageLog.i("resume bookmark: список изменился — старт с начала")
            return null
        }
        StageLog.i("resume bookmark: продолжаю с idx=${b.idx} pos=${b.posMs}ms of ${b.relPaths.size}")
        return ResumeMark(b.idx, b.posMs)
    }

    /** Аналогично [takeVideoBookmark] для фото-ранов: вернуть стартовый индекс или null. */
    fun takePhotoBookmark(expected: List<String>): Int? {
        val b = photoBookmark ?: return null
        photoBookmark = null
        if (b.relPaths.size != expected.size || b.relPaths.zip(expected).any { (a, e) -> a != e }) {
            StageLog.i("resume bookmark photo: список изменился — старт с начала")
            return null
        }
        StageLog.i("resume bookmark: продолжаю photo с idx=${b.idx} of ${b.relPaths.size}")
        return b.idx
    }

    private fun resolveStart(items: List<StageItem>, mark: ResumeMark?): Pair<Int, Long> =
        if (mark != null && mark.idx in items.indices) mark.idx to mark.posMs else 0 to 0L

    /**
     * Параметры эфира (обычно из config.json хоста).
     *
     * @param photoSeconds длительность слайда фото-рана, сек (решение о slideMs — за хостом);
     * @param trMs длительность кроссфейда/маски, мс;
     * @param pFit фото: fit | crop;
     * @param vFit видео: fill (блюр+резкое) | fit (letterbox) | crop;
     * @param mute заглушить звук;
     * @param videoFade "crossfade" | "none" (инвалидное значение трактуется как none);
     * @param aFade аудио-оверлеп на стыках видео;
     * @param aOverlapMs ширина аудио-перекрытия, мс (0..3000).
     */
    fun setConfig(
        photoSeconds: Double, trMs: Long, pFit: String, vFit: String, mute: Boolean,
        videoFade: String, aFade: Boolean, aOverlapMs: Long
    ) {
        transitionMs = trMs
        photoFit = pFit
        videoFit = vFit
        videoMute = mute
        val vf = (videoFade ?: "none").trim().lowercase()
        if (vf != "crossfade" && vf != "none") {
            StageLog.w("play.videoFade='$videoFade' невалиден — использую none")
        }
        audioFade = aFade
        audioOverlapMs = aOverlapMs.coerceIn(0, 3000)
        applyVisualScales()
    }

    /** Переприменить scaleType фото и режим GL-слоя (после [setConfig]). */
    fun applyVisualScales() {
        sink?.let { s ->
            val st = if (photoFit == "crop") ImageView.ScaleType.CENTER_CROP else ImageView.ScaleType.FIT_CENTER
            s.a.scaleType = st
            s.b.scaleType = st
        }
        glView?.setMode(videoFit)
    }

    private fun baseVolume(): Float = if (videoMute) 0f else 1f

    private fun hideAmbient() {
        glView?.visibility = View.GONE
    }

    private fun ensurePhotoLayer(): PhotoSink {
        if (sink == null) {
            fun mk(alpha: Float): ImageView {
                val iv = ImageView(appView.context)
                iv.scaleType =
                    if (photoFit == "crop") ImageView.ScaleType.CENTER_CROP else ImageView.ScaleType.FIT_CENTER
                iv.alpha = alpha
                photoLayer.addView(iv, FrameLayout.LayoutParams(-1, -1))
                return iv
            }
            val a = mk(1f)
            val b = mk(0f)
            sink = PhotoSink(a, b, photoLayer)
        }
        return sink!!
    }

    /** Плеер слота (создание при первом использовании) + привязка свежего SurfaceTexture. */
    private suspend fun ensureSlot(i: Int): Slot {
        val s = slots[i]
        if (paused) return s
        var p = s.player
        if (p == null) {
            p = ExoPlayer.Builder(appView.context).build()
            p.repeatMode = Player.REPEAT_MODE_OFF
            p.addListener(object : Player.Listener {
                override fun onVideoSizeChanged(videoSize: androidx.media3.common.VideoSize) {
                    glView?.setSlotAspect(i, videoSize.width, videoSize.height, videoSize.unappliedRotationDegrees)
                }
            })
            s.player = p
        }
        var st = awaitSurface(i)
        if (paused) return s
        // ST мог пересоздаться (новый GL-контекст) — перепривязываем; до 3 попыток с паузой
        var tries = 1
        while (st == null && tries < 3) {
            StageLog.w("GL surface not ready (попытка $tries) — ретрай через 4с")
            kotlinx.coroutines.delay(4000)
            st = awaitSurface(i)
            if (paused) return s
            tries++
        }
        if (st != null) {
            if (s.boundSt !== st) {
                runCatching { s.surface?.release() }
                val surf = android.view.Surface(st)
                p.setVideoSurface(surf)
                s.surface = surf
                s.boundSt = st
            }
        } else {
            StageLog.w("GL surface не появился за 3 попытки — чёрный экран (фоллбек)")
            p.setVideoSurface(null)
            setUpdatingText("Video unavailable")
        }
        return s
    }

    private fun stopSlot(s: Slot) {
        val p = s.player ?: return
        runCatching { p.stop(); p.clearMediaItems() }
    }

    /** Показать/скрыть статусную строку поверх чёрного экрана (null — скрыть). */
    fun setUpdatingText(text: String?) {
        if (text.isNullOrBlank()) {
            statusText.visibility = View.GONE
        } else {
            statusText.visibility = View.VISIBLE
            statusText.text = text
        }
    }

    /** Немедленное затемнение маской (для старта/первого рана). */
    fun blackoutNow() {
        overlayBlack.visibility = View.VISIBLE
        overlayBlack.animate().cancel()
        overlayBlack.alpha = 1f
    }

    /** Плавно затемнить маской до 100%. */
    suspend fun fadeOut() = animateTo(1f)

    /** Плавно убрать маску до 0%. */
    suspend fun fadeIn() = animateTo(0f)

    private suspend fun animateTo(alpha: Float) {
        overlayBlack.animate().cancel()
        overlayBlack.visibility = View.VISIBLE
        if (transitionMs <= 30) {
            overlayBlack.alpha = alpha
            if (alpha == 0f) overlayBlack.visibility = View.INVISIBLE
        } else {
            suspendCancellableCoroutine<Unit> { c ->
                overlayBlack.animate()
                    .alpha(alpha).setDuration(transitionMs)
                    .withEndAction {
                        if (alpha == 0f) overlayBlack.visibility = View.INVISIBLE
                        c.resume(Unit)
                    }
                    .start()
                c.invokeOnCancellation { overlayBlack.animate().cancel() }
            }
        }
    }

    // ============================ VIDEO ============================

    /**
     * Готовит видео-ран ПОД чёрной маской; возвращает после готовности первого кадра.
     * @param crossfade режим стыков: true — движок ведёт кроссфейд между роликами,
     * false — hard-cut одним списком MediaItems.
     * @param resumeMark продолжить прерванный ран с item[idx] posMs (закладка после HOME).
     */
    suspend fun startVideoRun(run: Run.VideoRun, crossfade: Boolean, resumeMark: ResumeMark? = null) {
        if (paused) {
            StageLog.i("startVideoRun ignored: paused")
            return
        }
        photoLayer.visibility = View.GONE
        appView.visibility = View.GONE
        overlayBlack.visibility = View.VISIBLE
        applyVisualScales()
        activeSlot = 0
        glView?.setActiveSlot(0)
        glView?.setFadeAlpha(0f)
        fadeTargetIdx = -1

        val s = ensureSlot(0)
        val p = s.player ?: return
        glView?.startRendering()

        val items = run.items
        if (items.isEmpty()) return
        playRelPaths = items.map { it.relPath }
        playCrossfade = crossfade

        p.volume = baseVolume()

        if (!crossfade || items.size <= 1) {
            // videoFade=none: поведение как раньше — список MediaItems, hard-cut стыки
            val msm = items.map { MediaItem.fromUri(it.uri) }
            val (startIdx, startPos) = resolveStart(items, resumeMark)
            if (startIdx > 0 || startPos > 0) {
                p.setMediaItems(msm, startIdx, startPos)
            } else {
                p.setMediaItems(msm)
            }
            p.prepare()
            p.playWhenReady = true
            awaitFirstFrame(p)
            if (paused) { stopSlot(s); return }
            if (p.playerError != null) {
                val bad = items.getOrNull(p.currentMediaItemIndex) ?: items.first()
                StageLog.w("player error item ${bad.relPath} code=${p.playerError?.errorCodeName}")
                errorDelegate?.invoke(bad)
                stopSlot(s)
                return
            }
            endJob = scope.launch {
                try {
                    val end = awaitRunEnd(p, items)
                    if (end != null) lastPlayed = end
                    stopSlot(s)
                } finally { playRelPaths = null }
            }
        } else {
            // crossfade: активный плеер играет ОДИН MediaItem, стыки ведёт driveVideoRun
            val (startIdx, startPosRaw) = resolveStart(items, resumeMark)
            p.setMediaItem(MediaItem.fromUri(items[startIdx].uri), startPosRaw)
            p.prepare()
            p.playWhenReady = true
            awaitFirstFrame(p)
            if (paused) { stopSlot(s); return }
            var startPos = startPosRaw
            val dur = p.duration
            if (dur > 0 && startPos >= dur) { // ролик кончился за время паузы — отступаем к стыку
                startPos = (dur - 1000).coerceAtLeast(0)
                p.seekTo(startPos)
            }
            lastPlayed = items[startIdx]
            playIdx = startIdx
            playSlot = 0
            endJob = scope.launch {
                try { driveVideoRun(run, startIdx) }
                finally { playRelPaths = null; fadeTargetIdx = -1 }
            }
        }
    }

    private suspend fun awaitSurface(i: Int): SurfaceTexture? {
        val gl = glView ?: return null
        gl.show()
        val deadline = System.currentTimeMillis() + 6000
        var s = gl.lastSurfaceTexture(i)
        while (s == null && System.currentTimeMillis() < deadline) {
            if (paused) return null
            delay(25)
            s = gl.lastSurfaceTexture(i)
        }
        return s
    }

    /**
     * Кроссфейд-ран: активный плеер играет ОДИН MediaItem; за transitionMs до конца
     * готовит другой слот с следующим роликом и смешивает картинки (GL fade) + звук.
     */
    private suspend fun driveVideoRun(run: Run.VideoRun, startIdx: Int) {
        val items = run.items
        if (items.isEmpty()) return
        var slotIdx = 0
        var idx = startIdx
        var loaded = true // первый элемент уже загружен и показан startVideoRun'ом
        var exhausted = false // все последующие элементы рана пробовали грузиться и битые
        playSlot = 0
        playIdx = idx

        while (idx < items.size) {
            val s = slots[slotIdx]
            val p = s?.player
            if (s == null || p == null) return

            if (exhausted) {
                // дальше в ране нечего играть: ждём конец текущего и выходим
                awaitEndedOnly(p)
                stopSlot(s)
                return
            }

            if (!loaded) {
                val prep = prepareOnSlot(s, items[idx])
                if (prep == PREP_ERROR) {
                    StageLog.w("video item error: ${items[idx].relPath}")
                    errorDelegate?.invoke(items[idx])
                    stopSlot(s)
                    idx++
                    continue
                }
                // мгновенный ввод (сюда попадаем только после ошибки предыдущего item'а)
                glView?.setFadeAlpha(0f)
                glView?.setActiveSlot(slotIdx)
                p.volume = baseVolume()
                loaded = true
            }
            lastPlayed = items[idx]
            playIdx = idx
            playSlot = slotIdx

            // --- наблюдение: near-end | ended | error ---
            var boundary = B_ENDED
            while (true) {
                if (p.playerError != null) { boundary = B_ERROR; break }
                if (p.playbackState == Player.STATE_ENDED) { boundary = B_ENDED; break }
                val dur = p.duration
                if (idx + 1 < items.size && dur > 0 && dur - p.currentPosition <= transitionMs) {
                    boundary = B_NEAR_END; break
                }
                delay(80)
            }

            when (boundary) {
                B_ERROR -> {
                    val bad = items.getOrNull(p.currentMediaItemIndex) ?: items[idx]
                    StageLog.w("player error item ${bad.relPath} code=${p.playerError?.errorCodeName}")
                    errorDelegate?.invoke(bad)
                    stopSlot(s)
                    idx++
                    if (idx >= items.size) return
                    slotIdx = 1 - slotIdx // новый item грузится на другой слот; активный слот заменится после READY
                    playIdx = idx
                    playSlot = slotIdx
                    loaded = false
                }
                B_ENDED, B_NEAR_END -> {
                    if (idx + 1 >= items.size) {
                        stopSlot(s)
                        return
                    }
                    val done = crossfadeStep(items, idx, slotIdx)
                    if (done == null) {
                        exhausted = true // дальше в этом ране всё битое
                    } else {
                        idx = done
                        slotIdx = 1 - slotIdx
                        playIdx = idx
                        playSlot = slotIdx
                        loaded = true // crossfadeStep уже загрузил и ввёл новый item
                    }
                }
            }
        }
    }

    private companion object {
        const val B_ERROR = 0
        const val B_ENDED = 1
        const val B_NEAR_END = 2
        const val PREP_OK = 0
        const val PREP_ERROR = 1
    }

    /** Ждёт STATE_ENDED или ошибку (без переключений) — для «всё битое, играть текущий до конца». */
    private suspend fun awaitEndedOnly(p: ExoPlayer) {
        while (true) {
            if (p.playerError != null || p.playbackState == Player.STATE_ENDED || paused) return
            delay(100)
        }
    }

    /** Готовит item на слоте и играет его тихо; ввод (фейд/вкл звука) делает вызывающий. */
    private suspend fun prepareOnSlot(s: Slot, item: StageItem): Int {
        val p = s.player ?: return PREP_ERROR
        p.volume = 0f // тихо до ввода
        p.setMediaItem(MediaItem.fromUri(item.uri))
        p.prepare()
        p.playWhenReady = true
        // ждём READY/ошибку до 6с
        val t0 = System.currentTimeMillis()
        while (System.currentTimeMillis() - t0 < 6000) {
            if (p.playerError != null) return PREP_ERROR
            if (p.playbackState == Player.STATE_READY) break
            delay(50)
        }
        if (p.playerError != null || p.playbackState != Player.STATE_READY) return PREP_ERROR
        // ждём первый кадр (до 900мс) — чтобы фейд не начинался с пустой текстуры
        val gl = glView
        val t1 = System.currentTimeMillis()
        while (gl != null && !gl.slotHasFrame(s.index) && System.currentTimeMillis() - t1 < 900) {
            if (p.playerError != null) return PREP_ERROR
            delay(30)
        }
        return PREP_OK
    }

    /**
     * Стык двух роликов: подготовить следующий на другом слоте, ввести кроссфейдом
     * (картинка + звук). Если следующий(ие) битые — блэклистим и пробуем следующие.
     * Возвращает индекс реально играющего item'а (слот чередуется), либо null,
     * если играть дальше нечего (текущий доигрывает до конца).
     */
    private suspend fun crossfadeStep(items: List<StageItem>, idx: Int, fromSlot: Int): Int? {
        var target = idx + 1
        val toSlot = 1 - fromSlot
        val oldS = slots[fromSlot]
        val oldP = oldS?.player
        val cur = items[idx]
        val durMs = transitionMs.coerceIn(120, 6000)

        while (target < items.size) {
            val ns = ensureSlot(toSlot)
            val np = ns.player ?: return null
            val prep = prepareOnSlot(ns, items[target])
            if (prep == PREP_ERROR) {
                StageLog.w("crossfade: item error: ${items[target].relPath}")
                errorDelegate?.invoke(items[target])
                stopSlot(ns)
                target++
                continue
            }
            StageLog.i("fade start: ${cur.relPath} -> ${items[target].relPath} (${durMs}ms, slot$fromSlot->slot$toSlot)")
            fadeTargetIdx = target // во время фейда «текущим» считаем цель (bookmark-семантика)
            doFade(oldP, np, durMs)
            StageLog.i("fade complete: ${items[target].relPath}")
            glView?.setActiveSlot(toSlot)
            glView?.setFadeAlpha(0f)
            fadeTargetIdx = -1
            np.volume = baseVolume()
            stopSlot(oldS!!)
            return target
        }
        return null
    }

    /** Визуальный кроссфейд на GL + аудио-оверлеп, центрируемый на стыке. */
    private suspend fun doFade(
        oldP: ExoPlayer?,
        newP: ExoPlayer,
        fadeMs: Long
    ) {
        val gl = glView
        if (gl == null) {
            delay(fadeMs)
            if (videoMute) newP.volume = 0f
            return
        }
        val steps = (fadeMs / 33).coerceIn(4, 32)
        val stepMs = fadeMs / steps
        // окно аудио-оверлепа проходит через стык (конец фейда): половина до, половина после (здесь до)
        val audioFromRaw = if (audioFade) (1f - (audioOverlapMs.coerceIn(60, fadeMs) / 2f) / fadeMs) else 1.01f
        for (i in 1..steps) {
            val raw = i.toFloat() / steps
            val t = smoothstep(raw)
            gl.setFadeAlpha(t)
            if (audioFade && !videoMute && raw >= audioFromRaw) {
                val at = ((raw - audioFromRaw) / (1f - audioFromRaw).coerceAtLeast(1e-3f)).coerceIn(0f, 1f)
                oldP?.volume = 1f - at
                newP.volume = at
            }
            delay(stepMs)
        }
        gl.setFadeAlpha(1f)
        // финал: старый глушим, новый по конфигу
        if (videoMute) {
            oldP?.volume = 0f
            newP.volume = 0f
        } else {
            oldP?.volume = 0f
            newP.volume = 1f
        }
    }

    private fun smoothstep(t: Float): Float = t * t * (3f - 2f * t)

    /** Дождаться конца текущего видео-рана (endJob). */
    suspend fun awaitVideoRunEnd() {
        endJob?.join()
        endJob = null
    }

    /** Останов всех плееров и фоновых задач (перед сменой конфигурации/выходом). */
    fun stopAll() {
        endJob?.cancel()
        endJob = null
        for (s in slots) stopSlot(s)
    }

    /**
     * Готовит фото-ран ПОД маской; возвращает после кроссфейда первого слайда.
     * @param slideMs длительность слайда решает хост/директор.
     * @param startIdx стартовый слайд (закладка после HOME).
     */
    suspend fun startPhotoRun(run: Run.PhotoRun, slideMs: Long, startIdx: Int = 0) {
        if (paused) {
            StageLog.i("startPhotoRun ignored: paused")
            return
        }
        ensurePhotoLayer()
        val s = sink!!
        photoLayer.visibility = View.VISIBLE
        appView.visibility = View.GONE
        hideAmbient()
        overlayBlack.visibility = View.VISIBLE

        val start = startIdx.coerceIn(0, run.items.size - 1)
        val first = run.items.getOrNull(start) ?: return
        photoRelPaths = run.items.map { it.relPath }
        photoIdx = start
        showSlide(s, first, crossfade = false)
        lastPlayed = first

        endJob = scope.launch {
            try { playRestSlides(run, slideMs, start) }
            finally { photoRelPaths = null }
        }
    }

    /** Дождаться конца текущего фото-рана (endJob). */
    suspend fun awaitPhotoRunEnd() {
        endJob?.join()
        endJob = null
    }

    private suspend fun playRestSlides(run: Run.PhotoRun, slideMsIn: Long, startIdx: Int) {
        val s = sink ?: return
        val fadeMs = minOf(transitionMs, 600L)
        val slideMs = slideMsIn.coerceAtLeast(1000L)
        if (startIdx < run.items.size) delay(slideMs) // слайд startIdx уже показан startPhotoRun'ом
        for (i in (startIdx + 1) until run.items.size) {
            if (paused) return
            val it = run.items[i]
            photoIdx = i
            showSlide(s, it, crossfade = true)
            lastPlayed = it
            if (i != run.items.size - 1) delay(slideMs - fadeMs)
        }
    }

    private suspend fun showSlide(s: PhotoSink, item: StageItem, crossfade: Boolean) {
        val gr = Glide.with(appView.context)
        val fadeMs = minOf(transitionMs, 600L)
        val img = s.back
        java.util.concurrent.atomic.AtomicBoolean(false).let { done ->
            suspendCancellableCoroutine<Unit> { cont ->
                fun settle() {
                    if (done.compareAndSet(false, true)) cont.resume(Unit)
                }
                cont.invokeOnCancellation { runCatching { Glide.with(appView.context).clear(img) } }
                gr.load(item.uri)
                    .listener(object : com.bumptech.glide.request.RequestListener<android.graphics.drawable.Drawable> {
                        override fun onLoadFailed(e: GlideException?, model: Any?, target: com.bumptech.glide.request.target.Target<android.graphics.drawable.Drawable>, isFirstResource: Boolean): Boolean {
                            StageLog.w("photo load failed: ${item.relPath} ${e?.message}")
                            errorDelegate?.invoke(item)
                            settle()
                            return true
                        }

                        override fun onResourceReady(resource: android.graphics.drawable.Drawable, model: Any, target: com.bumptech.glide.request.target.Target<android.graphics.drawable.Drawable>, dataSource: com.bumptech.glide.load.DataSource, isFirstResource: Boolean): Boolean {
                            settle()
                            return false
                        }
                    })
                    .into(img)
            }
        }
        img.alpha = 0f
        s.layer.removeView(img)
        s.layer.addView(img, FrameLayout.LayoutParams(-1, -1))
        img.animate().alpha(1f).setDuration(if (crossfade) fadeMs else 1L).setListener(null).start()
        val prev = s.front
        s.swap()
        if (crossfade && prev !== img) {
            prev.animate().alpha(0f).setDuration(fadeMs * 2).setListener(null).start()
        }
    }

    private suspend fun awaitFirstFrame(p: Player) {
        while (p.playbackState != Player.STATE_READY && p.playbackState != Player.STATE_ENDED) {
            if (p.playerError != null || paused) return
            delay(50)
        }
    }

    private suspend fun awaitRunEnd(p: Player, items: List<StageItem>): StageItem? {
        return suspendCancellableCoroutine { cont ->
            val listener = object : Player.Listener {
                override fun onPlayerError(error: PlaybackException) {
                    val badIdx = p.currentMediaItemIndex
                    val bad = items.getOrNull(badIdx)
                    StageLog.w("player error item ${bad?.relPath} code=${error.errorCodeName}")
                    if (bad != null) errorDelegate?.invoke(bad)
                    if (badIdx + 1 < items.size) {
                        p.seekToNextMediaItem()
                    } else {
                        p.removeListener(this)
                        cont.resume(null)
                    }
                }

                override fun onPlaybackStateChanged(state: Int) {
                    if (state == Player.STATE_ENDED) {
                        p.removeListener(this)
                        cont.resume(items.lastOrNull())
                    }
                }
            }
            p.addListener(listener)
            cont.invokeOnCancellation { p.removeListener(listener) }
        }
    }

    /** Полное освобождение: плееры, поверхностности, GL-слой, корутинный scope. */
    fun release() {
        endJob?.cancel()
        endJob = null
        scope.cancel()
        for (s in slots) {
            runCatching { s.player?.release() }
            s.player = null
            runCatching { s.surface?.release() }
            s.surface = null
            s.boundSt = null
        }
        glView?.release()
    }
}
