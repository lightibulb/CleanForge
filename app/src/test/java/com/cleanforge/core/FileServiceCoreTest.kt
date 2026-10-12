package com.cleanforge.core

import com.cleanforge.core.fs.DeleteMode
import com.cleanforge.core.fs.DeleteStatus
import com.cleanforge.core.fs.FsType
import com.cleanforge.core.fs.WalkLimits
import com.cleanforge.core.privileged.FileServiceCore
import com.cleanforge.core.privileged.ReplyStatus
import com.cleanforge.testutil.FaultyFs
import com.cleanforge.testutil.InMemoryFs
import com.cleanforge.testutil.ROOT
import com.google.common.truth.Truth.assertThat
import org.junit.Test

/** The code that runs inside the privileged process, driven over an in-memory filesystem. */
class FileServiceCoreTest {
    private val mem = InMemoryFs()
    private val fs = FaultyFs(mem)
    private val core = FileServiceCore(fs, ownPackage = "com.cleanforge")
    private val data = "$ROOT/Android/data"

    private fun guard(vararg packages: String) = core.setProtectedPackages(arrayOf(*packages))

    private fun deleteTree(path: String): com.cleanforge.core.fs.DeleteResult {
        val st = mem.lstat(path)!!
        return core.delete(path, DeleteMode.TREE.code, st.dev, st.ino, true)
    }

    private fun clearCache(path: String): com.cleanforge.core.fs.DeleteResult {
        val st = mem.lstat(path)!!
        return core.delete(path, DeleteMode.CONTENTS_ONLY.code, st.dev, st.ino, true)
    }

    // ------------------------------------------------------------ path policy

    @Test fun `reads outside Android data and obb are refused, whatever the spelling`() {
        mem.file("$ROOT/DCIM/Camera/IMG.jpg")
        val bad = listOf(
            "$ROOT/DCIM",
            "$ROOT/Android",
            "$ROOT/Android/media/com.a",
            "/data/data/com.example",
            "$ROOT/Android/data/../../DCIM",
            "$ROOT/Android/data/./com.a",
            "$ROOT/Android/data/com.a/",
            "relative/Android/data",
            "$ROOT/Android/data/com.a\u0000/x",
            ""
        )
        for (p in bad) {
            assertThat(core.stat(p).status).isEqualTo(ReplyStatus.REFUSED)
            assertThat(core.list(p).status).isEqualTo(ReplyStatus.REFUSED)
            assertThat(core.size(p).status).isEqualTo(ReplyStatus.REFUSED)
        }
        assertThat(core.stat(null).status).isEqualTo(ReplyStatus.REFUSED)
        assertThat(core.list(null).status).isEqualTo(ReplyStatus.REFUSED)
        assertThat(core.size(null).status).isEqualTo(ReplyStatus.REFUSED)
    }

    @Test fun `a symlink anywhere above the target refuses the call instead of following it`() {
        mem.file("$ROOT/DCIM/cache/photo.jpg")
        mem.symlink("$data/com.x")
        assertThat(core.stat("$data/com.x/cache").status).isEqualTo(ReplyStatus.REFUSED)
        assertThat(core.list("$data/com.x/cache").status).isEqualTo(ReplyStatus.REFUSED)
        assertThat(core.size("$data/com.x/cache").status).isEqualTo(ReplyStatus.REFUSED)
        val r = core.delete("$data/com.x/cache", DeleteMode.CONTENTS_ONLY.code, 1L, 1L, true)
        assertThat(r.status).isEqualTo(DeleteStatus.REFUSED)
        assertThat(mem.exists("$ROOT/DCIM/cache/photo.jpg")).isTrue()
    }

    // ------------------------------------------------------------ reads say why

