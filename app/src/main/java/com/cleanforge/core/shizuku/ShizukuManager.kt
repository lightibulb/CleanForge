package com.cleanforge.core.shizuku

import android.content.Context
import android.content.pm.PackageManager
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import rikka.shizuku.Shizuku
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Tracks the real Shizuku situation: installed? running? permission? Shizuku is NOT root (unless the user
 * deliberately started it in root mode, which is surfaced via [ShizukuState.Authorized.isRoot]).
 */
@Singleton
class ShizukuManager @Inject constructor(
    @ApplicationContext private val context: Context
) {
    private val _state = MutableStateFlow<ShizukuState>(ShizukuState.NotRunning)
    val state: StateFlow<ShizukuState> = _state

    private val binderReceived = Shizuku.OnBinderReceivedListener { refresh() }
    private val binderDead = Shizuku.OnBinderDeadListener { _state.value = ShizukuState.Disconnected }
    private val permissionResult = Shizuku.OnRequestPermissionResultListener { _, _ -> refresh() }

    /** Call once from Application.onCreate. Listeners live as long as the process. */
    fun register() {
        Shizuku.addBinderReceivedListenerSticky(binderReceived)
        Shizuku.addBinderDeadListener(binderDead)
        Shizuku.addRequestPermissionResultListener(permissionResult)
        refresh()
    }

    fun unregister() {
        runCatching { Shizuku.removeBinderReceivedListener(binderReceived) }
        runCatching { Shizuku.removeBinderDeadListener(binderDead) }
        runCatching { Shizuku.removeRequestPermissionResultListener(permissionResult) }
    }

    fun refresh() {
        _state.value = currentState()
    }

    private fun currentState(): ShizukuState = try {
        when {
            !Shizuku.pingBinder() ->
                if (isShizukuAppInstalled()) ShizukuState.NotRunning else ShizukuState.NotInstalled
            Shizuku.isPreV11() -> ShizukuState.Error("Shizuku v11 or newer is required. Update the Shizuku app.")
            Shizuku.checkSelfPermission() == PackageManager.PERMISSION_GRANTED ->
                ShizukuState.Authorized(Shizuku.getUid())
            else -> ShizukuState.PermissionDenied
        }
    } catch (t: Throwable) {
        ShizukuState.Error(t.message ?: "Unknown Shizuku error")
    }

    @Suppress("DEPRECATION")
    private fun isShizukuAppInstalled(): Boolean = try {
        context.packageManager.getPackageInfo(SHIZUKU_PACKAGE, 0)
        true
    } catch (e: PackageManager.NameNotFoundException) {
        false
    }

    fun requestPermission(requestCode: Int = REQUEST_CODE) {
        try {
            if (!Shizuku.pingBinder() || Shizuku.isPreV11()) {
                refresh()
                return
            }
            if (Shizuku.checkSelfPermission() == PackageManager.PERMISSION_GRANTED) {
                refresh()
                return
            }
            Shizuku.requestPermission(requestCode)
        } catch (t: Throwable) {
            _state.value = ShizukuState.Error(t.message ?: "Permission request failed")
        }
    }

    companion object {
        const val SHIZUKU_PACKAGE = "moe.shizuku.privileged.api"
        const val REQUEST_CODE = 4201
    }
}
