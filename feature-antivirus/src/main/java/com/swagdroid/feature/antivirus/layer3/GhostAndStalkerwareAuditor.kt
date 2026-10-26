package com.swagdroid.feature.antivirus.layer3

import com.swagdroid.api.SwagDroidIpcContract
import com.swagdroid.feature.antivirus.model.DetectorType
import com.swagdroid.feature.antivirus.model.EvidenceFact
import com.swagdroid.feature.antivirus.model.Finding
import com.swagdroid.feature.antivirus.model.FindingConfidence
import com.swagdroid.feature.antivirus.model.RecommendedAction
import com.swagdroid.feature.antivirus.model.ScannedEntity
import com.swagdroid.feature.antivirus.model.VerdictLabel
import kotlinx.serialization.Serializable
import java.util.UUID

/**
 * Срез реально активных в системе опасных полномочий пакета (не просто строк в манифесте!).
 */
@Serializable
data class AppRuntimeRiskProfile(
    val packageName: String,
    val uid: Int,
    val versionName: String,
    val versionCode: Long,
    val isSystemApp: Boolean,
    val installerPackageName: String?,
    val signingCertSha256: String?,
    val baseApkPath: String?,
    val baseApkSha256: String?,
    /** Нет видимой иконки в лаунчере прямо сейчас. */
    val hasVisibleLauncherIcon: Boolean,
    /** В манифесте есть LAUNCHER-активность, но приложение само отключило её через PackageManager! */
    val launcherComponentExplicitlyDisabled: Boolean,
    /** Реально включён в Настройки -> Специальные возможности. */
    val isAccessibilityServiceEnabled: Boolean,
    /** Реально активен в Администраторах устройства. */
    val isDeviceAdminEnabled: Boolean,
    /** Реально включён доступ к чтению всех уведомлений. */
    val isNotificationListenerEnabled: Boolean,
    /** Реально разрешён показ поверх других окон (AppOps SYSTEM_ALERT_WINDOW == MODE_ALLOWED). */
    val isOverlayAllowed: Boolean,
    /** Разрешена установка неизвестных APK (REQUEST_INSTALL_PACKAGES). */
    val canRequestPackageInstalls: Boolean,
    /** Выданы runtime-разрешения на чтение SMS / журнала звонков / микрофона / геолокации в фоне. */
    val grantedSensitivePermissions: Set<String> = emptySet(),
)

/**
 * Событие недавней активности приложения из `UsageStatsManager` для кнопки «Реклама появилась только что!».
 */
@Serializable
data class RecentForegroundEvent(
    val packageName: String,
    val className: String?,
    val lastForegroundTimestampEpochMs: Long,
)

/**
 * Запись индикатора компрометации (IOC) из открытых фидов (`Echap/stalkerware-indicators` CC-BY, `ESET malware-ioc`).
 */
@Serializable
data class StalkerwareIocRecord(
    val packageName: String,
    val familyName: String,
    val certSha256: String? = null,
    val sourceFeed: String = "Echap/stalkerware-indicators (CC-BY)",
)

/**
 * Слой 3: Точный IOC-детектор Stalkerware + Объяснимый аудитор «Приложений-призраков» +
 * Детектор «Реклама появилась только что!».
 */
