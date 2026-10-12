package com.cleanforge.core

import com.cleanforge.core.privileged.CapabilityRegistry
import com.cleanforge.core.privileged.CapabilityStatus
import com.cleanforge.core.privileged.FileServiceCore
import com.cleanforge.core.privileged.PrivilegedOperation
import com.cleanforge.core.privileged.SessionResult
import com.cleanforge.core.privileged.TransportException
import com.cleanforge.core.privileged.UnavailableReason
import com.cleanforge.core.shizuku.ShizukuState
import com.cleanforge.testutil.FakeSessionProvider
import com.cleanforge.testutil.FakeShizukuState
import com.cleanforge.testutil.FaultyFs
import com.cleanforge.testutil.InMemoryFs
import com.cleanforge.testutil.LocalPrivilegedFileApi
import com.cleanforge.testutil.ROOT
import com.cleanforge.testutil.ScriptedApi
import com.google.common.truth.Truth.assertThat
import kotlinx.coroutines.runBlocking
import org.junit.Test
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

/** What the app says it can do, per operation, as Shizuku and the device change underneath it. */
class CapabilityRegistryTest {

    private class Rig(state: ShizukuState = ShizukuState.Authorized(2000, 13), serviceVersion: Int = 1) {
        val mem = InMemoryFs()
        val faulty = FaultyFs(mem)
        val core = FileServiceCore(faulty, "com.cleanforge")
        val calls: MutableList<String> = java.util.concurrent.CopyOnWriteArrayList()
        @Volatile var before: (String) -> Unit = {}
        val api = ScriptedApi(LocalPrivilegedFileApi(core, uidValue = 2000, version = serviceVersion), calls).also { a ->
            a.before = { name -> before(name) }
        }
        val provider = FakeSessionProvider { api }
        val shizuku = FakeShizukuState(state)
        @Volatile var now = 1_000_000L
        val registry = CapabilityRegistry(
            shizuku, provider,
            clock = { now },
            minProbeIntervalMs = 3_000L,
            probeCallTimeoutMs = 750L
        )

        init {
            mem.mkdirs("$ROOT/Android/data/com.example/cache")
            mem.mkdirs("$ROOT/Android/obb/com.example")
        }
    }

    private fun CapabilityRegistry.statuses() = PrivilegedOperation.entries.associateWith { current(it).status }

    @Test fun `before any check nothing is claimed`() {
        val rig = Rig()
        assertThat(rig.registry.statuses().values.toSet()).containsExactly(CapabilityStatus.UNKNOWN)
        assertThat(rig.registry.report.value.probedAtMs).isNull()
    }

    @Test fun `each Shizuku state blocks every operation with the right status and an actionable reason`() {
        val expected = mapOf(
            ShizukuState.NotInstalled to CapabilityStatus.UNAVAILABLE,
            ShizukuState.NotRunning to CapabilityStatus.UNAVAILABLE,
            ShizukuState.Disconnected to CapabilityStatus.UNAVAILABLE,
            ShizukuState.PermissionDenied to CapabilityStatus.NEEDS_AUTHORIZATION,
            ShizukuState.PermissionPermanentlyDenied to CapabilityStatus.NEEDS_AUTHORIZATION,
            ShizukuState.ServerTooOld to CapabilityStatus.UNSUPPORTED,
            ShizukuState.Error("boom") to CapabilityStatus.UNAVAILABLE
        )
        for ((state, status) in expected) {
            val rig = Rig(state)
            for (op in PrivilegedOperation.entries) {
                val c = rig.registry.current(op)
                assertThat(c.status).isEqualTo(status)
                assertThat(c.canAttempt).isFalse()
                assertThat(c.reason).isNotEmpty()
                assertThat(c.limitations).isNotEmpty()
            }
        }
        val blocked = Rig(ShizukuState.PermissionPermanentlyDenied).registry.current(PrivilegedOperation.LIST_EXTERNAL_APP_DATA)
        assertThat(blocked.reason).contains("Shizuku app") // tells the user where to go, not to press a dead button
    }

    @Test fun `with Shizuku authorized and everything readable, reads are verified and deletions are only ready`() = runBlocking {
        val rig = Rig()
        val report = rig.registry.probe()
        assertThat(report.serviceUid).isEqualTo(2000)
        assertThat(report.serviceApiVersion).isEqualTo(1)
        assertThat(report.probedAtMs).isEqualTo(1_000_000L)
        assertThat(report.of(PrivilegedOperation.LIST_EXTERNAL_APP_DATA).status).isEqualTo(CapabilityStatus.VERIFIED)
        assertThat(report.of(PrivilegedOperation.MEASURE_EXTERNAL_APP_DIR).status).isEqualTo(CapabilityStatus.PREREQUISITES_MET)
        // A read-only probe never deletes to test deleting, so these cannot be VERIFIED yet.
        assertThat(report.of(PrivilegedOperation.CLEAR_EXTERNAL_APP_CACHE).status).isEqualTo(CapabilityStatus.PREREQUISITES_MET)
        assertThat(report.of(PrivilegedOperation.REMOVE_UNINSTALLED_APP_DATA).status).isEqualTo(CapabilityStatus.PREREQUISITES_MET)
        assertThat(rig.calls).doesNotContain("delete")
        assertThat(rig.calls).doesNotContain("setProtectedPackages")
        assertThat(rig.mem.exists("$ROOT/Android/data/com.example/cache")).isTrue()
    }

