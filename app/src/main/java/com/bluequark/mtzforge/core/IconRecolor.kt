package com.bluequark.mtzforge.core

import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sqrt

/**
 * Цвета значка: palette[0] — самый частый (обычно фон), дальше по убыванию площади:
 * логотип, дополнительный цвет, обводка и т.д. shares — доля площади каждого цвета.
 */
data class IconColors(val palette: List<Int>, val shares: List<Float>) {
    val bg: Int get() = palette.firstOrNull() ?: 0xFF000000.toInt()
    val logo: Int? get() = palette.getOrNull(1)
}

/** Цвет, общий для всей темы (найден во многих значках). */
class ThemeColor(val color: Int, val weight: Double, val icons: Int)

/** Палитра всей темы: основной и дополнительный цвет определяются автоматически. */
class ThemePalette(val colors: List<ThemeColor>, val main: Int?, val accent: Int?)

object IconRecolor {

    const val MAX_COLORS = 6
    /** Насколько разными должны быть два цвета значка, чтобы считаться отдельными. */
    const val SEPARATE = 44.0
    /** Радиус совпадения цвета значка с цветом палитры темы. */
    const val THEME_MATCH = 40.0

    // ---------------------------------------------------------------- анализ

    fun analyze(r: Raster): IconColors {
        // 1) только «сплошные» пиксели (соседи почти того же цвета) — сглаженные края не мешают;
        // 2) если нашёлся один цвет (тонкие линии, градиент) — фильтр слабее; 3) без фильтра.
        for (near in intArrayOf(6, 18, -1)) {
            val res = analyzePass(r, near) ?: continue
            if (res.palette.size >= 2 || near == -1) return res
        }
        return IconColors(listOf(0xFF000000.toInt()), listOf(1f))
    }

    private fun analyzePass(r: Raster, nearTol: Int): IconColors? {
        val bins = 1 shl 15
        val counts = IntArray(bins)
        val sumR = LongArray(bins); val sumG = LongArray(bins); val sumB = LongArray(bins)
        var opaque = 0
        val w = r.width; val h = r.height; val px = r.pixels
        fun near(a: Int, b: Int): Boolean =
            abs(((a shr 16) and 0xFF) - ((b shr 16) and 0xFF)) <= nearTol &&
                    abs(((a shr 8) and 0xFF) - ((b shr 8) and 0xFF)) <= nearTol &&
                    abs((a and 0xFF) - (b and 0xFF)) <= nearTol && (b ushr 24) >= 250
        for (i in px.indices) {
            val p = px[i]
            if ((p ushr 24) < 250) continue
            opaque++
            if (nearTol >= 0) {
                val x = i % w; val y = i / w
                if (x == 0 || y == 0 || x == w - 1 || y == h - 1) continue
                if (!near(p, px[i - 1]) || !near(p, px[i + 1]) || !near(p, px[i - w]) || !near(p, px[i + w])) continue
            }
            val rr = (p shr 16) and 0xFF; val gg = (p shr 8) and 0xFF; val bb = p and 0xFF
            val k = ((rr shr 3) shl 10) or ((gg shr 3) shl 5) or (bb shr 3)
            counts[k]++; sumR[k] += rr.toLong(); sumG[k] += gg.toLong(); sumB[k] += bb.toLong()
        }
        if (opaque == 0 || counts.all { it == 0 }) return null

        val used = (0 until bins).filter { counts[it] > 0 }.sortedByDescending { counts[it] }
        fun avg(k: Int) = Raster.argb(255, (sumR[k] / counts[k]).toInt(), (sumG[k] / counts[k]).toInt(), (sumB[k] / counts[k]).toInt())

        // Жадно набираем заметно разные цвета. Первый — от 0.6% площади, следующие — от 0.2%.
        // Цвета-«смеси» двух уже найденных (сглаженные края) пропускаем, если их немного.
        val minFirst = max(1, opaque * 6 / 1000)
        val minNext = max(1, opaque * 2 / 1000)
        val centers = mutableListOf<Int>()
        for (k in used) {
            if (centers.size >= MAX_COLORS) break
            if (counts[k] < (if (centers.isEmpty()) minFirst else minNext)) break
            val c = avg(k)
            if (centers.any { dist(it, c) < SEPARATE }) continue
            if (counts[k] * 100 < opaque * 3 && isBlendOf(c, centers)) continue
            centers += c
        }
        if (centers.isEmpty()) centers += avg(used.first())

        // Уточняем: каждый цвет = среднее близких к нему пикселей.
        val n = centers.size
        val accR = DoubleArray(n); val accG = DoubleArray(n); val accB = DoubleArray(n); val accN = DoubleArray(n)
        for (k in used) {
            val c = avg(k)
            var bi = 0; var bd = Double.MAX_VALUE
            for (i in 0 until n) { val d = dist(c, centers[i]); if (d < bd) { bd = d; bi = i } }
            if (bd > SEPARATE / 2) continue
            val wt = counts[k].toDouble()
            accR[bi] += sumR[k]; accG[bi] += sumG[k]; accB[bi] += sumB[k]; accN[bi] += wt
        }
        val refined = (0 until n).map { i ->
            if (accN[i] > 0) Raster.argb(255, (accR[i] / accN[i]).toInt(), (accG[i] / accN[i]).toInt(), (accB[i] / accN[i]).toInt()) else centers[i]
        }
        val order = (0 until n).sortedByDescending { accN[it] }
        val total = accN.sum().coerceAtLeast(1.0)
        return IconColors(order.map { refined[it] }, order.map { (accN[it] / total).toFloat() })
    }

