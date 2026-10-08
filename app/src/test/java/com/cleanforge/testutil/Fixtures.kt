package com.cleanforge.testutil

import com.cleanforge.core.backend.ArchiveInfo
import com.cleanforge.core.backend.Capabilities
import com.cleanforge.core.backend.PackageInventory
import com.cleanforge.core.fs.DeleteMode
import com.cleanforge.core.fs.FsOps
import com.cleanforge.core.fs.FsStat
import com.cleanforge.core.fs.FsType
import com.cleanforge.core.model.CleaningCategory
import com.cleanforge.core.model.ConfidenceLevel
import com.cleanforge.core.model.Reversibility
import com.cleanforge.core.model.RiskLevel
import com.cleanforge.core.model.ScanItem
import java.nio.file.Files
import java.nio.file.LinkOption
import java.nio.file.Paths
import java.nio.file.attribute.FileTime

/** Real filesystem (temp dirs) accessed exactly like production: lstat, no symlink following. */
object NioFsOps : FsOps {
    override fun lstat(path: String): FsStat? {
        return try {
            val p = Paths.get(path)
            if (!Files.exists(p, LinkOption.NOFOLLOW_LINKS)) return null
            val a = Files.readAttributes(p, "unix:dev,ino,size,lastModifiedTime", LinkOption.NOFOLLOW_LINKS)
            val type = when {
                Files.isSymbolicLink(p) -> FsType.SYMLINK
                Files.isDirectory(p, LinkOption.NOFOLLOW_LINKS) -> FsType.DIRECTORY
                Files.isRegularFile(p, LinkOption.NOFOLLOW_LINKS) -> FsType.FILE
                else -> FsType.OTHER
            }
            FsStat(
                type,
                (a["size"] as Number).toLong(),
                (a["lastModifiedTime"] as FileTime).toMillis(),
                (a["dev"] as Number).toLong(),
                (a["ino"] as Number).toLong()
            )
        } catch (e: Exception) {
            null
        }
    }

    override fun list(path: String): List<String>? = try {
        Files.newDirectoryStream(Paths.get(path)).use { ds -> ds.map { it.fileName.toString() } }
    } catch (e: Exception) {
        null
    }

    override fun remove(path: String): Boolean = try {
        Files.delete(Paths.get(path))
        true
    } catch (e: Exception) {
        false
    }
}

/** Wraps a real FsOps to simulate mount points and races. */
class HookedFs(private val delegate: FsOps = NioFsOps) : FsOps {
    var devOverride: (String) -> Long? = { null }
    var beforeRemove: (String) -> Unit = {}

    /** Runs AFTER an lstat result was computed but BEFORE it is returned: models a swap right after inspection. */
    var afterLstat: (String) -> Unit = {}
    val removed = ArrayList<String>()

    override fun lstat(path: String): FsStat? {
        val s = delegate.lstat(path)
        val dev = devOverride(path)
        val result = when {
            s == null -> null
            dev != null -> s.copy(dev = dev)
            else -> s
        }
        afterLstat(path)
        return result
    }

    override fun list(path: String): List<String>? = delegate.list(path)

    override fun remove(path: String): Boolean {
        removed += path
        beforeRemove(path)
        return delegate.remove(path)
    }
}

/** Deterministic in-memory tree whose paths can live under /storage/emulated/0 (what the validator demands). */
class InMemoryFs : FsOps {
    private class Node(val type: FsType, var size: Long, var mtime: Long, val ino: Long)

    private val nodes = LinkedHashMap<String, Node>()
    private var nextIno = 1L
    val denyRemove = HashSet<String>()

    fun mkdirs(path: String) {
        var cur = ""
        for (part in path.substring(1).split('/')) {
            cur += "/$part"
            if (cur !in nodes) nodes[cur] = Node(FsType.DIRECTORY, 0, 1_000, nextIno++)
        }
    }

    fun file(path: String, size: Long = 100, mtime: Long = 1_000) {
        mkdirs(path.substringBeforeLast('/'))
        nodes[path] = Node(FsType.FILE, size, mtime, nextIno++)
    }

