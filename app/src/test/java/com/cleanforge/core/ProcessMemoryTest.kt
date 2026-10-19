package com.cleanforge.core

import com.cleanforge.core.privileged.FileServiceCore
import com.cleanforge.core.privileged.ProcSource
import com.cleanforge.core.privileged.ProcessMemoryEntry
import com.cleanforge.core.privileged.ProcessMemoryReader
import com.cleanforge.testutil.InMemoryFs
import com.google.common.truth.Truth.assertThat
import org.junit.Test

class ProcessMemoryTest {
    private class FakeProc(private val files: Map<Int, Map<String, String>>) : ProcSource {
        override fun processIds(): List<Int> = files.keys.toList()
        override fun read(pid: Int, name: String): String? = files[pid]?.get(name)
    }

    private fun status(uid: Int, rssKb: Long?) = buildString {
        append("Name:\tsomething\nUid:\t$uid\t$uid\t$uid\t$uid\n")
        if (rssKb != null) append("VmRSS:\t   $rssKb kB\n")
    }

    @Test fun `only app processes with a memory figure and a safe name are reported`() {
        val src = FakeProc(
            mapOf(
                1 to mapOf("status" to status(0, 100), "cmdline" to "init\u0000"), // root: never reported
                2 to mapOf("status" to status(10123, 2048), "cmdline" to "com.example.app\u0000"),
                3 to mapOf("status" to status(10124, 512), "cmdline" to "com.example.app:push\u0000"),
                4 to mapOf("status" to status(10125, null), "cmdline" to "com.example.nomem\u0000"), // no VmRSS
                5 to mapOf("status" to status(10126, 64), "cmdline" to ""), // no name
                6 to mapOf("status" to status(10127, 64), "cmdline" to "bad|name\u0000") // would break the wire format
            )
        )
        val r = ProcessMemoryReader.read(src)
        assertThat(r.map { it.processName }).containsExactly("com.example.app", "com.example.app:push").inOrder()
        assertThat(r.first().rssKb).isEqualTo(2048L)
    }

    @Test fun `the wire format round-trips and a damaged line is dropped instead of thrown`() {
        val entries = listOf(ProcessMemoryEntry(2, 10123, 2048, "com.example.app"))
        assertThat(ProcessMemoryReader.decode(ProcessMemoryReader.encode(entries))).isEqualTo(entries)
        assertThat(ProcessMemoryReader.decode(arrayOf("garbage", "1|2|x|y", "3|4|5|ok")))
            .containsExactly(ProcessMemoryEntry(3, 4, 5, "ok"))
        assertThat(ProcessMemoryReader.decode(null)).isEmpty()
    }

    @Test fun `the service core exposes the reader and takes no argument to steer it`() {
        val proc = FakeProc(mapOf(2 to mapOf("status" to status(10123, 1), "cmdline" to "a.b\u0000")))
        val core = FileServiceCore(InMemoryFs(), "com.cleanforge", proc = proc)
        assertThat(core.processMemory().toList()).containsExactly("2|10123|1|a.b")
    }
}
