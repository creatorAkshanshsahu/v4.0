package com.aktv.app

import android.animation.ValueAnimator
import android.content.Context
import android.graphics.*
import android.graphics.drawable.BitmapDrawable
import android.graphics.drawable.Drawable
import android.os.SystemClock
import android.util.AttributeSet
import android.view.View
import android.view.ViewGroup
import android.view.animation.DecelerateInterpolator
import android.view.animation.LinearInterpolator
import androidx.core.graphics.ColorUtils
import androidx.palette.graphics.Palette
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import kotlin.math.cos
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sin

/** Brand palette + colour extraction from channel logos. */
object Accent {
    val ABYSS = 0xFF05060D.toInt()
    val SIGNAL = 0xFF3D8BFF.toInt()
    val ION = 0xFF7FE7FF.toInt()
    val INDIGO = 0xFF4B3BD6.toInt()
    val DEEP = 0xFF1B1A6B.toInt()
    val LIVE = 0xFFFF375F.toInt()
    val TEXT = 0xFFF4F6FF.toInt()
    val DIM = 0xFF9AA0C3.toInt()
    val FAINT = 0xFF5F6590.toInt()

    /** Makes any logo colour usable as a glow: saturated, mid-light. Greys fall back to brand blue. */
    fun normalize(c: Int?): Int {
        if (c == null) return SIGNAL
        val hsl = FloatArray(3); ColorUtils.colorToHSL(c, hsl)
        if (hsl[1] < 0.18f) return SIGNAL
        hsl[1] = max(hsl[1], 0.65f); hsl[2] = hsl[2].coerceIn(0.48f, 0.62f)
        return ColorUtils.HSLToColor(hsl)
    }

    /** A neighbouring hue so the backdrop has depth instead of one flat colour. */
    fun partner(c: Int): Int {
        val hsl = FloatArray(3); ColorUtils.colorToHSL(c, hsl)
        hsl[0] = (hsl[0] + 48f) % 360f; hsl[2] = 0.42f
        return ColorUtils.HSLToColor(hsl)
    }

    /** Stable colour for channels that have no logo to sample (V2 / 24-7 channels): same name, same glow. */
    fun fromName(name: String): Int {
        var h = 7
        for (ch in name.lowercase()) h = (h * 31 + ch.code) and 0x7fffffff
        return ColorUtils.HSLToColor(floatArrayOf((h % 360).toFloat(), 0.72f, 0.56f))
    }

    fun from(d: Drawable, cb: (Int) -> Unit) {
        val bmp = (d as? BitmapDrawable)?.bitmap
        if (bmp == null || bmp.config == Bitmap.Config.HARDWARE) { cb(SIGNAL); return }
        Palette.from(bmp).maximumColorCount(16).generate { p ->
            val sw = p?.vibrantSwatch ?: p?.lightVibrantSwatch ?: p?.darkVibrantSwatch ?: p?.dominantSwatch
            cb(normalize(sw?.rgb))
        }
    }
}

/**
 * Frosted-glass surface: translucent fill, a soft top sheen and a lit edge that
 * picks up the item's accent colour when focused. Focus glides in and out (no hard
 * switch) and nothing is allocated while it animates, so it stays smooth on weak TV chips.
 * radiusDp < 0 gives a pill / circle.
 */
