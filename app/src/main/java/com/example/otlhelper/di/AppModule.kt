package com.example.otlhelper.di

import android.content.Context
import com.example.otlhelper.data.repository.FeedRepository
import com.example.otlhelper.data.repository.FeedRepositoryImpl
import com.example.otlhelper.data.repository.MolRepository
import com.example.otlhelper.data.repository.MolRepositoryImpl
import com.example.otlhelper.SessionManager
import com.example.otlhelper.data.db.dao.BaseMetaDao
import com.example.otlhelper.data.db.dao.FeedItemDao
import com.example.otlhelper.data.db.dao.MolRecordDao
import com.example.otlhelper.data.db.dao.PendingActionDao
import dagger.Binds
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.android.qualifiers.ApplicationContext
import dagger.hilt.components.SingletonComponent
import javax.inject.Singleton

@Module
@InstallIn(SingletonComponent::class)
object AppModule {

    @Provides
    @Singleton
    fun provideSessionManager(@ApplicationContext context: Context): SessionManager =
        SessionManager(context)

    @Provides
    @Singleton
    fun provideMolRepository(
        molRecordDao: MolRecordDao,
        baseMetaDao: BaseMetaDao
    ): MolRepository = MolRepositoryImpl(molRecordDao, baseMetaDao)

    @Provides
    @Singleton
    fun provideFeedRepository(
        feedItemDao: FeedItemDao,
        pendingActionDao: PendingActionDao
    ): FeedRepository = FeedRepositoryImpl(feedItemDao, pendingActionDao)
}
