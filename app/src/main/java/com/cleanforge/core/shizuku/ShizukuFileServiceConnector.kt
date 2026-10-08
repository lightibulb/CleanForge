package com.cleanforge.core.shizuku

import android.content.ComponentName
import android.content.Context
import android.content.ServiceConnection
import android.os.IBinder
import com.cleanforge.BuildConfig
import com.cleanforge.shizuku.IFileService
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withTimeoutOrNull
import rikka.shizuku.Shizuku
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Owns the lifecycle of the Shizuku user-service binding: bind on demand, detect process death,
 * rebind after Shizuku restarts, give up cleanly when Shizuku is unavailable.
 */
@Singleton
class ShizukuFileServiceConnector @Inject constructor(
    @ApplicationContext private val context: Context,
    private val manager: ShizukuManager
) {
    private val mutex = Mutex()

    @Volatile private var service: IFileService? = null
    @Volatile private var pending: CompletableDeferred<IFileService?>? = null

    private val args: Shizuku.UserServiceArgs by lazy {
        Shizuku.UserServiceArgs(ComponentName(context.packageName, ShizukuFileService::class.java.name))
            .daemon(false)
            .processNameSuffix("fs")
            .debuggable(BuildConfig.DEBUG)
            .version(BuildConfig.VERSION_CODE)
    }

    private val connection = object : ServiceConnection {
        override fun onServiceConnected(name: ComponentName, binder: IBinder) {
            val svc = if (binder.pingBinder()) IFileService.Stub.asInterface(binder) else null
            service = svc
            pending?.complete(svc)
        }

        override fun onServiceDisconnected(name: ComponentName) {
            service = null // process death or unbind
        }
    }

    /** A live service, or null if Shizuku is missing, not authorized, dead, or too slow to bind. */
    suspend fun get(): IFileService? = mutex.withLock {
        val existing = service
        if (existing != null && existing.asBinder().pingBinder()) return@withLock existing
        service = null

        manager.refresh()
        if (manager.state.value !is ShizukuState.Authorized) return@withLock null

        val deferred = CompletableDeferred<IFileService?>()
        pending = deferred
        try {
            Shizuku.bindUserService(args, connection)
        } catch (t: Throwable) {
            pending = null
            return@withLock null
        }
        val bound = withTimeoutOrNull(BIND_TIMEOUT_MS) { deferred.await() }
        pending = null
        bound
    }

    /** Called by backends after a RemoteException so the next call rebinds. */
    fun invalidate() {
        service = null
    }

    fun unbind() {
        runCatching { Shizuku.unbindUserService(args, connection, true) }
        service = null
    }

    private companion object {
        const val BIND_TIMEOUT_MS = 6_000L
    }
}
