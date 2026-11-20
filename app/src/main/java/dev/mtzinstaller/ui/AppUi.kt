package dev.mtzinstaller.ui

import android.Manifest
import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import android.graphics.BitmapFactory
import android.net.Uri
import android.os.Build
import android.provider.Settings
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.Star
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ElevatedCard
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.SuggestionChip
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import dev.mtzinstaller.MainViewModel
import dev.mtzinstaller.ThemeDetail
import dev.mtzinstaller.core.InstallMethod
import dev.mtzinstaller.core.MtzParser
import dev.mtzinstaller.core.ThemeEntry
import dev.mtzinstaller.core.ThemeInstaller
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

private val Green = Color(0xFF2E9E5B)

@Composable
fun AppRoot(vm: MainViewModel) {
    val context = LocalContext.current
    val snackbar = remember { SnackbarHostState() }
    var showSettings by remember { mutableStateOf(false) }
    var pendingInstall by remember { mutableStateOf<Pair<ThemeEntry, InstallMethod?>?>(null) }

    val picker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) vm.importUri(uri)
    }
    val legacyPermission = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { granted ->
        val pending = pendingInstall
        pendingInstall = null
        if (pending != null) {
            vm.install(pending.first, if (granted) pending.second else InstallMethod.OPEN_WITH)
        }
    }

    fun startInstall(entry: ThemeEntry, override: InstallMethod?) {
        val method = override ?: vm.method
        if (method == InstallMethod.OPEN_WITH || ThemeInstaller.hasStorageAccess(context)) {
            vm.install(entry, override)
        } else {
            pendingInstall = entry to override
        }
    }

    LaunchedEffect(vm.message) {
        val text = vm.message
        if (text != null) {
            snackbar.showSnackbar(text)
            vm.message = null
        }
    }

    Box(Modifier.fillMaxSize()) {
        val detail = vm.detail
        if (detail != null) {
            BackHandler { vm.closeDetail() }
            DetailScreen(
                detail = detail,
                method = vm.method,
                onBack = { vm.closeDetail() },
                onInstall = { override -> startInstall(detail.entry, override) },
                onDelete = { vm.delete(detail.entry) },
            )
        } else {
            HomeScreen(
                vm = vm,
                onPick = { picker.launch(arrayOf("*/*")) },
                onSettings = { showSettings = true },
                onOpen = { vm.open(it) },
            )
        }
        SnackbarHost(
            hostState = snackbar,
            modifier = Modifier
                .align(Alignment.BottomCenter)
                .navigationBarsPadding()
                .padding(bottom = if (detail != null) 80.dp else 0.dp),
        )
    }

    pendingInstall?.let { (entry, _) ->
        AlertDialog(
            onDismissRequest = { pendingInstall = null },
            title = { Text("Нужен доступ к файлам") },
            text = {
                Text(
                    "Чтобы «Темы» увидели файл, его нужно положить в Загрузки/MTZ Installer. " +
                        "Для этого нужен доступ ко всем файлам.\n\n" +
                        "Можно и без него — тогда тема будет передана в «Темы» напрямую, " +
                        "но некоторые версии MIUI так не принимают."
                )
            },
            confirmButton = {
                TextButton(onClick = {
                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                        pendingInstall = null
                        openAllFilesSettings(context)
                        vm.message = "Разрешите доступ и снова нажмите «Установить»"
                    } else {
                        legacyPermission.launch(Manifest.permission.WRITE_EXTERNAL_STORAGE)
                    }
                }) { Text("Разрешить") }
            },
            dismissButton = {
                TextButton(onClick = {
                    pendingInstall = null
                    vm.install(entry, InstallMethod.OPEN_WITH)
                }) { Text("Без доступа") }
            },
        )
    }

    if (showSettings) {
        SettingsDialog(
            current = vm.method,
            onSelect = { vm.updateMethod(it) },
            onOpenThemes = { vm.openThemeManager() },
            onDismiss = { showSettings = false },
        )
    }

    vm.busy?.let { BusyDialog(it) }
}

