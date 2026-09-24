package com.nuvio.app.features.streams

import android.content.Context
import android.content.SharedPreferences
import android.net.ConnectivityManager
import android.net.NetworkCapabilities

internal actual object ConnectionSpeedStorage {
    private const val preferencesName = "nuvio_connection_speed"
    private const val samplesKey = "throughput_samples_v2"

    private var preferences: SharedPreferences? = null
    internal var connectivityManager: ConnectivityManager? = null
        private set

    fun initialize(context: Context) {
        preferences = context.getSharedPreferences(preferencesName, Context.MODE_PRIVATE)
        connectivityManager = context.getSystemService(ConnectivityManager::class.java)
    }

    actual fun load(): String? = preferences?.getString(samplesKey, null)

    actual fun save(value: String) {
        preferences
            ?.edit()
            ?.putString(samplesKey, value)
            ?.apply()
    }
}

internal actual fun currentNetworkKind(): NetworkKind? {
    val manager = ConnectionSpeedStorage.connectivityManager ?: return null
    val capabilities = runCatching { manager.getNetworkCapabilities(manager.activeNetwork) }.getOrNull()
        ?: return null
    return when {
        capabilities.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) ||
            capabilities.hasTransport(NetworkCapabilities.TRANSPORT_ETHERNET) -> NetworkKind.WIFI
        capabilities.hasTransport(NetworkCapabilities.TRANSPORT_CELLULAR) -> NetworkKind.CELLULAR
        else -> NetworkKind.OTHER
    }
}
