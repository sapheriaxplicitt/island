package dev.mtzinstaller.ui

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import dev.mtzinstaller.MainViewModel
import dev.mtzinstaller.core.DiagReport
import dev.mtzinstaller.core.Recipe

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun DiagScreen(vm: MainViewModel, report: DiagReport, onBack: () -> Unit) {
    val context = LocalContextCompat()

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Диагностика") },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Назад")
                    }
                },
                actions = {
                    TextButton(onClick = { copyToClipboard(context, vm.diagText()) }) {
                        Text("Копировать")
                    }
                },
            )
        },
    ) { padding ->
        LazyColumn(
            contentPadding = PaddingValues(
                start = 16.dp,
                end = 16.dp,
                top = padding.calculateTopPadding() + 8.dp,
                bottom = padding.calculateBottomPadding() + 32.dp,
            ),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            item {
                Text(
                    "Нажмите «Копировать» и пришлите отчёт — по нему видно, почему тема не применяется.",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }

            item {
                Card(Modifier.fillMaxWidth()) {
                    Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                        report.lines.forEach { (key, value) ->
                            Row {
                                Text(
                                    key,
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                    modifier = Modifier.width(140.dp),
                                )
                                Text(value, style = MaterialTheme.typography.bodySmall)
                            }
                        }
                    }
                }
            }

            item {
                Text("Проверить способы по очереди", style = MaterialTheme.typography.titleMedium)
            }
            items(Recipe.entries.size) { index ->
                val recipe = Recipe.entries[index]
                Card(
                    Modifier
                        .fillMaxWidth()
                        .clickable { vm.probe(recipe) },
                    colors = CardDefaults.cardColors(
                        containerColor = MaterialTheme.colorScheme.surfaceContainerHigh,
                    ),
                ) {
                    Column(Modifier.padding(14.dp)) {
                        Text(recipe.title, style = MaterialTheme.typography.titleSmall)
                        Text(
                            recipe.detail,
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
            }

            if (report.activities.isNotEmpty()) {
                item {
                    Column {
                        HorizontalDivider(Modifier.padding(vertical = 8.dp))
                        Text(
                            "Активности «Тем» · ${report.activities.size}",
                            style = MaterialTheme.typography.titleMedium,
                        )
                        Text(
                            "«закрыта» значит, что другие приложения её вызвать не могут. " +
                                "Нажмите, чтобы попробовать вручную.",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
                items(report.activities.size) { index ->
                    val activity = report.activities[index]
                    Surface(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clickable(enabled = activity.exported) {
                                vm.probeActivity(activity.name)
                            },
                        shape = RoundedCornerShape(12.dp),
                        color = MaterialTheme.colorScheme.surfaceContainer,
                    ) {
                        Column(Modifier.padding(horizontal = 12.dp, vertical = 8.dp)) {
                            Text(
                                activity.name.removePrefix("com.android.thememanager."),
                                style = MaterialTheme.typography.bodySmall,
                                fontFamily = FontFamily.Monospace,
                            )
                            Text(
                                if (activity.exported) "открыта" else "закрыта",
                                style = MaterialTheme.typography.labelSmall,
                                color = if (activity.exported) {
                                    MaterialTheme.colorScheme.primary
                                } else {
                                    MaterialTheme.colorScheme.error
                                },
                            )
                        }
                    }
                }
            }

            if (vm.diagLog.isNotEmpty()) {
                item {
                    Column {
                        HorizontalDivider(Modifier.padding(vertical = 8.dp))
                        Row {
                            Text(
                                "Ответы системы",
                                style = MaterialTheme.typography.titleMedium,
                                modifier = Modifier.weight(1f),
                            )
                            TextButton(onClick = { vm.clearLog() }) { Text("Очистить") }
                        }
                    }
                }
                items(vm.diagLog.size) { index ->
                    Text(
                        vm.diagLog[index],
                        style = MaterialTheme.typography.bodySmall,
                        fontFamily = FontFamily.Monospace,
                    )
                }
            }

            item {
                Row(
                    Modifier
                        .fillMaxWidth()
                        .navigationBarsPadding(),
                    horizontalArrangement = Arrangement.spacedBy(12.dp),
                ) {
                    Button(
                        onClick = { copyToClipboard(context, vm.diagText()) },
                        modifier = Modifier.weight(1f),
                    ) { Text("Копировать отчёт") }
                    OutlinedButton(onClick = { vm.openThemeManager() }) { Text("«Темы»") }
                }
            }
        }
    }
}

@Composable
private fun LocalContextCompat(): Context = androidx.compose.ui.platform.LocalContext.current

private fun copyToClipboard(context: Context, text: String) {
    val manager = context.getSystemService(Context.CLIPBOARD_SERVICE) as? ClipboardManager ?: return
    manager.setPrimaryClip(ClipData.newPlainText("MTZ Installer diagnostics", text))
}

