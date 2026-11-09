package com.meditrack.app.core.di

import com.meditrack.app.core.security.AndroidKeystoreKeyProvider
import com.meditrack.app.core.security.KeyProvider
import dagger.Binds
import dagger.Module
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import javax.inject.Singleton

@Module
@InstallIn(SingletonComponent::class)
abstract class SecurityModule {

    @Binds
    @Singleton
    abstract fun bindKeyProvider(impl: AndroidKeystoreKeyProvider): KeyProvider
}
