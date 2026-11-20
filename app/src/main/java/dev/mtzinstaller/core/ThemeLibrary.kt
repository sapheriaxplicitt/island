package dev.mtzinstaller.core

import android.content.Context
import android.graphics.Bitmap
import android.net.Uri
import android.provider.OpenableColumns
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.io.IOException
import java.util.UUID
import java.util.zip.ZipException

data class ThemeEntry(
    val id: String,
    val title: String,
    val author: String?,
    val sizeBytes: Long,
    val addedAt: Long,
)

/** Импортированные темы хранятся во внутренней памяти приложения, чтобы их можно было переустановить. */
class ThemeLibrary(private val context: Context) {

    private val dir = File(context.filesDir, "themes").apply { mkdirs() }
    private val prefs = context.getSharedPreferences("library", Context.MODE_PRIVATE)

    fun fileOf(entry: ThemeEntry) = File(dir, "${entry.id}.mtz")
    fun coverOf(entry: ThemeEntry) = File(dir, "${entry.id}.jpg")

    fun list(): List<ThemeEntry> {
        val raw = prefs.getString(KEY_ITEMS, "[]") ?: "[]"
        val array = runCatching { JSONArray(raw) }.getOrDefault(JSONArray())
        val result = ArrayList<ThemeEntry>()
        for (i in 0 until array.length()) {
            val o = array.optJSONObject(i) ?: continue
            val entry = ThemeEntry(
                id = o.optString("id"),
                title = o.optString("title"),
                author = o.optString("author").takeIf { it.isNotBlank() },
                sizeBytes = o.optLong("size"),
                addedAt = o.optLong("addedAt"),
            )
            if (entry.id.isNotBlank() && fileOf(entry).exists()) result.add(entry)
        }
        return result.sortedByDescending { it.addedAt }
    }

    private fun save(items: List<ThemeEntry>) {
        val array = JSONArray()
        items.forEach { e ->
            array.put(
                JSONObject()
                    .put("id", e.id)
                    .put("title", e.title)
                    .put("author", e.author ?: "")
                    .put("size", e.sizeBytes)
                    .put("addedAt", e.addedAt)
            )
        }
        prefs.edit().putString(KEY_ITEMS, array.toString()).apply()
    }

    fun import(uri: Uri): Pair<ThemeEntry, MtzInfo> {
        val displayName = queryName(uri) ?: "theme.mtz"
        val id = UUID.randomUUID().toString()
        val target = File(dir, "$id.mtz")
        val cover = File(dir, "$id.jpg")
        try {
            val input = context.contentResolver.openInputStream(uri)
                ?: throw IOException("Не удалось открыть файл")
            input.use { src -> target.outputStream().use { src.copyTo(it) } }

            val info = try {
                MtzParser.parse(target)
            } catch (e: ZipException) {
                throw IOException("Файл повреждён или это не тема .mtz")
            }
            if (!info.hasDescription && info.components.isEmpty()) {
                throw IOException("Внутри файла нет темы MIUI")
            }

            info.previewEntries.firstOrNull()?.let { entryName ->
                MtzParser.loadPreview(target, entryName, 480)?.let { bmp ->
                    cover.outputStream().use { bmp.compress(Bitmap.CompressFormat.JPEG, 85, it) }
                    bmp.recycle()
                }
            }

            val entry = ThemeEntry(
                id = id,
                title = info.title ?: displayName.substringBeforeLast('.'),
                author = info.author ?: info.designer,
                sizeBytes = target.length(),
                addedAt = System.currentTimeMillis(),
            )
            save(listOf(entry) + list())
            return entry to info
        } catch (t: Throwable) {
            target.delete()
            cover.delete()
            throw t
        }
    }

    fun delete(entry: ThemeEntry) {
        fileOf(entry).delete()
        coverOf(entry).delete()
        save(list().filterNot { it.id == entry.id })
        if (prefs.getString(KEY_LAST, null) == entry.id) prefs.edit().remove(KEY_LAST).apply()
    }

    /** Последняя тема, которую установили через приложение — для быстрого повторного применения. */
    fun lastApplied(): ThemeEntry? {
        val id = prefs.getString(KEY_LAST, null) ?: return null
        return list().firstOrNull { it.id == id }
    }

    fun setLastApplied(id: String) {
        prefs.edit().putString(KEY_LAST, id).apply()
    }

    private fun queryName(uri: Uri): String? {
        val fromResolver = runCatching {
            context.contentResolver
                .query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)
                ?.use { c -> if (c.moveToFirst()) c.getString(0) else null }
        }.getOrNull()
        return fromResolver ?: uri.lastPathSegment
    }

    private companion object {
        const val KEY_ITEMS = "items"
        const val KEY_LAST = "last_applied"
    }
}
