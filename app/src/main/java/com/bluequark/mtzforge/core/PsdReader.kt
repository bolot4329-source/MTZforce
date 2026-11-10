package com.bluequark.mtzforge.core

import java.io.Closeable
import java.io.File
import java.io.RandomAccessFile
import java.util.zip.Inflater
import kotlin.math.max
import kotlin.math.min

class PsdException(message: String) : Exception(message)

/** Буферизованное чтение из файла с произвольным доступом (big-endian, как в PSD). */
internal class Src(file: File) : Closeable {
    private val raf = RandomAccessFile(file, "r")
    val length: Long = raf.length()
    private val buf = ByteArray(1 shl 16)
    private var bufStart = 0L
    private var bufLen = 0
    var pos = 0L
        private set

    fun seek(p: Long) { pos = p }
    fun skip(n: Long) { pos += n }

    fun u8(): Int {
        if (pos < bufStart || pos >= bufStart + bufLen) fill()
        val v = buf[(pos - bufStart).toInt()].toInt() and 0xFF
        pos++
        return v
    }

    private fun fill() {
        if (pos >= length) throw PsdException("Файл PSD обрывается (повреждён или не до конца скопирован)")
        raf.seek(pos)
        bufStart = pos
        bufLen = raf.read(buf, 0, buf.size).coerceAtLeast(0)
        if (bufLen == 0) throw PsdException("Не удалось прочитать файл")
    }

    fun u16(): Int = (u8() shl 8) or u8()
    fun s16(): Int = u16().toShort().toInt()
    fun u32(): Long = (u16().toLong() shl 16) or u16().toLong()
    fun s32(): Int = u32().toInt()
    fun u64(): Long = (u32() shl 32) or u32()

    fun bytes(n: Int): ByteArray {
        val out = ByteArray(n)
        var off = 0
        while (off < n) {
            if (pos < bufStart || pos >= bufStart + bufLen) fill()
            val start = (pos - bufStart).toInt()
            val c = min(n - off, bufLen - start)
            System.arraycopy(buf, start, out, off, c)
            off += c
            pos += c
        }
        return out
    }

    fun readInto(out: ByteArray, n: Int) {
        var off = 0
        while (off < n) {
            if (pos < bufStart || pos >= bufStart + bufLen) fill()
            val start = (pos - bufStart).toInt()
            val c = min(n - off, bufLen - start)
            System.arraycopy(buf, start, out, off, c)
            off += c
            pos += c
        }
    }

    fun ascii(n: Int): String = String(bytes(n), Charsets.ISO_8859_1)

    override fun close() = raf.close()
}

internal class ChannelInfo(val id: Int, val length: Long, var offset: Long = 0L)

internal class LayerRec(
    val top: Int, val left: Int, val bottom: Int, val right: Int,
    val channels: List<ChannelInfo>,
    val opacity: Int,
    val clipping: Boolean,
    val visible: Boolean,
    val name: String,
    val sectionType: Int,
    val fillOpacity: Int,
    val maskTop: Int, val maskLeft: Int, val maskBottom: Int, val maskRight: Int,
    val maskDefault: Int, val maskEnabled: Boolean,
) {
    val width get() = right - left
    val height get() = bottom - top
}

/** Узел дерева слоёв: слой или группа. Дети — снизу вверх, как в Photoshop. */
class PsdNode internal constructor(
    val name: String,
    val isGroup: Boolean,
    val visible: Boolean,
    val opacity: Int,
    val clipping: Boolean,
    internal val layer: LayerRec?,
) {
    val children = mutableListOf<PsdNode>()

    /** Границы содержимого (для группы — объединение видимых детей). [left, top, right, bottom] или null. */
    fun bounds(): IntArray? {
        if (!isGroup) {
            val l = layer ?: return null
            return if (l.width > 0 && l.height > 0) intArrayOf(l.left, l.top, l.right, l.bottom) else null
        }
        var r: IntArray? = null
        for (c in children) {
            if (!c.visible) continue
            val b = c.bounds() ?: continue
            r = if (r == null) b.copyOf() else intArrayOf(min(r[0], b[0]), min(r[1], b[1]), max(r[2], b[2]), max(r[3], b[3]))
        }
        return r
    }
}

