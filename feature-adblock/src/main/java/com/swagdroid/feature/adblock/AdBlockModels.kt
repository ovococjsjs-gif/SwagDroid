package com.swagdroid.feature.adblock

import com.swagdroid.api.PrivilegeTier
import kotlinx.serialization.Serializable

/**
 * Честные альтернативные режимы работы блокировщика рекламы.
 * Вместо мифа об «одновременно работающем трёхслойном пироге» пользователь выбирает основной сетевой режим:
 * - либо `PRIVATE_DNS_DOT` (не занимает слот VPN, 0% фонового расхода, но без per-app логов),
 * - либо `LOCAL_VPN_FIREWALL` (даёт per-app правила, белый список приложений и живой лог доменов для анти-майнера, но занимает слот VPN),
 * - а при наличии Root дополнительно доступна интеграция с модулем `bindhosts`.
 */
@Serializable
enum class AdBlockPrimaryMode(
    val titleRu: String,
    val prosRu: String,
    val consAndLimitationsRu: String,
    val minPrivilege: PrivilegeTier,
) {
    DISABLED(
        titleRu = "Выключен",
        prosRu = "Штатная работа сети без фильтрации",
        consAndLimitationsRu = "Реклама и трекеры не блокируются",
        minPrivilege = PrivilegeTier.NONE,
    ),
    PRIVATE_DNS_DOT(
        titleRu = "Режим 1: Системный Private DNS (DNS-over-TLS)",
        prosRu = "Не занимает слот VPN • Нет фонового сервиса • После первой выдачи WRITE_SECURE_SETTINGS работает даже без запущенного сервера",
        consAndLimitationsRu = "Не даёт per-app исключений • Обходится встроенным DoH браузера • Не убирает пустые рамки • При падении DoT-сервера блокирует сеть (защищено авто-откатом за 15 сек)",
        minPrivilege = PrivilegeTier.ADB_SHELL,
    ),
    LOCAL_VPN_FIREWALL(
        titleRu = "Режим 2: Локальный VPN-фильтр (Per-App + Журнал доменов)",
        prosRu = "Работает вообще без Root и ADB • Живой журнал «какое приложение куда стучится» (питает анти-майнер) • Белый список для любого сломавшегося приложения в 1 клик",
        consAndLimitationsRu = "Занимает единственный системный слот VPN • Требует работы Foreground-сервиса",
        minPrivilege = PrivilegeTier.NONE,
    ),
    ROOT_BINDHOSTS_INTEGRATION(
        titleRu = "Режим 3 (Root): Интеграция с bindhosts / systemless-hosts",
        prosRu = "Системная фильтрация на уровне резолвера без занятия слота VPN и без зависимости от внешнего DoT-сервера",
        consAndLimitationsRu = "Требует установленного модуля bindhosts / systemless-hosts в Magisk/KernelSU/APatch • Нет per-app аналитики",
        minPrivilege = PrivilegeTier.ROOT,
    ),
}

/**
 * Профиль провайдера Private DNS (DoT).
 * Важно: для NextDNS пользователь обязан ввести СВОЙ собственный Config ID (в приложение не зашивается чужой ID!).
 */
@Serializable
sealed class PrivateDnsProviderProfile {
    abstract val id: String
    abstract val displayNameRu: String
    abstract fun resolveDotHostname(): String

    @Serializable
    data object AdGuardDefault : PrivateDnsProviderProfile() {
        override val id: String = "adguard_dns"
        override val displayNameRu: String = "AdGuard DNS (Реклама и трекеры)"
        override fun resolveDotHostname(): String = "dns.adguard-dns.com"
    }

    @Serializable
    data object ControlDFree : PrivateDnsProviderProfile() {
        override val id: String = "controld_ads"
        override val displayNameRu: String = "Control D (Ads & Trackers)"
        override fun resolveDotHostname(): String = "p2.freedns.controld.com"
    }

    @Serializable
    data object MullvadAdblock : PrivateDnsProviderProfile() {
        override val id: String = "mullvad_adblock"
        override val displayNameRu: String = "Mullvad Adblock DNS"
        override fun resolveDotHostname(): String = "adblock.dns.mullvad.net"
    }

    @Serializable
    data class NextDnsCustom(val userConfigId: String) : PrivateDnsProviderProfile() {
        override val id: String = "nextdns_custom"
        override val displayNameRu: String = "NextDNS (Персональный профиль пользователя)"
        override fun resolveDotHostname(): String {
            val cleanId = userConfigId.trim().lowercase()
            require(cleanId.matches(NEXTDNS_ID_REGEX)) {
                "Некорректный ID конфигурации NextDNS '$userConfigId'. Введите ваш 6-символьный ID с my.nextdns.io."
            }
            return "$cleanId.dns.nextdns.io"
        }
    }

    @Serializable
    data class CustomDotHost(val hostname: String) : PrivateDnsProviderProfile() {
        override val id: String = "custom_dot"
        override val displayNameRu: String = "Свой DoT-сервер ($hostname)"
        override fun resolveDotHostname(): String {
            val norm = hostname.trim().lowercase()
            require(isValidDotHostname(norm)) { "Некорректное доменное имя DoT: $hostname" }
            return norm
        }
    }

    companion object {
        private val NEXTDNS_ID_REGEX = Regex("^[a-z0-9]{5,10}$")
        private val RFC1123_HOSTNAME_REGEX =
            Regex("^(?=.{1,253}$)(?:(?!-)[a-z0-9-]{1,63}(?<!-)\\.)+[a-z]{2,63}$")

        fun isValidDotHostname(hostname: String): Boolean =
            hostname.trim().lowercase().matches(RFC1123_HOSTNAME_REGEX)
    }
}
