package com.nasframe.app.network

import android.content.Context
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import android.net.NetworkRequest
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

class NetworkMonitor(private val context: Context) {

    interface Listener {
        fun onNetworkLost()
        fun onNetworkAvailable()
        fun onReconnectFailed()
    }

    private val connectivityManager = context.getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
    private var callback: ConnectivityManager.NetworkCallback? = null
    private var listener: Listener? = null

    private var isConnected = true
    private var reconnectAttempts = 0
    private var reconnectJob: Job? = null
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)

    companion object {
        private const val MAX_RECONNECT_ATTEMPTS = 5
        private const val BASE_DELAY_MS = 1000L
        private const val MAX_DELAY_MS = 30000L
    }

    fun register(listener: Listener) {
        // Defensive: unregister any previous callback to avoid leaks on double-register
        unregister()
        this.listener = listener
        val request = NetworkRequest.Builder()
            .addCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
            .build()

        callback = object : ConnectivityManager.NetworkCallback() {
            override fun onLost(network: Network) {
                isConnected = false
                listener.onNetworkLost()
                startReconnect()
            }

            override fun onAvailable(network: Network) {
                if (!isConnected) {
                    isConnected = true
                    reconnectAttempts = 0
                    listener.onNetworkAvailable()
                }
            }
        }

        connectivityManager.registerNetworkCallback(request, callback!!)
        isConnected = checkConnection()
    }

    fun unregister() {
        reconnectJob?.cancel()
        reconnectJob = null
        callback?.let { connectivityManager.unregisterNetworkCallback(it) }
        callback = null
        listener = null
    }

    private fun checkConnection(): Boolean {
        val network = connectivityManager.activeNetwork ?: return false
        val capabilities = connectivityManager.getNetworkCapabilities(network) ?: return false
        return capabilities.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
    }

    private fun startReconnect() {
        reconnectJob?.cancel()
        reconnectJob = scope.launch {
            while (reconnectAttempts < MAX_RECONNECT_ATTEMPTS && !isConnected) {
                reconnectAttempts++
                val delayMs = minOf(BASE_DELAY_MS * (1 shl (reconnectAttempts - 1)), MAX_DELAY_MS)
                delay(delayMs)

                if (checkConnection()) {
                    // Only fire callback if the ConnectivityManager callback hasn't
                    // already set isConnected (avoids duplicate onNetworkAvailable calls)
                    if (!isConnected) {
                        isConnected = true
                        reconnectAttempts = 0
                        listener?.onNetworkAvailable()
                    }
                    return@launch
                }
            }

            if (!isConnected) {
                listener?.onReconnectFailed()
            }
        }
    }
}