    @Test fun `stat tells found, missing, denied and failed apart`() {
        mem.file("$data/com.a/cache/x.bin", 10)
        val found = core.stat("$data/com.a/cache/x.bin")
        assertThat(found.status).isEqualTo(ReplyStatus.OK)
        assertThat(found.stat!!.type).isEqualTo(FsType.FILE)
        assertThat(found.stat!!.size).isEqualTo(10L)

        assertThat(core.stat("$data/com.nope").status).isEqualTo(ReplyStatus.NOT_FOUND)
        assertThat(core.stat("$data/com.nope/cache").status).isEqualTo(ReplyStatus.NOT_FOUND) // missing parent

        fs.denyProbe += "$data/com.a"
        assertThat(core.stat("$data/com.a").status).isEqualTo(ReplyStatus.OS_DENIED)
        fs.denyProbe.clear()
        fs.errorProbe += "$data/com.a"
        assertThat(core.stat("$data/com.a").status).isEqualTo(ReplyStatus.FAILED)
    }

    @Test fun `listing keeps names with spaces and unicode exactly as they are`() {
        mem.mkdirs("$data/my app")
        mem.file("$data/com.a/cache/\u0394 file (1).txt")
        mem.file("$data/com.a/cache/two  spaces.bin")
        val top = core.list(data)
        assertThat(top.status).isEqualTo(ReplyStatus.OK)
        assertThat(top.names).containsExactly("my app", "com.a")
        val cache = core.list("$data/com.a/cache")
        assertThat(cache.names).containsExactly("\u0394 file (1).txt", "two  spaces.bin")
    }

    @Test fun `a folder the OS refuses to list is reported as denied, not as empty`() {
        mem.mkdirs("$data/com.a")
        fs.denyList += "$data/com.a"
        val r = core.list("$data/com.a")
        assertThat(r.status).isEqualTo(ReplyStatus.OS_DENIED)
        assertThat(r.names).isEmpty()
    }

    @Test fun `listing a file or a missing folder does not pretend to be an empty folder`() {
        mem.file("$data/com.a/file.txt")
        assertThat(core.list("$data/com.a/file.txt").status).isEqualTo(ReplyStatus.FAILED)
        assertThat(core.list("$data/com.zzz").status).isEqualTo(ReplyStatus.NOT_FOUND)
    }

    @Test fun `a long listing is cut by count and by total name length and says so`() {
        for (i in 1..5) mem.mkdirs("$data/com.aaaa$i")
        val byCount = FileServiceCore(fs, "x", maxNames = 3).list(data)
        assertThat(byCount.names).hasSize(3)
        assertThat(byCount.truncated).isTrue()

        val byChars = FileServiceCore(fs, "x", maxListChars = 20).list(data) // each name is 9 characters
        assertThat(byChars.names).hasSize(2)
        assertThat(byChars.truncated).isTrue()

        val all = core.list(data)
        assertThat(all.names).hasSize(5)
        assertThat(all.truncated).isFalse()
    }

    @Test fun `size counts unreadable sub-folders so the figure is never presented as exact`() {
        mem.file("$data/com.a/cache/ok.bin", 50)
        mem.file("$data/com.a/cache/locked/hidden.bin", 70)
        fs.denyList += "$data/com.a/cache/locked"
        val r = core.size("$data/com.a/cache")
        assertThat(r.status).isEqualTo(ReplyStatus.OK)
        assertThat(r.size!!.bytes).isEqualTo(50L)
        assertThat(r.size!!.unreadable).isEqualTo(1)
        assertThat(r.size!!.complete).isTrue()
        assertThat(r.size!!.isExact).isFalse()
    }

    @Test fun `size of a folder walked to its safety bound is incomplete`() {
        for (i in 1..10) mem.file("$data/com.a/cache/f$i.bin", 10)
        val bounded = FileServiceCore(fs, "x", walkLimits = WalkLimits(maxDepth = 32, maxEntries = 3))
        val r = bounded.size("$data/com.a/cache")
        assertThat(r.status).isEqualTo(ReplyStatus.OK)
        assertThat(r.size!!.complete).isFalse()
        assertThat(r.size!!.isExact).isFalse()
    }

