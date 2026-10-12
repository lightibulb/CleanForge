package com.cleanforge.core

import com.cleanforge.core.backend.FsResult
import com.cleanforge.core.fs.FsStat
import com.cleanforge.core.privileged.CapabilityRegistry
import com.cleanforge.core.privileged.CapabilityStatus
import com.cleanforge.core.privileged.DefaultPrivilegedExecutor
import com.cleanforge.core.privileged.ExecutionPolicy
import com.cleanforge.core.privileged.ExternalArea
import com.cleanforge.core.privileged.FailureKind
import com.cleanforge.core.privileged.FileServiceCore
import com.cleanforge.core.privileged.MaintenanceRequest
import com.cleanforge.core.privileged.MaintenanceResult
import com.cleanforge.core.privileged.PrivilegedOperation
import com.cleanforge.core.privileged.SessionResult
import com.cleanforge.core.privileged.TransportException
import com.cleanforge.core.privileged.UnavailableReason
import com.cleanforge.core.shizuku.ShizukuState
import com.cleanforge.testutil.FakeInventory
import com.cleanforge.testutil.FakeSessionProvider
import com.cleanforge.testutil.FakeShizukuState
import com.cleanforge.testutil.FaultyFs
import com.cleanforge.testutil.InMemoryFs
import com.cleanforge.testutil.LocalPrivilegedFileApi
import com.cleanforge.testutil.ROOT
import com.cleanforge.testutil.ScriptedApi
import com.google.common.truth.Truth.assertThat
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.Test
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger

/**
 * The execution layer against the REAL service logic, with the service made to die, hang, refuse and race.
 * These use runBlocking (real time) on purpose: the deadlines are real, and runTest's virtual time would fire them early.
 * Binder, Shizuku and the AIDL stub are NOT covered here (device tests).
 */
class PrivilegedExecutorTest {

    private class Rig(
        policy: ExecutionPolicy = ExecutionPolicy(),
        withRegistry: Boolean = false,
        shizukuState: ShizukuState = ShizukuState.Authorized(2000, 13)
    ) {
        val mem = InMemoryFs()
        val faulty = FaultyFs(mem)
        val core = FileServiceCore(faulty, "com.cleanforge")
        val calls: MutableList<String> = CopyOnWriteArrayList()
        @Volatile var before: (String) -> Unit = {}
        @Volatile var after: (String) -> Unit = {}
        val bindings = AtomicInteger()

        /** Every (re)bind creates a NEW api object, like a new Binder, but shares the recorded calls and the hooks. */
        val provider = FakeSessionProvider {
            bindings.incrementAndGet()
            ScriptedApi(LocalPrivilegedFileApi(core), calls).also { api ->
                api.before = { name -> before(name) }
                api.after = { name -> after(name) }
            }
        }
        val shizuku = FakeShizukuState(shizukuState)
        val registry: CapabilityRegistry? =
            if (withRegistry) CapabilityRegistry(shizuku, provider, minProbeIntervalMs = 0L, probeCallTimeoutMs = 2_000L) else null
        val executor = DefaultPrivilegedExecutor(provider, registry, policy)

        val data = "$ROOT/Android/data"
        val cache = "$data/com.example/cache"

        fun fingerprint(path: String): FsStat = mem.lstat(path)!!
        fun clearCache(pkg: String = "com.example", fp: FsStat = fingerprint(cache)) =
            MaintenanceRequest.ClearExternalAppCache(pkg, fp)

        fun removeLeftover(pkg: String, known: Set<String> = FakeInventory.defaultPackages("com.other"), area: ExternalArea = ExternalArea.DATA) =
            MaintenanceRequest.RemoveUninstalledAppData(
                area, pkg, fingerprint("$ROOT/Android/${area.folder}/$pkg"), known
            )
    }

    private fun killed(why: String = "The Shizuku service stopped") =
        TransportException(TransportException.Kind.SERVICE_DIED, why)

    // ------------------------------------------------------------------ reads

