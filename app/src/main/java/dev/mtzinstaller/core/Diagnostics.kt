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
                .sortedWith(compareByDescending<ThemeActivity> { it.exported }.thenBy { it.name })
        }.getOrDefault(emptyList())

        fun describe(name: String): String {
            val info = activities.firstOrNull { it.name == name }
            return when {
                info == null -> "НЕТ в этой версии «Тем»"
                !info.exported -> "есть, но ЗАКРЫТА для других приложений"
                info.permission != null -> "открыта, нужна ${info.permission}"
                else -> "открыта"
            }
        }
        lines += "ApplyThemeForScreenshot" to describe(ThemeActivities.APPLY)
        lines += "ThemeGateActivity" to describe(ThemeActivities.GATE)
        lines += "ComponentActivity (локальные)" to describe(ThemeActivities.LOCAL_COMPONENT)
        lines += "ThemeDetailActivity" to describe(ThemeActivities.DETAIL)

        return DiagReport(lines, activities)
    }
}
