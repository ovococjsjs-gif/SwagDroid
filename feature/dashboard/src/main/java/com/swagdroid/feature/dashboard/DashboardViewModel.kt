package com.swagdroid.feature.dashboard

import android.content.Intent
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.swagdroid.core.model.DevicePassport
import com.swagdroid.core.model.FeatureSource
import com.swagdroid.core.model.PrivilegeState
import com.swagdroid.core.model.PrivilegeTier
import com.swagdroid.core.model.SettingNamespace
import com.swagdroid.core.model.SnapshotStatus
import com.swagdroid.core.model.SystemHealthSummary
import com.swagdroid.core.model.VaultSnapshot
import com.swagdroid.core.privilege.DevicePassportCollector
import com.swagdroid.core.privilege.PrivilegeManager
import com.swagdroid.core.vault.StateVaultManager
import com.swagdroid.core.vault.VaultMutationSpec
import com.swagdroid.core.vault.VaultTransactionResult
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import javax.inject.Inject

sealed interface DashboardSheet {
    data object None : DashboardSheet
    data object WirelessAdbWizard : DashboardSheet
    data object RootAdvisor : DashboardSheet
    data object StateVaultHistory : DashboardSheet
}

data class DashboardUiState(
    val isLoading: Boolean = true,
    val privilege: PrivilegeState = PrivilegeState(),
    val passport: DevicePassport? = null,
    val health: SystemHealthSummary? = null,
    val snapshots: List<VaultSnapshot> = emptyList(),
    val activeSnapshotsCount: Int = 0,
    val activeSheet: DashboardSheet = DashboardSheet.None,
    val bannerMessage: String? = null,
)

