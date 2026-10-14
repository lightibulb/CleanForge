// core/fs/SafeDeleter.kt
package com.cleanforge.core.fs

/**
 * The single implementation of deletion. Used by the app process AND by the Shizuku service.
 * Guarantees:
 * - symlinks are never followed; a symlink target/root is refused, a symlink child is unlinked as a link only;
 * - recursion is explicit-depth-bounded and entry-bounded;
 * - a different st_dev (mount point) below the root is never entered or deleted;
 * - the root's (dev, ino) must match what the scan saw, and each directory is re-checked
 *   immediately before its entries are removed;
 * - every entry (file, link, sub-folder, the root itself) is lstat'ed AGAIN immediately before remove() and is
 *   left alone, with a recorded problem, unless it is still the same object (type, device, inode) that was
 *   inspected a moment earlier;
 * - no shell, no command line, no path concatenation outside "<dir>/<plain name>".
 * Residual risk (documented in SECURITY.md): the re-check and remove() are still two separate path-based
 * calls, so an attacker with write access to the same directory who wins that microsecond race can swap an entry
 * in between. Android offers no unlinkat/openat from Kotlin. A swap can never make remove() follow a symlink:
 * the worst case is that the swapped-in entry itself (or the empty directory it replaced) is unlinked.
 */
