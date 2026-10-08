package com.cleanforge.di

import android.content.Context
import com.cleanforge.core.backend.AndroidCapabilities
import com.cleanforge.core.backend.AndroidPackageInventory
import com.cleanforge.core.backend.Capabilities
import com.cleanforge.core.backend.FileOperationBackend
import com.cleanforge.core.backend.PackageInventory
import com.cleanforge.core.backend.ShizukuFileBackend
import com.cleanforge.core.backend.StandardFileBackend
import com.cleanforge.core.cleaning.CleaningEngine
import com.cleanforge.core.fs.AndroidFsOps
import com.cleanforge.core.fs.FsOps
import com.cleanforge.core.safety.SafetyValidator
import com.cleanforge.core.scanner.ApkScanner
import com.cleanforge.core.scanner.CacheScanner
import com.cleanforge.core.scanner.CorpseScanner
import com.cleanforge.core.scanner.DuplicateScanner
import com.cleanforge.core.scanner.EmptyDirectoryScanner
import com.cleanforge.core.scanner.LargeFileScanner
import com.cleanforge.core.scanner.LogScanner
import com.cleanforge.core.scanner.Scanner
import com.cleanforge.core.scanner.TempScanner
import com.cleanforge.core.scanner.ThumbnailScanner
import com.cleanforge.core.shizuku.ShizukuFileServiceConnector
import com.cleanforge.core.shizuku.ShizukuManager
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.android.qualifiers.ApplicationContext
import dagger.hilt.components.SingletonComponent
import javax.inject.Named
import javax.inject.Singleton

/**
 * Everything is constructed explicitly here: the two FileOperationBackend instances differ only by
 * qualifier, and doing it by hand removes any chance of an ambiguous/missing Hilt binding.
 */
@Module
@InstallIn(SingletonComponent::class)
object AppModule {

    @Provides @Singleton
    fun provideFsOps(): FsOps = AndroidFsOps

    @Provides @Singleton @Named("standard")
    fun provideStandardBackend(fs: FsOps): FileOperationBackend = StandardFileBackend(fs)

    @Provides @Singleton @Named("shizuku")
    fun provideShizukuBackend(connector: ShizukuFileServiceConnector): FileOperationBackend =
        ShizukuFileBackend(connector)

    @Provides @Singleton
    fun providePackageInventory(@ApplicationContext ctx: Context): PackageInventory = AndroidPackageInventory(ctx)

    @Provides @Singleton
    fun provideCapabilities(shizuku: ShizukuManager): Capabilities = AndroidCapabilities(shizuku)

    @Provides @Singleton
    fun provideCleaningEngine(
        safety: SafetyValidator,
        @Named("standard") standard: FileOperationBackend,
        @Named("shizuku") shizuku: FileOperationBackend,
        inventory: PackageInventory,
        capabilities: Capabilities
    ): CleaningEngine = CleaningEngine(safety, standard, shizuku, inventory, capabilities)

    @Provides @Singleton
    fun provideScanners(
        fs: FsOps,
        @Named("shizuku") shizuku: FileOperationBackend,
        inventory: PackageInventory
    ): Set<@JvmSuppressWildcards Scanner> = setOf(
        CacheScanner(shizuku, inventory),
        CorpseScanner(shizuku, inventory),
        ThumbnailScanner(fs),
        TempScanner(fs),
        ApkScanner(fs, inventory),
        LargeFileScanner(fs),
        DuplicateScanner(fs),
        LogScanner(fs),
        EmptyDirectoryScanner(fs)
    )
}
