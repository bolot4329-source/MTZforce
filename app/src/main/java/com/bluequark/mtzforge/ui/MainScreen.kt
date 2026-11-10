@file:OptIn(androidx.compose.material3.ExperimentalMaterial3Api::class)

package com.bluequark.mtzforge.ui

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
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
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Share
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.AssistChip
import androidx.compose.material3.Button
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExtendedFloatingActionButton
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarDuration
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.produceState
import androidx.compose.foundation.lazy.grid.LazyGridState
import androidx.compose.foundation.lazy.grid.rememberLazyGridState
import com.bluequark.mtzforge.core.Raster
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.lifecycle.viewmodel.compose.viewModel
import com.bluequark.mtzforge.AndroidPng
import com.bluequark.mtzforge.IconItem
import com.bluequark.mtzforge.MainViewModel
import com.bluequark.mtzforge.Origin
import com.bluequark.mtzforge.SysViewModel
import com.bluequark.mtzforge.UiState
import com.bluequark.mtzforge.core.IconRecolor
import com.bluequark.mtzforge.core.ThemePalette
import com.bluequark.mtzforge.colorMap
import com.bluequark.mtzforge.core.PsdReader
import com.bluequark.mtzforge.toBitmap

/** Что перекрашиваем. */
sealed class ColorTarget {
    /** Цвет номер index у одного значка. */
    data class IconColor(val id: Long, val index: Int) : ColorTarget()
    /** Цвет номер index у всех значков (0 — фон, 1 — логотип). */
    data class AllIndex(val index: Int) : ColorTarget()
    /** Цвет темы source — во всех значках, где он встречается. */
    data class Theme(val source: Int) : ColorTarget()
    /** Свой основной цвет, дополнительный подберётся сам. */
    data object PairMain : ColorTarget()
}

