package com.cleanforge.core.shizuku

import android.os.DeadObjectException
import android.os.RemoteException
import android.os.TransactionTooLargeException
import com.cleanforge.core.fs.DeleteMode
import com.cleanforge.core.fs.DeleteResult
import com.cleanforge.core.fs.FsStat
import com.cleanforge.core.privileged.ListReply
import com.cleanforge.core.privileged.PrivilegedFileApi
import com.cleanforge.core.privileged.SizeReply
import com.cleanforge.core.privileged.StatReply
import com.cleanforge.core.privileged.TransportException
import com.cleanforge.core.privileged.WireCodec
import com.cleanforge.shizuku.IFileService

/**
 * The thin, device-only layer between the AIDL stub and the pure-Kotlin [PrivilegedFileApi]. It does two things:
 * decode replies with [WireCodec], and turn Android's remote-call exceptions into [TransportException] so nothing
 * above it ever sees an Android class. It cannot be exercised on the JVM; see the device test checklist.
 */
class AidlPrivilegedFileApi(private val service: IFileService) : PrivilegedFileApi {

    private inline fun <T> guarded(block: () -> T): T = try {
        block()
    } catch (e: DeadObjectException) {
        throw TransportException(TransportException.Kind.SERVICE_DIED, "The Shizuku service stopped", e)
    } catch (e: TransactionTooLargeException) {
        throw TransportException(TransportException.Kind.CALL_FAILED, "The reply was too large", e)
    } catch (e: RemoteException) {
        throw TransportException(TransportException.Kind.SERVICE_DIED, "The Shizuku service could not be reached", e)
    } catch (e: RuntimeException) {
        throw TransportException(TransportException.Kind.CALL_FAILED, e.message ?: "The call failed", e)
    }

    override fun apiVersion(): Int = guarded { service.apiVersion() }

    override fun uid(): Int = guarded { service.uid() }

    override fun stat(path: String): StatReply = WireCodec.decodeStat(guarded { service.statEx(path) })

    override fun list(path: String): ListReply = WireCodec.decodeList(guarded { service.listEx(path) })

    override fun size(path: String): SizeReply = WireCodec.decodeSize(guarded { service.sizeEx(path) })

    override fun delete(path: String, mode: DeleteMode, expected: FsStat?): DeleteResult =
        WireCodec.decodeDelete(
            guarded { service.deleteEx(path, mode.code, expected?.dev ?: 0L, expected?.ino ?: 0L, expected != null) }
        )

    override fun cancelCurrent() = guarded { service.cancelCurrent() }

    override fun setProtectedPackages(packages: Collection<String>): Int =
        guarded { service.setProtectedPackages(packages.toTypedArray()) }

    override fun processMemory(): com.cleanforge.core.privileged.ProcessListReply =
        com.cleanforge.core.privileged.ProcessListReply(
            com.cleanforge.core.privileged.ReplyStatus.OK,
            com.cleanforge.core.privileged.ProcessMemoryReader.decode(guarded { service.processMemoryEx() })
        )
}
