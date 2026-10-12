package com.cleanforge.testutil

import com.cleanforge.core.fs.DeleteMode
import com.cleanforge.core.fs.DeleteResult
import com.cleanforge.core.fs.FsOps
import com.cleanforge.core.fs.FsProbe
import com.cleanforge.core.fs.FsStat
import com.cleanforge.core.privileged.FileServiceCore
import com.cleanforge.core.privileged.ListReply
import com.cleanforge.core.privileged.PrivilegedFileApi
import com.cleanforge.core.privileged.PrivilegedProtocol
import com.cleanforge.core.privileged.PrivilegedSessionProvider
import com.cleanforge.core.privileged.SessionResult
import com.cleanforge.core.privileged.SizeReply
import com.cleanforge.core.privileged.StatReply
import com.cleanforge.core.privileged.WireCodec
import com.cleanforge.core.shizuku.ShizukuState
import com.cleanforge.core.shizuku.ShizukuStateSource
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.atomic.AtomicInteger

/**
 * The REAL service logic ([FileServiceCore]) behind the [PrivilegedFileApi] seam, with every reply pushed through
 * [WireCodec] both ways exactly as the AIDL layer does. Only Binder, Shizuku and Android classes are missing.
 */
class LocalPrivilegedFileApi(
    val core: FileServiceCore,
    private val uidValue: Int = 2000,
    private val version: Int = PrivilegedProtocol.API_VERSION
) : PrivilegedFileApi {
    override fun apiVersion(): Int = version
    override fun uid(): Int = uidValue
    override fun stat(path: String): StatReply = WireCodec.decodeStat(WireCodec.encodeStat(core.stat(path)))
    override fun list(path: String): ListReply = WireCodec.decodeList(WireCodec.encodeList(core.list(path)))
    override fun size(path: String): SizeReply = WireCodec.decodeSize(WireCodec.encodeSize(core.size(path)))
    override fun delete(path: String, mode: DeleteMode, expected: FsStat?): DeleteResult =
        WireCodec.decodeDelete(
            WireCodec.encodeDelete(core.delete(path, mode.code, expected?.dev ?: 0L, expected?.ino ?: 0L, expected != null))
        )

    override fun cancelCurrent() = core.cancelCurrent()
    override fun setProtectedPackages(packages: Collection<String>): Int = core.setProtectedPackages(packages.toTypedArray())
}

/**
 * Records every call by name and lets a test run code before and after the real call: throw a TransportException to
 * kill the "service", block on a latch to hang it, or change the filesystem to model a race.
 * Call names: apiVersion, uid, stat, list, size, delete, cancelCurrent, setProtectedPackages.
 */
class ScriptedApi(
    private val delegate: PrivilegedFileApi,
    val calls: MutableList<String> = CopyOnWriteArrayList()
) : PrivilegedFileApi {
    @Volatile var before: (String) -> Unit = {}
    @Volatile var after: (String) -> Unit = {}

    private fun <T> traced(name: String, block: () -> T): T {
        calls += name
        before(name)
        val result = block()
        after(name)
        return result
    }

    override fun apiVersion(): Int = traced("apiVersion") { delegate.apiVersion() }
    override fun uid(): Int = traced("uid") { delegate.uid() }
    override fun stat(path: String): StatReply = traced("stat") { delegate.stat(path) }
    override fun list(path: String): ListReply = traced("list") { delegate.list(path) }
    override fun size(path: String): SizeReply = traced("size") { delegate.size(path) }
    override fun delete(path: String, mode: DeleteMode, expected: FsStat?): DeleteResult =
        traced("delete") { delegate.delete(path, mode, expected) }

    override fun cancelCurrent() = traced("cancelCurrent") { delegate.cancelCurrent() }
    override fun setProtectedPackages(packages: Collection<String>): Int =
        traced("setProtectedPackages") { delegate.setProtectedPackages(packages) }
}

/** Hands out a service, or the configured "not ready" answer, and counts what the executor asked of it. */
class FakeSessionProvider(private val make: () -> PrivilegedFileApi) : PrivilegedSessionProvider {
    @Volatile var notReady: SessionResult.NotReady? = null
    @Volatile var onRestart: () -> Unit = {}
    @Volatile private var live: PrivilegedFileApi? = null
    val acquires = AtomicInteger()
    val invalidations = AtomicInteger()
    val restarts = AtomicInteger()

    override suspend fun acquire(): SessionResult {
        acquires.incrementAndGet()
        notReady?.let { return it }
        val api = live ?: make().also { live = it }
        return SessionResult.Ready(api)
    }

    override fun invalidate() {
        invalidations.incrementAndGet()
        live = null
    }

    override suspend fun restart() {
        restarts.incrementAndGet()
        live = null
        onRestart()
    }
}

class FakeShizukuState(initial: ShizukuState = ShizukuState.Authorized(2000, 13)) : ShizukuStateSource {
    private val flow = MutableStateFlow(initial)
    override val state: StateFlow<ShizukuState> = flow
    val refreshes = AtomicInteger()
    override fun refresh() {
        refreshes.incrementAndGet()
    }

    fun set(state: ShizukuState) {
        flow.value = state
    }
}

/** A filesystem that can refuse to look at, list or fail on chosen paths, and tell the test about each removal. */
class FaultyFs(private val delegate: FsOps) : FsOps {
    val denyProbe = HashSet<String>()
    val errorProbe = HashSet<String>()
    val denyList = HashSet<String>()
    @Volatile var afterRemove: (String) -> Unit = {}

    override fun lstat(path: String): FsStat? = if (path in denyProbe || path in errorProbe) null else delegate.lstat(path)

    override fun probe(path: String): FsProbe = when (path) {
        in denyProbe -> FsProbe.Denied
        in errorProbe -> FsProbe.Error
        else -> delegate.probe(path)
    }

    override fun list(path: String): List<String>? = if (path in denyList) null else delegate.list(path)

    override fun remove(path: String): Boolean {
        val ok = delegate.remove(path)
        if (ok) afterRemove(path)
        return ok
    }
}
