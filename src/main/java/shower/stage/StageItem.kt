package shower.stage

/** Тип медиа-элемента. */
enum class StageMediaType { VIDEO, PHOTO }

/**
 * Элемент плейлиста компонента.
 *
 * @property relPath относительный путь от корня источника (ключ для закладок/блэклиста);
 * @property name имя файла (используется в логах);
 * @property type видео или фото;
 * @property uri URI/путь, который проигрывается плеером (file://, content:// и т.п.).
 */
data class StageItem(
    val relPath: String,
    val name: String,
    val type: StageMediaType,
    val uri: String
)

/**
 * Хелпер определения типа по имени файла.
 * Видео: mp4 webm mkv mov m4v avi. Фото: jpg jpeg png webp bmp.
 * Файлы вида "*.part" (недокачанные) — null.
 */
object StageItemFactory {
    private val videoExt = setOf("mp4", "webm", "mkv", "mov", "m4v", "avi")
    private val photoExt = setOf("jpg", "jpeg", "png", "webp", "bmp")

    fun typeOf(name: String): StageMediaType? {
        val ext = name.substringAfterLast('.', "").lowercase()
        if (name.endsWith(".part")) return null
        return when {
            ext in videoExt -> StageMediaType.VIDEO
            ext in photoExt -> StageMediaType.PHOTO
            else -> null
        }
    }
}
