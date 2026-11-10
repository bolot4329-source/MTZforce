package com.bluequark.mtzforge

import android.app.Application
import android.content.Intent
import android.content.pm.ApplicationInfo
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.graphics.Canvas
import android.net.Uri
import android.util.LruCache
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.bluequark.mtzforge.core.IconRecolor
import com.bluequark.mtzforge.core.ModuleIcon
import com.bluequark.mtzforge.core.MtzTheme
import com.bluequark.mtzforge.core.Raster
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlin.math.min

/** Пункт меню «Поделиться»: конкретный экран (активность) приложения, принимающий файлы или текст. */
class ShareTarget(val pkg: String, val cls: String, val label: String, val appLabel: String, val icon: ImageBitmap?, val isLauncher: Boolean, val kind: ShareKind)

class SysApp(val pkg: String, val label: String, val system: Boolean, val preset: String?, val icon: ImageBitmap?)

/** Правка одной картинки: своя PNG (уже в нужном размере) и/или цвет. */
data class SysEdit(val png: ByteArray? = null, val tint: Int? = null)

data class SysState(
    val apps: List<SysApp> = emptyList(),
    val appsLoading: Boolean = true,
    val showThirdParty: Boolean = false,
    val query: String = "",
    val openPkg: String? = null,
    val openLabel: String? = null,
    val icons: List<SysIcon> = emptyList(),
    val iconsLoading: String? = null,
    val onlyIcons: Boolean = true,
    val edits: Map<String, Map<String, SysEdit>> = emptyMap(),   // пакет → тип/имя → правка
    val pkgTint: Map<String, Int> = emptyMap(),                  // пакет → цвет для всех его иконок
    val message: String? = null,
    val mode: Int = 0,                                          // 0 — экраны приложений, 1 — меню «Поделиться»
    val shareTargets: List<ShareTarget> = emptyList(),
    val shareLoading: Boolean = false,
) {
    /** Сколько приложений затронуто правками. */
    val changedApps: Int get() = (edits.filterValues { it.isNotEmpty() }.keys + pkgTint.keys).size
}

class SysViewModel(app: Application) : AndroidViewModel(app) {

    private val _state = MutableStateFlow(SysState())
    val state: StateFlow<SysState> = _state.asStateFlow()

    private val ctx get() = getApplication<Application>()
    private val lists = HashMap<String, List<SysIcon>>()
    private val resCache = HashMap<String, PkgRes>()
    // кэш превью ограничен по памяти (~32 МБ), а не по количеству картинок
    private val previews = object : LruCache<String, ImageBitmap>(32 * 1024) {
        override fun sizeOf(key: String, value: ImageBitmap): Int = (value.width * value.height * 4 / 1024).coerceAtLeast(1)
    }
    private var appsLoaded = false

    fun consumeMessage() = _state.update { it.copy(message = null) }
    fun setShowThirdParty(v: Boolean) = _state.update { it.copy(showThirdParty = v) }
    fun setQuery(q: String) = _state.update { it.copy(query = q) }
    fun setOnlyIcons(v: Boolean) = _state.update { it.copy(onlyIcons = v) }

    fun clearAll() = _state.update { it.copy(edits = emptyMap(), pkgTint = emptyMap()) }

    fun setMode(m: Int) {
        _state.update { it.copy(mode = m) }
        if (m == 1) ensureShare()
    }

    private var shareLoaded = false

