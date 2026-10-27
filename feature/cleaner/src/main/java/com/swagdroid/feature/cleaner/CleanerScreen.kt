package com.swagdroid.feature.cleaner

import android.Manifest
import android.content.ClipData
import android.content.Intent
import android.net.Uri
import android.provider.Settings
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.Checkbox
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LifecycleEventEffect
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.swagdroid.core.designsystem.component.formatBytesRu
import com.swagdroid.core.designsystem.icon.SwagIcons
import com.swagdroid.feature.cleaner.model.CleanupBucket
import com.swagdroid.feature.cleaner.model.CleanupCandidate
import com.swagdroid.feature.cleaner.model.CleanupReceipt
import com.swagdroid.feature.cleaner.model.CleanupReview
import com.swagdroid.feature.cleaner.model.CleanupRunOutcome
import com.swagdroid.feature.cleaner.model.CleanupStep
import com.swagdroid.feature.cleaner.model.ScanLocationStatus
import com.swagdroid.feature.cleaner.model.StorageAccess

@Composable
fun CleanerRoute(
    contentPadding: PaddingValues = PaddingValues(),
    viewModel: CleanerViewModel = hiltViewModel(),
) {
    val state by viewModel.uiState.collectAsStateWithLifecycle()
    val context = LocalContext.current
    val settings = rememberLauncherForActivityResult(ActivityResultContracts.StartActivityForResult()) { viewModel.refreshAccess() }
    val legacy = rememberLauncherForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { viewModel.refreshAccess() }
    LifecycleEventEffect(Lifecycle.Event.ON_RESUME) { viewModel.refreshAccess() }
    BackHandler(enabled = state.step != CleanupStep.ACCESS || state.isScanning || state.isPreparingCleanup || state.isCleaning) { viewModel.back() }

    var coverageOpen by rememberSaveable { mutableStateOf(false) }
    var detailPath by rememberSaveable { mutableStateOf<String?>(null) }
    val candidate = state.scan?.candidates?.firstOrNull { it.file.path == detailPath }
    if (coverageOpen) CoverageDialog(state.scan?.coverage, onDismiss = { coverageOpen = false })
    candidate?.let {
        CandidateDetailsDialog(it, onDismiss = { detailPath = null }) { file ->
            viewModel.previewFile(file) { preview ->
                try {
                    val uri = Uri.parse(preview.uri)
                    val intent = Intent(Intent.ACTION_VIEW).setDataAndType(uri, preview.mimeType)
                        .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                        .apply { clipData = ClipData.newUri(context.contentResolver, "Выбранный файл", uri) }
                    context.startActivity(Intent.createChooser(intent, "Открыть выбранный файл"))
                } catch (_: Exception) { viewModel.showMessage("Нет доступного приложения для просмотра этого файла") }
            }
        }
    }

    val requestAccess = {
        when (state.access) {
            StorageAccess.NEEDS_ALL_FILES -> {
                try {
                    settings.launch(Intent(Settings.ACTION_MANAGE_APP_ALL_FILES_ACCESS_PERMISSION, Uri.parse("package:${context.packageName}")))
                } catch (_: Exception) {
                    try { settings.launch(Intent(Settings.ACTION_MANAGE_ALL_FILES_ACCESS_PERMISSION)) }
                    catch (_: Exception) { viewModel.showMessage("Откройте настройки Android → Приложения → Специальный доступ → Все файлы → SwagDroid") }
                }
            }
            StorageAccess.NEEDS_LEGACY_PERMISSIONS -> legacy.launch(arrayOf(Manifest.permission.READ_EXTERNAL_STORAGE, Manifest.permission.WRITE_EXTERNAL_STORAGE))
            else -> viewModel.refreshAccess()
        }
    }
    val plan = (state.cleanupReview as? CleanupReview.Awaiting)?.plan
    var permanentConsent by rememberSaveable(plan?.id) { mutableStateOf(false) }

    Scaffold(
        modifier = Modifier.fillMaxSize().padding(contentPadding),
        contentWindowInsets = WindowInsets(0, 0, 0, 0),
        containerColor = MaterialTheme.colorScheme.background,
        topBar = {
            Column(Modifier.fillMaxWidth().padding(horizontal = 20.dp, vertical = 12.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f)) {
                        Text("Освободить место", style = MaterialTheme.typography.headlineSmall.copy(fontWeight = FontWeight.Bold))
                        Text("Локально · Root не нужен", style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                    if (state.step != CleanupStep.ACCESS && !state.isCleaning) TextButton(onClick = viewModel::back) { Text("Назад") }
                }
                if (state.step != CleanupStep.RESULT) {
                    val active = when {
                        state.isPreparingCleanup -> 2
                        state.step == CleanupStep.ACCESS -> 0
                        state.step == CleanupStep.CHOOSE -> 1
                        else -> 2
                    }
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                        listOf("Доступ", "Выбор", "Проверка").forEachIndexed { index, title ->
                            Surface(
                                modifier = Modifier.weight(1f), shape = RoundedCornerShape(10.dp),
                                color = if (active == index) MaterialTheme.colorScheme.primaryContainer else MaterialTheme.colorScheme.surfaceVariant,
                                contentColor = if (active == index) MaterialTheme.colorScheme.onPrimaryContainer else MaterialTheme.colorScheme.onSurfaceVariant,
                            ) { Text("${index + 1} · $title", Modifier.padding(horizontal = 10.dp, vertical = 8.dp), style = MaterialTheme.typography.labelMedium) }
                        }
                    }
                }
            }
        },
        bottomBar = {
            Surface(shadowElevation = 5.dp) {
                Column(Modifier.fillMaxWidth().padding(horizontal = 20.dp, vertical = 12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    when {
                        state.isRestoringReceipt -> Text("Читаем последний итог…", style = MaterialTheme.typography.bodySmall)
                        state.isScanning -> OutlinedButton(onClick = viewModel::cancelScan, modifier = Modifier.fillMaxWidth()) { Text("Остановить поиск") }
                        state.isPreparingCleanup -> OutlinedButton(onClick = viewModel::cancelCleanupPreparation, modifier = Modifier.fillMaxWidth()) { Text("Отменить проверку выбора") }
                        state.isCleaning -> {
                            Text("Остановка не вернёт уже удалённое.", style = MaterialTheme.typography.bodySmall)
                            OutlinedButton(onClick = viewModel::stopCleanup, modifier = Modifier.fillMaxWidth()) { Text("Остановить дальнейшее удаление") }
                        }
                        state.step == CleanupStep.ACCESS -> {
                            if (!state.access.canScan) {
                                Button(onClick = requestAccess, modifier = Modifier.fillMaxWidth().testTag("cleaner_access")) { Text("Разрешить доступ к файлам") }
                            } else {
                                Button(
                                    onClick = if (state.scan?.candidates?.isNotEmpty() == true) viewModel::resumeSelection else viewModel::runScan,
                                    modifier = Modifier.fillMaxWidth().testTag("cleaner_scan"),
                                ) { Text(if (state.scan?.candidates?.isNotEmpty() == true) "Продолжить выбор" else "Найти файлы") }
                                if (state.scan != null) TextButton(onClick = viewModel::runScan, modifier = Modifier.fillMaxWidth()) { Text("Начать новый поиск") }
                            }
                        }
                        state.step == CleanupStep.CHOOSE -> {
                            Text("Выбрано во всём списке: ${state.selected.size} · ${formatBytesRu(state.selectedBytes)}", style = MaterialTheme.typography.labelLarge)
                            Button(onClick = viewModel::requestCleanupReview, enabled = state.selected.isNotEmpty() && state.access.canScan,
                                modifier = Modifier.fillMaxWidth().testTag("cleaner_review")) { Text("Проверить выбор") }
                        }
                        state.step == CleanupStep.REVIEW && plan != null -> {
                            Row(
                                Modifier.fillMaxWidth().testTag("cleanup_ack").toggleable(
                                    value = permanentConsent, enabled = plan.targets.isNotEmpty(), role = Role.Checkbox,
                                    onValueChange = { permanentConsent = it },
                                ), verticalAlignment = Alignment.CenterVertically,
                            ) {
                                Checkbox(permanentConsent, onCheckedChange = null, enabled = plan.targets.isNotEmpty())
                                Text("Я проверил список. Понимаю, что корзины и восстановления в SwagDroid нет.", style = MaterialTheme.typography.bodySmall)
                            }
                            Button(
                                onClick = { viewModel.confirmCleanup(plan.id, permanentConsent) },
                                enabled = permanentConsent && plan.targets.isNotEmpty() && state.access.canScan,
                                modifier = Modifier.fillMaxWidth().testTag("cleanup_confirm"),
                            ) { Text("Удалить ${plan.targets.size} объектов без корзины") }
                            TextButton(onClick = viewModel::editSelection, modifier = Modifier.fillMaxWidth().testTag("cleanup_cancel")) { Text("Изменить выбор / отменить") }
                        }
                        state.step == CleanupStep.RESULT -> {
                            Button(onClick = if (state.access.canScan) viewModel::runScan else viewModel::finish, modifier = Modifier.fillMaxWidth().testTag("cleanup_new_scan")) {
                                Text(if (state.access.canScan) "Проверить файлы заново" else "К настройке доступа")
                            }
                            TextButton(onClick = viewModel::finish, modifier = Modifier.fillMaxWidth()) { Text("Готово") }
                        }
                    }
                }
            }
        },
    ) { padding ->
        key(state.step, state.scan?.id, plan?.id) {
            LazyColumn(
                modifier = Modifier.fillMaxSize().padding(padding).testTag(if (state.step == CleanupStep.REVIEW) "cleanup_plan_list" else "cleaner_list"),
                contentPadding = PaddingValues(start = 20.dp, end = 20.dp, top = 6.dp, bottom = 20.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                state.bannerMessage?.let { message ->
                    item {
                        InfoPanel(message, warning = true)
                        TextButton(onClick = viewModel::dismissBanner) { Text("Понятно") }
                    }
                }
                when {
                    state.isRestoringReceipt -> item { LinearProgressIndicator(Modifier.fillMaxWidth()) }
                    state.isScanning -> item {
                        FlowCard {
                            Icon(SwagIcons.Search, contentDescription = null, tint = MaterialTheme.colorScheme.primary, modifier = Modifier.size(36.dp))
                            Text("Ищем в доступных папках", style = MaterialTheme.typography.titleLarge)
                            Text(state.progress.location)
                            LinearProgressIndicator(Modifier.fillMaxWidth())
                            Text("Просмотрено файлов: ${state.progress.inspectedFiles}. Ничего не удаляется.", style = MaterialTheme.typography.bodyMedium)
                            Text("Ограничения поиска будут показаны вместе с результатами.", style = MaterialTheme.typography.bodySmall)
                        }
                    }
                    state.isPreparingCleanup -> item {
                        FlowCard {
                            Text("Проверяем выбранные файлы", style = MaterialTheme.typography.titleLarge)
                            LinearProgressIndicator(Modifier.fillMaxWidth())
                            Text("Читаем содержимое и сохраняемые оригиналы. Большие файлы проверяются дольше. Удаление ещё не началось.")
                        }
                    }
                    state.isCleaning -> item {
                        FlowCard {
                            Text("Удаляем только подтверждённый список", style = MaterialTheme.typography.titleLarge)
                            LinearProgressIndicator(Modifier.fillMaxWidth())
                            Text("Новые и изменившиеся файлы не добавляются к удалению. Если приложение прервётся, точный результат может быть неизвестен.")
                        }
                    }
                    state.step == CleanupStep.ACCESS -> {
                        item {
                            FlowCard(emphasized = true) {
                                Icon(SwagIcons.Storage, contentDescription = null, modifier = Modifier.size(40.dp))
                                Text("Место — под вашим контролем", style = MaterialTheme.typography.titleLarge.copy(fontWeight = FontWeight.Bold))
                                state.space?.let {
                                    Text("Свободно ${formatBytesRu(it.availableBytes)}", style = MaterialTheme.typography.headlineSmall)
                                    Text("из ${formatBytesRu(it.totalBytes)} · данные Android сейчас", style = MaterialTheme.typography.bodySmall)
                                    LinearProgressIndicator(progress = { (1f - it.availableBytes.toFloat() / it.totalBytes).coerceIn(0f, 1f) }, modifier = Modifier.fillMaxWidth())
                                }
                                Text("Найдём доступные файлы. Вы сами выберете, что больше не нужно — ничего не отмечается автоматически.")
                            }
                        }
                        item {
                            FlowCard {
                                Text(if (state.access.canScan) "Доступ к общим файлам выдан" else "Сначала — доступ к файлам", style = MaterialTheme.typography.titleMedium)
                                Text(if (state.access.canScan) "Можно начать поиск. Разрешение не открывает все чужие каталоги." else "Android откроет настройки доступа. Разрешение нужно, чтобы показать общие файлы и удалить только ваш подтверждённый выбор.")
                                if (state.access == StorageAccess.LEGACY_GRANTED) Text("На Android 9–10 действуют ограничения вашей версии системы; выдача разрешения не означает доступ ко всем папкам.", style = MaterialTheme.typography.bodySmall)
                                TextButton(onClick = { coverageOpen = true }) { Text("Какие папки проверяем") }
                            }
                        }
                        item { InfoPanel("Корзины пока нет. Удаление необратимо через SwagDroid — сначала сохраните нужное в другом месте.", warning = true) }
                        state.lastReceipt?.let { receipt ->
                            item {
                                FlowCard {
                                    Text("Последняя очистка", style = MaterialTheme.typography.titleSmall)
                                    Text(cleanupDate(receipt.startedAtMs), style = MaterialTheme.typography.bodySmall)
                                    Text(if (receipt.status == CleanupReceipt.Status.COMPLETE) "Удалено файлов: ${receipt.deletedFiles}; пропущено: ${receipt.skipped}; ошибок: ${receipt.failed}." else "Завершение не подтверждено. Часть файлов могла быть удалена.")
                                    TextButton(onClick = viewModel::showLastResult) { Text("Посмотреть итог") }
                                }
                            }
                        }
                    }
                    state.step == CleanupStep.CHOOSE -> {
                        val scan = state.scan
                        if (scan != null) {
                            item {
                                FlowCard(emphasized = true) {
                                    Text(if (scan.candidates.isEmpty()) "Кандидатов не найдено" else "Выберите, что больше не нужно", style = MaterialTheme.typography.titleLarge, modifier = Modifier.testTag("cleanup_results"))
                                    Text("${scan.candidates.size} объектов · ${formatBytesRu(scan.fileBytes)}", style = MaterialTheme.typography.headlineSmall.copy(fontWeight = FontWeight.Bold))
                                    Text("Размер найденных файлов, не обещание освободить столько места. Список не означает, что файлы лишние.", style = MaterialTheme.typography.bodySmall)
                                    Text("Поиск: ${cleanupDate(scan.scannedAtMs)}", style = MaterialTheme.typography.labelSmall)
                                }
                            }
                            item {
                                val unavailable = scan.coverage.locations.count { it.status in setOf(ScanLocationStatus.UNAVAILABLE, ScanLocationStatus.NOT_REACHED, ScanLocationStatus.PARTIAL) }
                                InfoPanel(if (scan.coverage.isPartial) {
                                    if (unavailable > 0) "Не полностью проверено папок: $unavailable. Сравнение точных копий также ограничено."
                                    else "Папки просмотрены в доступных пределах, но сравнение содержимого файлов ограничено."
                                } else "Проверены доступные выбранные папки, не весь телефон. Внутренние данные приложений, Android/data, SD-карты и облако не включены.", warning = scan.coverage.isPartial)
                                TextButton(onClick = { coverageOpen = true }, modifier = Modifier.testTag("cleaner_coverage")) { Text("Что проверено и пропущено") }
                            }
                            item {
                                OutlinedTextField(
                                    value = state.query, onValueChange = viewModel::setQuery,
                                    modifier = Modifier.fillMaxWidth().testTag("cleanup_search"), singleLine = true,
                                    label = { Text("Имя или путь файла") }, leadingIcon = { Icon(SwagIcons.Search, contentDescription = null) },
                                )
                                Row(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                                    FilterChip(selected = state.bucket == null, onClick = { viewModel.setBucket(null) }, label = { Text("Все") })
                                    CleanupBucket.entries.forEach { bucket ->
                                        val count = scan.candidates.count { it.bucket == bucket }
                                        if (count > 0) FilterChip(selected = state.bucket == bucket, onClick = { viewModel.setBucket(bucket) }, label = { Text("${bucket.title} · $count") })
                                    }
                                }
                                state.bucket?.let { Text(it.explanation, style = MaterialTheme.typography.bodySmall, modifier = Modifier.padding(vertical = 8.dp)) }
                                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                                    if (state.bucket in setOf(CleanupBucket.TEMPORARY, CleanupBucket.EMPTY_DIRECTORIES) && state.visibleCandidates.isNotEmpty()) {
                                        TextButton(onClick = viewModel::selectFilteredFiles) { Text("Выбрать эти ${state.visibleCandidates.size}") }
                                    }
                                    if (state.selectedPaths.isNotEmpty()) TextButton(onClick = viewModel::clearSelection) { Text("Снять весь выбор") }
                                }
                            }
                            if (state.visibleCandidates.isEmpty()) item {
                                Text(if (scan.candidates.isEmpty()) "В доступной проверенной части кандидатов нет. Это не доказательство, что всё хранилище пусто или очищено." else "В этом фильтре файлов нет. Выбор в других разделах сохранён.")
                            }
                            items(state.visibleCandidates, key = { it.file.path }) { file ->
                                CandidateRow(file, file.file.path in state.selectedPaths, onToggle = { viewModel.toggleFile(file.file.path) }, onDetails = { detailPath = file.file.path })
                            }
                            item { TextButton(onClick = viewModel::runScan) { Text("Повторить поиск и сбросить выбор") } }
                        }
                    }
                    state.step == CleanupStep.REVIEW && plan != null -> {
                        item {
                            FlowCard(emphasized = true) {
                                Text("Проверьте окончательный список", style = MaterialTheme.typography.titleLarge)
                                Text("${plan.fileCount} файлов · ${plan.directoryCount} пустых папок", style = MaterialTheme.typography.titleMedium)
                                Text(formatBytesRu(plan.fileBytes), style = MaterialTheme.typography.headlineMedium.copy(fontWeight = FontWeight.Bold))
                                Text("Выбор из всех фильтров. Каждый путь учитывается один раз.", style = MaterialTheme.typography.bodySmall)
                            }
                        }
                        item { InfoPanel("Удаление без корзины. Восстановить файлы через SwagDroid невозможно. Если сомневаетесь — вернитесь к выбору.", warning = true) }
                        items(plan.targets, key = { it.snapshot.path }) { target ->
                            FlowCard {
                                Text(java.io.File(target.snapshot.path).name, style = MaterialTheme.typography.titleSmall)
                                Text(target.snapshot.path, style = MaterialTheme.typography.bodySmall.copy(fontFamily = FontFamily.Monospace))
                                Text(if (target.snapshot.isEmptyDirectory) "Только пустая папка" else formatBytesRu(target.snapshot.bytes), style = MaterialTheme.typography.labelMedium)
                                target.requiredOriginals.forEach { Text("Оригинал остаётся: ${it.path}", style = MaterialTheme.typography.bodySmall) }
                            }
                        }
                        if (plan.exclusions.isNotEmpty()) {
                            item { Text("Не включено в удаление: ${plan.exclusions.size}", style = MaterialTheme.typography.titleMedium) }
                            items(plan.exclusions) { InfoPanel("${it.path}\n${it.reason}", warning = true) }
                        }
                        if (plan.targets.isEmpty()) item { Text("Подтверждённых объектов нет. Вернитесь к выбору или выполните новый поиск.") }
                    }
                    state.step == CleanupStep.RESULT -> {
                        val completed = state.outcome as? CleanupRunOutcome.Completed
                        val receipt = completed?.receipt ?: state.lastReceipt
                        val interrupted = state.outcome as? CleanupRunOutcome.Interrupted
                        val notStarted = state.outcome as? CleanupRunOutcome.NotStarted
                        when {
                            notStarted != null -> item { InfoPanel(notStarted.reason, warning = true) }
                            interrupted != null || receipt?.status != CleanupReceipt.Status.COMPLETE -> item {
                                FlowCard {
                                    Icon(SwagIcons.Warning, contentDescription = null, tint = MaterialTheme.colorScheme.error, modifier = Modifier.size(36.dp))
                                    Text("Завершение не подтверждено", style = MaterialTheme.typography.headlineSmall)
                                    Text(interrupted?.reason ?: "Последняя операция прервалась или её итог не удалось сохранить. Часть файлов могла быть удалена; точные числа неизвестны.")
                                    Text("Удаление не продолжается автоматически. Проверьте доступные файлы заново.")
                                }
                            }
                            else -> item {
                                val problems = (receipt.skipped ?: 0) + (receipt.failed ?: 0)
                                FlowCard(emphasized = problems == 0) {
                                    Text(if (problems == 0) "Выбранный список обработан" else "Есть пропуски или ошибки", style = MaterialTheme.typography.headlineSmall)
                                    Text("Удалено файлов: ${receipt.deletedFiles}", style = MaterialTheme.typography.titleLarge, modifier = Modifier.testTag("cleanup_result_files"))
                                    Text("Пустых папок: ${receipt.deletedDirectories}")
                                    Text("Размер удалённых файлов: ${formatBytesRu(receipt.deletedBytes ?: 0)}")
                                    Text("Это сумма размеров файлов, не замер освободившегося места.", style = MaterialTheme.typography.bodySmall)
                                    Text("Пропущено: ${receipt.skipped} · Ошибок: ${receipt.failed}", modifier = Modifier.testTag("cleanup_result_problems"))
                                    state.space?.let { Text("Сейчас свободно: ${formatBytesRu(it.availableBytes)}", style = MaterialTheme.typography.labelLarge) }
                                    if (completed == null) Text("${if (state.lastReceiptPersisted) "Сохранённый итог" else "Итог только в памяти этого сеанса"} ${cleanupDate(receipt.startedAtMs)}. Подробные пути доступны только для текущего запуска.", style = MaterialTheme.typography.bodySmall)
                                }
                            }
                        }
                        if (completed != null) {
                            if (!completed.receiptSaved) item { InfoPanel("Файловый итог получен, но не удалось сохранить его для следующего запуска. Не считайте это подтверждением записанной истории.", warning = true) }
                            items(completed.result.skipped) { InfoPanel("Не удалён: ${it.path}\n${it.reason}", warning = true) }
                            items(completed.result.failed) { InfoPanel("Ошибка удаления: ${it.path}\n${it.reason}", warning = true) }
                        }
                        item { Text("Корзины и отката файлов нет. Для актуального списка нужен новый поиск.", style = MaterialTheme.typography.bodySmall) }
                    }
                }
            }
        }
    }
}

