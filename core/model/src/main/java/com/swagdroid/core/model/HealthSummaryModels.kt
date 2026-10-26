package com.swagdroid.core.model

enum class SwagTab(val titleRu: String) {
    DASHBOARD("Пульс"),
    SECURITY("Защита"),
    CLEANER("Очистка"),
    OPTIMIZER("Твики"),
    FIXER("Фиксер"),
}

enum class SignalSeverity {
    GOOD,
    ATTENTION,
    CRITICAL,
}

data class HealthSignal(
    val id: String,
    val title: String,
    val detail: String,
    val severity: SignalSeverity,
    val penaltyPoints: Int,
    val targetTab: SwagTab,
)

data class SystemHealthSummary(
    val score: Int,
    val statusLabel: String,
    val subtitle: String,
    val signals: List<HealthSignal>,
)
