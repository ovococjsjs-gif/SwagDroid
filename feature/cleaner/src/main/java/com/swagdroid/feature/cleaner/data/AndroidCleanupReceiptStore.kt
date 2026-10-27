package com.swagdroid.feature.cleaner.data

import android.content.Context
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import com.swagdroid.feature.cleaner.model.CleanupReceipt
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.flow.first
import javax.inject.Inject
import javax.inject.Singleton

private val Context.cleanupReceiptDataStore by preferencesDataStore(name = "last_cleanup_receipt")

@Singleton
class AndroidCleanupReceiptStore @Inject constructor(@ApplicationContext private val context: Context) : CleanupReceiptStore {
    override suspend fun readLast(): CleanupReceipt? {
        val values = context.cleanupReceiptDataStore.data.first().asMap().map { (key, value) ->
            require(value is String) { "Повреждён формат итога очистки" }
            key.name to value
        }.toMap()
        return CleanupReceiptCodec.decode(values)
    }

    override suspend fun write(receipt: CleanupReceipt) {
        val values = CleanupReceiptCodec.encode(receipt)
        require(CleanupReceiptCodec.decode(values) == receipt)
        context.cleanupReceiptDataStore.edit { stored ->
            stored.clear()
            values.forEach { (key, value) -> stored[stringPreferencesKey(key)] = value }
        }
    }
}