    /** Лежит ли цвет на отрезке между двумя уже найденными цветами (это сглаженный край, а не свой цвет). */
    private fun isBlendOf(c: Int, centers: List<Int>): Boolean {
        for (i in centers.indices) for (j in i + 1 until centers.size) {
            val (t, res) = project(c, centers[j], centers[i])
            if (t > 0.12 && t < 0.88 && res < 16.0) return true
        }
        return false
    }

    /** Проекция цвета p на отрезок a→b: доля t (0 = a, 1 = b) и расстояние до отрезка. */
    private fun project(p: Int, a: Int, b: Int): Pair<Double, Double> {
        val pr = ((p shr 16) and 0xFF) - ((a shr 16) and 0xFF).toDouble()
        val pg = ((p shr 8) and 0xFF) - ((a shr 8) and 0xFF).toDouble()
        val pb = (p and 0xFF) - (a and 0xFF).toDouble()
        val dr = ((b shr 16) and 0xFF) - ((a shr 16) and 0xFF).toDouble()
        val dg = ((b shr 8) and 0xFF) - ((a shr 8) and 0xFF).toDouble()
        val db = (b and 0xFF) - (a and 0xFF).toDouble()
        val len2 = dr * dr + dg * dg + db * db
        val t = if (len2 == 0.0) 0.0 else ((pr * dr + pg * dg + pb * db) / len2).coerceIn(0.0, 1.0)
        val rr = pr - t * dr; val rg = pg - t * dg; val rb = pb - t * db
        return t to sqrt(rr * rr + rg * rg + rb * rb)
    }

    // ---------------------------------------------------------------- перекраска

