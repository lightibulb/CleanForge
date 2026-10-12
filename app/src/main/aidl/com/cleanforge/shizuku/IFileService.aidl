package com.cleanforge.shizuku;

/**
 * Runs inside the Shizuku "user service" process (shell UID 2000, or root if the user
 * started Shizuku in root mode). It is the ONLY place privileged filesystem work happens.
 *
 * Every method re-validates its arguments; the app process is not trusted blindly.
 * Nothing here ever spawns a shell or builds a command line from a path.
 *
 * COMPATIBILITY RULE: ids 1-5 and destroy() never change meaning. New methods are only appended with new ids.
 * Ids 2-5 are the original null-means-everything methods; the app no longer calls them (it uses the *Ex methods,
 * which say WHY there is no value) but the service still answers them for an older app build.
 * Reply layouts of the *Ex methods are defined and unit-tested in WireCodec.kt; bump PrivilegedProtocol.API_VERSION
 * when one changes.
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

    // ---- appended in protocol version 1 ----

    /** Protocol version of the running service (PrivilegedProtocol.API_VERSION). */
    int apiVersion() = 6;

    /** [status] or [status, type, size, mtimeMs, dev, ino]. Status values: see ReplyStatus. */
    long[] statEx(String path) = 7;

    /** ["<status>", "<truncated 0/1>", name...]. Total name length is bounded to stay below the binder limit. */
    String[] listEx(String path) = 8;

    /** [status] or [status, bytes, complete(0/1), unreadableDirs]. Bounded walk. */
    long[] sizeEx(String path) = 9;

    /** ["<status>", "<entriesRemoved>", "<bytesFreed>", "<detail>"]. Status values: see DeleteStatus. */
    String[] deleteEx(String path, int mode, long dev, long ino, boolean hasIdentity) = 10;

    /** Stops the delete in progress before its next entry. Best effort; a delete not yet started is unaffected. */
    void cancelCurrent() = 11;

    /** Replaces the installed-apps guard for tree deletions. Returns names accepted, or -1 if the list was rejected. */
    int setProtectedPackages(in String[] packages) = 12;
}
