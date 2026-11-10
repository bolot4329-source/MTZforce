package com.bluequark.mtzforge

import android.app.Application
import android.content.ComponentName
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.graphics.Canvas
import android.net.Uri
import android.provider.OpenableColumns
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.bluequark.mtzforge.core.IconColors
import com.bluequark.mtzforge.core.IconRecolor
import com.bluequark.mtzforge.core.MtzTheme
import com.bluequark.mtzforge.core.PsdReader
import com.bluequark.mtzforge.core.Raster
import com.bluequark.mtzforge.core.ThemeIcon
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import java.io.File

enum class Origin(val title: String) {
    PSD("из PSD"), MTZ("из темы"), PNG("PNG"),
    SYSTEM("текущий значок приложения"), SHARE("пункт меню «Поделиться»")
}

/** Один значок в редакторе. */
data class IconItem(
    val id: Long,
    val name: String,
    val pkg: String?,
    val autoMatched: Boolean,
    val origin: Origin,
    val basePkg: String?,          // для значков из темы-основы: исходный пакет
    val replaced: Boolean,         // картинку заменили на PNG
    val source: ByteArray,         // исходник (PNG, до 512 px)
    val thumb: Raster,             // миниатюра для сетки
    val colors: IconColors,                  // цвета значка: фон, логотип, дополнительные…
    val overrides: Map<Int, Int> = emptyMap(),  // номер цвета → новый цвет (только у этого значка)
    val component: String? = null,            // для пунктов «Поделиться»: пакет/экран
) {
    val modified: Boolean get() = overrides.isNotEmpty() || replaced || pkg != basePkg
}

/**
 * Итоговая замена цветов значка: своя правка значка важнее общей замены цвета темы.
 * Общая замена срабатывает, если цвет значка близок к заменяемому цвету темы.
 */
fun IconItem.colorMap(swaps: Map<Int, Int>): Map<Int, Int> {
    if (overrides.isEmpty() && swaps.isEmpty()) return emptyMap()
    val out = HashMap<Int, Int>()
    colors.palette.forEachIndexed { i, c ->
        val v = overrides[i] ?: IconRecolor.matchTheme(c, swaps.keys.toList())?.let { swaps[it] }
        if (v != null) out[i] = v
    }
    return out
}

/** Значок с применёнными цветами. */
fun IconItem.render(src: Raster, swaps: Map<Int, Int>): Raster = IconRecolor.recolor(src, colors, colorMap(swaps))

class AppInfo(val label: String, val pkg: String, val icon: ImageBitmap?)

data class UiState(
    val icons: List<IconItem> = emptyList(),
    val busy: String? = null,
    val progress: Float? = null,
    val message: String? = null,
    val psdName: String? = null,
    val baseTitle: String? = null,
    val psdMode: PsdReader.Mode = PsdReader.Mode.AUTO,
    val title: String = "Моя тема",
    val author: String = "",
    val outSize: Int = 432,
    val apps: List<AppInfo> = emptyList(),
    val swaps: Map<Int, Int> = emptyMap(),   // цвет темы → новый цвет (для всех значков сразу)
    val openItemId: Long? = null,            // открыть карточку этого значка
    val lastThemePath: String? = null,       // последняя тема, сохранённая в «Загрузки»
    val diag: List<DiagRow>? = null,         // результат проверки значков
)

/** Строка проверки: что система показывает сейчас и что в нашей теме. */
class DiagRow(val title: String, val where: String, val system: ImageBitmap?, val ours: ImageBitmap?, val ok: Boolean?, val note: String)

class MainViewModel(app: Application) : AndroidViewModel(app) {

    private val _state = MutableStateFlow(UiState())
    val state: StateFlow<UiState> = _state.asStateFlow()

    private var nextId = 1L
    private var base: MtzTheme? = null
    private val ctx get() = getApplication<Application>()

    init {
        viewModelScope.launch(Dispatchers.IO) { loadApps() }
        ThemeApply.ensureKeeper(app)
    }

    fun consumeMessage() = _state.update { it.copy(message = null) }
    fun consumeOpenItem() = _state.update { it.copy(openItemId = null) }

