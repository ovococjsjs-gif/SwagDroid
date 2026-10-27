package com.swagdroid.feature.cleaner.engine

import com.swagdroid.feature.cleaner.model.CleanupFileSnapshot
import com.swagdroid.feature.cleaner.model.CleanupReview
import com.swagdroid.feature.cleaner.model.CleanupRunOutcome
import com.swagdroid.feature.cleaner.model.CleanupScanProgress
import com.swagdroid.feature.cleaner.model.CleanupSelection
import com.swagdroid.feature.cleaner.model.PreparedCleanupPlan
import com.swagdroid.feature.cleaner.model.StorageAccess
import com.swagdroid.feature.cleaner.model.StorageCleanupScan
import com.swagdroid.feature.cleaner.model.StorageSpace

data class CleanupFilePreview(val uri: String, val mimeType: String)

interface CleanerOperations {
    fun storageAccess(): StorageAccess
    fun storageSpace(): StorageSpace?
    suspend fun scan(onProgress: (CleanupScanProgress) -> Unit): StorageCleanupScan
    suspend fun prepare(selection: CleanupSelection): PreparedCleanupPlan
    suspend fun execute(approval: CleanupReview.Approved): CleanupRunOutcome
    suspend fun preview(file: CleanupFileSnapshot): CleanupFilePreview
}