/** Найденный в PSD значок. */
class PsdIcon(val name: String, val raster: Raster)

class PsdResult(val icons: List<PsdIcon>, val usedComposite: Boolean, val warnings: List<String>)

/**
 * Читает PSD/PSB (RGB, 8 бит/канал) и вырезает из него значки.
 * Значок = видимый слой верхнего уровня или группа, где слои лежат стопкой (фон + логотип).
 * Группы-«папки», в которых значки разложены сеткой, раскрываются автоматически.
 */
class PsdReader(private val file: File) {

    private var psb = false
    private var width = 0
    private var height = 0
    private var channels = 0
    private var mergedHasTransparency = false
    private var extraIsTransparency = false
    private var compositeCompression = -1
    private var compositeDataStart = 0L
    private var compositeRowOffsets: LongArray? = null
    private val layers = mutableListOf<LayerRec>()
    private val warnings = mutableListOf<String>()

    enum class Mode { AUTO, COMPOSITE, LAYERS }

    fun read(
        mode: Mode = Mode.AUTO,
        onProgress: (Int, Int) -> Unit = { _, _ -> },
        onIcon: ((PsdIcon) -> Unit)? = null,   // если задан — значки отдаются по одному и не копятся в памяти
    ): PsdResult {
        Src(file).use { s ->
            parse(s)
            val root = buildTree()
            val items = mutableListOf<PsdNode>()
            collectIcons(root, items, isRoot = true)
            if (items.isEmpty()) throw PsdException("В PSD не найдено ни одного видимого слоя со значком")

            val canvasArea = width.toLong() * height
            val backdrop = items.filter { n ->
                val b = n.bounds() ?: return@filter false
                val area = (b[2] - b[0]).toLong() * (b[3] - b[1])
                items.size > 1 && area >= canvasArea * 8 / 10
            }.toSet()

            val canComposite = compositeCompression == 0 || compositeCompression == 1
            val hasAlpha = channels >= 4 && (mergedHasTransparency || extraIsTransparency)
            val useComposite = when (mode) {
                Mode.LAYERS -> false
                Mode.COMPOSITE -> {
                    if (!canComposite) warnings += "В PSD нет готового изображения — читаю по слоям"
                    canComposite
                }
                Mode.AUTO -> canComposite && hasAlpha && backdrop.isEmpty()
            }
            if (mode == Mode.AUTO && !useComposite) {
                when {
                    !canComposite -> warnings += "В PSD не сохранено готовое изображение (включите «Максимальная совместимость») — читаю по слоям, эффекты слоёв не учитываются"
                    !hasAlpha -> warnings += "У документа непрозрачный фон — читаю по слоям, эффекты слоёв не учитываются"
                    backdrop.isNotEmpty() -> warnings += "Найден фоновый слой на весь холст («${backdrop.first().name}») — пропускаю его и читаю по слоям"
                }
            }

            val tooBig = items.filter { n -> n !in backdrop && (n.bounds()?.let { maxDim(it) } ?: 0) > 4096 }
            if (tooBig.isNotEmpty()) warnings += "Пропущено слишком больших элементов (больше 4096 px): ${tooBig.size}, например «${tooBig.first().name}»"
            val result = mutableListOf<PsdIcon>()
            val list = items.filter { it !in backdrop && it !in tooBig }
            list.forEachIndexed { i, node ->
                onProgress(i, list.size)
                val b = node.bounds() ?: return@forEachIndexed
                val l = max(0, b[0]); val t = max(0, b[1])
                val r = min(width, b[2]); val btm = min(height, b[3])
                if (r <= l || btm <= t) return@forEachIndexed
                var raster = if (useComposite) cropComposite(s, l, t, r, btm)
                else renderNode(s, node, l, t, r, btm)
                if (useComposite && raster.pixels.all { (it ushr 24) == 0 }) {
                    // в готовом изображении пусто (PSD сохранён не Photoshop'ом) — собираем из слоёв
                    raster = renderNode(s, node, l, t, r, btm)
                }
                val icon = PsdIcon(node.name, raster.toSquare())
                if (onIcon != null) onIcon(icon) else result += icon
            }
            onProgress(list.size, list.size)
            return PsdResult(result, useComposite, warnings.toList())
        }
    }

    // ---------------------------------------------------------------- разбор файла

