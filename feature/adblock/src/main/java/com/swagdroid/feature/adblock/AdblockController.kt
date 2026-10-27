package com.swagdroid.feature.adblock

import android.content.Context
import android.content.Intent
import android.os.Build
import com.swagdroid.core.model.FeatureSource
import com.swagdroid.core.model.PrivilegeTier
import com.swagdroid.core.model.SettingNamespace
import com.swagdroid.core.privilege.PrivilegedCommandExecutor
import com.swagdroid.core.vault.StateVaultManager
import com.swagdroid.core.vault.VaultMutationSpec
import com.swagdroid.core.vault.VaultTransactionResult
import com.swagdroid.feature.adblock.data.BlocklistRepository
import com.swagdroid.feature.adblock.model.UpstreamDnsResolver
import com.swagdroid.feature.adblock.vpn.SwagDnsVpnService
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class AdblockController @Inject constructor(
    @ApplicationContext private val context: Context,
    private val blocklistRepo: BlocklistRepository,
    private val executor: PrivilegedCommandExecutor,
    private val vaultManager: StateVaultManager,
) {

    // =========================================================================
    // Mode 1: Local Split-Route DNS Sinkhole VPN (No-Root)
    // =========================================================================

    fun startLocalDnsVpn(
        upstream: UpstreamDnsResolver,
        bypassedPackages: List<String> = emptyList(),
    ) {
        val intent = Intent(context, SwagDnsVpnService::class.java).apply {
            action = SwagDnsVpnService.ACTION_START
            putExtra(SwagDnsVpnService.EXTRA_UPSTREAM_IP, upstream.ipv4Address)
            putStringArrayListExtra(
                SwagDnsVpnService.EXTRA_BYPASSED_PACKAGES,
                ArrayList(bypassedPackages),
            )
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            context.startForegroundService(intent)
        } else {
            context.startService(intent)
        }
    }

    fun stopLocalDnsVpn() {
        val intent = Intent(context, SwagDnsVpnService::class.java).apply {
            action = SwagDnsVpnService.ACTION_STOP
        }
        context.startService(intent)
    }

    // =========================================================================
    // Mode 2: Native Private DNS DoT via ADB/Shizuku (Leaves VPN Slot 100% Free!)
    // =========================================================================

    suspend fun enableAdbPrivateDns(resolver: UpstreamDnsResolver): VaultTransactionResult =
        withContext(Dispatchers.IO) {
            if (!executor.currentTier().satisfies(PrivilegeTier.ADB_SHIZUKU)) {
                return@withContext VaultTransactionResult.Failure(
                    "Режим 2 требует Беспроводной ADB / Shizuku или Root. Без них используйте Режим 1 (Локальный DNS-щит)!",

                )
            }
            vaultManager.applyTransaction(
                title = "AdBlock DoT: ${resolver.titleRu}",
                reason = "Системная фильтрация рекламы без занятия слота VPN (${resolver.dotHostname})",
                source = FeatureSource.ADBLOCK,
                mutations = listOf(
                    VaultMutationSpec.MutateSetting(SettingNamespace.GLOBAL, "private_dns_mode", "hostname"),
                    VaultMutationSpec.MutateSetting(SettingNamespace.GLOBAL, "private_dns_specifier", resolver.dotHostname),
                ),
            )
        }

    suspend fun disableAdbPrivateDns(): VaultTransactionResult =
        vaultManager.applyTransaction(
            title = "Отключение AdBlock Private DNS",
            reason = "Возврат системного DNS в адаптивный режим opportunistic",
            source = FeatureSource.ADBLOCK,
            mutations = listOf(
                VaultMutationSpec.MutateSetting(SettingNamespace.GLOBAL, "private_dns_mode", "opportunistic"),
            ),
        )

    // =========================================================================
    // Mode 3: Root Systemless /etc/hosts (Magisk / KernelSU / APatch)
    // =========================================================================

    suspend fun applyRootSystemlessHosts(): VaultTransactionResult =
        VaultTransactionResult.Failure(
            "Root hosts временно недоступен: нужны отдельный модуль, неизменяемый backup и проверка mount/отката. Файлы системы не изменялись.",
        )

    suspend fun readCurrentStatus(): Triple<Boolean, String?, Boolean> = withContext(Dispatchers.IO) {
        val dnsMode = executor.readSetting(SettingNamespace.GLOBAL, "private_dns_mode")
        val dnsHost = executor.readSetting(SettingNamespace.GLOBAL, "private_dns_specifier")
        val isDoTActive = dnsMode == "hostname" && !dnsHost.isNullOrBlank()
        val isRootHostsPresent = File("/data/adb/modules/hosts/system/etc/hosts").exists() ||
            runCatching { File("/system/etc/hosts").readText().contains("SwagDroid") }.getOrDefault(false)
        Triple(isDoTActive, dnsHost, isRootHostsPresent)
    }

    // =========================================================================
    // Per-App Background Network Firewall (ADB / Root)
    // =========================================================================

    suspend fun setAppBackgroundNetworkBlocked(
        packageName: String,
        appLabel: String,
        blockBackground: Boolean,
    ): VaultTransactionResult = withContext(Dispatchers.IO) {
        if (!Regex("^[a-zA-Z0-9._]+$").matches(packageName)) {
            return@withContext VaultTransactionResult.Failure("Некорректное имя пакета")
        }
        val uidStr = runCatching {
            context.packageManager.getApplicationInfo(packageName, 0).uid.toString()
        }.getOrNull() ?: return@withContext VaultTransactionResult.Failure("Пакет не найден")

        val applyCmd = if (blockBackground) {
            "cmd netpolicy add restrict-background-blacklist $uidStr"
        } else {
            "cmd netpolicy remove restrict-background-blacklist $uidStr"
        }
        val revertCmd = if (blockBackground) {
            "cmd netpolicy remove restrict-background-blacklist $uidStr"
        } else {
            "cmd netpolicy add restrict-background-blacklist $uidStr"
        }

        vaultManager.applyTransaction(
            title = "Фаервол фона: $appLabel",
            reason = if (blockBackground) "Блокировка фонового трафика UID $uidStr" else "Разрешение фонового трафика",
            source = FeatureSource.ADBLOCK,
            mutations = listOf(
                VaultMutationSpec.MutateAppOp(
                    packageName = packageName,
                    opName = "RUN_ANY_IN_BACKGROUND",
                    targetMode = if (blockBackground) "ignore" else "allow",
                ),
                VaultMutationSpec.MutateCustomShell(
                    applyCommand = applyCmd,
                    revertCommand = revertCmd,
                    summaryRu = "netpolicy restrict-background-blacklist UID $uidStr",
                ),
            ),
        )
    }
}
