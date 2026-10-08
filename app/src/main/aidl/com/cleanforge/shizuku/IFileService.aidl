package com.cleanforge.shizuku;

/**
 * Runs inside the Shizuku "user service" process (shell UID 2000, or root if the user
 * started Shizuku in root mode). It is the ONLY place privileged filesystem work happens.
 *
 * Every method re-validates its arguments; the app process is not trusted blindly.
 * Nothing here ever spawns a shell or builds a command line from a path.
 */
interface IFileService {
    // Transaction id reserved by Shizuku for service teardown.
    void destroy() = 16777114;

    int uid() = 1;

    /** [type, size, mtimeMs, dev, ino] or null if missing / not allowed. Never follows symlinks. */
    long[] lstat(String path) = 2;

    /** Child NAMES (not paths) of an allowed directory, or null. Capped in size. */
    String[] list(String path) = 3;

    /** [bytes, complete(0/1)] or null. Bounded walk; never crosses mounts or follows symlinks. */
    long[] directorySize(String path) = 4;

    /** [status, entriesRemoved, bytesFreed]. Directory identity (dev, ino) is mandatory. */
    long[] delete(String path, int mode, long dev, long ino, boolean hasIdentity) = 5;
}
