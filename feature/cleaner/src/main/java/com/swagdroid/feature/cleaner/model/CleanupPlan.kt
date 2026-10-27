package com.swagdroid.feature.cleaner.model

/** A selection is not permission to delete. It must first become a reviewed, finite plan. */
data class CleanupSource(val tab: CleanerSubTab, val title: String)

data class CleanupSelectionGroup(
    val source: CleanupSource,
    val paths: List<String>,
    val keptOriginalPath: String? = null,
    val exactDuplicates: Boolean = false,
)

data class CleanupSelection(
    val groups: List<CleanupSelectionGroup>,
    val protectedOriginalPaths: Set<String>,
) {
    companion object {
        fun from(
            junk: List<SystemJunkGroup>,
            messengers: List<MessengerShelfItem>,
            corpses: List<CorpseFinding>,
            duplicates: List<DuplicateCluster>,
        ): CleanupSelection = CleanupSelection(
            groups = buildList {
                junk.filter { it.isSelected && it.category.cleanupBlockReason == null }.forEach {
                    add(CleanupSelectionGroup(CleanupSource(CleanerSubTab.SYSTEM_CACHE, it.category.titleRu), it.targetPaths.toList()))
                }
                messengers.filter { it.isSelected }.forEach {
                    add(CleanupSelectionGroup(CleanupSource(CleanerSubTab.MESSENGERS, "${it.brand.titleRu} · ${it.shelf.titleRu}"), it.filePaths.toList()))
                }
                corpses.filter { it.isSelected }.forEach {
                    add(CleanupSelectionGroup(CleanupSource(CleanerSubTab.CORPSES, it.packageName), listOf(it.absolutePath)))
                }
                duplicates.filter { it.isSelected }.forEach {
                    add(
                        CleanupSelectionGroup(
                            source = CleanupSource(CleanerSubTab.DUPLICATES, it.kind.labelRu),
                            paths = it.duplicatePaths.toList(),
                            keptOriginalPath = it.keptOriginalPath,
                            exactDuplicates = it.kind == DuplicateMatchKind.EXACT_SHA256,
                        ),
                    )
                }
            },
            // Also protect originals when the same directory is selected in another tab.
            protectedOriginalPaths = duplicates.map { it.keptOriginalPath }.toSet(),
        )
    }
}

data class CleanupFileSnapshot(
    val path: String,
    val bytes: Long,
    val modifiedAtMs: Long,
    val sha256: String?,
    val isEmptyDirectory: Boolean = false,
    val fileKey: String? = null,
)

data class PlannedCleanupTarget(
    val snapshot: CleanupFileSnapshot,
    val sources: Set<CleanupSource>,
    val requiredOriginals: Set<CleanupFileSnapshot> = emptySet(),
)

data class CleanupExclusion(val path: String, val reason: String)

data class PreparedCleanupPlan(
    val id: String,
    val storageRoot: String,
    val targets: List<PlannedCleanupTarget>,
    val exclusions: List<CleanupExclusion>,
) {
    val fileCount: Int get() = targets.count { !it.snapshot.isEmptyDirectory }
    val directoryCount: Int get() = targets.count { it.snapshot.isEmptyDirectory }
    val fileBytes: Long get() = targets.sumOf { it.snapshot.bytes }
    val sources: Set<CleanupSource> get() = targets.flatMap { it.sources }.toSet()
}

/** Pure confirmation state: cancel, a stale dialog or an unchecked warning cannot authorize work. */
sealed interface CleanupReview {
    data object None : CleanupReview
    data class Awaiting(val plan: PreparedCleanupPlan) : CleanupReview
    class Approved internal constructor(private val plan: PreparedCleanupPlan) : CleanupReview {
        private var claimed = false

        /** A receipt authorizes one execution attempt, including an attempt that fails or is cancelled. */
        @Synchronized
        internal fun claimPlan(): PreparedCleanupPlan? {
            if (claimed) return null
            claimed = true
            return plan
        }
    }

    fun approve(planId: String, acceptsPermanentDeletion: Boolean): Approved? =
        if (this is Awaiting && plan.id == planId && acceptsPermanentDeletion && plan.targets.isNotEmpty()) {
            Approved(plan)
        } else {
            null
        }
}

data class CleanupExecutionResult(
    val deletedFiles: Int,
    val deletedEmptyDirectories: Int,
    /** Sum of deleted file lengths, not a measurement of newly available filesystem space. */
    val deletedFileBytes: Long,
    val skipped: List<CleanupExclusion>,
    val failed: List<CleanupExclusion>,
)
