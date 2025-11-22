package com.example.android_helloworld.helpers

import android.content.Context
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import java.net.Inet4Address

//Gets the device's current IPv4 address on the Wi-Fi network.

fun getIpAddress(context: Context): String? {
    val connectivityManager = context.getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
    val network = connectivityManager.activeNetwork ?: return null
    val capabilities = connectivityManager.getNetworkCapabilities(network) ?: return null

    // Ensure the device is connected to Wi-Fi
    if (!capabilities.hasTransport(NetworkCapabilities.TRANSPORT_WIFI)) {
        return null
    }

    val linkProperties = connectivityManager.getLinkProperties(network) ?: return null
    for (linkAddress in linkProperties.linkAddresses) {
        val address = linkAddress.address
        // Find the first IPv4 address
        if (address is Inet4Address) {
            return address.hostAddress
        }
    }
    return null // No IPv4 address found
}
