package shower.stage

import android.content.Context
import android.graphics.SurfaceTexture
import android.opengl.GLES11Ext
import android.opengl.GLES20
import android.opengl.GLSurfaceView
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.FloatBuffer
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicReference
import javax.microedition.khronos.egl.EGLConfig
import javax.microedition.khronos.opengles.GL10

/**
 * Единая GL-композиция видео (GL ES 2.0):
 * один SurfaceTexture (GL_TEXTURE_EXTERNAL_OES) от единственного плеера,
 * один onDrawFrame:
 *  - fill: OES + cover-crop → FBO 192×108 → H/V gauss → растяжка на весь экран (блюр-фон)
 *          + поверх резкое (sharp) aspect-fit видео из ТОГО ЖЕ OES-кадра;
 *  - fit:  резкое letterbox на чёрном (блюр пропускается);
 *  - crop: резкое center-crop на весь экран.
 *
 * Два слота (основной/второй) — для кроссфейда между роликами: альфа второго слота
 * задаётся [setFadeAlpha].
 */
class StageGLView @JvmOverloads constructor(
    context: Context,
    attrs: android.util.AttributeSet? = null
) : GLSurfaceView(context, attrs) {

    companion object {
        const val TAG = "StageGL"
        const val SMALL_W = 192
        const val SMALL_H = 108
        const val MODE_FILL = "fill"
        const val MODE_FIT = "fit"
        const val MODE_CROP = "crop"

        /** Нормализация режима: неизвестное/null → "fill". */
        fun normalizeMode(m: String?): String = when ((m ?: "").lowercase()) {
            MODE_FIT -> MODE_FIT
            MODE_CROP -> MODE_CROP
            else -> MODE_FILL
        }
    }

    private val rendererImpl = Renderer()
    private val stRef0 = AtomicReference<SurfaceTexture?>(null)
    private val stRef1 = AtomicReference<SurfaceTexture?>(null)
    private val running = AtomicBoolean(false)

    init {
        setEGLContextClientVersion(2)
        setRenderer(rendererImpl)
        renderMode = RENDERMODE_WHEN_DIRTY
        preserveEGLContextOnPause = true
    }

    /** Текущая SurfaceTexture слота (0=основной, 1=второй для кроссфейда), если GL готов. */
    fun lastSurfaceTexture(slot: Int = 0): SurfaceTexture? =
        if (slot == 1) stRef1.get() else stRef0.get()

    /** Показать view и включить рендер (после resume/старта). */
    fun show() {
        StageLog.i("StageGL.show() vis=$visibility running=${rendererImpl.running}")
        running.set(true)
        rendererImpl.running = true
        visibility = VISIBLE
        requestRender()
    }

    /** Включить рендер без изменения видимости. */
    fun startRendering() {
        rendererImpl.running = true
        requestRender()
    }

    /** Остановить рендер и скрыть view (при паузе/уходе с экрана). */
    fun stopRendering() {
        StageLog.i("StageGL.stopRendering() (hide)")
        rendererImpl.running = false
        visibility = GONE
    }

    /** Режим отображения: fill (блюр-фон + резкое видео) | fit (letterbox) | crop. */
    fun setMode(mode: String?) {
        val m = normalizeMode(mode)
        if (rendererImpl.mode != m) {
            rendererImpl.mode = m
            requestRender()
        }
    }

    /** Пропорции/поворот видео (для cover-обрезки, letterbox и буфера ST) для слота. */
    fun setSlotAspect(slot: Int, w: Int, h: Int, rotationDegrees: Int) {
        val rot = ((rotationDegrees % 360) + 360) % 360
        var dw = if (w > 0) w else 16
        var dh = if (h > 0) h else 9
        if (rot == 90 || rot == 270) {
            val t = dw; dw = dh; dh = t
        }
        val sa = dw.toFloat() / dh.toFloat()
        val long = 360
        val stSize = if (sa >= 1f) intArrayOf(long, Math.round(long / sa)) else intArrayOf(Math.round(long * sa), long)
        rendererImpl.slotApplyAspect(slot, sa, stSize[0], stSize[1])
        requestRender()
        StageLog.i("StageGL slot=$slot aspect=$sa buf=${stSize[0]}x${stSize[1]} rot=$rot mode=${rendererImpl.mode}")
    }

    /** Пропорции/поворот видео активного слота (обратная совместимость). */
    fun setVideoAspect(w: Int, h: Int, rotationDegrees: Int) =
        setSlotAspect(rendererImpl.primary, w, h, rotationDegrees)

    /** Какой слот (0/1) рисуется непрозрачным основным пайплайном. */
    fun setActiveSlot(slot: Int) {
        val s = if (slot == 1) 1 else 0
        if (rendererImpl.primary != s) {
            rendererImpl.primary = s
            requestRender()
        }
    }

    /** Альфа второго слота во время кроссфейда: 0 = только основной, 1 = только второй. */
    fun setFadeAlpha(t: Float) {
        val v = t.coerceIn(0f, 1f)
        if (rendererImpl.fade != v) {
            rendererImpl.fade = v
            requestRender()
        }
    }

    /** Активный (основной) слот. */
    fun activeSlot(): Int = rendererImpl.primary

    /** Был ли уже кадр в этом слоте (для ожидания перед стартом фейда). */
    fun slotHasFrame(slot: Int): Boolean =
        rendererImpl.slotFrame(if (slot == 1) 1 else 0)

    /** Останов рендера и освобождение GL-ресурсов (SurfaceTexture, FBO). */
    fun release() {
        stopRendering()
        queueEvent {
            rendererImpl.teardown()
            stRef0.set(null)
            stRef1.set(null)
        }
    }

    inner class Renderer : GLSurfaceView.Renderer {
        var running = false

        /** Слот: OES-текстура + SurfaceTexture + матрица + аспект видео в нём. */
        private inner class Slot {
            var oesTex = -1
            var st: SurfaceTexture? = null
            val stMatrix = FloatArray(16)
            var aspect = 16f / 9f
            var loggedAspect = 0f
            @Volatile
            var hasFrame = false
        }

        private val slots = arrayOf(Slot(), Slot())

        @Volatile
        var primary = 0 // какой слот рисуется непрозрачно; второй — с alpha=fade

        @Volatile
        var fade = 0f // 0..1 альфа второго слота (кроссфейд)

        fun slotApplyAspect(slot: Int, sa: Float, bufW: Int, bufH: Int) {
            if (slot !in 0..1) return
            val s = slots[slot]
            s.aspect = sa
            queueEvent {
                runCatching { s.st?.setDefaultBufferSize(bufW, bufH) }
            }
        }

        @Volatile
        var mode = MODE_FILL

        private var oesProgram = 0
        private var blurProgram = 0
        private var finalProgram = 0
        private var sharpProgram = 0
        private var weights = FloatArray(9)

        private var fboA = -1
        private var texA = -1
        private var fboB = -1
        private var texB = -1
        private var fboReady = false
        // вторая пара под блюр второго слота во время кроссфейда
        private var fboC = -1
        private var texC = -1
        private var fboD = -1
        private var texD = -1
        private var fbo2Ready = false

        private var vertexBuf: FloatBuffer? = null
        private var uvBuf: FloatBuffer? = null

        var width = 0
        var height = 0

        private fun quadPos(): FloatBuffer {
            val fb = ByteBuffer.allocateDirect(4 * 2 * 4).order(ByteOrder.nativeOrder()).asFloatBuffer()
            fb.put(floatArrayOf(-1f, -1f, 1f, -1f, -1f, 1f, 1f, 1f))
            fb.position(0)
            return fb
        }

        private fun quadUv(): FloatBuffer {
            val fb = ByteBuffer.allocateDirect(4 * 2 * 4).order(ByteOrder.nativeOrder()).asFloatBuffer()
            fb.put(floatArrayOf(0f, 0f, 1f, 0f, 0f, 1f, 1f, 1f))
            fb.position(0)
            return fb
        }

        override fun onSurfaceCreated(gl: GL10?, config: EGLConfig?) {
            StageLog.i("StageGL.onSurfaceCreated ENTER")
            try {
                GLES20.glDisable(GLES20.GL_DEPTH_TEST)
                GLES20.glDisable(GLES20.GL_CULL_FACE)
                GLES20.glClearColor(0f, 0f, 0f, 1f)

                vertexBuf = quadPos()
                uvBuf = quadUv()

                // свежий GL-контекст: старые имена/ST невалидны
                fboReady = false
                fboA = -1; texA = -1; fboB = -1; texB = -1
                fbo2Ready = false
                fboC = -1; texC = -1; fboD = -1; texD = -1

                for (i in 0..1) {
                    val s = slots[i]
                    runCatching { s.st?.release() }
                    s.st = null
                    val texId = IntArray(1)
                    GLES20.glGenTextures(1, texId, 0)
                    s.oesTex = texId[0]
                    val stn = SurfaceTexture(s.oesTex)
                    stn.setDefaultBufferSize(216, 384)
                    s.hasFrame = false
                    s.st = stn
                    (if (i == 1) stRef1 else stRef0).set(stn)
                    stn.setOnFrameAvailableListener {
                        s.hasFrame = true
                        if (running) requestRender()
                    }
                }

                oesProgram = buildProgram(oesVsh, oesFsh)
                blurProgram = buildProgram(texVsh, blurFsh)
                finalProgram = buildProgram(texVsh, finalFsh)
                sharpProgram = buildProgram(texVsh, sharpFsh)

                // веса гаусса: 9-tap, sigma ≈ 1.6
                var sum = 0f
                for (i in 0..8) {
                    val d = i - 4
                    val w = Math.exp((-d * d / (2 * 1.6 * 1.6)).toDouble()).toFloat()
                    weights[i] = w
                    sum += w
                }
                for (i in 0..8) weights[i] /= sum

                StageLog.i("StageGL surface created (oes tex=${slots[0].oesTex},+${slots[1].oesTex}, mode=$mode, version=${GLES20.glGetString(GLES20.GL_VERSION)})")
            } catch (t: Throwable) {
                StageLog.e("StageGL onSurfaceCreated", t)
            }
        }

        override fun onSurfaceChanged(gl: GL10?, width: Int, height: Int) {
            this.width = width
            this.height = height
            GLES20.glViewport(0, 0, width, height)
            createSmallFramebuffers()
            StageLog.i("StageGL surface changed ${width}x${height}")
        }

        private fun createSmallFramebuffers(): Boolean {
            if (fboReady) return true
            val fbos = IntArray(2)
            val texs = IntArray(2)
            GLES20.glGenFramebuffers(2, fbos, 0)
            GLES20.glGenTextures(2, texs, 0)
            var ok = fbos[0] != 0 && fbos[1] != 0 && texs[0] != 0 && texs[1] != 0
            if (ok) ok = setupTex(texs[0], fbos[0]) && setupTex(texs[1], fbos[1])
            if (ok) {
                fboA = fbos[0]; texA = texs[0]
                fboB = fbos[1]; texB = texs[1]
                fboReady = true
                StageLog.i("StageGL FBOs ready A=$fboA/tex=$texA B=$fboB/tex=$texB (${SMALL_W}x${SMALL_H})")
                return true
            } else {
                StageLog.e("StageGL FBO setup FAILED — fbos=${fbos.joinToString()}, texs=${texs.joinToString()}")
                GLES20.glBindFramebuffer(GLES20.GL_FRAMEBUFFER, 0)
                return false
            }
        }

        /** Вторая пара FBO под блюр следующего слота (лениво, только для кроссфейда). */
        private fun createSecondFramebuffers(): Boolean {
            if (fbo2Ready) return true
            val fbos = IntArray(2)
            val texs = IntArray(2)
            GLES20.glGenFramebuffers(2, fbos, 0)
            GLES20.glGenTextures(2, texs, 0)
            var ok = fbos[0] != 0 && fbos[1] != 0 && texs[0] != 0 && texs[1] != 0
            if (ok) ok = setupTex(texs[0], fbos[0]) && setupTex(texs[1], fbos[1])
            if (ok) {
                fboC = fbos[0]; texC = texs[0]
                fboD = fbos[1]; texD = texs[1]
                fbo2Ready = true
                StageLog.i("StageGL FBOs2 ready C=$fboC/tex=$texC D=$fboD/tex=$texD (${SMALL_W}x${SMALL_H})")
                return true
            }
            StageLog.e("StageGL FBO2 setup FAILED")
            GLES20.glBindFramebuffer(GLES20.GL_FRAMEBUFFER, 0)
            return false
        }

        private fun setupTex(tex: Int, fbo: Int): Boolean {
            GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, tex)
            GLES20.glTexImage2D(
                GLES20.GL_TEXTURE_2D, 0, GLES20.GL_RGBA, SMALL_W, SMALL_H, 0,
                GLES20.GL_RGBA, GLES20.GL_UNSIGNED_BYTE, null
            )
            GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_MIN_FILTER, GLES20.GL_LINEAR)
            GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_MAG_FILTER, GLES20.GL_LINEAR)
            GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_WRAP_S, GLES20.GL_CLAMP_TO_EDGE)
            GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_WRAP_T, GLES20.GL_CLAMP_TO_EDGE)
            GLES20.glBindFramebuffer(GLES20.GL_FRAMEBUFFER, fbo)
            GLES20.glFramebufferTexture2D(
                GLES20.GL_FRAMEBUFFER, GLES20.GL_COLOR_ATTACHMENT0, GLES20.GL_TEXTURE_2D, tex, 0
            )
            val status = GLES20.glCheckFramebufferStatus(GLES20.GL_FRAMEBUFFER)
            GLES20.glBindFramebuffer(GLES20.GL_FRAMEBUFFER, 0)
            return status == GLES20.GL_FRAMEBUFFER_COMPLETE
        }

        /** [tx, ty] cover-масштаб: во весь экран без искажений (лишнее обрезается). */
        private fun coverScale(sa: Float, ta: Float): FloatArray {
            val tx = if (sa > ta) ta / sa else 1f
            val ty = if (sa > ta) 1f else sa / ta
            return floatArrayOf(tx, ty)
        }

        /** [fx, fy] доля экрана, занимаемая видео при aspect-fit (всё видео видно). */
        private fun fitBox(sa: Float, ta: Float): FloatArray {
            val fx = if (sa > ta) 1f else sa / ta
            val fy = if (sa > ta) ta / sa else 1f
            return floatArrayOf(fx, fy)
        }

        private fun drawSharp(s: Slot, box: FloatArray, scale: FloatArray, alpha: Float, where: String) {
            GLES20.glUseProgram(sharpProgram)
            GLES20.glActiveTexture(GLES20.GL_TEXTURE0)
            GLES20.glBindTexture(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, s.oesTex)
            GLES20.glUniformMatrix4fv(
                GLES20.glGetUniformLocation(sharpProgram, "u_st"), 1, false, s.stMatrix, 0
            )
            GLES20.glUniform2f(GLES20.glGetUniformLocation(sharpProgram, "u_box"), box[0], box[1])
            GLES20.glUniform2f(GLES20.glGetUniformLocation(sharpProgram, "u_scale"), scale[0], scale[1])
            GLES20.glUniform1f(GLES20.glGetUniformLocation(sharpProgram, "u_alpha"), alpha)
            drawQuad(sharpProgram)
            checkGlError(where)
        }

        /** Пайплайн блюра одного слота: OES→small→blurH→blurV→растяжка на экран (с alpha). */
        private fun drawBgPipeline(
            s: Slot, sa: Float, ta: Float,
            f1: Int, f2: Int, s1: Int, s2: Int,
            helpWhere: String, screenAlpha: Float
        ) {
            // pass 1: OES → small, cover-crop ПОСЛЕ stMatrix
            GLES20.glBindFramebuffer(GLES20.GL_FRAMEBUFFER, f1)
            GLES20.glViewport(0, 0, SMALL_W, SMALL_H)
            GLES20.glUseProgram(oesProgram)
            GLES20.glActiveTexture(GLES20.GL_TEXTURE0)
            GLES20.glBindTexture(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, s.oesTex)
            GLES20.glUniformMatrix4fv(
                GLES20.glGetUniformLocation(oesProgram, "u_st"), 1, false, s.stMatrix, 0
            )
            val cs = coverScale(sa, ta)
            if (sa != s.loggedAspect) {
                s.loggedAspect = sa
                StageLog.i("StageGL crop sa=$sa tgt=$ta tx=${cs[0]} ty=${cs[1]} mode=$MODE_FILL")
            }
            GLES20.glUniform2f(GLES20.glGetUniformLocation(oesProgram, "u_scale"), cs[0], cs[1])
            drawQuad(oesProgram)
            checkGlError(helpWhere + "1")

            // pass 2: горизонтальный blur → f2
            GLES20.glBindFramebuffer(GLES20.GL_FRAMEBUFFER, f2)
            GLES20.glViewport(0, 0, SMALL_W, SMALL_H)
            GLES20.glUseProgram(blurProgram)
            GLES20.glActiveTexture(GLES20.GL_TEXTURE0)
            GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, s1)
            GLES20.glUniform2f(GLES20.glGetUniformLocation(blurProgram, "u_texel"), 1f / SMALL_W, 0f)
            GLES20.glUniform1fv(GLES20.glGetUniformLocation(blurProgram, "u_w"), 9, weights, 0)
            drawQuad(blurProgram)
            checkGlError(helpWhere + "2")

            // pass 3: вертикальный blur → s1
            GLES20.glBindFramebuffer(GLES20.GL_FRAMEBUFFER, f1)
            GLES20.glViewport(0, 0, SMALL_W, SMALL_H)
            GLES20.glUseProgram(blurProgram)
            GLES20.glActiveTexture(GLES20.GL_TEXTURE0)
            GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, s2)
            GLES20.glUniform2f(GLES20.glGetUniformLocation(blurProgram, "u_texel"), 0f, 1f / SMALL_H)
            GLES20.glUniform1fv(GLES20.glGetUniformLocation(blurProgram, "u_w"), 9, weights, 0)
            drawQuad(blurProgram)
            checkGlError(helpWhere + "3")

            // pass 4: small → экран (растяжка блюра, alpha для кроссфейда)
            GLES20.glBindFramebuffer(GLES20.GL_FRAMEBUFFER, 0)
            GLES20.glViewport(0, 0, width, height)
            GLES20.glUseProgram(finalProgram)
            GLES20.glActiveTexture(GLES20.GL_TEXTURE0)
            GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, s1)
            GLES20.glUniform1f(GLES20.glGetUniformLocation(finalProgram, "u_alpha"), screenAlpha)
            drawQuad(finalProgram)
            checkGlError(helpWhere + "4bg")
        }

        private fun updateSt(s: Slot) {
            val st = s.st ?: return
            runCatching { st.updateTexImage() }
            st.getTransformMatrix(s.stMatrix)
        }

        private fun slotReady(s: Slot): Boolean = s.st != null && s.hasFrame

        fun slotFrame(slot: Int): Boolean = slots[slot].hasFrame

        override fun onDrawFrame(gl: GL10?) {
            if (!running || width == 0 || height == 0) return
            try {
                val p = slots[primary]
                if (p.st == null) return
                val sec = slots[1 - primary]
                val f = fade
                // кроссфейд активен: есть что смешивать и оба слота с кадрами
                val fading = (f > 0.001f) && slotReady(sec)

                updateSt(p)
                if (fading) updateSt(sec)

                val ta = width.toFloat() / height.toFloat()
                val m = mode

                GLES20.glDisable(GLES20.GL_BLEND)

                if (m == MODE_FILL && fboOrTry()) {
                    drawBgPipeline(p, p.aspect, ta, fboA, fboB, texA, texB, "pass", 1f)
                    drawSharp(p, fitBox(p.aspect, ta), floatArrayOf(1f, 1f), 1f, "pass5fill")
                    if (fading && fbo2OrTry()) {
                        GLES20.glEnable(GLES20.GL_BLEND)
                        GLES20.glBlendFunc(GLES20.GL_SRC_ALPHA, GLES20.GL_ONE_MINUS_SRC_ALPHA)
                        checkGlError("fadeBlendOn")
                        drawBgPipeline(sec, sec.aspect, ta, fboC, fboD, texC, texD, "pass2", f)
                        drawSharp(sec, fitBox(sec.aspect, ta), floatArrayOf(1f, 1f), f, "passDfill")
                        GLES20.glDisable(GLES20.GL_BLEND)
                    }
                    return
                }

                // fill без FBO (fallback), fit или crop — в дефолтный фреймбуфер
                GLES20.glBindFramebuffer(GLES20.GL_FRAMEBUFFER, 0)
                GLES20.glViewport(0, 0, width, height)
                GLES20.glClear(GLES20.GL_COLOR_BUFFER_BIT)
                when (m) {
                    MODE_CROP -> {
                        drawSharp(p, floatArrayOf(1f, 1f), coverScale(p.aspect, ta), 1f, "passCrop")
                        if (fading) {
                            GLES20.glEnable(GLES20.GL_BLEND)
                            GLES20.glBlendFunc(GLES20.GL_SRC_ALPHA, GLES20.GL_ONE_MINUS_SRC_ALPHA)
                            drawSharp(sec, floatArrayOf(1f, 1f), coverScale(sec.aspect, ta), f, "passDcrop")
                            GLES20.glDisable(GLES20.GL_BLEND)
                        }
                    }
                    else -> {
                        drawSharp(p, fitBox(p.aspect, ta), floatArrayOf(1f, 1f), 1f, "passFit")
                        if (fading) {
                            GLES20.glEnable(GLES20.GL_BLEND)
                            GLES20.glBlendFunc(GLES20.GL_SRC_ALPHA, GLES20.GL_ONE_MINUS_SRC_ALPHA)
                            drawSharp(sec, fitBox(sec.aspect, ta), floatArrayOf(1f, 1f), f, "passDfit")
                            GLES20.glDisable(GLES20.GL_BLEND)
                        }
                    }
                }
            } catch (t: Throwable) {
                StageLog.e("StageGL onDrawFrame", t)
                running = false
            }
        }

        private fun fboOrTry(): Boolean {
            if (!fboReady && !createSmallFramebuffers()) return false
            return true
        }

        private fun fbo2OrTry(): Boolean {
            if (!fbo2Ready && !createSecondFramebuffers()) return false
            return true
        }

        private fun checkGlError(where: String) {
            val e = GLES20.glGetError()
            if (e != GLES20.GL_NO_ERROR) StageLog.e("StageGL $where err=0x${Integer.toHexString(e)}")
        }

        private fun drawQuad(program: Int) {
            val pos = GLES20.glGetAttribLocation(program, "a_pos")
            val uv = GLES20.glGetAttribLocation(program, "a_uv")
            vertexBuf?.position(0)
            uvBuf?.position(0)
            GLES20.glVertexAttribPointer(pos, 2, GLES20.GL_FLOAT, false, 0, vertexBuf)
            GLES20.glEnableVertexAttribArray(pos)
            GLES20.glVertexAttribPointer(uv, 2, GLES20.GL_FLOAT, false, 0, uvBuf)
            GLES20.glEnableVertexAttribArray(uv)
            GLES20.glDrawArrays(GLES20.GL_TRIANGLE_STRIP, 0, 4)
        }

        fun teardown() {
            running = false
            for (s in slots) {
                runCatching { s.st?.release() }
                s.st = null
            }
            fboReady = false
            fbo2Ready = false
        }
    }

    // ---------- shaders ----------

    private val oesVsh = """
        attribute vec2 a_pos;
        attribute vec2 a_uv;
        varying vec2 v_uv;
        void main() {
            gl_Position = vec4(a_pos, 0.0, 1.0);
            v_uv = a_uv;
        }
    """.trimIndent()

    private val oesFsh = """
        #extension GL_OES_EGL_image_external : require
        precision mediump float;
        varying vec2 v_uv;
        uniform samplerExternalOES s_tex;
        uniform mat4 u_st;
        uniform vec2 u_scale;
        void main() {
            vec2 st = (u_st * vec4(v_uv, 0.0, 1.0)).xy;
            st = 0.5 + (st - 0.5) * u_scale;
            gl_FragColor = texture2D(s_tex, st);
        }
    """.trimIndent()

    private val texVsh = """
        attribute vec2 a_pos;
        attribute vec2 a_uv;
        varying vec2 v_uv;
        void main() {
            gl_Position = vec4(a_pos, 0.0, 1.0);
            v_uv = a_uv;
        }
    """.trimIndent()

    private val blurFsh = """
        precision mediump float;
        varying vec2 v_uv;
        uniform sampler2D s_tex2;
        uniform vec2 u_texel;
        uniform float u_w[9];
        void main() {
            vec3 c = vec3(0.0);
            for (int i = 0; i < 9; i++) {
                float o = float(i - 4);
                c += u_w[i] * texture2D(s_tex2, v_uv + u_texel * o).rgb;
            }
            gl_FragColor = vec4(c, 1.0);
        }
    """.trimIndent()

    private val finalFsh = """
        precision mediump float;
        varying vec2 v_uv;
        uniform sampler2D s_tex2;
        uniform float u_alpha;
        void main() {
            vec3 c = texture2D(s_tex2, v_uv).rgb;
            gl_FragColor = vec4(c, u_alpha);
        }
    """.trimIndent()

    /** Sharp-пасс: сэмпл того же OES-кадра; u_box — доля экрана с видео, u_scale — cover-кадрирование. */
    private val sharpFsh = """
        #extension GL_OES_EGL_image_external : require
        precision mediump float;
        varying vec2 v_uv;
        uniform samplerExternalOES s_tex;
        uniform mat4 u_st;
        uniform vec2 u_box;
        uniform vec2 u_scale;
        uniform float u_alpha;
        void main() {
            vec2 uvn = (v_uv - 0.5) / u_box + 0.5;
            if (any(lessThan(uvn, vec2(0.0))) || any(greaterThan(uvn, vec2(1.0)))) discard;
            vec2 st = (u_st * vec4(uvn, 0.0, 1.0)).xy;
            st = 0.5 + (st - 0.5) * u_scale;
            vec3 c = texture2D(s_tex, st).rgb;
            gl_FragColor = vec4(c, u_alpha);
        }
    """.trimIndent()

    private fun buildProgram(vs: String, fs: String): Int {
        val v = shader(GLES20.GL_VERTEX_SHADER, vs)
        val f = shader(GLES20.GL_FRAGMENT_SHADER, fs)
        val p = GLES20.glCreateProgram()
        GLES20.glAttachShader(p, v)
        GLES20.glAttachShader(p, f)
        GLES20.glLinkProgram(p)
        val stat = IntArray(1)
        GLES20.glGetProgramiv(p, GLES20.GL_LINK_STATUS, stat, 0)
        if (stat[0] != GLES20.GL_TRUE) {
            val log = GLES20.glGetProgramInfoLog(p)
            throw IllegalStateException("GL link failed: $log")
        }
        return p
    }

    private fun shader(type: Int, src: String): Int {
        val s = GLES20.glCreateShader(type)
        GLES20.glShaderSource(s, src)
        GLES20.glCompileShader(s)
        val stat = IntArray(1)
        GLES20.glGetShaderiv(s, GLES20.GL_COMPILE_STATUS, stat, 0)
        if (stat[0] != GLES20.GL_TRUE) {
            val log = GLES20.glGetShaderInfoLog(s)
            throw IllegalStateException("GL compile failed: $log")
        }
        return s
    }
}
