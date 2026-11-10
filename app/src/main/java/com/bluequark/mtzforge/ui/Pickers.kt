@file:OptIn(androidx.compose.material3.ExperimentalMaterial3Api::class)

package com.bluequark.mtzforge.ui

import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Search
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Slider
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import com.bluequark.mtzforge.AppInfo
import com.bluequark.mtzforge.core.IconRecolor
import android.graphics.Color as AColor

private val presets = listOf(
    0xFF1D78EF, 0xFF0A84FF, 0xFF5E5CE6, 0xFFBF5AF2, 0xFFFF375F, 0xFFFF453A, 0xFFFF9F0A,
    0xFFFFD60A, 0xFF30D158, 0xFF63E6E2, 0xFF64D2FF, 0xFF8E8E93, 0xFF1C1C1E, 0xFF000000,
    0xFFFFFFFF, 0xFFF2F2F7, 0xFF2C2C2E, 0xFF007AFF, 0xFF34C759, 0xFFFF2D55, 0xFFAF52DE,
).map { it.toInt() }

private fun hsvOf(c: Int): FloatArray = FloatArray(3).also { AColor.colorToHSV(c, it) }

@Composable
fun ColorPickerDialog(
    title: String,
    initial: Int,
    onDismiss: () -> Unit,
    onPick: (Int?) -> Unit,
    suggestions: List<Pair<String, Int>> = emptyList(),   // подсказки: «Светлее основного» и т.п.
    showReset: Boolean = true,
) {
    val start = remember { hsvOf(initial) }
    var h by remember { mutableFloatStateOf(start[0]) }
    var sat by remember { mutableFloatStateOf(start[1]) }
    var v by remember { mutableFloatStateOf(start[2]) }
    var hex by remember { mutableStateOf(IconRecolor.toHex(initial)) }
    val color = AColor.HSVToColor(floatArrayOf(h, sat, v))

    fun syncHex() { hex = IconRecolor.toHex(AColor.HSVToColor(floatArrayOf(h, sat, v))) }
    fun setColor(c: Int) {
        val a = hsvOf(c); h = a[0]; sat = a[1]; v = a[2]
        hex = IconRecolor.toHex(c)
    }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(title) },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState())) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Box(
                        Modifier.size(52.dp).clip(RoundedCornerShape(14.dp)).background(Color(color))
                            .border(1.dp, MaterialTheme.colorScheme.outline, RoundedCornerShape(14.dp))
                    )
                    Spacer(Modifier.width(12.dp))
                    OutlinedTextField(
                        value = hex,
                        onValueChange = { t ->
                            hex = t.removePrefix("#").filter { it.isLetterOrDigit() }.uppercase().take(6)
                            IconRecolor.parseHex(hex)?.let { c -> val a = hsvOf(c); h = a[0]; sat = a[1]; v = a[2] }
                        },
                        label = { Text("HEX") },
                        prefix = { Text("#") },
                        singleLine = true,
                        modifier = Modifier.weight(1f)
                    )
                }
                if (suggestions.isNotEmpty()) {
                    Spacer(Modifier.height(10.dp))
                    Text("Подсказки", style = MaterialTheme.typography.labelMedium)
                    Spacer(Modifier.height(6.dp))
                    suggestions.forEach { (label, c) ->
                        Row(
                            Modifier.fillMaxWidth().clip(RoundedCornerShape(10.dp)).clickable { setColor(c) }.padding(vertical = 4.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Box(
                                Modifier.size(26.dp).clip(CircleShape).background(Color(c))
                                    .border(1.dp, MaterialTheme.colorScheme.outline, CircleShape)
                            )
                            Spacer(Modifier.width(10.dp))
                            Text("$label  #${IconRecolor.toHex(c)}", style = MaterialTheme.typography.bodySmall)
                        }
                    }
                }
                Spacer(Modifier.height(12.dp))
                Text("Оттенок", style = MaterialTheme.typography.labelMedium)
                Slider(
                    value = h,
                    onValueChange = {
                        h = it
                        // у чёрного и белого оттенка «нет» — иначе ползунок казался бы сломанным
                        if (sat < 0.05f) sat = 1f
                        if (v < 0.05f) v = 1f
                        syncHex()
                    },
                    valueRange = 0f..360f
                )
                Text("Насыщенность", style = MaterialTheme.typography.labelMedium)
                Slider(value = sat, onValueChange = { sat = it; syncHex() })
                Text("Яркость", style = MaterialTheme.typography.labelMedium)
                Slider(value = v, onValueChange = { v = it; syncHex() })
                Spacer(Modifier.height(8.dp))
                Text("Готовые цвета", style = MaterialTheme.typography.labelMedium)
                Spacer(Modifier.height(6.dp))
                presets.chunked(7).forEach { row ->
                    Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                        row.forEach { c ->
                            Box(
                                Modifier.size(30.dp).clip(CircleShape).background(Color(c))
                                    .border(1.dp, MaterialTheme.colorScheme.outline, CircleShape)
                                    .clickable { setColor(c) }
                            )
                        }
                    }
                    Spacer(Modifier.height(6.dp))
                }
            }
        },
        confirmButton = { TextButton(onClick = { onPick(color) }) { Text("Применить") } },
        dismissButton = {
            Row {
                if (showReset) TextButton(onClick = { onPick(null) }) { Text("Как было") }
                TextButton(onClick = onDismiss) { Text("Отмена") }
            }
        }
    )
}

