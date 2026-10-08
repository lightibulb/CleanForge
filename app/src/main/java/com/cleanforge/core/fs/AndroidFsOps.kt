package com.cleanforge.core.fs

import android.system.Os
import android.system.OsConstants
import java.io.File

/** Real implementation. Uses lstat(2)/remove(3) via android.system.Os, so symlinks are never followed. */
object AndroidFsOps : FsOps {

    override fun lstat(path: String): FsStat? = try {
        val st = Os.lstat(path)
        val type = when {
            OsConstants.S_ISLNK(st.st_mode) -> FsType.SYMLINK
            OsConstants.S_ISDIR(st.st_mode) -> FsType.DIRECTORY
            OsConstants.S_ISREG(st.st_mode) -> FsType.FILE
            else -> FsType.OTHER
        }
        FsStat(type, st.st_size, st.st_mtime * 1000L, st.st_dev, st.st_ino)
    } catch (e: Exception) {
        null
    }

    override fun list(path: String): List<String>? = try {
        File(path).list()?.toList()
    } catch (e: SecurityException) {
        null
    }

    override fun remove(path: String): Boolean = try {
        Os.remove(path)
        true
    } catch (e: Exception) {
        false
    }
}
