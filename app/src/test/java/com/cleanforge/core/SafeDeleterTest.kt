package com.cleanforge.core

import com.cleanforge.core.fs.DeleteMode
import com.cleanforge.core.fs.DeleteStatus
import com.cleanforge.core.fs.SafeDeleter
import com.cleanforge.core.fs.WalkLimits
import com.cleanforge.testutil.HookedFs
import com.cleanforge.testutil.NioFsOps
import com.google.common.truth.Truth.assertThat
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File
import java.nio.file.Files
import java.nio.file.StandardCopyOption

class SafeDeleterTest {
    @get:Rule val tmp = TemporaryFolder()

    private fun deleter(fs: com.cleanforge.core.fs.FsOps = NioFsOps, limits: WalkLimits = WalkLimits(48, 200_000)) =
        SafeDeleter(fs, limits)

    private fun dir(vararg parts: String): File = File(tmp.root, parts.joinToString("/")).also { it.mkdirs() }
    private fun write(f: File, text: String = "data") {
        f.parentFile.mkdirs()
        Files.write(f.toPath(), text.toByteArray())
    }

    @Test fun `tree delete removes nested folders with spaces unicode and shell metacharacters`() {
        val root = dir("victim")
        write(File(root, "a b/ファイル/é.txt"))
        write(File(root, "\$(touch pwned);rm -rf x/`id`.txt"))
        write(File(root, "..hidden../x"))
        val r = deleter().delete(root.path, DeleteMode.TREE, null)
        assertThat(r.status).isEqualTo(DeleteStatus.OK)
        assertThat(root.exists()).isFalse()
        assertThat(File("pwned").exists()).isFalse()
    }

    @Test fun `contents only keeps the folder itself`() {
        val root = dir("cache")
        write(File(root, "x/y.bin"), "1234")
        val r = deleter().delete(root.path, DeleteMode.CONTENTS_ONLY, null)
        assertThat(r.status).isEqualTo(DeleteStatus.OK)
        assertThat(root.isDirectory).isTrue()
        assertThat(root.list()!!.toList()).isEmpty()
        assertThat(r.bytesFreed).isEqualTo(4L)
    }

    @Test fun `symlink inside tree is unlinked but its target is never followed`() {
        val outside = dir("outside")
        val precious = File(outside, "precious.txt").also { write(it) }
        val root = dir("victim")
        write(File(root, "keep-me-not.txt"))
        Files.createSymbolicLink(File(root, "link-to-dir").toPath(), outside.toPath())
        Files.createSymbolicLink(File(root, "link-to-file").toPath(), precious.toPath())
        val r = deleter().delete(root.path, DeleteMode.TREE, null)
        assertThat(r.status).isEqualTo(DeleteStatus.OK)
        assertThat(root.exists()).isFalse()
        assertThat(precious.exists()).isTrue()
    }

    @Test fun `symlink as the root is refused and target survives`() {
        val outside = dir("outside")
        val precious = File(outside, "precious.txt").also { write(it) }
        val link = File(tmp.root, "evil-link")
        Files.createSymbolicLink(link.toPath(), outside.toPath())
        for (mode in DeleteMode.entries) {
            val r = deleter().delete(link.path, mode, null)
            assertThat(r.status).isEqualTo(DeleteStatus.WRONG_TYPE)
        }
        assertThat(precious.exists()).isTrue()
        assertThat(Files.isSymbolicLink(link.toPath())).isTrue()
    }

    @Test fun `missing path is reported not crashed`() {
        val r = deleter().delete(File(tmp.root, "nope").path, DeleteMode.TREE, null)
        assertThat(r.status).isEqualTo(DeleteStatus.MISSING)
    }

