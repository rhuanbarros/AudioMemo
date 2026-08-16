package com.example.audiomemo.di

import com.example.audiomemo.features.cloudsync.data.SupabaseAuthRepositoryImpl
import com.example.audiomemo.features.cloudsync.domain.SupabaseAuthRepository
import com.example.audiomemo.features.home.data.HomeRepositoryImpl
import com.example.audiomemo.features.home.domain.HomeRepository
import com.example.audiomemo.features.summary.data.repository.SummaryRepositoryImpl
import com.example.audiomemo.features.summary.domain.repository.SummaryRepository
import com.example.audiomemo.features.transcript.data.repository.ChunkRepositoryImpl
import com.example.audiomemo.features.transcript.data.repository.SessionRepositoryImpl
import com.example.audiomemo.features.transcript.data.repository.TranscriptRepositoryImpl
import com.example.audiomemo.features.transcript.domain.repository.ChunkRepository
import com.example.audiomemo.features.transcript.domain.repository.SessionRepository
import com.example.audiomemo.features.transcript.domain.repository.TranscriptRepository
import dagger.Binds
import dagger.Module
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import javax.inject.Singleton

@Module
@InstallIn(SingletonComponent::class)
abstract class RepositoryModule {

    @Binds
    @Singleton
    abstract fun bindSessionRepository(impl: SessionRepositoryImpl): SessionRepository

    @Binds
    @Singleton
    abstract fun bindChunkRepository(impl: ChunkRepositoryImpl): ChunkRepository

    @Binds
    @Singleton
    abstract fun bindTranscriptRepository(impl: TranscriptRepositoryImpl): TranscriptRepository

    @Binds
    @Singleton
    abstract fun bindSummaryRepository(impl: SummaryRepositoryImpl): SummaryRepository

    @Binds
    @Singleton
    abstract fun bindHomeRepository(impl: HomeRepositoryImpl): HomeRepository

    @Binds
    @Singleton
    abstract fun bindSupabaseAuthRepository(impl: SupabaseAuthRepositoryImpl): SupabaseAuthRepository
}
