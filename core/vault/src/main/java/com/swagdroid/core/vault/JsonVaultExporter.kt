package com.swagdroid.core.vault

import com.swagdroid.core.model.VaultExportBundle
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import javax.inject.Inject

class JsonVaultExporter @Inject constructor(private val json: Json) : VaultExporter {
    override fun export(bundle: VaultExportBundle): String = json.encodeToString(bundle)
}
