package com.swagdroid.core.privilege.di

import com.swagdroid.core.privilege.AndroidCommandExecutor
import com.swagdroid.core.privilege.PrivilegedCommandExecutor
import dagger.Binds
import dagger.Module
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import javax.inject.Singleton

@Module
@InstallIn(SingletonComponent::class)
abstract class PrivilegeModule {

    @Binds
    @Singleton
    abstract fun bindCommandExecutor(
        impl: AndroidCommandExecutor,
    ): PrivilegedCommandExecutor
}
