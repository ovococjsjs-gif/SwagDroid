package com.swagdroid.feature.dashboard

import com.swagdroid.core.model.DevicePassport
import com.swagdroid.core.model.HealthSignal
import com.swagdroid.core.model.PrivilegeState
import com.swagdroid.core.model.PrivilegeTier
import com.swagdroid.core.model.SignalSeverity
import com.swagdroid.core.model.SnapshotStatus
import com.swagdroid.core.model.SwagTab
import com.swagdroid.core.model.SystemHealthSummary
import com.swagdroid.core.model.VaultSnapshot

object HealthScoreCalculator {

    fun evaluate(
        passport: DevicePassport,
        privilege: PrivilegeState,
        snapshots: List<VaultSnapshot>,
    ): SystemHealthSummary {
        var score = 100
        val signals = ArrayList<HealthSignal>()

        // 1. Storage pressure check
        val usedStoragePct = passport.storage.usedPercent
        when {
            usedStoragePct >= 93 -> {
                score -= 24
                signals += HealthSignal(
                    id = "storage_critical",
                    title = "Хранилище почти заполнено ($usedStoragePct%)",
                    detail = "Свободно менее 7% — скорость флеш-памяти UFS резко падает, возможны сбои установки APK.",
                    severity = SignalSeverity.CRITICAL,
                    penaltyPoints = 24,
                    targetTab = SwagTab.CLEANER,
                )
            }
            usedStoragePct >= 83 -> {
                score -= 10
                signals += HealthSignal(
                    id = "storage_warn",
                    title = "Память заполнена на $usedStoragePct%",
                    detail = "Рекомендуется проверить кэш мессенджеров и остатки удалённых приложений.",
                    severity = SignalSeverity.ATTENTION,
                    penaltyPoints = 10,
                    targetTab = SwagTab.CLEANER,
                )
            }
        }

        // 2. Battery thermal & wear check
        val temp = passport.battery.temperatureCelsius
        when {
            temp >= 43.0f -> {
                score -= 20
                signals += HealthSignal(
                    id = "thermal_critical",
                    title = "Высокий нагрев батареи (${temp}°C)",
                    detail = "Проверьте фоновые майнеры и залипшие WakeLock-процессы.",
                    severity = SignalSeverity.CRITICAL,
                    penaltyPoints = 20,
                    targetTab = SwagTab.SECURITY,
                )
            }
            temp >= 39.0f -> {
                score -= 8
                signals += HealthSignal(
                    id = "thermal_warn",
                    title = "Повышенная температура (${temp}°C)",
                    detail = "Возможна повышенная фоновая нагрузка на процессор.",
                    severity = SignalSeverity.ATTENTION,
                    penaltyPoints = 8,
                    targetTab = SwagTab.FIXER,
                )
            }
        }

        passport.battery.estimatedWearHealthPercent?.let { wearHealth ->
            if (wearHealth < 80) {
                score -= 10
                signals += HealthSignal(
                    id = "battery_wear",
                    title = "Износ аккумулятора (~$wearHealth% от заводской ёмкости)",
                    detail = "Рекомендуется включить оптимизацию фоновых процессов и Doze.",
                    severity = SignalSeverity.ATTENTION,
                    penaltyPoints = 10,
                    targetTab = SwagTab.OPTIMIZER,
                )
            }
        }

        // 3. RAM pressure check
        if (passport.memory.isLowMemory || passport.memory.usedRamPercent >= 92) {
            score -= 10
            signals += HealthSignal(
                id = "ram_pressure",
                title = "Высокая нагрузка на ОЗУ (${passport.memory.usedRamPercent}%)",
                detail = "Заморозка ненужного системного bloatware освободит память без вреда для батареи.",
                severity = SignalSeverity.ATTENTION,
                penaltyPoints = 10,
                targetTab = SwagTab.OPTIMIZER,
            )
        }

        // 4. Privilege readiness check
        if (privilege.activeTier == PrivilegeTier.BASIC) {
            score -= 8
            signals += HealthSignal(
                id = "privilege_basic",
                title = "Суперправа ADB / Root ещё не активированы",
                detail = "Включите Беспроводной ADB + Shizuku за 45 сек прямо на телефоне, чтобы открыть 90% системных функций без потери банков.",
                severity = SignalSeverity.ATTENTION,
                penaltyPoints = 8,
                targetTab = SwagTab.DASHBOARD,
            )
        }

        // 5. Baseline restore point check
        val hasActiveCheckpoint = snapshots.any { it.status == SnapshotStatus.ACTIVE }
        if (!hasActiveCheckpoint) {
            score -= 4
            signals += HealthSignal(
                id = "no_checkpoint",
                title = "Нет базовой точки восстановления StateVault",
                detail = "Создайте стартовый слепок настроек в 1 нажатие перед применением твиков.",
                severity = SignalSeverity.ATTENTION,
                penaltyPoints = 4,
                targetTab = SwagTab.DASHBOARD,
            )
        }

        val finalScore = score.coerceIn(0, 100)
        val statusLabel = when {
            finalScore >= 88 -> "Отличное состояние"
            finalScore >= 70 -> "Хорошо · есть резерв"
            finalScore >= 50 -> "Требует внимания"
            else -> "Нужна диагностика"
        }

        val subtitle = if (signals.isEmpty()) {
            "Все ключевые метрики устройства в норме, защита откатов активна."
        } else {
            "Обнаружено рекомендаций для улучшения: ${signals.size}"
        }

        return SystemHealthSummary(
            score = finalScore,
            statusLabel = statusLabel,
            subtitle = subtitle,
            signals = signals,
        )
    }
}
