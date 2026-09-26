package com.nuvio.app.features.streams

import android.content.Context
import android.content.SharedPreferences
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities

internal actual object ConnectionSpeedStorage {
    private const val preferencesName = "nuvio_connection_speed"
    private const val samplesKey = "throughput_samples_v2"

    private var preferences: SharedPreferences? = null

    fun initialize(context: Context) {
        preferences = context.getSharedPreferences(preferencesName, Context.MODE_PRIVATE)
        context.getSystemService(ConnectivityManager::class.java)?.let(DefaultNetworkObserver::start)
    }

    actual fun load(): String? = preferences?.getString(samplesKey, null)

    actual fun save(value: String) {
        preferences
            ?.edit()
            ?.putString(samplesKey, value)
            ?.apply()
    }
}

internal actual fun currentNetworkKind(): NetworkKind? = DefaultNetworkObserver.kind

internal actual fun currentNetworkGeneration(): Int = DefaultNetworkObserver.generation

/**
 * Follows the default network through one system callback, so reading its kind on the stream
 * load and playback paths is a field read rather than a binder call into the system server.
 */
private object DefaultNetworkObserver : ConnectivityManager.NetworkCallback() {
    @Volatile var kind: NetworkKind? = null
        private set

    @Volatile var generation = 0
        private set

    private var network: Network? = null
    private var isStarted = false

    @Synchronized
    fun start(manager: ConnectivityManager) {
        if (isStarted) return
        isStarted = true
        // Seed synchronously; the callback's first update arrives asynchronously.
        runCatching { manager.activeNetwork?.let { active -> update(active, manager.getNetworkCapabilities(active)) } }
        runCatching { manager.registerDefaultNetworkCallback(this) }
    }

    override fun onCapabilitiesChanged(network: Network, capabilities: NetworkCapabilities) {
        update(network, capabilities)
    }

    /** A switch can report the new default before the old one is lost, so only the current one clears. */
    @Synchronized
    override fun onLost(network: Network) {
        if (network == this.network) update(null, null)
    }

    /** Called on every signal-strength change too, so it only bumps [generation] on real changes. */
    @Synchronized
    private fun update(network: Network?, capabilities: NetworkCapabilities?) {
        val newKind = capabilities?.toNetworkKind()
        if (network == this.network && newKind == kind) return
        this.network = network
        kind = newKind
        generation++
    }

    private fun NetworkCapabilities.toNetworkKind(): NetworkKind = when {
        hasTransport(NetworkCapabilities.TRANSPORT_WIFI) ||
            hasTransport(NetworkCapabilities.TRANSPORT_ETHERNET) -> NetworkKind.WIFI
        hasTransport(NetworkCapabilities.TRANSPORT_CELLULAR) -> NetworkKind.CELLULAR
        else -> NetworkKind.OTHER
    }
}
