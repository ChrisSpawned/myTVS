package com.mytvs.ntorq

import android.annotation.SuppressLint
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothGatt
import android.bluetooth.BluetoothGattCallback
import android.bluetooth.BluetoothGattCharacteristic
import android.bluetooth.BluetoothGattDescriptor
import android.bluetooth.BluetoothProfile
import android.content.Context
import android.os.Handler
import android.os.Looper
import java.util.UUID

/**
 * BLE link to the Ntorq 150 TFT cluster.
 *
 * Milestone 1 is READ-ONLY: it connects, lets Android bond (the cluster requires
 * pairing), subscribes to the notify characteristic, and reports decoded frames.
 * It never writes to the cluster. The cluster drops a silent link after ~15 s, so
 * this reconnects automatically. (The keepalive write that holds the link is the
 * next milestone, once its exact bytes are confirmed.)
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
            status("Subscribed — waiting for pairing / data…")
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
