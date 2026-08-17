package com.example.audiomemo.core.di

import android.content.Context
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.android.qualifiers.ApplicationContext
import dagger.hilt.components.SingletonComponent
import java.io.File
import javax.inject.Qualifier
import javax.inject.Singleton

/**
 * Qualifies the on-disk directory used for the persisted app-event log
 * ([com.example.audiomemo.core.logging.AppEventLogger] / [com.example.audiomemo.core.logging.LogFileReader],
 * am2-2). Injecting a plain [File] here — instead of the raw `android.content.Context` — keeps
 * both of those classes free of any Android framework dependency in their constructors, so their
 * file-handling logic stays unit-testable from plain-JVM `src/test` against a real temp directory
 * (this project has no Robolectric/mocking framework for `Context`; see `SupabaseUploadWorkerTest`
 * for the same rationale applied elsewhere).
 */
@Qualifier
@Retention(AnnotationRetention.BINARY)
annotation class LogsDirectory

@Module
@InstallIn(SingletonComponent::class)
object LoggingModule {

    @Provides
    @Singleton
    @LogsDirectory
    fun provideLogsDirectory(@ApplicationContext context: Context): File =
        File(context.filesDir, "logs")
}
