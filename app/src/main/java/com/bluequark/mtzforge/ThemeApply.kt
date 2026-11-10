package com.bluequark.mtzforge

import android.annotation.SuppressLint
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.BroadcastReceiver
import android.content.ComponentName
import android.content.ContentValues
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.ServiceInfo
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.os.IBinder
import android.os.PowerManager
import android.provider.MediaStore
import android.provider.Settings
import androidx.core.app.NotificationCompat
import androidx.core.app.ServiceCompat
import androidx.core.content.ContextCompat
import java.io.File
import java.io.OutputStream
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Применение темы в HyperOS без сторонних приложений — так же, как это делает Zyper:
 * открываем экран «Тем» ApplyThemeForScreenshot, а удерживающая служба не даёт системе откатить тему.
 */
object ThemeApply {

    const val FOLDER = "MTZ Forge"
    private const val THEME_PKG = "com.android.thememanager"
    private const val APPLY_ACTIVITY = "com.android.thememanager.ApplyThemeForScreenshot"
    private const val PREFS = "theme_keeper"
    private const val KEY_KEEP = "keep"

    /**
     * Сохраняет тему в «Загрузки/MTZ Forge/». Имя всегда новое (с датой и временем):
     * так нет конфликтов в MediaStore и «Темы» не возьмут старую копию из кэша.
     * Возвращает абсолютный путь к файлу.
     */
    fun saveToDownloads(ctx: Context, title: String, write: (OutputStream) -> Unit): String {
        val safe = title.ifBlank { "theme" }.replace(Regex("[\\\\/:*?\"<>|]"), "_")
        val stamp = SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US).format(Date())
        val name = "${safe}_$stamp.mtz"
        if (Build.VERSION.SDK_INT < 29) throw IllegalStateException("Нужен Android 10 или новее")
        val values = ContentValues().apply {
            put(MediaStore.MediaColumns.DISPLAY_NAME, name)
            put(MediaStore.MediaColumns.MIME_TYPE, "application/octet-stream")
            put(MediaStore.MediaColumns.RELATIVE_PATH, Environment.DIRECTORY_DOWNLOADS + "/" + FOLDER)
            put(MediaStore.MediaColumns.IS_PENDING, 1)
        }
        val cr = ctx.contentResolver
        val uri = cr.insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, values)
            ?: throw IllegalStateException("Не удалось создать файл в «Загрузках»")
        try {
            cr.openOutputStream(uri)?.use(write) ?: throw IllegalStateException("Не удалось открыть файл для записи")
            cr.update(uri, ContentValues().apply { put(MediaStore.MediaColumns.IS_PENDING, 0) }, null, null)
        } catch (e: Exception) {
            try { cr.delete(uri, null, null) } catch (_: Exception) { }
            throw e
        }
        return realPath(ctx, uri) ?: File(
            Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS), "$FOLDER/$name"
        ).absolutePath
    }

    @Suppress("DEPRECATION")
    private fun realPath(ctx: Context, uri: Uri): String? = try {
        ctx.contentResolver.query(uri, arrayOf(MediaStore.MediaColumns.DATA), null, null, null)?.use { c ->
            if (c.moveToFirst()) c.getString(0) else null
        }
    } catch (e: Exception) { null }

    /** Открывает «Темы» с нашим файлом. Пустой путь — просто окно импорта (как в Zyper). */
    fun apply(ctx: Context, path: String): Boolean = try {
        val i = Intent()
            .setClassName(THEME_PKG, APPLY_ACTIVITY)
            .putExtra("theme_file_path", path)
            .putExtra("api_called_from", THEME_PKG)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        ctx.startActivity(i)
        true
    } catch (e: Exception) { false }

    // ---------------------------------------------------------------- удержание темы

    /** При открытии приложения: если удержание включено, а служба выгружена — запустить снова. */
    fun ensureKeeper(ctx: Context) {
        if (!isKeepOn(ctx)) return
        try { ContextCompat.startForegroundService(ctx, Intent(ctx, ThemeKeeperService::class.java)) } catch (_: Exception) { }
    }

    fun isKeepOn(ctx: Context) = ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getBoolean(KEY_KEEP, false)

    fun setKeep(ctx: Context, on: Boolean) {
        ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().putBoolean(KEY_KEEP, on).apply()
        val i = Intent(ctx, ThemeKeeperService::class.java)
        if (on) ContextCompat.startForegroundService(ctx, i) else ctx.stopService(i)
    }

    fun isIgnoringBattery(ctx: Context): Boolean =
        (ctx.getSystemService(Context.POWER_SERVICE) as PowerManager).isIgnoringBatteryOptimizations(ctx.packageName)

    @SuppressLint("BatteryLife")
    fun requestBattery(ctx: Context) {
        val i = Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS, Uri.parse("package:" + ctx.packageName))
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        if (!tryStart(ctx, i)) openAppDetails(ctx)
    }

    /** Автозапуск в «Безопасности» Xiaomi. */
    fun openAutostart(ctx: Context) {
        val i = Intent().setComponent(ComponentName("com.miui.securitycenter", "com.miui.permcenter.autostart.AutoStartManagementActivity"))
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        if (!tryStart(ctx, i)) openAppDetails(ctx)
    }

    /** Настройки значков рабочего стола (там выключается «Фоновый цвет» — сиреневая подложка). */
    fun openIconSettings(ctx: Context): Boolean {
        val candidates = listOf(
            Intent().setComponent(ComponentName("com.miui.home", "com.miui.home.settings.IconCustomizeActivity")),
            Intent("miui.intent.action.ICON_PANEL"),
            Intent().setComponent(ComponentName("com.miui.home", "com.miui.home.settings.MiuiHomeSettingActivity")),
            Intent(Settings.ACTION_HOME_SETTINGS),
        )
        return candidates.any { tryStart(ctx, Intent(it).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) }
    }

    private fun openAppDetails(ctx: Context) {
        tryStart(ctx, Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.parse("package:" + ctx.packageName))
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
    }

    private fun tryStart(ctx: Context, i: Intent): Boolean = try { ctx.startActivity(i); true } catch (e: Exception) { false }
}

