package dev.mtzinstaller.core

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.net.Uri
import androidx.core.content.FileProvider
import java.io.File

/**
 * Способы передать .mtz приложению «Темы». Поведение меняется от версии к версии MIUI,
 * поэтому держим их списком и даём прогнать по очереди.
 */
enum class Recipe(
    val title: String,
    val detail: String,
    /** Нужен файл в общей памяти (а значит и доступ к файлам). */
    val needsPublicFile: Boolean,
) {
    APPLY_PATH(
        "ApplyThemeForScreenshot + путь",
        "Классический способ: окно применения темы, путь к файлу в extra",
        true,
    ),
    APPLY_FILE_URI(
        "ApplyThemeForScreenshot + file://",
        "То же окно, но файл передаётся как data-Uri",
        true,
    ),
    APPLY_CONTENT_URI(
        "ApplyThemeForScreenshot + content://",
        "То же окно, файл через FileProvider",
        false,
    ),
    VIEW_CONTENT_OCTET(
        "VIEW content:// octet-stream",
        "Открыть файл в «Темах» как документ",
        false,
    ),
    VIEW_CONTENT_MTZ(
        "VIEW content:// x-mtz",
        "То же, но с mime-типом темы",
        false,
    ),
    VIEW_FILE_URI(
        "VIEW file://",
        "Старый способ через file-Uri (на Android 7+ может быть запрещён системой)",
        true,
    ),
    THEME_TAB(
        "ThemeResourceTabActivity",
        "Открыть раздел «Мои темы» в «Темах»",
        false,
    ),
    LAUNCH_APP(
        "Запустить «Темы»",
        "Просто открыть приложение — импорт вручную",
        false,
    ),
    ;

    companion object {
        const val APPLY_ACTIVITY = "com.android.thememanager.ApplyThemeForScreenshot"
        const val TAB_ACTIVITY = "com.android.thememanager.ThemeResourceTabActivity"
    }
}

object RecipeBuilder {

    /** @param internal файл во внутренней памяти, [public] — копия в общей памяти (может быть null). */
    fun build(context: Context, recipe: Recipe, internal: File, public: File?): Intent {
        val pkg = ThemeInstaller.THEME_PKG
        fun contentUri(): Uri =
            FileProvider.getUriForFile(context, "${context.packageName}.files", internal)

        fun publicPath(): String =
            requireNotNull(public) { "файл не скопирован в общую память" }.absolutePath

        fun applyIntent(): Intent = Intent()
            .setComponent(ComponentName(pkg, Recipe.APPLY_ACTIVITY))
            .putExtra("api_called_from", "ThemeEditor")
            .putExtra("ver2_step", "ver2_step_apply")

        return when (recipe) {
            Recipe.APPLY_PATH -> applyIntent()
                .putExtra("theme_file_path", publicPath())

            Recipe.APPLY_FILE_URI -> applyIntent()
                .setData(Uri.fromFile(File(publicPath())))
                .putExtra("theme_file_path", publicPath())

            Recipe.APPLY_CONTENT_URI -> applyIntent()
                .setData(contentUri())
                .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)

            Recipe.VIEW_CONTENT_OCTET -> Intent(Intent.ACTION_VIEW)
                .setDataAndType(contentUri(), "application/octet-stream")
                .setPackage(pkg)
                .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)

            Recipe.VIEW_CONTENT_MTZ -> Intent(Intent.ACTION_VIEW)
                .setDataAndType(contentUri(), "application/x-mtz")
                .setPackage(pkg)
                .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)

            Recipe.VIEW_FILE_URI -> Intent(Intent.ACTION_VIEW)
                .setDataAndType(Uri.fromFile(File(publicPath())), "application/octet-stream")
                .setPackage(pkg)

            Recipe.THEME_TAB -> Intent()
                .setComponent(ComponentName(pkg, Recipe.TAB_ACTIVITY))

            Recipe.LAUNCH_APP -> context.packageManager.getLaunchIntentForPackage(pkg)
                ?: throw IllegalStateException("у «Тем» нет экрана запуска")
        }.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
    }

    /** Произвольная активность «Тем» — для ручного перебора в диагностике. */
    fun buildCustom(context: Context, activity: String, public: File?): Intent {
        val intent = Intent()
            .setComponent(ComponentName(ThemeInstaller.THEME_PKG, activity))
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        if (public != null) intent.putExtra("theme_file_path", public.absolutePath)
        return intent
    }
}
