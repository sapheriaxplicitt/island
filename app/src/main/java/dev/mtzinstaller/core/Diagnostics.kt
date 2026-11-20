package dev.mtzinstaller.core

import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import android.os.Environment
import java.io.File

data class ThemeActivity(val name: String, val exported: Boolean, val permission: String?)

data class DiagReport(
    val lines: List<Pair<String, String>>,
    val activities: List<ThemeActivity>,
) {
    /** Текст для отправки в чат — чтобы не переписывать всё со скриншота. */
    fun asText(): String = buildString {
        appendLine("=== MTZ Installer: диагностика ===")
        lines.forEach { (k, v) -> appendLine("$k: $v") }
        appendLine()
        appendLine("Активности «Тем» (${activities.size}):")
        activities.forEach { a ->
            append("  ")
            append(if (a.exported) "[открыта] " else "[закрыта] ")
            append(a.name.removePrefix("com.android.thememanager"))
            a.permission?.let { append(" perm=$it") }
            appendLine()
        }
    }
}

object Diagnostics {

    private val INTERESTING = listOf("apply", "import", "theme", "detail", "tab", "local", "mine")

    fun collect(context: Context): DiagReport {
        val pm = context.packageManager
        val lines = ArrayList<Pair<String, String>>()

        lines += "Модель" to "${Build.MANUFACTURER} ${Build.MODEL}"
        lines += "Android" to "${Build.VERSION.RELEASE} (SDK ${Build.VERSION.SDK_INT})"
        lines += "Прошивка" to (ThemeInstaller.romName() ?: "не MIUI / HyperOS")
        lines += "Сборка" to (ThemeInstaller.prop("ro.build.version.incremental") ?: "?")
        lines += "Регион" to listOfNotNull(
            ThemeInstaller.prop("ro.miui.region"),
            ThemeInstaller.prop("persist.sys.device_region"),
        ).distinct().joinToString("/").ifBlank { "?" }

        val version = ThemeInstaller.themeManagerVersion(context)
        lines += "«Темы»" to (version?.let { "установлены, версия $it" } ?: "НЕ НАЙДЕНЫ")
        lines += "Доступ к файлам" to if (ThemeInstaller.hasStorageAccess(context)) "есть" else "НЕТ"

        @Suppress("DEPRECATION")
        val downloads = File(
            Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS),
            "MTZ Installer",
        )
        lines += "Папка с копиями" to when {
            !downloads.exists() -> "нет (${downloads.absolutePath})"
            else -> "${downloads.listFiles()?.size ?: 0} файл(ов), ${downloads.absolutePath}"
        }

        val themeDir = File(Environment.getExternalStorageDirectory(), "MIUI/theme")
        lines += "/MIUI/theme" to if (themeDir.exists()) "есть" else "нет"

        val activities = runCatching {
            @Suppress("DEPRECATION")
            val info = pm.getPackageInfo(ThemeInstaller.THEME_PKG, PackageManager.GET_ACTIVITIES)
            info.activities.orEmpty()
                .map { ThemeActivity(it.name, it.exported, it.permission) }
                .filter { a -> INTERESTING.any { it in a.name.lowercase() } }
                .sortedWith(compareByDescending<ThemeActivity> { it.exported }.thenBy { it.name })
        }.getOrDefault(emptyList())

        val applyInfo = activities.firstOrNull { it.name == Recipe.APPLY_ACTIVITY }
        lines += "ApplyThemeForScreenshot" to when {
            applyInfo == null -> "НЕТ в этой версии «Тем»"
            !applyInfo.exported -> "есть, но ЗАКРЫТА для других приложений"
            applyInfo.permission != null -> "открыта, нужна ${applyInfo.permission}"
            else -> "открыта"
        }

        return DiagReport(lines, activities)
    }
}