    @Test fun `a complete measurement of a readable folder is exact`() {
        mem.file("$data/com.a/cache/a.bin", 40)
        mem.file("$data/com.a/cache/sub/b.bin", 60)
        val r = core.size("$data/com.a/cache")
        assertThat(r.size!!.bytes).isEqualTo(100L)
        assertThat(r.size!!.isExact).isTrue()
    }

    // ------------------------------------------------------------ the installed-apps guard

    @Test fun `a tree delete is refused until the app has installed the guard`() {
        mem.file("$data/com.gone/files/save.dat")
        val r = deleteTree("$data/com.gone")
        assertThat(r.status).isEqualTo(DeleteStatus.REFUSED)
        assertThat(mem.exists("$data/com.gone/files/save.dat")).isTrue()
    }

    @Test fun `a tree delete is refused for a package Android knows, in any letter case`() {
        mem.file("$data/COM.Known.APP/files/save.dat")
        assertThat(guard("com.known.app", "com.other")).isEqualTo(2)
        val r = deleteTree("$data/COM.Known.APP")
        assertThat(r.status).isEqualTo(DeleteStatus.REFUSED)
        assertThat(mem.exists("$data/COM.Known.APP/files/save.dat")).isTrue()
    }

    @Test fun `a tree delete never removes the app's own folder`() {
        mem.file("$data/com.cleanforge/files/x")
        guard("com.other")
        assertThat(deleteTree("$data/com.cleanforge").status).isEqualTo(DeleteStatus.REFUSED)
        mem.file("$data/Com.CleanForge2/files/x") // a different package that merely starts the same way
        assertThat(deleteTree("$data/Com.CleanForge2").status).isEqualTo(DeleteStatus.OK)
    }

    @Test fun `with the guard installed a real leftover is removed completely`() {
        mem.file("$data/com.gone/files/save.dat", 30)
        mem.file("$ROOT/Android/obb/com.gone/main.obb", 70)
        guard("com.other")
        val a = deleteTree("$data/com.gone")
        assertThat(a.status).isEqualTo(DeleteStatus.OK)
        assertThat(a.bytesFreed).isEqualTo(30L)
        assertThat(mem.exists("$data/com.gone")).isFalse()
        assertThat(deleteTree("$ROOT/Android/obb/com.gone").status).isEqualTo(DeleteStatus.OK)
    }

    @Test fun `an oversized or null guard list is rejected and the previous guard stays`() {
        mem.file("$data/com.keep/files/save.dat")
        assertThat(guard("com.keep")).isEqualTo(1)
        assertThat(core.setProtectedPackages(Array(5_001) { "com.p$it" })).isEqualTo(-1)
        assertThat(core.setProtectedPackages(null)).isEqualTo(-1)
        assertThat(deleteTree("$data/com.keep").status).isEqualTo(DeleteStatus.REFUSED)
    }

    @Test fun `names that cannot be a package are ignored by the guard, not stored`() {
        assertThat(guard("com.ok", "not a package", "../x", "", "a;b.c")).isEqualTo(1)
    }

    @Test fun `clearing an app cache needs no guard and leaves the folder in place`() {
        mem.file("$data/com.example/cache/a.bin", 40)
        mem.file("$data/com.example/cache/sub/b.bin", 60)
        val r = clearCache("$data/com.example/cache")
        assertThat(r.status).isEqualTo(DeleteStatus.OK)
        assertThat(r.bytesFreed).isEqualTo(100L)
        assertThat(mem.exists("$data/com.example/cache")).isTrue()
        assertThat(mem.list("$data/com.example/cache")).isEmpty()
    }

    // ------------------------------------------------------------ what may be deleted at all

