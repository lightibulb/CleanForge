package com.cleanforge.core.shizuku

import android.content.ComponentName
import android.content.Context
import android.content.ServiceConnection
import android.os.IBinder
import com.cleanforge.BuildConfig
import com.cleanforge.core.privileged.PrivilegedFileApi
import com.cleanforge.core.privileged.PrivilegedProtocol
import com.cleanforge.core.privileged.PrivilegedSessionProvider
import com.cleanforge.core.privileged.SessionResult
import com.cleanforge.core.privileged.TransportException
import com.cleanforge.core.privileged.UnavailableReason
import com.cleanforge.shizuku.IFileService
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import rikka.shizuku.Shizuku
import java.util.concurrent.atomic.AtomicReference
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Owns the lifecycle of the Shizuku user-service binding: bind on demand with a deadline, version-check the service,
 * detect process death, rebind after Shizuku restarts, and say precisely why there is no service when there is none.
 *
 * Every bind attempt uses its OWN [ServiceConnection] and its own result holder, so a late callback from an
 * abandoned attempt can never be mistaken for the current one. After a bind timeout the half-started service is
 * removed instead of being left behind.
 *
 * NOT unit-testable (it talks to Shizuku's static API). See the device test checklist in
 * docs/SHIZUKU_CAPABILITY_MATRIX.md; whether bindUserService re-delivers onServiceConnected for a reused connection
 * is unknown, which is why a fresh connection object is used each time.
 */
@Singleton
class ShizukuFileServiceConnector @Inject constructor(
    @ApplicationContext private val context: Context,
    private val manager: ShizukuManager
) : PrivilegedSessionProvider {
    private val mutex = Mutex()

    private class Binding(val connection: ServiceConnection, val binder: IBinder, val api: PrivilegedFileApi)

    private val current = AtomicReference<Binding?>(null)

    private val args: Shizuku.UserServiceArgs by lazy {
        Shizuku.UserServiceArgs(ComponentName(context.packageName, ShizukuFileService::class.java.name))
            .daemon(false)
            .processNameSuffix("fs")
            .debuggable(BuildConfig.DEBUG)
            .version(BuildConfig.VERSION_CODE)
    }

    // Shizuku status checks and bind/unbind are binder calls: always off the caller's (possibly main) thread.
    override suspend fun acquire(): SessionResult = withContext(Dispatchers.IO) { acquireLocked() }

    private suspend fun acquireLocked(): SessionResult = mutex.withLock {
        manager.refresh()
        val blocked = notReadyFor(manager.state.value)
        if (blocked != null) {
            dropBinding(removeService = false) // never keep a service reference across a dead or revoked Shizuku
            return@withLock blocked
        }
        val existing = current.get()
        if (existing != null && existing.binder.pingBinder()) return@withLock SessionResult.Ready(existing.api)
        dropBinding(removeService = false)
        bind()
    }

    override fun invalidate() {
        dropBinding(removeService = false)
    }

    override suspend fun restart() {
        withContext(Dispatchers.IO) { mutex.withLock { dropBinding(removeService = true) } }
    }

    // ------------------------------------------------------------------ internals

    private fun notReadyFor(state: ShizukuState): SessionResult.NotReady? = when (state) {
        is ShizukuState.Authorized -> null
        ShizukuState.NotInstalled -> SessionResult.NotReady(UnavailableReason.NOT_INSTALLED, "Shizuku is not installed")
        ShizukuState.NotRunning -> SessionResult.NotReady(UnavailableReason.NOT_RUNNING, "Shizuku is not running")
        ShizukuState.Disconnected -> SessionResult.NotReady(UnavailableReason.NOT_RUNNING, "The Shizuku service stopped")
        ShizukuState.PermissionDenied ->
            SessionResult.NotReady(UnavailableReason.NEEDS_PERMISSION, "CleanForge has not been allowed in Shizuku")
        ShizukuState.PermissionPermanentlyDenied ->
            SessionResult.NotReady(
                UnavailableReason.PERMISSION_PERMANENTLY_DENIED,
                "Permission was denied with \"don't ask again\"; allow CleanForge inside the Shizuku app"
            )
        ShizukuState.ServerTooOld ->
            SessionResult.NotReady(UnavailableReason.SERVER_TOO_OLD, "The running Shizuku is too old (version 11 or newer is required)")
        is ShizukuState.Error -> SessionResult.NotReady(UnavailableReason.SHIZUKU_ERROR, state.message)
    }

    private suspend fun bind(): SessionResult {
        val result = CompletableDeferred<IBinder?>() // null = the service went away before it connected
        val connection = object : ServiceConnection {
            override fun onServiceConnected(name: ComponentName, binder: IBinder) {
                result.complete(binder)
            }

            override fun onServiceDisconnected(name: ComponentName) {
                // Process death, or unbind. Forget this binding if it is the live one; release a pending bind.
                val live = current.get()
                if (live != null && live.connection === this) current.compareAndSet(live, null)
                result.complete(null)
            }
        }

        try {
            Shizuku.bindUserService(args, connection)
        } catch (e: Exception) {
            return SessionResult.NotReady(
                UnavailableReason.BIND_FAILED,
                "Shizuku refused to start the service: ${e.message ?: e.javaClass.simpleName}"
            )
        }

        val binder = withTimeoutOrNull(BIND_TIMEOUT_MS) { result.await() }
        if (binder == null) {
            val died = result.isCompleted // completed with null: the service went away; otherwise: no answer in time
            unbindQuietly(connection, removeService = true)
            return if (died) {
                SessionResult.NotReady(UnavailableReason.SERVICE_DIED, "The Shizuku service stopped while starting")
            } else {
                SessionResult.NotReady(UnavailableReason.BIND_TIMEOUT, "The Shizuku service did not start within ${BIND_TIMEOUT_MS / 1000} s")
            }
        }
        if (!binder.pingBinder()) {
            unbindQuietly(connection, removeService = true)
            return SessionResult.NotReady(UnavailableReason.SERVICE_DIED, "The Shizuku service stopped while starting")
        }

        val api = AidlPrivilegedFileApi(IFileService.Stub.asInterface(binder))
        val version = try {
            api.apiVersion()
        } catch (e: TransportException) {
            unbindQuietly(connection, removeService = true)
            return SessionResult.NotReady(
                UnavailableReason.SERVICE_VERSION_MISMATCH,
                "The running Shizuku service does not speak this version of CleanForge's protocol; restart Shizuku and the app"
            )
        }
        if (version < PrivilegedProtocol.MIN_API_VERSION) {
            unbindQuietly(connection, removeService = true)
            return SessionResult.NotReady(
                UnavailableReason.SERVICE_VERSION_MISMATCH,
                "The running service is outdated (protocol $version); restart Shizuku and the app"
            )
        }

        current.set(Binding(connection, binder, api))
        return SessionResult.Ready(api)
    }

    private fun dropBinding(removeService: Boolean) {
        val old = current.getAndSet(null) ?: return
        unbindQuietly(old.connection, removeService)
    }

    private fun unbindQuietly(connection: ServiceConnection, removeService: Boolean) {
        try {
            Shizuku.unbindUserService(args, connection, removeService)
        } catch (e: Exception) {
            // Shizuku may already be gone; nothing left to release.
        }
    }

    private companion object {
        const val BIND_TIMEOUT_MS = 6_000L
    }
}
