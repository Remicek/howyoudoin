package com.devvalor.howyoudoing.app.connectivity

import android.content.Context
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import com.devvalor.howyoudoing.core.domain.connectivity.InternetConnection
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/** Online while the phone's default network has internet access (airplane mode or no signal: offline). */
class AndroidInternetConnection(context: Context) : InternetConnection {

    private val connectivity = context.getSystemService(ConnectivityManager::class.java)

    private val online = MutableStateFlow(hasInternet(connectivity.getNetworkCapabilities(connectivity.activeNetwork)))
    override val isOnline: StateFlow<Boolean> = online.asStateFlow()

    init {
        connectivity.registerDefaultNetworkCallback(object : ConnectivityManager.NetworkCallback() {
            override fun onCapabilitiesChanged(network: Network, capabilities: NetworkCapabilities) {
                online.value = hasInternet(capabilities)
            }

            override fun onLost(network: Network) {
                online.value = false
            }
        })
    }

    private fun hasInternet(capabilities: NetworkCapabilities?): Boolean =
        capabilities != null && capabilities.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET) &&
            capabilities.hasCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED)
}
