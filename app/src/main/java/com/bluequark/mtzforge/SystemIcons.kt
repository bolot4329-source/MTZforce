package com.bluequark.mtzforge

import android.content.Context
import android.content.pm.ApplicationInfo
import android.content.res.Resources
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.drawable.AnimatedVectorDrawable
import android.graphics.drawable.Animatable
import android.graphics.drawable.AnimationDrawable
import android.graphics.drawable.ColorDrawable
import android.graphics.drawable.Drawable
import android.graphics.drawable.GradientDrawable
import android.graphics.drawable.LevelListDrawable
import android.graphics.drawable.NinePatchDrawable
import android.graphics.drawable.RippleDrawable
import android.graphics.drawable.StateListDrawable
import android.os.Build
import com.bluequark.mtzforge.core.Raster

/** Что это за картинка. Заменять безопасно только ICON. */
enum class ResKind(val title: String) {
    ICON("иконка"),
    STATES("переключатель: у него несколько состояний, замена картинкой отключит их"),
    LEVELS("индикатор уровней (сигнал, заряд): замена картинкой оставит один уровень"),
    ANIMATED("анимация: после замены анимация пропадёт"),
    OTHER("фон или фигура, не иконка"),
}

/** Картинка внутри приложения. w/h — её реальный размер в пикселях при xxhdpi (480 dpi). */
class SysIcon(val pkg: String, val id: Int, val type: String, val name: String, val kind: ResKind, val w: Int, val h: Int) {
    val key: String get() = "$type/$name"
}

/** Ресурсы и тема чужого пакета: без темы векторы с ?attr/ не рисуются. */
class PkgRes(val pkg: String, val res: Resources, val theme: Resources.Theme)

object SystemIcons {
    const val DENSITY = 480 // xxhdpi

    /** Готовые наборы системных приложений HyperOS. */
    val presets = listOf(
        "com.android.settings" to "Настройки",
        "miui.systemui.plugin" to "Пункт управления",
        "com.android.systemui" to "Строка состояния и шторка",
        "com.android.contacts" to "Телефон и Контакты",
        "com.android.incallui" to "Экран звонка",
        "com.miui.securitycenter" to "Безопасность",
        "com.android.camera" to "Камера",
        "com.android.fileexplorer" to "Проводник",
        "com.miui.gallery" to "Галерея",
        "com.android.mms" to "Сообщения",
        "com.android.deskclock" to "Часы",
        "com.miui.home" to "Рабочий стол",
        "com.android.intentresolver" to "Меню «Поделиться» (системное окно)",
        "com.miui.intentresolver" to "Меню «Поделиться» HyperOS",
        "android" to "Android (общие системные значки)",
    )

    fun open(ctx: Context, pkg: String): PkgRes {
        val pm = ctx.packageManager
        val ai: ApplicationInfo = pm.getApplicationInfo(pkg, 0)
        val res = pm.getResourcesForApplication(ai)
        val theme = res.newTheme()
        if (ai.theme != 0) try { theme.applyStyle(ai.theme, true) } catch (_: Exception) { }
        val base = if (Build.VERSION.SDK_INT >= 29) android.R.style.Theme_DeviceDefault_DayNight else android.R.style.Theme_DeviceDefault
        try { theme.applyStyle(base, false) } catch (_: Exception) { }
        return PkgRes(pkg, res, theme)
    }

    private fun packageId(ctx: Context, pr: PkgRes): Int {
        if (pr.pkg == "android") return 0x01
        try {
            val ai = ctx.packageManager.getApplicationInfo(pr.pkg, 0)
            if (ai.icon != 0) return (ai.icon ushr 24) and 0xFF
        } catch (_: Exception) { }
        for (probe in listOf("ic_launcher" to "mipmap", "ic_launcher" to "drawable", "app_name" to "string")) {
            val id = pr.res.getIdentifier(probe.first, probe.second, pr.pkg)
            if (id != 0) return (id ushr 24) and 0xFF
        }
        return 0x7f
    }

    /**
     * Перебор всех drawable/mipmap пакета без разбора resources.arsc:
     * id ресурса = пакет(8 бит) · тип(8 бит) · номер(16 бит).
     */
    fun list(ctx: Context, pr: PkgRes, onProgress: (String) -> Unit = {}): List<SysIcon> {
        val pkgId = packageId(ctx, pr)
        val out = mutableListOf<SysIcon>()
        for (type in 1..0x30) {
            var typeName: String? = null
            var first = 0
            for (probe in 0..255) {
                val id = (pkgId shl 24) or (type shl 16) or probe
                try { typeName = pr.res.getResourceTypeName(id); first = probe; break } catch (_: Resources.NotFoundException) { }
            }
            val tName = typeName ?: continue
            if (tName != "drawable" && tName != "mipmap") continue
            var misses = 0
            for (entry in first..0xFFFF) {
                val id = (pkgId shl 24) or (type shl 16) or entry
                val name = try { pr.res.getResourceEntryName(id) } catch (_: Resources.NotFoundException) { null }
                if (name == null) { if (++misses >= 1024) break; continue }
                misses = 0
                if (name.contains("obfuscated")) continue          // имена сжаты — по имени не подменить
                if (out.size % 50 == 0) onProgress("Читаю ресурсы… ${out.size}")
                val d = load(pr, id) ?: continue
                val w = d.intrinsicWidth; val h = d.intrinsicHeight
                val kind = classify(d)
                out += SysIcon(pr.pkg, id, tName, name, kind, if (w > 0) w.coerceAtMost(1024) else 0, if (h > 0) h.coerceAtMost(1024) else 0)
            }
        }
        return out.sortedWith(compareBy<SysIcon> { it.kind.ordinal }.thenBy { it.name })
    }

    fun load(pr: PkgRes, id: Int): Drawable? = try {
        pr.res.getDrawableForDensity(id, DENSITY, pr.theme)
    } catch (_: Throwable) {
        try { pr.res.getDrawableForDensity(id, DENSITY, null) } catch (_: Throwable) { null }
    }

    private fun classify(d: Drawable): ResKind = when {
        d is StateListDrawable -> ResKind.STATES          // сюда же AnimatedStateListDrawable
        d is LevelListDrawable -> ResKind.LEVELS
        d is AnimationDrawable || d is AnimatedVectorDrawable || d is Animatable -> ResKind.ANIMATED
        d is NinePatchDrawable || d is ColorDrawable || d is GradientDrawable || d is RippleDrawable -> ResKind.OTHER
        d.intrinsicWidth <= 0 || d.intrinsicHeight <= 0 -> ResKind.OTHER
        d.intrinsicWidth > d.intrinsicHeight * 2 || d.intrinsicHeight > d.intrinsicWidth * 2 -> ResKind.OTHER
        else -> ResKind.ICON
    }

    /** Рисует ресурс в его реальном размере при xxhdpi. */
    fun render(pr: PkgRes, icon: SysIcon): Raster? {
        if (icon.w <= 0 || icon.h <= 0) return null
        val d = load(pr, icon.id) ?: return null
        return try {
            val bmp = Bitmap.createBitmap(icon.w, icon.h, Bitmap.Config.ARGB_8888)
            d.setBounds(0, 0, icon.w, icon.h)
            d.draw(Canvas(bmp))
            bmp.toRaster().also { bmp.recycle() }
        } catch (_: Throwable) { null }
    }
}
