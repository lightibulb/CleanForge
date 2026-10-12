package com.cleanforge.core.privileged

/**
 * Every operation CleanForge performs through the Shizuku user service. This list is closed on purpose:
 * the service exposes a fixed, small surface and never accepts a command line (see [FileServiceCore]).
 *
 * "Privileged" here means "runs as the shell user (uid 2000), or root only if the user started Shizuku in root
 * mode". It does NOT mean unrestricted: the shell user cannot read other apps' private data under /data/data.
 */
enum class PrivilegedOperation(val title: String, val mutating: Boolean) {
    /** List the folders directly inside Android/data and Android/obb. */
    LIST_EXTERNAL_APP_DATA("List Android/data and Android/obb", mutating = false),

    /** Measure the size of a folder below Android/data or Android/obb (logical bytes, bounded walk). */
    MEASURE_EXTERNAL_APP_DIR("Measure folder sizes in Android/data and Android/obb", mutating = false),

    /** Delete the contents of Android/data/<package>/cache of an INSTALLED app. The folder stays. */
    CLEAR_EXTERNAL_APP_CACHE("Clear an installed app's external cache", mutating = true),

    /** Delete Android/data/<package> or Android/obb/<package> of an app Android no longer knows. */
    REMOVE_UNINSTALLED_APP_DATA("Remove an uninstalled app's leftover folder", mutating = true)
}
