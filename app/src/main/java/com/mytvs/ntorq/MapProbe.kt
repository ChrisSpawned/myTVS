package com.mytvs.ntorq

import android.content.Context
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import android.net.NetworkRequest
import java.io.InputStream
import java.net.InetSocketAddress
import java.net.Socket
import kotlin.concurrent.thread

/**
 * Read-only probe of the cluster's Wi-Fi map channel.
 *
 * From the TVS Connect app we know the cluster hosts a TCP server at
 * 192.168.1.1 (ports 2000 and 4444) on its own Wi-Fi AP, over which it does
 * request/response still-image transfer. This probe, while the phone is joined
 * to that AP, opens a TCP connection to those ports and logs whatever the
 * cluster sends. It writes NOTHING to the cluster — it only listens, to confirm
 * the server exists on the Ntorq 150 TFT and to capture its opening bytes
 * (the "query" the app responds to).
 *
 * The socket is bound to the Wi-Fi network explicitly, because the cluster AP
 * has no internet and Android would otherwise route the connection over mobile
 * data and never reach 192.168.1.1.
 */
object MapProbe {
    const val HOST = "192.168.1.1"
    val PORTS = intArrayOf(2000, 4444)

    interface Listener {
        fun onProbe(text: String)
        fun onDone()
    }

    @Volatile private var running = false

    fun run(context: Context, listener: Listener) {
        if (running) {
            listener.onProbe("Probe already running")
            return
        }
        running = true
        val cm = context.getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
        // Match the cluster AP: Wi-Fi transport, and do NOT require internet.
        val req = NetworkRequest.Builder()
            .addTransportType(NetworkCapabilities.TRANSPORT_WIFI)
            .removeCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
            .build()

        val cb = object : ConnectivityManager.NetworkCallback() {
            override fun onAvailable(network: Network) {
                log(listener, "Bound to Wi-Fi network — probing $HOST")
                thread(name = "map-probe", isDaemon = true) {
                    for (port in PORTS) probePort(network, port, listener)
                    log(listener, "Probe finished.")
                    listener.onDone()
                    running = false
                    runCatching { cm.unregisterNetworkCallback(this) }
                }
            }

            override fun onUnavailable() {
                log(listener, "No Wi-Fi network available. Are you connected to the cluster's Wi-Fi?")
                listener.onDone()
                running = false
            }
        }
        try {
            // requestNetwork with a timeout so we don't wait forever.
            cm.requestNetwork(req, cb, 8000)
        } catch (e: Exception) {
            log(listener, "Could not request Wi-Fi network: ${e.message}")
            listener.onDone()
            running = false
        }
    }

    private fun probePort(network: Network, port: Int, listener: Listener) {
        log(listener, "— port $port —")
        val socket = Socket()
        try {
            network.bindSocket(socket)
            socket.connect(InetSocketAddress(HOST, port), 4000)
            log(listener, "Connected to $HOST:$port ✓")
            socket.soTimeout = 5000
            val input: InputStream = socket.getInputStream()
            val buf = ByteArray(2048)
            var totalRead = 0
            val deadline = System.currentTimeMillis() + 6000
            while (System.currentTimeMillis() < deadline) {
                val n = try {
                    input.read(buf)
                } catch (e: java.net.SocketTimeoutException) {
                    -2
                }
                if (n == -1) {
                    log(listener, "Cluster closed the connection")
                    break
                }
                if (n == -2) {
                    if (totalRead == 0) log(listener, "Connected, but cluster sent nothing in 6 s")
                    break
                }
                if (n > 0) {
                    totalRead += n
                    val hex = buf.copyOf(n).joinToString(" ") { "%02X".format(it.toInt() and 0xFF) }
                    log(listener, "RX $n bytes: $hex")
                }
            }
        } catch (e: java.net.ConnectException) {
            log(listener, "$HOST:$port refused (no server on this port)")
        } catch (e: Exception) {
            log(listener, "$HOST:$port error: ${e.message}")
        } finally {
            runCatching { socket.close() }
        }
    }

    private fun log(listener: Listener, text: String) {
        FrameLog.add("WIFI", text)
        listener.onProbe(text)
    }
}