    /**
     * Перекраска любых цветов значка. map: номер цвета в палитре → новый цвет.
     * Каждый пиксель раскладывается на смесь двух цветов палитры (так устроены сглаженные края),
     * поэтому края остаются гладкими, а тени и градиенты — на месте.
     */
    fun recolor(src: Raster, colors: IconColors, map: Map<Int, Int>): Raster {
        val pal = colors.palette
        if (map.isEmpty() || pal.isEmpty() || map.all { (i, c) -> pal.getOrNull(i)?.let { (it and 0xFFFFFF) == (c and 0xFFFFFF) } ?: true }) return src
        val n = pal.size
        val pr = IntArray(n) { (pal[it] shr 16) and 0xFF }; val pg = IntArray(n) { (pal[it] shr 8) and 0xFF }; val pb = IntArray(n) { pal[it] and 0xFF }
        val np = IntArray(n) { map[it] ?: pal[it] }
        val nr = IntArray(n) { (np[it] shr 16) and 0xFF }; val ng = IntArray(n) { (np[it] shr 8) and 0xFF }; val nb = IntArray(n) { np[it] and 0xFF }

        // Кэш по цвету: у значков большие одноцветные области
        val cacheKeys = IntArray(1 shl 14) { -1 }
        val cacheVals = IntArray(1 shl 14)

        val out = Raster(src.width, src.height)
        for (idx in src.pixels.indices) {
            val p = src.pixels[idx]
            val a = p ushr 24
            if (a == 0) { out.pixels[idx] = 0; continue }
            val rgb = p and 0xFFFFFF
            val h = (rgb * -0x61c88647 ushr 18)
            if (cacheKeys[h] == rgb) { out.pixels[idx] = (a shl 24) or cacheVals[h]; continue }

            val r = (p shr 16) and 0xFF; val g = (p shr 8) and 0xFF; val b = p and 0xFF
            val res: Int
            if (n == 1) {
                val dr0 = r - pr[0]; val dg0 = g - pg[0]; val db0 = b - pb[0]
                val d = sqrt((dr0 * dr0 + dg0 * dg0 + db0 * db0).toDouble())
                val k = (1.0 - (d - 20.0) / 60.0).coerceIn(0.0, 1.0)
                res = rgbOf((r + k * (nr[0] - pr[0])).toInt(), (g + k * (ng[0] - pg[0])).toInt(), (b + k * (nb[0] - pb[0])).toInt())
            } else {
                // пара цветов палитры, на отрезке между которыми лежит пиксель
                var bi = 0; var bj = 1; var bt = 0.0; var bres = Double.MAX_VALUE
                for (i in 0 until n) for (j in i + 1 until n) {
                    val dr = (pr[i] - pr[j]).toDouble(); val dg = (pg[i] - pg[j]).toDouble(); val db = (pb[i] - pb[j]).toDouble()
                    val qr = (r - pr[j]).toDouble(); val qg = (g - pg[j]).toDouble(); val qb = (b - pb[j]).toDouble()
                    val len2 = dr * dr + dg * dg + db * db
                    val t = if (len2 == 0.0) 1.0 else ((qr * dr + qg * dg + qb * db) / len2).coerceIn(0.0, 1.0)
                    val er = qr - t * dr; val eg = qg - t * dg; val eb = qb - t * db
                    val e = er * er + eg * eg + eb * eb
                    if (e < bres) { bres = e; bi = i; bj = j; bt = t }
                }
                // новый = та же смесь новых цветов + то, чем пиксель отличался от смеси (тени, шум)
                val baseR = pr[bj] + bt * (pr[bi] - pr[bj]); val baseG = pg[bj] + bt * (pg[bi] - pg[bj]); val baseB = pb[bj] + bt * (pb[bi] - pb[bj])
                val newR = nr[bj] + bt * (nr[bi] - nr[bj]); val newG = ng[bj] + bt * (ng[bi] - ng[bj]); val newB = nb[bj] + bt * (nb[bi] - nb[bj])
                res = rgbOf((newR + r - baseR).toInt(), (newG + g - baseG).toInt(), (newB + b - baseB).toInt())
            }
            cacheKeys[h] = rgb; cacheVals[h] = res
            out.pixels[idx] = (a shl 24) or res
        }
        return out
    }

    private fun rgbOf(r: Int, g: Int, b: Int): Int = (r.coerceIn(0, 255) shl 16) or (g.coerceIn(0, 255) shl 8) or b.coerceIn(0, 255)

    /** Залить одним цветом с сохранением прозрачности (для одноцветных системных иконок). */
    fun tint(src: Raster, color: Int): Raster {
        val rgb = color and 0xFFFFFF
        val out = Raster(src.width, src.height)
        for (i in src.pixels.indices) {
            val a = src.pixels[i] ushr 24
            out.pixels[i] = if (a == 0) 0 else (a shl 24) or rgb
        }
        return out
    }

    // ---------------------------------------------------------------- палитра темы

    /** Общие цвета всех значков темы. Основной — самый площадной, дополнительный — его оттенок. */
    fun themePalette(all: List<IconColors>): ThemePalette {
        class Acc(var r: Double, var g: Double, var b: Double, var w: Double, var icons: Int)
        val accs = mutableListOf<Acc>()
        // значки различаем по номеру: одинаковые по цветам значки — всё равно разные значки
        val entries = all.flatMapIndexed { iconIdx, ic -> ic.palette.indices.map { Triple(ic.palette[it], ic.shares.getOrElse(it) { 0f }.toDouble(), iconIdx) } }
            .sortedByDescending { it.second }
        val seen = HashMap<Acc, MutableSet<Int>>()
        for ((c, w, ic) in entries) {
            val cr = (c shr 16) and 0xFF; val cg = (c shr 8) and 0xFF; val cb = c and 0xFF
            val hit = accs.minByOrNull { a -> dist(c, Raster.argb(255, (a.r / a.w).toInt(), (a.g / a.w).toInt(), (a.b / a.w).toInt())) }
                ?.takeIf { a -> dist(c, Raster.argb(255, (a.r / a.w).toInt(), (a.g / a.w).toInt(), (a.b / a.w).toInt())) <= THEME_MATCH }
            val ww = max(w, 1e-4)
            val acc = hit ?: Acc(0.0, 0.0, 0.0, 0.0, 0).also { accs += it }
            acc.r += cr * ww; acc.g += cg * ww; acc.b += cb * ww; acc.w += ww
            if (seen.getOrPut(acc) { HashSet() }.add(ic)) acc.icons++
        }
        val colors = accs.map { ThemeColor(Raster.argb(255, (it.r / it.w).toInt(), (it.g / it.w).toInt(), (it.b / it.w).toInt()), it.w, it.icons) }
            .filter { it.icons >= 2 || all.size < 3 }
            .sortedByDescending { it.weight }
            .take(10)
        val main = colors.firstOrNull { saturation(it.color) > 0.12 }?.color ?: colors.firstOrNull()?.color
        val accent = if (main == null || saturation(main) <= 0.12) null else colors.firstOrNull { tc ->
            tc.color != main && saturation(tc.color) > 0.12 && hueDiff(hue(tc.color), hue(main)) < 45f
        }?.color
        return ThemePalette(colors, main, accent)
    }

