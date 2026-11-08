package shower.stage

import android.view.View
import android.widget.FrameLayout
import android.widget.ImageView

/**
 * Два чередующихся ImageView для фото-кроссфейда (front показывает, back готовится).
 */
class PhotoSink(val a: ImageView, val b: ImageView, val layer: FrameLayout) {
    var front: ImageView = a
    var back: ImageView = b

    fun swap() {
        val t = front
        front = back
        back = t
    }
}