    /**
     * Взять текущий значок приложения (component == null) или экрана «Поделиться» (component задан)
     * и добавить в тему для правки. key — имя значка в теме: пакет или полное имя экрана.
     */
    fun addSystemIcon(key: String, title: String, component: ComponentName?) = task("Беру значок…") {
        _state.value.icons.firstOrNull { it.pkg == key }?.let { ex ->
            _state.update { it.copy(openItemId = ex.id) }
            return@task
        }
        val pm = ctx.packageManager
        // для пункта «Поделиться» — тот значок, что показывает само меню (с учётом иконки фильтра)
        val d = if (component != null) ShareIcons.resolve(pm, component)?.loadIcon(pm) ?: pm.getActivityIcon(component)
        else pm.getApplicationIcon(key)
        val bmp = Bitmap.createBitmap(512, 512, Bitmap.Config.ARGB_8888)
        d.setBounds(0, 0, 512, 512)
        d.draw(Canvas(bmp))
        val r = bmp.toRaster()
        bmp.recycle()
        val item = makeItem(title, r, if (component != null) Origin.SHARE else Origin.SYSTEM, key, null, false)
            .copy(component = component?.flattenToString())
        _state.update {
            it.copy(
                icons = it.icons + item,
                openItemId = item.id,
                message = "Значок добавлен в тему: меняйте цвета, дорисуйте или замените на PNG"
            )
        }
    }
    fun setPsdMode(m: PsdReader.Mode) = _state.update { it.copy(psdMode = m) }
    fun setTitle(t: String) = _state.update { it.copy(title = t) }
    fun setAuthor(a: String) = _state.update { it.copy(author = a) }
    fun setOutSize(s: Int) = _state.update { it.copy(outSize = s) }

    fun appLabel(pkg: String?): String? = pkg?.let { p -> _state.value.apps.firstOrNull { it.pkg == p }?.label }

    // ------------------------------------------------------------------ загрузка