@Composable
fun AppPickerDialog(apps: List<AppInfo>, current: String?, onDismiss: () -> Unit, onPick: (String) -> Unit) {
    var query by remember { mutableStateOf("") }
    var manual by remember { mutableStateOf(current ?: "") }
    Dialog(onDismissRequest = onDismiss, properties = DialogProperties(usePlatformDefaultWidth = false)) {
        Surface(Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
            Column(Modifier.fillMaxSize().imePadding().padding(16.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text("Какому приложению значок?", style = MaterialTheme.typography.titleLarge, modifier = Modifier.weight(1f))
                    IconButton(onClick = onDismiss) { Icon(Icons.Default.Close, contentDescription = "Закрыть") }
                }
                OutlinedTextField(
                    value = query,
                    onValueChange = { query = it },
                    modifier = Modifier.fillMaxWidth(),
                    placeholder = { Text("Поиск: название или пакет") },
                    leadingIcon = { Icon(Icons.Default.Search, contentDescription = null) },
                    singleLine = true
                )
                Spacer(Modifier.height(8.dp))
                Row(verticalAlignment = Alignment.CenterVertically) {
                    OutlinedTextField(
                        value = manual,
                        onValueChange = { manual = it.trim() },
                        modifier = Modifier.weight(1f),
                        label = { Text("Или пакет вручную") },
                        singleLine = true
                    )
                    Spacer(Modifier.width(8.dp))
                    FilledTonalButton(onClick = { if (manual.isNotBlank()) onPick(manual) }) { Text("OK") }
                }
                Spacer(Modifier.height(8.dp))
                val q = query.trim().lowercase()
                val filtered = remember(apps, q) {
                    if (q.isEmpty()) apps else apps.filter { it.label.lowercase().contains(q) || it.pkg.lowercase().contains(q) }
                }
                if (apps.isEmpty()) Text("Список приложений загружается…", style = MaterialTheme.typography.bodySmall)
                LazyColumn(Modifier.fillMaxSize()) {
                    items(filtered, key = { it.pkg }) { a ->
                        Row(
                            Modifier.fillMaxWidth().clip(RoundedCornerShape(10.dp))
                                .background(if (a.pkg == current) MaterialTheme.colorScheme.primaryContainer else Color.Transparent)
                                .clickable { onPick(a.pkg) }
                                .padding(8.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            val icon = a.icon
                            if (icon != null) Image(bitmap = icon, contentDescription = null, modifier = Modifier.size(40.dp))
                            else Box(Modifier.size(40.dp))
                            Spacer(Modifier.width(12.dp))
                            Column {
                                Text(a.label, maxLines = 1, overflow = TextOverflow.Ellipsis)
                                Text(a.pkg, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1, overflow = TextOverflow.Ellipsis)
                            }
                        }
                    }
                }
            }
        }
    }
}
