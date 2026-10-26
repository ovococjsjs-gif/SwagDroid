package com.swagdroid.feature.adblock.model

import com.swagdroid.core.model.PrivilegeTier

enum class AdblockEngineMode(
    val titleRu: String,
    val subtitleRu: String,
    val requiredTier: PrivilegeTier,
) {
    LOCAL_DNS_VPN(
        titleRu = "Режим 1 · Локальный DNS-щит (Без Root)",
        subtitleRu = "Маршрутизирует в туннель только UDP:53 DNS-запросы (<0.5% батареи, 100% скорость трафика)",
        requiredTier = PrivilegeTier.BASIC,
    ),
    ADB_PRIVATE_DNS(
        titleRu = "Режим 2 · Системный DoT (ADB / Shizuku)",
        subtitleRu = "Нативный Private DNS Android — блокирует рекламу и оставляет слот VPN свободным!",
        requiredTier = PrivilegeTier.ADB_SHIZUKU,
    ),
    ROOT_SYSTEMLESS_HOSTS(
        titleRu = "Режим 3 · Системный /etc/hosts (Root)",
        subtitleRu = "Нулевое потребление ОЗУ и батареи через Magisk / KernelSU systemless hosts",
        requiredTier = PrivilegeTier.ROOT,
    ),
}

enum class UpstreamDnsResolver(
    val titleRu: String,
    val ipv4Address: String,
    val dotHostname: String,
) {
    ADGUARD("AdGuard DNS (Анти-реклама + Трекеры)", "94.140.14.14", "dns.adguard-dns.com"),
    CONTROLD("ControlD Free (Реклама + Вредоносы)", "76.76.2.2", "p2.freedns.controld.com"),
    CLOUDFLARE("Cloudflare 1.1.1.1 (Чистый резолвер)", "1.1.1.1", "one.one.one.one"),
    QUAD9("Quad9 Security (Защита от фишинга)", "9.9.9.9", "dns.quad9.net"),
}

data class DnsQueryLogEntry(
    val timestampEpochMs: Long,
    val domain: String,
    val wasBlocked: Boolean,
)

data class FirewallAppRule(
    val packageName: String,
    val appLabel: String,
    val blockBackgroundNetwork: Boolean,
    val bypassLocalVpnFilter: Boolean = false,
)

data class AdblockRuntimeStatus(
    val selectedMode: AdblockEngineMode = AdblockEngineMode.LOCAL_DNS_VPN,
    val upstreamResolver: UpstreamDnsResolver = UpstreamDnsResolver.ADGUARD,
    val isLocalVpnRunning: Boolean = false,
    val isPrivateDnsActive: Boolean = false,
    val activePrivateDnsHost: String? = null,
    val isRootHostsInstalled: Boolean = false,
    val loadedBlockRulesCount: Int = 0,
    val blockedQueriesSessionCount: Long = 0L,
    val customBlockedDomains: Set<String> = emptySet(),
    val customAllowedDomains: Set<String> = emptySet(),
    val recentDnsLog: List<DnsQueryLogEntry> = emptyList(),
)
