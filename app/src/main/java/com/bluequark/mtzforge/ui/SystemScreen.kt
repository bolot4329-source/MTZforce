@file:OptIn(androidx.compose.material3.ExperimentalMaterial3Api::class)

package com.bluequark.mtzforge.ui

import android.content.ComponentName
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
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
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowBack
import androidx.compose.material.icons.filled.Search
import androidx.compose.material3.AssistChip
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarDuration
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.bluequark.mtzforge.ResKind
import com.bluequark.mtzforge.ShareKind
import com.bluequark.mtzforge.core.IconRecolor
import com.bluequark.mtzforge.SysApp
import com.bluequark.mtzforge.SysEdit
import com.bluequark.mtzforge.SysIcon
import com.bluequark.mtzforge.SysState
import com.bluequark.mtzforge.SysViewModel
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

@Composable
fun SystemScreen(
    vm: SysViewModel,
    onBack: () -> Unit,
    onEditIcon: (key: String, title: String, component: ComponentName?) -> Unit,
) {
    val s by vm.state.collectAsState()
    val snackbar = remember { SnackbarHostState() }
    var selectedKey by remember { mutableStateOf<String?>(null) }
    var tintFor by remember { mutableStateOf<SysIcon?>(null) }
    var tintAll by remember { mutableStateOf(false) }
    var replaceFor by remember { mutableStateOf<SysIcon?>(null) }

    LaunchedEffect(Unit) { vm.ensureApps() }
    LaunchedEffect(s.message) {
        val m = s.message
        if (m != null) {
            snackbar.showSnackbar(m, withDismissAction = true, duration = SnackbarDuration.Long)
            vm.consumeMessage()
        }
    }
    BackHandler { if (s.openPkg != null) vm.closeApp() else onBack() }

    val pickPng = rememberLauncherForActivityResult(ActivityResultContracts.GetContent()) { uri ->
        val icon = replaceFor
        if (uri != null && icon != null) vm.replacePng(icon, uri)
        replaceFor = null
    }

    val openPkg = s.openPkg
    Scaffold(
        snackbarHost = { SnackbarHost(snackbar) },
        topBar = {
            TopAppBar(
                title = {
                    Column {
                        Text(if (openPkg == null) "Значки внутри приложений" else (s.openLabel ?: openPkg), maxLines = 1, overflow = TextOverflow.Ellipsis)
                        if (openPkg != null) Text(openPkg, style = MaterialTheme.typography.labelSmall, maxLines = 1)
                    }
                },
                navigationIcon = {
                    IconButton(onClick = { if (openPkg != null) vm.closeApp() else onBack() }) {
                        Icon(Icons.Default.ArrowBack, contentDescription = "Назад")
                    }
                }
            )
        }
    ) { pad ->
        Box(Modifier.fillMaxSize().padding(pad)) {
            if (openPkg == null) AppList(s, vm, onEditIcon)
            else IconGrid(
                s, vm, openPkg,
                onTintAll = { tintAll = true },
                onSelect = { selectedKey = it.key },
                onAppIcon = { onEditIcon(openPkg, s.openLabel ?: openPkg, null) }
            )
        }
    }

    val selected = s.icons.firstOrNull { it.key == selectedKey }
    if (selected != null && openPkg != null) {
        ModalBottomSheet(onDismissRequest = { selectedKey = null }) {
            SysIconDetails(
                icon = selected,
                edit = s.edits[selected.pkg]?.get(selected.key),
                vm = vm,
                version = s.edits.hashCode() + s.pkgTint.hashCode(),
                onReplace = { replaceFor = selected; pickPng.launch("image/*") },
                onTint = { tintFor = selected },
                onReset = { vm.reset(selected) }
            )
        }
    }

    tintFor?.let { icon ->
        ColorPickerDialog(
            title = "Цвет иконки",
            initial = s.edits[icon.pkg]?.get(icon.key)?.tint ?: 0xFFFFFFFF.toInt(),
            onDismiss = { tintFor = null },
            onPick = { c -> vm.setTint(icon, c); tintFor = null }
        )
    }
    if (tintAll && openPkg != null) {
        ColorPickerDialog(
            title = "Цвет всех иконок приложения",
            initial = s.pkgTint[openPkg] ?: 0xFFFFFFFF.toInt(),
            onDismiss = { tintAll = false },
            onPick = { c -> vm.setPkgTint(openPkg, c); tintAll = false }
        )
    }
}

