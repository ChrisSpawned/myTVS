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
 * Read-only probe of the cluster's Wi-Fi map channel (192.168.1.1, ports 2000/4444).
 *
 * Binds to the Wi-Fi network the phone is actually on and logs whatever the
 * cluster sends. Writes NOTHING to the cluster.
 *
 * The cluster AP has no internet, which Android dislikes: it marks the network
 * "unvalidated" and may route around it. So instead of asking the system to
 * find a matching network, this enumerates the phone's live networks, picks the
 * Wi-Fi one, and binds the socket to it directly.
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
        if (running) { listener.onProbe("Probe already running"); return }
        running = true
        val cm = context.getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager

        thread(name = "map-probe", isDaemon = true) {
            try {
                val wifi = wifiNetworks(cm)
                if (wifi.isEmpty()) {
                    log(listener, "Phone is not on any Wi-Fi right now.")
                    log(listener, "Join the cluster's Wi-Fi, tap \"Stay connected\" on the " +
                        "no-internet prompt, then run this again.")
                    // last-ditch: try an unbound socket in case routing still works
                    for (port in PORTS) probe(null, port, listener)
                    return@thread
                }
                log(listener, "Live Wi-Fi networks: ${wifi.size}")
                for (net in wifi) {
                    val gw = gatewayHint(cm, net)
                    log(listener, "Trying Wi-Fi network${if (gw != null) " (gw $gw)" else ""}")
                    for (port in PORTS) probe(net, port, listener)
                }
            } finally {
                log(listener, "Probe finished.")
                running = false
                listener.onDone()
            }
        }
    }

    private fun wifiNetworks(cm: ConnectivityManager): List<Network> =
        cm.allNetworks.filter {
            val c = cm.getNetworkCapabilities(it)
            c != null && c.hasTransport(NetworkCapabilities.TRANSPORT_WIFI)
        }

    private fun gatewayHint(cm: ConnectivityManager, net: Network): String? = runCatching {
        cm.getLinkProperties(net)?.linkAddresses?.firstOrNull {
            it.address.hostAddress?.startsWith("192.168") == true
        }?.address?.hostAddress
    }.getOrNull()

    private fun probe(net: Network?, port: Int, listener: Listener) {
        log(listener, "— $HOST:$port —")
        val socket = Socket()
        try {
            net?.bindSocket(socket)
            socket.connect(InetSocketAddress(HOST, port), 4000)
            log(listener, "Connected to $HOST:$port ✓")
            socket.soTimeout = 5000
            val input: InputStream = socket.getInputStream()
            val buf = ByteArray(2048)
            var total = 0
            val deadline = System.currentTimeMillis() + 7000
            while (System.currentTimeMillis() < deadline) {
                val n = try { input.read(buf) } catch (e: java.net.SocketTimeoutException) { -2 }
                when {
                    n == -1 -> { log(listener, "cluster closed the connection"); break }
                    n == -2 -> { if (total == 0) log(listener, "connected, but no bytes in 7 s"); break }
                    n > 0 -> {
                        total += n
                        val hex = buf.copyOf(n).joinToString(" ") { "%02X".format(it.toInt() and 0xFF) }
                        log(listener, "RX $n: $hex")
                    }
                }
            }
        } catch (e: java.net.ConnectException) {
            log(listener, "$HOST:$port refused / unreachable (${e.message})")
        } catch (e: Exception) {
            log(listener, "$HOST:$port ${e.javaClass.simpleName}: ${e.message}")
        } finally {
            runCatching { socket.close() }
        }
    }

    private fun log(listener: Listener, text: String) {
        FrameLog.add("WIFI", text)
        listener.onProbe(text)
    }
}