    /** Все экраны «Отправить / Поделиться» установленных приложений. */
    private fun ensureShare() {
        if (shareLoaded) return
        shareLoaded = true
        _state.update { it.copy(shareLoading = true) }
        viewModelScope.launch(Dispatchers.IO) {
            val pm = ctx.packageManager
            val intents = listOf(
                Intent(Intent.ACTION_SEND).setType("text/plain"),
                Intent(Intent.ACTION_SEND).setType("image/*"),
                Intent(Intent.ACTION_SEND).setType("video/*"),
                Intent(Intent.ACTION_SEND).setType("*/*"),
                Intent(Intent.ACTION_SEND_MULTIPLE).setType("*/*"),
            )
            val infos = intents.flatMap { i -> try { pm.queryIntentActivities(i, PackageManager.MATCH_ALL) } catch (e: Exception) { emptyList() } }
            val list = infos.distinctBy { it.activityInfo.packageName + "/" + it.activityInfo.name }.map { ri ->
                val ai = ri.activityInfo
                val label = try { ri.loadLabel(pm).toString() } catch (e: Exception) { ai.name.substringAfterLast('.') }
                val appLabel = try { ai.applicationInfo.loadLabel(pm).toString() } catch (e: Exception) { ai.packageName }
                val icon = try {
                    val d = ri.loadIcon(pm)
                    val bmp = Bitmap.createBitmap(64, 64, Bitmap.Config.ARGB_8888)
                    d.setBounds(0, 0, 64, 64); d.draw(Canvas(bmp))
                    bmp.asImageBitmap()
                } catch (e: Exception) { null }
                val launcher = try { pm.getLaunchIntentForPackage(ai.packageName)?.component?.className == ai.name } catch (e: Exception) { false }
                val kind = try { ShareIcons.classify(pm, ri, withSize = false).kind } catch (e: Exception) { ShareKind.UNKNOWN }
                ShareTarget(ai.packageName, ai.name, label, appLabel, icon, launcher, kind)
            }.sortedWith(compareBy<ShareTarget> { it.appLabel.lowercase() }.thenBy { it.label.lowercase() })
            _state.update { it.copy(shareTargets = list, shareLoading = false) }
        }
    }

    fun ensureApps() {
        if (appsLoaded) return
        appsLoaded = true
        viewModelScope.launch(Dispatchers.IO) {
            val pm = ctx.packageManager
            val presetMap = SystemIcons.presets.toMap()
            val all = try { pm.getInstalledApplications(0) } catch (e: Exception) { emptyList() }
            val apps = all.map { ai ->
                val system = (ai.flags and (ApplicationInfo.FLAG_SYSTEM or ApplicationInfo.FLAG_UPDATED_SYSTEM_APP)) != 0
                val label = presetMap[ai.packageName] ?: try { ai.loadLabel(pm).toString() } catch (e: Exception) { ai.packageName }
                val icon = try {
                    val d = ai.loadIcon(pm)
                    val bmp = Bitmap.createBitmap(64, 64, Bitmap.Config.ARGB_8888)
                    d.setBounds(0, 0, 64, 64); d.draw(Canvas(bmp))
                    bmp.asImageBitmap()
                } catch (e: Exception) { null }
                SysApp(ai.packageName, label, system, presetMap[ai.packageName], icon)
            }
            val order = SystemIcons.presets.map { it.first }
            val sorted = apps.sortedWith(
                compareBy<SysApp> { if (it.preset != null) order.indexOf(it.pkg) else Int.MAX_VALUE }
                    .thenBy { it.label.lowercase() }
            )
            _state.update { it.copy(apps = sorted, appsLoading = false) }
        }
    }

    private fun pkgRes(pkg: String): PkgRes = synchronized(resCache) {
        resCache.getOrPut(pkg) { SystemIcons.open(ctx, pkg) }
    }

    private fun iconsOf(pkg: String, onProgress: (String) -> Unit): List<SysIcon> {
        synchronized(lists) { lists[pkg] }?.let { return it }
        val l = SystemIcons.list(ctx, pkgRes(pkg), onProgress)
        synchronized(lists) { lists[pkg] = l }
        return l
    }

    fun openApp(pkg: String, label: String) {
        _state.update { it.copy(openPkg = pkg, openLabel = label, icons = emptyList(), iconsLoading = "Читаю ресурсы…") }
        viewModelScope.launch(Dispatchers.Default) {
            try {
                val l = iconsOf(pkg) { msg -> _state.update { s -> if (s.openPkg == pkg) s.copy(iconsLoading = msg) else s } }
                _state.update { s ->
                    if (s.openPkg != pkg) s else s.copy(
                        icons = l, iconsLoading = null,
                        message = if (l.isEmpty()) "В этом приложении не нашлось картинок с понятными именами (возможно, имена ресурсов сжаты при сборке)" else null
                    )
                }
            } catch (e: Throwable) {
                _state.update { it.copy(iconsLoading = null, openPkg = null, message = "Не удалось открыть приложение: ${e.message ?: e.javaClass.simpleName}") }
            }
        }
    }

    fun closeApp() = _state.update { it.copy(openPkg = null, openLabel = null, icons = emptyList(), iconsLoading = null) }

    // ------------------------------------------------------------------ правки

    fun editOf(icon: SysIcon): SysEdit? = _state.value.edits[icon.pkg]?.get(icon.key)

