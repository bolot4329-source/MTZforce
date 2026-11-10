package com.bluequark.mtzforge.core

import kotlin.math.ceil
import kotlin.math.floor
import kotlin.math.hypot
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sqrt

/**
 * Простое рисование по значку: заливка, кисть, ластик, пипетка, отмена/повтор.
 * Работает прямо с пикселями Raster, без Android — поэтому проверяется тестами.
 */
class IconPainter(start: Raster) {

    var image: Raster = start.copy()
        private set

    private val undo = ArrayDeque<IntArray>()
    private val redo = ArrayDeque<IntArray>()
    private val maxHistory = 15

    val canUndo get() = undo.isNotEmpty()
    val canRedo get() = redo.isNotEmpty()

    private fun snapshot() {
        undo.addLast(image.pixels.copyOf())
        if (undo.size > maxHistory) undo.removeFirst()
        redo.clear()
    }

    fun undo(): Boolean {
        val prev = undo.removeLastOrNull() ?: return false
        redo.addLast(image.pixels.copyOf())
        System.arraycopy(prev, 0, image.pixels, 0, prev.size)
        return true
    }

    fun redo(): Boolean {
        val next = redo.removeLastOrNull() ?: return false
        undo.addLast(image.pixels.copyOf())
        System.arraycopy(next, 0, image.pixels, 0, next.size)
        return true
    }

    /** Цвет под пальцем (null — прозрачно). */
    fun pick(x: Int, y: Int): Int? {
        if (x !in 0 until image.width || y !in 0 until image.height) return null
        val p = image[x, y]
        return if ((p ushr 24) < 16) null else p or (0xFF shl 24)
    }

    // ---------------------------------------------------------------- заливка

    /**
     * Заливка связной области похожего цвета. Внутри области сохраняется фактура (тени, шум),
     * на границе цвет смешивается пропорционально — края остаются гладкими.
     * tolerance — насколько далеко цвет может уйти от цвета под пальцем (0..255).
     */
    fun fill(x: Int, y: Int, color: Int, tolerance: Int = 40): Boolean {
        val w = image.width; val h = image.height
        if (x !in 0 until w || y !in 0 until h) return false
        val px = image.pixels
        val seed = px[y * w + x]
        val target = color or (0xFF shl 24)
        if (seed == target) return false
        snapshot()

        val tol2 = tolerance.toLong() * tolerance
        val region = BooleanArray(w * h)
        val stack = IntArray(w * h)
        var sp = 0
        stack[sp++] = y * w + x
        region[y * w + x] = true
        while (sp > 0) {
            val i = stack[--sp]
            val cx = i % w; val cy = i / w
            fun visit(nx: Int, ny: Int) {
                if (nx < 0 || ny < 0 || nx >= w || ny >= h) return
                val j = ny * w + nx
                if (region[j]) return
                if (dist2(px[j], seed) > tol2) return
                region[j] = true
                stack[sp++] = j
            }
            visit(cx + 1, cy); visit(cx - 1, cy); visit(cx, cy + 1); visit(cx, cy - 1)
        }

        val out = px.copyOf()
        val sa = seed ushr 24; val sr = (seed shr 16) and 0xFF; val sg = (seed shr 8) and 0xFF; val sb = seed and 0xFF
        val ta = 255; val tr = (target shr 16) and 0xFF; val tg = (target shr 8) and 0xFF; val tb = target and 0xFF
        val sTransparent = sa < 16

        for (i in px.indices) {
            if (region[i]) {
                out[i] = if (sTransparent) target else shift(px[i], 1.0, ta - sa, tr - sr, tg - sg, tb - sb)
                continue
            }
            // граница: соседи по 8 направлениям, хотя бы один из области
            val cx = i % w; val cy = i / w
            var touches = false
            var other = px[i]; var otherD = dist2(px[i], seed)
            for (dy in -1..1) for (dx in -1..1) {
                if (dx == 0 && dy == 0) continue
                val nx = cx + dx; val ny = cy + dy
                if (nx < 0 || ny < 0 || nx >= w || ny >= h) continue
                val j = ny * w + nx
                if (region[j]) { touches = true; continue }
                val d = dist2(px[j], seed)
                if (d > otherD) { otherD = d; other = px[j] }
            }
            if (!touches) continue
            // доля «цвета заливки» в пикселе края: проекция на отрезок другой цвет → цвет под пальцем
            val t = fraction(px[i], other, seed)
            if (t <= 0.0) continue
            out[i] = if (sTransparent) {
                // край прозрачной области: добавляем цвет заливки под существующий пиксель
                Raster.blendOver(Raster.argb((255 * t).toInt(), tr, tg, tb), px[i], 255)
            } else shift(px[i], t, ta - sa, tr - sr, tg - sg, tb - sb)
        }
        System.arraycopy(out, 0, px, 0, out.size)
        return true
    }