class SafeDeleter(
    private val fs: FsOps,
    private val limits: WalkLimits = WalkLimits(maxDepth = 48, maxEntries = 200_000),
    /*
     Polled between entries. When it returns true the walk stops BEFORE touching the next entry and the result is
     [DeleteStatus.CANCELLED] (or [DeleteStatus.PARTIAL]-like counts in the result). Never interrupts a single
     remove() call, and never changes deletion order or identity checks.
     */
    private val isCancelled: () -> Boolean = { false },
    /**
     * The system-owned storage root. Only folders BELOW it are inspected as ancestors of the target; the system's own
     * directories above it are not ours to judge (see [ancestorsAreSafe]).
     */
    private val trustedRoot: String = PathRules.ROOT
) {
    fun delete(path: String, mode: DeleteMode, expected: FsStat?): DeleteResult {
        // 1. Initial check on the target itself
        val st = fs.lstat(path) ?: return DeleteResult(DeleteStatus.MISSING, detail = "Path does not exist")
        
        if (st.type == FsType.SYMLINK) {
            return DeleteResult(DeleteStatus.WRONG_TYPE, detail = "Refusing to act on a symbolic link")
        }
        
        if (expected != null && !st.sameObjectAs(expected)) {
            return DeleteResult(DeleteStatus.IDENTITY_CHANGED, detail = "Object changed since it was scanned")
        }

        // 2. Every folder between the storage root and the target must still be a real directory (not a link).
        // This closes the TOCTOU gap between FileServiceCore's check and this execution.
        if (!ancestorsAreSafe(path)) {
            return DeleteResult(DeleteStatus.REFUSED, detail = "One or more parent directories are unsafe (symlink/non-dir)")
        }

        return when (mode) {
            DeleteMode.FILE -> removeLeaf(path, st, FsType.FILE)
            DeleteMode.EMPTY_DIR -> removeLeaf(path, st, FsType.DIRECTORY)
            DeleteMode.CONTENTS_ONLY -> removeDirectory(path, st, removeRoot = false)
            DeleteMode.TREE -> removeDirectory(path, st, removeRoot = true)
        }
    }

    /**
     * Every folder between [trustedRoot] and [path] must be a real directory, never a symlink or a file.
     *
     * Only the part BELOW [trustedRoot] is inspected. The directories above it belong to the system: on Android
     * "/storage" is a tmpfs, a different device than the shared-storage mount, so comparing device numbers up there
     * made every real deletion come back REFUSED. Device numbers of ancestors are deliberately not compared at all:
     * a mount point among the ancestors cannot redirect the deletion, and mounts INSIDE the tree are handled in
     * [purge]. A path that is not under [trustedRoot] (only tests; the validators confine production paths to
     * PathRules.ROOT) has all of its ancestors inspected.
     */
    private fun ancestorsAreSafe(path: String): Boolean {
        val parts = path.split('/').filter { it.isNotEmpty() }
        if (parts.size < 2) return true
        val rootParts = trustedRoot.split('/').filter { it.isNotEmpty() }
        val underRoot = parts.size > rootParts.size && parts.subList(0, rootParts.size) == rootParts
        val firstInspected = if (underRoot) rootParts.size else 0
        var current = ""
        for (i in 0 until parts.size - 1) { // the last part is the target itself, already inspected
            current += "/" + parts[i]
            if (i < firstInspected) continue
            val stat = fs.lstat(current) ?: return false // a missing parent is fatal
            if (stat.type != FsType.DIRECTORY) return false // lstat reports a link as SYMLINK, so links land here
        }
        return true
    }

    private fun removeLeaf(path: String, st: FsStat, expectedType: FsType): DeleteResult {
        if (st.type != expectedType) {
            return DeleteResult(DeleteStatus.WRONG_TYPE, detail = "Expected $expectedType but found ${st.type}")
        }
        // Narrow the check-to-remove window: the object must still be the one inspected a moment ago.
        val cur = fs.lstat(path) ?: return DeleteResult(DeleteStatus.MISSING, detail = "Vanished before removal")
        if (!cur.sameIdentityAs(st)) {
            return DeleteResult(DeleteStatus.IDENTITY_CHANGED, detail = "Object was replaced just before removal")
        }
        if (!fs.remove(path)) {
            return DeleteResult(
                DeleteStatus.FAILED,
                detail = if (expectedType == FsType.DIRECTORY) "Directory is not empty or not removable" else "Could not remove file"
            )
        }
        if (fs.lstat(path) != null) return DeleteResult(DeleteStatus.FAILED, detail = "Still present after removal")
        return DeleteResult(DeleteStatus.OK, 1, if (expectedType == FsType.FILE) st.size else 0L)
    }

    private class Ctx(val rootDev: Long) {
        var entries = 0
        var bytes = 0L
        var failed = 0
        var visited = 0
        var limit = false
        var cancelled = false
        var firstProblem = ""
    }

    private fun removeDirectory(root: String, rootStat: FsStat, removeRoot: Boolean): DeleteResult {
        if (rootStat.type != FsType.DIRECTORY) {
            return DeleteResult(DeleteStatus.WRONG_TYPE, detail = "Expected a directory but found ${rootStat.type}")
        }
        val ctx = Ctx(rootStat.dev)
        val clean = purge(root, rootStat, 0, ctx)
        if (clean && removeRoot) {
            removeIfUnchanged(root, rootStat, ctx, "Could not remove the folder itself")
        }
        val status = when {
            ctx.cancelled -> DeleteStatus.CANCELLED
            ctx.limit -> DeleteStatus.LIMIT_EXCEEDED
            ctx.failed == 0 -> DeleteStatus.OK
            ctx.entries > 0 -> DeleteStatus.PARTIAL
            else -> DeleteStatus.FAILED
        }
        return DeleteResult(status, ctx.entries, ctx.bytes, ctx.firstProblem)
    }

    private fun problem(ctx: Ctx, message: String) {
        ctx.failed++
        if (ctx.firstProblem.isEmpty()) ctx.firstProblem = message
    }

    /**
     * Removes [path] only if it is still the object [seen] described (same type, device and inode).
     * Returns true if the entry is gone afterwards (removed by us, or already vanished); false if it was replaced
     * or could not be removed, in which case a problem is recorded and the entry is left untouched.
     */
    private fun removeIfUnchanged(path: String, seen: FsStat, ctx: Ctx, failure: String): Boolean {
        val cur = fs.lstat(path) ?: return true // vanished meanwhile: nothing left to do
        if (!cur.sameIdentityAs(seen)) {
            problem(ctx, "An entry was replaced while cleaning; it was left untouched")
            return false
        }
        if (!fs.remove(path)) {
            problem(ctx, failure)
            return false
        }
        ctx.entries++
        if (seen.type == FsType.FILE) ctx.bytes += seen.size
        return true
    }

    private fun FsStat.sameIdentityAs(other: FsStat): Boolean =
        type == other.type && dev == other.dev && ino == other.ino

    /** Empties [dir]. Returns true only if every entry was removed. */
    private fun purge(dir: String, dirStat: FsStat, depth: Int, ctx: Ctx): Boolean {
        if (depth >= limits.maxDepth) {
            ctx.limit = true
            ctx.firstProblem = "Folder nesting is deeper than ${limits.maxDepth}"
            return false
        }
        val names = fs.list(dir)
        if (names == null) {
            problem(ctx, "Cannot list a folder")
            return false
        }
        // Re-verify identity right before mutating this directory (narrows the swap window).
        val again = fs.lstat(dir)
        if (again == null || again.type != FsType.DIRECTORY || again.dev != dirStat.dev || again.ino != dirStat.ino) {
            problem(ctx, "A folder was replaced while cleaning")
            return false
        }

        var clean = true
        for (name in names) {
            if (isCancelled()) {
                ctx.cancelled = true
                if (ctx.firstProblem.isEmpty()) ctx.firstProblem = "Stopped before finishing"
                return false
            }
            if (!StorageWalker.isPlainName(name)) {
                problem(ctx, "Skipped an entry with an unsafe name")
                clean = false
                continue
            }
            ctx.visited++
            if (ctx.visited > limits.maxEntries) {
                ctx.limit = true
                ctx.firstProblem = "More than ${limits.maxEntries} entries"
                return false
            }
            val child = "$dir/$name"
            val cs = fs.lstat(child) ?: continue // vanished: fine
            if (cs.dev != ctx.rootDev) {
                problem(ctx, "Skipped a different filesystem (mount point)")
                clean = false
                continue
            }
            when (cs.type) {
                FsType.DIRECTORY -> {
                    if (!purge(child, cs, depth + 1, ctx) ||
                        !removeIfUnchanged(child, cs, ctx, "Could not remove a sub-folder")
                    ) {
                        clean = false
                    }
                }
                else -> {
                    // FILE, SYMLINK (the link itself, never its target) or OTHER (socket, fifo...).
                    if (!removeIfUnchanged(child, cs, ctx, "Could not remove an entry")) clean = false
                }
            }
            if (ctx.limit || ctx.cancelled) return false
        }
        return clean
    }
}