/** Подпись цвета значка по номеру. */
fun colorRole(index: Int): String = when (index) { 0 -> "фон"; 1 -> "лого"; else -> "доп. ${index - 1}" }

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun MainScreen(vm: MainViewModel = viewModel(), sysVm: SysViewModel = viewModel()) {
    val s by vm.state.collectAsState()
    val sys by sysVm.state.collectAsState()
    var sysOpen by rememberSaveable { mutableStateOf(false) }
    val snackbar = remember { SnackbarHostState() }
    var selectedId by remember { mutableStateOf<Long?>(null) }
    var colorTarget by remember { mutableStateOf<ColorTarget?>(null) }
    var appPickerFor by remember { mutableStateOf<Long?>(null) }
    var replaceFor by remember { mutableStateOf<Long?>(null) }
    var showExport by remember { mutableStateOf(false) }
    var menu by remember { mutableStateOf(false) }
    var confirmClear by remember { mutableStateOf(false) }
    var paintFor by remember { mutableStateOf<Long?>(null) }
    var showPairs by remember { mutableStateOf(false) }
    var showApply by remember { mutableStateOf(false) }
    val themePalette = remember(s.icons) { IconRecolor.themePalette(s.icons.map { it.colors }) }
    val gridState = rememberLazyGridState()

    val openPsd = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) vm.loadPsd(uri)
    }
    val openMtz = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) vm.loadBaseMtz(uri)
    }
    val pickPngs = rememberLauncherForActivityResult(ActivityResultContracts.GetMultipleContents()) { uris ->
        if (uris.isNotEmpty()) vm.addPngs(uris)
    }
    val replacePng = rememberLauncherForActivityResult(ActivityResultContracts.GetContent()) { uri ->
        val id = replaceFor
        if (uri != null && id != null) vm.replaceWithPng(id, uri)
        replaceFor = null
    }
    val saveMtz = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("application/octet-stream")) { uri ->
        if (uri != null) vm.export(uri, sysVm)
    }

    LaunchedEffect(s.openItemId) {
        val id = s.openItemId
        if (id != null) { selectedId = id; vm.consumeOpenItem() }
    }

    LaunchedEffect(s.message, sysOpen) {
        val m = s.message
        if (m != null && !sysOpen) {
            snackbar.showSnackbar(m, withDismissAction = true, duration = SnackbarDuration.Long)
            vm.consumeMessage()
        }
    }

    // ---- рисование: полноэкранный экран вместо главного
    paintFor?.let { id ->
        val start by produceState<Raster?>(null, id) {
            value = withContext(Dispatchers.Default) { vm.fullRender(id) }
            if (value == null) paintFor = null
        }
        val item = s.icons.firstOrNull { it.id == id }
        val ready = start
        if (item == null) {
            LaunchedEffect(id) { paintFor = null }
        } else if (ready != null) {
            val quick = buildList {
                item.colors.palette.forEachIndexed { i, c -> add(item.colorMap(s.swaps)[i] ?: c) }
                themePalette.main?.let { add(s.swaps[it] ?: it) }
                themePalette.accent?.let { add(s.swaps[it] ?: it) }
                add(0xFFFFFFFF.toInt()); add(0xFF000000.toInt())
            }.distinct()
            PainterScreen(
                start = ready,
                quickColors = quick,
                onCancel = { paintFor = null },
                onDone = { r -> vm.applyPaint(id, r); paintFor = null }
            )
            return
        }
    }

    if (sysOpen) {
        SystemScreen(
            sysVm,
            onBack = { sysOpen = false },
            onEditIcon = { key, title, comp -> sysOpen = false; vm.addSystemIcon(key, title, comp) }
        )
        return
    }

    Scaffold(
        snackbarHost = { SnackbarHost(snackbar) },
        topBar = {
            TopAppBar(
                title = {
                    Column {
                        Text("MTZ Forge", fontWeight = FontWeight.SemiBold)
                        val sub = listOfNotNull(s.psdName, s.baseTitle?.let { "основа: $it" }).joinToString(" · ")
                        if (sub.isNotEmpty()) Text(sub, style = MaterialTheme.typography.labelSmall, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    }
                },
                actions = {
                    Box {
                        IconButton(onClick = { menu = true }) { Icon(Icons.Default.MoreVert, contentDescription = "Меню") }
                        DropdownMenu(expanded = menu, onDismissRequest = { menu = false }) {
                            DropdownMenuItem(text = { Text("Открыть PSD") }, onClick = { menu = false; openPsd.launch(arrayOf("*/*")) })
                            DropdownMenuItem(text = { Text("Открыть тему MTZ (основа)") }, onClick = { menu = false; openMtz.launch(arrayOf("*/*")) })
                            if (s.baseTitle != null) DropdownMenuItem(text = { Text("Убрать тему-основу") }, onClick = { menu = false; vm.clearBase() })
                            DropdownMenuItem(text = { Text("Добавить PNG") }, onClick = { menu = false; pickPngs.launch("image/*") })
                            DropdownMenuItem(text = { Text("Значки внутри приложений") }, onClick = { menu = false; sysOpen = true })
                            DropdownMenuItem(text = { Text("Применение темы и проверка") }, onClick = { menu = false; showApply = true })
                            DropdownMenuItem(text = { Text("Очистить всё") }, onClick = { menu = false; confirmClear = true })
                        }
                    }
                }
            )
        },
        floatingActionButton = {
            if (s.icons.isNotEmpty() || sys.changedApps > 0) {
                ExtendedFloatingActionButton(
                    onClick = { showExport = true },
                    icon = { Icon(Icons.Default.Share, contentDescription = null) },
                    text = { Text("Собрать MTZ") }
                )
            }
        }
    ) { pad ->
        Box(Modifier.fillMaxSize().padding(pad)) {
            if (s.icons.isEmpty()) {
                Welcome(
                    s = s,
                    onPsd = { openPsd.launch(arrayOf("*/*")) },
                    onMtz = { openMtz.launch(arrayOf("*/*")) },
                    onPng = { pickPngs.launch("image/*") },
                    onMode = vm::setPsdMode,
                    onSys = { sysOpen = true },
                    sysChanged = sys.changedApps
                )
            } else {
                Editor(
                    s = s,
                    labelOf = vm::appLabel,
                    theme = themePalette,
                    gridState = gridState,
                    onThemeColor = { colorTarget = ColorTarget.Theme(it) },
                    onPairs = { showPairs = true },
                    onAllBg = { colorTarget = ColorTarget.AllIndex(0) },
                    onAllLogo = { colorTarget = ColorTarget.AllIndex(1) },
                    onResetAll = vm::resetAllColors,
                    onAddPng = { pickPngs.launch("image/*") },
                    onPsd = { openPsd.launch(arrayOf("*/*")) },
                    onMtz = { openMtz.launch(arrayOf("*/*")) },
                    onClick = { selectedId = it.id },
                    onSys = { sysOpen = true },
                    sysChanged = sys.changedApps
                )
            }
        }
    }

    // ---- карточка значка
    val selected = s.icons.firstOrNull { it.id == selectedId }
    if (selected != null) {
        ModalBottomSheet(onDismissRequest = { selectedId = null }) {
            IconDetails(
                item = selected,
                label = vm.appLabel(selected.pkg),
                swaps = s.swaps,
                onColor = { idx -> colorTarget = ColorTarget.IconColor(selected.id, idx) },
                onPaint = { paintFor = selected.id; selectedId = null },
                onReset = { vm.resetColors(selected.id) },
                onReplace = { replaceFor = selected.id; replacePng.launch("image/*") },
                onApp = { appPickerFor = selected.id },
                onRemove = { vm.remove(selected.id); selectedId = null }
            )
        }
    }

    // ---- выбор цвета
    colorTarget?.let { t ->
        val mainNow = themePalette.main?.let { m -> s.swaps[m] ?: m }
        val accentSuggest = buildList {
            if (mainNow != null) {
                add("Светлее основного" to IconRecolor.lighterAccent(mainNow))
                add("Темнее основного" to IconRecolor.darkerShade(mainNow))
            }
        }
        when (t) {
            is ColorTarget.IconColor -> {
                val item = s.icons.firstOrNull { it.id == t.id }
                val cur = item?.let { it.colorMap(s.swaps)[t.index] ?: it.colors.palette.getOrNull(t.index) } ?: 0xFFFFFFFF.toInt()
                ColorPickerDialog(
                    title = "Цвет: ${colorRole(t.index)}",
                    initial = cur,
                    suggestions = accentSuggest,
                    onDismiss = { colorTarget = null },
                    onPick = { c -> vm.setOverride(t.id, t.index, c); colorTarget = null }
                )
            }
            is ColorTarget.AllIndex -> ColorPickerDialog(
                title = (if (t.index == 0) "Цвет фона" else "Цвет логотипа") + " — всем значкам",
                initial = if (t.index == 0) 0xFF000000.toInt() else 0xFFFFFFFF.toInt(),
                suggestions = accentSuggest,
                onDismiss = { colorTarget = null },
                onPick = { c -> vm.setAllIndex(t.index, c); colorTarget = null }
            )
            is ColorTarget.Theme -> {
                val role = when (t.source) { themePalette.main -> "основной"; themePalette.accent -> "дополнительный"; else -> null }
                ColorPickerDialog(
                    title = "Заменить ${role?.let { "$it цвет" } ?: "цвет"} #${IconRecolor.toHex(t.source)} во всех значках",
                    initial = s.swaps[t.source] ?: t.source,
                    suggestions = if (t.source == themePalette.main) emptyList() else accentSuggest,
                    onDismiss = { colorTarget = null },
                    onPick = { c -> vm.setSwap(t.source, c); colorTarget = null }
                )
            }
            ColorTarget.PairMain -> ColorPickerDialog(
                title = "Основной цвет (дополнительный подберётся светлее)",
                initial = mainNow ?: 0xFF749F50.toInt(),
                showReset = false,
                onDismiss = { colorTarget = null },
                onPick = { c -> if (c != null) vm.applyPair(c, IconRecolor.lighterAccent(c)); colorTarget = null }
            )
        }
    }

    if (showPairs) {
        PairsDialog(
            onDismiss = { showPairs = false },
            onPick = { m, a -> vm.applyPair(m, a); showPairs = false },
            onCustom = { showPairs = false; colorTarget = ColorTarget.PairMain }
        )
    }

    // ---- выбор приложения
    appPickerFor?.let { id ->
        AppPickerDialog(
            apps = s.apps,
            current = s.icons.firstOrNull { it.id == id }?.pkg,
            onDismiss = { appPickerFor = null },
            onPick = { pkg -> vm.setPackage(id, pkg); appPickerFor = null }
        )
    }

    if (showExport) {
        ExportDialog(
            s = s,
            sysChanged = sys.changedApps,
            onTitle = vm::setTitle,
            onAuthor = vm::setAuthor,
            onSize = vm::setOutSize,
            onDismiss = { showExport = false },
            onExport = {
                showExport = false
                val name = s.title.ifBlank { "theme" }.replace(Regex("[\\\\/:*?\"<>|]"), "_")
                saveMtz.launch("$name.mtz")
            },
            onApply = {
                showExport = false
                vm.exportAndApply(sysVm)
            }
        )
    }

    if (showApply) {
        ApplyDialog(
            lastPath = s.lastThemePath,
            onReapply = { vm.reapplyLast() },
            onDiagnose = { showApply = false; vm.diagnose() },
            onDismiss = { showApply = false }
        )
    }

    s.diag?.let { rows -> DiagDialog(rows, onDismiss = vm::closeDiag) }

    if (confirmClear) {
        AlertDialog(
            onDismissRequest = { confirmClear = false },
            title = { Text("Очистить всё?") },
            text = { Text("Все значки и правки будут убраны из редактора. Сами файлы PSD/MTZ не трогаются.") },
            confirmButton = { TextButton(onClick = { confirmClear = false; vm.clearAll(); sysVm.clearAll() }) { Text("Очистить") } },
            dismissButton = { TextButton(onClick = { confirmClear = false }) { Text("Отмена") } }
        )
    }

    // ---- индикатор работы
    val busy = s.busy
    if (busy != null) {
        Dialog(
            onDismissRequest = {},
            properties = DialogProperties(dismissOnBackPress = false, dismissOnClickOutside = false)
        ) {
            Surface(shape = RoundedCornerShape(20.dp), tonalElevation = 6.dp) {
                Column(Modifier.padding(24.dp).widthIn(min = 260.dp)) {
                    Text(busy, style = MaterialTheme.typography.titleMedium)
                    Spacer(Modifier.height(16.dp))
                    val p = s.progress
                    if (p != null) LinearProgressIndicator(progress = { p }, modifier = Modifier.fillMaxWidth())
                    else LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
                }
            }
        }
    }
}

