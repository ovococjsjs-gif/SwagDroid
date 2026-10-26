package com.swagdroid.feature.fixer

import kotlinx.serialization.Serializable

/**
 * Домен `TelephonyDoctor`: диагностика SIM, eSIM, мобильной передачи данных, APN, VoLTE/VoWiFi и SMS
 * с обязательным маскированием чувствительных идентификаторов (IMSI, ICCID, EID, телефонный номер, Cell ID).
 */
@Serializable
enum class SimDiagnosticVerdict(val titleRu: String) {
    HEALTHY("Слот SIM и передача данных работают штатно"),
    PHYSICAL_SIM_CARD_FAULT("Вероятная неисправность самой SIM-карты (не видна ни в одном слоте — программного фикса нет)"),
    PHYSICAL_SIM_TRAY_OR_READER_FAULT("Вероятная неисправность SIM-лотка/коннектора телефона (во втором слоте карта работает)"),
    APN_OR_DEFAULT_DATA_SUB_MISCONFIGURED("Голосовая связь есть, но сбит APN или подписка для мобильных данных (исправимо)"),
    CARRIER_IMS_PROVISIONING_REQUIRED("VoLTE/VoWiFi включён на телефоне, но оператор не подтверждает IMS-регистрацию"),
    DSDS_MODEM_LIMITATION_EXPLAINED("Ограничение модема Dual SIM Dual Standby (DSDS): во время звонка/передачи на SIM 1 вторая карта временно недоступна"),
}

@Serializable
data class SimSlotSnapshot(
    val slotIndex: Int,
    val isSimPresent: Boolean,
    val isEmbeddedEsim: Boolean,
    val carrierName: String?,
    val voiceRegistered: Boolean,
    val dataRegistered: Boolean,
    val imsVolteRegistered: Boolean,
    val isDefaultDataSubscription: Boolean,
    val hasValidPreferredApn: Boolean,
)

class TelephonyDoctorEngine {

    /**
     * Локализует проблему SIM / мобильного интернета без выдумывания фейковых prop-твиков.
     */
    fun diagnoseSimAndDataHealth(
        slot1: SimSlotSnapshot,
        slot2: SimSlotSnapshot?,
        didUserSwapSlotsForTest: Boolean = false,
        simWorkedInOtherSlotAfterSwap: Boolean = false,
    ): SimDiagnosticVerdict {
        if (!slot1.isSimPresent && (slot2 == null || !slot2.isSimPresent)) {
            return if (didUserSwapSlotsForTest && simWorkedInOtherSlotAfterSwap) {
                SimDiagnosticVerdict.PHYSICAL_SIM_TRAY_OR_READER_FAULT
            } else {
                SimDiagnosticVerdict.PHYSICAL_SIM_CARD_FAULT
            }
        }

        val activeDataSlot = listOfNotNull(slot1, slot2).firstOrNull { it.isDefaultDataSubscription } ?: slot1
        if (activeDataSlot.voiceRegistered && (!activeDataSlot.dataRegistered || !activeDataSlot.hasValidPreferredApn)) {
            return SimDiagnosticVerdict.APN_OR_DEFAULT_DATA_SUB_MISCONFIGURED
        }

        if (activeDataSlot.voiceRegistered && !activeDataSlot.imsVolteRegistered) {
            return SimDiagnosticVerdict.CARRIER_IMS_PROVISIONING_REQUIRED
        }

        return SimDiagnosticVerdict.HEALTHY
    }

    /**
     * Жёстко маскирует персональные и локационные идентификаторы (IMSI, ICCID, EID, номер телефона, Cell ID)
     * перед сохранением или экспортом любого диагностического отчёта `TelephonyDoctor`.
     */
    fun maskSensitiveTelephonyReport(rawReport: String): String {
        var scrubbed = rawReport
        // 1. Маскируем ICCID (18–20 цифр, начинается с 89)
        scrubbed = Regex("\\b89\\d{16,18}\\b").replace(scrubbed) { match ->
            "${match.value.take(5)}***REDACTED_ICCID***${match.value.takeLast(2)}"
        }
        // 2. Маскируем EID eSIM (32 цифры, начинается с 89)
        scrubbed = Regex("\\b89\\d{30}\\b").replace(scrubbed) { match ->
            "${match.value.take(6)}***REDACTED_EID***"
        }
        // 3. Маскируем IMSI (15 цифр)
        scrubbed = Regex("\\b\\d{15}\\b").replace(scrubbed) { match ->
            "${match.value.take(5)}***REDACTED_IMSI***"
        }
        // 4. Маскируем телефонные номера (+7..., +1..., +380... и др.)
        scrubbed = Regex("(\\+\\d{1,3})\\d{6,9}(\\d{2})").replace(scrubbed) { match ->
            "${match.groupValues[1]}***REDACTED_PHONE***${match.groupValues[2]}"
        }
        // 5. Маскируем Cell ID и LAC/TAC (данные геолокации базовой станции)
        scrubbed = Regex("(?i)(mCid|cid|mTac|tac|mLac|lac)=([0-9a-fA-Fx]+)").replace(scrubbed) { match ->
            "${match.groupValues[1]}=***REDACTED_CELL***"
        }
        return scrubbed
    }
}
