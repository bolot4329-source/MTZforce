package com.bluequark.mtzforge.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import com.bluequark.mtzforge.core.IconRecolor
import com.bluequark.mtzforge.core.ThemePalette

/** Кружок цвета. Если цвет заменён — левая половина старый, правая новый. */
@Composable
fun ColorDot(old: Int, new: Int?, sizeDp: Int) {
    val shape = CircleShape
    Box(
        Modifier.size(sizeDp.dp).clip(shape).background(Color(old))
            .border(1.dp, MaterialTheme.colorScheme.outline, shape)
    ) {
        if (new != null) {
            Box(
                Modifier.size(sizeDp.dp)
                    .padding(start = (sizeDp / 2).dp)
                    .background(Color(new))
            )
        }
    }
}

/** Полоса «Палитра темы»: общие цвета всех значков. Нажатие — заменить цвет везде. */
@Composable
fun ThemeBar(theme: ThemePalette, swaps: Map<Int, Int>, onColor: (Int) -> Unit) {
    if (theme.colors.isEmpty()) return
    Column(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 2.dp)) {
        Text("Палитра темы — нажмите цвет, чтобы заменить его во всех значках", style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant)
        Spacer(Modifier.height(6.dp))
        Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            theme.colors.forEach { tc ->
                val swapped = IconRecolor.matchTheme(tc.color, swaps.keys.toList())?.let { swaps[it] }
                val role = when (tc.color) {
                    theme.main -> "основной"
                    theme.accent -> "доп."
                    else -> "${tc.icons} зн."
                }
                Column(
                    Modifier.clip(RoundedCornerShape(10.dp)).clickable { onColor(tc.color) }.padding(2.dp),
                    horizontalAlignment = Alignment.CenterHorizontally
                ) {
                    ColorDot(tc.color, swapped, 34)
                    Spacer(Modifier.height(2.dp))
                    Text(role, style = MaterialTheme.typography.labelSmall)
                }
            }
        }
    }
}

/** Готовые пары «основной + дополнительный». */
private val pairs = listOf(
    "Травяной" to (0xFF749F50 to 0xFFA4C964),
    "Шалфей" to (0xFF5C8B67 to 0xFF8EC09B),
    "Синий iOS" to (0xFF1D78EF to 0xFF6FA8F5),
    "Индиго" to (0xFF5856D6 to 0xFF9A98F0),
    "Сирень" to (0xFF9B6BC8 to 0xFFC9A6E8),
    "Коралл" to (0xFFE8664D to 0xFFF5A08E),
    "Песок" to (0xFFC49A5A to 0xFFE6C690),
    "Графит" to (0xFF3A3A3C to 0xFF6E6E73),
    "OLED" to (0xFF000000 to 0xFF2C2C2E),
).map { (n, p) -> n to (p.first.toInt() to p.second.toInt()) }

@Composable
fun PairsDialog(onDismiss: () -> Unit, onPick: (Int, Int) -> Unit, onCustom: () -> Unit) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Пары цветов") },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState())) {
                Text(
                    "Основной цвет заменит главный цвет значков, дополнительный — их второй оттенок (заливки внутри рисунков).",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                Spacer(Modifier.height(10.dp))
                pairs.forEach { (name, p) ->
                    Row(
                        Modifier.fillMaxWidth().clip(RoundedCornerShape(10.dp)).clickable { onPick(p.first, p.second) }.padding(vertical = 6.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Box(Modifier.size(28.dp).clip(RoundedCornerShape(8.dp)).background(Color(p.first)))
                        Spacer(Modifier.width(4.dp))
                        Box(Modifier.size(28.dp).clip(RoundedCornerShape(8.dp)).background(Color(p.second)))
                        Spacer(Modifier.width(12.dp))
                        Column {
                            Text(name)
                            Text("#${IconRecolor.toHex(p.first)} + #${IconRecolor.toHex(p.second)}", style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                    }
                }
            }
        },
        confirmButton = { TextButton(onClick = onCustom) { Text("Свой основной…") } },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Отмена") } }
    )
}