    @Test fun `reads report found, missing and refused for what they are`() = runBlocking {
        val rig = Rig()
        rig.mem.file("${rig.cache}/a.bin", 40)
        rig.mem.file("${rig.cache}/sub/b.bin", 60)

        val st = rig.executor.stat(rig.cache)
        assertThat(st).isInstanceOf(FsResult.Ok::class.java)

        val listing = rig.executor.list(rig.cache) as FsResult.Ok
        assertThat(listing.value.names).containsExactly("a.bin", "sub")
        assertThat(listing.value.truncated).isFalse()

        val size = rig.executor.measure(rig.cache) as FsResult.Ok
        assertThat(size.value.bytes).isEqualTo(100L)
        assertThat(size.value.isExact).isTrue()

        assertThat(rig.executor.stat("${rig.data}/com.nope")).isEqualTo(FsResult.NotFound)
        assertThat(rig.executor.stat("$ROOT/DCIM")).isInstanceOf(FsResult.Denied::class.java)
    }

    @Test fun `a folder the OS will not list is Denied, never an empty list`() = runBlocking {
        val rig = Rig()
        rig.mem.mkdirs("${rig.data}/com.a")
        rig.faulty.denyList += "${rig.data}/com.a"
        val r = rig.executor.list("${rig.data}/com.a")
        assertThat(r).isInstanceOf(FsResult.Denied::class.java)
    }

    @Test fun `with no usable service every read says Unavailable and carries the reason`() = runBlocking {
        val rig = Rig()
        rig.provider.notReady = SessionResult.NotReady(UnavailableReason.NOT_RUNNING, "Shizuku is not running")
        for (r in listOf(rig.executor.stat(rig.cache), rig.executor.list(rig.cache), rig.executor.measure(rig.cache))) {
            assertThat(r).isInstanceOf(FsResult.Unavailable::class.java)
            assertThat((r as FsResult.Unavailable).why).isEqualTo("Shizuku is not running")
        }
        assertThat(rig.calls).isEmpty()
    }

    @Test fun `a service that dies during a read is rebound and the read retried once`() = runBlocking {
        val rig = Rig()
        rig.mem.file("${rig.cache}/a.bin")
        val deaths = AtomicInteger()
        rig.before = { name -> if (name == "stat" && deaths.getAndIncrement() == 0) throw killed() }
        val r = rig.executor.stat(rig.cache)
        assertThat(r).isInstanceOf(FsResult.Ok::class.java)
        assertThat(rig.provider.invalidations.get()).isEqualTo(1)
        assertThat(rig.bindings.get()).isEqualTo(2) // a fresh binding served the retry
    }

    @Test fun `a service that keeps dying is reported Unavailable after the retry budget`() = runBlocking {
        val rig = Rig()
        rig.before = { name -> if (name == "list") throw killed("gone for good") }
        val r = rig.executor.list(rig.data)
        assertThat(r).isInstanceOf(FsResult.Unavailable::class.java)
        assertThat((r as FsResult.Unavailable).why).contains("gone for good")
        assertThat(rig.provider.invalidations.get()).isEqualTo(2) // first attempt + one retry
    }

    @Test fun `a failed call that is not a death is a Failed result and is not retried`() = runBlocking {
        val rig = Rig()
        rig.before = { name ->
            if (name == "size") throw TransportException(TransportException.Kind.CALL_FAILED, "reply too large")
        }
        val r = rig.executor.measure(rig.data)
        assertThat(r).isInstanceOf(FsResult.Failed::class.java)
        assertThat(rig.calls.count { it == "size" }).isEqualTo(1)
        assertThat(rig.provider.invalidations.get()).isEqualTo(0)
    }

    @Test fun `a hung read times out, the service is restarted, and the caller gets an answer`() = runBlocking {
        val rig = Rig(ExecutionPolicy(statTimeoutMs = 150L))
        val release = CountDownLatch(1)
        rig.provider.onRestart = { release.countDown() } // stopping the process frees the blocked thread
        rig.before = { name ->
            if (name == "stat") {
                release.await(10, TimeUnit.SECONDS)
                throw killed("killed")
            }
        }
        val r = rig.executor.stat(rig.cache)
        assertThat(r).isInstanceOf(FsResult.Unavailable::class.java)
        assertThat((r as FsResult.Unavailable).why).contains("did not answer")
        assertThat(rig.provider.restarts.get()).isEqualTo(1)
    }

