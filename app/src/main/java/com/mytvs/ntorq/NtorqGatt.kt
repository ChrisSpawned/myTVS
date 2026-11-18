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
 * BLE link to the Ntorq 150 TFT cluster (official-app type U_577_PREMIUM, id 38).
 *
 * Connects, lets Android bond, subscribes to notifications and decodes telemetry.
 * Like the official app, it then runs a phone session: rider name once, and a
 * phone-status heartbeat (5B 4A) every 2 s. All writes are 20-byte frames,
 * XOR-0xEA encoded (encryptDataU577Premium), one at a time.
 *
 * Writes are limited to display/status frames the official app sends (phone
 * status, rider name, navigation text/arrows). It never sends vehicle-control or
 * vehicle-settings commands, and never the keyless-entry/unlock handshake.
 */
class NtorqGatt(
    private val context: Context,
    @Volatile var listener: Listener?,
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
    /** Road speed in km/h from frame 0x10 byte 0 (confirmed on a ride: 0–48 km/h). */
    @Volatile var speedKmh: Int = 0
        private set

    /** True once the cluster is streaming telemetry to us. */
    @Volatile var isLive = false
        private set

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
        stopSession()
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
                isLive = false
                main.post { stopSession() }
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
            // The official app starts its phone session right after the MTU
            // exchange (onConnectedPeripheral -> setMTU -> initializeDeviceServices).
            main.postDelayed({ startSession() }, 500)
        }

        override fun onCharacteristicWrite(g: BluetoothGatt, ch: BluetoothGattCharacteristic, st: Int) {
            if (st != 0) FrameLog.add("BLE", "write result status=$st on ${ch.uuid.toString().substring(4, 8)}")
            onWriteDone()
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
     * Outgoing encryption. The official app's bike type for this scooter is
     * vehicleTypeId 38 = BikeType.U_577_PREMIUM (Ntorq 150 TFT). For that type
     * BleEngine.writeCharacteristicToCentral runs BluetoothUtil.encryptDataU577Premium
     * on EVERY write: keep bytes [0] and [1], XOR bytes [2 .. len-2] with
     * getKeyByteArrayForNtorqU577 (= 0xEA at every position), keep the trailing 0xFF.
     * (U_577_BASE, type 37, sends raw.) -1 = raw.
     * Confirmed on the scooter: the 0xEA-encoded frames are the ones the cluster
     * accepts (v0.12's left-arrow/0 m screen came from the 0xEA pass's last frame;
     * an all-raw run in v0.14 produced nothing, not even the phone icons).
     */
    var txXorKey: Int = 0xEA
    private fun encrypt(frame: ByteArray, key: Int = txXorKey): ByteArray {
        if (key < 0) return frame
        val out = frame.copyOf()
        for (i in 2 until out.size - 1) out[i] = (out[i].toInt() xor key).toByte()
        return out
    }

    // ---- Write queue ------------------------------------------------------
    // Android allows one GATT write in flight at a time. The official app pushes
    // every write through a command queue (BluetoothPeripheral.enqueue) and only
    // starts the next one when the previous write completes. With the 2-second
    // heartbeat running underneath navigation we must do the same, or writes
    // collide and are silently dropped.
    private class PendingWrite(val label: String, val bytes: ByteArray, val key: Int?)
    private val queue = ArrayDeque<PendingWrite>()
    private var writeBusy = false
    private var busySince = 0L

    /** Queue a frame for the cluster (encrypted at send time; key = null -> txXorKey). */
    fun writeRaw(label: String, bytes: ByteArray, key: Int? = null): Boolean {
        if (bytes.size != 20) {
            // BluetoothPeripheral.writeCharacteristicNtorq refuses any frame that
            // is not exactly 20 bytes, so the official app never sends one.
            FrameLog.add("BLE", "refused $label: ${bytes.size} bytes (cluster frames are 20)")
            return false
        }
        main.post {
            queue.addLast(PendingWrite(label, bytes, key))
            pump()
        }
        return true
    }

    @SuppressLint("MissingPermission")
    private fun pump() {
        if (writeBusy && System.currentTimeMillis() - busySince > 1500) {
            FrameLog.add("BLE", "write timed out — continuing")
            writeBusy = false
        }
        if (writeBusy) return
        val next = queue.removeFirstOrNull() ?: return
        val g = gatt
        val ch = writeChar
        if (g == null || ch == null) {
            FrameLog.add("BLE", "write skipped (${next.label}): not connected")
            queue.clear()
            return
        }
        // Same rule as BleEngine for U577: use the characteristic's own write type —
        // write-without-response if that's its default, otherwise with response.
        val noResp = (ch.properties and BluetoothGattCharacteristic.PROPERTY_WRITE) == 0
        val type = if (noResp) BluetoothGattCharacteristic.WRITE_TYPE_NO_RESPONSE
        else BluetoothGattCharacteristic.WRITE_TYPE_DEFAULT
        val k = next.key ?: txXorKey
        val plain = next.bytes
        val wire = encrypt(plain, k)
        FrameLog.add("TX-BLE", "${next.label}: ${NtorqCodec.hex(plain)}" +
            if (k >= 0) " -> enc ${NtorqCodec.hex(wire)}" else " (raw)")
        val ok = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            g.writeCharacteristic(ch, wire, type) == BluetoothStatusCodes.SUCCESS
        } else {
            @Suppress("DEPRECATION") run {
                ch.writeType = type
                ch.value = wire
                g.writeCharacteristic(ch)
            }
        }
        if (!ok) {
            FrameLog.add("BLE", "write REJECTED by Android stack: ${next.label} — retrying")
            queue.addFirst(next)
            main.postDelayed({ pump() }, 150)
            return
        }
        if (noResp) {
            main.postDelayed({ pump() }, 30)
        } else {
            writeBusy = true
            busySince = System.currentTimeMillis()
            main.postDelayed({ pump() }, 1600)
        }
    }

    private fun onWriteDone() {
        main.post {
            writeBusy = false
            pump()
        }
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
        // ASCII only: a non-Latin instruction (e.g. Maps in Malayalam) would otherwise
        // exceed 17 bytes and break the 20-byte frame. The cluster shows no text anyway.
        val t = sanitize(text).take(17).toByteArray(Charsets.US_ASCII)
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
            // navOnOff for vehicle types 37/38, from the raw bytecode of
            // byteArrayForIceBikeNavigation (the readable decompile had it inverted):
            // navigating = 0xFF, stopped = 0x00.
            byteArrayOf(if (stopped) 0x00 else 0xFF.toByte()) +
            byteArrayOf(0, 0, 0, 0, 0, 0, 0) +
            byteArrayOf(0xFF.toByte())
    }

    private fun sanitize(s: String): String =
        s.map { c -> if (c in 'A'..'Z' || c in 'a'..'z' || c in '0'..'9' || c == ' ') c else 'X' }.joinToString("")

    /**
     * Navigation-assist-ON frame (BluetoothUtil.createNavigationAssistArray, dataId 0x4C):
     *   5B 4C 4E 41 | text(≤15 alnum) | pad | FF
     */
    private fun navAssistFrame(text: String): ByteArray {
        val t = sanitize(text).take(15).trim().toByteArray(Charsets.UTF_8)
        val head = byteArrayOf(0x5B, 0x4C, 0x4E, 0x41)
        val pad = ByteArray(20 - (head.size + t.size + 1))
        return head + t + pad + byteArrayOf(0xFF.toByte())
    }

    /**
     * Custom text frame (BluetoothUtil.createMobileToClusterArray): 5B <dataId> | text(≤17) | pad | FF.
     * dataId 0x63 = custom text line two, 0x4C = line one, 0x52 = rider name.
     */
    private fun customTextFrame(text: String, dataId: Int): ByteArray {
        val t = sanitize(text).take(17).trim().toByteArray(Charsets.UTF_8)
        val head = byteArrayOf(0x5B, dataId.toByte())
        val pad = ByteArray(20 - (head.size + t.size + 1))
        return head + t + pad + byteArrayOf(0xFF.toByte())
    }

    // ---- Phone session (what the official app does on every connect) ------
    // BaseBleConnectHelperService.initializeDeviceServices():
    //   sendInitialDataPacket()  -> NtorqBleHelper: sendRiderName (5B 52 <name>)
    //   startSendingCyclicData() -> every 2000 ms: NtorqMobileToCluster.sendMobileData
    //                               = BlePacketsProvider.sendNtorqMobileData (5B 4A …)
    // Your own logcat shows that 2.000 s write beat on this scooter. We never sent it.
    // NOT copied: the vehicle-settings sync (sendVehicleControl) — that's vehicle config.

    var riderName = "Rider"
    private var sessionOn = false
    private val heartbeat = object : Runnable {
        override fun run() {
            if (!sessionOn) return
            writeRaw("heartbeat 5B4A", mobileDataFrame())
            main.postDelayed(this, 2000)
        }
    }

    private fun startSession() {
        if (sessionOn) return
        sessionOn = true
        FrameLog.add("BLE", "session start (key ${if (txXorKey >= 0) "0x%02X".format(txXorKey) else "raw"}): rider name + 2 s heartbeat")
        writeRaw("rider-name 5B52", customTextFrame(riderName, 0x52))
        main.postDelayed(heartbeat, 50)
        if (NavBridge.isActive) main.postDelayed({ navStart() }, 1500)
        status("Connected — phone session running (heartbeat every 2 s). Watch the cluster for a phone/battery/signal icon.")
    }

    private fun stopSession() {
        sessionOn = false
        main.removeCallbacks(heartbeat)
        queue.clear()
        writeBusy = false
    }

    /** Phone battery % (no permission needed). */
    private fun batteryPct(): Int = try {
        val i = context.registerReceiver(null, android.content.IntentFilter(android.content.Intent.ACTION_BATTERY_CHANGED))
        val level = i?.getIntExtra(android.os.BatteryManager.EXTRA_LEVEL, -1) ?: -1
        val scale = i?.getIntExtra(android.os.BatteryManager.EXTRA_SCALE, 100) ?: 100
        if (level >= 0 && scale > 0) level * 100 / scale else 80
    } catch (e: Exception) { 80 }

    /** BluetoothUtil.getIceBatteryLevel: % -> 0..5 bars. */
    private fun iceBatteryLevel(p: Int): Int = when {
        p < 10 -> 0
        p < 20 -> 1
        p < 40 -> 2
        p < 60 -> 3
        p < 80 -> 4
        p <= 100 -> 5
        else -> 0
    }

    /** Signal level 0..4 (TelephonyManager.signalStrength.level, as the app uses). */
    private fun signalLevel(): Int = try {
        val tm = context.getSystemService(Context.TELEPHONY_SERVICE) as android.telephony.TelephonyManager
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) (tm.signalStrength?.level ?: 4).coerceIn(0, 4) else 4
    } catch (e: Exception) { 4 }

    /**
     * BlePacketsProvider.sendNtorqMobileData for U_577_PREMIUM (20 bytes):
     *   5B 4A | sig<<4|bat | overSpeed(FF=off) | temp | 00 | hour12 | min | sec | am(0)/pm(1) |
     *   missedCalls | netType(4=LTE) | day | month | yy | 00 | voiceAssist(0) | 00 | callState | FF
     * The packed byte is hex2decimal("$signal$battery"), i.e. nibbles.
     */
    private fun mobileDataFrame(): ByteArray {
        val c = java.util.Calendar.getInstance()
        val h24 = c.get(java.util.Calendar.HOUR_OF_DAY)
        val h12 = if (h24 % 12 == 0) 12 else h24 % 12          // app uses "hh" (01..12)
        // From the raw bytecode of sendNtorqMobileData: AM_PM == AM -> 0, PM -> 1.
        // (The readable decompile showed it inverted; that sent "3 PM" as 3 AM.)
        val am = if (c.get(java.util.Calendar.AM_PM) == java.util.Calendar.AM) 0 else 1
        val packed = (signalLevel() shl 4) or iceBatteryLevel(batteryPct())
        return byteArrayOf(
            0x5B, 0x4A,
            packed.toByte(),
            0xFF.toByte(),               // over-speed alert off
            0,                           // temperature (app sends 0 until weather loads)
            0,
            h12.toByte(),
            c.get(java.util.Calendar.MINUTE).toByte(),
            c.get(java.util.Calendar.SECOND).toByte(),
            am.toByte(),                 // 0 = AM, 1 = PM
            0,                           // missed calls
            4,                           // network type: LTE
            c.get(java.util.Calendar.DAY_OF_MONTH).toByte(),
            (c.get(java.util.Calendar.MONTH) + 1).toByte(),
            (c.get(java.util.Calendar.YEAR) % 100).toByte(),
            0,
            0,                           // voice-assist status (non-LCD cluster)
            0,
            0,                           // call state: idle
            0xFF.toByte(),
        )
    }

    // ---- Navigation, replicating the official app's on-air cadence ----------
    // From your official-app logcat (navlog2): assist-on pairs ~2.1 s apart until
    // route data flows, then a nav pair every ~1 s (text, +400 ms control), with
    // the 2 s heartbeat running underneath the whole time.

    private fun sendAssistOn(line2: String = "ASSIST ON") {
        writeRaw("assist-on 5B4C", navAssistFrame("NAVIGATION"))
        main.postDelayed({ writeRaw("line-two 5B63 \"$line2\"", customTextFrame(line2, 0x63)) }, 400)
    }

    /** BleEngine.sendNavigationData: pair.second (text 5B4F) first, then pair.first (control 5A4E) 400 ms later. */
    private fun sendNavPair(instruction: String, distNextM: Int, totalDistM: Int, etaSec: Long, picto: Int) {
        writeRaw("nav-text 5B4F \"${instruction.take(17)}\"", navTextFrame(instruction))
        main.postDelayed({
            writeRaw("nav-ctrl 5A4E d=$distNextM picto=$picto",
                navControlFrame(distNextM, totalDistM, etaSec, picto, twoLines = instruction.length > 17, stopped = false))
        }, 400)
    }

    // ---- Live navigation API (used by NavBridge) -------------------------
    /** Switch the cluster to its nav screen (assist-on pair twice, ~2.1 s apart, like the app). */
    fun navStart() {
        txXorKey = 0xEA
        sendAssistOn()
        main.postDelayed({ sendAssistOn() }, 2100)
    }

    /**
     * One nav update: text frame, control frame 400 ms later.
     * minutesField -> "31m" slot (cluster derives ETA from it);
     * totalFieldM  -> "15.6km" slot (metres).
     */
    fun navPush(instruction: String, distNextM: Int, minutesField: Int, totalFieldM: Int, picto: Int) {
        sendNavPair(instruction, distNextM.coerceIn(0, 0xFFFF), totalFieldM.coerceIn(0, 0xFFFFFF),
            minutesField.coerceIn(0, 0xFFFF) * 60L, picto)
    }

    /** Control frame only (arrow + distance + bottom row) — for fast distance updates between text frames. */
    fun navPushControl(instruction: String, distNextM: Int, minutesField: Int, totalFieldM: Int, picto: Int) {
        writeRaw("nav-ctrl 5A4E d=$distNextM picto=$picto",
            navControlFrame(distNextM.coerceIn(0, 0xFFFF), totalFieldM.coerceIn(0, 0xFFFFFF),
                minutesField.coerceIn(0, 0xFFFF) * 60L, picto, twoLines = instruction.length > 17, stopped = false))
    }

    /** Close the cluster's nav screen (navOnOff = 0x00). */
    fun navStop() {
        writeRaw("nav-stop 5A4E", navControlFrame(0, 0, 0, 0, twoLines = false, stopped = true))
    }

    private fun handleData(uuid: UUID, value: ByteArray?) {
        if (uuid != NOTIFY || value == null) return
        val hex = NtorqCodec.hex(value)
        val frame = NtorqCodec.decode(value)
        if (frame != null) {
            isLive = true
            // Road speed (frame 0x10 byte 0) drives the distance-to-turn countdown.
            if (frame.id == 0x10) speedKmh = frame.u(0)
            main.post { listener?.onFrame(frame, hex) }
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
        main.post { listener?.onStatus(text) }
    }
}
