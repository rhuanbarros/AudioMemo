package com.example.audiomemo.di

import android.content.Context
import androidx.room.Room
import androidx.work.WorkManager
import com.example.audiomemo.data.db.AudioMemoDatabase
import com.example.audiomemo.data.db.MIGRATION_1_2
import com.example.audiomemo.data.db.dao.ChunkDao
import com.example.audiomemo.data.db.dao.SessionDao
import com.example.audiomemo.data.db.dao.SummaryDao
import com.example.audiomemo.data.db.dao.TranscriptDao
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
    fun provideDatabase(@ApplicationContext context: Context): AudioMemoDatabase =
        Room.databaseBuilder(context, AudioMemoDatabase::class.java, "audiomemo.db")
            .addMigrations(MIGRATION_1_2)
            .build()

    @Provides fun provideSessionDao(db: AudioMemoDatabase): SessionDao = db.sessionDao()
    @Provides fun provideChunkDao(db: AudioMemoDatabase): ChunkDao = db.chunkDao()
    @Provides fun provideTranscriptDao(db: AudioMemoDatabase): TranscriptDao = db.transcriptDao()
    @Provides fun provideSummaryDao(db: AudioMemoDatabase): SummaryDao = db.summaryDao()

    @Provides
    @Singleton
    fun provideWorkManager(@ApplicationContext context: Context): WorkManager =
        WorkManager.getInstance(context)
}