    private fun parse(s: Src) {
        if (s.ascii(4) != "8BPS") throw PsdException("Это не PSD-файл")
        val version = s.u16()
        psb = when (version) { 1 -> false; 2 -> true; else -> throw PsdException("Неизвестная версия PSD: $version") }
        s.skip(6)
        channels = s.u16()
        height = s.u32().toInt()
        width = s.u32().toInt()
        val depth = s.u16()
        val colorMode = s.u16()
        if (colorMode != 3) throw PsdException("Нужен PSD в режиме RGB (Изображение → Режим → RGB)")
        if (depth != 8) throw PsdException("Нужен PSD 8 бит/канал (Изображение → Режим → 8 бит/канал), сейчас $depth бит")

        s.skip(s.u32())                          // color mode data
        // Ресурсы изображения: считаем именованные альфа-каналы (сохранённые выделения),
        // чтобы понять, является ли лишний канал готового изображения прозрачностью
        val resLen = s.u32()
        val resEnd = s.pos + resLen
        var namedAlphas = 0
        while (s.pos + 12 <= resEnd) {
            if (s.ascii(4) != "8BIM") break
            val id = s.u16()
            val nl = s.u8(); s.skip((nl + if ((nl + 1) % 2 == 1) 1 else 0).toLong())
            val size = s.u32()
            val dataStart = s.pos
            val dataEnd = dataStart + size
            when (id) {
                1006 -> { var n = 0; while (s.pos < dataEnd) { s.skip(s.u8().toLong()); n++ }; namedAlphas = maxOf(namedAlphas, n) }
                1045 -> { var n = 0; while (s.pos + 4 <= dataEnd) { s.skip(s.u32() * 2); n++ }; namedAlphas = maxOf(namedAlphas, n) }
            }
            s.seek(dataEnd + (size and 1L))
        }
        s.seek(resEnd)
        extraIsTransparency = channels - 3 > namedAlphas

        val lmLen = if (psb) s.u64() else s.u32()
        val lmStart = s.pos
        if (lmLen > 0) {
            val liLen = if (psb) s.u64() else s.u32()
            val liStart = s.pos
            if (liLen > 0) {
                val rawCount = s.s16()
                mergedHasTransparency = rawCount < 0
                val count = kotlin.math.abs(rawCount)
                repeat(count) { layers += readLayerRecord(s) }
                var cursor = s.pos
                for (layer in layers) for (ch in layer.channels) { ch.offset = cursor; cursor += ch.length }
            }
            s.seek(liStart + liLen)
        }
        s.seek(lmStart + lmLen)

        // Готовое (склеенное) изображение
        if (s.pos + 2 <= s.length) {
            compositeCompression = s.u16()
            compositeDataStart = s.pos
            if (compositeCompression == 1) {
                val n = channels * height
                val offs = LongArray(n + 1)
                var acc = 0L
                for (i in 0 until n) {
                    offs[i] = acc
                    acc += if (psb) s.u32() else s.u16().toLong()
                }
                offs[n] = acc
                val dataStart = s.pos
                for (i in 0..n) offs[i] += dataStart
                compositeRowOffsets = offs
            }
        }
    }

    private val longLengthKeys = setOf("LMsk", "Lr16", "Lr32", "Layr", "Mt16", "Mt32", "Mtrn", "Alph", "FMsk", "lnk2", "FEid", "FXid", "PxSD")