private fun openAllFilesSettings(context: Context) {
    if (Build.VERSION.SDK_INT < Build.VERSION_CODES.R) return
    val perApp = Intent(
        Settings.ACTION_MANAGE_APP_ALL_FILES_ACCESS_PERMISSION,
        Uri.parse("package:${context.packageName}"),
    )
    try {
        context.startActivity(perApp)
    } catch (e: ActivityNotFoundException) {
        try {
            context.startActivity(Intent(Settings.ACTION_MANAGE_ALL_FILES_ACCESS_PERMISSION))
        } catch (e2: ActivityNotFoundException) {
            // настройки недоступны — пользователь откроет их сам
        }
    }
}

// ---------------------------------------------------------------- Главный экран

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun HomeScreen(
    vm: MainViewModel,
    onPick: () -> Unit,
    onSettings: () -> Unit,
    onOpen: (ThemeEntry) -> Unit,
) {
    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("MTZ Installer", fontWeight = FontWeight.SemiBold) },
                actions = {
                    IconButton(onClick = onSettings) {
                        Icon(Icons.Filled.Settings, contentDescription = "Настройки")
                    }
                },
            )
        },
    ) { padding ->
        LazyColumn(
            contentPadding = PaddingValues(
                start = 16.dp,
                end = 16.dp,
                top = padding.calculateTopPadding() + 4.dp,
                bottom = padding.calculateBottomPadding() + 32.dp,
            ),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            item { HeroCard(onPick) }
            vm.lastApplied?.let { last ->
                item(key = "last") {
                    LastThemeCard(
                        entry = last,
                        cover = vm.coverFile(last),
                        onReapply = { vm.install(last) },
                        onPin = { vm.pinShortcut() },
                    )
                }
            }
            item { StatusCard(vm) }
            item {
                Text(
                    text = if (vm.themes.isEmpty()) "Библиотека" else "Библиотека · ${vm.themes.size}",
                    style = MaterialTheme.typography.titleMedium,
                    modifier = Modifier.padding(top = 8.dp, start = 4.dp),
                )
            }
            if (vm.themes.isEmpty()) {
                item {
                    Text(
                        "Здесь появятся импортированные темы — их можно будет переустановить в любой момент. " +
                            "Файл .mtz можно также открыть из файлового менеджера через «Открыть с помощью».",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(horizontal = 4.dp),
                    )
                }
            }
            items(vm.themes, key = { it.id }) { entry ->
                ThemeRow(entry = entry, cover = vm.coverFile(entry), onClick = { onOpen(entry) })
            }
        }
    }
}

@Composable
private fun HeroCard(onPick: () -> Unit) {
    val colors = MaterialTheme.colorScheme
    Box(
        Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(28.dp))
            .background(Brush.linearGradient(listOf(colors.primaryContainer, colors.tertiaryContainer)))
            .padding(22.dp)
    ) {
        Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Text(
                "Темы для MIUI и HyperOS",
                style = MaterialTheme.typography.headlineSmall,
                fontWeight = FontWeight.Bold,
                color = colors.onPrimaryContainer,
            )
            Text(
                "Выберите файл .mtz — покажу превью и состав темы, а потом установлю её в пару нажатий.",
                style = MaterialTheme.typography.bodyMedium,
                color = colors.onPrimaryContainer.copy(alpha = 0.85f),
            )
            Spacer(Modifier.height(4.dp))
            Button(onClick = onPick) {
                Icon(Icons.Filled.Add, contentDescription = null)
                Spacer(Modifier.width(8.dp))
                Text("Выбрать .mtz")
            }
        }
    }
}

