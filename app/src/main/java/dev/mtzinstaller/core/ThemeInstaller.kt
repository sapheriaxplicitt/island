package dev.mtzinstaller.core

import android.Manifest
import android.content.ActivityNotFoundException
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.os.Environment
import androidx.core.content.ContextCompat
import androidx.core.content.FileProvider
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.io.IOException

enum class InstallMethod(val title: String, val hint: String) {
    DIRECT(
        "Прямое применение",
        "Сразу открывает окно применения темы в «Темах». Работает на большинстве MIUI 12–14 и HyperOS.",
    ),
    OPEN_WITH(
        "Открыть в «Темах»",
        "Передаёт файл приложению «Темы» как документ. Доступ к файлам не нужен.",
    ),
    COPY_ONLY(
        "Скопировать и открыть «Темы»",
        "Кладёт файл в Загрузки/MTZ Installer и открывает «Темы» — импорт вручную.",
    ),
}

data class InstallResult(val ok: Boolean, val message: String)

object ThemeInstaller {

    const val THEME_PKG = "com.android.thememanager"
    private const val APPLY_ACTIVITY = "com.android.thememanager.ApplyThemeForScreenshot"

    fun themeManagerVersion(context: Context): String? = try {
        @Suppress("DEPRECATION")
        context.packageManager.getPackageInfo(THEME_PKG, 0).versionName ?: "?"
    } catch (e: PackageManager.NameNotFoundException) {
        null
    }

    /** «HyperOS 2.0», «MIUI 14.0» или null, если прошивка не от Xiaomi. */
    fun romName(): String? {
        val hyper = prop("ro.mi.os.version.name")
        if (!hyper.isNullOrBlank()) return "HyperOS ${hyper.removePrefix("OS")}"
        val miui = prop("ro.miui.ui.version.name")
        if (!miui.isNullOrBlank()) {
            val digits = miui.removePrefix("V")
            val pretty = if (digits.length == 3 && digits.all { it.isDigit() }) {
                "${digits.dropLast(1)}.${digits.last()}"
            } else {
                digits
            }
            return "MIUI $pretty"
        }
        return null
    }

    private fun prop(name: String): String? = runCatching {
        val process = ProcessBuilder("getprop", name).redirectErrorStream(true).start()
        val out = process.inputStream.bufferedReader().use { it.readText() }.trim()
        process.waitFor()
        out
    }.getOrNull()?.takeIf { it.isNotEmpty() }

    fun hasStorageAccess(context: Context): Boolean =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            Environment.isExternalStorageManager()
        } else {
            ContextCompat.checkSelfPermission(
                context, Manifest.permission.WRITE_EXTERNAL_STORAGE,
            ) == PackageManager.PERMISSION_GRANTED
        }

    @Suppress("DEPRECATION")
    private fun publicDir(): File = File(
        Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS),
        "MTZ Installer",
    )

    private fun safeName(title: String): String =
        title.replace(Regex("[\\\\/:*?\"<>|\\r\\n\\t]"), "_").trim().take(80).ifBlank { "theme" }

    private fun copyToPublic(src: File, title: String): File {
        val dir = publicDir()
        if (!dir.exists() && !dir.mkdirs()) throw IOException("Не удалось создать папку ${dir.absolutePath}")
        val out = File(dir, safeName(title) + ".mtz")
        src.inputStream().use { input -> out.outputStream().use { input.copyTo(it) } }
        return out
    }

    /**
     * Пробует выбранный способ, а если система его не принимает — следующие по очереди.
     */
    suspend fun install(
        context: Context,
        src: File,
        title: String,
        method: InstallMethod,
    ): InstallResult {
        if (themeManagerVersion(context) == null) {
            return InstallResult(
                false,
                "Приложение «Темы» не найдено. Установка .mtz работает только на MIUI / HyperOS.",
            )
        }
        val order = when (method) {
            InstallMethod.DIRECT -> listOf(InstallMethod.DIRECT, InstallMethod.OPEN_WITH, InstallMethod.COPY_ONLY)
            InstallMethod.OPEN_WITH -> listOf(InstallMethod.OPEN_WITH, InstallMethod.DIRECT, InstallMethod.COPY_ONLY)
            InstallMethod.COPY_ONLY -> listOf(InstallMethod.COPY_ONLY)
        }

        var lastError: String? = null
        var publicFile: File? = null
        for (m in order) {
            try {
                if (m != InstallMethod.OPEN_WITH) {
                    if (!hasStorageAccess(context)) {
                        lastError = "нет доступа к файлам"
                        continue
                    }
                    if (publicFile == null) {
                        publicFile = withContext(Dispatchers.IO) { copyToPublic(src, title) }
                    }
                }
                val target = publicFile
                withContext(Dispatchers.Main) { startIntent(context, m, src, target) }

                val note = if (m == method) "" else " (запасной способ: ${m.title})"
                val text = when (m) {
                    InstallMethod.DIRECT -> "Открыто окно применения темы$note"
                    InstallMethod.OPEN_WITH -> "Тема передана в «Темы»$note"
                    InstallMethod.COPY_ONLY ->
                        "Файл сохранён в Загрузки/MTZ Installer — импортируйте его в «Темах»$note"
                }
                return InstallResult(true, text)
            } catch (e: ActivityNotFoundException) {
                lastError = e.message
            } catch (e: SecurityException) {
                lastError = e.message
            } catch (e: IOException) {
                lastError = e.message
            } catch (e: IllegalArgumentException) {
                lastError = e.message
            }
        }
        return InstallResult(false, "Не удалось установить: ${lastError ?: "неизвестная ошибка"}")
    }

    private fun startIntent(context: Context, method: InstallMethod, internal: File, public: File?) {
        when (method) {
            InstallMethod.DIRECT -> {
                val path = requireNotNull(public) { "файл не скопирован" }.absolutePath
                val intent = Intent()
                    .setComponent(ComponentName(THEME_PKG, APPLY_ACTIVITY))
                    .putExtra("theme_file_path", path)
                    .putExtra("api_called_from", "ThemeEditor")
                    .putExtra("ver2_step", "ver2_step_apply")
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                context.startActivity(intent)
            }

            InstallMethod.OPEN_WITH -> {
                val uri = FileProvider.getUriForFile(context, "${context.packageName}.files", internal)
                val intent = Intent(Intent.ACTION_VIEW)
                    .setDataAndType(uri, "application/octet-stream")
                    .setPackage(THEME_PKG)
                    .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_ACTIVITY_NEW_TASK)
                context.startActivity(intent)
            }

            InstallMethod.COPY_ONLY -> {
                val intent = context.packageManager.getLaunchIntentForPackage(THEME_PKG)
                    ?: throw ActivityNotFoundException("«Темы» не найдены")
                context.startActivity(intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
            }
        }
    }

    fun openThemeManager(context: Context): Boolean {
        val intent = context.packageManager.getLaunchIntentForPackage(THEME_PKG) ?: return false
        return try {
            context.startActivity(intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
            true
        } catch (e: ActivityNotFoundException) {
            false
        }
    }
}
