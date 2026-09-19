package com.appvalence.umay.cloud

import android.content.Context
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import android.net.NetworkRequest
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.coroutines.flow.distinctUntilChanged

/**
 * Tracks NET_CAPABILITY_VALIDATED (the system has confirmed this network can
 * actually reach the internet, not just that Wi-Fi/mobile data is nominally
 * connected — a captive portal or a dead access point reports connected but
 * not validated) across every network the device has, so a phone with both
 * Wi-Fi and mobile data doesn't report "offline" during a brief handover.
 */
class AndroidConnectivityMonitor(context: Context) : ConnectivityMonitor {
    private val connectivityManager = context.applicationContext.getSystemService(ConnectivityManager::class.java)

    override val isOnline: Flow<Boolean> = callbackFlow {
        val validatedNetworks = mutableSetOf<Network>()
        fun send() = trySend(validatedNetworks.isNotEmpty())

        val manager = connectivityManager
        if (manager == null) {
            trySend(false)
            close()
            return@callbackFlow
        }

        val callback = object : ConnectivityManager.NetworkCallback() {
            override fun onCapabilitiesChanged(network: Network, capabilities: NetworkCapabilities) {
                val validated = capabilities.hasCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED) &&
                    capabilities.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
                if (validated) validatedNetworks += network else validatedNetworks -= network
                send()
            }

            override fun onLost(network: Network) {
                validatedNetworks -= network
                send()
            }
        }

        val request = NetworkRequest.Builder()
            .addCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
            .build()
        manager.registerNetworkCallback(request, callback)
        send() // the initial state, in case nothing changes right after

        awaitClose { manager.unregisterNetworkCallback(callback) }
    }.distinctUntilChanged()
}