@Composable
private fun LastThemeCard(entry: ThemeEntry, cover: File, onReapply: () -> Unit, onPin: () -> Unit) {
    ElevatedCard(Modifier.fillMaxWidth()) {
        Row(Modifier.padding(14.dp), verticalAlignment = Alignment.CenterVertically) {
            CoverImage(
                file = cover,
                modifier = Modifier
                    .width(48.dp)
                    .height(86.dp)
                    .clip(RoundedCornerShape(10.dp)),
            )
            Spacer(Modifier.width(14.dp))
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Text(
                    "Тема слетела? Верните её",
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.primary,
                )
                Text(
                    entry.title,
                    style = MaterialTheme.typography.titleMedium,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Button(
                        onClick = onReapply,
                        contentPadding = PaddingValues(horizontal = 14.dp, vertical = 8.dp),
                    ) {
                        Icon(Icons.Filled.Refresh, contentDescription = null, modifier = Modifier.size(18.dp))
                        Spacer(Modifier.width(6.dp))
                        Text("Вернуть")
                    }
                    OutlinedButton(
                        onClick = onPin,
                        contentPadding = PaddingValues(horizontal = 14.dp, vertical = 8.dp),
                    ) { Text("Ярлык") }
                }
                Text(
                    "Ещё есть плитка «Вернуть тему» в шторке — добавьте её через редактор плиток.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }
}

@Composable
private fun StatusCard(vm: MainViewModel) {
    ElevatedCard(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            StatusLine(
                label = "Система",
                value = when {
                    !vm.statusLoaded -> "проверяю…"
                    vm.romName != null -> vm.romName!!
                    else -> "не MIUI / HyperOS"
                },
                ok = vm.romName != null || !vm.statusLoaded,
            )
            StatusLine(
                label = "Приложение «Темы»",
                value = vm.themeManager?.let { "есть · $it" } ?: "не найдено",
                ok = vm.themeManager != null || !vm.statusLoaded,
            )
            StatusLine(label = "Способ установки", value = vm.method.title, ok = null)
        }
    }
}

@Composable
private fun StatusLine(label: String, value: String, ok: Boolean?) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        val dot = when (ok) {
            true -> Green
            false -> MaterialTheme.colorScheme.error
            null -> MaterialTheme.colorScheme.outline
        }
        Box(
            Modifier
                .size(10.dp)
                .clip(CircleShape)
                .background(dot)
        )
        Spacer(Modifier.width(12.dp))
        Text(
            label,
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.weight(1f),
        )
        Text(value, style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.Medium)
    }
}

