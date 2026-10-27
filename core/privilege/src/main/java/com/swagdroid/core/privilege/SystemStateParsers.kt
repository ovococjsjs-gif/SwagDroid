package com.swagdroid.core.privilege

import com.swagdroid.core.model.ShellExecResult
import com.swagdroid.core.model.StateReadResult

object SystemStateParsers {
    val appOpModes: Set<String> = setOf("allow", "ignore", "deny", "default", "foreground")
    val settingKeyPattern = Regex("^[a-zA-Z0-9_.:-]{1,200}$")

    /** Leave room for shell escaping and the OS argument limit, including the inverse value. */
    fun canRoundTripSettingValue(value: String?): Boolean =
        value == null || ('\u0000' !in value && value.toByteArray(Charsets.UTF_8).size <= 16_384)

    fun appOp(result: ShellExecResult, opName: String): StateReadResult<String> {
        if (!result.isSuccess || result.outputTruncated) {
            return StateReadResult.Unavailable(result.stderrText.ifBlank { "AppOps не прочитан полностью (${result.exitCode})" })
        }
        val lines = result.stdout.map { it.trim() }.filter { it.isNotEmpty() }
        if (lines.any { it.startsWith("Uid mode:", ignoreCase = true) }) {
            return StateReadResult.Unavailable("AppOps содержит UID-режим: его нельзя точно восстановить как режим отдельного пакета")
        }
        if (lines == listOf("No operations.")) return StateReadResult.Value("default")
        val pattern = Regex("^${Regex.escape(opName)}: (allow|ignore|deny|default|foreground)(?:;.*)?$")
        if (lines.size != 1) return StateReadResult.Unavailable("Неоднозначный или пустой ответ AppOps для $opName")
        val mode = pattern.matchEntire(lines.single())?.groupValues?.get(1)
            ?: return StateReadResult.Unavailable("Неподдерживаемый ответ AppOps для $opName")
        return StateReadResult.Value(mode)
    }
}
