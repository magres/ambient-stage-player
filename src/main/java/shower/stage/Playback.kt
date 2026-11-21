package shower.stage

/**
 * Контракт компонента показа. [StagePlayer] — единственная продовая реализация;
 * Player (модуль app) кодируется против интерфейса, в тестах подставляется заглушка.
 */
interface Playback {

    /** Мягкая пауза (HOME): компонент не принимает команды показа. */
    fun isPaused(): Boolean

    /** Начать видео-ран (серия роликов): подготовка под маской + resume-закладка. */
    suspend fun playVideo(items: List<StageItem>, crossfade: Boolean)

    /** Начать фото-ран (слайдшоу). */
    suspend fun playPhoto(items: List<StageItem>, slideMs: Long)

    /** Дождаться конца текущего рана. */
    suspend fun awaitRunEnd()

    /** Погасить текущий ран немедленно (awaitRunEnd вернётся). */
    fun stop()

    /** Плавно затемнить маской до 100%. */
    suspend fun fadeOut()

    /** Плавно убрать маску до 0%. */
    suspend fun fadeIn()
}