    @Test fun `file mode refuses a directory and empty-dir mode refuses a non-empty one`() {
        val d = dir("d")
        write(File(d, "f"))
        assertThat(deleter().delete(d.path, DeleteMode.FILE, null).status).isEqualTo(DeleteStatus.WRONG_TYPE)
        assertThat(deleter().delete(d.path, DeleteMode.EMPTY_DIR, null).status).isEqualTo(DeleteStatus.FAILED)
        assertThat(File(d, "f").exists()).isTrue()
        File(d, "f").delete()
        assertThat(deleter().delete(d.path, DeleteMode.EMPTY_DIR, null).status).isEqualTo(DeleteStatus.OK)
    }

    @Test fun `file replaced after scan is detected by fingerprint`() {
        val f = File(tmp.root, "photo.jpg").also { write(it, "original") }
        val scanned = NioFsOps.lstat(f.path)!!
        f.delete()
        write(f, "something else entirely")
        val r = deleter().delete(f.path, DeleteMode.FILE, scanned)
        assertThat(r.status).isEqualTo(DeleteStatus.IDENTITY_CHANGED)
        assertThat(f.exists()).isTrue()
    }

    @Test fun `directory that is not the one scanned is detected`() {
        // (Inode numbers can be reused after delete+recreate, so compare against a genuinely different directory.)
        val scannedElsewhere = NioFsOps.lstat(dir("other").path)!!
        val target = dir("cache").also { write(File(it, "x")) }
        val r = deleter().delete(target.path, DeleteMode.CONTENTS_ONLY, scannedElsewhere)
        assertThat(r.status).isEqualTo(DeleteStatus.IDENTITY_CHANGED)
        assertThat(File(target, "x").exists()).isTrue()
    }

    @Test fun `mount boundary is never crossed`() {
        val root = dir("root")
        val mount = dir("root", "mnt")
        val inside = File(mount, "other-filesystem.txt").also { write(it) }
        write(File(root, "normal.txt"))
        val fs = HookedFs().also { it.devOverride = { p -> if (p.startsWith(mount.path)) 4242L else null } }
        val r = deleter(fs).delete(root.path, DeleteMode.TREE, null)
        assertThat(r.status).isNotEqualTo(DeleteStatus.OK)
        assertThat(inside.exists()).isTrue()
        assertThat(File(root, "normal.txt").exists()).isFalse()
        assertThat(root.exists()).isTrue() // not removed because it still holds the mount point
    }

    @Test fun `depth limit stops runaway nesting`() {
        var d = dir("deep")
        repeat(10) { d = File(d, "n$it").also { f -> f.mkdirs() } }
        val r = deleter(limits = WalkLimits(maxDepth = 5, maxEntries = 1000)).delete(File(tmp.root, "deep").path, DeleteMode.TREE, null)
        assertThat(r.status).isEqualTo(DeleteStatus.LIMIT_EXCEEDED)
        assertThat(File(tmp.root, "deep").exists()).isTrue()
    }

    @Test fun `entry limit stops huge folders`() {
        val root = dir("many")
        repeat(20) { write(File(root, "f$it")) }
        val r = deleter(limits = WalkLimits(maxDepth = 8, maxEntries = 5)).delete(root.path, DeleteMode.TREE, null)
        assertThat(r.status).isEqualTo(DeleteStatus.LIMIT_EXCEEDED)
        assertThat(root.exists()).isTrue()
    }

    @Test fun `file appearing during deletion is never removed and is reported`() {
        val root = dir("racy")
        write(File(root, "a.txt"))
        val late = File(root, "late.txt")
        val fs = HookedFs().also {
            it.beforeRemove = { p -> if (p.endsWith("a.txt")) write(late) }
        }
        val r = deleter(fs).delete(root.path, DeleteMode.TREE, null)
        assertThat(r.status).isEqualTo(DeleteStatus.PARTIAL)
        assertThat(late.exists()).isTrue()
        assertThat(root.exists()).isTrue()
    }

