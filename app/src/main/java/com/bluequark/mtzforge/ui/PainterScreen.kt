@file:OptIn(androidx.compose.material3.ExperimentalMaterial3Api::class)

package com.bluequark.mtzforge.ui

import android.graphics.Bitmap
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Slider
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.FilterQuality
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import com.bluequark.mtzforge.core.IconPainter
import com.bluequark.mtzforge.core.IconRecolor
import com.bluequark.mtzforge.core.Raster

enum class PaintTool(val title: String) { FILL("Заливка"), BRUSH("Кисть"), ERASER("Ластик"), PICK("Пипетка") }

/** Полноэкранный редактор: заливка, кисть, ластик, пипетка, отмена/повтор. */
@Composable
fun PainterScreen(start: Raster, quickColors: List<Int>, onCancel: () -> Unit, onDone: (Raster) -> Unit) {
    val painter = remember { IconPainter(start) }
    val w = start.width
    val h = start.height
    val bitmap = remember {
        Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888).also { it.setPixels(start.pixels, 0, w, 0, 0, w, h) }
    }
    var version by remember { mutableIntStateOf(0) }
    fun refresh() {
        bitmap.setPixels(painter.image.pixels, 0, w, 0, 0, w, h)
        version++
    }

    var tool by remember { mutableStateOf(PaintTool.FILL) }
    var color by remember { mutableIntStateOf(quickColors.getOrElse(2) { quickColors.firstOrNull() ?: 0xFFFFFFFF.toInt() }) }
    var brush by remember { mutableFloatStateOf(w / 40f) }
    var tolerance by remember { mutableFloatStateOf(40f) }
    var showPicker by remember { mutableStateOf(false) }

    BackHandler(onBack = onCancel)
    run {
        // обычный экран (не отдельное окно): тогда отступы от системных панелей работают правильно
        Surface(Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
            Column(Modifier.fillMaxSize().statusBarsPadding().navigationBarsPadding()) {
                // ---- верхняя панель
                Row(Modifier.fillMaxWidth().padding(horizontal = 4.dp), verticalAlignment = Alignment.CenterVertically) {
                    TextButton(onClick = onCancel) { Text("Отмена") }
                    Spacer(Modifier.weight(1f))
                    val v = version // перечитываем доступность отмены после каждого действия
                    TextButton(onClick = { if (painter.undo()) refresh() }, enabled = v >= 0 && painter.canUndo) { Text("↶ Назад") }
                    TextButton(onClick = { if (painter.redo()) refresh() }, enabled = v >= 0 && painter.canRedo) { Text("Вперёд ↷") }
                    Spacer(Modifier.weight(1f))
                    TextButton(onClick = { onDone(painter.image.copy()) }) { Text("Готово") }
                }

                // ---- холст
                // холст занимает всё свободное место, но панель инструментов внизу всегда видна
                Box(Modifier.weight(1f).fillMaxWidth().padding(12.dp), contentAlignment = Alignment.Center) {
                    Canvas(
                        Modifier.aspectRatio(w.toFloat() / h).clip(RoundedCornerShape(8.dp))
                            .pointerInput(tool, color, brush, tolerance) {
                                awaitEachGesture {
                                    val down = awaitFirstDown()
                                    fun ix(o: Offset) = o.x / size.width * w
                                    fun iy(o: Offset) = o.y / size.height * h
                                    val x = ix(down.position); val y = iy(down.position)
                                    when (tool) {
                                        PaintTool.FILL -> {
                                            if (painter.fill(x.toInt(), y.toInt(), color, tolerance.toInt())) refresh()
                                            down.consume()
                                        }
                                        PaintTool.PICK -> {
                                            painter.pick(x.toInt(), y.toInt())?.let { color = it }
                                            tool = PaintTool.FILL
                                            down.consume()
                                        }
                                        PaintTool.BRUSH, PaintTool.ERASER -> {
                                            painter.beginStroke(x, y, color, brush, tool == PaintTool.ERASER)
                                            refresh()
                                            down.consume()
                                            while (true) {
                                                val ev = awaitPointerEvent()
                                                val ch = ev.changes.firstOrNull { it.id == down.id } ?: break
                                                if (!ch.pressed) break
                                                painter.strokeTo(ix(ch.position), iy(ch.position))
                                                ch.consume()
                                                refresh()
                                            }
                                            painter.endStroke()
                                        }
                                    }
                                }
                            }
                    ) {
                        // шахматка, чтобы было видно прозрачность
                        val cell = size.width / 16f
                        for (yy in 0 until 16) for (xx in 0 until 16) {
                            drawRect(
                                color = if ((xx + yy) % 2 == 0) Color(0xFF3A3A3C) else Color(0xFF2C2C2E),
                                topLeft = Offset(xx * cell, yy * cell),
                                size = Size(cell + 1f, cell + 1f)
                            )
                        }
                        @Suppress("UNUSED_VARIABLE") val v = version // перерисовка после изменений
                        drawImage(
                            image = bitmap.asImageBitmap(),
                            srcOffset = IntOffset.Zero,
                            srcSize = IntSize(w, h),
                            dstOffset = IntOffset.Zero,
                            dstSize = IntSize(size.width.toInt(), size.height.toInt()),
                            filterQuality = FilterQuality.Medium
                        )
                    }
                }

                // ---- инструменты (компактно, чтобы всё помещалось на экране)
                Column(Modifier.fillMaxWidth().padding(horizontal = 16.dp).padding(bottom = 8.dp)) {
                    Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        PaintTool.entries.forEach { t ->
                            FilterChip(selected = tool == t, onClick = { tool = t }, label = { Text(t.title) })
                        }
                    }
                    Spacer(Modifier.height(6.dp))
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Box(
                            Modifier.size(40.dp).clip(RoundedCornerShape(10.dp)).background(Color(color))
                                .border(2.dp, MaterialTheme.colorScheme.primary, RoundedCornerShape(10.dp))
                                .clickable { showPicker = true }
                        )
                        Spacer(Modifier.width(10.dp))
                        Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            quickColors.forEach { c ->
                                Box(
                                    Modifier.size(30.dp).clip(CircleShape).background(Color(c))
                                        .border(1.dp, MaterialTheme.colorScheme.outline, CircleShape)
                                        .clickable { color = c; if (tool == PaintTool.ERASER || tool == PaintTool.PICK) tool = PaintTool.FILL }
                                )
                            }
                        }
                    }
                    Text("#${IconRecolor.toHex(color)} · большой квадрат — любой цвет",
                        style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    when (tool) {
                        PaintTool.FILL -> Row(verticalAlignment = Alignment.CenterVertically) {
                            Text("Чувствит. ${tolerance.toInt()}", style = MaterialTheme.typography.labelMedium, modifier = Modifier.width(96.dp))
                            Slider(value = tolerance, onValueChange = { tolerance = it }, valueRange = 5f..120f, modifier = Modifier.weight(1f))
                        }
                        PaintTool.BRUSH, PaintTool.ERASER -> Row(verticalAlignment = Alignment.CenterVertically) {
                            Text("Кисть ${brush.toInt()} px", style = MaterialTheme.typography.labelMedium, modifier = Modifier.width(96.dp))
                            Slider(value = brush, onValueChange = { brush = it }, valueRange = 1f..(w / 6f), modifier = Modifier.weight(1f))
                        }
                        PaintTool.PICK -> Text("Нажмите на значок, чтобы взять цвет", style = MaterialTheme.typography.labelSmall,
                            modifier = Modifier.padding(vertical = 14.dp))
                    }
                }
            }
        }
    }

    if (showPicker) {
        ColorPickerDialog(
            title = "Цвет для рисования",
            initial = color,
            showReset = false,
            suggestions = quickColors.take(4).mapIndexed { i, c -> (if (i == 0) "Основной значка" else "Цвет значка ${i + 1}") to c },
            onDismiss = { showPicker = false },
            onPick = { c -> if (c != null) color = c; showPicker = false }
        )
    }
}