/**
 * Удерживающая служба. HyperOS рассылает сигналы CHECK_TIME_UP / CHECK_THEME_UPDATE,
 * по которым «Темы» откатывают стороннюю тему. Мы ловим их с высоким приоритетом и обрываем.
 */
class ThemeKeeperService : Service() {

    private var registered = false

    private val blocker = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            if (isOrderedBroadcast) try { abortBroadcast() } catch (_: Exception) { }
        }
    }

    override fun onCreate() {
        super.onCreate()
        val nm = getSystemService(NotificationManager::class.java)
        if (Build.VERSION.SDK_INT >= 26) {
            nm.createNotificationChannel(NotificationChannel(CHANNEL, "Удержание темы", NotificationManager.IMPORTANCE_MIN))
        }
        val open = PendingIntent.getActivity(
            this, 0, Intent(this, MainActivity::class.java), PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )
        val n: Notification = NotificationCompat.Builder(this, CHANNEL)
            .setSmallIcon(android.R.drawable.ic_menu_gallery)
            .setContentTitle("MTZ Forge удерживает тему")
            .setContentText("Не даёт HyperOS откатить применённую тему")
            .setContentIntent(open)
            .setOngoing(true)
            .setPriority(NotificationCompat.PRIORITY_MIN)
            .build()
        val type = if (Build.VERSION.SDK_INT >= 34) ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE else 0
        try {
            ServiceCompat.startForeground(this, NOTIFICATION_ID, n, type)
        } catch (e: Exception) {
            // система не разрешила запуск в фоне (например, перезапуск после выгрузки) — тихо выходим
            stopSelf()
            return
        }

        if (!registered) {
            val f = IntentFilter().apply {
                addAction("miui.intent.action.CHECK_TIME_UP")
                addAction("miui.intent.action.CHECK_THEME_UPDATE")
                priority = IntentFilter.SYSTEM_HIGH_PRIORITY - 1
            }
            ContextCompat.registerReceiver(this, blocker, f, ContextCompat.RECEIVER_EXPORTED)
            registered = true
        }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int = START_STICKY

    override fun onDestroy() {
        if (registered) try { unregisterReceiver(blocker) } catch (_: Exception) { }
        registered = false
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? = null

    companion object {
        const val CHANNEL = "theme_keeper"
        const val NOTIFICATION_ID = 1001
    }
}

/** После перезагрузки снова запускаем удержание, если оно было включено. */
class BootReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != Intent.ACTION_BOOT_COMPLETED) return
        if (!ThemeApply.isKeepOn(context)) return
        try { ContextCompat.startForegroundService(context, Intent(context, ThemeKeeperService::class.java)) } catch (_: Exception) { }
    }
}
