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
            status("Subscribed — waiting for pairing / data…")
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
        FrameLog.add("TX-BLE", "$label: ${NtorqCodec.hex(bytes)}")
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            g.writeCharacteristic(ch, bytes, type) == BluetoothStatusCodes.SUCCESS
        } else {
            @Suppress("DEPRECATION") run {
                ch.writeType = type
                ch.value = bytes
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

    private fun handleData(uuid: UUID, value: ByteArray?) {
        if (uuid != NOTIFY || value == null) return
        val hex = NtorqCodec.hex(value)
        val frame = NtorqCodec.decode(value)
        if (frame != null) {
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
