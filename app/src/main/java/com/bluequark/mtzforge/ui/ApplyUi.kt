package com.bluequark.mtzforge.ui

import android.Manifest
import android.content.pm.PackageManager
import android.os.Build
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.core.content.ContextCompat
import androidx.lifecycle.compose.LifecycleResumeEffect
import com.bluequark.mtzforge.DiagRow
import com.bluequark.mtzforge.ThemeApply

/** Применение темы и её удержание (вместо Zyper). */
@Composable
fun ApplyDialog(lastPath: String?, onReapply: () -> Unit, onDiagnose: () -> Unit, onDismiss: () -> Unit) {
    val ctx = LocalContext.current
    var keep by remember { mutableStateOf(ThemeApply.isKeepOn(ctx)) }
    var battery by remember { mutableStateOf(ThemeApply.isIgnoringBattery(ctx)) }
    var iconHint by remember { mutableStateOf(false) }
    // галочка «Батарея» обновляется, когда пользователь вернулся из системного окна
    LifecycleResumeEffect(Unit) {
        battery = ThemeApply.isIgnoringBattery(ctx)
        keep = ThemeApply.isKeepOn(ctx)
        onPauseOrDispose { }
    }
    val notifPerm = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) {
        ThemeApply.setKeep(ctx, true); keep = true
    }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Применение темы") },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f)) {
                        Text("Удерживать тему")
                        Text(
                            "HyperOS через какое-то время откатывает сторонние темы. Служба MTZ Forge не даёт ей это сделать (как Zyper).",
                            style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                    Switch(checked = keep, onCheckedChange = { on ->
                        if (on && Build.VERSION.SDK_INT >= 33 &&
                            ContextCompat.checkSelfPermission(ctx, Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED
                        ) {
                            notifPerm.launch(Manifest.permission.POST_NOTIFICATIONS)
                        } else {
                            ThemeApply.setKeep(ctx, on); keep = on
                        }
                    })
                }
                Text(
                    "Чтобы служба не выгружалась, разрешите:",
                    style = MaterialTheme.typography.labelMedium
                )
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedButton(onClick = { ThemeApply.requestBattery(ctx) }, modifier = Modifier.weight(1f)) {
                        Text(if (battery) "Батарея ✓" else "Батарея")
                    }
                    OutlinedButton(onClick = { ThemeApply.openAutostart(ctx) }, modifier = Modifier.weight(1f)) { Text("Автозапуск") }
                }
                if (lastPath != null) {
                    FilledTonalButton(onClick = onReapply, modifier = Modifier.fillMaxWidth()) { Text("Применить последнюю тему ещё раз") }
                    Text(lastPath, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
                FilledTonalButton(onClick = onDiagnose, modifier = Modifier.fillMaxWidth()) { Text("Проверить значки на телефоне") }
                OutlinedButton(onClick = { iconHint = true; ThemeApply.openIconSettings(ctx) }, modifier = Modifier.fillMaxWidth()) {
                    Text("Убрать цветную подложку значков")
                }
                if (iconHint) {
                    Text(
                        "Если значки стали одного цвета с чёрным логотипом — это настройка HyperOS «Фоновый цвет» значков. " +
                                "В открывшихся настройках выберите вариант без заливки («По умолчанию»). " +
                                "Если окно не открылось: настройки рабочего стола → Значки.",
                        style = MaterialTheme.typography.labelSmall
                    )
                }
            }
        },
        confirmButton = { TextButton(onClick = onDismiss) { Text("Готово") } }
    )
}

/** Результат проверки: что показывает система сейчас и что в нашей теме. */
@Composable
fun DiagDialog(rows: List<DiagRow>, onDismiss: () -> Unit) {
    Dialog(onDismissRequest = onDismiss, properties = DialogProperties(usePlatformDefaultWidth = false)) {
        Surface(Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
            Column(Modifier.fillMaxSize().statusBarsPadding().navigationBarsPadding()) {
                Row(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f)) {
                        Text("Проверка значков", style = MaterialTheme.typography.titleLarge)
                        val ok = rows.count { it.ok == true }
                        Text("Применено: $ok из ${rows.size}. Слева — что показывает телефон, справа — значок из темы.",
                            style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                    TextButton(onClick = onDismiss) { Text("Закрыть") }
                }
                LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(bottom = 72.dp)) {
                    items(rows) { r ->
                        Row(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 6.dp), verticalAlignment = Alignment.CenterVertically) {
                            IconBox(r.system?.let { { Image(bitmap = it, contentDescription = null, modifier = Modifier.size(40.dp)) } })
                            Text(" → ", style = MaterialTheme.typography.labelMedium)
                            IconBox(r.ours?.let { { Image(bitmap = it, contentDescription = null, modifier = Modifier.size(40.dp)) } })
                            Spacer(Modifier.width(12.dp))
                            Column(Modifier.weight(1f)) {
                                Text(r.title, maxLines = 1)
                                Text(
                                    (when (r.ok) { true -> "✓ "; false -> "✗ "; null -> "" }) + r.note + " · " + r.where,
                                    style = MaterialTheme.typography.labelSmall,
                                    color = if (r.ok == true) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.error
                                )
                            }
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun IconBox(content: (@Composable () -> Unit)?) {
    Box(
        Modifier.size(44.dp).clip(RoundedCornerShape(10.dp)).background(MaterialTheme.colorScheme.surfaceVariant),
        contentAlignment = Alignment.Center
    ) { content?.invoke() }
}
