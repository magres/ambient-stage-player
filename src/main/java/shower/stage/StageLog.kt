package shower.stage

import android.util.Log as AndroidLog
import java.util.concurrent.CopyOnWriteArrayList

/** Уровни лог-сообщений (совместимы с android.util.Log). */
object StageLogLevel {
    const val VERBOSE = 2
    const val DEBUG = 3
    const val INFO = 4
    const val WARN = 5
    const val ERROR = 6
}

/**
 * Слушатель логов плеера (для хост-приложения: можно дублировать в файл, на UI и т.п.).
 */
interface StageLogListener {
    fun onStageLog(level: Int, msg: String)
}

/**
 * Лёгкий логгер компонента: android.util.Log с тегом по умолчанию "StagePlayer"
 * плюс опциональные слушатели для хост-приложения.
 */
object StageLog {
    /** Тег в logcat. */
    @Volatile
    var tag: String = "StagePlayer"

    /** Порог вывода: сообщения ниже уровня не печатаются. */
    @Volatile
    var level: Int = StageLogLevel.INFO

    private val listeners = CopyOnWriteArrayList<StageLogListener>()

    /** Подписка хост-приложения на весь трафик логов (вне зависимости от порога). */
    fun addListener(l: StageLogListener) {
        listeners.add(l)
    }

    fun removeListener(l: StageLogListener) {
        listeners.remove(l)
    }

    fun v(msg: String) = log(StageLogLevel.VERBOSE, msg)
    fun d(msg: String) = log(StageLogLevel.DEBUG, msg)
    fun i(msg: String) = log(StageLogLevel.INFO, msg)
    fun w(msg: String) = log(StageLogLevel.WARN, msg)

    /** Ошибка; если [t] передано — к сообщению добавляется стейтрейс. */
    fun e(msg: String, t: Throwable? = null) {
        val full = if (t == null) msg else "$msg ${t.javaClass.simpleName}: ${t.message}\n" +
            AndroidLog.getStackTraceString(t)
        log(StageLogLevel.ERROR, full)
    }

    private fun log(lvl: Int, msg: String) {
        if (lvl >= level) {
            AndroidLog.println(lvl, tag, msg)
        }
        if (listeners.isNotEmpty()) {
            for (l in listeners) {
                try {
                    l.onStageLog(lvl, msg)
                } catch (_: Throwable) {
                }
            }
        }
    }
}
