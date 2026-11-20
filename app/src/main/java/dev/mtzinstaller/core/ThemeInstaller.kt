package dev.mtzinstaller.core

import android.Manifest
import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.os.Environment
import androidx.core.content.ContextCompat
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

data class InstallResult(
    val ok: Boolean,
    val message: String,
    val recipe: Recipe? = null,
    val log: List<String> = emptyList(),
)

object ThemeInstaller {

    const val THEME_PKG = "com.android.thememanager"

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

    fun prop(name: String): String? = runCatching {
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

    /** Копия файла в общей памяти — нужна «Темам» для прямого применения. */
    suspend fun publicCopy(context: Context, src: File, title: String): File? =
        if (!hasStorageAccess(context)) null else withContext(Dispatchers.IO) { copyToPublic(src, title) }

    /** Есть ли такая активность и открыта ли она для сторонних приложений. */
    fun activityExists(context: Context, name: String): Boolean = runCatching {
        @Suppress("DEPRECATION")
        context.packageManager
            .getPackageInfo(THEME_PKG, PackageManager.GET_ACTIVITIES)
            .activities.orEmpty()
            .any { it.name == name && it.exported }
    }.getOrDefault(false)

    private fun recipesFor(method: InstallMethod): List<Recipe> = when (method) {
        InstallMethod.DIRECT -> listOf(
            Recipe.APPLY_PATH,
            Recipe.APPLY_FILE_URI,
            Recipe.APPLY_CONTENT_URI,
            Recipe.ACTION_MIUI_APPLY,
            Recipe.DEEPLINK_THEME,
            Recipe.GATE_FILE,
            Recipe.COMPONENT_LOCAL,
            Recipe.DETAIL_LOCAL,
            Recipe.VIEW_CONTENT_OCTET,
            Recipe.VIEW_CONTENT_MTZ,
            Recipe.VIEW_MIUI_MIME,
            Recipe.THEME_TAB,
        )
        InstallMethod.OPEN_WITH -> listOf(
            Recipe.VIEW_CONTENT_OCTET,
            Recipe.VIEW_CONTENT_MTZ,
            Recipe.VIEW_MIUI_MIME,
            Recipe.APPLY_CONTENT_URI,
            Recipe.APPLY_PATH,
        )
        InstallMethod.COPY_ONLY -> listOf(Recipe.LAUNCH_APP)
    }

    /** Пробует способы по очереди и запоминает, что ответила система на каждый. */
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
        val public = runCatching { publicCopy(context, src, title) }.getOrNull()
        val log = ArrayList<String>()

        val missing = HashSet<String>()
        for (recipe in recipesFor(method)) {
            val target = recipe.component
            if (target != null && !activityExists(context, target)) {
                if (missing.add(target)) {
                    log += "${target.substringAfterLast('.')}: нет в этой версии «Тем» — " +
                        "все способы через неё пропущены"
                }
                continue
            }
            if (recipe.needsPublicFile && public == null) {
                log += "${recipe.title}: пропущен, нет доступа к файлам"
                continue
            }
            val error = attempt(context, recipe, src, public)
            if (error == null) {
                log += "${recipe.title}: запрос принят"
                return InstallResult(
                    ok = true,
                    message = "«Темы» получили запрос (${recipe.title}). " +
                        "Если окно не открылось — откройте диагностику.",
                    recipe = recipe,
                    log = log,
                )
            }
            log += "${recipe.title}: $error"
        }
        return InstallResult(
            ok = false,
            message = "Ни один способ не сработал. Откройте диагностику и пришлите отчёт.",
            log = log,
        )
    }

    /** Один способ, без запасных — для экрана диагностики. */
    suspend fun runRecipe(
        context: Context,
        src: File,
        title: String,
        recipe: Recipe,
    ): InstallResult {
        recipe.component?.let { target ->
            if (!activityExists(context, target)) {
                return InstallResult(
                    false,
                    "${recipe.title}: активности ${target.substringAfterLast('.')} " +
                        "нет в этой версии «Тем»",
                    recipe,
                )
            }
        }
        val public = if (recipe.needsPublicFile) {
            runCatching { publicCopy(context, src, title) }.getOrNull()
                ?: return InstallResult(false, "${recipe.title}: нужен доступ к файлам", recipe)
        } else {
            runCatching { publicCopy(context, src, title) }.getOrNull()
        }
        val error = attempt(context, recipe, src, public)
        return if (error == null) {
            InstallResult(true, "${recipe.title}: запрос принят", recipe)
        } else {
            InstallResult(false, "${recipe.title}: $error", recipe)
        }
    }

    /** Любая активность «Тем», выбранная вручную в диагностике. */
    suspend fun runCustom(
        context: Context,
        src: File,
        title: String,
        activity: String,
    ): InstallResult {
        val public = runCatching { publicCopy(context, src, title) }.getOrNull()
        val short = activity.substringAfterLast('.')
        return try {
            fire(context, RecipeBuilder.buildCustom(context, activity, src, public))
            InstallResult(true, "$short: запрос принят")
        } catch (e: Exception) {
            InstallResult(false, "$short: ${describe(e)}")
        }
    }

    /** @return null если запрос ушёл, иначе текст ошибки. */
    private suspend fun attempt(
        context: Context,
        recipe: Recipe,
        src: File,
        public: File?,
    ): String? = try {
        fire(context, RecipeBuilder.build(context, recipe, src, public))
        null
    } catch (e: Exception) {
        describe(e)
    }

    private suspend fun fire(context: Context, intent: Intent) {
        @Suppress("DEPRECATION")
        val resolved = context.packageManager.resolveActivity(intent, 0)
        if (resolved == null && intent.component != null) {
            throw ActivityNotFoundException("активности нет или она закрыта")
        }
        withContext(Dispatchers.Main) { context.startActivity(intent) }
    }

    private fun describe(e: Exception): String = when (e) {
        is ActivityNotFoundException -> "активность не найдена"
        is SecurityException -> "система запретила доступ"
        is IOException -> "ошибка файла: ${e.message}"
        else -> e.javaClass.simpleName + (e.message?.let { ": $it" } ?: "")
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
