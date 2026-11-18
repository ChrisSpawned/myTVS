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
import android.widget.CheckBox
import android.content.Intent
import android.provider.Settings
import androidx.core.app.NotificationManagerCompat
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
    private lateinit var navStatusView: TextView
    private lateinit var notifBtn: Button

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
        NavBridge.init(this)
        setContentView(buildUi())
        status("Idle. Turn the scooter on, then tap Connect.")
        // Re-attach to a link that survived while this screen was closed.
        NavBridge.gatt?.let {
            gatt = it
            it.listener = this
            connectBtn.text = "Disconnect"
            status("Connected (link kept alive in the background).")
        }
        NavBridge.statusListener = { s -> navStatusView.text = s }
        navStatusView.text = NavBridge.lastStatus
        if (Build.VERSION.SDK_INT >= 33 &&
            ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED
        ) {
            registerForActivityResult(ActivityResultContracts.RequestPermission()) { }.launch(Manifest.permission.POST_NOTIFICATIONS)
        }
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
            text = "Export"
            setOnClickListener {
                try {
                    startActivity(FrameLog.exportIntent(this@MainActivity))
                } catch (e: Exception) {
                    toast("Export failed: ${e.message}")
                }
            }
        }
        val probeBtn = Button(this).apply {
            text = "Wi-Fi probe"
            setOnClickListener { onProbeClicked() }
        }
        val handshakeBtn = Button(this).apply {
            text = "Handshake"
            setOnClickListener { onHandshakeClicked() }
        }
        val imageBtn = Button(this).apply {
            text = "Image probe"
            setOnClickListener { onImageProbeClicked() }
        }
        val navBtn = Button(this).apply {
            text = "Nav test"
            setOnClickListener { onNavTestClicked() }
        }
        notifBtn = Button(this).apply {
            setOnClickListener {
                try {
                    startActivity(Intent(Settings.ACTION_NOTIFICATION_LISTENER_SETTINGS))
                } catch (e: Exception) {
                    toast("Open Settings → Notifications → Notification access")
                }
            }
        }
        val kmplBox = CheckBox(this).apply {
            text = "kmpl in distance slot"
            isChecked = NavBridge.kmplInDistanceSlot
            setOnCheckedChangeListener { _, on -> NavBridge.kmplInDistanceSlot = on }
        }
        navStatusView = TextView(this).apply { setPadding(0, 8, 0, 8); textSize = 14f; setTypeface(typeface, Typeface.BOLD) }
        val row4 = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        val row2 = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        val row3 = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        fun weighted() = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
        row.addView(connectBtn, weighted())
        row.addView(exportBtn, weighted())
        row2.addView(probeBtn, weighted())
        row2.addView(handshakeBtn, weighted())
        row3.addView(imageBtn, weighted())
        row3.addView(navBtn, weighted())
        row4.addView(notifBtn, weighted())
        row4.addView(kmplBox, weighted())

        val liveTitle = section("Live frames")
        liveView = mono()
        val logTitle = section("Log")
        logView = mono()

        val scroll = ScrollView(this)
        val inner = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        inner.addView(title)
        inner.addView(statusView)
        inner.addView(navStatusView)
        inner.addView(row4)
        inner.addView(row)
        inner.addView(row2)
        inner.addView(row3)
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
            NavBridge.gatt = null
            RideService.stop(this)
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
        gatt = NtorqGatt(applicationContext, this).also { it.connect(dev) }
        NavBridge.gatt = gatt
        RideService.start(this)
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

    private fun onImageProbeClicked() {
        val g = gatt
        if (g == null) {
            toast("Connect to the cluster first")
            return
        }
        status("Image-channel probe: sending INIT frames over BLE…")
        FrameLog.add("BLE", "---- image-channel INIT probe start ----")
        g.sendImageInitProbe()
        refreshLog()
    }

    private fun onNavTestClicked() {
        val g = gatt
        if (g == null) {
            toast("Connect to the cluster first")
            return
        }
        status("Nav test: sending turn-by-turn frames over BLE…")
        FrameLog.add("BLE", "---- nav test start ----")
        g.sendNavTest()
        refreshLog()
    }

    private fun onHandshakeClicked() {
        status("Map handshake: make sure you're on the cluster's Wi-Fi…")
        FrameLog.add("WIFI", "---- map handshake start ----")
        MapProbe.handshake(applicationContext, object : MapProbe.Listener {
            override fun onProbe(text: String) { main.post { status(text); refreshLog() } }
            override fun onDone() { main.post { status("Handshake done. Export log."); refreshLog() } }
        })
    }

    override fun onResume() {
        super.onResume()
        val granted = NotificationManagerCompat.getEnabledListenerPackages(this).contains(packageName)
        notifBtn.text = if (granted) "Maps access ✓" else "Allow Maps access"
    }

    override fun onDestroy() {
        // Keep the link alive (RideService) so Google Maps turns still reach the
        // cluster; just detach this screen. Use Disconnect to end the session.
        gatt?.listener = null
        NavBridge.statusListener = null
        super.onDestroy()
    }
}
