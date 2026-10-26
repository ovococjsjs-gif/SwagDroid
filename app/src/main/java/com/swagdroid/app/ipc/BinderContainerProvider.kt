package com.swagdroid.app.ipc

import android.content.ContentProvider
import android.content.ContentValues
import android.database.Cursor
import android.net.Uri
import android.os.Binder
import android.os.Bundle
import android.os.Process
import com.swagdroid.api.SwagDroidIpcContract
import com.swagdroid.backend.root.PrivilegeManager

/**
 * Защищённый `ContentProvider`, принимающий `IBinder` от привилегированного процесса `swagdroid_server`.
 *
 * Безопасность:
 * Принимает `METHOD_SEND_BINDER` ТОЛЬКО если `Binder.getCallingUid()` равен `0` (Root) или `2000` (ADB Shell).
 * Никакое стороннее приложение с обычным пользовательским UID не может подсунуть фальшивый Binder!
 */
class BinderContainerProvider : ContentProvider() {

    override fun onCreate(): Boolean = true

    override fun call(method: String, arg: String?, extras: Bundle?): Bundle? {
        if (method == SwagDroidIpcContract.METHOD_SEND_BINDER && extras != null) {
            val callingUid = Binder.getCallingUid()
            if (callingUid != Process.ROOT_UID && callingUid != Process.SHELL_UID) {
                throw SecurityException(
                    "BinderContainerProvider: Rejected binder injection from untrusted UID=$callingUid"
                )
            }
            extras.classLoader = BinderContainerProvider::class.java.classLoader
            val serverBinder = extras.getBinder(SwagDroidIpcContract.EXTRA_BINDER)
            if (serverBinder != null) {
                PrivilegeManager.onServerBinderReceived(serverBinder)
                return Bundle().apply { putBoolean("accepted", true) }
            }
        }
        return null
    }

    override fun query(
        uri: Uri,
        projection: Array<out String>?,
        selection: String?,
        selectionArgs: Array<out String>?,
        sortOrder: String?,
    ): Cursor? = null

    override fun getType(uri: Uri): String? = null
    override fun insert(uri: Uri, values: ContentValues?): Uri? = null
    override fun delete(uri: Uri, selection: String?, selectionArgs: Array<out String>?): Int = 0
    override fun update(uri: Uri, values: ContentValues?, selection: String?, selectionArgs: Array<out String>?): Int = 0
}
