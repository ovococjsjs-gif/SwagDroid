package com.swagdroid.feature.optimizer.data

import android.content.Context
import android.content.pm.ApplicationInfo
import android.content.pm.PackageManager
import com.swagdroid.core.model.PackageStateTarget
import com.swagdroid.feature.optimizer.model.DebloatAppItem
import com.swagdroid.feature.optimizer.model.UadCatalogEntry
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class UadCatalogRepository @Inject constructor(
    @ApplicationContext private val context: Context,
) {
    private val mutex = Mutex()
    private var cachedEntries: List<UadCatalogEntry>? = null

    suspend fun getAllCatalogEntries(): List<UadCatalogEntry> = mutex.withLock {
        cachedEntries?.let { return@withLock it }
        val loaded = withContext(Dispatchers.IO) {
            val rawJson = context.assets.open(ASSET_FILE_NAME).bufferedReader().use { it.readText() }
            parseCatalogJson(rawJson)
        }
        cachedEntries = loaded
        loaded
    }

    /**
     * Cross-references the 4,123-package UAD-ng knowledge base against the packages
     * actually installed on this Android smartphone.
     */
    suspend fun scanInstalledAndCatalog(): List<DebloatAppItem> = withContext(Dispatchers.IO) {
        val entries = getAllCatalogEntries()
        val pm = context.packageManager

        @Suppress("DEPRECATION")
        val installedApps: Map<String, ApplicationInfo> = pm
            .getInstalledApplications(PackageManager.MATCH_UNINSTALLED_PACKAGES)
            .associateBy { it.packageName }

        entries.map { entry ->
            val appInfo = installedApps[entry.id]
            val isInstalled = appInfo != null && (appInfo.flags and ApplicationInfo.FLAG_INSTALLED) != 0
            val label = if (appInfo != null) {
                runCatching { pm.getApplicationLabel(appInfo).toString() }.getOrDefault(entry.id)
            } else {
                entry.id.substringAfterLast('.').replaceFirstChar { it.uppercase() }
            }
            val currentState = when {
                appInfo == null -> PackageStateTarget.ENABLED
                (appInfo.flags and ApplicationInfo.FLAG_SUSPENDED) != 0 -> PackageStateTarget.SUSPENDED
                !appInfo.enabled -> PackageStateTarget.DISABLED_USER
                else -> PackageStateTarget.ENABLED
            }

            DebloatAppItem(
                entry = entry,
                appLabel = label,
                isInstalledOnDevice = isInstalled,
                stateTarget = currentState,
            )
        }.sortedWith(
            compareByDescending<DebloatAppItem> { it.isInstalledOnDevice }
                .thenByDescending { it.entry.ru }
                .thenBy { removalRank(it.entry.removal) }
                .thenBy { it.appLabel.lowercase() },
        )
    }

    private fun removalRank(removal: String): Int = when (removal) {
        "Recommended" -> 0
        "Advanced" -> 1
        else -> 2
    }

    companion object {
        const val ASSET_FILE_NAME = "uad_catalog.json"

        private val catalogJson = Json {
            ignoreUnknownKeys = true
            isLenient = true
        }

        fun parseCatalogJson(rawJson: String): List<UadCatalogEntry> =
            catalogJson.decodeFromString<List<UadCatalogEntry>>(rawJson)
    }
}
