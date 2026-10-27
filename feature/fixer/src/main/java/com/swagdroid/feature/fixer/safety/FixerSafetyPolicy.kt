package com.swagdroid.feature.fixer.safety

/** Temporary containment until these actions have scoped plans, evidence and explicit consent. */
object FixerSafetyPolicy {
    const val PACKAGE_REMNANTS = "pkg_ghost_uninstalled_conflict"
    const val INSTALL_SESSIONS = "stor_abandoned_install_sessions"
    const val USER_CERTIFICATES = "sec_rogue_mitm_ca_audit"
    const val ACCESSIBILITY_SERVICES = "sec_accessibility_scroll_stutter_audit"

    fun automaticFixBlockReason(playbookId: String): String? = when (playbookId) {
        USER_CERTIFICATES ->
            "Только ручная проверка сертификатов в системных настройках. Приложение не подтверждает отсутствие CA при закрытом доступе и не удаляет сертификаты автоматически."
        PACKAGE_REMNANTS ->
            "Только проверка. Разность списков пакетов не доказывает конфликт или ненужность данных. " +
                "Автоматическое массовое удаление отключено: нужны конкретный пакет, профиль пользователя и подтверждение потери данных."
        INSTALL_SESSIONS ->
            "Только проверка. Среди сессий могут быть действующие или ожидающие установки. " +
                "Автоматическая отмена всех сессий отключена до проверки их состояния."
        ACCESSIBILITY_SERVICES ->
            "Только проверка. Службы доступности могут быть необходимы для управления телефоном. " +
                "SwagDroid не отключает их автоматически. Проверьте нужные службы в системных настройках."
        else -> null
    }
}
