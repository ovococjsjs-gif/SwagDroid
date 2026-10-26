package com.swagdroid.feature.adblock

import kotlinx.serialization.Serializable

/**
 * Статус обнаружения внешних модулей `bindhosts` / `systemless-hosts` / `AdAway` в Root-окружении
 * и экспериментальная лаборатория `Intent Firewall (IFW)` (`/data/system/ifw`).
 */
@Serializable
enum class ExistingHostsProvider {
    NONE,
    BINDHOSTS_MODULE,
    MAGISK_SYSTEMLESS_HOSTS,
    ADAWAY_INSTALLED,
}

@Serializable
data class BindhostsEnvironmentStatus(
    val provider: ExistingHostsProvider,
    val modulePath: String?,
    val canManageWhitelistSafely: Boolean,
    val builtInCriticalWhitelist: Set<String>,
)

/**
 * Кандидат рекламной активности для экспериментальной Root-лаборатории `Intent Firewall (IFW)`.
 */
@Serializable
data class IfwAdComponentCandidate(
    val targetPackage: String,
    val componentClassName: String,
    val sdkProviderName: String,
    val isSafeToExperiment: Boolean,
    val blockReasonIfUnsafe: String? = null,
)

/**
 * Интегратор `bindhosts` и Экспериментальная лаборатория `Intent Firewall (IFW)`.
 *
 * Ключевые инженерные исправления:
 * 1. Вместо написания собственного хрупкого механизма монтирования `/system/etc/hosts`, обнаруживаем
 *    установленный модуль `bindhosts` / `systemless-hosts` и держим обязательный белый список для
 *    банков, Captive-порталов и проверки лицензий.
 * 2. Вместо массового `pm disable <AdActivity>` (который приложение может само обойти или упасть),
 *    в режиме Root генерируется правило `Intent Firewall` (`/data/system/ifw/swagdroid_<pkg>.xml`)
 *    строго для ОДНОГО выбранного приложения за раз, с жёстким запретом трогать `com.google.android.gms`
 *    и потоки согласия `UMP/Consent`, и с автоматическим откатом при обнаружении падения в `exit-info`!
 */
class BindhostsAndIfwLabEngine {

    /**
     * Генерирует XML-правило Android Intent Firewall (`/data/system/ifw/swagdroid_<pkg>.xml`)
     * только для одного проверенного приложения, блокируя запуск `AdActivity` по Intent без изменения
     * видимого состояния компонента в `PackageManager`.
     */
    fun generateSingleAppIfwRuleXml(
        targetPackage: String,
        adActivityComponents: List<String>,
    ): Result<String> = runCatching {
        require(targetPackage !in FORBIDDEN_IFW_PACKAGES) {
            "Запрещено применять правила Intent Firewall к системному или сервисному пакету '$targetPackage'"
        }
        val safeActivities = adActivityComponents.filter { cls ->
            FORBIDDEN_COMPONENT_SUBSTRINGS.none { forbidden ->
                cls.contains(forbidden, ignoreCase = true)
            }
        }
        require(safeActivities.isNotEmpty()) {
            "Не найдено безопасных для блокировки AdActivity в пакете $targetPackage (компоненты Consent/UMP/GMS защищены)"
        }

        buildString {
            appendLine("<rules>")
            appendLine("  <activity block=\"true\" log=\"true\">")
            for (activityClass in safeActivities) {
                val flatComponent = if (activityClass.contains('/')) {
                    activityClass
                } else {
                    "$targetPackage/$activityClass"
                }
                appendLine("    <component-filter name=\"$flatComponent\" />")
            }
            appendLine("  </activity>")
            appendLine("</rules>")
        }
    }

    /**
     * Проверяет вывод `dumpsys activity exit-info <pkg>` после запуска приложения с правилом IFW.
     * Если зафиксировано падение (`REASON_CRASH` или `REASON_ANR`), возвращает `true` для немедленного авто-отката правила!
     */
    fun didAppCrashAfterIfwRule(
        exitInfoDump: String,
        ruleAppliedAtEpochMs: Long,
    ): Boolean {
        val hasRecentCrash = exitInfoDump.contains("reason=CRASH", ignoreCase = true) ||
            exitInfoDump.contains("reason=ANR", ignoreCase = true) ||
            exitInfoDump.contains("ActivityNotFoundException", ignoreCase = true)
        return hasRecentCrash && ruleAppliedAtEpochMs > 0L
    }

    companion object {
        /**
         * Обязательный белый список доменов, которые никогда не должны блокироваться в `hosts` / DNS,
         * чтобы не ломать проверку Wi-Fi Captive Portal, банковские приложения и магазины.
         */
        val MANDATORY_CRITICAL_WHITELIST_DOMAINS = setOf(
            "connectivitycheck.gstatic.com",
            "clients3.google.com",
            "captive.apple.com",
            "play.googleapis.com",
            "android.clients.google.com",
        )

        /**
         * Пакеты, к которым категорически нельзя применять блокировку компонентов рекламы/сервисов.
         */
        val FORBIDDEN_IFW_PACKAGES = setOf(
            "android",
            "com.google.android.gms",
            "com.google.android.gsf",
            "com.android.vending",
            "com.android.systemui",
            "com.swagdroid.app",
        )

        /**
         * Подстроки классов, которые нельзя блокировать (окна GDPR/UMP согласия, биллинг, авторизация),
         * иначе приложение зависнет на стартовом экране.
         */
        val FORBIDDEN_COMPONENT_SUBSTRINGS = listOf(
            "Consent",
            "Ump",
            "Billing",
            "Auth",
            "SignIn",
            "PlayCore",
        )
    }
}
