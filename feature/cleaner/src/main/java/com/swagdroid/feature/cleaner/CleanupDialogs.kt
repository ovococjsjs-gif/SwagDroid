package com.swagdroid.feature.cleaner

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.selection.toggleable
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Checkbox
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import com.swagdroid.core.designsystem.component.formatBytesRu
import com.swagdroid.feature.cleaner.model.CleanupExecutionResult
import com.swagdroid.feature.cleaner.model.PreparedCleanupPlan

@Composable
internal fun CleanupReviewDialog(
    plan: PreparedCleanupPlan,
    onDismiss: () -> Unit,
    onConfirm: (String, Boolean) -> Unit,
) {
    var accepted by rememberSaveable(plan.id) { mutableStateOf(false) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("План безвозвратного удаления") },
        text = {
            LazyColumn(
                modifier = Modifier.fillMaxWidth().heightIn(max = 420.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                item {
                    Text(
                        "Выбор из ВСЕХ разделов: ${plan.fileCount} файлов и ${plan.directoryCount} пустых папок. " +
                            "Размер файлов: ${formatBytesRu(plan.fileBytes)} (без повторного учёта).",
                    )
                }
                item {
                    Text(
                        "Корзины и отката нет. В списке могут быть сохранённые фото, видео и другие нужные файлы. " +
                            "Наличие резервной копии не проверено.",
                        color = MaterialTheme.colorScheme.error,
                    )
                }
                item {
                    Text(
                        plan.sources.joinToString("\n") { "${it.tab.titleRu} · ${it.title}" }.ifEmpty { "Удалять нечего" },
                        style = MaterialTheme.typography.labelMedium,
                    )
                }
                items(plan.targets, key = { it.snapshot.path }) { target ->
                    Column {
                        Text(target.snapshot.path, style = MaterialTheme.typography.bodySmall.copy(fontFamily = FontFamily.Monospace))
                        Text(
                            if (target.snapshot.isEmptyDirectory) "Пустая папка" else formatBytesRu(target.snapshot.bytes),
                            style = MaterialTheme.typography.labelSmall,
                        )
                        target.requiredOriginals.forEach {
                            Text("Сохраняется оригинал: ${it.path}", style = MaterialTheme.typography.labelSmall)
                        }
                    }
                }
                if (plan.exclusions.isNotEmpty()) {
                    item { Text("Исключено из плана: ${plan.exclusions.size}") }
                    items(plan.exclusions) {
                        Text("${it.path}\n${it.reason}", style = MaterialTheme.typography.bodySmall)
                    }
                }
                item {
                    Row(
                        modifier = Modifier.fillMaxWidth().toggleable(
                            value = accepted,
                            enabled = plan.targets.isNotEmpty(),
                            role = Role.Checkbox,
                            onValueChange = { accepted = it },
                        ),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Checkbox(checked = accepted, enabled = plan.targets.isNotEmpty(), onCheckedChange = null)
                        Text(
                            "Я проверил список и понимаю, что восстановление через SwagDroid невозможно.",
                            style = MaterialTheme.typography.bodySmall,
                        )
                    }
                }
            }
        },
        confirmButton = {
            Button(onClick = { onConfirm(plan.id, accepted) }, enabled = accepted && plan.targets.isNotEmpty()) {
                Text("Удалить безвозвратно")
            }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Отмена") } },
    )
}

@Composable
internal fun CleanupResultDialog(result: CleanupExecutionResult, onDismiss: () -> Unit) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Результат очистки") },
        text = {
            LazyColumn(
                modifier = Modifier.fillMaxWidth().heightIn(max = 400.dp),
                verticalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                item {
                    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        Text("Удалено файлов: ${result.deletedFiles}. Пустых папок: ${result.deletedEmptyDirectories}.")
                        Text("Размер удалённых файлов: ${formatBytesRu(result.deletedFileBytes)}. Это не замер свободного места.")
                        Text("Пропущено: ${result.skipped.size}. Ошибок удаления: ${result.failed.size}.")
                    }
                }
                items(result.skipped) { Text("Не удалён: ${it.path}\n${it.reason}", style = MaterialTheme.typography.bodySmall) }
                items(result.failed) { Text("Ошибка: ${it.path}\n${it.reason}", style = MaterialTheme.typography.bodySmall) }
            }
        },
        confirmButton = { TextButton(onClick = onDismiss) { Text("Понятно") } },
    )
}