    @Test fun `delete refuses every shape except the two it exists for`() {
        mem.file("$data/com.a/cache/sub/x.bin")
        mem.file("$ROOT/DCIM/Camera/IMG.jpg")
        guard("com.other")
        val dcim = mem.lstat("$ROOT/DCIM/Camera")!!
        val st = mem.lstat("$data/com.a")!!
        val cases = listOf(
            Triple("$data/com.a/cache/sub", DeleteMode.CONTENTS_ONLY.code, st),
            Triple(data, DeleteMode.TREE.code, st),
            Triple("$ROOT/DCIM/Camera", DeleteMode.TREE.code, dcim),
            Triple("$data/com.a", DeleteMode.CONTENTS_ONLY.code, st),
            Triple("$data/com.a/cache", DeleteMode.FILE.code, st),
            Triple("$data/com.a/cache", DeleteMode.EMPTY_DIR.code, st),
            Triple("$data/com.a/cache", 99, st)
        )
        for ((path, mode, stat) in cases) {
            val r = core.delete(path, mode, stat.dev, stat.ino, true)
            assertThat(r.status).isEqualTo(DeleteStatus.REFUSED)
        }
        assertThat(core.delete(null, DeleteMode.TREE.code, 1L, 1L, true).status).isEqualTo(DeleteStatus.REFUSED)
        assertThat(core.delete("$data/com.a", DeleteMode.TREE.code, st.dev, st.ino, false).status).isEqualTo(DeleteStatus.REFUSED)
        assertThat(mem.exists("$ROOT/DCIM/Camera/IMG.jpg")).isTrue()
        assertThat(mem.exists("$data/com.a/cache/sub/x.bin")).isTrue()
    }

    @Test fun `a replaced or missing target is reported as such and nothing is touched`() {
        mem.file("$data/com.a/cache/x.bin")
        val st = mem.lstat("$data/com.a/cache")!!
        val changed = core.delete("$data/com.a/cache", DeleteMode.CONTENTS_ONLY.code, st.dev, st.ino + 999, true)
        assertThat(changed.status).isEqualTo(DeleteStatus.IDENTITY_CHANGED)
        assertThat(mem.exists("$data/com.a/cache/x.bin")).isTrue()

        val missing = core.delete("$data/com.nope/cache", DeleteMode.CONTENTS_ONLY.code, 1L, 1L, true)
        assertThat(missing.status).isEqualTo(DeleteStatus.MISSING)
    }

    // ------------------------------------------------------------ cancellation

    @Test fun `a cancel request stops the delete between entries and reports what was already removed`() {
        for (name in listOf("a", "b", "c", "d", "e")) mem.file("$data/com.a/cache/$name.bin", 10)
        fs.afterRemove = { core.cancelCurrent() } // the user presses Stop right after the first removal
        val r = clearCache("$data/com.a/cache")
        assertThat(r.status).isEqualTo(DeleteStatus.CANCELLED)
        assertThat(r.entriesRemoved).isEqualTo(1)
        assertThat(r.bytesFreed).isEqualTo(10L)
        assertThat(mem.list("$data/com.a/cache")).hasSize(4)
        assertThat(mem.exists("$data/com.a/cache")).isTrue()
    }

    @Test fun `a cancel that arrived before the delete started is not remembered`() {
        mem.file("$data/com.a/cache/a.bin", 10)
        core.cancelCurrent()
        val r = clearCache("$data/com.a/cache")
        assertThat(r.status).isEqualTo(DeleteStatus.OK) // documented limitation: the app-side wait is what ends early
        assertThat(mem.list("$data/com.a/cache")).isEmpty()
    }

    @Test fun `a cancelled tree delete never removes the folder itself`() {
        for (name in listOf("a", "b", "c")) mem.file("$data/com.gone/files/$name.dat", 10)
        guard("com.other")
        fs.afterRemove = { core.cancelCurrent() }
        val r = deleteTree("$data/com.gone")
        assertThat(r.status).isEqualTo(DeleteStatus.CANCELLED)
        assertThat(mem.exists("$data/com.gone")).isTrue()
    }
}
