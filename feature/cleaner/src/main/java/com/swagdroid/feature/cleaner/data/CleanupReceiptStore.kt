package com.swagdroid.feature.cleaner.data

import com.swagdroid.feature.cleaner.model.CleanupReceipt

interface CleanupReceiptStore {
    suspend fun readLast(): CleanupReceipt?
    /** Return only after durable storage acknowledges the write; never silently replace corrupt data. */
    suspend fun write(receipt: CleanupReceipt)
}

object CleanupReceiptCodec {
    fun encode(receipt: CleanupReceipt): Map<String, String> = buildMap {
        put("version", "1")
        put("id", receipt.planId)
        put("started", receipt.startedAtMs.toString())
        put("planned", receipt.plannedCount.toString())
        put("status", receipt.status.name)
        receipt.completedAtMs?.let { put("completed", it.toString()) }
        receipt.deletedFiles?.let { put("files", it.toString()) }
        receipt.deletedDirectories?.let { put("directories", it.toString()) }
        receipt.deletedBytes?.let { put("bytes", it.toString()) }
        receipt.skipped?.let { put("skipped", it.toString()) }
        receipt.failed?.let { put("failed", it.toString()) }
    }

    fun decode(values: Map<String, String>): CleanupReceipt? {
        if (values.isEmpty()) return null
        require(values["version"] == "1") { "Неизвестный формат итога очистки" }
        val id = requireNotNull(values["id"]).also { require(it.isNotBlank()) }
        val planned = requireNotNull(values["planned"]).toInt().also { require(it > 0) }
        val status = CleanupReceipt.Status.valueOf(requireNotNull(values["status"]))
        val receipt = CleanupReceipt(
            id, requireNotNull(values["started"]).toLong(), planned, status,
            values["completed"]?.toLong(), values["files"]?.toInt(), values["directories"]?.toInt(),
            values["bytes"]?.toLong(), values["skipped"]?.toInt(), values["failed"]?.toInt(),
        )
        if (status == CleanupReceipt.Status.COMPLETE) {
            requireNotNull(receipt.completedAtMs)
            val counts = listOf(receipt.deletedFiles, receipt.deletedDirectories, receipt.skipped, receipt.failed).map { requireNotNull(it) }
            require(counts.all { it >= 0 } && counts.sumOf { it.toLong() } <= planned.toLong())
            require(requireNotNull(receipt.deletedBytes) >= 0)
        } else {
            require(receipt.deletedFiles == null && receipt.deletedDirectories == null && receipt.deletedBytes == null && receipt.skipped == null && receipt.failed == null)
        }
        return receipt
    }
}