class GlassDrawable(private val dp: Float, private val radiusDp: Float = -1f) : Drawable() {
    private val fillP = Paint(Paint.ANTI_ALIAS_FLAG)
    private val sheenP = Paint(Paint.ANTI_ALIAS_FLAG)
    private val poolP = Paint(Paint.ANTI_ALIAS_FLAG)
    private val edgeW = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE }
    private val edgeA = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE }
    private val r = RectF()
    private var rad = 0f
    private var p = 0f                       // focus progress 0..1
    private var foc = false
    private var anim: ValueAnimator? = null
    private var tint = ColorUtils.setAlphaComponent(Accent.SIGNAL, 70)

    var accent: Int = Accent.SIGNAL
        set(v) { if (field != v) { field = v; tint = ColorUtils.setAlphaComponent(v, 70); buildAccent(); invalidateSelf() } }
    var focused: Boolean
        get() = foc
        set(v) { if (foc != v) { foc = v; glide(if (v) 1f else 0f) } }
    var base: Int = Color.argb(20, 255, 255, 255)
        set(v) { field = v; invalidateSelf() }
    var showFill = true
    /** false = only drawn while focused (used for list rows in the channel guide). */
    var idleVisible = true
        set(v) { if (field != v) { field = v; invalidateSelf() } }

    /** Set the focus state instantly (used when a recycled row is bound). */
    fun snap(f: Boolean) { anim?.cancel(); foc = f; p = if (f) 1f else 0f; invalidateSelf() }

    private fun glide(to: Float) {
        anim?.cancel()
        if (bounds.isEmpty) { p = to; invalidateSelf(); return }
        anim = ValueAnimator.ofFloat(p, to).apply {
            duration = if (to > p) 170 else 240
            interpolator = DecelerateInterpolator(1.5f)
            addUpdateListener { p = it.animatedValue as Float; invalidateSelf() }
            start()
        }
    }

    private fun radiusFor(b: Rect) = if (radiusDp < 0) min(b.width(), b.height()) / 2f else radiusDp * dp

    override fun onBoundsChange(b: Rect) {
        rad = radiusFor(b)
        sheenP.shader = LinearGradient(0f, b.top.toFloat(), 0f, b.top + b.height() * 0.6f,
            Color.WHITE, Color.TRANSPARENT, Shader.TileMode.CLAMP)
        edgeW.shader = LinearGradient(b.left.toFloat(), b.top.toFloat(), b.right.toFloat(), b.bottom.toFloat(),
            intArrayOf(Color.WHITE, Color.argb(0, 255, 255, 255), Color.argb(140, 255, 255, 255)),
            floatArrayOf(0f, 0.55f, 1f), Shader.TileMode.CLAMP)
        buildAccent()
    }

    private fun buildAccent() {
        val b = bounds; if (b.isEmpty) return
        val a = accent
        edgeA.shader = LinearGradient(b.left.toFloat(), b.top.toFloat(), b.right.toFloat(), b.bottom.toFloat(),
            intArrayOf(ColorUtils.setAlphaComponent(a, 0), ColorUtils.setAlphaComponent(a, 255), ColorUtils.setAlphaComponent(a, 0)),
            floatArrayOf(0f, 0.55f, 1f), Shader.TileMode.CLAMP)
        // light pooling in the lower half of a focused surface, like light through liquid glass
        poolP.shader = LinearGradient(0f, b.top + b.height() * 0.4f, 0f, b.bottom.toFloat(),
            ColorUtils.setAlphaComponent(a, 0), ColorUtils.setAlphaComponent(a, 255), Shader.TileMode.CLAMP)
    }

    override fun draw(c: Canvas) {
        val vis = if (idleVisible) 1f else p
        if (vis <= 0.004f) return
        val b = bounds; if (b.isEmpty) return
        val sw = (1f + p) * dp
        r.set(b.left + sw / 2, b.top + sw / 2, b.right - sw / 2, b.bottom - sw / 2)
        if (showFill) {
            val col = ColorUtils.blendARGB(base, tint, 0.6f * p)
            fillP.color = col
            fillP.alpha = (Color.alpha(col) * vis).toInt()
            c.drawRoundRect(r, rad, rad, fillP)
        }
        sheenP.alpha = ((22 + 18 * p) * vis).toInt()
        c.drawRoundRect(r, rad, rad, sheenP)
        if (p > 0.01f) {
            poolP.alpha = (64 * p * vis).toInt()
            c.drawRoundRect(r, rad, rad, poolP)
        }
        edgeW.strokeWidth = sw; edgeW.alpha = ((80 + 175 * p) * vis).toInt()
        c.drawRoundRect(r, rad, rad, edgeW)
        edgeA.strokeWidth = sw; edgeA.alpha = ((30 + 170 * p) * vis).toInt()
        c.drawRoundRect(r, rad, rad, edgeA)
    }
    override fun setAlpha(a: Int) {}
    override fun setColorFilter(cf: ColorFilter?) {}
    @Deprecated("Deprecated in Java")
    override fun getOpacity() = PixelFormat.TRANSLUCENT
    override fun getOutline(o: Outline) { o.setRoundRect(bounds, radiusFor(bounds)); o.alpha = 0.9f }
}