    private fun shift(p: Int, t: Double, da: Int, dr: Int, dg: Int, db: Int): Int =
        Raster.argb(
            ((p ushr 24) + t * da).toInt(),
            (((p shr 16) and 0xFF) + t * dr).toInt(),
            (((p shr 8) and 0xFF) + t * dg).toInt(),
            ((p and 0xFF) + t * db).toInt()
        )

    private fun fraction(p: Int, a: Int, b: Int): Double {
        val paA = (p ushr 24) - (a ushr 24); val dA = (b ushr 24) - (a ushr 24)
        val paR = ((p shr 16) and 0xFF) - ((a shr 16) and 0xFF); val dR = ((b shr 16) and 0xFF) - ((a shr 16) and 0xFF)
        val paG = ((p shr 8) and 0xFF) - ((a shr 8) and 0xFF); val dG = ((b shr 8) and 0xFF) - ((a shr 8) and 0xFF)
        val paB = (p and 0xFF) - (a and 0xFF); val dB = (b and 0xFF) - (a and 0xFF)
        val num = (paA * dA + paR * dR + paG * dG + paB * dB).toDouble()
        val den = (dA * dA + dR * dR + dG * dG + dB * dB).toDouble()
        return if (den == 0.0) 0.0 else (num / den).coerceIn(0.0, 1.0)
    }

    private fun dist2(a: Int, b: Int): Long {
        val da = (a ushr 24) - (b ushr 24)
        val dr = ((a shr 16) and 0xFF) - ((b shr 16) and 0xFF)
        val dg = ((a shr 8) and 0xFF) - ((b shr 8) and 0xFF)
        val db = (a and 0xFF) - (b and 0xFF)
        return (da * da + dr * dr + dg * dg + db * db).toLong()
    }

    // ---------------------------------------------------------------- кисть и ластик

    private var strokeBase: IntArray? = null
    private var strokeMask: FloatArray? = null
    private var strokeColor = 0
    private var strokeRadius = 8f
    private var strokeErase = false
    private var lastX = 0f
    private var lastY = 0f

    fun beginStroke(x: Float, y: Float, color: Int, radius: Float, erase: Boolean) {
        snapshot()
        strokeBase = image.pixels.copyOf()
        strokeMask = FloatArray(image.pixels.size)
        strokeColor = color or (0xFF shl 24)
        strokeRadius = max(0.5f, radius)
        strokeErase = erase
        lastX = x; lastY = y
        stamp(x, y)
    }

    fun strokeTo(x: Float, y: Float) {
        if (strokeMask == null) return
        val len = hypot(x - lastX, y - lastY)
        val step = max(0.5f, strokeRadius * 0.25f)
        val n = max(1, ceil(len / step).toInt())
        for (k in 1..n) {
            val t = k.toFloat() / n
            stamp(lastX + (x - lastX) * t, lastY + (y - lastY) * t)
        }
        lastX = x; lastY = y
    }

    fun endStroke() {
        strokeBase = null
        strokeMask = null
    }

    /** Круглый отпечаток с мягким краем в 1 px; маска мазка берёт максимум, поэтому края не темнеют. */
    private fun stamp(cx: Float, cy: Float) {
        val base = strokeBase ?: return
        val mask = strokeMask ?: return
        val w = image.width; val h = image.height
        val r = strokeRadius
        val x0 = max(0, floor(cx - r - 1).toInt()); val x1 = min(w - 1, ceil(cx + r + 1).toInt())
        val y0 = max(0, floor(cy - r - 1).toInt()); val y1 = min(h - 1, ceil(cy + r + 1).toInt())
        if (x0 > x1 || y0 > y1) return
        val px = image.pixels
        for (yy in y0..y1) for (xx in x0..x1) {
            val d = sqrt((xx + 0.5f - cx) * (xx + 0.5f - cx) + (yy + 0.5f - cy) * (yy + 0.5f - cy))
            val cov = (r + 0.5f - d).coerceIn(0f, 1f)
            val i = yy * w + xx
            if (cov <= mask[i]) continue
            mask[i] = cov
            px[i] = if (strokeErase) {
                val p = base[i]
                val a = ((p ushr 24) * (1f - cov)).toInt()
                (a shl 24) or (p and 0xFFFFFF)
            } else {
                Raster.blendOver(base[i], strokeColor, (cov * 255f + 0.5f).toInt())
            }
        }
    }
}
