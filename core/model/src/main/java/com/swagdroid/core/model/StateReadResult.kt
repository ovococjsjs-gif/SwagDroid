package com.swagdroid.core.model

/** Missing is a successful observation of absence, never a substitute for a failed read. */
sealed interface StateReadResult<out T> {
    data class Value<T>(val value: T) : StateReadResult<T>
    data object Missing : StateReadResult<Nothing>
    data class Unavailable(val reason: String) : StateReadResult<Nothing>

    fun valueOrNullIfMissing(): T? = when (this) {
        is Value -> value
        Missing -> null
        is Unavailable -> throw StateReadException(reason)
    }

    fun requireValue(): T = valueOrNullIfMissing() ?: throw StateReadException("Объект не найден")
}

class StateReadException(message: String) : IllegalStateException(message)