@Composable
private fun PsdModeRow(mode: PsdReader.Mode, onMode: (PsdReader.Mode) -> Unit) {
    Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.horizontalScroll(rememberScrollState())) {
        FilterChip(selected = mode == PsdReader.Mode.AUTO, onClick = { onMode(PsdReader.Mode.AUTO) }, label = { Text("Авто") })
        FilterChip(selected = mode == PsdReader.Mode.COMPOSITE, onClick = { onMode(PsdReader.Mode.COMPOSITE) }, label = { Text("Как в Photoshop") })
        FilterChip(selected = mode == PsdReader.Mode.LAYERS, onClick = { onMode(PsdReader.Mode.LAYERS) }, label = { Text("По слоям") })
    }
}

@Composable
private fun Welcome(s: UiState, onPsd: () -> Unit, onMtz: () -> Unit, onPng: () -> Unit, onMode: (PsdReader.Mode) -> Unit, onSys: () -> Unit, sysChanged: Int) {
    Column(
        Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(20.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        Text("Сборка темы значков для HyperOS", style = MaterialTheme.typography.headlineSmall)
        Text(
            "1. Откройте PSD — каждый слой или группа станет значком. Назовите слои именами пакетов " +
                    "(com.android.chrome) или названиями приложений — приложение подберётся само.\n" +
                    "2. По желанию откройте готовую тему MTZ как основу — из неё возьмутся обои, шрифты и остальные значки.\n" +
                    "3. Перекрашивайте фон и логотип по одному или всем сразу, меняйте любой значок на свой PNG.\n" +
                    "4. Нажмите «Собрать MTZ».",
            style = MaterialTheme.typography.bodyMedium
        )
        Spacer(Modifier.height(4.dp))
        Button(onClick = onPsd, modifier = Modifier.fillMaxWidth()) { Text("Открыть PSD") }
        OutlinedButton(onClick = onMtz, modifier = Modifier.fillMaxWidth()) { Text("Открыть тему MTZ (основа)") }
        OutlinedButton(onClick = onPng, modifier = Modifier.fillMaxWidth()) { Text("Добавить PNG-значки") }
        OutlinedButton(onClick = onSys, modifier = Modifier.fillMaxWidth()) {
            Text("Значки внутри приложений" + if (sysChanged > 0) " · изменено приложений: $sysChanged" else "")
        }
        Spacer(Modifier.height(8.dp))
        Text("Как читать PSD", style = MaterialTheme.typography.titleSmall)
        PsdModeRow(s.psdMode, onMode)
        Text(
            "«Как в Photoshop» — точная картинка со всеми эффектами (нужно сохранять PSD с «Максимальной совместимостью»). " +
                    "«По слоям» — если на холсте есть общий фон или значки наложены друг на друга.",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
    }
}

@Composable
private fun Editor(
    s: UiState,
    labelOf: (String?) -> String?,
    theme: ThemePalette,
    gridState: LazyGridState,
    onThemeColor: (Int) -> Unit,
    onPairs: () -> Unit,
    onAllBg: () -> Unit,
    onAllLogo: () -> Unit,
    onResetAll: () -> Unit,
    onAddPng: () -> Unit,
    onPsd: () -> Unit,
    onMtz: () -> Unit,
    onClick: (IconItem) -> Unit,
    onSys: () -> Unit,
    sysChanged: Int,
) {
    Column(Modifier.fillMaxSize()) {
        Row(
            Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()).padding(horizontal = 12.dp, vertical = 4.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            AssistChip(onClick = onPairs, label = { Text("Пары цветов") }, leadingIcon = { Icon(Icons.Default.Edit, null, Modifier.size(18.dp)) })
            AssistChip(onClick = onAllBg, label = { Text("Фон всем") }, leadingIcon = { Icon(Icons.Default.Edit, null, Modifier.size(18.dp)) })
            AssistChip(onClick = onAllLogo, label = { Text("Логотип всем") }, leadingIcon = { Icon(Icons.Default.Edit, null, Modifier.size(18.dp)) })
            AssistChip(onClick = onResetAll, label = { Text("Сбросить цвета") }, leadingIcon = { Icon(Icons.Default.Refresh, null, Modifier.size(18.dp)) })
            AssistChip(onClick = onAddPng, label = { Text("PNG") }, leadingIcon = { Icon(Icons.Default.Add, null, Modifier.size(18.dp)) })
            AssistChip(onClick = onPsd, label = { Text("PSD") }, leadingIcon = { Icon(Icons.Default.Add, null, Modifier.size(18.dp)) })
            AssistChip(onClick = onMtz, label = { Text("Тема-основа") }, leadingIcon = { Icon(Icons.Default.Add, null, Modifier.size(18.dp)) })
            AssistChip(onClick = onSys, label = { Text("Внутри приложений" + if (sysChanged > 0) " ($sysChanged)" else "") }, leadingIcon = { Icon(Icons.Default.Edit, null, Modifier.size(18.dp)) })
        }
        ThemeBar(theme, s.swaps, onThemeColor)
        val noPkg = s.icons.count { it.pkg == null }
        Text(
            "Значков: ${s.icons.size}" + (if (noPkg > 0) " · без приложения: $noPkg (красные — нажмите и выберите приложение)" else ""),
            style = MaterialTheme.typography.labelMedium,
            color = if (noPkg > 0) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(horizontal = 16.dp, vertical = 4.dp)
        )
        LazyVerticalGrid(
            state = gridState,
            columns = GridCells.Adaptive(84.dp),
            contentPadding = PaddingValues(start = 12.dp, end = 12.dp, top = 8.dp, bottom = 100.dp),
            horizontalArrangement = Arrangement.spacedBy(6.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp),
            modifier = Modifier.fillMaxSize()
        ) {
            items(s.icons, key = { it.id }) { item ->
                IconCell(item, labelOf(item.pkg), s.swaps) { onClick(item) }
            }
        }
    }
}

@Composable
private fun IconCell(item: IconItem, label: String?, swaps: Map<Int, Int>, onClick: () -> Unit) {
    val map = item.colorMap(swaps)
    val bmp = remember(item.thumb, item.colors, map) {
        IconRecolor.recolor(item.thumb, item.colors, map).toBitmap().asImageBitmap()
    }
    Column(
        Modifier.clip(RoundedCornerShape(12.dp)).clickable(onClick = onClick).padding(4.dp),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Image(bitmap = bmp, contentDescription = label ?: item.name, modifier = Modifier.size(64.dp))
        Spacer(Modifier.height(4.dp))
        Text(
            label ?: (if (item.origin == Origin.SHARE) item.name else item.pkg) ?: item.name,
            style = MaterialTheme.typography.labelSmall,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            textAlign = TextAlign.Center,
            color = if (item.pkg == null) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurface
        )
    }
}

@Composable
private fun Swatch(c: Int?) {
    Box(
        Modifier.size(18.dp).clip(RoundedCornerShape(5.dp))
            .background(if (c != null) Color(c) else Color.Transparent)
            .border(1.dp, MaterialTheme.colorScheme.outline, RoundedCornerShape(5.dp))
    )
}

@Composable
private fun IconDetails(
    item: IconItem,
    label: String?,
    swaps: Map<Int, Int>,
    onColor: (Int) -> Unit,
    onPaint: () -> Unit,
    onReset: () -> Unit,
    onReplace: () -> Unit,
    onApp: () -> Unit,
    onRemove: () -> Unit,
) {
    val src = remember(item.source) { AndroidPng.decode(item.source) }
    val map = item.colorMap(swaps)
    val big = remember(src, item.colors, map) {
        IconRecolor.recolor(src, item.colors, map).toBitmap().asImageBitmap()
    }
    Column(Modifier.fillMaxWidth().padding(horizontal = 20.dp).padding(bottom = 16.dp).navigationBarsPadding()) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Image(bitmap = big, contentDescription = null, modifier = Modifier.size(128.dp))
            Spacer(Modifier.width(16.dp))
            Column(Modifier.weight(1f)) {
                Text(label ?: item.name, style = MaterialTheme.typography.titleMedium, maxLines = 2, overflow = TextOverflow.Ellipsis)
                Text(
                    item.pkg ?: "Приложение не выбрано",
                    style = MaterialTheme.typography.bodySmall,
                    color = if (item.pkg == null) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurfaceVariant
                )
                Text(
                    "Слой: ${item.name} · ${item.origin.title}" + (if (item.autoMatched) " · приложение подобрано автоматически" else ""),
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }
        Spacer(Modifier.height(14.dp))
        Text("Цвета значка — нажмите, чтобы заменить", style = MaterialTheme.typography.labelLarge)
        Spacer(Modifier.height(8.dp))
        Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            item.colors.palette.forEachIndexed { i, c ->
                Column(
                    Modifier.clip(RoundedCornerShape(10.dp)).clickable { onColor(i) }.padding(4.dp),
                    horizontalAlignment = Alignment.CenterHorizontally
                ) {
                    ColorDot(c, map[i], 40)
                    Spacer(Modifier.height(4.dp))
                    Text(colorRole(i), style = MaterialTheme.typography.labelSmall)
                }
            }
        }
        Spacer(Modifier.height(12.dp))
        FilledTonalButton(onClick = onPaint, modifier = Modifier.fillMaxWidth()) {
            Icon(Icons.Default.Edit, null, Modifier.size(18.dp)); Spacer(Modifier.width(6.dp)); Text("Дорисовать: заливка и кисть")
        }
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            OutlinedButton(onClick = onReplace, modifier = Modifier.weight(1f)) { Text("Заменить PNG") }
            OutlinedButton(onClick = onApp, modifier = Modifier.weight(1f)) { Text("Приложение") }
        }
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            OutlinedButton(onClick = onReset, modifier = Modifier.weight(1f)) { Text("Сбросить цвета") }
            OutlinedButton(onClick = onRemove, modifier = Modifier.weight(1f)) {
                Icon(Icons.Default.Delete, null, Modifier.size(18.dp)); Spacer(Modifier.width(4.dp)); Text("Убрать")
            }
        }
    }
}

