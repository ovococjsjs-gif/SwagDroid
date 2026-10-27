package com.swagdroid.feature.cleaner

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import com.swagdroid.core.designsystem.component.formatBytesRu
import com.swagdroid.feature.cleaner.model.CleanupCandidate
import com.swagdroid.feature.cleaner.model.CleanupFileSnapshot
import com.swagdroid.feature.cleaner.model.ScanCoverage
import com.swagdroid.feature.cleaner.model.ScanLocationStatus
import java.text.DateFormat
import java.util.Date

private fun supportsPreview(file: CleanupFileSnapshot): Boolean = !file.isEmptyDirectory && java.io.File(file.path).extension.lowercase() in setOf(
    "jpg", "jpeg", "png", "webp", "heic", "heif", "gif", "mp4", "mkv", "webm", "3gp", "mp3", "aac", "m4a", "wav", "ogg", "opus", "amr", "pdf", "txt", "csv", "json", "log",
)

internal fun cleanupDate(timeMs: Long): String = DateFormat.getDateTimeInstance(DateFormat.MEDIUM, DateFormat.SHORT).format(Date(timeMs))

@Composable
internal fun CoverageDialog(coverage: ScanCoverage?, onDismiss: () -> Unit) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Что удалось проверить") },
        text = {
            LazyColumn(Modifier.fillMaxWidth().heightIn(max = 440.dp), verticalArrangement = Arrangement.spacedBy(14.dp)) {
                item {
                    Text("Только основное общее хранилище. Данные не отправляются в облако.")
                    Text("Не включены Android/data, внутренние данные приложений, SD-карты, облако и другие профили. Root и Shizuku не расширяют доступ этого файлового сценария.", style = MaterialTheme.typography.bodySmall)
                }
                if (coverage == null) {
                    item { Text("Будут проверены Загрузки, Документы, Камера, Изображения, Видео, Музыка, Android/media и известные общие папки мессенджеров.") }
                } else {
                    items(coverage.locations) { location ->
                        Column(verticalArrangement = Arrangement.spacedBy(3.dp)) {
                            Text(location.title, style = MaterialTheme.typography.titleSmall)
                            Text(location.relativePath, style = MaterialTheme.typography.bodySmall.copy(fontFamily = FontFamily.Monospace))
                            Text(when (location.status) {
                                ScanLocationStatus.CHECKED -> "Просмотрено файлов: ${location.inspectedFiles}"
                                ScanLocationStatus.PARTIAL -> "Проверена только часть · файлов: ${location.inspectedFiles}"
                                ScanLocationStatus.UNAVAILABLE -> "Не удалось прочитать — это не пустая папка"
                                ScanLocationStatus.ABSENT -> "Папка не найдена"
                                ScanLocationStatus.NOT_REACHED -> "Не проверена: сработал лимит поиска"
                            }, style = MaterialTheme.typography.bodySmall)
                            location.notes.forEach { Text(it, style = MaterialTheme.typography.bodySmall) }
                        }
                    }
                    item {
                        Text("Исключено фильтром путей/ссылок: ${coverage.protectedEntries} объектов или папок.", style = MaterialTheme.typography.bodySmall)
                        Text("По содержимому сравнили: ${coverage.duplicateFilesCompared}. Не сравнили в группах возможных копий: ${coverage.duplicateFilesNotCompared}.", style = MaterialTheme.typography.bodySmall)
                    }
                    items(coverage.limits) { Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error) }
                }
                item { Text("Фильтры известных важных данных — дополнительная защита, а не гарантия. Похожие фотографии не считаются точными копиями.", style = MaterialTheme.typography.bodySmall) }
            }
        },
        confirmButton = { TextButton(onClick = onDismiss) { Text("Понятно") } },
    )
}

@Composable
internal fun CandidateDetailsDialog(candidate: CleanupCandidate, onDismiss: () -> Unit, onOpen: (CleanupFileSnapshot) -> Unit) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(candidate.name) },
        text = {
            LazyColumn(Modifier.fillMaxWidth().heightIn(max = 420.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                item { Text(candidate.file.path, style = MaterialTheme.typography.bodySmall.copy(fontFamily = FontFamily.Monospace)) }
                item {
                    Text(if (candidate.file.isEmptyDirectory) "Пустая папка" else "Размер: ${formatBytesRu(candidate.file.bytes)}")
                    Text("Изменён: ${cleanupDate(candidate.file.modifiedAtMs)}", style = MaterialTheme.typography.bodySmall)
                    Text("Раздел: ${candidate.locationTitle}", style = MaterialTheme.typography.bodySmall)
                }
                item { Text(candidate.bucket.explanation) }
                candidate.keptOriginal?.let { original ->
                    item {
                        Text("Сохраняемый оригинал", style = MaterialTheme.typography.titleSmall)
                        Text(original.path, style = MaterialTheme.typography.bodySmall.copy(fontFamily = FontFamily.Monospace))
                        if (supportsPreview(original)) TextButton(onClick = { onOpen(original) }) { Text("Открыть оригинал") }
                    }
                }
                item {
                    Text("Открытие передаёт выбранный файл другому приложению с временным правом чтения. APK из этого экрана не запускаются.", style = MaterialTheme.typography.bodySmall)
                }
            }
        },
        confirmButton = {
            if (supportsPreview(candidate.file)) TextButton(onClick = { onOpen(candidate.file) }) { Text("Открыть файл") }
            else TextButton(onClick = onDismiss) { Text("Понятно") }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Закрыть") } },
    )
}
