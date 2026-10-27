package com.swagdroid.core.privilege

/** Android's per-user UID range (UserHandle.getUserId/getIdentifier are hidden SDK APIs). */
object AndroidUidScope {
    fun userId(uid: Int): Int {
        require(uid >= 0) { "Неизвестный UID" }
        return uid / 100_000
    }
}