    private fun displayName(uri: Uri): String {
        try {
            ctx.contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)?.use { c ->
                if (c.moveToFirst()) {
                    val v = c.getString(0)
                    if (!v.isNullOrBlank()) return v
                }
            }
        } catch (_: Exception) { }
        return uri.lastPathSegment?.substringAfterLast('/') ?: "file"
    }

    private fun copyToCache(uri: Uri, name: String): File {
        val f = File(ctx.cacheDir, name)
        ctx.contentResolver.openInputStream(uri)?.use { input ->
            f.outputStream().use { input.copyTo(it, 1 shl 16) }
        } ?: throw IllegalStateException("Не удалось открыть файл")
        return f
    }

    private fun task(busy: String, block: suspend () -> Unit) {
        viewModelScope.launch(Dispatchers.Default) {
            _state.update { it.copy(busy = busy, progress = null) }
            try {
                block()
            } catch (e: OutOfMemoryError) {
                _state.update { it.copy(message = "Не хватило памяти. Попробуйте PSD поменьше или режим «По слоям»") }
            } catch (e: Exception) {
                _state.update { it.copy(message = "Ошибка: ${e.message ?: e.javaClass.simpleName}") }
            } finally {
                _state.update { it.copy(busy = null, progress = null) }
            }
        }
    }

    private fun makeItem(name: String, raster: Raster, origin: Origin, pkg: String?, basePkg: String?, auto: Boolean): IconItem {
        var src = raster.toSquare()
        if (src.width > 512) src = src.scaleTo(512, 512)
        val colors = IconRecolor.analyze(src)
        return IconItem(
            id = nextId++, name = name, pkg = pkg, autoMatched = auto, origin = origin, basePkg = basePkg,
            replaced = false, source = AndroidPng.encode(src), thumb = src.scaleTo(128, 128), colors = colors,
        )
    }

    fun loadPsd(uri: Uri) = task("Читаю PSD…") {
        val name = displayName(uri)
        val file = copyToCache(uri, "source.psd")
        val newItems = mutableListOf<IconItem>()
        val res = try {
            PsdReader(file).read(
                mode = _state.value.psdMode,
                onProgress = { i, n ->
                    _state.update { it.copy(progress = if (n == 0) null else i.toFloat() / n, busy = "Вырезаю значки… $i из $n") }
                },
                onIcon = { ic ->
                    val (pkg, auto) = matchPackage(ic.name)
                    newItems += makeItem(ic.name, ic.raster, Origin.PSD, pkg, null, auto)
                }
            )
        } finally {
            file.delete()
        }
        val newPkgs = newItems.mapNotNull { it.pkg }.toSet()
        _state.update { s ->
            s.copy(
                icons = s.icons.filter { it.origin != Origin.PSD && (it.pkg == null || it.pkg !in newPkgs) } + newItems,
                psdName = name,
                message = buildString {
                    append("Найдено значков: ${newItems.size}")
                    val noPkg = newItems.count { it.pkg == null }
                    if (noPkg > 0) append(", без приложения: $noPkg")
                    append(if (res.usedComposite) " · как в Photoshop" else " · по слоям")
                    res.warnings.firstOrNull()?.let { append("\n").append(it) }
                }
            )
        }
    }

    fun loadBaseMtz(uri: Uri) = task("Открываю тему…") {
        val name = displayName(uri)
        val file = copyToCache(uri, "base.mtz")
        val theme = MtzTheme.read(file)
        base = theme
        val existing = _state.value.icons.mapNotNull { it.pkg }.toSet()
        val baseIcons = theme.appIcons()
        val items = baseIcons.filter { it.pkg !in existing }.mapIndexedNotNull { i, ti ->
            _state.update { it.copy(progress = i.toFloat() / baseIcons.size.coerceAtLeast(1)) }
            try { makeItem(ti.pkg, AndroidPng.decode(ti.png), Origin.MTZ, ti.pkg, ti.pkg, false) } catch (e: Exception) { null }
        }
        _state.update { s ->
            s.copy(
                icons = s.icons.filter { it.origin != Origin.MTZ } + items,
                baseTitle = theme.title ?: name,
                title = if (s.title == "Моя тема" && theme.title != null) theme.title + " (мод)" else s.title,
                message = "Тема-основа: ${theme.title ?: name} · значков в ней: ${baseIcons.size}"
            )
        }
    }

    fun clearBase() {
        base = null
        _state.update { s -> s.copy(baseTitle = null, icons = s.icons.filter { it.origin != Origin.MTZ }) }
    }

    fun addPngs(uris: List<Uri>) = task("Добавляю картинки…") {
        val items = uris.mapNotNull { uri ->
            try {
                val n = displayName(uri).substringBeforeLast('.')
                val bytes = ctx.contentResolver.openInputStream(uri)?.use { it.readBytes() } ?: return@mapNotNull null
                val (pkg, auto) = matchPackage(n)
                makeItem(n, AndroidPng.decode(bytes), Origin.PNG, pkg, null, auto)
            } catch (e: Exception) { null }
        }
        val pkgs = items.mapNotNull { it.pkg }.toSet()
        _state.update { s ->
            s.copy(
                icons = s.icons.filter { it.pkg == null || it.pkg !in pkgs } + items,
                message = "Добавлено картинок: ${items.size}" + items.count { it.pkg == null }.let { if (it > 0) ", выберите для них приложение (значки с красной подписью)" else "" }
            )
        }
    }

    fun replaceWithPng(id: Long, uri: Uri) = task("Заменяю значок…") {
        val bytes = ctx.contentResolver.openInputStream(uri)?.use { it.readBytes() } ?: return@task
        var src = AndroidPng.decode(bytes).toSquare()
        if (src.width > 512) src = src.scaleTo(512, 512)
        val colors = IconRecolor.analyze(src)
        val png = AndroidPng.encode(src)
        val thumb = src.scaleTo(128, 128)
        updateItem(id) { it.copy(source = png, thumb = thumb, colors = colors, overrides = emptyMap(), replaced = true) }
    }

    // ------------------------------------------------------------------ правка

    private fun updateItem(id: Long, f: (IconItem) -> IconItem) =
        _state.update { s -> s.copy(icons = s.icons.map { if (it.id == id) f(it) else it }) }

    /** Цвет номер index у одного значка (0 — фон, 1 — логотип, дальше дополнительные). */
    fun setOverride(id: Long, index: Int, c: Int?) = updateItem(id) {
        it.copy(overrides = if (c == null) it.overrides - index else it.overrides + (index to c))
    }
    fun resetColors(id: Long) = updateItem(id) { it.copy(overrides = emptyMap()) }

    /** Цвет номер index у всех значков (например, фон всем). */
    fun setAllIndex(index: Int, c: Int?) = _state.update { s ->
        s.copy(icons = s.icons.map { if (index >= it.colors.palette.size) it else it.copy(overrides = if (c == null) it.overrides - index else it.overrides + (index to c)) })
    }

    /** Заменить цвет темы во всех значках, где он встречается. */
    fun setSwap(source: Int, c: Int?) = _state.update { s ->
        // убираем и близкие старые ключи: цвета темы немного «плывут» при пересчёте
        val cleaned = s.swaps.filterKeys { IconRecolor.dist(it, source) > IconRecolor.THEME_MATCH }
        s.copy(swaps = if (c == null) cleaned else cleaned + (source to c))
    }

    /** Пара «основной + дополнительный», как на примерах. */
    fun applyPair(main: Int, accent: Int) = _state.update { s ->
        val theme = IconRecolor.themePalette(s.icons.map { it.colors })
        var sw = s.swaps
        fun put(src: Int, c: Int) { sw = sw.filterKeys { IconRecolor.dist(it, src) > IconRecolor.THEME_MATCH } + (src to c) }
        theme.main?.let { put(it, main) }
        theme.accent?.let { put(it, accent) }
        s.copy(swaps = sw, message = if (theme.accent == null) "Дополнительный цвет в значках не найден — изменён только основной" else null)
    }

    fun resetAllColors() = _state.update { s -> s.copy(swaps = emptyMap(), icons = s.icons.map { it.copy(overrides = emptyMap()) }) }

    /** Значок в полном размере с текущими цветами — для редактора рисования. */
    fun fullRender(id: Long): Raster? {
        val s = _state.value
        val item = s.icons.firstOrNull { it.id == id } ?: return null
        return item.render(AndroidPng.decode(item.source), s.swaps)
    }

    /** Результат рисования становится новым исходником значка (цвета «впекаются»). */
    fun applyPaint(id: Long, r: Raster) = task("Сохраняю значок…") {
        val colors = IconRecolor.analyze(r)
        val png = AndroidPng.encode(r)
        val thumb = r.scaleTo(128, 128)
        updateItem(id) { it.copy(source = png, thumb = thumb, colors = colors, overrides = emptyMap(), replaced = true) }
    }

    fun setPackage(id: Long, pkg: String) {
        val p = pkg.trim()
        if (p.isEmpty()) return
        _state.update { s ->
            // у одного приложения может быть только один значок
            val others = s.icons.filter { it.id != id && it.pkg == p }
            s.copy(
                icons = s.icons.filter { o -> others.none { it.id == o.id } }.map { if (it.id == id) it.copy(pkg = p, autoMatched = false) else it },
                message = if (others.isNotEmpty()) "Прежний значок для $p убран" else null
            )
        }
    }

    fun remove(id: Long) = _state.update { s -> s.copy(icons = s.icons.filter { it.id != id }) }

    fun clearAll() {
        base = null
        _state.update { s -> s.copy(icons = emptyList(), psdName = null, baseTitle = null, swaps = emptyMap()) }
    }

    // ------------------------------------------------------------------ экспорт

    private class Built(val icons: Int, val modules: Int, val fromBase: Int, val skipped: Int, val notes: List<String>)

    /** Собирает тему в поток. Вызывается из export() и exportAndApply(). */
    private fun buildTheme(out: java.io.OutputStream, sys: SysViewModel): Built {
        val s = _state.value
        val theme = base
        val size = s.outSize
        val pm = ctx.packageManager
        // последний значок для пакета побеждает
        val withPkg = s.icons.filter { it.pkg != null }.reversed().distinctBy { it.pkg }.reversed()
        val toWrite = withPkg.filter { theme == null || it.origin != Origin.MTZ || it.modified || it.colorMap(s.swaps).isNotEmpty() }
        val explicitKeys = withPkg.mapNotNull { it.pkg }.toSet()
        val icons = mutableListOf<ThemeIcon>()
        val shareModules = mutableListOf<com.bluequark.mtzforge.core.ModuleIcon>()
        val notes = mutableListOf<String>()
        toWrite.forEachIndexed { i, item ->
            _state.update { st -> st.copy(progress = i.toFloat() / toWrite.size.coerceAtLeast(1), busy = "Собираю MTZ… $i из ${toWrite.size}") }
            val full = item.render(AndroidPng.decode(item.source), s.swaps)
            val png = AndroidPng.encode(full.scaleTo(size, size))
            val key = item.pkg!!
            val owner = ownerOf(key)
            icons += ThemeIcon(key, png, owner)
            // Значок приложения: HyperOS ищет его и по имени каждого экрана-ярлыка.
            // У приложений, где ярлык — отдельный экран со своим значком (ВКонтакте), иначе не сработает.
            if (owner == null && '#' !in key) {
                for (k in ShareIcons.launcherKeys(pm, key)) if (k != key && k !in explicitKeys) icons += ThemeIcon(k, png, key)
            }
            // Пункт «Поделиться» со своим значком: подменяем сам ресурс значка внутри приложения.
            val cn = item.component?.let { android.content.ComponentName.unflattenFromString(it) }
            if (cn != null) {
                val info = ShareIcons.classify(ctx, cn)
                shareModules += ShareIcons.moduleWrites(info, cn.packageName, full)
                if (info.kind == ShareKind.VECTOR) notes += "«${item.name}»: векторный значок, HyperOS может его не заменить"
            }
        }
        val basePkgs = theme?.appIcons()?.map { it.pkg }?.toSet() ?: emptySet()
        val removed = basePkgs - explicitKeys - icons.map { it.pkg }.toSet()
        val preview = if (withPkg.isEmpty()) null else AndroidPng.encode(Raster.grid(
            withPkg.take(24).map { it.render(it.thumb, s.swaps) },
            cols = 4, cell = 128, gap = 22, background = 0xFF0E0E10.toInt()
        ))
        _state.update { it.copy(progress = null) }
        val modules = sys.buildModules { msg -> _state.update { st -> st.copy(busy = msg) } } + shareModules
        _state.update { it.copy(busy = "Записываю файл…") }
        MtzTheme.write(out, theme, icons, removed, s.title, s.author, preview, modules)
        return Built(toWrite.size, modules.size, withPkg.size - toWrite.size, s.icons.count { it.pkg == null }, notes)
    }

    private fun summary(b: Built, prefix: String): String =
        prefix + " Значков: ${b.icons}" +
                (if (b.modules > 0) ", картинок внутри приложений: ${b.modules}" else "") +
                (if (base != null) " (+ ${b.fromBase} без изменений из темы)" else "") +
                (if (b.skipped > 0) "\nПропущено без приложения: ${b.skipped}" else "") +
                b.notes.take(3).joinToString("") { "\n$it" }

    fun export(uri: Uri, sys: SysViewModel) = task("Собираю MTZ…") {
        val b = ctx.contentResolver.openOutputStream(uri)?.use { out -> buildTheme(out, sys) }
            ?: throw IllegalStateException("Не удалось сохранить файл")
        _state.update { it.copy(message = summary(b, "Готово!")) }
    }

    /** Собрать, положить в «Загрузки/MTZ Forge» и сразу открыть в «Темах». */
    fun exportAndApply(sys: SysViewModel) = task("Собираю MTZ…") {
        var built: Built? = null
        val path = ThemeApply.saveToDownloads(ctx, _state.value.title) { out -> built = buildTheme(out, sys) }
        val opened = ThemeApply.apply(ctx, path)
        val b = built
        _state.update {
            it.copy(
                lastThemePath = path,
                message = (if (b != null) summary(b, "Тема сохранена: Загрузки/${ThemeApply.FOLDER}.") else "Тема сохранена.") +
                        if (opened) "\nОткрываю «Темы» — подтвердите применение. Если открылся выбор файла — выберите его в Загрузки/${ThemeApply.FOLDER}." else
                            "\nНе удалось открыть «Темы» напрямую. Импортируйте файл вручную: Темы → Профиль → Импорт."
            )
        }
    }

    fun reapplyLast() {
        val p = _state.value.lastThemePath ?: return
        if (!ThemeApply.apply(ctx, p)) _state.update { it.copy(message = "Не удалось открыть «Темы»") }
    }

    // ------------------------------------------------------------------ проверка значков

    /** Что система показывает сейчас для наших значков (тема Xiaomi подставляется и в нашем процессе). */
    fun diagnose() = task("Проверяю значки…") {
        val s = _state.value
        val pm = ctx.packageManager
        val rows = mutableListOf<DiagRow>()
        val list = s.icons.filter { it.pkg != null }.take(150)
        list.forEachIndexed { i, item ->
            _state.update { st -> st.copy(progress = i.toFloat() / list.size.coerceAtLeast(1)) }
            val key = item.pkg!!
            val cn = item.component?.let { android.content.ComponentName.unflattenFromString(it) }
            val kind = cn?.let { ShareIcons.classify(ctx, it).kind }
            val full = item.render(item.thumb, s.swaps).scaleTo(64, 64)
            // адаптивный значок система рисует нашей картинкой в центральных 2/3
            val ours = if (kind == ShareKind.ADAPTIVE) Raster(64, 64).also { it.drawOver(full.scaleTo(43, 43), 10, 10) } else full
            var where = "—"
            val d = try {
                if (cn != null) {
                    where = "меню «Поделиться»"
                    ShareIcons.resolve(pm, cn)?.loadIcon(pm)
                } else {
                    where = "рабочий стол"
                    val owner = ownerOf(key)
                    val comp = when {
                        '#' in key -> android.content.ComponentName(key.substringBefore('#'), key.substringAfter('#'))
                        owner != null -> android.content.ComponentName(owner, key)
                        else -> pm.getLaunchIntentForPackage(key)?.component
                    }
                    if (comp != null) pm.getActivityIcon(comp) else pm.getApplicationIcon(key)
                }
            } catch (e: Exception) { null }
            val sys = d?.let {
                val bmp = Bitmap.createBitmap(64, 64, Bitmap.Config.ARGB_8888)
                it.setBounds(0, 0, 64, 64); it.draw(Canvas(bmp))
                bmp.toRaster().also { bmp.recycle() }
            }
            val diff = sys?.let { iconDiff(it, ours) }
            val ok = diff?.let { it < 45.0 }
            val note = when {
                sys == null -> "приложение не найдено на телефоне"
                ok == true -> "значок из темы применён"
                kind == ShareKind.VECTOR -> "векторный значок — тема его не меняет"
                else -> "показан другой значок"
            }
            rows += DiagRow(item.name, where, sys?.toBitmap()?.asImageBitmap(), ours.toBitmap().asImageBitmap(), ok, note)
        }
        _state.update { it.copy(diag = rows.sortedBy { r -> r.ok == true }, progress = null) }
    }

    fun closeDiag() = _state.update { it.copy(diag = null) }

    /** Средняя разница цвета в центре двух значков 64×64 (0 — одинаковые). Края не считаем: там маска формы значков. */
    private fun iconDiff(a: Raster, b: Raster): Double {
        var sum = 0.0; var n = 0
        for (i in a.pixels.indices) {
            val x = i % 64; val y = i / 64
            if (x < 14 || x >= 50 || y < 14 || y >= 50) continue
            val p = a.pixels[i]; val q = b.pixels[i]
            val pa = p ushr 24; val qa = q ushr 24
            if (pa < 128 && qa < 128) continue
            n++
            if ((pa < 128) != (qa < 128)) { sum += 255.0; continue }
            sum += (kotlin.math.abs(((p shr 16) and 0xFF) - ((q shr 16) and 0xFF)) +
                    kotlin.math.abs(((p shr 8) and 0xFF) - ((q shr 8) and 0xFF)) +
                    kotlin.math.abs((p and 0xFF) - (q and 0xFF))) / 3.0
        }
        return if (n == 0) 255.0 else sum / n
    }

    // ------------------------------------------------------------------ приложения

    /** Пакет приложения для имени значка экрана (null — имя и есть пакет). */
    private fun ownerOf(key: String): String? {
        if ('#' in key) return key.substringBefore('#')
        // имя экрана кончается на имя класса с заглавной буквы; иначе это имя пакета (пусть и не установленного)
        if (key.substringAfterLast('.').firstOrNull()?.isUpperCase() != true) return null
        val pm = ctx.packageManager
        fun installed(p: String) = try { pm.getPackageInfo(p, 0); true } catch (e: Exception) { false }
        if (installed(key)) return null
        var k = key
        while ('.' in k) {
            k = k.substringBeforeLast('.')
            if (k.count { it == '.' } < 1) break
            if (installed(k)) return k
        }
        return null
    }

    private fun loadApps() {
        val pm = ctx.packageManager
        val intent = Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER)
        val list = try {
            pm.queryIntentActivities(intent, PackageManager.MATCH_ALL)
        } catch (e: Exception) { emptyList() }
        // если у пакета несколько значков на рабочем столе (Телефон и Контакты в com.android.contacts),
        // каждый получает своё имя: пакет.Активность — так их различают темы MIUI/HyperOS
        val multi = list.groupBy { it.activityInfo.packageName }.filterValues { it.size > 1 }.keys
        val perActivity = list.distinctBy { it.activityInfo.packageName + "/" + it.activityInfo.name }.map { ri ->
            val pkg = if (ri.activityInfo.packageName in multi) IconRecolor.themeIconName(ri.activityInfo.packageName, ri.activityInfo.name)
            else ri.activityInfo.packageName
            val label = try { ri.loadLabel(pm).toString() } catch (e: Exception) { pkg }
            val icon = try {
                val d = ri.loadIcon(pm)
                val bmp = Bitmap.createBitmap(96, 96, Bitmap.Config.ARGB_8888)
                val c = Canvas(bmp)
                d.setBounds(0, 0, 96, 96)
                d.draw(c)
                bmp.asImageBitmap()
            } catch (e: Exception) { null }
            AppInfo(label, pkg, icon)
        }
        // и общий значок пакета — его HyperOS берёт, если у экрана нет своего
        val packageLevel = multi.mapNotNull { p ->
            try {
                val ai = pm.getApplicationInfo(p, 0)
                AppInfo(ai.loadLabel(pm).toString() + " (общий значок)", p, perActivity.firstOrNull { it.pkg.startsWith(p) }?.icon)
            } catch (e: Exception) { null }
        }
        val apps = (perActivity + packageLevel).sortedBy { it.label.lowercase() }
        _state.update { s ->
            // доподбираем пакеты для уже загруженных значков
            val fixed = s.icons.map { ic ->
                if (ic.pkg != null) ic else {
                    val (p, auto) = matchPackage(ic.name, apps)
                    if (p != null) ic.copy(pkg = p, autoMatched = auto) else ic
                }
            }
            s.copy(apps = apps, icons = fixed)
        }
    }

    private val pkgRegex = Regex("^[a-zA-Z][a-zA-Z0-9_]*(\\.[a-zA-Z0-9_]+)+$")

    private fun norm(s: String): String =
        s.lowercase()
            .replace(Regex("^(blue|ic|icon|app|oled)[_\\- ]+"), "")
            .replace(Regex("[^\\p{L}\\p{N}]"), "")

    /** Подбор пакета по имени слоя/файла: «com.android.chrome», «Telegram», «blue_youtube»… */
    private fun matchPackage(rawName: String, apps: List<AppInfo> = _state.value.apps): Pair<String?, Boolean> {
        val name = rawName.trim().removeSuffix(".png").removeSuffix(".PNG")
        if (pkgRegex.matches(name)) return name to false
        val n = norm(name)
        if (n.length < 3) return null to false
        apps.firstOrNull { norm(it.label) == n }?.let { return it.pkg to true }
        apps.firstOrNull { a -> a.pkg.lowercase().split('.').any { it == n } }?.let { return it.pkg to true }
        if (n.length >= 4) {
            apps.firstOrNull { a -> val l = norm(a.label); l.length >= 4 && (l.startsWith(n) || n.startsWith(l)) }
                ?.let { return it.pkg to true }
        }
        return null to false
    }
}
