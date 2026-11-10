package com.bluequark.mtzforge

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.content.pm.ResolveInfo
import android.content.res.Resources
import android.util.TypedValue
import com.bluequark.mtzforge.core.IconRecolor
import com.bluequark.mtzforge.core.ModuleIcon
import com.bluequark.mtzforge.core.MtzTheme
import com.bluequark.mtzforge.core.Raster
import org.xmlpull.v1.XmlPullParser

/** Как HyperOS рисует значок пункта «Поделиться» — от этого зависит, получится ли его заменить. */
enum class ShareKind(val short: String, val hint: String) {
    APP("общий значок", "Берётся значок приложения: сменится и он, и значок приложения везде."),
    RASTER("свой значок", "У пункта свой значок-картинка — подменяем его внутри приложения."),
    ADAPTIVE("свой значок", "У пункта свой адаптивный значок — подменяем его верхний слой."),
    VECTOR("не меняется", "У пункта свой векторный значок — тема HyperOS такие не подменяет."),
    UNKNOWN("?", "Не удалось определить, как рисуется значок."),
}

class ShareInfo(
    val kind: ShareKind,
    val resId: Int = 0,          // ресурс, который подменяем (сам значок или его верхний слой)
    val type: String = "",       // drawable / mipmap
    val name: String = "",       // имя ресурса
    val apkPath: String = "",    // путь файла в APK, например res/drawable-xxhdpi-v4/ic_lens.png
    val w: Int = 0,
    val h: Int = 0,
)

object ShareIcons {

    private val sendIntents = listOf(
        Intent(Intent.ACTION_SEND).setType("*/*"),
        Intent(Intent.ACTION_SEND).setType("text/plain"),
        Intent(Intent.ACTION_SEND).setType("image/*"),
        Intent(Intent.ACTION_SEND).setType("video/*"),
        Intent(Intent.ACTION_SEND_MULTIPLE).setType("*/*"),
    )

    /** ResolveInfo пункта так, как его видит меню «Поделиться» (с иконкой из intent-filter). */
    fun resolve(pm: PackageManager, cn: ComponentName): ResolveInfo? {
        for (i in sendIntents) {
            val list = try { pm.queryIntentActivities(Intent(i).setPackage(cn.packageName), PackageManager.MATCH_ALL) } catch (e: Exception) { emptyList() }
            list.firstOrNull { it.activityInfo.name == cn.className }?.let { return it }
        }
        return null
    }

    fun classify(ctx: Context, cn: ComponentName): ShareInfo {
        val pm = ctx.packageManager
        val ri = resolve(pm, cn) ?: return ShareInfo(ShareKind.UNKNOWN)
        return classify(pm, ri, withSize = true)
    }

    /**
     * withSize = false — быстро, только вид пункта (для списка).
     * Значок «общий», только если ни у фильтра, ни у экрана нет своего android:icon:
     * тогда система идёт через значок приложения и тему (<пакет>.png).
     */
    fun classify(pm: PackageManager, ri: ResolveInfo, withSize: Boolean): ShareInfo {
        val ai = ri.activityInfo.applicationInfo
        val actIcon = ri.activityInfo.icon
        if (ri.icon == 0 && (actIcon == 0 || actIcon == ai.icon)) return ShareInfo(ShareKind.APP)
        val resId = ri.iconResource          // иконка фильтра → экрана → приложения
        if (resId == 0) return ShareInfo(ShareKind.APP)
        val res = try { pm.getResourcesForApplication(ai) } catch (e: Exception) { return ShareInfo(ShareKind.UNKNOWN) }
        val path = filePath(res, resId) ?: return ShareInfo(ShareKind.UNKNOWN)
        return when {
            isRaster(path) -> info(res, ShareKind.RASTER, resId, path, withSize)
            path.endsWith(".xml") -> {
                val fg = adaptiveForeground(res, resId)
                val fgPath = fg?.let { filePath(res, it) }
                if (fg != null && fgPath != null && isRaster(fgPath)) info(res, ShareKind.ADAPTIVE, fg, fgPath, withSize)
                else ShareInfo(ShareKind.VECTOR)
            }
            else -> ShareInfo(ShareKind.UNKNOWN)
        }
    }

