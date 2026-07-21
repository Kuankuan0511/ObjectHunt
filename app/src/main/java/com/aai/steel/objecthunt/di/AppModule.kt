package com.aai.steel.objecthunt.di

import android.content.Context
import com.aai.steel.objecthunt.BuildConfig
import com.aai.steel.objecthunt.MuseApiService
import com.aai.steel.objecthunt.PigeonRepository
import com.aai.steel.objecthunt.data.DetectionQueueRepository
import com.aai.steel.objecthunt.data.NetworkMonitor
import com.aai.steel.objecthunt.data.PigeonDao
import com.aai.steel.objecthunt.data.PigeonDatabase
import com.aai.steel.objecthunt.data.QueuedDetectionDao
import com.aai.steel.objecthunt.data.SavedPigeonRepository
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
    fun provideDatabase(@ApplicationContext ctx: Context): PigeonDatabase =
        PigeonDatabase.getInstance(ctx)

    @Provides
    fun providePigeonDao(db: PigeonDatabase): PigeonDao = db.pigeonDao()

    @Provides
    fun provideQueuedDao(db: PigeonDatabase): QueuedDetectionDao = db.queuedDetectionDao()

    @Provides
    @Singleton
    fun provideApi(): MuseApiService = PigeonRepository.createApiService()

    @Provides
    @Singleton
    fun providePigeonRepository(api: MuseApiService): PigeonRepository =
        PigeonRepository(api, BuildConfig.MUSE_API_KEY, BuildConfig.MUSE_API_MODEL)

    @Provides
    @Singleton
    fun provideSavedRepository(dao: PigeonDao): SavedPigeonRepository =
        SavedPigeonRepository(dao)

    @Provides
    @Singleton
    fun provideQueueRepository(
        queuedDao: QueuedDetectionDao,
        pigeonRepo: PigeonRepository,
        savedRepo: SavedPigeonRepository
    ): DetectionQueueRepository = DetectionQueueRepository(queuedDao, pigeonRepo, savedRepo)

    @Provides
    @Singleton
    fun provideNetworkMonitor(@ApplicationContext ctx: Context): NetworkMonitor =
        NetworkMonitor(ctx)
}
