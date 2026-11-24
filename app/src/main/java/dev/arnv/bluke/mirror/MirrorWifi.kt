package dev.arnv.bluke.mirror

import android.content.Context
import android.net.ConnectivityManager
import android.net.LinkProperties
import android.net.Network
import android.net.NetworkCapabilities
import android.net.NetworkRequest
import android.net.wifi.WifiManager
import android.os.Build
import java.net.Inet4Address
import java.net.InetAddress

/**
 * The Wi-Fi side of Mirror: which address the phone listens on, a callback when that changes, and
 * the low-latency lock (802.11 power save off) held only while Mirror is on screen.
 */
class MirrorWifi(context: Context, private val onAddressChanged: (InetAddress?) -> Unit) {
    private val appContext = context.applicationContext
    private val connectivity = appContext.getSystemService(ConnectivityManager::class.java)
    private val wifi = appContext.getSystemService(WifiManager::class.java)
    private var lock: WifiManager.WifiLock? = null
    private var callback: ConnectivityManager.NetworkCallback? = null
    @Volatile private var current: InetAddress? = null

    fun start() {
        val request = NetworkRequest.Builder().addTransportType(NetworkCapabilities.TRANSPORT_WIFI).build()
        val networks = mutableMapOf<Network, InetAddress?>()
        val cb = object : ConnectivityManager.NetworkCallback() {
            override fun onLinkPropertiesChanged(network: Network, linkProperties: LinkProperties) {
                synchronized(networks) { networks[network] = linkProperties.wifiAddress() }
                publish(networks)
            }
            override fun onLost(network: Network) {
                synchronized(networks) { networks.remove(network) }
                publish(networks)
            }
        }
        callback = cb
        runCatching { connectivity?.registerNetworkCallback(request, cb) }
        acquireLock()
    }

    fun stop() {
        callback?.let { runCatching { connectivity?.unregisterNetworkCallback(it) } }
        callback = null
        lock?.let { if (it.isHeld) it.release() }
        lock = null
        current = null
    }

    private fun publish(networks: Map<Network, InetAddress?>) {
        val address = synchronized(networks) { networks.values.firstOrNull { it != null } }
        if (address != current) {
            current = address
            onAddressChanged(address)
        }
    }

    @Suppress("DEPRECATION")
    private fun acquireLock() {
        val mode = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            WifiManager.WIFI_MODE_FULL_LOW_LATENCY
        } else {
            WifiManager.WIFI_MODE_FULL_HIGH_PERF
        }
        lock = runCatching {
            wifi?.createWifiLock(mode, "Bluke:mirror")?.apply {
                setReferenceCounted(false)
                acquire()
            }
        }.getOrNull()
    }

    private fun LinkProperties.wifiAddress(): InetAddress? =
        linkAddresses.map { it.address }.firstOrNull { it is Inet4Address && !it.isLoopbackAddress }
}