@Composable
private fun ExportDialog(
    s: UiState,
    sysChanged: Int,
    onTitle: (String) -> Unit,
    onAuthor: (String) -> Unit,
    onSize: (Int) -> Unit,
    onDismiss: () -> Unit,
    onExport: () -> Unit,
    onApply: () -> Unit,
) {
    val withPkg = s.icons.count { it.pkg != null }
    val noPkg = s.icons.size - withPkg
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Собрать MTZ") },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                OutlinedTextField(value = s.title, onValueChange = onTitle, label = { Text("Название темы") }, singleLine = true, modifier = Modifier.fillMaxWidth())
                OutlinedTextField(value = s.author, onValueChange = onAuthor, label = { Text("Автор") }, singleLine = true, modifier = Modifier.fillMaxWidth())
                Text("Размер значков, px", style = MaterialTheme.typography.labelLarge)
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    listOf(192, 432, 512).forEach { size ->
                        FilterChip(selected = s.outSize == size, onClick = { onSize(size) }, label = { Text("$size") })
                    }
                }
                Text(
                    if (s.baseTitle != null) "Основа: ${s.baseTitle} — обои, шрифты и прочее возьмутся из неё."
                    else "Без основы в теме будут только значки. Надёжнее взять любую рабочую тему как основу.",
                    style = MaterialTheme.typography.bodySmall
                )
                Text(
                    "Значков рабочего стола: $withPkg" + (if (noPkg > 0) "\nБез приложения, пропустятся: $noPkg" else "") +
                            (if (sysChanged > 0) "\nПриложений с заменёнными картинками внутри: $sysChanged" else ""),
                    style = MaterialTheme.typography.bodySmall,
                    color = if (noPkg > 0) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        },
        confirmButton = { TextButton(onClick = onApply, enabled = withPkg > 0 || sysChanged > 0) { Text("Собрать и применить") } },
        dismissButton = {
            Row {
                TextButton(onClick = onExport, enabled = withPkg > 0 || sysChanged > 0) { Text("Только сохранить") }
                TextButton(onClick = onDismiss) { Text("Отмена") }
            }
        }
    )
}
