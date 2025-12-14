package com.example.phonecamapp.network

import android.content.Context
import android.net.nsd.NsdManager
import android.net.nsd.NsdServiceInfo
import android.util.Log

// Клас для керування Network Service Discovery
class NsdServiceManager(context: Context) {

    private val nsdManager = context.getSystemService(Context.NSD_SERVICE) as NsdManager
    private var registrationListener: NsdManager.RegistrationListener? = null
    private var serviceName = "PhoneCam"
    private val serviceType = "_rtsp._tcp."

    // Реєстрація сервісу при старті стріму
    fun registerService(port: Int) {
        tearDown()

        val serviceInfo = NsdServiceInfo().apply {
            this.serviceName = this@NsdServiceManager.serviceName
            this.serviceType = this@NsdServiceManager.serviceType
            this.port = port
        }

        registrationListener = object : NsdManager.RegistrationListener {
            override fun onServiceRegistered(NsdServiceInfo: NsdServiceInfo) {
                serviceName = NsdServiceInfo.serviceName
                Log.d("NSD", "Service registered: $serviceName")
            }

            override fun onRegistrationFailed(serviceInfo: NsdServiceInfo, errorCode: Int) {
                Log.e("NSD", "Registration failed: Error code: $errorCode")
            }

            override fun onServiceUnregistered(arg0: NsdServiceInfo) {
                Log.d("NSD", "Service unregistered")
            }

            override fun onUnregistrationFailed(serviceInfo: NsdServiceInfo, errorCode: Int) {
                Log.e("NSD", "Unregistration failed: Error code: $errorCode")
            }
        }

        try {
            nsdManager.registerService(
                serviceInfo,
                NsdManager.PROTOCOL_DNS_SD,
                registrationListener
            )
        } catch (e: Exception) {
            Log.e("NSD", "Error registering service", e)
        }
    }

    // Скасування реєстрації
    fun tearDown() {
        registrationListener?.let {
            try {
                nsdManager.unregisterService(it)
            } catch (e: Exception) {
                // Якщо сервіс вже зупинено, ігноруємо
                Log.e("NSD", "Error unregistering service", e)
            } finally {
                registrationListener = null
            }
        }
    }
}