    private fun putEdit(icon: SysIcon, f: (SysEdit) -> SysEdit?) = _state.update { s ->
        val m = (s.edits[icon.pkg] ?: emptyMap()).toMutableMap()
        val e = f(m[icon.key] ?: SysEdit())
        if (e == null || (e.png == null && e.tint == null)) m.remove(icon.key) else m[icon.key] = e
        s.copy(edits = s.edits + (icon.pkg to m))
    }

    fun setTint(icon: SysIcon, c: Int?) = putEdit(icon) { it.copy(tint = c) }
    fun reset(icon: SysIcon) = putEdit(icon) { null }
    fun setPkgTint(pkg: String, c: Int?) = _state.update { s ->
        s.copy(pkgTint = if (c == null) s.pkgTint - pkg else s.pkgTint + (pkg to c))
    }
    fun resetPkg(pkg: String) = _state.update { s -> s.copy(edits = s.edits - pkg, pkgTint = s.pkgTint - pkg) }

    fun replacePng(icon: SysIcon, uri: Uri) {
        viewModelScope.launch(Dispatchers.Default) {
            try {
                val bytes = ctx.contentResolver.openInputStream(uri)?.use { it.readBytes() } ?: return@launch
                val fitted = fit(AndroidPng.decode(bytes), icon.w, icon.h)
                val png = AndroidPng.encode(fitted)
                putEdit(icon) { it.copy(png = png) }
            } catch (e: Exception) {
                _state.update { it.copy(message = "Не удалось открыть картинку: ${e.message}") }
            }
        }
    }

    /** Вписать картинку по центру в точный размер оригинала — иначе вёрстка экрана разъедется. */
    private fun fit(src: Raster, w: Int, h: Int): Raster {
        val sq = src.toSquare()
        val side = min(w, h)
        val scaled = sq.scaleTo(side, side)
        if (w == h) return scaled
        val out = Raster(w, h)
        out.drawOver(scaled, (w - side) / 2, (h - side) / 2)
        return out
    }

    /** Итоговая картинка с учётом правок; null — если править нечего (оставить оригинал). */
    private fun edited(icon: SysIcon, s: SysState): Raster? {
        val e = s.edits[icon.pkg]?.get(icon.key)
        val tint = e?.tint ?: if (icon.kind == ResKind.ICON) s.pkgTint[icon.pkg] else null
        if (e?.png == null && tint == null) return null
        val base = e?.png?.let { AndroidPng.decode(it) } ?: SystemIcons.render(pkgRes(icon.pkg), icon) ?: return null
        return if (tint != null) IconRecolor.tint(base, tint) else base
    }

    /** Превью для сетки (вызывать не из главного потока). */
    fun preview(icon: SysIcon): ImageBitmap? {
        val s = _state.value
        val e = s.edits[icon.pkg]?.get(icon.key)
        val tint = e?.tint ?: if (icon.kind == ResKind.ICON) s.pkgTint[icon.pkg] else null
        val cacheKey = "${icon.pkg}/${icon.key}/${e?.png?.contentHashCode()}/$tint"
        previews.get(cacheKey)?.let { return it }
        return try {
            val r = edited(icon, s) ?: SystemIcons.render(pkgRes(icon.pkg), icon) ?: return null
            r.toBitmap().asImageBitmap().also { previews.put(cacheKey, it) }
        } catch (e: Throwable) { null }
    }

    // ------------------------------------------------------------------ сборка

    /** Картинки для модулей MTZ. Считается при сборке, чтобы не держать сотни PNG в памяти. */
    fun buildModules(onProgress: (String) -> Unit): List<ModuleIcon> {
        val s = _state.value
        val pkgs = (s.edits.filterValues { it.isNotEmpty() }.keys + s.pkgTint.keys).toList()
        val out = mutableListOf<ModuleIcon>()
        pkgs.forEachIndexed { pi, pkg ->
            val label = s.apps.firstOrNull { it.pkg == pkg }?.label ?: pkg
            onProgress("Иконки приложения «$label» (${pi + 1} из ${pkgs.size})…")
            val icons = try { iconsOf(pkg) {} } catch (e: Throwable) { emptyList() }
            val edits = s.edits[pkg] ?: emptyMap()
            val targets = icons.filter { it.key in edits || (it.kind == ResKind.ICON && pkg in s.pkgTint) }
            for (icon in targets) {
                val r = try { edited(icon, s) } catch (e: Throwable) { null } ?: continue
                out += ModuleIcon(MtzTheme.moduleName(pkg), icon.type, icon.name, AndroidPng.encode(r))
            }
        }
        return out
    }
}
