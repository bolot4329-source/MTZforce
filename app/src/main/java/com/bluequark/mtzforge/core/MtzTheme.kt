package com.bluequark.mtzforge.core

import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.OutputStream
import java.util.zip.ZipEntry
import java.util.zip.ZipFile
import java.util.zip.ZipInputStream
import java.util.zip.ZipOutputStream

/** Значок приложения внутри темы. */
/** pkg — имя значка в теме (пакет, полное имя экрана или «пакет#экран»); owner — пакет приложения, если имя не пакет. */
class ThemeIcon(val pkg: String, val png: ByteArray, val owner: String? = null)

/**
 * Картинка внутри приложения (иконка экрана Настроек, плитка шторки…).
 * module — имя модуля в корне MTZ (пакет или «framework-res»), type — drawable/mipmap, resName — имя ресурса.
 */
class ModuleIcon(
    val module: String, val type: String, val resName: String, val png: ByteArray,
    val extraPaths: List<String> = emptyList(),   // точные пути, как в APK (res/drawable-xxhdpi-v4/x.png)
)

/**
 * Тема MTZ — это zip. Значки лежат во вложенном zip-файле «icons»:
 * icons/res/drawable-xxhdpi/<имя.пакета>.png
 */
class MtzTheme private constructor(
    private val file: File,
    val title: String?,
    private val iconsZip: LinkedHashMap<String, ByteArray>,
) {

    /** Значки приложений, которые уже есть в теме (по одному на пакет, в лучшем разрешении). */
    fun appIcons(): List<ThemeIcon> {
        val best = LinkedHashMap<String, Pair<Int, String>>()
        for (name in iconsZip.keys) {
            val pkg = appIconPackage(name) ?: continue
            val rank = if (name.contains('/')) densityRank(name.substringBeforeLast('/')) else 0
            val cur = best[pkg]
            if (cur == null || rank > cur.first) best[pkg] = rank to name
        }
        return best.map { (pkg, v) -> ThemeIcon(pkg, iconsZip.getValue(v.second)) }
    }

    companion object {
        // значок может лежать в res/drawable-xxhdpi/, в res/drawable/ или прямо в корне icons
        private val iconPath = Regex("^(?:res/drawable(?:-[^/]+)?/)?([^/]+)\.(png|webp)$")
        private val perAppDirs = listOf("fancy_icons/", "layer_animating_icons/", "animating_icons/", "dynamic_icons/")

        fun read(file: File): MtzTheme {
            ZipFile(file).use { zip ->
                val iconsEntry = zip.getEntry("icons")
                val icons = LinkedHashMap<String, ByteArray>()
                if (iconsEntry != null && !iconsEntry.isDirectory) {
                    ZipInputStream(zip.getInputStream(iconsEntry)).use { zin ->
                        while (true) {
                            val e = zin.nextEntry ?: break
                            if (!e.isDirectory) icons[e.name] = zin.readBytes()
                        }
                    }
                }
                val title = zip.getEntry("description.xml")?.let { e ->
                    val xml = zip.getInputStream(e).readBytes().toString(Charsets.UTF_8)
                    Regex("<title>(.*?)</title>", RegexOption.DOT_MATCHES_ALL).find(xml)?.groupValues?.get(1)?.trim()
                }
                if (zip.getEntry("description.xml") == null && iconsEntry == null)
                    throw IllegalArgumentException("Это не тема MTZ (нет description.xml и icons)")
                return MtzTheme(file, title, icons)
            }
        }

        internal fun appIconPackage(entry: String): String? {
            val m = iconPath.find(entry) ?: return null
            val stem = m.groupValues[1]
            if (!stem.contains('.') || stem.endsWith(".9")) return null
            return stem
        }

        private fun densityRank(dir: String): Int = when {
            dir.endsWith("xxxhdpi") -> 6
            dir.endsWith("xxhdpi") -> 5
            dir.endsWith("xhdpi") -> 4
            dir.endsWith("hdpi") -> 3
            dir.endsWith("mdpi") -> 2
            dir.endsWith("nodpi") -> 1
            else -> 0
        }

        /** Относится ли запись из icons к значку пакета pkg (чтобы убрать старый вариант). */
        internal fun belongsTo(entry: String, pkg: String): Boolean {
            val file = entry.substringAfterLast('/')
            val stem = file.substringBeforeLast('.')
            if ((entry.startsWith("res/") || !entry.contains('/')) && matchesPkg(stem, pkg)) return true
            if (perAppDirs.any { entry.startsWith(it) }) {
                val parts = entry.split('/')
                if (parts.size > 1) {
                    val dir = if (parts.size == 2) parts[1].substringBeforeLast('.') else parts[1]
                    if (matchesPkg(dir, pkg) || matchesPkg(parts[1], pkg)) return true
                }
            }
            return false
        }

        private fun ownsAnimated(entry: String, owner: String): Boolean {
            if (perAppDirs.none { entry.startsWith(it) }) return false
            val parts = entry.split('/')
            if (parts.size < 2) return false
            val dir = if (parts.size == 2) parts[1].substringBeforeLast('.') else parts[1]
            return dir.equals(owner, ignoreCase = true)
        }

        /**
         * name — это пакет pkg или его активность (com.android.contacts.activities.TwelveKeyDialer).
         * Другой пакет с тем же началом (com.android.settings.intelligence) не считается:
         * у активности последнее слово начинается с заглавной буквы.
         */
        private fun matchesPkg(name: String, pkg: String): Boolean {
            if (name.equals(pkg, ignoreCase = true)) return true
            if (!name.startsWith("$pkg.", ignoreCase = true)) return false
            return name.substringAfterLast('.').firstOrNull()?.isUpperCase() == true
        }

        private fun zipBytes(entries: Map<String, ByteArray>): ByteArray {
            val bos = ByteArrayOutputStream()
            ZipOutputStream(bos).use { z ->
                for ((name, data) in entries) {
                    z.putNextEntry(ZipEntry(name))
                    z.write(data)
                    z.closeEntry()
                }
            }
            return bos.toByteArray()
        }

        private fun esc(s: String) = s.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;")

        /**
         * Собирает MTZ.
         * base — тема-основа (обои, шрифты, экран блокировки и т.п. берутся из неё), может быть null.
         * icons — значки для записи (пакет → PNG). removed — пакеты, чьи значки из основы нужно убрать.
         */
        fun write(
            out: OutputStream,
            base: MtzTheme?,
            icons: List<ThemeIcon>,
            removed: Set<String>,
            title: String,
            author: String,
            previewPng: ByteArray?,
            modules: List<ModuleIcon> = emptyList(),
        ) {
            val byModule = modules.groupBy { it.module }
            val replace = icons.map { it.pkg }.toSet() + removed
            // анимированные значки пакета в основе перекрыли бы наш статичный значок экрана — убираем их
            val owners = icons.mapNotNull { it.owner }.toSet()
            val newIcons = LinkedHashMap<String, ByteArray>()
            base?.iconsZip?.forEach { (name, data) ->
                if (replace.none { belongsTo(name, it) } && owners.none { ownsAnimated(name, it) }) newIcons[name] = data
            }
            for (ic in icons) newIcons["res/drawable-xxhdpi/${ic.pkg}.png"] = ic.png
            val iconsBytes = zipBytes(newIcons)

            ZipOutputStream(out).use { z ->
                val written = HashSet<String>()
                fun put(name: String, data: ByteArray) {
                    if (!written.add(name)) return
                    z.putNextEntry(ZipEntry(name)); z.write(data); z.closeEntry()
                }
                if (base != null) {
                    ZipFile(base.file).use { zip ->
                        val en = zip.entries()
                        while (en.hasMoreElements()) {
                            val e = en.nextElement()
                            if (e.name == "icons" || e.isDirectory) continue
                            if (e.name in byModule) continue   // этот модуль пересоберём ниже
                            if (previewPng != null && e.name.startsWith("preview/preview_icons")) continue
                            if (e.name == "description.xml") {
                                put(e.name, patchDescription(zip.getInputStream(e).readBytes().toString(Charsets.UTF_8), title, author).toByteArray())
                                continue
                            }
                            // копируем потоком, не загружая в память (видеообои бывают тяжёлыми)
                            if (written.add(e.name)) {
                                z.putNextEntry(ZipEntry(e.name))
                                zip.getInputStream(e).use { it.copyTo(z, 1 shl 16) }
                                z.closeEntry()
                            }
                        }
                    }
                } else {
                    put("description.xml", newDescription(title, author).toByteArray())
                }
                put("icons", iconsBytes)
                for ((module, list) in byModule) {
                    val baseBytes = base?.let { b ->
                        ZipFile(b.file).use { zip -> zip.getEntry(module)?.let { e -> zip.getInputStream(e).use { it.readBytes() } } }
                    }
                    put(module, rebuildModule(baseBytes, list))
                }
                if (previewPng != null) put("preview/preview_icons_0.png", previewPng)
            }
        }

        /**
         * Модуль приложения: zip с res/<тип>-<плотность>/<имя>.png.
         * Из основы убираем все старые варианты этого ресурса (любая плотность, ночные, xml),
         * наш кладём в xxhdpi и копией в night-xxhdpi — иначе в тёмной теме победит старый ночной вариант.
         */
        internal fun rebuildModule(baseBytes: ByteArray?, icons: List<ModuleIcon>): ByteArray {
            val names = icons.map { it.resName }.toSet()
            val entries = LinkedHashMap<String, ByteArray>()
            if (baseBytes != null) {
                ZipInputStream(ByteArrayInputStream(baseBytes)).use { zin ->
                    while (true) {
                        val e = zin.nextEntry ?: break
                        if (e.isDirectory) continue
                        val data = zin.readBytes()
                        val m = resFile.find(e.name)
                        if (m != null && m.groupValues[1] in names) continue
                        entries[e.name] = data
                    }
                }
            }
            for (ic in icons) {
                for (d in listOf("xxhdpi", "xxxhdpi", "night-xxhdpi", "night-xxxhdpi")) {
                    entries["res/${ic.type}-$d/${ic.resName}.png"] = ic.png
                }
                for (p in ic.extraPaths) {
                    if (!p.startsWith("res/")) continue
                    // путь как в APK и он же без «-v4»; с исходным расширением (.webp тоже) и с .png.
                    // Формат картинки система определяет по содержимому, а не по расширению.
                    val noVer = p.replace(Regex("-v\\d+(?=/)"), "")
                    for (v in setOf(p, noVer)) {
                        entries[v] = ic.png
                        entries[v.substringBeforeLast('.') + ".png"] = ic.png
                    }
                }
            }
            return zipBytes(entries)
        }

        private val resFile = Regex("^res/(?:drawable|mipmap)[^/]*/([^/.]+)(?:\\.9)?\\.(png|webp|xml|jpg)$")

        /** Имя модуля в MTZ для пакета. */
        fun moduleName(pkg: String): String = if (pkg == "android") "framework-res" else pkg

        private fun patchDescription(xml: String, title: String, author: String): String {
            var x = xml
            if (title.isNotBlank()) x = x.replace(Regex("<title>.*?</title>", RegexOption.DOT_MATCHES_ALL), "<title>${esc(title)}</title>")
            if (author.isNotBlank()) {
                x = x.replace(Regex("<designer>.*?</designer>", RegexOption.DOT_MATCHES_ALL), "<designer>${esc(author)}</designer>")
                x = x.replace(Regex("<author>.*?</author>", RegexOption.DOT_MATCHES_ALL), "<author>${esc(author)}</author>")
            }
            return x
        }

        private fun newDescription(title: String, author: String): String = """<?xml version="1.0" encoding="UTF-8"?>
<theme>
    <title>${esc(title.ifBlank { "My icons" })}</title>
    <designer>${esc(author.ifBlank { "MTZ Forge" })}</designer>
    <author>${esc(author.ifBlank { "MTZ Forge" })}</author>
    <version>1.0.0</version>
    <uiVersion>15</uiVersion>
    <description>Icons made with MTZ Forge</description>
</theme>
"""

        /** Проверка собранного файла: возвращает список пакетов в icons. */
        fun listPackages(mtzBytes: ByteArray): List<String> {
            val out = mutableListOf<String>()
            ZipInputStream(ByteArrayInputStream(mtzBytes)).use { z ->
                while (true) {
                    val e = z.nextEntry ?: break
                    if (e.name == "icons") {
                        ZipInputStream(ByteArrayInputStream(z.readBytes())).use { zi ->
                            while (true) { val ie = zi.nextEntry ?: break; appIconPackage(ie.name)?.let { out += it } }
                        }
                    }
                }
            }
            return out
        }
    }
}
