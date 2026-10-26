package com.swagdroid.feature.cleaner.corpse

import com.swagdroid.api.PrivilegeTier
import com.swagdroid.feature.cleaner.model.CleanerActionType
import com.swagdroid.feature.cleaner.model.OwnershipConfidence
import com.swagdroid.feature.cleaner.model.StorageCategory
import com.swagdroid.feature.cleaner.model.StorageFinding
import com.swagdroid.feature.cleaner.path.StoragePathCanonicalizer
import kotlinx.serialization.Serializable
import java.io.File
import java.util.UUID

/**
 * Снимок инвентаря установленных пакетов по ВСЕМ пользователям и профилям устройства
 * (основной профиль `0`, рабочий профиль `10`, клонированные приложения `999`).
 */
@Serializable
data class MultiUserPackageInventory(
    val inspectedUserIds: Set<Int>,
    val installedPackagesAcrossAllUsers: Set<String>,
    val isCompleteAndReliable: Boolean,
)

/**
 * Инвентарь публичных папок (`UninstallInventoryWatcher`), который запоминает привязку
 * папок к установленным пакетам, чтобы после `PACKAGE_FULLY_REMOVED` отличать сироту от папки пользователя.
 */
class UninstallInventoryWatcher {
    private val knownFolderOwners = HashMap<String, String>() // canonicalPath -> packageName

    fun recordKnownAppFolder(canonicalPath: String, ownerPackage: String) {
        knownFolderOwners[StoragePathCanonicalizer.canonicalize(canonicalPath)] = ownerPackage
    }

    fun getRecordedOwner(canonicalPath: String): String? =
        knownFolderOwners[StoragePathCanonicalizer.canonicalize(canonicalPath)]
}

/**
 * Охотник за «трупами» удалённых приложений (`CorpseFinderEngine`).
 *
 * Ключевые предохранители:
 * 1. Если список установленных пакетов неполный (`!inventory.isCompleteAndReliable`) — CorpseFinder
 *    немедленно останавливается и НЕ гадает!
 * 2. Пакет считается удалённым только если он отсутствует у ВСЕХ пользователей/профилей (`installedPackagesAcrossAllUsers`).
 * 3. Папки с общими пользовательскими именами (`Backup`, `Notes`, `Saves`, `DCIM`, `Documents`, `Export`)
 *    жёстко защищены статусом `LOW_KEEP` и действием `REPORT_ONLY_DO_NOT_TOUCH`.
 */