    private fun readLayerRecord(s: Src): LayerRec {
        val top = s.s32(); val left = s.s32(); val bottom = s.s32(); val right = s.s32()
        val nCh = s.u16()
        val chs = List(nCh) { ChannelInfo(s.s16(), if (psb) s.u64() else s.u32()) }
        if (s.ascii(4) != "8BIM") throw PsdException("Повреждена запись слоя")
        s.skip(4) // blend mode
        val opacity = s.u8()
        val clipping = s.u8() == 1
        val flags = s.u8()
        s.skip(1)
        val extraLen = s.u32()
        val extraStart = s.pos

        var mT = 0; var mL = 0; var mB = 0; var mR = 0; var mDef = 255; var mEnabled = false
        val maskLen = s.u32()
        val maskStart = s.pos
        if (maskLen >= 18) {
            mT = s.s32(); mL = s.s32(); mB = s.s32(); mR = s.s32()
            mDef = s.u8()
            val mFlags = s.u8()
            mEnabled = (mFlags and 2) == 0
        }
        s.seek(maskStart + maskLen)
        s.skip(s.u32()) // blending ranges
        val nameLen = s.u8()
        val nameBytes = s.bytes(nameLen)
        var name = try {
            Charsets.UTF_8.newDecoder().decode(java.nio.ByteBuffer.wrap(nameBytes)).toString()
        } catch (e: java.nio.charset.CharacterCodingException) {
            String(nameBytes, Charsets.ISO_8859_1)
        }
        val padded = ((nameLen + 1 + 3) / 4) * 4
        s.skip((padded - nameLen - 1).toLong())

        var section = 0
        var fill = 255
        val extraEnd = extraStart + extraLen
        while (s.pos + 12 <= extraEnd) {
            var sig = s.ascii(4)
            var tries = 0
            while (sig != "8BIM" && sig != "8B64" && tries < 3 && s.pos + 9 <= extraEnd) {
                s.seek(s.pos - 3); sig = s.ascii(4); tries++
            }
            if (sig != "8BIM" && sig != "8B64") break
            val key = s.ascii(4)
            val len = if (psb && key in longLengthKeys) s.u64() else s.u32()
            val dataStart = s.pos
            when (key) {
                "luni" -> {
                    val n = s.u32().toInt()
                    if (n in 0..2000) {
                        val sb = StringBuilder()
                        repeat(n) { sb.append(s.u16().toChar()) }
                        name = sb.toString().trimEnd('\u0000')
                    }
                }
                "lsct", "lsdk" -> section = s.u32().toInt()
                "iOpa" -> fill = s.u8()
            }
            s.seek(dataStart + len)
        }
        s.seek(extraEnd)
        return LayerRec(top, left, bottom, right, chs, opacity, clipping, (flags and 2) == 0,
            name, section, fill, mT, mL, mB, mR, mDef, mEnabled)
    }

    /** Слои хранятся снизу вверх: маркер конца группы (3) идёт раньше самой группы (1/2). */
    private fun buildTree(): PsdNode {
        val root = PsdNode("root", true, true, 255, false, null)
        val stack = ArrayDeque<PsdNode>()
        stack.addLast(root)
        for (l in layers) {
            when (l.sectionType) {
                3 -> stack.addLast(PsdNode("", true, true, 255, false, null))
                1, 2 -> {
                    if (stack.size > 1) {
                        val tmp = stack.removeLast()
                        val g = PsdNode(l.name, true, l.visible, l.opacity, l.clipping, l)
                        g.children += tmp.children
                        stack.last().children += g
                    }
                }
                else -> stack.last().children += PsdNode(l.name, false, l.visible, l.opacity, l.clipping, l)
            }
        }
        while (stack.size > 1) { val tmp = stack.removeLast(); stack.last().children += tmp.children }
        return root
    }

    private fun maxDim(b: IntArray) = max(b[2] - b[0], b[3] - b[1])

    /** Слой-подложка на весь холст (чёрный фон, сетка) — не значок. */
    private fun isBackdrop(n: PsdNode): Boolean {
        val b = n.bounds() ?: return false
        return (b[2] - b[0]).toLong() * (b[3] - b[1]) >= width.toLong() * height * 8 / 10
    }

    private fun collectIcons(node: PsdNode, out: MutableList<PsdNode>, isRoot: Boolean = false) {
        if (!node.visible) return
        if (!node.isGroup) { if (node.bounds() != null) out += node; return }
        if (!isRoot && isStackedGroup(node)) { out += node; return }
        for (c in node.children) collectIcons(c, out)
    }

    /** Группа — один значок, если её слои лежат стопкой, а не раскиданы сеткой. */
    private fun isStackedGroup(g: PsdNode): Boolean {
        val gb = g.bounds() ?: return false
        var maxChild = 0
        for (c in g.children) {
            if (!c.visible || isBackdrop(c)) continue
            val b = c.bounds() ?: continue
            maxChild = max(maxChild, maxDim(b))
        }
        if (maxChild == 0) return false
        return maxDim(gb) <= maxChild * 13 / 10
    }

