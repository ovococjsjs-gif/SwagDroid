package com.swagdroid.feature.adblock

import com.swagdroid.api.ISwagDroidService
import com.swagdroid.api.SettingNamespace
import com.swagdroid.core.rollback.ReversibilityType
import com.swagdroid.core.rollback.ReversibleSystemOp
import com.swagdroid.core.rollback.RollbackJournal
import com.swagdroid.core.rollback.RollbackTransaction
import com.swagdroid.core.rollback.WalEntryState
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable

/**
 * Результат безопасного применения Private DNS с проверкой связности (по принципу смены разрешения экрана).
 */
@Serializable
data class PrivateDnsApplyTrialResult(
    val transaction: RollbackTransaction,
    val targetHostname: String,
    val previousMode: String?,
    val previousSpecifier: String?,
    val probeConnectivityPassed: Boolean,
    val confirmationTimeoutSeconds: Int = 15,
    val autoRolledBackDueToProbeFailure: Boolean,
)

/**
 * Контроллер безопасного переключения Private DNS (`PrivateDnsSafetyController`).
 *
 * Почему нельзя просто молча записать `private_dns_mode=hostname`:
 * Если выбранный DoT-сервер заблокирован провайдером или недоступен на текущем Wi-Fi,
 * строгий режим `hostname` оставит телефон вообще без DNS!
 *
 * Поэтому применение работает как смена разрешения экрана на ПК:
 * 1. В Write-Ahead журнал (`RollbackJournal`) сохраняется снимок старых `private_dns_mode` и `private_dns_specifier` (в состоянии `PREPARED`).
 * 2. Проверяется синтаксис DoT-хоста.
 * 3. Записываются новые значения и перечитываются обратно.
 * 4. Выполняется проверка DNS-резолва (`connectivityProbe`).
 * 5. Если проверка не прошла ИЛИ пользователь не нажал «Подтвердить» за 15 секунд — контроллер
 *    автоматически откатывает старые настройки DNS!
 */
class PrivateDnsSafetyController(
    private val rollbackJournal: RollbackJournal,
) {
    suspend fun applyWithSafetyRollback(
        profile: PrivateDnsProviderProfile,
        service: ISwagDroidService,
        connectivityProbe: suspend (String) -> Boolean,
    ): Result<PrivateDnsApplyTrialResult> = withContext(Dispatchers.IO) {
        runCatching {
            val targetHost = profile.resolveDotHostname()
            require(PrivateDnsProviderProfile.isValidDotHostname(targetHost)) {
                "Некорректный синтаксис DoT hostname: $targetHost"
            }

            // 1. Читаем текущие значения до изменения (включая null, если они не были заданы)
            val prevMode = service.getSystemSetting(SettingNamespace.GLOBAL.wireName, KEY_PRIVATE_DNS_MODE)
            val prevSpecifier = service.getSystemSetting(SettingNamespace.GLOBAL.wireName, KEY_PRIVATE_DNS_SPECIFIER)

            // 2. Записываем PREPARED снепшот в Write-Ahead журнал ДО изменения настроек
            val tx = rollbackJournal.recordPreMutationSnapshot(
                featureId = "adblock_private_dns",
                titleRu = "Включение Private DNS: ${profile.displayNameRu}",
                descriptionRu = "Установка private_dns_mode=hostname, specifier=$targetHost",
                operations = listOf(
                    ReversibleSystemOp.SystemSettingChange(
                        namespace = SettingNamespace.GLOBAL,
                        key = KEY_PRIVATE_DNS_SPECIFIER,
                        previousValue = prevSpecifier,
                        newValue = targetHost,
                    ),
                    ReversibleSystemOp.SystemSettingChange(
                        namespace = SettingNamespace.GLOBAL,
                        key = KEY_PRIVATE_DNS_MODE,
                        previousValue = prevMode,
                        newValue = MODE_STRICT_HOSTNAME,
                    ),
                ),
                reversibility = ReversibilityType.EXACT_REVERSIBLE,
                initialState = WalEntryState.PREPARED,
            )

            rollbackJournal.updateWalState(tx.transactionId, WalEntryState.APPLYING)

            // 3. Применяем настройки и проверяем фактическую запись
            service.putSystemSetting(SettingNamespace.GLOBAL.wireName, KEY_PRIVATE_DNS_SPECIFIER, targetHost)
            service.putSystemSetting(SettingNamespace.GLOBAL.wireName, KEY_PRIVATE_DNS_MODE, MODE_STRICT_HOSTNAME)

            val readBackHost = service.getSystemSetting(SettingNamespace.GLOBAL.wireName, KEY_PRIVATE_DNS_SPECIFIER)
            check(readBackHost == targetHost) {
                "Не удалось подтвердить запись private_dns_specifier (ожидалось $targetHost, прочитано $readBackHost)"
            }
            rollbackJournal.updateWalState(tx.transactionId, WalEntryState.APPLIED)

            // 4. Проверяем, работает ли DNS-резолв и валидация сети с новым DoT-сервером
            val probePassed = runCatching { connectivityProbe(targetHost) }.getOrDefault(false)
            if (!probePassed) {
                // Автоматический откат, чтобы не оставить пользователя без интернета!
                rollbackJournal.updateWalState(tx.transactionId, WalEntryState.VERIFY_FAILED)
                rollbackJournal.rollbackTransaction(tx.transactionId, service, forceOverwriteConflicts = true)
                return@runCatching PrivateDnsApplyTrialResult(
                    transaction = tx.copy(walState = WalEntryState.ROLLED_BACK, isRolledBack = true),
                    targetHostname = targetHost,
                    previousMode = prevMode,
                    previousSpecifier = prevSpecifier,
                    probeConnectivityPassed = false,
                    autoRolledBackDueToProbeFailure = true,
                )
            }

            rollbackJournal.updateWalState(tx.transactionId, WalEntryState.VERIFIED)
            PrivateDnsApplyTrialResult(
                transaction = tx.copy(walState = WalEntryState.VERIFIED),
                targetHostname = targetHost,
                previousMode = prevMode,
                previousSpecifier = prevSpecifier,
                probeConnectivityPassed = true,
                autoRolledBackDueToProbeFailure = false,
            )
        }
    }

    companion object {
        const val KEY_PRIVATE_DNS_MODE = "private_dns_mode"
        const val KEY_PRIVATE_DNS_SPECIFIER = "private_dns_specifier"
        const val MODE_STRICT_HOSTNAME = "hostname"
        const val MODE_OPPORTUNISTIC = "opportunistic"
        const val MODE_OFF = "off"
    }
}
