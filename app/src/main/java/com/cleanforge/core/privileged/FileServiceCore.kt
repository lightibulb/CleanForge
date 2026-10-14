package com.cleanforge.core.privileged

import com.cleanforge.core.fs.DeleteMode
import com.cleanforge.core.fs.DeleteResult
import com.cleanforge.core.fs.DeleteStatus
import com.cleanforge.core.fs.FsOps
import com.cleanforge.core.fs.FsProbe
import com.cleanforge.core.fs.FsStat
import com.cleanforge.core.fs.FsType
import com.cleanforge.core.fs.PathException
import com.cleanforge.core.fs.PathRules
import com.cleanforge.core.fs.SafeDeleter
import com.cleanforge.core.fs.StorageWalker
import com.cleanforge.core.fs.WalkLimits

/**
 * ALL the logic that runs inside the privileged Shizuku process. `ShizukuFileService` is only a thin Binder
 * adapter around this class; keeping the logic here lets the JVM unit tests run the real thing.
 * It trusts nothing from the caller:
 * - every path must be canonical and below Android/data or Android/obb ([PathRules]);
 * - every ancestor of the path below the storage root must be a real directory (never a symlink), because lstat
 *   only avoids following the LAST component;
 * - only two recursive delete shapes exist, and a tree delete is refused unless the app has installed the
 *   installed-apps guard ([setProtectedPackages]) and the package is not in it;
 * - there is no shell, no command line, no arbitrary-path method.
 * The installed-apps guard protects against a logic bug in the app, NOT against a hostile app: a hostile app could
 * simply send an empty list. Only the app that Shizuku authorized can reach this service at all.
 */
