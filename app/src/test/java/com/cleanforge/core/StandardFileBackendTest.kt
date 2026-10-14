package com.cleanforge.core

import com.cleanforge.core.backend.StandardFileBackend
import com.cleanforge.core.fs.DeleteMode
import com.cleanforge.testutil.FaultyFs
import com.cleanforge.testutil.InMemoryFs
import com.cleanforge.testutil.ROOT
import com.google.common.truth.Truth.assertThat
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import org.junit.Test

class StandardFileBackendTest {
    @Test fun `cancelling the caller stops a big delete between entries`() = runBlocking {
        val mem = InMemoryFs()
        val fs = FaultyFs(mem)
        val dir = "$ROOT/Download/big"
        for (n in 1..20) mem.file("$dir/f$n.bin", size = 10)

        val job = launch(start = CoroutineStart.LAZY) {
            StandardFileBackend(fs).delete(dir, DeleteMode.CONTENTS_ONLY, null)
        }
        fs.afterRemove = { job.cancel() } // the caller gives up right after the first entry is gone
        job.join()

        val left = mem.list(dir)!!.size
        assertThat(left).isGreaterThan(0)
        assertThat(left).isLessThan(20)
    }
}
