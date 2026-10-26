package com.swagdroid.feature.optimizer.model

import com.swagdroid.core.model.PackageStateTarget
import com.swagdroid.core.model.RiskLevel
import kotlinx.serialization.Serializable

enum class OptimizerSubTab(val titleRu: String) {
    PRESETS("Пресеты"),
    TWEAKS("Твики"),
    DEBLOATER("Debloater"),
}

@Serializable
data class UadCatalogEntry(
    val id: String,
    val list: String,
    val removal: String,
    val desc: String,
    val ru: Boolean = false,
) {
    val riskLevel: RiskLevel
        get() = when (removal) {
            "Recommended" -> RiskLevel.SAFE
            "Advanced" -> RiskLevel.ADVANCED
            else -> RiskLevel.EXPERT
        }

    val removalLabelRu: String
        get() = when (removal) {
            "Recommended" -> "Рекомендуется"
            "Advanced" -> "Продвинуто"
            else -> "Эксперт"
        }

    val listLabelRu: String
        get() = when (list) {
            "Oem" -> "Прошивка OEM"
            "Google" -> "Google"
            "Carrier" -> "Оператор"
            "Aosp" -> "AOSP Система"
            else -> "Стороннее / Meta"
        }
}

data class DebloatAppItem(
    val entry: UadCatalogEntry,
    val appLabel: String,
    val isInstalledOnDevice: Boolean,
    val stateTarget: PackageStateTarget,
    val isSelected: Boolean = false,
)

enum class AnimationScaleChoice(val scaleText: String, val labelRu: String) {
    SCALE_100("1.0", "1.0x Стандарт"),
    SCALE_075("0.75", "0.75x Плавно"),
    SCALE_050("0.5", "0.5x Быстро"),
    SCALE_025("0.25", "0.25x Молния"),
}

enum class PrivateDnsPreset(
    val titleRu: String,
    val subtitleRu: String,
    val modeValue: String,
    val hostnameValue: String?,
) {
    SYSTEM_AUTO(
        titleRu = "Автоматический (системный)",
        subtitleRu = "Совместим с метро/отелями, без фильтрации рекламы",
        modeValue = "opportunistic",
        hostnameValue = null,
    ),
    ADGUARD_ADS(
        titleRu = "AdGuard DNS (Блокировка рекламы)",
        subtitleRu = "dns.adguard-dns.com · Режет рекламу и трекеры без занятия слота VPN",
        modeValue = "hostname",
        hostnameValue = "dns.adguard-dns.com",
    ),
    CONTROLD_ADS(
        titleRu = "ControlD Free (Анти-реклама и малварь)",
        subtitleRu = "p2.freedns.controld.com · Лёгкий фильтр рекламы и фишинга по DoT",
        modeValue = "hostname",
        hostnameValue = "p2.freedns.controld.com",
    ),
    CLOUDFLARE_FAST(
        titleRu = "Cloudflare 1.1.1.1 (Минимальный пинг)",
        subtitleRu = "one.one.one.one · Быстрый зашифрованный DNS для игр",
        modeValue = "hostname",
        hostnameValue = "one.one.one.one",
    ),
    QUAD9_SECURITY(
        titleRu = "Quad9 Security (Анти-фишинг)",
        subtitleRu = "dns.quad9.net · Блокировка вредоносных и ботнет-доменов",
        modeValue = "hostname",
        hostnameValue = "dns.quad9.net",
    ),
}

data class LiveSystemTweaksState(
    val animationScale: String = "1.0",
    val privateDnsMode: String = "opportunistic",
    val privateDnsHost: String? = null,
    val cachedAppsFreezerEnabled: Boolean = false,
    val phantomKillerDisabled: Boolean = false,
    val hiddenRadioScanDisabled: Boolean = false,
    val batteryPercentInStatusBar: Boolean = false,
    val errorTelemetryDisabled: Boolean = false,
)

data class OptimizerPresetTemplate(
    val id: String,
    val titleRu: String,
    val subtitleRu: String,
    val bulletPoints: List<String>,
    val risk: RiskLevel,
    val activeSnapshotId: Long? = null,
)