@Composable
private fun CandidateRow(candidate: CleanupCandidate, selected: Boolean, onToggle: () -> Unit, onDetails: () -> Unit) {
    Surface(shape = RoundedCornerShape(16.dp), color = MaterialTheme.colorScheme.surface,
        border = BorderStroke(1.dp, if (selected) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.outlineVariant)) {
        Column(Modifier.padding(end = 8.dp, bottom = 10.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Row(
                    modifier = Modifier.weight(1f).testTag("cleanup_select:${candidate.file.path}").toggleable(
                        value = selected, role = Role.Checkbox, onValueChange = { onToggle() },
                    ).padding(vertical = 8.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Checkbox(checked = selected, onCheckedChange = null)
                    Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                        Text(candidate.name, style = MaterialTheme.typography.titleSmall, maxLines = 2, overflow = TextOverflow.Ellipsis)
                        Text(if (candidate.file.isEmptyDirectory) "Пустая папка" else "${formatBytesRu(candidate.file.bytes)} · ${candidate.bucket.title}", style = MaterialTheme.typography.labelMedium)
                    }
                }
                TextButton(onClick = onDetails, modifier = Modifier.testTag("cleanup_details:${candidate.file.path}")) { Text("О файле") }
            }
            Text(candidate.parentPath, modifier = Modifier.padding(start = 48.dp, end = 8.dp), style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 2, overflow = TextOverflow.Ellipsis)
            candidate.keptOriginal?.let {
                Text("Сохраняется: ${java.io.File(it.path).name}", Modifier.padding(start = 48.dp, end = 8.dp, top = 5.dp), style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.primary)
            }
        }
    }
}

@Composable
private fun FlowCard(emphasized: Boolean = false, content: @Composable androidx.compose.foundation.layout.ColumnScope.() -> Unit) {
    Surface(
        shape = RoundedCornerShape(20.dp),
        color = if (emphasized) MaterialTheme.colorScheme.primaryContainer else MaterialTheme.colorScheme.surface,
        contentColor = if (emphasized) MaterialTheme.colorScheme.onPrimaryContainer else MaterialTheme.colorScheme.onSurface,
        border = if (emphasized) null else BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant),
    ) { Column(Modifier.fillMaxWidth().padding(18.dp), verticalArrangement = Arrangement.spacedBy(10.dp), content = content) }
}

@Composable
private fun InfoPanel(text: String, warning: Boolean = false) {
    Surface(
        shape = RoundedCornerShape(14.dp),
        color = if (warning) MaterialTheme.colorScheme.errorContainer else MaterialTheme.colorScheme.surfaceVariant,
        contentColor = if (warning) MaterialTheme.colorScheme.onErrorContainer else MaterialTheme.colorScheme.onSurfaceVariant,
    ) { Text(text, Modifier.fillMaxWidth().padding(14.dp), style = MaterialTheme.typography.bodySmall) }
}