    @Test fun `reads never run more than the configured number at once`() = runBlocking {
        val rig = Rig(ExecutionPolicy(maxConcurrentReads = 2))
        rig.mem.file("${rig.cache}/a.bin")
        val running = AtomicInteger()
        val peak = AtomicInteger()
        rig.before = { name ->
            if (name == "stat") {
                val now = running.incrementAndGet()
                peak.updateAndGet { maxOf(it, now) }
                Thread.sleep(40)
            }
        }
        rig.after = { name -> if (name == "stat") running.decrementAndGet() }
        withTimeout(20_000) {
            (1..8).map { async(Dispatchers.Default) { rig.executor.stat(rig.cache) } }.awaitAll()
        }
        assertThat(peak.get()).isAtMost(2)
        assertThat(peak.get()).isAtLeast(1)
    }

    @Test fun `cancelling the caller of a read returns promptly even though the service is still blocked`() = runBlocking {
        val rig = Rig()
        val entered = CountDownLatch(1)
        val release = CountDownLatch(1)
        rig.before = { name ->
            if (name == "stat") {
                entered.countDown()
                release.await(10, TimeUnit.SECONDS)
            }
        }
        val job = launch(Dispatchers.Default) { rig.executor.stat(rig.cache) }
        assertThat(entered.await(5, TimeUnit.SECONDS)).isTrue()
        withTimeout(2_000) { job.cancelAndJoin() }
        assertThat(job.isCancelled).isTrue()
        release.countDown()
    }

    // ------------------------------------------------------------------ maintenance: results

    @Test fun `clearing a cache succeeds, is verified, and the folder stays`() = runBlocking {
        val rig = Rig()
        rig.mem.file("${rig.cache}/a.bin", 40)
        rig.mem.file("${rig.cache}/sub/b.bin", 60)
        val r = rig.executor.execute(rig.clearCache())
        assertThat(r).isInstanceOf(MaintenanceResult.Success::class.java)
        assertThat((r as MaintenanceResult.Success).bytesFreed).isEqualTo(100L)
        assertThat(rig.mem.exists(rig.cache)).isTrue()
        assertThat(rig.mem.list(rig.cache)).isEmpty()
    }

    @Test fun `removing a real leftover succeeds and the folder is gone`() = runBlocking {
        val rig = Rig()
        rig.mem.file("${rig.data}/com.gone/files/save.dat", 30)
        val r = rig.executor.execute(rig.removeLeftover("com.gone"))
        assertThat(r).isInstanceOf(MaintenanceResult.Success::class.java)
        assertThat(rig.mem.exists("${rig.data}/com.gone")).isFalse()
    }

    @Test fun `invalid package identifiers are refused before anything reaches the service`() = runBlocking {
        val rig = Rig()
        rig.mem.file("${rig.cache}/a.bin")
        val fp = rig.fingerprint(rig.cache)
        for (bad in listOf("../etc", "com.example; rm -rf /", "my app", "", "com.example/../../DCIM", "com.example\n", "\$(reboot).x.y")) {
            val r = rig.executor.execute(MaintenanceRequest.ClearExternalAppCache(bad, fp))
            assertThat(r).isInstanceOf(MaintenanceResult.Failure::class.java)
            assertThat((r as MaintenanceResult.Failure).kind).isEqualTo(FailureKind.REFUSED)
            val r2 = rig.executor.execute(
                MaintenanceRequest.RemoveUninstalledAppData(ExternalArea.DATA, bad, fp, FakeInventory.defaultPackages())
            )
            assertThat((r2 as MaintenanceResult.Failure).kind).isEqualTo(FailureKind.REFUSED)
        }
        assertThat(rig.calls).isEmpty()
        assertThat(rig.provider.acquires.get()).isEqualTo(0)
        assertThat(rig.mem.exists("${rig.cache}/a.bin")).isTrue()
    }

    @Test fun `a leftover removal with an untrustworthy package list is refused without calling the service`() = runBlocking {
        val rig = Rig()
        rig.mem.file("${rig.data}/com.gone/files/save.dat")
        val r = rig.executor.execute(rig.removeLeftover("com.gone", known = setOf("only.one")))
        assertThat((r as MaintenanceResult.Failure).kind).isEqualTo(FailureKind.REFUSED)
        assertThat(rig.calls).isEmpty()
        assertThat(rig.mem.exists("${rig.data}/com.gone/files/save.dat")).isTrue()
    }

