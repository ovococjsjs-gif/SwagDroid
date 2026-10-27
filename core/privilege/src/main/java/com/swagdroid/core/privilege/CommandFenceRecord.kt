package com.swagdroid.core.privilege

import com.swagdroid.core.model.isValidBootId

/** SharedPreferences may return an empty map after XML parse failure. Existing storage is NOT fresh state. */
object CommandFenceRecord {
    fun encode(ticket: CommandTicket?): Map<String, String> = buildMap {
        put("version", "1")
        put("state", if (ticket == null) "idle" else "pending")
        if (ticket != null) {
            put("ticket", ticket.id)
            put("boot", ticket.bootId)
        }
    }

    fun decode(values: Map<String, *>, storageExists: Boolean): CommandTicket? {
        if (values.isEmpty() && !storageExists) return null
        check(values["version"] == "1") { "Неподдерживаемый или повреждённый журнал команд" }
        return when (values["state"]) {
            "idle" -> {
                check(values["ticket"] == null && values["boot"] == null) { "Противоречивый idle-журнал" }
                null
            }
            "pending" -> {
                val id = values["ticket"] as? String
                val boot = values["boot"] as? String
                check(!id.isNullOrBlank() && isValidBootId(boot)) { "Неполная запись выполнявшейся команды" }
                CommandTicket(id, checkNotNull(boot))
            }
            else -> error("Неизвестное состояние журнала команд")
        }
    }
}
