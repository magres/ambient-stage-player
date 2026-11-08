package shower.stage

/**
 * Ран: подряд идущие элементы одного типа, проигрываются без внутрираннего затемнения.
 * Смена типа (видео↔фото) даёт смену рана с black-fade.
 */
sealed class Run {
    abstract val items: List<StageItem>

    /** Видео-ран: связная последовательность видеофайлов. */
    class VideoRun(override val items: List<StageItem>) : Run()

    /** Фото-ран: связная последовательность изображений (слайдшоу). */
    class PhotoRun(override val items: List<StageItem>) : Run()
}

/**
 * Разбивает плейлист на раны: последовательные видео и последовательные фото.
 */
object RunGrouper {
    fun group(items: List<StageItem>): List<Run> {
        val runs = ArrayList<Run>()
        var i = 0
        while (i < items.size) {
            val t = items[i].type
            var j = i
            while (j < items.size && items[j].type == t) j++
            val slice = items.subList(i, j)
            runs.add(if (t == StageMediaType.VIDEO) Run.VideoRun(slice) else Run.PhotoRun(slice))
            i = j
        }
        return runs
    }
}