    @Test fun `the service guard stops a leftover removal for a package Android still knows`() = runBlocking {
        val rig = Rig()
        rig.mem.file("${rig.data}/com.known/files/save.dat")
        val r = rig.executor.execute(rig.removeLeftover("com.known", known = FakeInventory.defaultPackages("com.known")))
        assertThat(r).isInstanceOf(MaintenanceResult.Failure::class.java)
        assertThat((r as MaintenanceResult.Failure).kind).isEqualTo(FailureKind.REFUSED)
        assertThat(rig.mem.exists("${rig.data}/com.known/files/save.dat")).isTrue()
    }

    @Test fun `the guard is pushed once per binding and again after a rebind`() = runBlocking {
        val rig = Rig()
        rig.mem.file("${rig.data}/com.gone1/f")
        rig.mem.file("${rig.data}/com.gone2/f")
        rig.mem.file("${rig.data}/com.gone3/f")
        rig.executor.execute(rig.removeLeftover("com.gone1"))
        rig.executor.execute(rig.removeLeftover("com.gone2"))
        assertThat(rig.calls.count { it == "setProtectedPackages" }).isEqualTo(1)
        rig.provider.invalidate() // a new binding: the new service process knows no guard
        rig.executor.execute(rig.removeLeftover("com.gone3"))
        assertThat(rig.calls.count { it == "setProtectedPackages" }).isEqualTo(2)
        assertThat(rig.mem.exists("${rig.data}/com.gone3")).isFalse()
    }

    @Test fun `a target that changed since the scan is a precondition failure and nothing is deleted`() = runBlocking {
        val rig = Rig()
        rig.mem.file("${rig.cache}/a.bin")
        val stale = rig.fingerprint(rig.cache).copy(ino = 9_999L)
        val r = rig.executor.execute(rig.clearCache(fp = stale))
        assertThat((r as MaintenanceResult.Failure).kind).isEqualTo(FailureKind.PRECONDITION_CHANGED)
        assertThat(rig.mem.exists("${rig.cache}/a.bin")).isTrue()
    }

    @Test fun `files names with spaces and odd characters are cleared like any other`() = runBlocking {
        val rig = Rig()
        rig.mem.file("${rig.cache}/my cache file (1).tmp", 10)
        rig.mem.file("${rig.cache}/dir with spaces/\u0394 data \u30c6.bin", 20)
        rig.mem.file("${rig.cache}/semi;colon && pipe | \$HOME.bin", 30)
        val r = rig.executor.execute(rig.clearCache())
        assertThat(r).isInstanceOf(MaintenanceResult.Success::class.java)
        assertThat((r as MaintenanceResult.Success).bytesFreed).isEqualTo(60L)
        assertThat(rig.mem.list(rig.cache)).isEmpty()
    }

    @Test fun `a partly failed cleanup is Partial with the bytes that really went`() = runBlocking {
        val rig = Rig()
        rig.mem.file("${rig.cache}/a.bin", 10)
        rig.mem.file("${rig.cache}/b.bin", 20)
        rig.mem.file("${rig.cache}/c.bin", 30)
        rig.mem.denyRemove += "${rig.cache}/b.bin"
        val r = rig.executor.execute(rig.clearCache())
        assertThat(r).isInstanceOf(MaintenanceResult.Partial::class.java)
        assertThat((r as MaintenanceResult.Partial).bytesFreed).isEqualTo(40L)
        assertThat(rig.mem.exists("${rig.cache}/b.bin")).isTrue()
    }

    @Test fun `success that is contradicted by what is seen afterwards is Partial, not Success`() = runBlocking {
        val rig = Rig()
        rig.mem.file("${rig.cache}/a.bin", 10)
        rig.after = { name -> if (name == "delete") rig.mem.file("${rig.cache}/new.bin", 5) } // the app writes again at once
        val r = rig.executor.execute(rig.clearCache())
        assertThat(r).isInstanceOf(MaintenanceResult.Partial::class.java)
        assertThat((r as MaintenanceResult.Partial).message).contains("files remain")
    }

