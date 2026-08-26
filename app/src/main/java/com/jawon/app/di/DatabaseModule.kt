package com.jawon.app.di

import android.content.Context
import androidx.room.Room
import com.jawon.app.data.local.AlbumDao
import com.jawon.app.data.local.JawonDatabase
import com.jawon.app.data.local.ShelfDao
import com.jawon.app.data.local.TrackDao
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
    fun provideDatabase(@ApplicationContext context: Context): JawonDatabase =
        Room.databaseBuilder(context, JawonDatabase::class.java, "javon.db").build()

    @Provides
    fun provideShelfDao(database: JawonDatabase): ShelfDao = database.shelfDao()

    @Provides
    fun provideAlbumDao(database: JawonDatabase): AlbumDao = database.albumDao()

    @Provides
    fun provideTrackDao(database: JawonDatabase): TrackDao = database.trackDao()
}