    /** Ближайший цвет палитры темы к цвету значка (или null, если далеко). */
    fun matchTheme(c: Int, theme: List<Int>): Int? {
        var best: Int? = null; var bd = Double.MAX_VALUE
        for (t in theme) { val d = dist(c, t); if (d < bd) { bd = d; best = t } }
        return if (bd <= THEME_MATCH) best else null
    }

    /**
     * Имя файла значка экрана в теме — так его ищет HyperOS:
     * экран внутри пакета → полное имя экрана, экран с другим началом имени → «пакет#экран».
     */
    fun themeIconName(pkg: String, cls: String?): String = when {
        cls == null -> pkg
        cls.startsWith(pkg) -> cls
        else -> "$pkg#$cls"
    }

    // ---------------------------------------------------------------- цвета

    fun hsv(c: Int): FloatArray {
        val r = ((c shr 16) and 0xFF) / 255f; val g = ((c shr 8) and 0xFF) / 255f; val b = (c and 0xFF) / 255f
        val mx = max(r, max(g, b)); val mn = min(r, min(g, b)); val d = mx - mn
        val h = when {
            d == 0f -> 0f
            mx == r -> 60f * (((g - b) / d) % 6f)
            mx == g -> 60f * ((b - r) / d + 2f)
            else -> 60f * ((r - g) / d + 4f)
        }.let { if (it < 0) it + 360f else it }
        return floatArrayOf(h, if (mx == 0f) 0f else d / mx, mx)
    }

    fun fromHsv(h: Float, s: Float, v: Float): Int {
        val c = v * s; val x = c * (1 - abs((h / 60f) % 2f - 1)); val m = v - c
        val (r, g, b) = when ((h / 60f).toInt().coerceIn(0, 5)) {
            0 -> Triple(c, x, 0f); 1 -> Triple(x, c, 0f); 2 -> Triple(0f, c, x)
            3 -> Triple(0f, x, c); 4 -> Triple(x, 0f, c); else -> Triple(c, 0f, x)
        }
        return Raster.argb(255, ((r + m) * 255 + 0.5f).toInt(), ((g + m) * 255 + 0.5f).toInt(), ((b + m) * 255 + 0.5f).toInt())
    }

    fun hue(c: Int) = hsv(c)[0]
    fun saturation(c: Int) = hsv(c)[1]
    private fun hueDiff(a: Float, b: Float): Float { val d = abs(a - b) % 360f; return if (d > 180f) 360f - d else d }

    /** Дополнительный цвет к основному: тот же оттенок, светлее (как #749F50 → #A4C964). */
    fun lighterAccent(main: Int): Int {
        val (h, s, v) = hsv(main).let { Triple(it[0], it[1], it[2]) }
        return fromHsv((h - 8f + 360f) % 360f, (s * 0.92f).coerceIn(0f, 1f), (v + 0.18f).coerceAtMost(1f))
    }

    fun darkerShade(main: Int): Int {
        val (h, s, v) = hsv(main).let { Triple(it[0], it[1], it[2]) }
        return fromHsv(h, (s * 1.05f).coerceIn(0f, 1f), (v - 0.18f).coerceAtLeast(0f))
    }

    fun dist(c1: Int, c2: Int): Double {
        val dr = ((c1 shr 16) and 0xFF) - ((c2 shr 16) and 0xFF)
        val dg = ((c1 shr 8) and 0xFF) - ((c2 shr 8) and 0xFF)
        val db = (c1 and 0xFF) - (c2 and 0xFF)
        return sqrt((dr * dr + dg * dg + db * db).toDouble())
    }

    fun toHex(c: Int): String = String.format("%06X", c and 0xFFFFFF)

    fun parseHex(s: String): Int? {
        val t = s.trim().removePrefix("#")
        if (t.length != 6) return null
        return t.toLongOrNull(16)?.let { (0xFF000000L or it).toInt() }
    }
}
