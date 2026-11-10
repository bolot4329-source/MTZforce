package com.bluequark.mtzforge.core

import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt

/** Простое изображение: пиксели ARGB (не premultiplied), построчно. */
class Raster(val width: Int, val height: Int, val pixels: IntArray = IntArray(width * height)) {

    init {
        require(width > 0 && height > 0) { "Пустое изображение" }
        require(pixels.size == width * height)
    }

    operator fun get(x: Int, y: Int): Int = pixels[y * width + x]
    operator fun set(x: Int, y: Int, v: Int) { pixels[y * width + x] = v }

    fun copy(): Raster = Raster(width, height, pixels.copyOf())

    /** Кладёт изображение по центру прозрачного квадрата. */
    fun toSquare(): Raster {
        if (width == height) return this
        val s = max(width, height)
        val out = Raster(s, s)
        val ox = (s - width) / 2
        val oy = (s - height) / 2
        for (y in 0 until height) {
            System.arraycopy(pixels, y * width, out.pixels, (y + oy) * s + ox, width)
        }
        return out
    }

    /** Масштабирование: уменьшение — усреднением по площади, увеличение — билинейно. */
    fun scaleTo(w: Int, h: Int): Raster {
        if (w == width && h == height) return this
        return if (w <= width && h <= height) downscale(w, h) else bilinear(w, h)
    }

    private fun downscale(w: Int, h: Int): Raster {
        val out = Raster(w, h)
        val sx = width.toDouble() / w
        val sy = height.toDouble() / h
        for (oy in 0 until h) {
            val y0 = oy * sy
            val y1 = y0 + sy
            for (ox in 0 until w) {
                val x0 = ox * sx
                val x1 = x0 + sx
                var aSum = 0.0; var rSum = 0.0; var gSum = 0.0; var bSum = 0.0; var wSum = 0.0
                var yy = y0.toInt()
                while (yy < y1 && yy < height) {
                    val wy = min(y1, yy + 1.0) - max(y0, yy.toDouble())
                    var xx = x0.toInt()
                    while (xx < x1 && xx < width) {
                        val wx = min(x1, xx + 1.0) - max(x0, xx.toDouble())
                        val wt = wx * wy
                        if (wt > 0) {
                            val p = pixels[yy * width + xx]
                            val a = (p ushr 24) * wt
                            aSum += a
                            rSum += ((p shr 16) and 0xFF) * a
                            gSum += ((p shr 8) and 0xFF) * a
                            bSum += (p and 0xFF) * a
                            wSum += wt
                        }
                        xx++
                    }
                    yy++
                }
                out.pixels[oy * w + ox] = if (aSum <= 0.0 || wSum <= 0.0) 0 else argb(
                    (aSum / wSum).roundToInt(),
                    (rSum / aSum).roundToInt(),
                    (gSum / aSum).roundToInt(),
                    (bSum / aSum).roundToInt()
                )
            }
        }
        return out
    }

    private fun bilinear(w: Int, h: Int): Raster {
        val out = Raster(w, h)
        val sx = width.toDouble() / w
        val sy = height.toDouble() / h
        for (oy in 0 until h) {
            val fy = ((oy + 0.5) * sy - 0.5).coerceIn(0.0, (height - 1).toDouble())
            val y0 = fy.toInt(); val y1 = min(y0 + 1, height - 1); val ty = fy - y0
            for (ox in 0 until w) {
                val fx = ((ox + 0.5) * sx - 0.5).coerceIn(0.0, (width - 1).toDouble())
                val x0 = fx.toInt(); val x1 = min(x0 + 1, width - 1); val tx = fx - x0
                val p00 = pixels[y0 * width + x0]; val p10 = pixels[y0 * width + x1]
                val p01 = pixels[y1 * width + x0]; val p11 = pixels[y1 * width + x1]
                val w00 = (1 - tx) * (1 - ty); val w10 = tx * (1 - ty)
                val w01 = (1 - tx) * ty; val w11 = tx * ty
                val a00 = (p00 ushr 24) * w00; val a10 = (p10 ushr 24) * w10
                val a01 = (p01 ushr 24) * w01; val a11 = (p11 ushr 24) * w11
                val a = a00 + a10 + a01 + a11
                if (a <= 0.0) { out.pixels[oy * w + ox] = 0; continue }
                fun ch(sh: Int) = (((p00 shr sh) and 0xFF) * a00 + ((p10 shr sh) and 0xFF) * a10 +
                        ((p01 shr sh) and 0xFF) * a01 + ((p11 shr sh) and 0xFF) * a11) / a
                out.pixels[oy * w + ox] = argb(a.roundToInt(), ch(16).roundToInt(), ch(8).roundToInt(), ch(0).roundToInt())
            }
        }
        return out
    }

    /** Рисует src поверх этого изображения в точке (dx, dy) — обычное наложение. */
    fun drawOver(src: Raster, dx: Int, dy: Int) {
        for (y in 0 until src.height) {
            val ty = y + dy
            if (ty < 0 || ty >= height) continue
            for (x in 0 until src.width) {
                val tx = x + dx
                if (tx < 0 || tx >= width) continue
                val i = ty * width + tx
                pixels[i] = blendOver(pixels[i], src.pixels[y * src.width + x], 255)
            }
        }
    }

    companion object {
        fun argb(a: Int, r: Int, g: Int, b: Int): Int =
            (a.coerceIn(0, 255) shl 24) or (r.coerceIn(0, 255) shl 16) or (g.coerceIn(0, 255) shl 8) or b.coerceIn(0, 255)

        /** Наложение src поверх dst (оба не premultiplied), extraAlpha 0..255. */
        fun blendOver(dst: Int, src: Int, extraAlpha: Int): Int {
            val sa = (src ushr 24) * extraAlpha / 255
            if (sa == 0) return dst
            val da = dst ushr 24
            if (sa == 255 || da == 0) return (sa shl 24) or (src and 0xFFFFFF)
            val outA = sa + da * (255 - sa) / 255
            fun c(sh: Int): Int {
                val s = (src shr sh) and 0xFF
                val d = (dst shr sh) and 0xFF
                return (s * sa + d * da * (255 - sa) / 255) / outA
            }
            return argb(outA, c(16), c(8), c(0))
        }

        /** Сетка-превью из значков. */
        fun grid(icons: List<Raster>, cols: Int, cell: Int, gap: Int, background: Int): Raster {
            val rows = max(1, (icons.size + cols - 1) / cols)
            val w = cols * cell + (cols + 1) * gap
            val h = rows * cell + (rows + 1) * gap
            val out = Raster(w, h)
            out.pixels.fill(background)
            icons.forEachIndexed { i, r ->
                val c = i % cols; val rr = i / cols
                out.drawOver(r.toSquare().scaleTo(cell, cell), gap + c * (cell + gap), gap + rr * (cell + gap))
            }
            return out
        }
    }
}

/** Кодирование/декодирование PNG — на Android через Bitmap, в тестах через ImageIO. */
interface PngCodec {
    fun decode(bytes: ByteArray): Raster
    fun encode(raster: Raster): ByteArray
}