    private fun isRaster(p: String) = p.endsWith(".png") || p.endsWith(".webp") || p.endsWith(".jpg")

    private fun filePath(res: Resources, id: Int): String? = try {
        val tv = TypedValue()
        res.getValue(id, tv, true)
        tv.string?.toString()
    } catch (e: Exception) { null }

    private fun info(res: Resources, kind: ShareKind, id: Int, path: String, withSize: Boolean): ShareInfo {
        val d = if (!withSize) null else try { res.getDrawableForDensity(id, SystemIcons.DENSITY, null) } catch (e: Throwable) { null }
        val w = d?.intrinsicWidth?.takeIf { it > 0 } ?: if (kind == ShareKind.ADAPTIVE) 324 else 144
        val h = d?.intrinsicHeight?.takeIf { it > 0 } ?: w
        return ShareInfo(kind, id, res.getResourceTypeName(id), res.getResourceEntryName(id), path, w.coerceAtMost(512), h.coerceAtMost(512))
    }

    /** Ресурс верхнего слоя <adaptive-icon> (в том числе внутри <inset>). */
    private fun adaptiveForeground(res: Resources, id: Int): Int? = try {
        res.getXml(id).use { p -> parseForeground(p) }
    } catch (e: Exception) { null }

    private fun parseForeground(p: android.content.res.XmlResourceParser): Int? {
        var inFg = false
        var result: Int? = null
        var root = true
        loop@ while (true) {
            when (p.next()) {
                XmlPullParser.END_DOCUMENT -> break@loop
                XmlPullParser.START_TAG -> {
                    if (root) { root = false; if (p.name != "adaptive-icon") break@loop; continue@loop }
                    if (p.name == "foreground") inFg = true
                    if (inFg) {
                        // <foreground android:drawable="…"/>, <inset android:drawable="…"/> или <bitmap android:src="…"/>
                        val r = p.getAttributeResourceValue(ANDROID_NS, "drawable", 0).takeIf { it != 0 }
                            ?: p.getAttributeResourceValue(ANDROID_NS, "src", 0)
                        if (r != 0) { result = r; break@loop }
                    }
                }
                XmlPullParser.END_TAG -> if (p.name == "foreground") inFg = false
            }
        }
        return result
    }

    private const val ANDROID_NS = "http://schemas.android.com/apk/res/android"

    /** Что записать в модуль пакета, чтобы подменить значок пункта. Пусто — подменять нечего. */
    fun moduleWrites(info: ShareInfo, pkg: String, icon: Raster): List<ModuleIcon> {
        if (info.kind != ShareKind.RASTER && info.kind != ShareKind.ADAPTIVE) return emptyList()
        val img = if (info.kind == ShareKind.ADAPTIVE) {
            // видимая часть адаптивного значка — центральные 72 из 108 dp
            val side = info.w * 2 / 3
            Raster(info.w, info.h).also { it.drawOver(icon.toSquare().scaleTo(side, side), (info.w - side) / 2, (info.h - side) / 2) }
        } else icon.toSquare().scaleTo(info.w, info.h)
        val png = AndroidPng.encode(img)
        return listOf(ModuleIcon(MtzTheme.moduleName(pkg), info.type, info.name, png, extraPaths = listOf(info.apkPath)))
    }

    /** Имена значков на рабочем столе для всех экранов-ярлыков пакета (и выключенных alias, как у VK). */
    fun launcherKeys(pm: PackageManager, pkg: String): List<String> {
        val i = Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER).setPackage(pkg)
        val list = try {
            pm.queryIntentActivities(i, PackageManager.MATCH_DISABLED_COMPONENTS or PackageManager.MATCH_ALL)
        } catch (e: Exception) { emptyList() }
        return list.map { IconRecolor.themeIconName(pkg, it.activityInfo.name) }.distinct()
    }
}