    fun symlink(path: String) {
        mkdirs(path.substringBeforeLast('/'))
        nodes[path] = Node(FsType.SYMLINK, 0, 1_000, nextIno++)
    }

    fun exists(path: String) = path in nodes

    fun touch(path: String, mtime: Long) {
        nodes[path]!!.mtime = mtime
    }

    fun replaceWithSymlink(path: String) {
        nodes.remove(path)
        symlink(path)
    }

    fun resize(path: String, size: Long) {
        nodes[path]!!.size = size
    }

    override fun lstat(path: String): FsStat? = nodes[path]?.let { FsStat(it.type, it.size, it.mtime, 1L, it.ino) }

    override fun list(path: String): List<String>? {
        val n = nodes[path] ?: return null
        if (n.type != FsType.DIRECTORY) return null
        val prefix = "$path/"
        return nodes.keys.filter { it.startsWith(prefix) && !it.substring(prefix.length).contains('/') }
            .map { it.substring(prefix.length) }
    }

    override fun remove(path: String): Boolean {
        val n = nodes[path] ?: return false
        if (path in denyRemove) return false
        if (n.type == FsType.DIRECTORY && list(path)!!.isNotEmpty()) return false
        nodes.remove(path)
        return true
    }
}

class FakeCapabilities(var allFiles: Boolean = true, var shizuku: Boolean = true) : Capabilities {
    override fun hasAllFilesAccess() = allFiles
    override fun shizukuAuthorized() = shizuku
}

class FakeInventory(
    /** The MATCH_UNINSTALLED_PACKAGES view: installed apps AND apps uninstalled with their data kept. */
    var known: Set<String>? = defaultPackages(),
    private val versions: Map<String, Long> = emptyMap(),
    private val archives: Map<String, ArchiveInfo> = emptyMap(),
    /** What is really installed right now. Null means "exactly [known]" (no data-kept uninstalls on this device). */
    var installed: Set<String>? = null
) : PackageInventory {
    override fun knownPackageNames(): Set<String>? = known
    override fun isCurrentlyInstalled(packageName: String): Boolean = (installed ?: known)?.contains(packageName) == true
    override fun installedVersionCode(packageName: String): Long? = versions[packageName]
    override fun archiveInfo(apkPath: String): ArchiveInfo? = archives[apkPath.substringAfterLast('/')]

    companion object {
        /** Plausible device: well over MIN_PLAUSIBLE_PACKAGES. */
        fun defaultPackages(vararg extra: String): Set<String> =
            (1..40).map { "com.system.pkg$it" }.toSet() + extra
    }
}

fun scanItem(
    path: String,
    category: CleaningCategory = CleaningCategory.LARGE_FILE,
    confidence: ConfidenceLevel = ConfidenceLevel.UNKNOWN,
    risk: RiskLevel = RiskLevel.DANGEROUS,
    pkg: String? = null,
    shizuku: Boolean = false,
    size: Long = 100,
    fingerprint: FsStat? = null,
    keepPath: String? = null,
    keepFingerprint: FsStat? = null,
    mode: DeleteMode? = null,
    groupId: String? = null
): ScanItem {
    val m = mode ?: when (category) {
        CleaningCategory.APP_CACHE, CleaningCategory.THUMBNAIL -> DeleteMode.CONTENTS_ONLY
        CleaningCategory.CORPSE_LEFTOVER -> DeleteMode.TREE
        CleaningCategory.EMPTY_DIRECTORY -> DeleteMode.EMPTY_DIR
        else -> DeleteMode.FILE
    }
    return ScanItem(
        path = path,
        sizeBytes = size,
        category = category,
        reasonForFlagging = "test",
        confidence = confidence,
        risk = risk,
        owningPackage = pkg,
        requiresShizuku = shizuku,
        deleteMode = m,
        reversibility = Reversibility.NOT_REVERSIBLE,
        fingerprint = fingerprint,
        keepPath = keepPath,
        keepFingerprint = keepFingerprint,
        groupId = groupId
    )
}

const val ROOT = "/storage/emulated/0"
