package com.example.optimalx.data.link

// Spec: app/docs/architecture/OPTIMALX_LINK.md §phone-link-screen.
//
// Inspects the device's active network and surfaces the LAN IPv4 address the
// desktop client should target. The Link screen polls this on a short
// interval so swapping Wi-Fi networks while the screen is open refreshes the
// displayed IP automatically.

import android.content.Context
import android.net.ConnectivityManager
import android.net.LinkAddress
import android.net.NetworkCapabilities
import java.net.Inet4Address

object LocalNetworkInfo {

    /** Status returned to the UI describing the current network. */
    enum class Connection {
        /** Device is on a Wi-Fi network and we found a usable IPv4 address. */
        WIFI,

        /** Device is on a metered cellular link — Link screen warns the user. */
        CELLULAR,

        /** No usable network. */
        NONE,
    }

    data class NetworkSnapshot(
        val ipv4: String,
        val connection: Connection,
    )

    /**
     * Returns the first non-loopback IPv4 address the OS reports for the
     * active network, plus a tag indicating what kind of link it is.
     */
    fun current(context: Context): NetworkSnapshot {
        val cm = context.getSystemService(Context.CONNECTIVITY_SERVICE) as? ConnectivityManager
            ?: return NetworkSnapshot(ipv4 = "", connection = Connection.NONE)
        val network = cm.activeNetwork ?: return NetworkSnapshot(ipv4 = "", connection = Connection.NONE)
        val caps = cm.getNetworkCapabilities(network)
        val connection = when {
            caps == null -> Connection.NONE
            caps.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) -> Connection.WIFI
            caps.hasTransport(NetworkCapabilities.TRANSPORT_CELLULAR) -> Connection.CELLULAR
            else -> Connection.NONE
        }
        val props = cm.getLinkProperties(network)
        val ipv4 = props?.linkAddresses
            ?.asSequence()
            ?.mapNotNull { la: LinkAddress ->
                val addr = la.address
                if (addr is Inet4Address && !addr.isLoopbackAddress) addr.hostAddress else null
            }
            ?.firstOrNull()
            ?: ""
        return NetworkSnapshot(ipv4 = ipv4, connection = connection)
    }
}
