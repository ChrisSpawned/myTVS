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

    // ---- Handshake mode: prompt the cluster to issue an image request ----

    /**
     * Connects to the cluster's TCP server and sends the app's own small control
     * packets (heartbeat + "image available"), framed 5A5A..5B5B, to make the
     * cluster reveal its image request (0xAA/0xBB/0xCC). Logs everything received.
     * Sends only tiny control packets on the display/image channel; never touches
     * the BLE instrument-control path.
     */
    fun handshake(context: Context, listener: Listener) {
        if (running) { listener.onProbe("Busy"); return }
        running = true
        val cm = context.getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
        thread(name = "map-handshake", isDaemon = true) {
            try {
                val net = wifiNetworks(cm).firstOrNull()
                if (net == null) {
                    log(listener, "Not on the cluster Wi-Fi. Join it (stay connected) and retry.")
                    return@thread
                }
                val socket = Socket()
                try {
                    net.bindSocket(socket)
                    socket.connect(InetSocketAddress(HOST, 2000), 4000)
                    log(listener, "Connected to $HOST:2000 ✓ — starting handshake")
                    socket.soTimeout = 1000
                    val out = socket.getOutputStream()
                    val input = socket.getInputStream()
                    val reader = thread(name = "map-hs-rx", isDaemon = true) {
                        val buf = ByteArray(4096)
                        val end = System.currentTimeMillis() + 20000
                        while (System.currentTimeMillis() < end) {
                            val n = try { input.read(buf) } catch (e: java.net.SocketTimeoutException) { 0 } catch (e: Exception) { -1 }
                            if (n == -1) { log(listener, "cluster closed the connection"); break }
                            if (n > 0) {
                                val hex = buf.copyOf(n).joinToString(" ") { "%02X".format(it.toInt() and 0xFF) }
                                log(listener, "RX $n: $hex")
                            }
                        }
                    }
                    // Exact heartbeat, reconstructed from the app's TCPClient.
                    // First few are plain pulses; then we advertise a document is
                    // available (cycling candidate values) to try to make the
                    // cluster request the map image (it would reply 0xAA/BB/CC).
                    var rolling = 1
                    var counterNo = 0
                    val availCandidates = listOf<Byte?>(null, null, null, 0x01, 0x02, 0x07, 0xFF.toByte(), 0x00, 0x01, 0x02)
                    for (av in availCandidates) {
                        val pkt = heartbeat(rolling, counterNo, av)
                        send(out, listener, if (av == null) "pulse" else "pulse+avail(0x%02X)".format(av.toInt() and 0xFF), pkt)
                        rolling++; counterNo++
                        Thread.sleep(1200)
                    }
                    reader.join(15000)
                } finally {
                    runCatching { socket.close() }
                }
            } catch (e: Exception) {
                log(listener, "handshake error: ${e.javaClass.simpleName}: ${e.message}")
            } finally {
                log(listener, "Handshake finished.")
                running = false
                listener.onDone()
            }
        }
    }

    private val PREFIX = byteArrayOf(0x5A, 0x5A)
    private val POSTFIX = byteArrayOf(0x5B, 0x5B)

    /**
     * Exact pulse/heartbeat packet from TCPClient:
     *   PREFIX | len(2) | noSignals(2) | checksum(1) | counterNo(2) | 00 00 | data | POSTFIX
     * where data = [2,1,0,5, 'T'(0x54),'V'(0x56),0x53, rollHi, rollLo] (+ availability).
     * checksum = sum(data) & 0xFF. len = total packet length.
     */
    private fun heartbeat(rolling: Int, counterNo: Int, docAvail: Byte?): ByteArray {
        val sig = byteArrayOf(84, 86, 83, (rolling shr 8).toByte(), rolling.toByte())
        val rollingData = byteArrayOf(2, 1, 0, 5) + sig
        val avail = if (docAvail != null) byteArrayOf(5, 1, 0, 1, docAvail) else ByteArray(0)
        val data = rollingData + avail
        val ck = (data.fold(0) { a, b -> a + b } and 0xFF).toByte()
        val noSignals = byteArrayOf(0, 4)
        val cn = byteArrayOf((counterNo shr 8).toByte(), counterNo.toByte())
        val zz = byteArrayOf(0, 0)
        val ckB = byteArrayOf(ck)
        val bodyLen = PREFIX.size + 2 + noSignals.size + ckB.size + cn.size + zz.size + data.size + POSTFIX.size
        val lenB = byteArrayOf((bodyLen shr 8).toByte(), bodyLen.toByte())
        return concat(PREFIX, lenB, noSignals, ckB, cn, zz, data, POSTFIX)
    }

    private fun concat(vararg parts: ByteArray): ByteArray {
        val out = ByteArray(parts.sumOf { it.size })
        var o = 0
        for (p in parts) { p.copyInto(out, o); o += p.size }
        return out
    }

    private fun send(out: java.io.OutputStream, listener: Listener, label: String, bytes: ByteArray) {
        try {
            out.write(bytes); out.flush()
            log(listener, "TX $label: ${bytes.joinToString(" ") { "%02X".format(it.toInt() and 0xFF) }}")
        } catch (e: Exception) {
            log(listener, "TX $label failed: ${e.message}")
        }
    }

    private fun log(listener: Listener, text: String) {
        FrameLog.add("WIFI", text)
        listener.onProbe(text)
    }
}
