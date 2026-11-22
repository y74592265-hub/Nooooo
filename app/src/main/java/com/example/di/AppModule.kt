package com.example.di

import android.content.Context
import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.sqlite.db.SupportSQLiteDatabase
import com.example.data.local.CategoryDao
import com.example.data.local.ClipDao
import com.example.data.local.SmartClipboardDatabase
import com.example.data.local.SyncLogDao
import com.example.data.repository.CategoryRepositoryImpl
import com.example.data.repository.ClipRepositoryImpl
import com.example.data.repository.SyncLogRepositoryImpl
import com.example.domain.repository.CategoryRepository
import com.example.domain.repository.ClipRepository
import com.example.domain.repository.SyncLogRepository
import com.example.integrations.IntegrationProvider
import com.example.integrations.discord.DiscordApiService
import com.example.integrations.discord.DiscordProvider
import com.example.integrations.gdrive.GoogleDriveApiService
import com.example.integrations.gdrive.GoogleDriveProvider
import com.example.integrations.telegram.TelegramApiService
import com.example.integrations.telegram.TelegramProvider
import com.squareup.moshi.Moshi
import com.squareup.moshi.kotlin.reflect.KotlinJsonAdapterFactory
import dagger.Binds
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.android.qualifiers.ApplicationContext
import dagger.hilt.components.SingletonComponent
import dagger.multibindings.IntoSet
import kotlinx.serialization.json.Json
import okhttp3.OkHttpClient
import okhttp3.logging.HttpLoggingInterceptor
import retrofit2.Retrofit
import retrofit2.converter.moshi.MoshiConverterFactory
import java.util.concurrent.TimeUnit
import javax.inject.Singleton

@Module
@InstallIn(SingletonComponent::class)
object AppModule {

  @Provides
  @Singleton
  fun provideJson(): Json = Json {
    ignoreUnknownKeys = true
    prettyPrint = true
    encodeDefaults = true
  }

  @Provides
  @Singleton
  fun provideMoshi(): Moshi = Moshi.Builder().addLast(KotlinJsonAdapterFactory()).build()

  @Provides
  @Singleton
  fun provideOkHttpClient(): OkHttpClient {
    val logging =
      HttpLoggingInterceptor().apply {
        // Never log full URLs/bodies or Auth headers containing Bot/OAuth tokens
        level = HttpLoggingInterceptor.Level.BASIC
        redactHeader("Authorization")
      }
    return OkHttpClient.Builder()
      .addInterceptor(logging)
      .connectTimeout(20, TimeUnit.SECONDS)
      .readTimeout(25, TimeUnit.SECONDS)
      .writeTimeout(25, TimeUnit.SECONDS)
      .build()
  }

  @Provides
  @Singleton
  fun provideTelegramApiService(okHttpClient: OkHttpClient, moshi: Moshi): TelegramApiService =
    Retrofit.Builder()
      .baseUrl("https://api.telegram.org/")
      .client(okHttpClient)
      .addConverterFactory(MoshiConverterFactory.create(moshi))
      .build()
      .create(TelegramApiService::class.java)

  @Provides
  @Singleton
  fun provideDiscordApiService(okHttpClient: OkHttpClient, moshi: Moshi): DiscordApiService =
    Retrofit.Builder()
      .baseUrl("https://discord.com/")
      .client(okHttpClient)
      .addConverterFactory(MoshiConverterFactory.create(moshi))
      .build()
      .create(DiscordApiService::class.java)

  @Provides
  @Singleton
  fun provideGoogleDriveApiService(okHttpClient: OkHttpClient, moshi: Moshi): GoogleDriveApiService =
    Retrofit.Builder()
      .baseUrl("https://www.googleapis.com/")
      .client(okHttpClient)
      .addConverterFactory(MoshiConverterFactory.create(moshi))
      .build()
      .create(GoogleDriveApiService::class.java)

  @Provides
  @Singleton
  fun provideDatabase(@ApplicationContext context: Context): SmartClipboardDatabase =
    Room.databaseBuilder(context, SmartClipboardDatabase::class.java, "smart_clipboard.db")
      .addCallback(
        object : RoomDatabase.Callback() {
          override fun onCreate(db: SupportSQLiteDatabase) {
            super.onCreate(db)
            SmartClipboardDatabase.DEFAULT_CATEGORIES.forEach { cat ->
              db.execSQL(
                "INSERT OR IGNORE INTO categories (id, name, colorHex, iconKey, isDefault, sortOrder) VALUES (?, ?, ?, ?, ?, ?)",
                arrayOf<Any>(
                  cat.id,
                  cat.name,
                  cat.colorHex,
                  cat.iconKey,
                  if (cat.isDefault) 1 else 0,
                  cat.sortOrder,
                ),
              )
            }
          }
        }
      )
      .build()

  @Provides fun provideClipDao(db: SmartClipboardDatabase): ClipDao = db.clipDao()

  @Provides fun provideCategoryDao(db: SmartClipboardDatabase): CategoryDao = db.categoryDao()

  @Provides fun provideSyncLogDao(db: SmartClipboardDatabase): SyncLogDao = db.syncLogDao()
}

@Module
@InstallIn(SingletonComponent::class)
abstract class BindingsModule {
  @Binds @Singleton abstract fun bindClipRepository(impl: ClipRepositoryImpl): ClipRepository

  @Binds
  @Singleton
  abstract fun bindCategoryRepository(impl: CategoryRepositoryImpl): CategoryRepository

  @Binds @Singleton abstract fun bindSyncLogRepository(impl: SyncLogRepositoryImpl): SyncLogRepository

  @Binds
  @IntoSet
  abstract fun bindTelegramProvider(impl: TelegramProvider): IntegrationProvider

  @Binds
  @IntoSet
  abstract fun bindDiscordProvider(impl: DiscordProvider): IntegrationProvider

  @Binds
  @IntoSet
  abstract fun bindGoogleDriveProvider(impl: GoogleDriveProvider): IntegrationProvider
}
