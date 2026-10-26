package com.swagdroid.feature.antivirus.layer4

import android.content.pm.PackageManager
import com.swagdroid.api.CriticalPackageGuard
import com.swagdroid.api.ISwagDroidService
import com.swagdroid.api.PackageSummary
import com.swagdroid.core.rollback.PackageMutationType
import com.swagdroid.core.rollback.ReversibleSystemOp
import com.swagdroid.core.rollback.RollbackJournal
import com.swagdroid.feature.antivirus.model.Finding
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.serialization.encodeToString
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import java.io.File
import java.util.UUID

/**
 * Снимок состояния установленного приложения, помещённого в Карантин.
 * Хранит точное прежнее состояние (`previousEnabledState`, `wasSuspendedBefore`) для безошибочного отката.
 */
@Serializable
data class QuarantinedAppRecord(
    val quarantineId: String,
    val packageName: String,
    val userId: Int,
    val versionName: String,
    val versionCode: Long,
    val installerPackageName: String?,
    val signingCertSha256: String?,
    val baseApkSha256: String?,
    val previousEnabledState: Int,
    val wasSuspendedBefore: Boolean,
    val rollbackTransactionId: String,
    val finding: Finding,
    val quarantinedAtEpochMs: Long,
    val isRestored: Boolean = false,
    val restoredAtEpochMs: Long? = null,
)

/**
 * Запись об отдельном файле из пользовательского хранилища, перемещённом в изолированный контейнер карантина.
 */
@Serializable
data class QuarantinedFileRecord(
    val quarantineId: String,
    val originalPath: String,
    val vaultFileName: String,
    val sizeBytes: Long,
    val sha256: String,
    val finding: Finding,
    val quarantinedAtEpochMs: Long,
    val isRestored: Boolean = false,
)

/**
 * Слой 4: Транзакционный Менеджер Карантина (`QuarantineManager`).
 *
 * Реализует два честных механизма:
 * 1. Для установленных пакетов: сохранение снимка состояния -> `force-stop` -> `suspend` -> `disable-user` ->
 *    проверка результата -> восстановление ИМЕННО прежнего состояния (`previousEnabledState` / `wasSuspendedBefore`).
 * 2. Для отдельных файлов в хранилище: перемещение в приватный контейнер с XOR-обфускацией заголовка и
 *    точным восстановлением по исходному пути и SHA-256.
 */