@Composable
private fun AppList(s: SysState, vm: SysViewModel, onEditIcon: (String, String, ComponentName?) -> Unit) {
    Column(Modifier.fillMaxSize()) {
        Row(
            Modifier.padding(horizontal = 16.dp, vertical = 4.dp).horizontalScroll(rememberScrollState()),
            horizontalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            FilterChip(selected = s.mode == 0, onClick = { vm.setMode(0) }, label = { Text("Приложения и их экраны") })
            FilterChip(selected = s.mode == 1, onClick = { vm.setMode(1) }, label = { Text("Меню «Поделиться»") })
        }
        if (s.mode == 1) {
            ShareList(s, vm, onEditIcon)
            return@Column
        }
        Text(
            "«Значок» — значок самого приложения (на рабочем столе, в Настройках, в недавних). " +
                    "Нажатие на строку — картинки внутри приложения: иконки пунктов Настроек, плитки Пункта управления и т.д.",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(horizontal = 16.dp, vertical = 4.dp)
        )
        OutlinedTextField(
            value = s.query,
            onValueChange = vm::setQuery,
            modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 4.dp),
            placeholder = { Text("Поиск приложения") },
            leadingIcon = { Icon(Icons.Default.Search, contentDescription = null) },
            singleLine = true
        )
        Row(Modifier.padding(horizontal = 16.dp)) {
            FilterChip(selected = s.showThirdParty, onClick = { vm.setShowThirdParty(!s.showThirdParty) }, label = { Text("Показать сторонние") })
        }
        if (s.appsLoading) {
            Box(Modifier.fillMaxWidth().padding(32.dp), contentAlignment = Alignment.Center) { CircularProgressIndicator() }
            return@Column
        }
        val q = s.query.trim().lowercase()
        val list = remember(s.apps, q, s.showThirdParty) {
            s.apps.filter { a ->
                (a.system || s.showThirdParty) &&
                        (q.isEmpty() || a.label.lowercase().contains(q) || a.pkg.lowercase().contains(q))
            }
        }
        LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(bottom = 24.dp)) {
            items(list, key = { it.pkg }) { a ->
                AppRow(a, s, onIcon = { onEditIcon(a.pkg, a.label, null) }) { vm.openApp(a.pkg, a.label) }
            }
        }
    }
}