class CorpseFinderEngine(
    private val uninstallWatcher: UninstallInventoryWatcher = UninstallInventoryWatcher(),
) {
    /**
     * Анализирует список директорий из `Android/data`, `Android/obb`, `Android/media`, корня хранилища
     * и (при наличии Root) `/data/user/<id>`.
     */
    fun scanForCorpses(
        candidateDirectories: List<File>,
        inventory: MultiUserPackageInventory,
        currentTier: PrivilegeTier,
    ): List<StorageFinding> {
        // Главный предохранитель: если нет полной видимости установленных пакетов по профилям — не гадаем!
        if (!inventory.isCompleteAndReliable || inventory.installedPackagesAcrossAllUsers.isEmpty()) {
            return emptyList()
        }

        val findings = mutableListOf<StorageFinding>()

        for (dir in candidateDirectories) {
            val canonical = StoragePathCanonicalizer.canonicalize(dir.path)
            val folderName = dir.name

            // 1. Проверяем высокоуверенные пути Android/data/<pkg>, Android/media/<pkg>, Android/obb/<pkg>
            val appSpecificMatch = APP_SPECIFIC_PATH_REGEX.matchEntire(canonical)
            if (appSpecificMatch != null) {
                val pkg = appSpecificMatch.groupValues[2]
                if (pkg !in inventory.installedPackagesAcrossAllUsers && looksLikeValidPackageName(pkg)) {
                    val sizeBytes = calculateDirectoryBytes(dir)
                    findings += StorageFinding(
                        findingId = UUID.randomUUID().toString(),
                        canonicalPath = canonical,
                        ownerPackage = pkg,
                        category = StorageCategory.CORPSE_APP_SPECIFIC,
                        bytesLogical = sizeBytes,
                        bytesAllocated = sizeBytes,
                        confidence = OwnershipConfidence.HIGH,
                        evidenceRule = "APP_SPECIFIC_ORPHAN_ALL_USERS",
                        explanationRu = "Каталог принадлежит пакету '$pkg', который отсутствует у всех пользователей (${inventory.inspectedUserIds.joinToString()}).",
                        minPrivilege = PrivilegeTier.ADB_SHELL,
                        action = CleanerActionType.DELETE_BY_VERIFIED_RULE,
                        reversible = false,
                    )
                }
                continue
            }

            // 2. Проверяем приватные Root-каталоги /data/user/<userId>/<pkg> или /data/user_de/<userId>/<pkg>
            val rootPrivateMatch = ROOT_DATA_USER_REGEX.matchEntire(canonical)
            if (rootPrivateMatch != null && currentTier == PrivilegeTier.ROOT) {
                val pkg = rootPrivateMatch.groupValues[3]
                if (pkg !in inventory.installedPackagesAcrossAllUsers && looksLikeValidPackageName(pkg)) {
                    val sizeBytes = calculateDirectoryBytes(dir)
                    findings += StorageFinding(
                        findingId = UUID.randomUUID().toString(),
                        canonicalPath = canonical,
                        ownerPackage = pkg,
                        category = StorageCategory.CORPSE_PRIVATE_ROOT,
                        bytesLogical = sizeBytes,
                        bytesAllocated = sizeBytes,
                        confidence = OwnershipConfidence.HIGH_ROOT,
                        evidenceRule = "ROOT_DATA_USER_ORPHAN",
                        explanationRu = "Приватный каталог /data/user от полностью удалённого пакета '$pkg' (Экспертный Root-режим).",
                        minPrivilege = PrivilegeTier.ROOT,
                        action = CleanerActionType.DELETE_BY_VERIFIED_RULE,
                        reversible = false,
                    )
                }
                continue
            }

            // 3. Проверяем защищённые пользовательские папки (Notes, Backup, Saves, Documents) -> НЕ ТРОГАТЬ!
            if (folderName.lowercase() in PROTECTED_GENERIC_FOLDER_NAMES) {
                val sizeBytes = calculateDirectoryBytes(dir)
                findings += StorageFinding(
                    findingId = UUID.randomUUID().toString(),
                    canonicalPath = canonical,
                    ownerPackage = null,
                    category = StorageCategory.CORPSE_PUBLIC_INVENTORY,
                    bytesLogical = sizeBytes,
                    bytesAllocated = sizeBytes,
                    confidence = OwnershipConfidence.LOW_KEEP,
                    evidenceRule = "PROTECTED_USER_FOLDER_NAME",
                    explanationRu = "Папка '$folderName' может содержать заметки, бэкапы или сохранения пользователя. Защищена от удаления.",
                    minPrivilege = PrivilegeTier.NONE,
                    action = CleanerActionType.REPORT_ONLY_DO_NOT_TOUCH,
                    reversible = true,
                )
                continue
            }

            // 4. Проверяем произвольную публичную папку через снимок UninstallInventoryWatcher
            val recordedOwner = uninstallWatcher.getRecordedOwner(canonical)
            if (recordedOwner != null && recordedOwner !in inventory.installedPackagesAcrossAllUsers) {
                val sizeBytes = calculateDirectoryBytes(dir)
                findings += StorageFinding(
                    findingId = UUID.randomUUID().toString(),
                    canonicalPath = canonical,
                    ownerPackage = recordedOwner,
                    category = StorageCategory.CORPSE_PUBLIC_INVENTORY,
                    bytesLogical = sizeBytes,
                    bytesAllocated = sizeBytes,
                    confidence = OwnershipConfidence.MEDIUM,
                    evidenceRule = "UNINSTALL_WATCHER_SNAPSHOT_MATCH",
                    explanationRu = "Папка была создана приложением '$recordedOwner' до его удаления (средняя уверенность — требуется ручной просмотр).",
                    minPrivilege = PrivilegeTier.NONE,
                    action = CleanerActionType.MEDIA_STORE_TRASH,
                    reversible = true,
                )
            }
        }

        return StoragePathCanonicalizer.deduplicateFindings(findings)
    }

    private fun looksLikeValidPackageName(name: String): Boolean =
        name.contains('.') && name.matches(Regex("^[a-zA-Z][a-zA-Z0-9_]*(\\.[a-zA-Z0-9_]+)+$"))

    private fun calculateDirectoryBytes(dir: File): Long = runCatching {
        if (!dir.exists()) return@runCatching 0L
        dir.walkTopDown().filter { it.isFile }.sumOf { it.length() }
    }.getOrDefault(0L)

    companion object {
        private val APP_SPECIFIC_PATH_REGEX =
            Regex("^/storage/emulated/\\d+/Android/(data|media|obb)/([^/]+)$")
        private val ROOT_DATA_USER_REGEX =
            Regex("^/data/(user|user_de)/(\\d+)/([^/]+)$")

        val PROTECTED_GENERIC_FOLDER_NAMES = setOf(
            "backup",
            "backups",
            "notes",
            "documents",
            "dcim",
            "pictures",
            "music",
            "movies",
            "download",
            "downloads",
            "saves",
            "gamesaves",
            "export",
            "exports",
            "recordings",
            "audiobooks",
        )
    }
}
