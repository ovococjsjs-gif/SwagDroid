package com.swagdroid.feature.optimizer

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.swagdroid.core.model.PackageStateTarget
import com.swagdroid.core.model.PrivilegeState
import com.swagdroid.core.model.SettingNamespace
import com.swagdroid.core.privilege.PrivilegeManager
import com.swagdroid.core.vault.StateVaultManager
import com.swagdroid.core.vault.VaultMutationSpec
import com.swagdroid.core.vault.VaultTransactionResult
import com.swagdroid.feature.optimizer.data.UadCatalogRepository
import com.swagdroid.feature.optimizer.engine.SystemTweaksController
import com.swagdroid.feature.optimizer.model.DebloatAppItem
import com.swagdroid.feature.optimizer.model.LiveSystemTweaksState
import com.swagdroid.feature.optimizer.model.OptimizerPresetTemplate
import com.swagdroid.feature.optimizer.model.OptimizerSubTab
import com.swagdroid.feature.optimizer.model.PrivateDnsPreset
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import javax.inject.Inject

data class OptimizerUiState(
    val privilegeState: PrivilegeState = PrivilegeState(),
    val activeTab: OptimizerSubTab = OptimizerSubTab.PRESETS,
    val presets: List<OptimizerPresetTemplate> = emptyList(),
    val tweaks: LiveSystemTweaksState = LiveSystemTweaksState(),
    val allDebloatItems: List<DebloatAppItem> = emptyList(),
    val showOnlyInstalledDebloat: Boolean = true,
    val selectedRemovalFilter: String? = "Recommended",
    val debloatQuery: String = "",
    val isBusy: Boolean = false,
    val bannerMessage: String? = null,
) {
    val installedBloatCount: Int
        get() = allDebloatItems.count { it.isInstalledOnDevice }

    val selectedIds: Set<String>
        get() = allDebloatItems.asSequence().filter { it.isSelected }.map { it.entry.id }.toSet()

    val visibleDebloatItems: List<DebloatAppItem>
        get() {
            val q = debloatQuery.trim().lowercase()
            return allDebloatItems.asSequence()
                .filter { !showOnlyInstalledDebloat || it.isInstalledOnDevice }
                .filter { selectedRemovalFilter == null || it.entry.removal == selectedRemovalFilter }
                .filter {
                    q.isEmpty() ||
                        it.appLabel.lowercase().contains(q) ||
                        it.entry.id.lowercase().contains(q) ||
                        it.entry.desc.lowercase().contains(q)
                }
                .take(150)
                .toList()
        }
}

private data class InternalOptState(
    val activeTab: OptimizerSubTab = OptimizerSubTab.PRESETS,
    val presets: List<OptimizerPresetTemplate> = emptyList(),
    val tweaks: LiveSystemTweaksState = LiveSystemTweaksState(),
    val allDebloatItems: List<DebloatAppItem> = emptyList(),
    val showOnlyInstalledDebloat: Boolean = true,
    val selectedRemovalFilter: String? = "Recommended",
    val debloatQuery: String = "",
    val isBusy: Boolean = false,
    val bannerMessage: String? = null,
)

