package com.cleanforge.di

import android.content.Context
import com.cleanforge.core.backend.AndroidCapabilities
import com.cleanforge.core.backend.AndroidPackageInventory
import com.cleanforge.core.backend.Capabilities
import com.cleanforge.core.backend.FileOperationBackend
import com.cleanforge.core.backend.FileReadBackend
import com.cleanforge.core.backend.PackageInventory
import com.cleanforge.core.backend.ShizukuFileBackend
import com.cleanforge.core.backend.StandardFileBackend
import com.cleanforge.core.benchmark.BenchmarkEngine
import com.cleanforge.core.cleaning.CleaningEngine
import com.cleanforge.core.cleaning.PermissionAnnotator
import com.cleanforge.core.fs.AndroidFsOps
import com.cleanforge.core.fs.FsOps
import com.cleanforge.core.fs.PathRules
import com.cleanforge.core.history.FileSnapshotStore
import com.cleanforge.core.history.SnapshotStore
import com.cleanforge.core.inventory.StorageIndexer
import com.cleanforge.core.memory.AndroidBackgroundProcessKiller
import com.cleanforge.core.memory.BackgroundProcessKiller
import com.cleanforge.core.memory.MemoryInfoProvider
import com.cleanforge.core.memory.ProcMemInfoProvider
import com.cleanforge.core.privileged.CapabilityRegistry
import com.cleanforge.core.privileged.DefaultPrivilegedExecutor
import com.cleanforge.core.privileged.PrivilegedExecutor
import com.cleanforge.core.privileged.PrivilegedSessionProvider
import com.cleanforge.core.safety.SafetyValidator
import com.cleanforge.core.scanner.ApkScanner
import com.cleanforge.core.scanner.CacheScanner
import com.cleanforge.core.scanner.CorpseScanner
import com.cleanforge.core.scanner.DuplicateScanner
import com.cleanforge.core.scanner.EmptyDirectoryScanner
import com.cleanforge.core.scanner.LargeFileScanner
import com.cleanforge.core.scanner.LogScanner
import com.cleanforge.core.scanner.Scanner
import com.cleanforge.core.scanner.TelegramScanner
import com.cleanforge.core.scanner.TempScanner
import com.cleanforge.core.scanner.ThumbnailScanner
import com.cleanforge.core.scanner.WhatsAppScanner
import com.cleanforge.core.shizuku.ShizukuFileServiceConnector
import com.cleanforge.core.shizuku.ShizukuManager
import com.cleanforge.data.repository.SnapshotRepository
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.android.qualifiers.ApplicationContext
import dagger.hilt.components.SingletonComponent
import java.io.File
import javax.inject.Named
import javax.inject.Singleton

@Module
@InstallIn(SingletonComponent::class)
object AppModule {

    @Provides @Singleton
    fun provideFsOps(): FsOps = AndroidFsOps

    @Provides @Singleton @Named("standard")
    fun provideStandardBackend(fs: FsOps): FileOperationBackend = StandardFileBackend(fs)

    @Provides @Singleton
    fun provideSessionProvider(connector: ShizukuFileServiceConnector): PrivilegedSessionProvider = connector

    @Provides @Singleton
    fun provideCapabilityRegistry(manager: ShizukuManager, provider: PrivilegedSessionProvider): CapabilityRegistry =
        CapabilityRegistry(manager, provider)

    @Provides @Singleton
    fun providePrivilegedExecutor(provider: PrivilegedSessionProvider, registry: CapabilityRegistry): PrivilegedExecutor =
        DefaultPrivilegedExecutor(provider, registry)

    @Provides @Singleton @Named("shizuku")
    fun provideShizukuBackend(executor: PrivilegedExecutor): FileReadBackend = ShizukuFileBackend(executor)

    @Provides @Singleton
    fun providePackageInventory(@ApplicationContext ctx: Context): PackageInventory = AndroidPackageInventory(ctx)

    @Provides @Singleton
    fun provideCapabilities(shizuku: ShizukuManager, registry: CapabilityRegistry): Capabilities =
        AndroidCapabilities(shizuku, registry)

    @Provides @Singleton
    fun provideCleaningEngine(
        safety: SafetyValidator,
        @Named("standard") standard: FileOperationBackend,
        privileged: PrivilegedExecutor,
        inventory: PackageInventory,
        capabilities: Capabilities
    ): CleaningEngine = CleaningEngine(safety, standard, privileged, inventory, capabilities)

    @Provides @Singleton
    fun providePermissionAnnotator(capabilities: Capabilities): PermissionAnnotator = PermissionAnnotator(capabilities)

    @Provides @Singleton
    fun provideBackgroundProcessKiller(@ApplicationContext context: Context): BackgroundProcessKiller =
        AndroidBackgroundProcessKiller(context)

    @Provides @Singleton
    fun provideMemoryInfoProvider(): MemoryInfoProvider = ProcMemInfoProvider()

    @Provides @Singleton
    fun provideStorageIndexer(fs: FsOps): StorageIndexer = StorageIndexer(fs)

    @Provides @Singleton
    fun provideSnapshotStore(@ApplicationContext ctx: Context): SnapshotStore =
        FileSnapshotStore(File(ctx.filesDir, "snapshots"))

    @Provides @Singleton
    fun provideSnapshotRepository(store: SnapshotStore): SnapshotRepository = SnapshotRepository(store)

    @Provides @Singleton
    fun provideBenchmarkEngine(fs: FsOps): BenchmarkEngine = BenchmarkEngine(fs)

    @Provides @Singleton
    fun provideScanners(
        fs: FsOps,
        @Named("shizuku") shizuku: FileReadBackend,
        @Named("standard") standard: FileOperationBackend,
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
        EmptyDirectoryScanner(fs),
        WhatsAppScanner(standard, inventory, PathRules.ROOT),
        TelegramScanner(standard, inventory, PathRules.ROOT)
        // InstagramScanner / TikTokScanner / TwitterXScanner are dormant (no privileged single-file delete shape).
    )
}