@Composable
private fun ThemeRow(entry: ThemeEntry, cover: File, onClick: () -> Unit) {
    Card(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainerHigh),
    ) {
        Row(Modifier.padding(12.dp), verticalAlignment = Alignment.CenterVertically) {
            CoverImage(
                file = cover,
                modifier = Modifier
                    .width(54.dp)
                    .height(96.dp)
                    .clip(RoundedCornerShape(12.dp)),
            )
            Spacer(Modifier.width(14.dp))
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                Text(
                    entry.title,
                    style = MaterialTheme.typography.titleMedium,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                entry.author?.let {
                    Text(
                        it,
                        style = MaterialTheme.typography.bodyMedium,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
                Text(
                    "${formatSize(entry.sizeBytes)} · ${formatDate(entry.addedAt)}",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            Icon(
                Icons.AutoMirrored.Filled.KeyboardArrowRight,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

@Composable
private fun CoverImage(file: File, modifier: Modifier) {
    val bitmap by produceState<ImageBitmap?>(initialValue = null, file) {
        value = withContext(Dispatchers.IO) {
            if (file.exists()) BitmapFactory.decodeFile(file.absolutePath)?.asImageBitmap() else null
        }
    }
    Box(modifier.background(MaterialTheme.colorScheme.surfaceVariant), contentAlignment = Alignment.Center) {
        val b = bitmap
        if (b != null) {
            Image(b, contentDescription = null, modifier = Modifier.fillMaxSize(), contentScale = ContentScale.Crop)
        } else {
            Icon(Icons.Filled.Star, contentDescription = null, tint = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}

// ---------------------------------------------------------------- Экран темы

@OptIn(ExperimentalMaterial3Api::class, ExperimentalLayoutApi::class)
@Composable
private fun DetailScreen(
    detail: ThemeDetail,
    method: InstallMethod,
    onBack: () -> Unit,
    onInstall: (InstallMethod?) -> Unit,
    onDelete: () -> Unit,
) {
    var confirmDelete by remember { mutableStateOf(false) }
    var chooseMethod by remember { mutableStateOf(false) }
    val info = detail.info

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(detail.entry.title, maxLines = 1, overflow = TextOverflow.Ellipsis) },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Назад")
                    }
                },
                actions = {
                    IconButton(onClick = { confirmDelete = true }) {
                        Icon(Icons.Filled.Delete, contentDescription = "Удалить из библиотеки")
                    }
                },
            )
        },
        bottomBar = {
            Surface(tonalElevation = 3.dp) {
                Row(
                    Modifier
                        .fillMaxWidth()
                        .navigationBarsPadding()
                        .padding(horizontal = 16.dp, vertical = 12.dp),
                    horizontalArrangement = Arrangement.spacedBy(12.dp),
                ) {
                    OutlinedButton(onClick = { chooseMethod = true }) { Text("Способ…") }
                    Button(onClick = { onInstall(null) }, modifier = Modifier.weight(1f)) {
                        Icon(Icons.Filled.Check, contentDescription = null)
                        Spacer(Modifier.width(8.dp))
                        Text("Установить")
                    }
                }
            }
        },
    ) { padding ->
        LazyColumn(
            contentPadding = PaddingValues(
                top = padding.calculateTopPadding() + 8.dp,
                bottom = padding.calculateBottomPadding() + 16.dp,
            ),
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            item { PreviewStrip(detail) }

            item {
                Column(Modifier.padding(horizontal = 16.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    Text(
                        info.title ?: detail.entry.title,
                        style = MaterialTheme.typography.headlineSmall,
                        fontWeight = FontWeight.Bold,
                    )
                    val people = listOfNotNull(
                        info.author?.let { "Автор: $it" },
                        info.designer?.takeIf { it != info.author }?.let { "Дизайнер: $it" },
                    )
                    if (people.isNotEmpty()) {
                        Text(
                            people.joinToString(" · "),
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
            }

            item {
                Row(
                    Modifier.padding(horizontal = 16.dp),
                    horizontalArrangement = Arrangement.spacedBy(10.dp),
                ) {
                    InfoTile("Версия", info.version ?: "—", Modifier.weight(1f))
                    InfoTile("UI", info.uiVersion?.let { "v$it" } ?: "—", Modifier.weight(1f))
                    InfoTile("Размер", formatSize(info.sizeBytes), Modifier.weight(1f))
                }
            }

            info.description?.takeIf { it.isNotBlank() }?.let { desc ->
                item {
                    Text(
                        desc,
                        style = MaterialTheme.typography.bodyMedium,
                        modifier = Modifier.padding(horizontal = 16.dp),
                    )
                }
            }

            if (info.components.isNotEmpty()) {
                item {
                    Column(Modifier.padding(horizontal = 16.dp)) {
                        Text(
                            "Состав темы · ${info.components.size}",
                            style = MaterialTheme.typography.titleMedium,
                        )
                        Spacer(Modifier.height(8.dp))
                        FlowRow(
                            horizontalArrangement = Arrangement.spacedBy(8.dp),
                            verticalArrangement = Arrangement.spacedBy(4.dp),
                        ) {
                            info.components
                                .map { MtzParser.componentLabel(it) }
                                .distinct()
                                .forEach { label ->
                                    SuggestionChip(onClick = {}, label = { Text(label) })
                                }
                        }
                    }
                }
            }

            item {
                Card(
                    Modifier
                        .padding(horizontal = 16.dp)
                        .fillMaxWidth(),
                    colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.secondaryContainer),
                ) {
                    Row(Modifier.padding(14.dp)) {
                        Icon(Icons.Filled.Info, contentDescription = null)
                        Spacer(Modifier.width(12.dp))
                        Column {
                            Text(method.title, style = MaterialTheme.typography.titleSmall)
                            Text(method.hint, style = MaterialTheme.typography.bodySmall)
                            if (!info.hasDescription) {
                                Spacer(Modifier.height(6.dp))
                                Text(
                                    "В файле нет description.xml — «Темы» могут его не принять.",
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.error,
                                )
                            }
                        }
                    }
                }
            }
        }
    }

    if (chooseMethod) {
        MethodDialog(
            title = "Установить способом",
            current = method,
            onSelect = {
                chooseMethod = false
                onInstall(it)
            },
            onDismiss = { chooseMethod = false },
        )
    }

    if (confirmDelete) {
        AlertDialog(
            onDismissRequest = { confirmDelete = false },
            title = { Text("Удалить из библиотеки?") },
            text = { Text("Файл темы будет удалён из приложения. Уже установленная тема останется.") },
            confirmButton = {
                TextButton(onClick = {
                    confirmDelete = false
                    onDelete()
                }) { Text("Удалить") }
            },
            dismissButton = {
                TextButton(onClick = { confirmDelete = false }) { Text("Отмена") }
            },
        )
    }
}

@Composable
private fun PreviewStrip(detail: ThemeDetail) {
    val total = detail.info.previewEntries.size.coerceAtMost(MainViewModel.MAX_PREVIEWS)
    if (total == 0) {
        Box(
            Modifier
                .padding(horizontal = 16.dp)
                .fillMaxWidth()
                .height(160.dp)
                .clip(RoundedCornerShape(20.dp))
                .background(MaterialTheme.colorScheme.surfaceVariant),
            contentAlignment = Alignment.Center,
        ) {
            Text("В теме нет превью", color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        return
    }
    LazyRow(
        contentPadding = PaddingValues(horizontal = 16.dp),
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        items(detail.previews) { bmp ->
            Image(
                bitmap = bmp,
                contentDescription = null,
                contentScale = ContentScale.Crop,
                modifier = Modifier
                    .height(400.dp)
                    .aspectRatio(bmp.width.toFloat() / bmp.height.coerceAtLeast(1))
                    .clip(RoundedCornerShape(20.dp)),
            )
        }
        if (detail.previews.size < total) {
            item {
                Box(
                    Modifier
                        .height(400.dp)
                        .width(180.dp)
                        .clip(RoundedCornerShape(20.dp))
                        .background(MaterialTheme.colorScheme.surfaceVariant),
                    contentAlignment = Alignment.Center,
                ) { CircularProgressIndicator() }
            }
        }
    }
}

@Composable
private fun InfoTile(label: String, value: String, modifier: Modifier) {
    Surface(
        modifier = modifier,
        shape = RoundedCornerShape(16.dp),
        color = MaterialTheme.colorScheme.surfaceContainerHigh,
    ) {
        Column(Modifier.padding(horizontal = 12.dp, vertical = 10.dp)) {
            Text(label, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            Text(
                value,
                style = MaterialTheme.typography.titleSmall,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
    }
}

// ---------------------------------------------------------------- Диалоги

@Composable
private fun MethodOptions(current: InstallMethod, onSelect: (InstallMethod) -> Unit) {
    Column {
        InstallMethod.entries.forEach { m ->
            Row(
                Modifier
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(12.dp))
                    .clickable { onSelect(m) }
                    .padding(vertical = 6.dp),
                verticalAlignment = Alignment.Top,
            ) {
                RadioButton(selected = m == current, onClick = { onSelect(m) })
                Column(Modifier.padding(top = 12.dp, end = 4.dp)) {
                    Text(m.title, style = MaterialTheme.typography.bodyLarge)
                    Text(
                        m.hint,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
        }
    }
}

@Composable
private fun MethodDialog(
    title: String,
    current: InstallMethod,
    onSelect: (InstallMethod) -> Unit,
    onDismiss: () -> Unit,
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(title) },
        text = { MethodOptions(current, onSelect) },
        confirmButton = { TextButton(onClick = onDismiss) { Text("Отмена") } },
    )
}

@Composable
private fun SettingsDialog(
    current: InstallMethod,
    onSelect: (InstallMethod) -> Unit,
    onOpenThemes: () -> Unit,
    onDismiss: () -> Unit,
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Настройки") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text("Способ установки по умолчанию", style = MaterialTheme.typography.titleSmall)
                MethodOptions(current, onSelect)
                HorizontalDivider()
                Text(
                    "Если тема не применяется, попробуйте другой способ. " +
                        "Копии тем сохраняются в Загрузки/MTZ Installer.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        },
        confirmButton = { TextButton(onClick = onDismiss) { Text("Готово") } },
        dismissButton = { TextButton(onClick = onOpenThemes) { Text("Открыть «Темы»") } },
    )
}

@Composable
private fun BusyDialog(text: String) {
    Dialog(
        onDismissRequest = {},
        properties = DialogProperties(dismissOnBackPress = false, dismissOnClickOutside = false),
    ) {
        Surface(shape = RoundedCornerShape(24.dp), tonalElevation = 6.dp) {
            Row(Modifier.padding(24.dp), verticalAlignment = Alignment.CenterVertically) {
                CircularProgressIndicator(Modifier.size(28.dp))
                Spacer(Modifier.width(18.dp))
                Text(text, style = MaterialTheme.typography.bodyLarge)
            }
        }
    }
}

// ---------------------------------------------------------------- Утилиты

private val RU = Locale.forLanguageTag("ru")

fun formatSize(bytes: Long): String = when {
    bytes >= 1024L * 1024L -> String.format(RU, "%.1f МБ", bytes / (1024.0 * 1024.0))
    bytes >= 1024L -> "${bytes / 1024} КБ"
    else -> "$bytes Б"
}

fun formatDate(ms: Long): String = SimpleDateFormat("d MMM yyyy", RU).format(Date(ms))