    // ---------------------------------------------------------------- готовое изображение

    private fun cropComposite(s: Src, l: Int, t: Int, r: Int, b: Int): Raster {
        val w = r - l; val h = b - t
        val out = Raster(w, h)
        val hasAlpha = channels >= 4 && (mergedHasTransparency || extraIsTransparency)
        val chCount = if (hasAlpha) 4 else 3
        val rowBuf = ByteArray(width)
        val planes = Array(chCount) { ByteArray(w * h) }
        for (c in 0 until chCount) {
            for (y in 0 until h) {
                readCompositeRow(s, c, t + y, rowBuf)
                System.arraycopy(rowBuf, l, planes[c], y * w, w)
            }
        }
        for (i in 0 until w * h) {
            val a = if (hasAlpha) planes[3][i].toInt() and 0xFF else 255
            var rr = planes[0][i].toInt() and 0xFF
            var gg = planes[1][i].toInt() and 0xFF
            var bb = planes[2][i].toInt() and 0xFF
            if (a == 0) { out.pixels[i] = 0; continue }
            if (a < 255) { // Photoshop смешивает полупрозрачное с белым — убираем это
                rr = ((rr - (255 - a)) * 255 + a / 2) / a
                gg = ((gg - (255 - a)) * 255 + a / 2) / a
                bb = ((bb - (255 - a)) * 255 + a / 2) / a
            }
            out.pixels[i] = Raster.argb(a, rr, gg, bb)
        }
        return out
    }

    private var packedBuf = ByteArray(0)

    private fun readCompositeRow(s: Src, channel: Int, y: Int, dst: ByteArray) {
        if (compositeCompression == 0) {
            s.seek(compositeDataStart + (channel.toLong() * height + y) * width)
            val bytes = s.bytes(width)
            System.arraycopy(bytes, 0, dst, 0, width)
        } else {
            val offs = compositeRowOffsets!!
            val idx = channel * height + y
            s.seek(offs[idx])
            val len = (offs[idx + 1] - offs[idx]).toInt()
            if (packedBuf.size < len) packedBuf = ByteArray(len)
            s.readInto(packedBuf, len)
            unpackBits(packedBuf, 0, len, dst, 0, width)
        }
    }

    // ---------------------------------------------------------------- рендер по слоям

    private fun renderNode(s: Src, node: PsdNode, l: Int, t: Int, r: Int, b: Int): Raster {
        val canvas = Raster(r - l, b - t)
        if (node.isGroup) composeChildren(s, node, canvas, l, t)
        else {
            val lr = node.layer!!
            drawLayer(s, lr, canvas, l, t, null)
        }
        return canvas
    }

    private fun composeChildren(s: Src, group: PsdNode, canvas: Raster, ox: Int, oy: Int) {
        var baseAlpha: ByteArray? = null
        for (c in group.children) {
            if (!c.visible) { if (!c.clipping) baseAlpha = null; continue }
            if (c.isGroup) {
                val sub = Raster(canvas.width, canvas.height)
                composeChildren(s, c, sub, ox, oy)
                val op = c.opacity
                for (i in canvas.pixels.indices) canvas.pixels[i] = Raster.blendOver(canvas.pixels[i], sub.pixels[i], op)
                if (!c.clipping) baseAlpha = ByteArray(sub.pixels.size) { (sub.pixels[it] ushr 24).toByte() }
            } else {
                val lr = c.layer!!
                if (c.clipping && baseAlpha != null) {
                    drawLayer(s, lr, canvas, ox, oy, baseAlpha)
                } else {
                    baseAlpha = drawLayer(s, lr, canvas, ox, oy, null)
                }
            }
        }
    }

