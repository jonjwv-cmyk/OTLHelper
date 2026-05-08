package com.example.otlhelper.di

import android.content.Context
import com.example.otlhelper.data.db.DbPassphraseProvider
import com.example.otlhelper.data.db.OtlDatabase
import com.example.otlhelper.data.db.dao.BaseMetaDao
import com.example.otlhelper.data.db.dao.FeedItemDao
import com.example.otlhelper.data.db.dao.MolRecordDao
import com.example.otlhelper.data.db.dao.PendingActionDao
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.android.qualifiers.ApplicationContext
import dagger.hilt.components.SingletonComponent
import javax.inject.Singleton

@Module
@InstallIn(SingletonComponent::class)
object DatabaseModule {

    @Provides
    @Singleton
    fun provideDatabase(@ApplicationContext context: Context): OtlDatabase {
        val passphrase = DbPassphraseProvider.getPassphrase(context)
        return OtlDatabase.create(context, passphrase)
    }

    @Provides
    fun provideMolRecordDao(db: OtlDatabase): MolRecordDao = db.molRecordDao()

    @Provides
    fun provideBaseMetaDao(db: OtlDatabase): BaseMetaDao = db.baseMetaDao()

    @Provides
    fun provideFeedItemDao(db: OtlDatabase): FeedItemDao = db.feedItemDao()

    @Provides
    fun providePendingActionDao(db: OtlDatabase): PendingActionDao = db.pendingActionDao()
}
