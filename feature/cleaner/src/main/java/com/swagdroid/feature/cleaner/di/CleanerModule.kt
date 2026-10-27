package com.swagdroid.feature.cleaner.di

import com.swagdroid.feature.cleaner.data.AndroidCleanupReceiptStore
import com.swagdroid.feature.cleaner.data.CleanupReceiptStore
import com.swagdroid.feature.cleaner.engine.CleanerOperations
import com.swagdroid.feature.cleaner.engine.CleanerScanAndPurgeEngine
import dagger.Binds
import dagger.Module
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent

@Module
@InstallIn(SingletonComponent::class)
abstract class CleanerModule {
    @Binds abstract fun operations(impl: CleanerScanAndPurgeEngine): CleanerOperations
    @Binds abstract fun receipts(impl: AndroidCleanupReceiptStore): CleanupReceiptStore
}