class FileServiceCore(
    private val fs: FsOps,
    /* The app's own package. Its folders are never removed as a "leftover". */
    private val ownPackage: String,
    private val walkLimits: WalkLimits = WalkLimits(),
    private val maxNames: Int = MAX_NAMES,
    /* Budget for the total length of names in one listing, to stay far below the 1 MB binder transaction limit. */
    private val maxListChars: Int = MAX_LIST_CHARS
) {
    private val walker = StorageWalker(fs)
    @Volatile private var cancelRequested = false
    /** Lower-cased (shared storage is case-insensitive). Null until the app has pushed a list. */
    @Volatile private var protectedLower: Set<String>? = null

    // ------------------------------------------------------------------ reads
    fun stat(path: String?): StatReply {
        val p = path ?: return StatReply(ReplyStatus.REFUSED)
        val gate = readGate(p)
        if (gate != ReplyStatus.OK) return StatReply(gate)
        return when (val r = fs.probe(p)) {
            is FsProbe.Found -> StatReply(ReplyStatus.OK, r.stat)
            FsProbe.Missing -> StatReply(ReplyStatus.NOT_FOUND)
            FsProbe.Denied -> StatReply(ReplyStatus.OS_DENIED)
            FsProbe.Error -> StatReply(ReplyStatus.FAILED)
        }
    }

    fun list(path: String?): ListReply {
        val p = path ?: return ListReply(ReplyStatus.REFUSED)
        val gate = readGate(p)
        if (gate != ReplyStatus.OK) return ListReply(gate)
        
        // 1. Probe to ensure it IS a directory and accessible
        val st = when (val r = fs.probe(p)) {
            is FsProbe.Found -> r.stat
            FsProbe.Missing -> return ListReply(ReplyStatus.NOT_FOUND)
            FsProbe.Denied -> return ListReply(ReplyStatus.OS_DENIED)
            FsProbe.Error -> return ListReply(ReplyStatus.FAILED)
        }
        if (st.type != FsType.DIRECTORY) return ListReply(ReplyStatus.FAILED) // Not a dir, but existed

        // 2. Attempt to list
        val names = fs.list(p)
        // If fs.list returns null HERE, it means either:
        // A) Race condition: became inaccessible between probe and list.
        // B) Implementation limitation: File.list() returned null despite being a dir.
        // Treat as OS_DENIED or FAILED conservatively.
        if (names == null) {
            return ListReply(ReplyStatus.OS_DENIED, detail = "Could not list contents of existing directory")
        }

        val out = ArrayList<String>()
        var chars = 0
        var truncated = false
        for (name in names) {
            if (!StorageWalker.isPlainName(name)) continue
            if (out.size >= maxNames || chars + name.length > maxListChars) {
                truncated = true
                break
            }
            out += name
            chars += name.length
        }
        return ListReply(ReplyStatus.OK, out, truncated)
    }

    fun size(path: String?): SizeReply {
        val p = path ?: return SizeReply(ReplyStatus.REFUSED)
        val gate = readGate(p)
        if (gate != ReplyStatus.OK) return SizeReply(gate)
        when (val r = fs.probe(p)) {
            is FsProbe.Found -> Unit
            FsProbe.Missing -> return SizeReply(ReplyStatus.NOT_FOUND)
            FsProbe.Denied -> return SizeReply(ReplyStatus.OS_DENIED)
            FsProbe.Error -> return SizeReply(ReplyStatus.FAILED)
        }
        val result = walker.sizeOf(p, walkLimits) ?: return SizeReply(ReplyStatus.FAILED)
        return SizeReply(ReplyStatus.OK, result)
    }

    // ------------------------------------------------------------------ guard + cancel
    /**
     * Replaces the installed-apps guard. Invalid names are ignored (they can never match a real package folder).
     * A list that is too long is rejected as a whole and the previous guard stays: truncating a guard would weaken it
     * silently. Returns the number of names accepted, or -1 on rejection.
     */
    fun setProtectedPackages(packages: Array<String>?): Int {
        if (packages == null || packages.size > MAX_PROTECTED) return -1
        val set = HashSet<String>()
        for (name in packages) {
            if (PathRules.isValidPackageName(name)) set += name.lowercase()
        }
        protectedLower = set
        return set.size
    }

    /** Stops the delete in progress before its next entry. A delete that has not started yet is not affected. */
    fun cancelCurrent() {
        cancelRequested = true
    }

    // ------------------------------------------------------------------ delete
    fun delete(path: String?, modeCode: Int, dev: Long, ino: Long, hasIdentity: Boolean): DeleteResult {
        val mode = DeleteMode.fromCode(modeCode)
        if (path == null || mode == null || !hasIdentity) return refused("Malformed delete request")
        if (!PathRules.isServiceDeletable(path, mode)) return refused("Not a folder this service is allowed to delete")
        
        val components = try {
            PathRules.componentsBelowRoot(path)
        } catch (e: PathException) {
            return refused(e.message ?: "Invalid path")
        }
        
        if (mode == DeleteMode.TREE) {
            val guard = protectedLower
                ?: return refused("The installed-apps guard was not set, so app data will not be deleted")
            val pkg = components[2]
            if (pkg.lowercase() in guard) return refused("$pkg is known to Android, so its folder is not a leftover")
            if (pkg.equals(ownPackage, ignoreCase = true)) return refused("CleanForge never deletes its own folders")
        }
        
        when (ancestorsStatus(path)) {
            ReplyStatus.OK -> Unit
            ReplyStatus.NOT_FOUND -> return DeleteResult(DeleteStatus.MISSING, detail = "A parent folder does not exist")
            ReplyStatus.REFUSED -> return refused("A parent of this path is not a plain folder")
            ReplyStatus.OS_DENIED -> return DeleteResult(DeleteStatus.FAILED, detail = "The OS refused access to a parent folder")
            ReplyStatus.FAILED -> return DeleteResult(DeleteStatus.FAILED, detail = "Could not examine a parent folder")
        }
        
        cancelRequested = false
        // Only directories are ever deleted here, so identity = (type, dev, ino).
        val expected = FsStat(FsType.DIRECTORY, 0L, 0L, dev, ino)
        return SafeDeleter(fs, isCancelled = { cancelRequested }).delete(path, mode, expected)
    }

    // ------------------------------------------------------------------ internals
    private fun refused(why: String) = DeleteResult(DeleteStatus.REFUSED, detail = why)
    
    private fun readGate(path: String): ReplyStatus {
        if (!PathRules.isServiceReadable(path)) return ReplyStatus.REFUSED
        return ancestorsStatus(path)
    }

    /** Every proper ancestor below the storage root must be a real directory. */
    private fun ancestorsStatus(path: String): ReplyStatus {
        val c = try {
            PathRules.componentsBelowRoot(path)
        } catch (e: PathException) {
            return ReplyStatus.REFUSED
        }
        var current = PathRules.ROOT
        for (i in 0 until c.size - 1) {
            current = "$current/${c[i]}"
            when (val r = fs.probe(current)) {
                is FsProbe.Found -> if (r.stat.type != FsType.DIRECTORY) return ReplyStatus.REFUSED
                FsProbe.Missing -> return ReplyStatus.NOT_FOUND
                FsProbe.Denied -> return ReplyStatus.OS_DENIED
                FsProbe.Error -> return ReplyStatus.FAILED
            }
        }
        return ReplyStatus.OK
    }

    companion object {
        const val MAX_NAMES = 20_000
        const val MAX_LIST_CHARS = 120_000
        const val MAX_PROTECTED = 5_000
    }
}