    @Test fun `long nested paths are handled`() {
        var d = dir("long")
        val name = "x".repeat(200)
        repeat(12) { d = File(d, name).also { f -> f.mkdirs() } }
        write(File(d, "leaf.txt"))
        val r = deleter().delete(File(tmp.root, "long").path, DeleteMode.TREE, null)
        assertThat(r.status).isEqualTo(DeleteStatus.OK)
        assertThat(File(tmp.root, "long").exists()).isFalse()
    }

    @Test fun `single file deletion removes only that file`() {
        val a = File(tmp.root, "a.txt").also { write(it) }
        val b = File(tmp.root, "b.txt").also { write(it) }
        val r = deleter().delete(a.path, DeleteMode.FILE, NioFsOps.lstat(a.path))
        assertThat(r.status).isEqualTo(DeleteStatus.OK)
        assertThat(a.exists()).isFalse()
        assertThat(b.exists()).isTrue()
    }

    // ---------- child-level race: swapped between inspection and removal (M3) ----------

    @Test fun `a child swapped for a different file after it was inspected is left alone`() {
        val root = dir("racy-file")
        val victim = File(root, "a.txt").also { write(it, "scanned original") }
        val other = File(root, "b.txt").also { write(it) }
        // Created while the victim still exists, so it is guaranteed to have a different inode.
        val replacement = File(tmp.root, "replacement.txt").also { write(it, "the user's new file") }
        var swapped = false
        val fs = HookedFs().also {
            it.afterLstat = { p ->
                if (!swapped && p == victim.path) {
                    swapped = true
                    Files.move(replacement.toPath(), victim.toPath(), StandardCopyOption.REPLACE_EXISTING)
                }
            }
        }
        val r = deleter(fs).delete(root.path, DeleteMode.CONTENTS_ONLY, null)
        assertThat(swapped).isTrue()
        assertThat(victim.readText()).isEqualTo("the user's new file")
        assertThat(r.status).isNotEqualTo(DeleteStatus.OK)
        assertThat(other.exists()).isFalse() // the untouched sibling is still cleaned
    }

    @Test fun `a sub-folder swapped for a symlink to elsewhere is never entered or emptied`() {
        val outside = dir("outside-race")
        val precious = File(outside, "precious.txt").also { write(it) }
        val root = dir("racy-dir")
        val sub = dir("racy-dir", "sub") // empty folder, replaced by a link right after it was inspected
        var swapped = false
        val fs = HookedFs().also {
            it.afterLstat = { p ->
                if (!swapped && p == sub.path) {
                    swapped = true
                    Files.delete(sub.toPath())
                    Files.createSymbolicLink(sub.toPath(), outside.toPath())
                }
            }
        }
        val r = deleter(fs).delete(root.path, DeleteMode.TREE, null)
        assertThat(swapped).isTrue()
        assertThat(precious.exists()).isTrue()
        assertThat(r.status).isNotEqualTo(DeleteStatus.OK)
        assertThat(root.exists()).isTrue()
    }

    @Test fun `a single file swapped just before removal is not removed`() {
        val f = File(tmp.root, "photo.jpg").also { write(it, "original") }
        val scanned = NioFsOps.lstat(f.path)!!
        val replacement = File(tmp.root, "other.jpg").also { write(it, "original") } // same size, different inode
        replacement.setLastModified(f.lastModified())
        var calls = 0
        val fs = HookedFs().also {
            it.afterLstat = { p ->
                // 1st lstat = SafeDeleter.delete(), 2nd = the re-check right before remove(): swap after the 1st.
                if (p == f.path && ++calls == 1) {
                    Files.move(replacement.toPath(), f.toPath(), StandardCopyOption.REPLACE_EXISTING)
                }
            }
        }
        val r = deleter(fs).delete(f.path, DeleteMode.FILE, scanned)
        assertThat(r.status).isEqualTo(DeleteStatus.IDENTITY_CHANGED)
        assertThat(f.exists()).isTrue()
    }
}
