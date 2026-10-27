package com.swagdroid.core.privilege

import com.swagdroid.core.model.CommandCompletion
import com.swagdroid.core.model.isDifferentBoot
import com.swagdroid.core.model.isValidBootId
import java.util.UUID

/** Persisted BEFORE starting a process; absence is the only idle state. */
data class CommandTicket(val id: String, val bootId: String)

interface CommandFenceStore {
    fun read(): CommandTicket?
    /** Implementations must commit synchronously or throw; apply()/fire-and-forget is unsafe here. */
    fun write(ticket: CommandTicket?)
}

/** Caller serializes begin/finish with the command lane's mutex. Survives app/process restart. */
class CommandExecutionFence(
    private val store: CommandFenceStore,
    private val bootId: () -> String?,
) {
    private var ownedTicket: String? = null
    private var persistenceError = false

    @Synchronized
    fun blockingReason(): String? = try {
        check(!persistenceError) { "Результат записи журнала не подтверждён" }
        val pending = store.read()
        val boot = bootId()
        when {
            pending == null -> if (!isValidBootId(boot)) "Не удалось подтвердить загрузку ядра; нужен доступный Shizuku/Root для чтения boot_id" else null
            isDifferentBoot(pending.bootId, boot) -> {
                store.write(null)
                ownedTicket = null
                null
            }
            ownedTicket == pending.id -> "Другая команда ещё выполняется. Дождитесь её завершения."
            else -> "Завершение предыдущей команды не подтверждено. Перезагрузите устройство перед новыми командами или откатом."
        }
    } catch (_: Exception) {
        "Не удалось прочитать или обновить защитный журнал команд. Выполнение заблокировано."
    }

    @Synchronized
    fun begin(): CommandTicket {
        val blocker = blockingReason()
        check(blocker == null) { blocker.orEmpty() }
        val boot = checkNotNull(bootId()) { "Загрузка устройства неизвестна" }
        val ticket = CommandTicket(UUID.randomUUID().toString(), boot)
        try { store.write(ticket) }
        catch (error: Exception) { persistenceError = true; throw error }
        ownedTicket = ticket.id
        return ticket
    }

    @Synchronized
    fun finish(ticket: CommandTicket, completion: CommandCompletion) {
        try {
            check(store.read() == ticket) { "Защитный журнал команды изменился" }
            if (completion != CommandCompletion.UNKNOWN) store.write(null)
        } catch (error: Exception) {
            persistenceError = true
            throw error
        } finally {
            ownedTicket = null
        }
    }
}