class GhostAndStalkerwareAuditor(
    customIocRecords: List<StalkerwareIocRecord> = DEFAULT_STALKERWARE_IOCS,
) {
    private val iocByPackage = customIocRecords.associateBy { it.packageName.lowercase() }
    private val iocByCert = customIocRecords
        .filter { !it.certSha256.isNullOrBlank() }
        .associateBy { it.certSha256!!.lowercase() }

    /**
     * Проверяет профиль установленного приложения:
     * 1. Сначала сверяет с точными IOC (`Echap/stalkerware-indicators`) -> `CONFIRMED` (`KNOWN_THREAT`).
     * 2. Затем выполняет прозрачный, объяснимый аудит конфигурации доступов -> `RISKY_ACCESS_CONFIGURATION`.
     */
    fun auditAppProfile(profile: AppRuntimeRiskProfile): Finding? {
        if (profile.packageName == SwagDroidIpcContract.HOST_PACKAGE_NAME) return null

        val entity = ScannedEntity.InstalledApp(
            packageName = profile.packageName,
            uid = profile.uid,
            versionName = profile.versionName,
            versionCode = profile.versionCode,
            installerPackageName = profile.installerPackageName,
            signingCertSha256 = profile.signingCertSha256,
            baseApkPath = profile.baseApkPath,
            baseApkSha256 = profile.baseApkSha256,
        )

        // 1. Проверка точного совпадения по базе Stalkerware / Trojan IOC
        val matchedIoc = iocByPackage[profile.packageName.lowercase()]
            ?: profile.signingCertSha256?.lowercase()?.let { iocByCert[it] }

        if (matchedIoc != null) {
            return Finding(
                findingId = UUID.randomUUID().toString(),
                entity = entity,
                detector = DetectorType.STALKERWARE_IOC,
                confidence = FindingConfidence.CONFIRMED,
                verdictLabel = VerdictLabel.KNOWN_THREAT,
                evidence = listOf(
                    EvidenceFact(
                        code = "STALKERWARE_IOC_MATCH",
                        titleRu = "Пакет или сертификат входит в базу шпионского ПО: ${matchedIoc.familyName}",
                        technicalDetail = "Пакет: ${profile.packageName} • Источник IOC: ${matchedIoc.sourceFeed}",
                        weight = 100,
                    )
                ),
                recommendedAction = RecommendedAction.QUARANTINE,
            )
        }

        // Системные пакеты не помечаем эвристикой «призраков» без точного IOC
        if (profile.isSystemApp) return null

        // 2. Объяснимый аудит рискованной конфигурации доступов
        val evidence = mutableListOf<EvidenceFact>()
        val isSideloaded = profile.installerPackageName == null ||
            profile.installerPackageName !in TRUSTED_STORE_INSTALLERS

        if (profile.launcherComponentExplicitlyDisabled) {
            evidence += EvidenceFact(
                code = "LAUNCHER_ICON_INTENTIONALLY_HIDDEN",
                titleRu = "Приложение намеренно скрыло свою иконку из меню после установки",
                technicalDetail = "В манифесте присутствует LAUNCHER-активность, но её состояние переведено в COMPONENT_ENABLED_STATE_DISABLED",
                weight = 45,
            )
        } else if (!profile.hasVisibleLauncherIcon && isSideloaded) {
            evidence += EvidenceFact(
                code = "NO_LAUNCHER_ICON_SIDELOADED",
                titleRu = "Установлено вручную (не из магазина) и не имеет иконки на рабочем столе",
                technicalDetail = "Установщик: ${profile.installerPackageName ?: "неизвестен (APK вручную)"}",
                weight = 25,
            )
        }

        if (profile.isAccessibilityServiceEnabled) {
            evidence += EvidenceFact(
                code = "ACTIVE_ACCESSIBILITY_SERVICE",
                titleRu = "Активен полный доступ к экрану и нажатиям (Accessibility Service)",
                technicalDetail = "Позволяет читать содержимое любых окон и автоматически нажимать кнопки",
                weight = 35,
            )
        }

        if (profile.isDeviceAdminEnabled) {
            evidence += EvidenceFact(
                code = "ACTIVE_DEVICE_ADMIN",
                titleRu = "Активированы права Администратора устройства (препятствует обычному удалению)",
                technicalDetail = "DevicePolicyManager: активный DeviceAdminReceiver",
                weight = 35,
            )
        }

        if (profile.isNotificationListenerEnabled) {
            evidence += EvidenceFact(
                code = "ACTIVE_NOTIFICATION_LISTENER",
                titleRu = "Включено чтение всех входящих уведомлений (включая коды банков и мессенджеры)",
                technicalDetail = "NotificationListenerService активен",
                weight = 25,
            )
        }

        if (profile.isOverlayAllowed && (!profile.hasVisibleLauncherIcon || profile.launcherComponentExplicitlyDisabled)) {
            evidence += EvidenceFact(
                code = "HIDDEN_APP_WITH_OVERLAY",
                titleRu = "Скрытое приложение с правом рисовать окна и рекламу поверх экрана",
                technicalDetail = "AppOps SYSTEM_ALERT_WINDOW = MODE_ALLOWED при отсутствии иконки лаунчера",
                weight = 45,
            )
        }

        if (profile.canRequestPackageInstalls && isSideloaded) {
            evidence += EvidenceFact(
                code = "SIDELOADED_DROPPER_CAPABILITY",
                titleRu = "Приложение установлено не из магазина и может устанавливать другие APK",
                technicalDetail = "REQUEST_INSTALL_PACKAGES разрешён",
                weight = 20,
            )
        }

        val totalRiskScore = evidence.sumOf { it.weight }
        if (totalRiskScore < 45) return null

        val confidence = if (totalRiskScore >= 75) FindingConfidence.PROBABLE else FindingConfidence.WEAK
        val action = if (profile.launcherComponentExplicitlyDisabled && profile.isOverlayAllowed) {
            RecommendedAction.QUARANTINE
        } else {
            RecommendedAction.REVIEW
        }

        return Finding(
            findingId = UUID.randomUUID().toString(),
            entity = entity,
            detector = DetectorType.GHOST_AND_ACCESS_AUDIT,
            confidence = confidence,
            verdictLabel = VerdictLabel.RISKY_ACCESS_CONFIGURATION,
            evidence = evidence,
            recommendedAction = action,
        )
    }

    /**
     * Функция «Реклама появилась только что!»:
     * Сопоставляет последние переходы приложений в foreground за последние `windowMinutes` минут
     * с профилями приложений (скрытая иконка, разрешение `SYSTEM_ALERT_WINDOW`, установка вне магазина).
     */
    fun findRecentAdPopupCulprits(
        recentEvents: List<RecentForegroundEvent>,
        installedProfilesByPkg: Map<String, AppRuntimeRiskProfile>,
        currentDefaultLauncher: String?,
        nowEpochMs: Long = System.currentTimeMillis(),
        windowMinutes: Int = 3,
    ): List<Finding> {
        val cutoffEpochMs = nowEpochMs - windowMinutes * 60_000L
        val recentByPkg = recentEvents
            .filter {
                it.lastForegroundTimestampEpochMs >= cutoffEpochMs &&
                    it.packageName != SwagDroidIpcContract.HOST_PACKAGE_NAME &&
                    it.packageName != currentDefaultLauncher &&
                    it.packageName != "com.android.systemui"
            }
            .groupBy { it.packageName }

        val findings = mutableListOf<Finding>()
        for ((pkg, events) in recentByPkg) {
            val profile = installedProfilesByPkg[pkg] ?: continue
            if (profile.isSystemApp) continue

            val latestEvent = events.maxByOrNull { it.lastForegroundTimestampEpochMs } ?: continue
            val secondsAgo = ((nowEpochMs - latestEvent.lastForegroundTimestampEpochMs) / 1000L).coerceAtLeast(0L)
            val evidence = mutableListOf(
                EvidenceFact(
                    code = "RECENT_FOREGROUND_POPUP",
                    titleRu = "Активность на экране зафиксирована $secondsAgo сек. назад",
                    technicalDetail = "Пакет: $pkg • Активность: ${latestEvent.className ?: "неизвестно"}",
                    weight = 50,
                )
            )
            if (profile.launcherComponentExplicitlyDisabled || !profile.hasVisibleLauncherIcon) {
                evidence += EvidenceFact(
                    code = "CULPRIT_HAS_NO_LAUNCHER_ICON",
                    titleRu = "У приложения отсутствует иконка в меню (маскируется от пользователя)",
                    technicalDetail = "launcherComponentExplicitlyDisabled=${profile.launcherComponentExplicitlyDisabled}",
                    weight = 40,
                )
            }
            if (profile.isOverlayAllowed) {
                evidence += EvidenceFact(
                    code = "CULPRIT_HAS_OVERLAY_PERMISSION",
                    titleRu = "Приложению разрешён показ поверх других окон (SYSTEM_ALERT_WINDOW)",
                    technicalDetail = "Может выводить полноэкранные баннеры поверх любых приложений",
                    weight = 30,
                )
            }

            val entity = ScannedEntity.InstalledApp(
                packageName = profile.packageName,
                uid = profile.uid,
                versionName = profile.versionName,
                versionCode = profile.versionCode,
                installerPackageName = profile.installerPackageName,
                signingCertSha256 = profile.signingCertSha256,
                baseApkPath = profile.baseApkPath,
                baseApkSha256 = profile.baseApkSha256,
            )
            findings += Finding(
                findingId = UUID.randomUUID().toString(),
                entity = entity,
                detector = DetectorType.RECENT_AD_POPUP_CULPRIT,
                confidence = if (evidence.sumOf { it.weight } >= 80) {
                    FindingConfidence.PROBABLE
                } else {
                    FindingConfidence.WEAK
                },
                verdictLabel = VerdictLabel.RISKY_ACCESS_CONFIGURATION,
                evidence = evidence,
                recommendedAction = RecommendedAction.QUARANTINE,
            )
        }
        return findings
    }

    companion object {
        val TRUSTED_STORE_INSTALLERS = setOf(
            "com.android.vending",             // Google Play Store
            "org.fdroid.fdroid",               // F-Droid
            "com.looker.droidify",             // Droid-ify
            "com.sec.android.app.samsungapps", // Samsung Galaxy Store
            "ru.vk.store",                     // RuStore
            "com.huawei.appmarket",            // Huawei AppGallery
        )

        val DEFAULT_STALKERWARE_IOCS = listOf(
            StalkerwareIocRecord("com.SPY.mobile", "MobileTracker / Stalkerware"),
            StalkerwareIocRecord("com.Hoverwatch.app", "Hoverwatch Stalkerware"),
            StalkerwareIocRecord("com.mspy.lite", "mSpy Stalkerware"),
            StalkerwareIocRecord("com.flexispy.main", "FlexiSPY Stalkerware"),
            StalkerwareIocRecord("com.cerberus.anti.theft.hidden", "Hidden Cerberus Abuse"),
            StalkerwareIocRecord("com. hidden.adware.dropper", "HiddenAd.Dropper"),
        )
    }
}