    @Test fun `a success whose end state cannot be read is Unverified, with the bytes the service reported`() = runBlocking {
        val rig = Rig()
        rig.mem.file("${rig.cache}/a.bin", 10)
        // The delete works, then the OS stops letting the shell look at the folder.
        rig.after = { name -> if (name == "delete") rig.faulty.errorProbe += rig.cache }
        val r = rig.executor.execute(rig.clearCache())
        assertThat(r).isInstanceOf(MaintenanceResult.Unverified::class.java)
        assertThat((r as MaintenanceResult.Unverified).bytesFreed).isEqualTo(10L)
        assertThat(r.message).contains("could not be confirmed")
    }

    // ------------------------------------------------------------------ maintenance: the service dies or hangs

    @Test fun `service death after the work was done is verified through a fresh binding`() = runBlocking {
        val rig = Rig()
        rig.mem.file("${rig.data}/com.gone/files/save.dat", 30)
        rig.after = { name -> if (name == "delete") throw killed() } // the reply is lost, the deletion happened
        val r = rig.executor.execute(rig.removeLeftover("com.gone"))
        assertThat(r).isInstanceOf(MaintenanceResult.Success::class.java)
        assertThat((r as MaintenanceResult.Success).bytesFreed).isEqualTo(0L) // unknown, and not invented
        assertThat(r.message).contains("unknown")
        assertThat(rig.mem.exists("${rig.data}/com.gone")).isFalse()
        assertThat(rig.provider.invalidations.get()).isAtLeast(1)
    }

    @Test fun `service death before any work leaves the item Unverified, never Success`() = runBlocking {
        val rig = Rig()
        rig.mem.file("${rig.data}/com.gone/files/save.dat", 30)
        rig.before = { name -> if (name == "delete") throw killed() }
        val r = rig.executor.execute(rig.removeLeftover("com.gone"))
        assertThat(r).isInstanceOf(MaintenanceResult.Unverified::class.java)
        assertThat(rig.mem.exists("${rig.data}/com.gone/files/save.dat")).isTrue()
    }

    @Test fun `service death with the end state unreachable is Unverified and says to scan again`() = runBlocking {
        val rig = Rig()
        rig.mem.file("${rig.cache}/a.bin", 10)
        val dead = AtomicInteger(0)
        rig.before = { name -> if (name == "delete" || dead.get() == 1) { dead.set(1); throw killed("gone") } }
        val r = rig.executor.execute(rig.clearCache())
        assertThat(r).isInstanceOf(MaintenanceResult.Unverified::class.java)
        assertThat((r as MaintenanceResult.Unverified).message).contains("Scan again")
    }

    @Test fun `a deletion that never returns times out, restarts the service, and reports the observed state`() = runBlocking {
        val rig = Rig(ExecutionPolicy(deleteTimeoutMs = 200L))
        rig.mem.file("${rig.cache}/a.bin", 10)
        val release = CountDownLatch(1)
        rig.provider.onRestart = { release.countDown() } // killing the process frees the blocked call, which then dies
        rig.before = { name ->
            if (name == "delete") {
                release.await(10, TimeUnit.SECONDS)
                throw killed("killed")
            }
        }
        val r = rig.executor.execute(rig.clearCache())
        assertThat(r).isInstanceOf(MaintenanceResult.Unverified::class.java)
        assertThat((r as MaintenanceResult.Unverified).message).contains("did not answer")
        assertThat(rig.provider.restarts.get()).isEqualTo(1)
        assertThat(rig.mem.exists("${rig.cache}/a.bin")).isTrue() // the service was killed before it deleted anything
    }