    /** Рисует слой на холст, возвращает альфу слоя (для обтравочных масок). */
    private fun drawLayer(s: Src, lr: LayerRec, canvas: Raster, ox: Int, oy: Int, clip: ByteArray?): ByteArray {
        val alphaOut = ByteArray(canvas.width * canvas.height)
        val w = lr.width; val h = lr.height
        if (w <= 0 || h <= 0) return alphaOut
        fun plane(id: Int): ByteArray? = lr.channels.firstOrNull { it.id == id }?.let { decodeChannel(s, it, w, h) }
        val pr = plane(0) ?: return alphaOut
        val pg = plane(1) ?: return alphaOut
        val pb = plane(2) ?: return alphaOut
        val pa = plane(-1)
        val mw = lr.maskRight - lr.maskLeft; val mh = lr.maskBottom - lr.maskTop
        val pm = if (lr.maskEnabled && mw > 0 && mh > 0) lr.channels.firstOrNull { it.id == -2 }?.let { decodeChannel(s, it, mw, mh) } else null
        val layerOpacity = lr.opacity * lr.fillOpacity / 255

        for (y in 0 until h) {
            val cy = lr.top + y - oy
            if (cy < 0 || cy >= canvas.height) continue
            for (x in 0 until w) {
                val cx = lr.left + x - ox
                if (cx < 0 || cx >= canvas.width) continue
                val i = y * w + x
                var a = if (pa != null) pa[i].toInt() and 0xFF else 255
                if (lr.maskEnabled && mw >= 0) {
                    val mx = lr.left + x - lr.maskLeft; val my = lr.top + y - lr.maskTop
                    val m = if (pm != null && mx in 0 until mw && my in 0 until mh) pm[my * mw + mx].toInt() and 0xFF
                    else if (pm != null) lr.maskDefault else 255
                    a = a * m / 255
                }
                val ci = cy * canvas.width + cx
                if (clip != null) a = a * (clip[ci].toInt() and 0xFF) / 255
                alphaOut[ci] = a.toByte()
                if (a == 0) continue
                val src = Raster.argb(a, pr[i].toInt() and 0xFF, pg[i].toInt() and 0xFF, pb[i].toInt() and 0xFF)
                canvas.pixels[ci] = Raster.blendOver(canvas.pixels[ci], src, layerOpacity)
            }
        }
        return alphaOut
    }

    private fun decodeChannel(s: Src, ch: ChannelInfo, w: Int, h: Int): ByteArray {
        val out = ByteArray(w * h)
        if (ch.length < 2) return out
        s.seek(ch.offset)
        val compression = s.u16()
        val dataLen = (ch.length - 2).toInt()
        when (compression) {
            0 -> { val raw = s.bytes(min(dataLen, w * h)); System.arraycopy(raw, 0, out, 0, raw.size) }
            1 -> {
                val header = h * (if (psb) 4 else 2)
                if (dataLen < header) { warnings += "Пропущен повреждённый канал слоя"; return out }
                val counts = IntArray(h) { if (psb) s.u32().toInt() else s.u16() }
                val data = s.bytes(dataLen - header)
                var p = 0
                for (y in 0 until h) {
                    unpackBits(data, p, counts[y], out, y * w, w)
                    p += counts[y]
                }
            }
            2, 3 -> {
                val inf = Inflater()
                try {
                    inf.setInput(s.bytes(dataLen))
                    var n = 0
                    while (n < out.size && !inf.finished()) {
                        val k = inf.inflate(out, n, out.size - n)
                        if (k == 0 && (inf.needsInput() || inf.needsDictionary())) break
                        n += k
                    }
                } catch (e: java.util.zip.DataFormatException) {
                    warnings += "Пропущен повреждённый канал слоя"
                } finally {
                    inf.end()
                }
                if (compression == 3) for (y in 0 until h) for (x in 1 until w) {
                    val i = y * w + x
                    out[i] = (out[i] + out[i - 1]).toByte()
                }
            }
            else -> warnings += "Неизвестное сжатие канала: $compression"
        }
        return out
    }

    companion object {
        /** PackBits (RLE из PSD). */
        internal fun unpackBits(src: ByteArray, start: Int, len: Int, dst: ByteArray, dstStart: Int, dstLen: Int) {
            var i = start
            val end = min(src.size, start + len)
            var o = dstStart
            val oEnd = dstStart + dstLen
            while (i < end && o < oEnd) {
                val n = src[i++].toInt()
                if (n >= 0) {
                    val c = min(n + 1, min(end - i, oEnd - o))
                    System.arraycopy(src, i, dst, o, c)
                    i += n + 1; o += c
                } else if (n != -128) {
                    if (i >= end) break
                    val v = src[i++]
                    val c = min(1 - n, oEnd - o)
                    for (k in 0 until c) dst[o + k] = v
                    o += c
                }
            }
        }
    }
}