@HiltViewModel
class DashboardViewModel @Inject constructor(
    private val privilegeManager: PrivilegeManager,
    private val passportCollector: DevicePassportCollector,
    private val vaultManager: StateVaultManager,
) : ViewModel() {

    private val passportFlow = MutableStateFlow<DevicePassport?>(null)
    private val activeSheetFlow = MutableStateFlow<DashboardSheet>(DashboardSheet.None)
    private val bannerFlow = MutableStateFlow<String?>(null)

    val uiState: StateFlow<DashboardUiState> = combine(
        privilegeManager.state,
        passportFlow,
        vaultManager.snapshotsFlow,
        activeSheetFlow,
        bannerFlow,
    ) { privilege, passport, snapshots, sheet, banner ->
        val health = passport?.let {
            HealthScoreCalculator.evaluate(
                passport = it,
                privilege = privilege,
                snapshots = snapshots,
            )
        }
        val activeCount = snapshots.count { it.status.canAttemptRollback }
        DashboardUiState(
            isLoading = passport == null,
            privilege = privilege,
            passport = passport,
            health = health,
            snapshots = snapshots,
            activeSnapshotsCount = activeCount,
            activeSheet = sheet,
            bannerMessage = banner,
        )
    }.stateIn(
        scope = viewModelScope,
        started = SharingStarted.WhileSubscribed(5_000),
        initialValue = DashboardUiState(),
    )

    init {
        refreshAll()
    }

    fun refreshAll() {
        launchSafely {
            privilegeManager.refreshPrivilegeState(requestRootPopup = false)
            passportFlow.value = passportCollector.collect()
        }
    }

    fun openSheet(sheet: DashboardSheet) {
        activeSheetFlow.value = sheet
    }

    fun closeSheet() {
        activeSheetFlow.value = DashboardSheet.None
    }

    fun dismissBanner() {
        bannerFlow.value = null
    }

    fun requestShizukuPermission() {
        privilegeManager.requestShizukuPermission { granted ->
            launchSafely {
                passportFlow.value = passportCollector.collect()
                bannerFlow.value = if (granted) {
                    "Суперправа Shizuku (UID 2000) успешно подключены!"
                } else {
                    "Убедитесь, что сервис Shizuku запущен через Беспроводную отладку."
                }
            }
        }
    }

    fun requestRootAccess() {
        launchSafely {
            val granted = privilegeManager.requestRootAccess()
            passportFlow.value = passportCollector.collect()
            bannerFlow.value = if (granted) {
                "Полный Root-доступ (UID 0) активирован!"
            } else {
                "Root-доступ не обнаружен. Откройте «Root-советник» или используйте Беспроводной ADB."
            }
        }
    }

    fun createBaselineCheckpoint() {
        launchSafely {
            bannerFlow.value = when (val result = vaultManager.createBaselineCheckpoint()) {
                is VaultTransactionResult.Success -> "Справочный снимок 9 параметров #${result.snapshotId} сохранён. Это не полный бэкап или автоматический откат."
                is VaultTransactionResult.Failure -> result.errorMessage
            }
        }
    }

    /**
     * Real Stage 1 transactional tweak demonstrating StateVault before-capture + 1-tap rollback:
     * speeds up Android window/transition/animator scales to snappy 0.5x.
     */
    fun applySnappyAnimationsTweak() {
        launchSafely {
            if (privilegeManager.state.value.activeTier == PrivilegeTier.BASIC) {
                activeSheetFlow.value = DashboardSheet.WirelessAdbWizard
                bannerFlow.value = "Для изменения системных шкал анимации подключите ADB / Shizuku или Root."
                return@launchSafely
            }
            val result = vaultManager.applyTransaction(
                title = "Отзывчивые анимации интерфейса (0.5x)",
                reason = "Ускорение системных переходов вдвое с сохранением плавности",
                source = FeatureSource.OPTIMIZER,
                mutations = listOf(
                    VaultMutationSpec.MutateSetting(SettingNamespace.GLOBAL, "window_animation_scale", "0.5"),
                    VaultMutationSpec.MutateSetting(SettingNamespace.GLOBAL, "transition_animation_scale", "0.5"),
                    VaultMutationSpec.MutateSetting(SettingNamespace.GLOBAL, "animator_duration_scale", "0.5"),
                ),
            )
            bannerFlow.value = when (result) {
                is VaultTransactionResult.Success ->
                    "Анимации 0.5x применены! Создана точка отката #${result.snapshotId}."
                is VaultTransactionResult.Failure ->
                    "Не удалось применить твик: ${result.errorMessage}"
            }
        }
    }

    fun rollbackSnapshot(snapshotId: Long) {
        launchSafely {
            val res = vaultManager.rollbackSnapshot(snapshotId)
            bannerFlow.value = when (res) {
                is VaultTransactionResult.Success ->
                    "Точка #$snapshotId успешно откачена (${res.appliedCount} парам. возвращено)."
                is VaultTransactionResult.Failure ->
                    "Ошибка при откате: ${res.errorMessage}"
            }
        }
    }

    fun rollbackAllActive() {
        launchSafely {
            val report = vaultManager.rollbackAllActive()
            bannerFlow.value = buildString {
                append("Проверено восстановлений: ${report.restoredCount} из ${report.eligibleCount}.")
                if (report.failures.isNotEmpty()) append(" Ошибки: ${report.failures.values.joinToString("; ")}")
                if (report.notAttemptedCount > 0) append(" Не запускались после сбоя: ${report.notAttemptedCount}.")
                if (report.unverifiedCount > 0) append(" Старых/повреждённых записей без автоматического отката: ${report.unverifiedCount}.")
            }
        }
    }

    fun exportVaultJson(onJsonReady: (String) -> Unit) {
        launchSafely {
            val model = passportFlow.value?.advisor?.deviceModel ?: android.os.Build.MODEL
            val exported = vaultManager.exportSnapshotsJson(model)
            onJsonReady(exported)
        }
    }

    fun developerOptionsIntent(): Intent = privilegeManager.buildDeveloperOptionsIntent()
    fun aboutPhoneIntent(): Intent = privilegeManager.buildAboutPhoneIntent()
    fun shizukuIntent(): Intent = privilegeManager.buildOpenOrGetShizukuIntent()
    private fun launchSafely(action: suspend () -> Unit) = viewModelScope.launch {
        try { action() }
        catch (cancelled: CancellationException) { throw cancelled }
        catch (error: Exception) { bannerFlow.value = "Операция не подтверждена: ${error.message}. Проверьте журнал StateVault." }
    }

}
