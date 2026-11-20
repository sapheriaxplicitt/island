package dev.mtzinstaller

import android.app.Application
import android.content.Context
import android.net.Uri
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import dev.mtzinstaller.core.DiagReport
import dev.mtzinstaller.core.Diagnostics
import dev.mtzinstaller.core.InstallMethod
import dev.mtzinstaller.core.Recipe
import dev.mtzinstaller.core.MtzInfo
import dev.mtzinstaller.core.MtzParser
import dev.mtzinstaller.core.ThemeEntry
import dev.mtzinstaller.core.ThemeInstaller
import dev.mtzinstaller.core.ThemeLibrary
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File

data class ThemeDetail(
    val entry: ThemeEntry,
    val info: MtzInfo,
    val previews: List<ImageBitmap> = emptyList(),
)

class MainViewModel(app: Application) : AndroidViewModel(app) {

    private val library = ThemeLibrary(app)
    private val prefs = app.getSharedPreferences("settings", Context.MODE_PRIVATE)

    var themes by mutableStateOf(library.list())
        private set
    var lastApplied by mutableStateOf(library.lastApplied())
        private set
    var detail by mutableStateOf<ThemeDetail?>(null)
        private set
    var busy by mutableStateOf<String?>(null)
        private set
    var message by mutableStateOf<String?>(null)
    var romName by mutableStateOf<String?>(null)
        private set
    var themeManager by mutableStateOf<String?>(null)
        private set
    var statusLoaded by mutableStateOf(false)
        private set
    var diag by mutableStateOf<DiagReport?>(null)
        private set
    var diagLog by mutableStateOf<List<String>>(emptyList())
        private set
    var method by mutableStateOf(
        runCatching { InstallMethod.valueOf(prefs.getString("method", null) ?: "") }
            .getOrDefault(InstallMethod.DIRECT)
    )
        private set

    init {
        refreshStatus()
    }

    fun refreshStatus() {
        viewModelScope.launch {
            romName = withContext(Dispatchers.IO) { ThemeInstaller.romName() }
            themeManager = ThemeInstaller.themeManagerVersion(getApplication())
            statusLoaded = true
        }
    }

    fun updateMethod(newMethod: InstallMethod) {
        method = newMethod
        prefs.edit().putString("method", newMethod.name).apply()
    }

    fun coverFile(entry: ThemeEntry): File = library.coverOf(entry)

    fun importUri(uri: Uri) {
        if (busy != null) return
        viewModelScope.launch {
            busy = "Читаю тему…"
            try {
                val (entry, info) = withContext(Dispatchers.IO) { library.import(uri) }
                themes = library.list()
                showDetail(entry, info)
            } catch (e: Exception) {
                message = e.message ?: "Не удалось импортировать файл"
            } finally {
                busy = null
            }
        }
    }

    fun open(entry: ThemeEntry) {
        viewModelScope.launch {
            try {
                val info = withContext(Dispatchers.IO) { MtzParser.parse(library.fileOf(entry)) }
                showDetail(entry, info)
            } catch (e: Exception) {
                message = "Файл темы повреждён: ${e.message}"
            }
        }
    }

    private fun showDetail(entry: ThemeEntry, info: MtzInfo) {
        detail = ThemeDetail(entry, info)
        viewModelScope.launch {
            val file = library.fileOf(entry)
            for (name in info.previewEntries.take(MAX_PREVIEWS)) {
                val bitmap = withContext(Dispatchers.IO) {
                    MtzParser.loadPreview(file, name, 1280)
                } ?: continue
                val current = detail ?: return@launch
                if (current.entry.id != entry.id) return@launch
                detail = current.copy(previews = current.previews + bitmap.asImageBitmap())
            }
        }
    }

    fun closeDetail() {
        detail = null
    }

    fun delete(entry: ThemeEntry) {
        library.delete(entry)
        themes = library.list()
        lastApplied = library.lastApplied()
        if (detail?.entry?.id == entry.id) detail = null
    }

    fun install(entry: ThemeEntry, override: InstallMethod? = null) {
        if (busy != null) return
        viewModelScope.launch {
            busy = "Устанавливаю тему…"
            val result = try {
                ThemeInstaller.install(getApplication(), library.fileOf(entry), entry.title, override ?: method)
            } finally {
                busy = null
            }
            if (result.log.isNotEmpty()) diagLog = result.log + diagLog
            if (result.ok) {
                library.setLastApplied(entry.id)
                lastApplied = entry
                Reapply.onThemeApplied(getApplication(), entry.title)
            }
            message = result.message
        }
    }

    /** Повторно применяет последнюю тему — если MIUI её сбросила. */
    fun reapplyLast() {
        val entry = library.lastApplied()
        if (entry == null) {
            message = "Ещё нет применённой темы — сначала установите её из библиотеки"
        } else {
            install(entry)
        }
    }

    fun pinShortcut() {
        val entry = lastApplied ?: return
        if (!Reapply.requestPin(getApplication(), entry.title)) {
            message = "Лаунчер не поддерживает закрепление ярлыков"
        }
    }

    // ------------------------------------------------------------ Диагностика

    fun openDiagnostics() {
        viewModelScope.launch {
            busy = "Собираю данные…"
            diag = withContext(Dispatchers.IO) { Diagnostics.collect(getApplication()) }
            busy = null
        }
    }

    fun closeDiagnostics() {
        diag = null
    }

    /** Тема, на которой гоняем способы в диагностике. */
    private fun probeTarget(): ThemeEntry? = lastApplied ?: themes.firstOrNull()

    fun probe(recipe: Recipe) {
        val entry = probeTarget()
        if (entry == null) {
            message = "Сначала импортируйте тему — без файла проверять нечего"
            return
        }
        viewModelScope.launch {
            val result = ThemeInstaller.runRecipe(
                getApplication(), library.fileOf(entry), entry.title, recipe,
            )
            diagLog = listOf(result.message) + diagLog
            message = result.message
        }
    }

    fun probeActivity(activity: String) {
        val entry = probeTarget()
        if (entry == null) {
            message = "Сначала импортируйте тему — без файла проверять нечего"
            return
        }
        viewModelScope.launch {
            val result = ThemeInstaller.runCustom(
                getApplication(), library.fileOf(entry), entry.title, activity,
            )
            diagLog = listOf(result.message) + diagLog
            message = result.message
        }
    }

    fun diagText(): String = buildString {
        append(diag?.asText() ?: "")
        if (diagLog.isNotEmpty()) {
            appendLine()
            appendLine("Что ответила система на каждый способ:")
            diagLog.forEach { appendLine("  $it") }
        }
    }

    fun clearLog() {
        diagLog = emptyList()
    }

    fun openThemeManager() {
        if (!ThemeInstaller.openThemeManager(getApplication())) {
            message = "Приложение «Темы» не найдено"
        }
    }

    companion object {
        const val MAX_PREVIEWS = 10
    }
}