    @Test fun `cancelling the caller during a deletion tells the service to stop`() = runBlocking {
        val rig = Rig()
        rig.mem.file("${rig.cache}/a.bin", 10)
        val entered = CountDownLatch(1)
        val release = CountDownLatch(1)
        rig.before = { name ->
            if (name == "delete") {
                entered.countDown()
                release.await(10, TimeUnit.SECONDS)
            }
        }
        val job = launch(Dispatchers.Default) { rig.executor.execute(rig.clearCache()) }
        assertThat(entered.await(5, TimeUnit.SECONDS)).isTrue()
        withTimeout(2_000) { job.cancelAndJoin() }
        assertThat(job.isCancelled).isTrue()
        // The stop request is sent in the background; give it a moment.
        var seen = false
        for (i in 1..40) {
            if ("cancelCurrent" in rig.calls) { seen = true; break }
            delay(50)
        }
        release.countDown()
        assertThat(seen).isTrue()
    }

    @Test fun `deletions are strictly one at a time`() = runBlocking {
        val rig = Rig()
        for (i in 1..4) rig.mem.file("${rig.data}/com.app$i/cache/x.bin", 10)
        val running = AtomicInteger()
        val peak = AtomicInteger()
        rig.before = { name ->
            if (name == "delete") {
                val now = running.incrementAndGet()
                peak.updateAndGet { maxOf(it, now) }
                Thread.sleep(30)
            }
        }
        rig.after = { name -> if (name == "delete") running.decrementAndGet() }
        // Fingerprints are taken up front: the in-memory filesystem is not safe to read while another thread deletes.
        val requests = (1..4).map { i -> rig.clearCache("com.app$i", rig.fingerprint("${rig.data}/com.app$i/cache")) }
        withTimeout(20_000) {
            requests.map { request -> async(Dispatchers.Default) { rig.executor.execute(request) } }.awaitAll()
        }
        assertThat(peak.get()).isEqualTo(1)
    }

    // ------------------------------------------------------------------ capability gate

    @Test fun `an operation the registry says cannot run is Unsupported and never reaches the service`() = runBlocking {
        val states = listOf(
            ShizukuState.NotRunning to CapabilityStatus.UNAVAILABLE,
            ShizukuState.PermissionDenied to CapabilityStatus.NEEDS_AUTHORIZATION,
            ShizukuState.PermissionPermanentlyDenied to CapabilityStatus.NEEDS_AUTHORIZATION,
            ShizukuState.ServerTooOld to CapabilityStatus.UNSUPPORTED
        )
        for ((state, expected) in states) {
            val rig = Rig(withRegistry = true, shizukuState = state)
            rig.mem.file("${rig.cache}/a.bin")
            val r = rig.executor.execute(rig.clearCache())
            assertThat(r).isInstanceOf(MaintenanceResult.Unsupported::class.java)
            assertThat((r as MaintenanceResult.Unsupported).capability).isEqualTo(expected)
            assertThat(r.message).isNotEmpty()
            assertThat(rig.calls).isEmpty()
            assertThat(rig.mem.exists("${rig.cache}/a.bin")).isTrue()
        }
    }

    @Test fun `a connected service is not enough when the OS will not let the shell read Android data`() = runBlocking {
        val rig = Rig(withRegistry = true)
        rig.mem.file("${rig.cache}/a.bin")
        rig.faulty.denyList += rig.data
        rig.faulty.denyList += "$ROOT/Android/obb"
        val r = rig.executor.execute(rig.clearCache())
        assertThat(r).isInstanceOf(MaintenanceResult.Unsupported::class.java)
        assertThat((r as MaintenanceResult.Unsupported).capability).isEqualTo(CapabilityStatus.UNSUPPORTED)
        assertThat(rig.calls).doesNotContain("delete")
        assertThat(rig.mem.exists("${rig.cache}/a.bin")).isTrue()
    }

    @Test fun `a successful deletion upgrades the operation from ready to verified`() = runBlocking {
        val rig = Rig(withRegistry = true)
        rig.mem.file("${rig.cache}/a.bin", 10)
        val reg = rig.registry!!
        reg.probe(force = true)
        assertThat(reg.current(PrivilegedOperation.CLEAR_EXTERNAL_APP_CACHE).status).isEqualTo(CapabilityStatus.PREREQUISITES_MET)
        val r = rig.executor.execute(rig.clearCache())
        assertThat(r).isInstanceOf(MaintenanceResult.Success::class.java)
        assertThat(reg.current(PrivilegedOperation.CLEAR_EXTERNAL_APP_CACHE).status).isEqualTo(CapabilityStatus.VERIFIED)
    }
}
