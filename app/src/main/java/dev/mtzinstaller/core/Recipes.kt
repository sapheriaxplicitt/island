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
    /** Активность «Тем», в которую стучимся, если она известна заранее. */
    val component: String? = null,
) {
    APPLY_PATH(
        "ApplyThemeForScreenshot + путь",
        "Классический способ: окно применения темы, путь к файлу в extra",
        true,
        APPLY_ACTIVITY,
    ),
    APPLY_FILE_URI(
        "ApplyThemeForScreenshot + file://",
        "То же окно, но файл передаётся как data-Uri",
        true,
        APPLY_ACTIVITY,
    ),
    APPLY_CONTENT_URI(
        "ApplyThemeForScreenshot + content://",
        "То же окно, файл через FileProvider",
        false,
        APPLY_ACTIVITY,
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
        TAB_ACTIVITY,
    ),
    LAUNCH_APP(
        "Запустить «Темы»",
        "Просто открыть приложение — импорт вручную",
        false,
    ),
    GATE_FILE(
        "ThemeGateActivity + файл",
        "Роутер «Тем» в HyperOS 3: отдаём ему файл и смотрим, подхватит ли",
        true,
        GATE_ACTIVITY,
    ),
    COMPONENT_LOCAL(
        "ComponentActivity + файл",
        "Экран локальных компонентов — на HyperOS 3 именно он отвечает за свои темы",
        true,
        LOCAL_COMPONENT_ACTIVITY,
    ),
    DETAIL_LOCAL(
        "ThemeDetailActivity + файл",
        "Карточка темы: иногда открывается и для файла с диска",
        true,
        DETAIL_ACTIVITY,
    ),
    DEEPLINK_THEME(
        "Ссылка theme://",
        "Внутренняя ссылка «Тем» на локальный файл",
        true,
    ),
    ACTION_MIUI_APPLY(
        "miui.intent.action.THEME_APPLY",
        "Системное действие применения темы, без указания активности",
        true,
    ),
    VIEW_MIUI_MIME(
        "VIEW vnd.miui.theme",
        "Открыть файл с «родным» mime-типом темы MIUI",
        false,
    ),
    ;

    companion object {
        const val APPLY_ACTIVITY = "com.android.thememanager.ApplyThemeForScreenshot"
        const val TAB_ACTIVITY = "com.android.thememanager.ThemeResourceTabActivity"
        const val GATE_ACTIVITY = "com.android.thememanager.activity.ThemeGateActivity"
        const val DETAIL_ACTIVITY = "com.android.thememanager.activity.ThemeDetailActivity"
        const val LOCAL_COMPONENT_ACTIVITY =
            "com.android.thememanager.mine.local.customize.ComponentActivity"
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

            Recipe.GATE_FILE -> withFile(context, Recipe.GATE_ACTIVITY, internal, publicPath())

            Recipe.COMPONENT_LOCAL ->
                withFile(context, Recipe.LOCAL_COMPONENT_ACTIVITY, internal, publicPath())
                    .putExtra("REQUEST_RESOURCE_CODE", "theme")

            Recipe.DETAIL_LOCAL -> withFile(context, Recipe.DETAIL_ACTIVITY, internal, publicPath())

            Recipe.DEEPLINK_THEME -> Intent(Intent.ACTION_VIEW)
                .setData(
                    Uri.Builder()
                        .scheme("theme")
                        .authority("resource")
                        .appendQueryParameter("filePath", publicPath())
                        .build()
                )
                .setPackage(pkg)

            Recipe.ACTION_MIUI_APPLY -> Intent("miui.intent.action.THEME_APPLY")
                .setPackage(pkg)
                .putExtra("theme_file_path", publicPath())

            Recipe.VIEW_MIUI_MIME -> Intent(Intent.ACTION_VIEW)
                .setDataAndType(contentUri(), "application/vnd.miui.theme")
                .setPackage(pkg)
                .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
    }

    /**
     * Кладём файл в intent всеми способами сразу: путь в нескольких extra,
     * которые встречаются во внутренностях «Тем», плюс content-Uri в ClipData
     * (так приложение получает право на чтение).
     */
    private fun withFile(context: Context, activity: String, internal: File, path: String): Intent {
        val uri = FileProvider.getUriForFile(context, "${context.packageName}.files", internal)
        return Intent()
            .setComponent(ComponentName(ThemeInstaller.THEME_PKG, activity))
            .putExtra("theme_file_path", path)
            .putExtra("themeFilePath", path)
            .putExtra("resource_path", path)
            .putExtra("file_path", path)
            .putExtra("android.intent.extra.STREAM", uri)
            .setClipData(android.content.ClipData.newRawUri("theme", uri))
            .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
    }

    /** Произвольная активность «Тем» — для ручного перебора в диагностике. */
    fun buildCustom(context: Context, activity: String, internal: File, public: File?): Intent {
        val path = public?.absolutePath
        val intent = if (path != null) {
            withFile(context, activity, internal, path)
        } else {
            Intent().setComponent(ComponentName(ThemeInstaller.THEME_PKG, activity))
        }
        return intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
    }
}
