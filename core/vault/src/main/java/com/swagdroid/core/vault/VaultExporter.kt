package com.swagdroid.core.vault

import com.swagdroid.core.model.VaultExportBundle

fun interface VaultExporter {
    fun export(bundle: VaultExportBundle): String
}