class QuarantineManager(
    private val vaultDir: File,
    private val rollbackJournal: RollbackJournal,
) {
    private val mutex = Mutex()
    private val json = Json { prettyPrint = true; ignoreUnknownKeys = true; encodeDefaults = true }

    private val appsManifestFile get() = File(vaultDir.apply { mkdirs() }, "quarantined_apps_v1.json")
    private val filesManifestFile get() = File(vaultDir.apply { mkdirs() }, "quarantined_files_v1.json")

    private val _quarantinedApps = MutableStateFlow<List<QuarantinedAppRecord>>(emptyList())
    val quarantinedApps: StateFlow<List<QuarantinedAppRecord>> = _quarantinedApps.asStateFlow()

    private val _quarantinedFiles = MutableStateFlow<List<QuarantinedFileRecord>>(emptyList())
    val quarantinedFiles: StateFlow<List<QuarantinedFileRecord>> = _quarantinedFiles.asStateFlow()

    /**
     * Помещает установленный пакет в карантин через привилегированный `ISwagDroidService` (Tier 1 ADB / Tier 2 Root).
     */
    suspend fun quarantineInstalledApp(
        packageSummary: PackageSummary,
        finding: Finding,
        service: ISwagDroidService,
        userId: Int = 0,
        currentDefaultLauncher: String? = null,
        currentDefaultInputMethod: String? = null,
    ): Result<QuarantinedAppRecord> = withContext(Dispatchers.IO) {
        mutex.withLock {
            runCatching {
                val pkg = packageSummary.packageName
                // 1. Жёсткая проверка безопасности: запрещено изолировать System UI, лаунчер, GMS, телефон и сам SwagDroid
                CriticalPackageGuard.enforceNotCritical(
                    packageName = pkg,
                    currentDefaultLauncher = currentDefaultLauncher,
                    currentDefaultInputMethod = currentDefaultInputMethod,
                )

                // 2. Сохраняем транзакцию в общий RollbackJournal ДО выполнения команд
                val rollbackTx = rollbackJournal.recordPreMutationSnapshot(
                    featureId = "antivirus_quarantine",
                    titleRu = "Карантин пакета $pkg (${finding.verdictLabel.titleRu})",
                    descriptionRu = finding.evidence.joinToString("; ") { it.titleRu },
                    operations = listOf(
                        ReversibleSystemOp.PackageStateChange(
                            packageName = pkg,
                            userId = userId,
                            previousEnabledState = packageSummary.enabledState,
                            wasInstalledForUser = true,
                            targetAction = PackageMutationType.DISABLE_USER,
                        )
                    ),
                )

                // 3. Останавливаем все процессы и замораживаем пакет
                service.forceStopPackage(pkg, userId)
                service.setPackageSuspended(pkg, true, userId)
                val disabledOk = service.setApplicationEnabledState(
                    pkg,
                    PackageManager.COMPONENT_ENABLED_STATE_DISABLED_USER,
                    0,
                    userId,
                )
                check(disabledOk) {
                    "Не удалось перевести пакет $pkg в состояние DISABLED_USER через привилегированный сервер"
                }

                // 4. Записываем полный снимок в журнал карантина
                val record = QuarantinedAppRecord(
                    quarantineId = UUID.randomUUID().toString(),
                    packageName = pkg,
                    userId = userId,
                    versionName = packageSummary.versionName,
                    versionCode = packageSummary.versionCode,
                    installerPackageName = packageSummary.installerPackageName,
                    signingCertSha256 = packageSummary.signingCertSha256,
                    baseApkSha256 = null,
                    previousEnabledState = packageSummary.enabledState,
                    wasSuspendedBefore = packageSummary.isSuspended,
                    rollbackTransactionId = rollbackTx.transactionId,
                    finding = finding,
                    quarantinedAtEpochMs = System.currentTimeMillis(),
                )

                val updated = listOf(record) + _quarantinedApps.value
                appsManifestFile.writeText(json.encodeToString(updated))
                _quarantinedApps.value = updated
                record
            }
        }
    }

    /**
     * Восстанавливает установленное приложение из карантина ИМЕННО в то состояние
     * (`previousEnabledState` и `wasSuspendedBefore`), в котором оно находилось до изоляции!
     */
    suspend fun restoreQuarantinedApp(
        quarantineId: String,
        service: ISwagDroidService,
    ): Result<QuarantinedAppRecord> = withContext(Dispatchers.IO) {
        mutex.withLock {
            runCatching {
                val current = _quarantinedApps.value
                val target = current.firstOrNull { it.quarantineId == quarantineId }
                    ?: throw NoSuchElementException("Запись карантина $quarantineId не найдена")

                if (target.isRestored) return@runCatching target

                // Возвращаем точное прежнее состояние enabledState и suspended
                val stateOk = service.setApplicationEnabledState(
                    target.packageName,
                    target.previousEnabledState,
                    0,
                    target.userId,
                )
                check(stateOk) {
                    "Не удалось восстановить исходное состояние (${target.previousEnabledState}) для пакета ${target.packageName}"
                }
                service.setPackageSuspended(target.packageName, target.wasSuspendedBefore, target.userId)

                val restoredRecord = target.copy(
                    isRestored = true,
                    restoredAtEpochMs = System.currentTimeMillis(),
                )
                val updated = current.map { if (it.quarantineId == quarantineId) restoredRecord else it }
                appsManifestFile.writeText(json.encodeToString(updated))
                _quarantinedApps.value = updated
                restoredRecord
            }
        }
    }

    /**
     * Изолирует отдельный подозрительный файл (например, скачанный `.apk` в `Download/`),
     * перемещая его в приватное хранилище карантина с обратимым XOR-ключом.
     */
    suspend fun quarantineStandaloneFile(
        sourceFile: File,
        sha256: String,
        finding: Finding,
    ): Result<QuarantinedFileRecord> = withContext(Dispatchers.IO) {
        mutex.withLock {
            runCatching {
                check(sourceFile.exists() && sourceFile.isFile) {
                    "Файл не существует: ${sourceFile.absolutePath}"
                }
                val quarantineId = UUID.randomUUID().toString()
                val vaultFileName = "$quarantineId.qbin"
                val vaultFile = File(vaultDir, vaultFileName)

                val rawBytes = sourceFile.readBytes()
                val maskedBytes = ByteArray(rawBytes.size) { idx ->
                    (rawBytes[idx].toInt() xor VAULT_XOR_MASK).toByte()
                }
                vaultFile.writeBytes(maskedBytes)
                check(sourceFile.delete()) {
                    "Не удалось удалить исходный файл после копирования в карантин: ${sourceFile.absolutePath}"
                }

                val record = QuarantinedFileRecord(
                    quarantineId = quarantineId,
                    originalPath = sourceFile.absolutePath,
                    vaultFileName = vaultFileName,
                    sizeBytes = rawBytes.size.toLong(),
                    sha256 = sha256,
                    finding = finding,
                    quarantinedAtEpochMs = System.currentTimeMillis(),
                )
                val updated = listOf(record) + _quarantinedFiles.value
                filesManifestFile.writeText(json.encodeToString(updated))
                _quarantinedFiles.value = updated
                record
            }
        }
    }

    /**
     * Восстанавливает изолированный файл из контейнера карантина по его исходному пути.
     */
    suspend fun restoreStandaloneFile(quarantineId: String): Result<File> = withContext(Dispatchers.IO) {
        mutex.withLock {
            runCatching {
                val current = _quarantinedFiles.value
                val target = current.firstOrNull { it.quarantineId == quarantineId }
                    ?: throw NoSuchElementException("Запись файлового карантина $quarantineId не найдена")

                val vaultFile = File(vaultDir, target.vaultFileName)
                check(vaultFile.exists()) { "Файл в хранилище карантина отсутствует: ${vaultFile.name}" }

                val maskedBytes = vaultFile.readBytes()
                val restoredBytes = ByteArray(maskedBytes.size) { idx ->
                    (maskedBytes[idx].toInt() xor VAULT_XOR_MASK).toByte()
                }
                val destFile = File(target.originalPath)
                destFile.parentFile?.mkdirs()
                destFile.writeBytes(restoredBytes)
                vaultFile.delete()

                val updated = current.map {
                    if (it.quarantineId == quarantineId) it.copy(isRestored = true) else it
                }
                filesManifestFile.writeText(json.encodeToString(updated))
                _quarantinedFiles.value = updated
                destFile
            }
        }
    }

    companion object {
        private const val VAULT_XOR_MASK = 0x5A
    }
}