    @Test fun `a connected service that cannot read Android data and obb supports nothing`() = runBlocking {
        val rig = Rig()
        rig.faulty.denyList += "$ROOT/Android/data"
        rig.faulty.denyList += "$ROOT/Android/obb"
        val report = rig.registry.probe()
        for (op in PrivilegedOperation.entries) {
            val c = report.of(op)
            assertThat(c.status).isEqualTo(CapabilityStatus.UNSUPPORTED)
            assertThat(c.canAttempt).isFalse()
            assertThat(c.reason).contains("Android/data")
        }
    }

    @Test fun `app caches need Android data, leftover removal can still work through obb alone`() = runBlocking {
        val rig = Rig()
        rig.faulty.denyList += "$ROOT/Android/data"
        val report = rig.registry.probe()
        assertThat(report.of(PrivilegedOperation.CLEAR_EXTERNAL_APP_CACHE).status).isEqualTo(CapabilityStatus.UNSUPPORTED)
        assertThat(report.of(PrivilegedOperation.LIST_EXTERNAL_APP_DATA).status).isEqualTo(CapabilityStatus.VERIFIED)
        assertThat(report.of(PrivilegedOperation.REMOVE_UNINSTALLED_APP_DATA).status).isEqualTo(CapabilityStatus.PREREQUISITES_MET)
    }

    @Test fun `when only obb is unreadable the reason says which folder`() = runBlocking {
        val rig = Rig()
        rig.faulty.denyList += "$ROOT/Android/obb"
        val c = rig.registry.probe().of(PrivilegedOperation.LIST_EXTERNAL_APP_DATA)
        assertThat(c.status).isEqualTo(CapabilityStatus.VERIFIED)
        assertThat(c.reason).contains("obb")
    }

    @Test fun `a service that cannot be started maps its reason onto the capability vocabulary`() = runBlocking {
        val table = mapOf(
            UnavailableReason.BIND_TIMEOUT to CapabilityStatus.UNAVAILABLE,
            UnavailableReason.BIND_FAILED to CapabilityStatus.UNAVAILABLE,
            UnavailableReason.SERVICE_DIED to CapabilityStatus.UNAVAILABLE,
            UnavailableReason.NEEDS_PERMISSION to CapabilityStatus.NEEDS_AUTHORIZATION,
            UnavailableReason.PERMISSION_PERMANENTLY_DENIED to CapabilityStatus.NEEDS_AUTHORIZATION,
            UnavailableReason.SERVICE_VERSION_MISMATCH to CapabilityStatus.UNSUPPORTED,
            UnavailableReason.SERVER_TOO_OLD to CapabilityStatus.UNSUPPORTED
        )
        for ((reason, status) in table) {
            val rig = Rig()
            rig.provider.notReady = SessionResult.NotReady(reason, "because $reason")
            val report = rig.registry.probe()
            for (op in PrivilegedOperation.entries) {
                assertThat(report.of(op).status).isEqualTo(status)
                assertThat(report.of(op).reason).isEqualTo("because $reason")
            }
        }
    }

    @Test fun `a service that dies during the check is Unavailable and its binding is dropped`() = runBlocking {
        val rig = Rig()
        rig.before = { name -> if (name == "list") throw TransportException(TransportException.Kind.SERVICE_DIED, "gone") }
        val report = rig.registry.probe()
        assertThat(report.of(PrivilegedOperation.LIST_EXTERNAL_APP_DATA).status).isEqualTo(CapabilityStatus.UNAVAILABLE)
        assertThat(rig.provider.invalidations.get()).isEqualTo(1)
    }

    @Test fun `a service that hangs during the check is Unavailable, restarted, and the check returns`() = runBlocking {
        val rig = Rig()
        val release = CountDownLatch(1)
        rig.provider.onRestart = { release.countDown() }
        rig.before = { name ->
            if (name == "list") {
                release.await(10, TimeUnit.SECONDS)
                throw TransportException(TransportException.Kind.SERVICE_DIED, "killed")
            }
        }
        val report = rig.registry.probe()
        assertThat(report.of(PrivilegedOperation.LIST_EXTERNAL_APP_DATA).status).isEqualTo(CapabilityStatus.UNAVAILABLE)
        assertThat(rig.provider.restarts.get()).isEqualTo(1)
    }

