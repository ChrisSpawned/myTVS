package com.mytvs.ntorq

import android.annotation.SuppressLint
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothGatt
import android.bluetooth.BluetoothGattCallback
import android.bluetooth.BluetoothGattCharacteristic
import android.bluetooth.BluetoothGattDescriptor
import android.bluetooth.BluetoothProfile
import android.bluetooth.BluetoothStatusCodes
import android.content.Context
import android.os.Build
import android.os.Handler
import android.os.Looper
import java.util.UUID

/**
 * BLE link to the Ntorq 150 TFT cluster.
 *
 * It connects, lets Android bond (the cluster requires pairing), subscribes to the
 * notify characteristic, and reports decoded frames. The cluster drops a silent
 * link after ~15 s, so this reconnects automatically.
 *
 * Writes are limited to the cluster's own documented *display-data* frames (the
 * image-transfer channel), used to probe whether this cluster accepts a pushed
 * image. It never sends guessed vehicle-control commands.
 */
class NtorqGatt(
    private val context: Context,
    private val listener: Listener,
) {
    interface Listener {
        fun onStatus(text: String)
        fun onFrame(frame: NtorqCodec.Frame, rawHex: String)
    }

    companion object {
        val SERVICE: UUID = UUID.fromString("5456534d-5647-5341-5342-454e544f5251")
        val NOTIFY: UUID = UUID.fromString("00005354-0000-1000-8000-00805f9b34fb")
        val WRITE: UUID = UUID.fromString("00005352-0000-1000-8000-00805f9b34fb")
        val CCCD: UUID = UUID.fromString("00002902-0000-1000-8000-00805f9b34fb")
    }

    private val main = Handler(Looper.getMainLooper())
    private var gatt: BluetoothGatt? = null
    private var device: BluetoothDevice? = null
    private var wantConnected = false
    private var writeChar: BluetoothGattCharacteristic? = null
    private var mtu = 23
    @Volatile private var latestAvgMileageRaw = -1

    @SuppressLint("MissingPermission")
    fun connect(device: BluetoothDevice) {
        this.device = device
        wantConnected = true
        status("Connecting to ${safeName(device)}…")
        gatt = device.connectGatt(context, false, callback, BluetoothDevice.TRANSPORT_LE)
    }

    @SuppressLint("MissingPermission")
    fun disconnect() {
        wantConnected = false
        gatt?.disconnect()
        gatt?.close()
        gatt = null
    }

    @SuppressLint("MissingPermission")
    private fun reconnectSoon() {
        if (!wantConnected) return
        status("Link dropped — reconnecting…")
        gatt?.close()
        gatt = null
        main.postDelayed({
            val d = device
            if (wantConnected && d != null) {
                status("Reconnecting to ${safeName(d)}…")
                gatt = d.connectGatt(context, false, callback, BluetoothDevice.TRANSPORT_LE)
            }
        }, 1500)
    }

    private val callback = object : BluetoothGattCallback() {
        @SuppressLint("MissingPermission")
        override fun onConnectionStateChange(g: BluetoothGatt, st: Int, newState: Int) {
            if (newState == BluetoothProfile.STATE_CONNECTED) {
                status("Connected — discovering services…")
                main.post { g.discoverServices() }
            } else if (newState == BluetoothProfile.STATE_DISCONNECTED) {
                reconnectSoon()
            }
        }

        @SuppressLint("MissingPermission")
        override fun onServicesDiscovered(g: BluetoothGatt, st: Int) {
            val svc = g.getService(SERVICE)
            if (svc == null) {
                status("Cluster service not found on this device")
                return
            }
            val notify = svc.getCharacteristic(NOTIFY)
            if (notify == null) {
                status("Notify characteristic missing")
                return
            }
            g.setCharacteristicNotification(notify, true)
            val cccd = notify.getDescriptor(CCCD)
            if (cccd != null) {
                @Suppress("DEPRECATION")
                cccd.value = BluetoothGattDescriptor.ENABLE_NOTIFICATION_VALUE
                @Suppress("DEPRECATION")
                g.writeDescriptor(cccd)
            }
            writeChar = svc.getCharacteristic(WRITE)
            val props = writeChar?.properties ?: 0
            FrameLog.add("BLE", "write char ${if (writeChar != null) "ready" else "MISSING"} props=0x%02X".format(props))
            // The 33-byte second-line (road-name) frame needs more than the
            // default 20-byte payload, so ask for a bigger MTU.
            main.post { g.requestMtu(247) }
            status("Subscribed — waiting for pairing / data…")
        }

        override fun onMtuChanged(g: BluetoothGatt, mtuValue: Int, st: Int) {
            mtu = mtuValue
            FrameLog.add("BLE", "MTU = $mtuValue (status $st)")
        }

        override fun onCharacteristicWrite(g: BluetoothGatt, ch: BluetoothGattCharacteristic, st: Int) {
            FrameLog.add("BLE", "write result status=$st on ${ch.uuid.toString().substring(4, 8)}")
        }

        @Deprecated("Deprecated in Java")
        override fun onCharacteristicChanged(g: BluetoothGatt, ch: BluetoothGattCharacteristic) {
            @Suppress("DEPRECATION")
            handleData(ch.uuid, ch.value)
        }

        override fun onCharacteristicChanged(
            g: BluetoothGatt,
            ch: BluetoothGattCharacteristic,
            value: ByteArray,
        ) {
            handleData(ch.uuid, value)
        }
    }

    /**
     * Raw write to the cluster's SR characteristic. Used ONLY for the cluster's
     * own documented *display-data* frames (image transfer), never for guessed
     * vehicle-control commands. Image frames are sent raw (no XOR, no checksum),
     * exactly as the official app does with isChecksumNeeded=false.
     */
    @SuppressLint("MissingPermission")
    /**
     * Outgoing encryption, matching the app's BluetoothUtil.encryptData for the
     * default (non-listed) bike types: keep the start byte and ID, XOR the body
     * (indices 2 .. len-2) with the key, leave the trailing 0xFF. The W626 Ntorq
     * is an older variant not in the no-encrypt list, so its writes are encrypted.
     * Key 0xEB = getKeyByteArray (-21).
     */
    var txXorKey: Int = 0xEB
    private fun encrypt(frame: ByteArray): ByteArray {
        if (txXorKey < 0) return frame
        val out = frame.copyOf()
        for (i in 2 until out.size - 1) out[i] = (out[i].toInt() xor txXorKey).toByte()
        return out
    }

    fun writeRaw(label: String, bytes: ByteArray): Boolean {
        val g = gatt
        val ch = writeChar
        if (g == null || ch == null) {
            FrameLog.add("BLE", "write skipped ($label): not connected / no write char")
            return false
        }
        // Prefer write-with-response when supported, so we get an ACK status back.
        val canResp = (ch.properties and BluetoothGattCharacteristic.PROPERTY_WRITE) != 0
        val type = if (canResp) BluetoothGattCharacteristic.WRITE_TYPE_DEFAULT
        else BluetoothGattCharacteristic.WRITE_TYPE_NO_RESPONSE
        val wire = encrypt(bytes)
        FrameLog.add("TX-BLE", "$label: ${NtorqCodec.hex(bytes)}" +
            if (txXorKey >= 0) " -> enc ${NtorqCodec.hex(wire)}" else "")
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            g.writeCharacteristic(ch, wire, type) == BluetoothStatusCodes.SUCCESS
        } else {
            @Suppress("DEPRECATION") run {
                ch.writeType = type
                ch.value = wire
                g.writeCharacteristic(ch)
            }
        }
    }

    /**
     * Build the cluster's image-transfer INIT frame (ID 0x53), reconstructed from
     * BLEImageTransferUtils.byteArrayForInitImageTransfer:
     *   5A 53 | picNo | totalFrames(2) | frameNo(2) | state | subtype | name… | pad… | FF
     * picNo 4 = profile image (subtype 5), 5 = caller image (subtype 10).
     */
    private fun imageInitFrame(picNo: Int, subtype: Int, totalFrames: Int, name: String): ByteArray {
        var nameBytes = name.toByteArray(Charsets.UTF_8)
        if (nameBytes.size > 10) nameBytes = nameBytes.copyOf(10)
        val head = byteArrayOf(
            0x5A, 0x53,
            picNo.toByte(),
            ((totalFrames shr 8) and 0xFF).toByte(), (totalFrames and 0xFF).toByte(),
            0x00, 0x01,              // frameNo = 1
            0x01,                    // state = in progress
            subtype.toByte(),
        )
        val padLen = 20 - (head.size + nameBytes.size + 1)
        val pad = ByteArray(if (padLen > 0) padLen else 0)
        return head + nameBytes + pad + byteArrayOf(0xFF.toByte())
    }

    /**
     * Probe the image channel: send the profile + caller INIT frames and watch
     * whether the cluster answers on the notify characteristic (any non-telemetry
     * frame = the image channel is alive on this cluster).
     */
    fun sendImageInitProbe() {
        main.post { writeRaw("img-init profile (picNo=4)", imageInitFrame(4, 5, 74, "Test")) }
        main.postDelayed({ writeRaw("img-init caller (picNo=5)", imageInitFrame(5, 10, 33, "Test")) }, 2500)
        main.postDelayed({ writeRaw("img-init profile x2", imageInitFrame(4, 5, 74, "Test")) }, 5000)
        status("Sent image-channel INIT frames — watch the log for a cluster reply.")
    }

    // ---- Navigation (turn-by-turn) --------------------------------------
    // Reconstructed from BleNavigationSendData.byteArrayForIceBikeNavigation +
    // BleEngine.sendNavigationData. Two raw 20-byte frames, text first then the
    // control frame 400 ms later (exactly as the official app sends them).
    //
    //  Text frame:    5B 4F | text(≤17 UTF-8) | pad 0x00 | FF
    //  Control frame: 5A 4E | distNext(2) | timeMin(2) | totalDist(3) |
    //                 picto(1) | lineCount(1) | navOnOff(1) | 00×7 | FF
    //  navOnOff on the Ntorq: 0x00 = navigating, 0xFF = stopped.

    private fun navTextFrame(text: String): ByteArray {
        val t = text.take(17).toByteArray(Charsets.UTF_8)
        val head = byteArrayOf(0x5B, 0x4F)
        val pad = ByteArray(20 - (head.size + t.size + 1))
        return head + t + pad + byteArrayOf(0xFF.toByte())
    }

    private fun be2(v: Int): ByteArray = when {
        v >= 0xFFFF -> byteArrayOf(0xFF.toByte(), 0xFF.toByte())
        v <= 0xFF -> byteArrayOf(0, v.toByte())
        else -> byteArrayOf(((v shr 8) and 0xFF).toByte(), (v and 0xFF).toByte())
    }

    private fun be3(v: Int): ByteArray = when {
        v <= 0xFF -> byteArrayOf(0, 0, v.toByte())
        v <= 0xFFFF -> byteArrayOf(0, ((v shr 8) and 0xFF).toByte(), (v and 0xFF).toByte())
        else -> byteArrayOf(((v shr 16) and 0xFF).toByte(), ((v shr 8) and 0xFF).toByte(), (v and 0xFF).toByte())
    }

    private fun navControlFrame(
        distNextM: Int, totalDistM: Int, timeSec: Long, picto: Int, twoLines: Boolean, stopped: Boolean,
    ): ByteArray {
        val timeMin = (timeSec / 60).toInt()
        return byteArrayOf(0x5A, 0x4E) +
            be2(distNextM) +
            be2(timeMin) +
            be3(totalDistM) +
            byteArrayOf(picto.toByte()) +
            byteArrayOf(if (twoLines) 2 else 1) +
            byteArrayOf(if (stopped) 0xFF.toByte() else 0x00) +
            byteArrayOf(0, 0, 0, 0, 0, 0, 0) +
            byteArrayOf(0xFF.toByte())
    }

    /**
     * Second navigation text line (the cluster's road-name slot), reconstructed
     * from BleNavigationSendData.sendNavigationControl2. A 33-byte frame:
     *   5A 49 | text(≤22 UTF-8) | pad 0x00 | destArrived | 00 00 00 | FF
     * We put the live mileage here instead of the road name.
     */
    private fun navControl2Frame(text: String, destArrived: Boolean): ByteArray {
        val t = (if (text.length > 23) text.substring(0, 22) else text).toByteArray(Charsets.UTF_8)
        val head = byteArrayOf(0x5A, 0x49) + t
        val tail = byteArrayOf(if (destArrived) 1 else 0, 0, 0, 0, 0xFF.toByte())
        val total = head.size + tail.size
        val pad = ByteArray(if (total <= 33) 33 - total else 1)
        return head + pad + tail
    }

    /**
     * Location / address frame, from BluetoothUtil.createCurrentLocationArray:
     *   5B 5C | text(≤17 UTF-8) | pad 0x00 | FF
     * The app sends this when navigation starts; it's the most likely trigger
     * that switches the cluster into its navigation screen.
     */
    private fun navLocationFrame(text: String): ByteArray {
        val t = text.take(17).toByteArray(Charsets.UTF_8)
        val head = byteArrayOf(0x5B, 0x5C)
        val pad = ByteArray(20 - (head.size + t.size + 1))
        return head + t + pad + byteArrayOf(0xFF.toByte())
    }

    private fun sanitize(s: String): String =
        s.map { c -> if (c in 'A'..'Z' || c in 'a'..'z' || c in '0'..'9' || c == ' ') c else 'X' }.joinToString("")

    /**
     * Navigation-assist-ON frame (createNavigationAssistArray, dataId 0x4C):
     *   5B 4C 4E 41 | text(≤15 alnum) | pad | FF
     * This is the command that switches the cluster to its navigation screen.
     */
    private fun navAssistFrame(text: String): ByteArray {
        val t = sanitize(text).take(15).toByteArray(Charsets.UTF_8)
        val head = byteArrayOf(0x5B, 0x4C, 0x4E, 0x41)
        val pad = ByteArray(20 - (head.size + t.size + 1))
        return head + t + pad + byteArrayOf(0xFF.toByte())
    }

    /** Custom text frame (createMobileToClusterArray): 5B <dataId> | text(≤17) | pad | FF */
    private fun customTextFrame(text: String, dataId: Int): ByteArray {
        val t = sanitize(text).take(17).toByteArray(Charsets.UTF_8)
        val head = byteArrayOf(0x5B, dataId.toByte())
        val pad = ByteArray(20 - (head.size + t.size + 1))
        return head + t + pad + byteArrayOf(0xFF.toByte())
    }

    /** The app's periodic nav keepalive: sendNavAssistOn("NAVIGATION","ASSIST ON"). */
    private fun sendAssistOn() {
        writeRaw("assist-on 5B4C", navAssistFrame("NAVIGATION"))
        main.postDelayed({ writeRaw("assist-text 5B63", customTextFrame("ASSIST ON", 0x63)) }, 350)
    }

    private fun sendNavStep(line1: String, line2: String, distNextM: Int, totalDistM: Int, timeSec: Long, picto: Int) {
        writeRaw("nav-L1 \"$line1\"", navTextFrame(line1))
        main.postDelayed({
            if (mtu - 3 >= 33) writeRaw("nav-L2 \"$line2\"", navControl2Frame(line2, false))
            else FrameLog.add("BLE", "nav-L2 skipped: MTU $mtu too small for the 33-byte line-2 frame")
        }, 300)
        main.postDelayed({
            writeRaw("nav-ctrl d=$distNextM picto=$picto", navControlFrame(distNextM, totalDistM, timeSec, picto, twoLines = false, stopped = false))
        }, 500)
    }

    /**
     * Fake a short live turn-by-turn so we can see whether the cluster switches
     * to a navigation screen. Line 1 = the turn, line 2 = the cluster's own live
     * average mileage (decoded from telemetry). Ends with a nav-stopped frame.
     */
    fun sendNavTest(picto: Int = 0) {
        status("Nav test: assist-on + turn-by-turn…")
        // 1) Switch the cluster into nav mode with the assist-on keepalive.
        sendAssistOn()
        // 2) Stream turn data (left turn, picto 0), re-sending assist-on as keepalive.
        val steps = listOf(250, 200, 150, 100, 60, 30, 10)
        steps.forEachIndexed { i, d ->
            main.postDelayed({
                if (i % 2 == 0) sendAssistOn()
                val m = latestAvgMileageRaw
                val line2 = if (m >= 0) "$m kmpl" else "-- kmpl"
                main.postDelayed({ sendNavStep("Turn left", line2, d, 15600, 1800L, picto) }, 700)
            }, 1400 + i * 2200L)
        }
        main.postDelayed({
            writeRaw("nav-stop", navControlFrame(0, 0, 0, 0, twoLines = false, stopped = true))
            status("Nav test done — did the cluster show a navigation screen? Export the log.")
        }, 1400 + steps.size * 2200L + 800L)
    }

    private fun handleData(uuid: UUID, value: ByteArray?) {
        if (uuid != NOTIFY || value == null) return
        val hex = NtorqCodec.hex(value)
        val frame = NtorqCodec.decode(value)
        if (frame != null) {
            // Fuel-economy frame (SpeedOMeter3 candidate, id 0x16): average
            // mileage sits at data byte 6, instantaneous at 7, range at 9..10.
            if (frame.id == 0x16) latestAvgMileageRaw = frame.u(6)
            main.post { listener.onFrame(frame, hex) }
        } else {
            FrameLog.add("RX?", hex)
        }
    }

    private fun safeName(d: BluetoothDevice): String =
        try {
            @SuppressLint("MissingPermission")
            val n = d.name
            n ?: d.address
        } catch (e: SecurityException) {
            d.address
        }

    private fun status(text: String) {
        FrameLog.add("BLE", text)
        main.post { listener.onStatus(text) }
    }
}
