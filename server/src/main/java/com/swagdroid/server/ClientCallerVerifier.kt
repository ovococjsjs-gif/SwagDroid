package com.swagdroid.server

import android.os.Binder
import android.os.Process
import com.swagdroid.api.SwagDroidIpcContract
import com.swagdroid.server.compat.SystemServiceCompat

/**
 * Проверка подлинности клиента на КАЖДЫЙ вызов Binder IPC.
 *
 * Защищает привилегированный демон `swagdroid_server` (работающий с uid=2000 или uid=0)
 * от несанкционированного вызова со стороны сторонних установленных приложений.
 */
class ClientCallerVerifier(
    private val systemCompat: SystemServiceCompat,
    private val hostPackageName: String = SwagDroidIpcContract.HOST_PACKAGE_NAME,
    private val expectedSignatureSha256: String? = null,
) {
    @Volatile
    private var cachedVerifiedHostUid: Int = -1

    /**
     * Проверяет, имеет ли вызывающий процесс (`Binder.getCallingUid()`) право обращаться к серверу.
     * Выбрасывает `SecurityException`, если вызов пришёл от чужого приложения.
     */
    fun enforceAuthorizedCaller(methodName: String) {
        val callingUid = Binder.getCallingUid()
        if (callingUid == Process.ROOT_UID || callingUid == Process.SHELL_UID || callingUid == Process.myUid()) {
            return
        }

        val hostUid = resolveAndVerifyHostUid()
        if (hostUid <= 0 || callingUid != hostUid) {
            throw SecurityException(
                "SwagDroidServer Security Violation: Unauthorized caller UID=$callingUid " +
                    "attempted to invoke '$methodName'. Only '$hostPackageName' (UID=$hostUid) is permitted."
            )
        }
    }

    private fun resolveAndVerifyHostUid(): Int {
        val cached = cachedVerifiedHostUid
        if (cached > 0) return cached

        val resolvedUid = systemCompat.getPackageUid(hostPackageName, userId = 0)
        if (resolvedUid <= 0) return -1

        // При наличии эталонного SHA-256 отпечатка сертификата сверяем подпись APK хост-приложения
        if (!expectedSignatureSha256.isNullOrBlank()) {
            val actualSha256 = systemCompat.getPackageSigningCertSha256(hostPackageName, userId = 0)
            if (!expectedSignatureSha256.equals(actualSha256, ignoreCase = true)) {
                throw SecurityException(
                    "SwagDroidServer Signature Mismatch for '$hostPackageName': " +
                        "expected=$expectedSignatureSha256, actual=$actualSha256"
                )
            }
        }

        cachedVerifiedHostUid = resolvedUid
        return resolvedUid
    }
}