/** Living backdrop: three slow-drifting light fields that flow into the focused item's colours. */
class AuroraView @JvmOverloads constructor(c: Context, a: AttributeSet? = null) : View(c, a) {
    private val cols = intArrayOf(Accent.SIGNAL, Accent.INDIGO, Accent.DEEP)
    // x, y (fractions of size), radius (fraction of width), phase
    private val blobs = arrayOf(
        floatArrayOf(0.80f, 0.22f, 0.70f, 0f),
        floatArrayOf(0.08f, 0.00f, 0.55f, 2.1f),
        floatArrayOf(0.45f, 1.10f, 0.80f, 4.2f))
    private val alphas = intArrayOf(150, 105, 120)
    private val shaders = arrayOfNulls<RadialGradient>(3)
    private val filters = arrayOfNulls<PorterDuffColorFilter>(3)
    private val m = Matrix()
    private val p = Paint()
    private var t = 0f
    private var last = 0L
    private var colorAnim: ValueAnimator? = null
    private val drift = ValueAnimator.ofFloat(0f, (Math.PI * 2).toFloat()).apply {
        duration = 48000; repeatCount = ValueAnimator.INFINITE; interpolator = LinearInterpolator()
        addUpdateListener {
            t = it.animatedValue as Float
            val now = SystemClock.uptimeMillis()
            if (now - last > 40) { last = now; invalidate() }   // ~25fps is plenty for slow light
        }
    }

    fun setPalette(a: Int, b: Int) {
        val fromA = cols[0]; val fromB = cols[1]
        if (fromA == a && fromB == b) return
        colorAnim?.cancel()
        colorAnim = ValueAnimator.ofFloat(0f, 1f).apply {
            duration = 900; interpolator = DecelerateInterpolator()
            addUpdateListener {
                val f = it.animatedValue as Float
                cols[0] = ColorUtils.blendARGB(fromA, a, f); cols[1] = ColorUtils.blendARGB(fromB, b, f)
                refilter(); invalidate()
            }
            start()
        }
    }

    fun pause() { if (drift.isRunning) drift.pause() }
    fun resume() { if (drift.isPaused) drift.resume() else if (!drift.isStarted && isAttachedToWindow) drift.start() }

    override fun onAttachedToWindow() { super.onAttachedToWindow(); if (!drift.isStarted) drift.start() }
    override fun onDetachedFromWindow() { drift.cancel(); colorAnim?.cancel(); super.onDetachedFromWindow() }
    override fun onSizeChanged(w: Int, h: Int, ow: Int, oh: Int) { rebuild() }

    // Shaders are white-with-alpha and built once per size; the colour comes from a cheap colour filter,
    // so a palette change costs three small objects instead of three gradient rebuilds per frame.
    private fun refilter() { for (i in 0..2) filters[i] = PorterDuffColorFilter(cols[i], PorterDuff.Mode.SRC_IN) }

    private fun rebuild() {
        if (width == 0) return
        for (i in 0..2) {
            val rad = blobs[i][2] * width
            shaders[i] = RadialGradient(0f, 0f, rad,
                intArrayOf(Color.argb(alphas[i], 255, 255, 255), Color.argb(alphas[i] / 3, 255, 255, 255), Color.TRANSPARENT),
                floatArrayOf(0f, 0.5f, 1f), Shader.TileMode.CLAMP)
        }
        refilter()
    }

    override fun onDraw(cv: Canvas) {
        cv.drawColor(Accent.ABYSS)
        val w = width.toFloat(); val h = height.toFloat()
        for (i in 0..2) {
            val s = shaders[i] ?: continue; val b = blobs[i]
            val cx = w * (b[0] + 0.05f * sin(t + b[3])); val cy = h * (b[1] + 0.07f * cos(2 * t + b[3]))
            m.setTranslate(cx, cy); s.setLocalMatrix(m); p.shader = s; p.colorFilter = filters[i]
            cv.drawRect(0f, 0f, w, h, p)
        }
    }
}

/** Keeps the focused item pinned to the start edge, like Netflix rows. */
class SnapLayoutManager(c: Context, orientation: Int) : LinearLayoutManager(c, orientation, false) {
    override fun requestChildRectangleOnScreen(parent: RecyclerView, child: View, rect: Rect,
                                               immediate: Boolean, focusedChildVisible: Boolean): Boolean {
        val lp = child.layoutParams as ViewGroup.MarginLayoutParams
        val d = if (orientation == RecyclerView.HORIZONTAL) child.left - lp.leftMargin - parent.paddingLeft
                else child.top - lp.topMargin - parent.paddingTop
        if (d == 0) return false
        if (orientation == RecyclerView.HORIZONTAL) { if (immediate) parent.scrollBy(d, 0) else parent.smoothScrollBy(d, 0) }
        else { if (immediate) parent.scrollBy(0, d) else parent.smoothScrollBy(0, d) }
        return true
    }
}
