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
import com.swagdroid.feature.optimizer.model.AndroidGameModeChoice
import com.swagdroid.feature.optimizer.model.ArtCompileMode
import com.swagdroid.feature.optimizer.model.BoosterUserApp
import com.swagdroid.feature.optimizer.model.DebloatAppItem
import com.swagdroid.feature.optimizer.model.DebloatApplyMode
import com.swagdroid.feature.optimizer.model.ImmersiveModeOption
import com.swagdroid.feature.optimizer.model.LiveSystemTweaksState
import com.swagdroid.feature.optimizer.model.OptimizerPresetTemplate
import com.swagdroid.feature.optimizer.model.OptimizerSubTab
import com.swagdroid.feature.optimizer.model.PrivateDnsPreset
import com.swagdroid.feature.optimizer.model.RefreshRateProfile
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
    val boosterApps: List<BoosterUserApp> = emptyList(),
    val selectedArtMode: ArtCompileMode = ArtCompileMode.SPEED_PROFILE,
    val allDebloatItems: List<DebloatAppItem> = emptyList(),
    val debloatApplyMode: DebloatApplyMode = DebloatApplyMode.SUSPEND_SAFE,
    val showOnlyInstalledDebloat: Boolean = true,
    val selectedRemovalFilter: String? = "Recommended",
    val selectedListSourceFilter: String? = null,
    val debloatQuery: String = "",
    val exportedJsonPreview: String? = null,
    val showJsonSheet: Boolean = false,
    val isBusy: Boolean = false,
    val bannerMessage: String? = null,
) {
    val installedBloatCount: Int
        get() = allDebloatItems.count { it.isInstalledOnDevice }

    val frozenBloatCount: Int
        get() = allDebloatItems.count { it.isInstalledOnDevice && it.stateTarget != PackageStateTarget.ENABLED }

    val selectedIds: Set<String>
        get() = allDebloatItems.asSequence().filter { it.isSelected }.map { it.entry.id }.toSet()

    val visibleDebloatItems: List<DebloatAppItem>
        get() {
            val q = debloatQuery.trim().lowercase()
            return allDebloatItems.asSequence()
                .filter { !showOnlyInstalledDebloat || it.isInstalledOnDevice }
                .filter { selectedRemovalFilter == null || it.entry.removal == selectedRemovalFilter }
                .filter { selectedListSourceFilter == null || it.entry.list == selectedListSourceFilter }
                .filter {
                    q.isEmpty() ||
                        it.appLabel.lowercase().contains(q) ||
                        it.entry.id.lowercase().contains(q) ||
                        it.entry.desc.lowercase().contains(q)
                }
                .take(160)
                .toList()
        }
}