@HiltViewModel
class OptimizerViewModel @Inject constructor(
    privilegeManager: PrivilegeManager,
    private val controller: SystemTweaksController,
    private val uadRepo: UadCatalogRepository,
    private val vaultManager: StateVaultManager,
) : ViewModel() {

    private val internal = MutableStateFlow(
        InternalOptState(presets = controller.defaultPresets()),
    )

    val uiState: StateFlow<OptimizerUiState> = combine(
        privilegeManager.state,
        internal,
    ) { priv, st ->
        OptimizerUiState(
            privilegeState = priv,
            activeTab = st.activeTab,
            presets = st.presets,
            tweaks = st.tweaks,
            allDebloatItems = st.allDebloatItems,
            showOnlyInstalledDebloat = st.showOnlyInstalledDebloat,
            selectedRemovalFilter = st.selectedRemovalFilter,
            debloatQuery = st.debloatQuery,
            isBusy = st.isBusy,
            bannerMessage = st.bannerMessage,
        )
    }.stateIn(
        scope = viewModelScope,
        started = SharingStarted.WhileSubscribed(5_000),
        initialValue = OptimizerUiState(presets = controller.defaultPresets()),
    )

    init {
        refreshAll()
    }

    fun selectTab(tab: OptimizerSubTab) {
        internal.update { it.copy(activeTab = tab) }
    }

    fun dismissBanner() {
        internal.update { it.copy(bannerMessage = null) }
    }

    fun refreshAll() {
        viewModelScope.launch {
            internal.update { it.copy(isBusy = true) }
            val liveTweaks = controller.readLiveTweaksState()
            val debloat = uadRepo.scanInstalledAndCatalog()
            internal.update {
                it.copy(
                    tweaks = liveTweaks,
                    allDebloatItems = debloat,
                    isBusy = false,
                )
            }
        }
    }

    // --- Presets ---

    fun applyPreset(presetId: String) {
        val preset = internal.value.presets.firstOrNull { it.id == presetId } ?: return
        viewModelScope.launch {
            internal.update { it.copy(isBusy = true) }
            when (val res = controller.applyPreset(preset)) {
                is VaultTransactionResult.Success -> {
                    val freshTweaks = controller.readLiveTweaksState()
                    internal.update { cur ->
                        cur.copy(
                            isBusy = false,
                            tweaks = freshTweaks,
                            presets = cur.presets.map {
                                if (it.id == presetId) it.copy(activeSnapshotId = res.snapshotId) else it
                            },
                            bannerMessage = "Пресет «${preset.titleRu}» применён · точка отката #${res.snapshotId}",
                        )
                    }
                }
                is VaultTransactionResult.Failure -> {
                    internal.update { it.copy(isBusy = false, bannerMessage = res.errorMessage) }
                }
            }
        }
    }

    fun rollbackPreset(presetId: String, snapshotId: Long) {
        viewModelScope.launch {
            internal.update { it.copy(isBusy = true) }
            when (val res = vaultManager.rollbackSnapshot(snapshotId)) {
                is VaultTransactionResult.Success -> {
                    val freshTweaks = controller.readLiveTweaksState()
                    internal.update { cur ->
                        cur.copy(
                            isBusy = false,
                            tweaks = freshTweaks,
                            presets = cur.presets.map {
                                if (it.id == presetId) it.copy(activeSnapshotId = null) else it
                            },
                            bannerMessage = "Пресет полностью откачен к исходному состоянию (#$snapshotId)",
                        )
                    }
                }
                is VaultTransactionResult.Failure -> {
                    internal.update { it.copy(isBusy = false, bannerMessage = res.errorMessage) }
                }
            }
        }
    }

    // --- Tweaks ---

    fun setAnimationScale(scale: String) {
        runVaultTweak("Шкала анимаций ${scale}x") {
            controller.setAnimationScale(scale)
        }
    }

    fun selectPrivateDns(preset: PrivateDnsPreset) {
        runVaultTweak("DNS: ${preset.titleRu}") {
            controller.applyPrivateDns(preset)
        }
    }

    fun toggleCachedAppsFreezer(enable: Boolean) {
        runVaultTweak("Заморозка кэшированных процессов") {
            controller.setSingleSwitch(
                titleRu = if (enable) "Вкл. Cached Apps Freezer" else "Стандартный Cached Apps Freezer",
                mutations = listOf(
                    VaultMutationSpec.MutateSetting(
                        SettingNamespace.GLOBAL,
                        "cached_apps_freezer",
                        if (enable) "enabled" else "device_default",
                    ),
                ),
            )
        }
    }

    fun togglePhantomKillerDisabled(disableKiller: Boolean) {
        runVaultTweak("Phantom Process Killer") {
            controller.setSingleSwitch(
                titleRu = if (disableKiller) "Откл. Phantom Process Killer" else "Вкл. Phantom Process Killer",
                mutations = listOf(
                    VaultMutationSpec.MutateSetting(
                        SettingNamespace.GLOBAL,
                        "settings_enable_monitor_phantom_procs",
                        if (disableKiller) "false" else "true",
                    ),
                ),
            )
        }
    }

    fun toggleHiddenRadioScanDisabled(disableScan: Boolean) {
        val v = if (disableScan) "0" else "1"
        runVaultTweak("Фоновые радиосканеры") {
            controller.setSingleSwitch(
                titleRu = if (disableScan) "Откл. скрытый поиск Wi-Fi/BLE" else "Вкл. фоновый поиск Wi-Fi/BLE",
                mutations = listOf(
                    VaultMutationSpec.MutateSetting(SettingNamespace.GLOBAL, "wifi_scan_always_enabled", v),
                    VaultMutationSpec.MutateSetting(SettingNamespace.GLOBAL, "ble_scan_always_enabled", v),
                ),
            )
        }
    }

    fun toggleBatteryPercent(enable: Boolean) {
        runVaultTweak("Процент батареи") {
            controller.setSingleSwitch(
                titleRu = "Процент батареи в строке состояния",
                mutations = listOf(
                    VaultMutationSpec.MutateSetting(
                        SettingNamespace.SYSTEM,
                        "status_bar_show_battery_percent",
                        if (enable) "1" else "0",
                    ),
                ),
            )
        }
    }

    fun toggleErrorTelemetryDisabled(disableTelemetry: Boolean) {
        runVaultTweak("Отчёты об ошибках") {
            controller.setSingleSwitch(
                titleRu = "Фоновая отправка отчётов об ошибках",
                mutations = listOf(
                    VaultMutationSpec.MutateSetting(
                        SettingNamespace.GLOBAL,
                        "send_action_app_error",
                        if (disableTelemetry) "0" else "1",
                    ),
                ),
            )
        }
    }

    fun runArtBooster() {
        viewModelScope.launch {
            internal.update { it.copy(isBusy = true) }
            val summary = controller.runArtBoosterAndTrim()
            internal.update { it.copy(isBusy = false, bannerMessage = summary) }
        }
    }

    // --- Debloater ---

    fun updateDebloatQuery(query: String) {
        internal.update { it.copy(debloatQuery = query) }
    }

    fun toggleInstalledOnlyFilter(onlyInstalled: Boolean) {
        internal.update { it.copy(showOnlyInstalledDebloat = onlyInstalled) }
    }

    fun selectRemovalFilter(removal: String?) {
        internal.update { it.copy(selectedRemovalFilter = removal) }
    }

    fun togglePackageSelection(pkgId: String) {
        internal.update { cur ->
            cur.copy(
                allDebloatItems = cur.allDebloatItems.map {
                    if (it.entry.id == pkgId) it.copy(isSelected = !it.isSelected) else it
                },
            )
        }
    }

    fun freezeSinglePackage(pkgId: String) {
        mutatePackages(listOf(pkgId), PackageStateTarget.SUSPENDED, "Мягкая заморозка")
    }

    fun restoreSinglePackage(pkgId: String) {
        mutatePackages(listOf(pkgId), PackageStateTarget.ENABLED, "Разморозка пакета")
    }

    fun freezeSelectedBatch() {
        val selected = internal.value.allDebloatItems
            .filter { it.isSelected && it.isInstalledOnDevice }
            .map { it.entry.id }
        mutatePackages(selected, PackageStateTarget.SUSPENDED, "Пакетная заморозка блоатвари")
    }

    private fun mutatePackages(
        packageIds: List<String>,
        target: PackageStateTarget,
        titleRu: String,
    ) {
        viewModelScope.launch {
            internal.update { it.copy(isBusy = true) }
            when (val res = controller.mutateDebloatPackages(packageIds, target, titleRu)) {
                is VaultTransactionResult.Success -> {
                    val refreshed = uadRepo.scanInstalledAndCatalog()
                    internal.update {
                        it.copy(
                            isBusy = false,
                            allDebloatItems = refreshed,
                            bannerMessage = "$titleRu выполнена (${res.appliedCount} пак.) · точка отката #${res.snapshotId}",
                        )
                    }
                }
                is VaultTransactionResult.Failure -> {
                    internal.update { it.copy(isBusy = false, bannerMessage = res.errorMessage) }
                }
            }
        }
    }

    private fun runVaultTweak(
        labelRu: String,
        block: suspend () -> VaultTransactionResult,
    ) {
        viewModelScope.launch {
            internal.update { it.copy(isBusy = true) }
            when (val res = block()) {
                is VaultTransactionResult.Success -> {
                    val freshTweaks = controller.readLiveTweaksState()
                    internal.update {
                        it.copy(
                            isBusy = false,
                            tweaks = freshTweaks,
                            bannerMessage = "$labelRu сохранено (точка отката #${res.snapshotId})",
                        )
                    }
                }
                is VaultTransactionResult.Failure -> {
                    internal.update { it.copy(isBusy = false, bannerMessage = res.errorMessage) }
                }
            }
        }
    }
}
