package com.nuvio.app.features.streams

import kotlinx.cinterop.ExperimentalForeignApi
import kotlin.concurrent.Volatile
import platform.Foundation.NSUserDefaults
import platform.Network.nw_interface_type_cellular
import platform.Network.nw_interface_type_wifi
import platform.Network.nw_interface_type_wired
import platform.Network.nw_path_get_status
import platform.Network.nw_path_monitor_create
import platform.Network.nw_path_monitor_set_queue
import platform.Network.nw_path_monitor_set_update_handler
import platform.Network.nw_path_monitor_start
import platform.Network.nw_path_status_satisfied
import platform.Network.nw_path_uses_interface_type
import platform.darwin.dispatch_queue_create

internal actual object ConnectionSpeedStorage {
    private const val samplesKey = "connection_speed_throughput_samples_v2"

    actual fun load(): String? =
        NSUserDefaults.standardUserDefaults.stringForKey(samplesKey)

    actual fun save(value: String) {
        NSUserDefaults.standardUserDefaults.setObject(value, forKey = samplesKey)
    }
}

internal actual fun currentNetworkKind(): NetworkKind? = NetworkPathObserver.currentKind

internal actual fun currentNetworkGeneration(): Int = NetworkPathObserver.generation

/** Tracks the default network path. Started at app launch because the first update arrives asynchronously. */
@OptIn(ExperimentalForeignApi::class)
internal object NetworkPathObserver {
    @Volatile var currentKind: NetworkKind? = null
        private set

    /**
     * Bumped on every path update after the first. The path doesn't identify a particular
     * Wi-Fi network, so any reported change is treated as a possible switch.
     */
    @Volatile var generation = 0
        private set

    private var isStarted = false

    fun start() {
        if (isStarted) return
        isStarted = true
        val monitor = nw_path_monitor_create()
        var isFirstUpdate = true
        nw_path_monitor_set_update_handler(monitor) { path ->
            if (!isFirstUpdate) generation++
            isFirstUpdate = false
            currentKind = when {
                nw_path_get_status(path) != nw_path_status_satisfied -> null
                nw_path_uses_interface_type(path, nw_interface_type_wifi) ||
                    nw_path_uses_interface_type(path, nw_interface_type_wired) -> NetworkKind.WIFI
                nw_path_uses_interface_type(path, nw_interface_type_cellular) -> NetworkKind.CELLULAR
                else -> NetworkKind.OTHER
            }
        }
        nw_path_monitor_set_queue(monitor, dispatch_queue_create("com.nuvio.network-path", null))
        nw_path_monitor_start(monitor)
    }
}
