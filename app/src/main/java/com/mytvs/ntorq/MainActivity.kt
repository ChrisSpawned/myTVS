package com.mytvs.ntorq

import android.Manifest
import android.annotation.SuppressLint
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothManager
import android.bluetooth.le.ScanCallback
import android.bluetooth.le.ScanResult
import android.bluetooth.le.ScanSettings
import android.content.Context
import android.content.pm.PackageManager
import android.graphics.Color
import android.graphics.Typeface
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.Gravity
import android.view.View
import android.widget.Button
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat

/**
 * Milestone 1 UI. Scan for the cluster, connect, let Android pair, and show the
 * decoded live frames. Read-only — nothing is written to the cluster.
 */
class MainActivity : AppCompatActivity(), NtorqGatt.Listener {

    private lateinit var statusView: TextView
    private lateinit var liveView: TextView
    private lateinit var logView: TextView
    private lateinit var connectBtn: Button

    private var gatt: NtorqGatt? = null
    private var scanning = false
    private val main = Handler(Looper.getMainLooper())
    private val adapter: BluetoothAdapter? by lazy {
        (getSystemService(Context.BLUETOOTH_SERVICE) as? BluetoothManager)?.adapter
    }
    private val latest = LinkedHashMap<Int, String>()

    private val permLauncher =
        registerForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { grants ->
            if (grants.values.all { it }) startScan() else toast("Bluetooth permission is required")
        }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(buildUi())
        status("Idle. Turn the scooter on, then tap Connect.")
    }

    private fun buildUi(): View {
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(24, 24, 24, 24)
        }
        val title = TextView(this).apply {
            text = "Ntorq 150 TFT — live link"
            textSize = 20f
            setTypeface(typeface, Typeface.BOLD)
        }
        statusView = TextView(this).apply { setPadding(0, 12, 0, 12); textSize = 14f }

        val row = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        connectBtn = Button(this).apply {
            text = "Connect"
            setOnClickListener { onConnectClicked() }
        }
        val exportBtn = Button(this).apply {
            text = "Export log"
            setOnClickListener {
                try {
                    startActivity(FrameLog.exportIntent(this@MainActivity))
                } catch (e: Exception) {
                    toast("Export failed: ${e.message}")
                }
            }
        }
        val probeBtn = Button(this).apply {
            text = "Probe map Wi-Fi"
            setOnClickListener { onProbeClicked() }
        }
        row.addView(connectBtn)
        row.addView(exportBtn)
        row.addView(probeBtn)
        val handshakeBtn = Button(this).apply {
            text = "Map handshake"
            setOnClickListener { onHandshakeClicked() }
        }
        row.addView(handshakeBtn)

        val liveTitle = section("Live frames")
        liveView = mono()
        val logTitle = section("Log")
        logView = mono()

        val scroll = ScrollView(this)
        val inner = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        inner.addView(title)
        inner.addView(statusView)
        inner.addView(row)
        inner.addView(liveTitle)
        inner.addView(liveView)
        inner.addView(logTitle)
        inner.addView(logView)
        scroll.addView(inner)
        root.addView(scroll)
        return root
    }

    private fun section(t: String) = TextView(this).apply {
        text = t
        setPadding(0, 24, 0, 6)
        setTypeface(typeface, Typeface.BOLD)
    }

    private fun mono() = TextView(this).apply {
        typeface = Typeface.MONOSPACE
        textSize = 12f
        setTextColor(Color.DKGRAY)
        gravity = Gravity.START
    }

    private fun onConnectClicked() {
        val a = adapter
        if (a == null || !a.isEnabled) {
            toast("Turn on Bluetooth first")
            return
        }
        if (gatt != null) {
            gatt?.disconnect()
            gatt = null
            connectBtn.text = "Connect"
            status("Disconnected.")
            return
        }
        val needed = requiredPermissions().filter {
            ContextCompat.checkSelfPermission(this, it) != PackageManager.PERMISSION_GRANTED
        }
        if (needed.isNotEmpty()) permLauncher.launch(needed.toTypedArray()) else startScan()
    }

    private fun requiredPermissions(): List<String> =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            listOf(Manifest.permission.BLUETOOTH_SCAN, Manifest.permission.BLUETOOTH_CONNECT)
        } else {
            listOf(Manifest.permission.ACCESS_FINE_LOCATION)
        }

    @SuppressLint("MissingPermission")
    private fun startScan() {
        val scanner = adapter?.bluetoothLeScanner ?: run { toast("No BLE scanner"); return }
        if (scanning) return
        scanning = true
        status("Scanning for the cluster (name starting \"TVS\")…")
        val settings = ScanSettings.Builder()
            .setScanMode(ScanSettings.SCAN_MODE_LOW_LATENCY)
            .build()
        scanner.startScan(null, settings, scanCallback)
        main.postDelayed({
            if (scanning) {
                scanning = false
                scanner.stopScan(scanCallback)
                if (gatt == null) status("No cluster found. Is the scooter on and nearby?")
            }
        }, 12000)
    }

    private val scanCallback = object : ScanCallback() {
        @SuppressLint("MissingPermission")
        override fun onScanResult(callbackType: Int, result: ScanResult) {
            val dev = result.device
            val name = try { dev.name } catch (e: SecurityException) { null } ?: return
            if (!name.uppercase().startsWith("TVS")) return
            scanning = false
            adapter?.bluetoothLeScanner?.stopScan(this)
            status("Found $name — connecting…")
            connect(dev)
        }

        override fun onScanFailed(errorCode: Int) {
            scanning = false
            status("Scan failed (code $errorCode)")
        }
    }

    private fun connect(dev: BluetoothDevice) {
        connectBtn.text = "Disconnect"
        gatt = NtorqGatt(this, this).also { it.connect(dev) }
    }

    override fun onStatus(text: String) {
        status(text)
        refreshLog()
    }

    override fun onFrame(frame: NtorqCodec.Frame, rawHex: String) {
        FrameLog.add("RX", "5A%02X  %s".format(frame.id, rawHex))
        latest[frame.id] = Telemetry.line(frame)
        liveView.text = latest.values.joinToString("\n")
        refreshLog()
    }

    private fun status(text: String) {
        statusView.text = text
    }

    private fun refreshLog() {
        val snap = FrameLog.snapshot()
        logView.text = snap.takeLast(40).joinToString("\n")
    }

    private fun toast(t: String) = Toast.makeText(this, t, Toast.LENGTH_SHORT).show()

    private fun onProbeClicked() {
        val a = adapter
        if (a == null || !a.isEnabled) { toast("Turn on Bluetooth/Wi-Fi"); return }
        status("Map-channel probe: make sure you're on the cluster's Wi-Fi…")
        FrameLog.add("WIFI", "---- map channel probe start ----")
        MapProbe.run(applicationContext, object : MapProbe.Listener {
            override fun onProbe(text: String) {
                main.post { status(text); refreshLog() }
            }
            override fun onDone() {
                main.post { status("Map-channel probe done. Export log to share."); refreshLog() }
            }
        })
    }

    private fun onHandshakeClicked() {
        status("Map handshake: make sure you're on the cluster's Wi-Fi…")
        FrameLog.add("WIFI", "---- map handshake start ----")
        MapProbe.handshake(applicationContext, object : MapProbe.Listener {
            override fun onProbe(text: String) { main.post { status(text); refreshLog() } }
            override fun onDone() { main.post { status("Handshake done. Export log."); refreshLog() } }
        })
    }

    override fun onDestroy() {
        gatt?.disconnect()
        super.onDestroy()
    }
}