private data class InternalOptState(
    val activeTab: OptimizerSubTab = OptimizerSubTab.PRESETS,
    val presets: List<OptimizerPresetTemplate> = emptyList(),
    val tweaks: LiveSystemTweaksState = LiveSystemTweaksState(),
    val boosterApps: List<BoosterUserApp> = emptyList(),
    val selectedArtMode: ArtCompileMode = ArtCompileMode.SPEED_PROFILE,
    val allDebloatItems: List<DebloatAppItem> = emptyList(),
    val debloatApplyMode: DebloatApplyMode = DebloatApplyMode.SUSPEND_SAFE,
    val showOnlyInstalledDebloat: Boolean = true,
    val selectedRemovalFilter: String? = "Recommended",
    val selectedListSourceFilter: String? = null,
    val debloatQuery: String = "",
    val exportedJsonPreview: String? = null,
    val showJsonSheet: Boolean = false,
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
            boosterApps = st.boosterApps,
            selectedArtMode = st.selectedArtMode,
            allDebloatItems = st.allDebloatItems,
            debloatApplyMode = st.debloatApplyMode,
            showOnlyInstalledDebloat = st.showOnlyInstalledDebloat,
            selectedRemovalFilter = st.selectedRemovalFilter,
            selectedListSourceFilter = st.selectedListSourceFilter,
            debloatQuery = st.debloatQuery,
            exportedJsonPreview = st.exportedJsonPreview,
            showJsonSheet = st.showJsonSheet,
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
            val userApps = controller.loadUserAppsForBooster()
            internal.update {
                it.copy(
                    tweaks = liveTweaks,
                    allDebloatItems = debloat,
                    boosterApps = userApps,
                    isBusy = false,
                )
            }
        }
    }

    // --- Presets & Portable JSON Profiles ---

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
                            bannerMessage = "Пресет применён · создана точка отката #${res.snapshotId}",
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
                            bannerMessage = "Пресет полностью откачен (#$snapshotId)",
                        )
                    }
                }
                is VaultTransactionResult.Failure -> {
                    internal.update { it.copy(isBusy = false, bannerMessage = res.errorMessage) }
                }
            }
        }
    }

    fun openPortableJsonSheet() {
        val suspendedIds = internal.value.allDebloatItems
            .filter { it.isInstalledOnDevice && it.stateTarget != PackageStateTarget.ENABLED }
            .map { it.entry.id }
        val json = controller.exportPortableProfileJson(internal.value.tweaks, suspendedIds)
        internal.update { it.copy(exportedJsonPreview = json, showJsonSheet = true) }
    }

    fun closePortableJsonSheet() {
        internal.update { it.copy(showJsonSheet = false) }
    }

    fun importPortableJson(rawJson: String) {
        viewModelScope.launch {
            internal.update { it.copy(isBusy = true, showJsonSheet = false) }
            when (val res = controller.importPortableProfileJson(rawJson)) {
                is VaultTransactionResult.Success -> {
                    val freshTweaks = controller.readLiveTweaksState()
                    val freshDebloat = uadRepo.scanInstalledAndCatalog()
                    internal.update {
                        it.copy(
                            isBusy = false,
                            tweaks = freshTweaks,
                            allDebloatItems = freshDebloat,
                            bannerMessage = "JSON-профиль применён (${res.appliedCount} опер.) · точка отката #${res.snapshotId}",
                        )
                    }
                }
                is VaultTransactionResult.Failure -> {
                    internal.update { it.copy(isBusy = false, bannerMessage = res.errorMessage) }
                }
            }
        }
    }

    // --- System, Network & Privacy Tweaks ---

    fun setAnimationScale(scale: String) = runVaultTweak("Шкала анимаций ${scale}x") {
        controller.setAnimationScale(scale)
    }

    fun applyRefreshProfile(profile: RefreshRateProfile) = runVaultTweak(profile.titleRu) {
        controller.applyRefreshRateProfile(profile)
    }

    fun selectPrivateDns(preset: PrivateDnsPreset) = runVaultTweak("DNS: ${preset.titleRu}") {
        controller.applyPrivateDns(preset)
    }

    fun applyCustomDnsHost(host: String) = runVaultTweak("Кастомный DoT DNS") {
        controller.applyCustomPrivateDnsHost(host)
    }

    fun applyClipboardGuard() = runVaultTweak("Clipboard Guard") {
        controller.applyClipboardGuardAllUserApps()
    }

    fun toggleSettingFlag(
        labelRu: String,
        namespace: SettingNamespace,
        key: String,
        targetVal: String,
    ) = runVaultTweak(labelRu) {
        controller.setSingleSwitch(
            titleRu = labelRu,
            mutations = listOf(VaultMutationSpec.MutateSetting(namespace, key, targetVal)),
        )
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

    fun setKernelSwappiness(value: Int) = runVaultTweak("VM Swappiness $value") {
        controller.setKernelSwappiness(value)
    }

    // --- SystemUI Tuner ---

    fun toggleStatusBarIcon(token: String, hide: Boolean) = runVaultTweak("Иконка статус-бара: $token") {
        controller.toggleStatusBarIconHidden(token, hide)
    }

    fun resetStatusBarIcons() = runVaultTweak("Все иконки статус-бара") {
        controller.resetAllStatusBarIcons()
    }

    fun applyImmersiveMode(option: ImmersiveModeOption) = runVaultTweak(option.titleRu) {
        controller.applyImmersiveMode(option)
    }

    // --- App Booster & Game Mode ---

    fun selectArtCompileMode(mode: ArtCompileMode) {
        internal.update { it.copy(selectedArtMode = mode) }
    }

    fun runGlobalArtBooster() {
        val mode = internal.value.selectedArtMode
        viewModelScope.launch {
            internal.update { it.copy(isBusy = true) }
            val summary = controller.runGlobalArtCompileAndTrim(mode)
            internal.update { it.copy(isBusy = false, bannerMessage = summary) }
        }
    }

    fun compileSingleApp(pkg: String) {
        val mode = internal.value.selectedArtMode
        viewModelScope.launch {
            internal.update { it.copy(isBusy = true) }
            val msg = controller.compileSingleApp(pkg, mode)
            internal.update { it.copy(isBusy = false, bannerMessage = msg) }
        }
    }

    fun setAppGameMode(pkg: String, choice: AndroidGameModeChoice) =
        runVaultTweak("Game Mode ($pkg → ${choice.labelRu})") {
            controller.applyAndroidGameMode(pkg, choice)
        }

    // --- Debloater Pro ---

    fun updateDebloatQuery(query: String) {
        internal.update { it.copy(debloatQuery = query) }
    }

    fun selectDebloatApplyMode(mode: DebloatApplyMode) {
        internal.update { it.copy(debloatApplyMode = mode) }
    }

    fun toggleInstalledOnlyFilter(onlyInstalled: Boolean) {
        internal.update { it.copy(showOnlyInstalledDebloat = onlyInstalled) }
    }

    fun selectRemovalFilter(removal: String?) {
        internal.update { it.copy(selectedRemovalFilter = removal) }
    }

    fun selectListSourceFilter(source: String?) {
        internal.update { it.copy(selectedListSourceFilter = source) }
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

    fun selectAllRecommendedOnDevice() {
        internal.update { cur ->
            cur.copy(
                allDebloatItems = cur.allDebloatItems.map { item ->
                    if (item.isInstalledOnDevice && item.entry.removal == "Recommended" && item.stateTarget == PackageStateTarget.ENABLED) {
                        item.copy(isSelected = true)
                    } else {
                        item
                    }
                },
            )
        }
    }

    fun clearAllDebloatSelections() {
        internal.update { cur ->
            cur.copy(allDebloatItems = cur.allDebloatItems.map { it.copy(isSelected = false) })
        }
    }

    fun freezeSinglePackage(pkgId: String) {
        val target = when (internal.value.debloatApplyMode) {
            DebloatApplyMode.SUSPEND_SAFE -> PackageStateTarget.SUSPENDED
            DebloatApplyMode.DISABLE_USER -> PackageStateTarget.DISABLED_USER
        }
        mutatePackages(listOf(pkgId), target, internal.value.debloatApplyMode.titleRu)
    }

    fun restoreSinglePackage(pkgId: String) {
        mutatePackages(listOf(pkgId), PackageStateTarget.ENABLED, "Разморозка пакета")
    }

    fun freezeSelectedBatch() {
        val selected = internal.value.allDebloatItems
            .filter { it.isSelected && it.isInstalledOnDevice }
            .map { it.entry.id }
        val target = when (internal.value.debloatApplyMode) {
            DebloatApplyMode.SUSPEND_SAFE -> PackageStateTarget.SUSPENDED
            DebloatApplyMode.DISABLE_USER -> PackageStateTarget.DISABLED_USER
        }
        mutatePackages(selected, target, "Пакетная заморозка блоатвари")
    }

    fun restoreAllFrozenPackages() {
        val frozen = internal.value.allDebloatItems
            .filter { it.isInstalledOnDevice && it.stateTarget != PackageStateTarget.ENABLED }
            .map { it.entry.id }
        mutatePackages(frozen, PackageStateTarget.ENABLED, "Массовая разморозка всех пакетов")
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