@Composable
private fun AppRow(a: SysApp, s: SysState, onIcon: () -> Unit, onClick: () -> Unit) {
    val changed = (s.edits[a.pkg]?.size ?: 0)
    val tinted = a.pkg in s.pkgTint
    Row(
        Modifier.fillMaxWidth().clickable(onClick = onClick).padding(horizontal = 16.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        val icon = a.icon
        if (icon != null) Image(bitmap = icon, contentDescription = null, modifier = Modifier.size(40.dp)) else Box(Modifier.size(40.dp))
        Spacer(Modifier.width(12.dp))
        Column(Modifier.weight(1f)) {
            Text(a.label, maxLines = 1, overflow = TextOverflow.Ellipsis)
            Text(
                a.pkg + if (!a.system) " · стороннее, может не сработать" else "",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1, overflow = TextOverflow.Ellipsis
            )
        }
        if (changed > 0 || tinted) {
            Text(
                listOfNotNull(if (changed > 0) "изменено: $changed" else null, if (tinted) "цвет" else null).joinToString(" · "),
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.primary
            )
        }
        TextButton(onClick = onIcon) { Text("Значок") }
    }
}

@Composable
private fun ShareList(s: SysState, vm: SysViewModel, onEditIcon: (String, String, ComponentName?) -> Unit) {
    Column(Modifier.fillMaxSize()) {
        Text(
            "Пункты меню «Поделиться». «общий значок» — меняется вместе со значком приложения; " +
                    "«свой значок» — подменяем картинку внутри приложения; «не меняется» — векторный значок, тема HyperOS его не трогает.",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(horizontal = 16.dp, vertical = 4.dp)
        )
        OutlinedTextField(
            value = s.query,
            onValueChange = vm::setQuery,
            modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 4.dp),
            placeholder = { Text("Поиск: приложение или пункт") },
            leadingIcon = { Icon(Icons.Default.Search, contentDescription = null) },
            singleLine = true
        )
        if (s.shareLoading) {
            Box(Modifier.fillMaxWidth().padding(32.dp), contentAlignment = Alignment.Center) { CircularProgressIndicator() }
            return@Column
        }
        val q = s.query.trim().lowercase()
        val list = remember(s.shareTargets, q) {
            s.shareTargets.filter { t ->
                q.isEmpty() || t.label.lowercase().contains(q) || t.appLabel.lowercase().contains(q) || t.cls.lowercase().contains(q)
            }
        }
        Text("Пунктов: ${list.size}", style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(horizontal = 16.dp))
        LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(bottom = 24.dp)) {
            items(list, key = { it.pkg + "/" + it.cls }) { t ->
                Row(
                    Modifier.fillMaxWidth()
                        .clickable { onEditIcon(IconRecolor.themeIconName(t.pkg, t.cls), "${t.label} (${t.appLabel})", ComponentName(t.pkg, t.cls)) }
                        .padding(horizontal = 16.dp, vertical = 8.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    val icon = t.icon
                    if (icon != null) Image(bitmap = icon, contentDescription = null, modifier = Modifier.size(40.dp)) else Box(Modifier.size(40.dp))
                    Spacer(Modifier.width(12.dp))
                    Column(Modifier.weight(1f)) {
                        Text(t.label, maxLines = 1, overflow = TextOverflow.Ellipsis)
                        Text(
                            t.appLabel + " · " + t.kind.short +
                                    if (t.isLauncher) " · это же значок на рабочем столе" else "",
                            style = MaterialTheme.typography.labelSmall,
                            color = if (t.kind == ShareKind.VECTOR) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurfaceVariant,
                            maxLines = 1, overflow = TextOverflow.Ellipsis
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun IconGrid(s: SysState, vm: SysViewModel, pkg: String, onTintAll: () -> Unit, onSelect: (SysIcon) -> Unit, onAppIcon: () -> Unit) {
    val loading = s.iconsLoading
    if (loading != null) {
        Column(Modifier.fillMaxSize(), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.Center) {
            CircularProgressIndicator()
            Spacer(Modifier.height(12.dp))
            Text(loading)
            Text("Первый раз может занять до минуты", style = MaterialTheme.typography.labelSmall)
        }
        return
    }
    val shown = remember(s.icons, s.onlyIcons) { if (s.onlyIcons) s.icons.filter { it.kind == ResKind.ICON } else s.icons }
    val version = s.edits[pkg].hashCode() + (s.pkgTint[pkg] ?: 0)
    Column(Modifier.fillMaxSize()) {
        Row(
            Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()).padding(horizontal = 12.dp, vertical = 4.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            AssistChip(onClick = onAppIcon, label = { Text("Значок приложения") })
            AssistChip(onClick = onTintAll, label = { Text(if (pkg in s.pkgTint) "Цвет всем ✓" else "Цвет всем иконкам") })
            AssistChip(onClick = { vm.resetPkg(pkg) }, label = { Text("Сбросить приложение") })
            FilterChip(selected = s.onlyIcons, onClick = { vm.setOnlyIcons(!s.onlyIcons) }, label = { Text("Только иконки") })
        }
        Text(
            "Показано: ${shown.size} из ${s.icons.size}. Иконки сохраняются в своём точном размере.",
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(horizontal = 16.dp, vertical = 2.dp)
        )
        LazyVerticalGrid(
            columns = GridCells.Adaptive(76.dp),
            contentPadding = PaddingValues(12.dp),
            horizontalArrangement = Arrangement.spacedBy(6.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp),
            modifier = Modifier.fillMaxSize()
        ) {
            items(shown, key = { it.key }) { icon ->
                val edited = s.edits[pkg]?.containsKey(icon.key) == true
                SysCell(icon, vm, version, edited) { onSelect(icon) }
            }
        }
    }
}

@Composable
private fun SysPreview(icon: SysIcon, vm: SysViewModel, version: Int, size: Int) {
    val bmp by produceState<ImageBitmap?>(null, icon.key, icon.pkg, version) {
        value = withContext(Dispatchers.Default) { vm.preview(icon) }
    }
    Box(
        Modifier.size(size.dp).clip(RoundedCornerShape(12.dp)).background(MaterialTheme.colorScheme.surfaceVariant),
        contentAlignment = Alignment.Center
    ) {
        val b = bmp
        if (b != null) Image(bitmap = b, contentDescription = icon.name, contentScale = ContentScale.Fit, modifier = Modifier.size((size * 0.7f).dp))
    }
}

@Composable
private fun SysCell(icon: SysIcon, vm: SysViewModel, version: Int, edited: Boolean, onClick: () -> Unit) {
    Column(
        Modifier.clip(RoundedCornerShape(12.dp)).clickable(onClick = onClick).padding(4.dp),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        SysPreview(icon, vm, version, 56)
        Spacer(Modifier.height(4.dp))
        Text(
            icon.name,
            style = MaterialTheme.typography.labelSmall,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            color = if (edited) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant
        )
    }
}

@Composable
private fun SysIconDetails(
    icon: SysIcon,
    edit: SysEdit?,
    vm: SysViewModel,
    version: Int,
    onReplace: () -> Unit,
    onTint: () -> Unit,
    onReset: () -> Unit,
) {
    Column(Modifier.fillMaxWidth().padding(horizontal = 20.dp).padding(bottom = 16.dp).navigationBarsPadding()) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            SysPreview(icon, vm, version, 120)
            Spacer(Modifier.width(16.dp))
            Column(Modifier.weight(1f)) {
                Text(icon.name, style = MaterialTheme.typography.titleMedium, maxLines = 2, overflow = TextOverflow.Ellipsis)
                Text("${icon.type} · ${icon.w}×${icon.h} px", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                if (edit?.png != null) Text("Заменено своей картинкой", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.primary)
                if (edit?.tint != null) Text("Свой цвет", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.primary)
            }
        }
        if (icon.kind != ResKind.ICON) {
            Spacer(Modifier.height(8.dp))
            Text("Внимание: это ${icon.kind.title}.", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error)
        }
        Spacer(Modifier.height(8.dp))
        Text(
            "Многие системные иконки система сама окрашивает в цвет темы. Тогда сработает замена формы, а цвет останется системным.",
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
        Spacer(Modifier.height(12.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            FilledTonalButton(onClick = onReplace, modifier = Modifier.weight(1f)) { Text("Заменить PNG") }
            FilledTonalButton(onClick = onTint, modifier = Modifier.weight(1f)) { Text("Цвет") }
        }
        OutlinedButton(onClick = onReset, modifier = Modifier.fillMaxWidth()) { Text("Вернуть оригинал") }
    }
}
