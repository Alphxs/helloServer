package com.example.android_helloworld

import android.content.Context
import android.net.nsd.NsdManager
import android.net.nsd.NsdServiceInfo
import android.util.Log

class ServiceAnnouncer(private val context: Context) {

    private val nsdManager = context.getSystemService(Context.NSD_SERVICE) as NsdManager
    private var registrationListener: NsdManager.RegistrationListener? = null
    private var serviceName: String? = null

    companion object {
        const val SERVICE_TYPE = "_proxy-edge._tcp."
    }

    fun registerService(port: Int) {
        serviceName = "EdgeServer-${(1000..9999).random()}"
        Log.i("ServiceAnnouncer", "Registering service: name=$serviceName, type=$SERVICE_TYPE, port=$port")

        if (serviceName.isNullOrEmpty() || SERVICE_TYPE.isBlank()) {
            Log.e("ServiceAnnouncer", "Invalid service info: name=$serviceName, type=$SERVICE_TYPE")
            return
        }

        val serviceInfo = NsdServiceInfo().apply {
            setServiceName(this@ServiceAnnouncer.serviceName)
            serviceType = SERVICE_TYPE
            setPort(port)
        }

        registrationListener = object : NsdManager.RegistrationListener {
            override fun onServiceRegistered(info: NsdServiceInfo) {
                serviceName = info.serviceName
                Log.i("ServiceAnnouncer", "Service registered: $serviceName")
            }

            override fun onRegistrationFailed(info: NsdServiceInfo, errorCode: Int) {
                Log.e("ServiceAnnouncer", "Registration failed: code=$errorCode")
            }

            override fun onServiceUnregistered(info: NsdServiceInfo) {
                Log.i("ServiceAnnouncer", "Service unregistered: ${info.serviceName}")
            }

            override fun onUnregistrationFailed(info: NsdServiceInfo, errorCode: Int) {
                Log.e("ServiceAnnouncer", "Unregistration failed: code=$errorCode")
            }
        }

        nsdManager.registerService(serviceInfo, NsdManager.PROTOCOL_DNS_SD, registrationListener)
    }

    fun unregisterService() {
        registrationListener?.let {
            Log.i("ServiceAnnouncer", "Unregistering service.")
            nsdManager.unregisterService(it)
            registrationListener = null
        }
    }
}