    @Test fun `a stale verified status never outlives Shizuku`() = runBlocking {
        val rig = Rig()
        rig.registry.probe()
        assertThat(rig.registry.current(PrivilegedOperation.LIST_EXTERNAL_APP_DATA).status).isEqualTo(CapabilityStatus.VERIFIED)
        rig.shizuku.set(ShizukuState.NotRunning)
        assertThat(rig.registry.current(PrivilegedOperation.LIST_EXTERNAL_APP_DATA).status).isEqualTo(CapabilityStatus.UNAVAILABLE)
        rig.shizuku.set(ShizukuState.PermissionDenied)
        assertThat(rig.registry.current(PrivilegedOperation.LIST_EXTERNAL_APP_DATA).status).isEqualTo(CapabilityStatus.NEEDS_AUTHORIZATION)
    }

    @Test fun `a recent check is reused, a forced one or a changed Shizuku state is not`() = runBlocking {
        val rig = Rig()
        rig.registry.probe()
        val first = rig.provider.acquires.get()
        rig.now += 1_000
        rig.registry.probe()
        assertThat(rig.provider.acquires.get()).isEqualTo(first)
        rig.registry.probe(force = true)
        assertThat(rig.provider.acquires.get()).isGreaterThan(first)

        val second = rig.provider.acquires.get()
        rig.now += 10_000
        rig.registry.probe()
        assertThat(rig.provider.acquires.get()).isGreaterThan(second) // interval passed
    }

    @Test fun `operation results move a capability between verified and unavailable`() {
        val rig = Rig()
        val reg = rig.registry
        reg.markVerified(PrivilegedOperation.CLEAR_EXTERNAL_APP_CACHE)
        assertThat(reg.current(PrivilegedOperation.CLEAR_EXTERNAL_APP_CACHE).status).isEqualTo(CapabilityStatus.VERIFIED)
        assertThat(reg.report.value.of(PrivilegedOperation.CLEAR_EXTERNAL_APP_CACHE).checkedAtMs).isEqualTo(1_000_000L)
        reg.markUnavailable(PrivilegedOperation.CLEAR_EXTERNAL_APP_CACHE, "service stopped")
        assertThat(reg.current(PrivilegedOperation.CLEAR_EXTERNAL_APP_CACHE).status).isEqualTo(CapabilityStatus.UNAVAILABLE)
        assertThat(reg.current(PrivilegedOperation.CLEAR_EXTERNAL_APP_CACHE).reason).isEqualTo("service stopped")
        // Other operations are untouched.
        assertThat(reg.current(PrivilegedOperation.LIST_EXTERNAL_APP_DATA).status).isEqualTo(CapabilityStatus.UNKNOWN)
    }

    @Test fun `the report flow publishes the probe result`() = runBlocking {
        val rig = Rig()
        rig.registry.probe()
        assertThat(rig.registry.report.value.of(PrivilegedOperation.LIST_EXTERNAL_APP_DATA).status).isEqualTo(CapabilityStatus.VERIFIED)
        assertThat(rig.registry.report.value.shizuku).isEqualTo(ShizukuState.Authorized(2000, 13))
    }

    @Test fun `every operation carries honest limitations`() {
        for (op in PrivilegedOperation.entries) {
            assertThat(Rig().registry.current(op).limitations).isNotEmpty()
        }
        val clear = Rig().registry.current(PrivilegedOperation.CLEAR_EXTERNAL_APP_CACHE).limitations.joinToString(" ")
        assertThat(clear).contains("/data/data")
        assertThat(clear).contains("Not proven")
    }

    @Test fun `a later read-only check does not downgrade an operation that really worked`() = runBlocking {
        val rig = Rig()
        rig.registry.probe(force = true)
        rig.registry.markVerified(PrivilegedOperation.CLEAR_EXTERNAL_APP_CACHE)
        rig.registry.probe(force = true)
        assertThat(rig.registry.current(PrivilegedOperation.CLEAR_EXTERNAL_APP_CACHE).status).isEqualTo(CapabilityStatus.VERIFIED)
        // ...but once Shizuku goes away the demonstration no longer counts, and it is not resurrected by the next probe.
        rig.shizuku.set(ShizukuState.NotRunning)
        rig.registry.probe(force = true)
        rig.shizuku.set(ShizukuState.Authorized(2000, 13))
        rig.registry.probe(force = true)
        assertThat(rig.registry.current(PrivilegedOperation.CLEAR_EXTERNAL_APP_CACHE).status).isEqualTo(CapabilityStatus.PREREQUISITES_MET)
    }
}
