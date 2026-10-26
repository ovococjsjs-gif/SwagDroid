package com.swagdroid.core.vault.di

import android.content.Context
import androidx.room.Room
import com.swagdroid.core.vault.RoomVaultStore
import com.swagdroid.core.vault.VaultStore
import com.swagdroid.core.vault.db.SnapshotDao
import com.swagdroid.core.vault.db.SwagVaultDatabase
import dagger.Binds
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.android.qualifiers.ApplicationContext
import dagger.hilt.components.SingletonComponent
import kotlinx.serialization.json.Json
import javax.inject.Singleton

@Module
@InstallIn(SingletonComponent::class)
abstract class VaultBindingModule {

    @Binds
    @Singleton
    abstract fun bindVaultStore(impl: RoomVaultStore): VaultStore
}

@Module
@InstallIn(SingletonComponent::class)
object VaultDatabaseModule {

    @Provides
    @Singleton
    fun provideVaultJson(): Json = Json {
        prettyPrint = true
        ignoreUnknownKeys = true
        encodeDefaults = true
        classDiscriminator = "type"
    }

    @Provides
    @Singleton
    fun provideVaultDatabase(
        @ApplicationContext context: Context,
    ): SwagVaultDatabase = Room.databaseBuilder(
        context,
        SwagVaultDatabase::class.java,
        "swagdroid_vault.db",
    ).fallbackToDestructiveMigration(true).build()

    @Provides
    @Singleton
    fun provideSnapshotDao(db: SwagVaultDatabase): SnapshotDao = db.snapshotDao()
}
