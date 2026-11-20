package dev.mtzinstaller.core

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.util.Xml
import org.xmlpull.v1.XmlPullParser
import java.io.File
import java.io.InputStream
import java.util.Locale
import java.util.zip.ZipFile

/** Что удалось прочитать из .mtz (это обычный zip с description.xml и папками компонентов). */
data class MtzInfo(
    val title: String?,
    val author: String?,
    val designer: String?,
    val version: String?,
    val uiVersion: String?,
    val description: String?,
    val components: List<String>,
    val previewEntries: List<String>,
    val sizeBytes: Long,
    val hasDescription: Boolean,
)

object MtzParser {

    private val IMAGE_EXT = listOf(".jpg", ".jpeg", ".png", ".webp")
    private val NOT_COMPONENTS = setOf(
        "description.xml", "preview", "META-INF", "plugin_config.xml", "theme_values.xml",
    )

    fun parse(file: File): MtzInfo = ZipFile(file).use { zip ->
        val names = zip.entries().asSequence().map { it.name }.toList()

        val descEntry = zip.getEntry("description.xml")
        val fields: Map<String, String> = if (descEntry != null) {
            zip.getInputStream(descEntry).use { input ->
                runCatching { parseDescription(input) }.getOrDefault(emptyMap())
            }
        } else {
            emptyMap()
        }

        val components = names
            .map { it.substringBefore('/') }
            .filter { it.isNotBlank() && it !in NOT_COMPONENTS && !it.startsWith(".") }
            .distinct()
            .sorted()

        val previews = names
            .filter { name ->
                val lower = name.lowercase(Locale.ROOT)
                lower.startsWith("preview/") && IMAGE_EXT.any { lower.endsWith(it) }
            }
            .sortedWith(compareBy<String>({ previewRank(it) }, { it }))

        MtzInfo(
            title = fields["title"],
            author = fields["author"],
            designer = fields["designer"],
            version = fields["version"],
            uiVersion = fields["uiVersion"],
            description = fields["description"],
            components = components,
            previewEntries = previews,
            sizeBytes = file.length(),
            hasDescription = descEntry != null,
        )
    }

    fun loadPreview(file: File, entryName: String, maxSide: Int): Bitmap? = runCatching {
        ZipFile(file).use { zip ->
            val entry = zip.getEntry(entryName)
            if (entry == null) {
                null
            } else {
                val bytes = zip.getInputStream(entry).use { it.readBytes() }
                decodeSampled(bytes, maxSide)
            }
        }
    }.getOrNull()

    private fun decodeSampled(bytes: ByteArray, maxSide: Int): Bitmap? {
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeByteArray(bytes, 0, bytes.size, bounds)
        if (bounds.outWidth <= 0 || bounds.outHeight <= 0) return null
        var sample = 1
        while (maxOf(bounds.outWidth, bounds.outHeight) / (sample * 2) >= maxSide) sample *= 2
        val opts = BitmapFactory.Options().apply {
            inSampleSize = sample
            inPreferredConfig = Bitmap.Config.RGB_565
        }
        return BitmapFactory.decodeByteArray(bytes, 0, bytes.size, opts)
    }

    private fun previewRank(name: String): Int {
        val n = name.lowercase(Locale.ROOT)
        return when {
            "cover" in n -> 0
            "lockscreen" in n || "lockstyle" in n -> 1
            "launcher" in n || "home" in n || "desktop" in n -> 2
            "icon" in n -> 3
            "systemui" in n || "statusbar" in n || "notification" in n -> 4
            else -> 5
        }
    }

    /** Берём поля верхнего уровня <theme>. Локализованные значения — только под язык системы. */
    private fun parseDescription(input: InputStream): Map<String, String> {
        val lang = Locale.getDefault().language
        val parser = Xml.newPullParser()
        parser.setFeature(XmlPullParser.FEATURE_PROCESS_NAMESPACES, false)
        parser.setInput(input, null)
        val result = HashMap<String, String>()
        var event = parser.eventType
        while (event != XmlPullParser.END_DOCUMENT) {
            if (event == XmlPullParser.START_TAG && parser.depth == 2) {
                val key = parser.name
                val locale = parser.getAttributeValue(null, "locale")
                val text = runCatching { parser.nextText() }.getOrNull()?.trim()
                if (!text.isNullOrEmpty()) {
                    val matchesLang = locale != null && locale.startsWith(lang)
                    if (locale == null || matchesLang || key !in result) result[key] = text
                }
            }
            event = parser.next()
        }
        return result
    }

    private val COMPONENT_LABELS = mapOf(
        "framework-res" to "Система",
        "framework-miui-res" to "Система MIUI",
        "com.android.systemui" to "Шторка и статус-бар",
        "com.android.settings" to "Настройки",
        "com.miui.home" to "Рабочий стол",
        "icons" to "Иконки",
        "lockscreen" to "Экран блокировки",
        "lockstyle" to "Стиль блокировки",
        "wallpaper" to "Обои",
        "miwallpaper" to "Живые обои",
        "fonts" to "Шрифты",
        "bootanimation" to "Анимация загрузки",
        "boots" to "Анимация загрузки",
        "com.android.contacts" to "Телефон и контакты",
        "com.android.incallui" to "Экран вызова",
        "com.android.mms" to "Сообщения",
        "com.android.deskclock" to "Часы",
        "com.miui.securitycenter" to "Безопасность",
        "com.android.thememanager" to "Темы",
        "com.miui.notification" to "Уведомления",
        "statusbar" to "Статус-бар",
        "alarmscreen" to "Будильник",
        "ringtones" to "Мелодии",
        "audio" to "Звуки",
    )

    fun componentLabel(name: String): String = COMPONENT_LABELS[name] ?: when {
        name.startsWith("clock_") -> "Виджет часов"
        name.startsWith("photoframe_") -> "Виджет фото"
        else -> name
    }
}
