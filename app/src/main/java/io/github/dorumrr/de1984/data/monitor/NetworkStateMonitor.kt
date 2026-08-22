package io.github.dorumrr.de1984.data.monitor

import android.content.Context
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import android.net.NetworkRequest
import android.os.Build
import android.util.Log
import io.github.dorumrr.de1984.domain.model.NetworkType
import io.github.dorumrr.de1984.utils.AppLogger
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.coroutines.flow.distinctUntilChanged

class NetworkStateMonitor(
    private val context: Context
) {

    private val connectivityManager = context.getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager

    companion object {
        private const val TAG = "NetworkStateMonitor"
    }

    /**
     * Observe VPN state changes on the device.
     * Emits true when ANY VPN is connected, false when no VPN is connected.
     * This monitors all VPN connections, not just DE1984's own VPN.
     */
    fun observeVpnState(): Flow<Boolean> = callbackFlow {
        AppLogger.d(TAG, "🔐 Starting VPN state monitoring")

        val callback = object : ConnectivityManager.NetworkCallback() {
            override fun onAvailable(network: Network) {
                val hasVpn = isVpnActive()
                AppLogger.d(TAG, "🔐 SYSTEM EVENT: Network available - VPN active: $hasVpn")
                trySend(hasVpn)
            }

            override fun onCapabilitiesChanged(
                network: Network,
                networkCapabilities: NetworkCapabilities
            ) {
                val hasVpn = isVpnActive()
                val thisNetworkIsVpn = networkCapabilities.hasTransport(NetworkCapabilities.TRANSPORT_VPN)
                AppLogger.d(TAG, "🔐 SYSTEM EVENT: Network capabilities changed - this network is VPN: $thisNetworkIsVpn, any VPN active: $hasVpn")
                trySend(hasVpn)
            }

            override fun onLost(network: Network) {
                val hasVpn = isVpnActive()
                AppLogger.d(TAG, "🔐 SYSTEM EVENT: Network lost - VPN active: $hasVpn")
                trySend(hasVpn)
            }
        }

        // Monitor ALL networks to catch VPN connections
        val request = NetworkRequest.Builder()
            .removeCapability(NetworkCapabilities.NET_CAPABILITY_NOT_VPN)  // Include VPNs
            .build()
        connectivityManager.registerNetworkCallback(request, callback)

        val initialVpnState = isVpnActive()
        AppLogger.d(TAG, "🔐 Initial VPN state: $initialVpnState")
        trySend(initialVpnState)

        awaitClose {
            AppLogger.d(TAG, "🔐 Stopping VPN state monitoring")
            connectivityManager.unregisterNetworkCallback(callback)
        }
    }.distinctUntilChanged()

    /**
     * Check if any VPN is currently active on the device.
     */
    fun isVpnActive(): Boolean {
        return try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
                val activeNetwork = connectivityManager.activeNetwork
                if (activeNetwork != null) {
                    val capabilities = connectivityManager.getNetworkCapabilities(activeNetwork)
                    if (capabilities?.hasTransport(NetworkCapabilities.TRANSPORT_VPN) == true) {
                        return true
                    }
                }
            }
            // Also check all networks (for cases where VPN isn't the "active" network)
            @Suppress("DEPRECATION")
            val allNetworks = connectivityManager.allNetworks
            for (network in allNetworks) {
                val capabilities = connectivityManager.getNetworkCapabilities(network)
                if (capabilities?.hasTransport(NetworkCapabilities.TRANSPORT_VPN) == true) {
                    return true
                }
            }
            false
        } catch (e: Exception) {
            AppLogger.e(TAG, "Failed to check VPN status", e)
            false
        }
    }

    /**
     * Check if another VPN (not De1984's) is active.
     * Returns true if there's a VPN active with a session ID other than "De1984 Firewall".
     */
    fun isOtherVpnActive(): Boolean {
        return try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
                @Suppress("DEPRECATION")
                val allNetworks = connectivityManager.allNetworks
                for (network in allNetworks) {
                    val capabilities = connectivityManager.getNetworkCapabilities(network)
                    if (capabilities?.hasTransport(NetworkCapabilities.TRANSPORT_VPN) == true) {
                        // Get VPN transport info to check session ID
                        val transportInfo = capabilities.transportInfo
                        if (transportInfo != null) {
                            val sessionId = getVpnSessionId(transportInfo)
                            AppLogger.d(TAG, "🔐 Found VPN with session: $sessionId")
                            if (sessionId != null && sessionId != "De1984 Firewall") {
                                AppLogger.i(TAG, "🔐 External VPN detected: $sessionId")
                                return true
                            }
                        } else {
                            // If we can't get transport info, assume it might be another VPN
                            // unless our VPN is running (checked elsewhere)
                            AppLogger.d(TAG, "🔐 Found VPN without transport info")
                        }
                    }
                }
            }
            false
        } catch (e: Exception) {
            AppLogger.e(TAG, "Failed to check other VPN status", e)
            false
        }
    }

    /**
     * Extract VPN session ID from transport info using reflection.
     * VpnTransportInfo is not public API, so we use reflection.
     */
    private fun getVpnSessionId(transportInfo: android.net.TransportInfo): String? {
        return try {
            // VpnTransportInfo has a getSessionId() method
            val method = transportInfo.javaClass.getMethod("getSessionId")
            method.invoke(transportInfo) as? String
        } catch (e: Exception) {
            // Fallback: try toString() which usually contains the session ID
            val str = transportInfo.toString()
            // Parse "VpnTransportInfo{type=1, sessionId=ProtonTunnel, ...}"
            val match = Regex("sessionId=([^,}]+)").find(str)
            match?.groupValues?.getOrNull(1)
        }
    }

    fun observeNetworkType(): Flow<NetworkType> = callbackFlow {
        AppLogger.d(TAG, "📡 Starting network state monitoring")

        val callback = object : ConnectivityManager.NetworkCallback() {
            override fun onAvailable(network: Network) {
                val networkType = getCurrentNetworkType()
                AppLogger.d(TAG, "📡 SYSTEM EVENT: Network available - type: $networkType")
                trySend(networkType)
            }

            override fun onCapabilitiesChanged(
                network: Network,
                networkCapabilities: NetworkCapabilities
            ) {
                val networkType = getCurrentNetworkType()
                AppLogger.d(TAG, "📡 SYSTEM EVENT: Network capabilities changed - type: $networkType")
                trySend(networkType)
            }

            override fun onLost(network: Network) {
                // Ask what is still connected instead of assuming nothing is - the other two
                // callbacks already do. A secondary network going away does not put the device
                // offline, and reporting NONE here unblocked every app on every backend. The flow
                // ends in distinctUntilChanged(), so the unchanged WIFI that followed was swallowed
                // and the firewall stayed off until some other network event happened to arrive.
                val networkType = networkTypeExcluding(network)
                AppLogger.d(TAG, "📡 SYSTEM EVENT: Network lost - remaining type: $networkType")
                trySend(networkType)
            }
        }

        val request = NetworkRequest.Builder()
            .addCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
            .build()
        connectivityManager.registerNetworkCallback(request, callback)

        val initialType = getCurrentNetworkType()
        AppLogger.d(TAG, "📡 Initial network type: $initialType")
        trySend(initialType)

        awaitClose {
            AppLogger.d(TAG, "📡 Stopping network state monitoring")
            connectivityManager.unregisterNetworkCallback(callback)
        }
    }.distinctUntilChanged()
    
    fun getCurrentNetworkType(): NetworkType {
        val activeNetwork = connectivityManager.activeNetwork ?: return NetworkType.NONE
        return networkTypeOf(connectivityManager.getNetworkCapabilities(activeNetwork))
    }

    /**
     * The current network type, ignoring [lost].
     *
     * ConnectivityManager can still name a departing network as `activeNetwork` for a moment after
     * `onLost`, so it is skipped explicitly and the remaining internet-capable networks are checked.
     */
    private fun networkTypeExcluding(lost: Network?): NetworkType {
        return try {
            val active = connectivityManager.activeNetwork
            if (active != null && active != lost) {
                val activeType = networkTypeOf(connectivityManager.getNetworkCapabilities(active))
                if (activeType != NetworkType.NONE) return activeType
            }

            @Suppress("DEPRECATION")
            for (candidate in connectivityManager.allNetworks) {
                if (candidate == lost) continue
                val capabilities = connectivityManager.getNetworkCapabilities(candidate) ?: continue
                if (!capabilities.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)) continue
                val candidateType = networkTypeOf(capabilities)
                if (candidateType != NetworkType.NONE) return candidateType
            }
            NetworkType.NONE
        } catch (e: Exception) {
            AppLogger.e(TAG, "Failed to resolve the remaining network type", e)
            NetworkType.NONE
        }
    }

    private fun networkTypeOf(capabilities: NetworkCapabilities?): NetworkType {
        if (capabilities == null) return NetworkType.NONE

        return when {
            capabilities.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) -> {
                NetworkType.WIFI
            }
            capabilities.hasTransport(NetworkCapabilities.TRANSPORT_CELLULAR) -> {
                if (capabilities.hasCapability(NetworkCapabilities.NET_CAPABILITY_NOT_ROAMING)) {
                    NetworkType.MOBILE
                } else {
                    NetworkType.ROAMING
                }
            }
            // A VPN reports the transports it runs over, so the branches above already classify it.
            // Left as NONE deliberately, to keep the VPN backend's behaviour exactly as it was.
            capabilities.hasTransport(NetworkCapabilities.TRANSPORT_VPN) -> {
                NetworkType.NONE
            }
            // Ethernet, USB and Bluetooth tethering are real internet transports that this app has
            // no separate switch for. NONE means "offline", and blocking rules are now held in
            // force when offline, so calling an Ethernet dock or a TV box "offline" would block
            // every rule permanently with no network event able to correct it. They are unmetered
            // and not cellular, so the WiFi rules govern them.
            capabilities.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET) -> {
                NetworkType.WIFI
            }
            else -> NetworkType.NONE
        }
    }
    
    fun isWiFi(): Boolean = getCurrentNetworkType() == NetworkType.WIFI
    
    fun isMobile(): Boolean = getCurrentNetworkType() == NetworkType.MOBILE
    
    fun isRoaming(): Boolean = getCurrentNetworkType() == NetworkType.ROAMING
    
    fun isConnected(): Boolean = getCurrentNetworkType() != NetworkType.NONE
}

