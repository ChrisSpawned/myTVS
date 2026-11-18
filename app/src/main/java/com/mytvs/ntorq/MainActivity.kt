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
import android.content.Intent
import android.content.pm.PackageManager
import android.content.res.ColorStateList
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.graphics.drawable.RippleDrawable
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.provider.Settings
import android.util.TypedValue
import android.view.Gravity
import android.view.View
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat

/**
 * Rider dashboard: connection, live fuel economy, and the Google Maps turn that
 * is being mirrored to the cluster. Probes and logs live under Developer tools.
 */
class MainActivity : AppCompatActivity(), NtorqGatt.Listener {

    // Palette
    private val bg = Color.parseColor("#0B0F14")
    private val card = Color.parseColor("#151B23")
    private val stroke = Color.parseColor("#232C38")
    private val fg = Color.parseColor("#F2F5F8")
    private val muted = Color.parseColor("#8A97A6")
    private val accent = Color.parseColor("#2ED3B7")
    private val amber = Color.parseColor("#FFB547")
    private val green = Color.parseColor("#3DDC84")

    // Views
    private lateinit var pill: TextView
    private lateinit var kmplNow: TextView
    private lateinit var kmplAvg: TextView
    private lateinit var navArrow: TextView
    private lateinit var navDist: TextView
    private lateinit var navInstr: TextView
    private lateinit var navMeta: TextView
    private lateinit var navChip: TextView
    private lateinit var accessCard: View
    private lateinit var connectBtn: TextView
    private lateinit var statusView: TextView
    private lateinit var devBox: LinearLayout
    private lateinit var devToggle: TextView

    private var gatt: NtorqGatt? = null
    private var scanning = false
    private val main = Handler(Looper.getMainLooper())
    private val adapter: BluetoothAdapter? by lazy {
        (getSystemService(Context.BLUETOOTH_SERVICE) as? BluetoothManager)?.adapter
    }

    private val permLauncher =
        registerForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { grants ->
            if (grants.values.all { it }) startScan() else toast("Bluetooth permission is needed to reach the scooter")
        }
    private val notifPermLauncher =
        registerForActivityResult(ActivityResultContracts.RequestPermission()) { }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.statusBarColor = bg
        window.navigationBarColor = bg
        NavBridge.init(this)
        setContentView(buildUi())
        status("Turn the scooter on, then tap Connect.")
        NavBridge.gatt?.let {
            gatt = it
            it.listener = this
            status("Connected — link kept alive in the background.")
        }
        NavBridge.statusListener = { refresh() }
        if (Build.VERSION.SDK_INT >= 33 &&
            ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED
        ) notifPermLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
        refresh()
    }

    override fun onResume() {
        super.onResume()
        main.post(ticker)
    }

    override fun onPause() {
        main.removeCallbacks(ticker)
        super.onPause()
    }

    private val ticker = object : Runnable {
        override fun run() {
            refresh()
            main.postDelayed(this, 1000)
        }
    }

    // ---------------------------------------------------------------- UI build

    private fun dp(v: Int): Int =
        TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, v.toFloat(), resources.displayMetrics).toInt()

    private fun rounded(color: Int, radiusDp: Int, strokeColor: Int? = null) = GradientDrawable().apply {
        setColor(color)
        cornerRadius = dp(radiusDp).toFloat()
        if (strokeColor != null) setStroke(dp(1), strokeColor)
    }

    private fun text(sizeSp: Float, color: Int, bold: Boolean = false, s: String = "") = TextView(this).apply {
        text = s
        textSize = sizeSp
        setTextColor(color)
        if (bold) typeface = Typeface.create("sans-serif-medium", Typeface.BOLD)
    }

    private fun label(s: String) = text(11f, muted, true, s.uppercase()).apply { letterSpacing = 0.18f }

    private fun cardBox(): LinearLayout = LinearLayout(this).apply {
        orientation = LinearLayout.VERTICAL
        background = rounded(card, 20, stroke)
        setPadding(dp(20), dp(18), dp(20), dp(18))
    }

    private fun lp(top: Int = 0) = LinearLayout.LayoutParams(
        LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT,
    ).apply { topMargin = dp(top) }

    private fun button(s: String, fill: Int, textColor: Int, onClick: () -> Unit) = TextView(this).apply {
        text = s
        textSize = 16f
        gravity = Gravity.CENTER
        setTextColor(textColor)
        typeface = Typeface.create("sans-serif-medium", Typeface.BOLD)
        setPadding(dp(16), dp(16), dp(16), dp(16))
        background = RippleDrawable(ColorStateList.valueOf(Color.parseColor("#33FFFFFF")), rounded(fill, 16), null)
        isClickable = true
        setOnClickListener { onClick() }
    }

    private fun buildUi(): View {
        val col = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(20), dp(24), dp(20), dp(32))
        }

        // Header
        val header = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL }
        val titles = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        titles.addView(label("Ntorq 150 TFT"))
        titles.addView(text(26f, fg, true, "Ride Companion"))
        header.addView(titles, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f))
        pill = text(13f, fg, true).apply { setPadding(dp(12), dp(6), dp(14), dp(6)) }
        header.addView(pill)
        col.addView(header)

        // Fuel economy
        val fuel = cardBox()
        fuel.addView(label("Fuel economy · now"))
        val nowRow = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.BOTTOM }
        kmplNow = text(72f, accent, true, "--").apply { includeFontPadding = false }
        nowRow.addView(kmplNow)
        nowRow.addView(text(20f, muted, true, "  kmpl").apply { setPadding(0, 0, 0, dp(12)) })
        fuel.addView(nowRow, lp(4))
        kmplAvg = text(15f, muted, false, "Average —")
        fuel.addView(kmplAvg, lp(2))
        fuel.addView(text(12f, muted, false, "During navigation this shows on the cluster in the km slot."), lp(10))
        col.addView(fuel, lp(20))

        // Navigation
        val nav = cardBox()
        val navHead = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL }
        navHead.addView(label("Navigation"), LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f))
        navChip = text(11f, bg, true, "LIVE ON CLUSTER").apply {
            letterSpacing = 0.12f
            setPadding(dp(10), dp(4), dp(10), dp(4))
            background = rounded(accent, 12)
        }
        navHead.addView(navChip)
        nav.addView(navHead)
        val turnRow = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL }
        navArrow = text(54f, fg, true, "➤").apply {
            gravity = Gravity.CENTER
            minWidth = dp(76)
            background = rounded(Color.parseColor("#1D2530"), 18)
            setPadding(dp(10), dp(2), dp(10), dp(6))
        }
        turnRow.addView(navArrow)
        navDist = text(40f, fg, true, "").apply { setPadding(dp(18), 0, 0, 0) }
        turnRow.addView(navDist)
        nav.addView(turnRow, lp(12))
        navInstr = text(17f, fg, false)
        nav.addView(navInstr, lp(12))
        navMeta = text(14f, muted, false)
        nav.addView(navMeta, lp(4))
        col.addView(nav, lp(14))

        // Maps access (only when missing)
        val access = cardBox().apply { background = rounded(Color.parseColor("#1F1A10"), 20, Color.parseColor("#4A3A17")) }
        access.addView(label("One-time setup").apply { setTextColor(amber) })
        access.addView(text(16f, fg, true, "Allow access to Google Maps' directions"), lp(6))
        access.addView(text(13f, muted, false,
            "The app reads only Google Maps' turn notification to send arrows to your cluster. " +
                "If Android says it's a restricted setting: App info → ⋮ → Allow restricted settings."), lp(4))
        access.addView(button("Allow access", amber, bg) {
            try { startActivity(Intent(Settings.ACTION_NOTIFICATION_LISTENER_SETTINGS)) }
            catch (e: Exception) { toast("Open Settings → Notifications → Notification access") }
        }, lp(14))
        accessCard = access
        col.addView(access, lp(14))

        // Connect
        connectBtn = button("Connect to scooter", accent, bg) { onConnectClicked() }
        col.addView(connectBtn, lp(20))
        statusView = text(13f, muted, false).apply { gravity = Gravity.CENTER }
        col.addView(statusView, lp(10))

        // Developer tools
        devToggle = text(13f, muted, true, "Developer tools  ▾").apply {
            gravity = Gravity.CENTER
            setPadding(0, dp(18), 0, dp(6))
            setOnClickListener {
                devBox.visibility = if (devBox.visibility == View.VISIBLE) View.GONE else View.VISIBLE
                devToggle.text = if (devBox.visibility == View.VISIBLE) "Developer tools  ▴" else "Developer tools  ▾"
            }
        }
        col.addView(devToggle, lp(8))
        devBox = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; visibility = View.GONE }
        fun devRow(vararg items: Pair<String, () -> Unit>) = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            items.forEachIndexed { i, (name, fn) ->
                addView(button(name, Color.parseColor("#1D2530"), fg, fn).apply { textSize = 13f; setPadding(dp(8), dp(12), dp(8), dp(12)) },
                    LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f).apply { if (i > 0) leftMargin = dp(8) })
            }
        }
        devBox.addView(devRow("Export log" to ::onExportClicked), lp(8))
        col.addView(devBox)

        return ScrollView(this).apply {
            setBackgroundColor(bg)
            isFillViewport = true
            addView(col)
        }
    }

    // ---------------------------------------------------------------- refresh

    private fun arrowFor(m: Maneuver): String = when (m) {
        Maneuver.LEFT -> "←"
        Maneuver.RIGHT -> "→"
        Maneuver.SLIGHT_LEFT, Maneuver.KEEP_LEFT -> "↖"
        Maneuver.SLIGHT_RIGHT, Maneuver.KEEP_RIGHT -> "↗"
        Maneuver.SHARP_LEFT -> "↙"
        Maneuver.SHARP_RIGHT -> "↘"
        Maneuver.STRAIGHT -> "↑"
        Maneuver.UTURN -> "↶"
        Maneuver.ROUNDABOUT -> "↻"
        Maneuver.ARRIVE -> "⚑"
    }

    private fun fmtDist(m: Int?): String = when {
        m == null -> ""
        m >= 1000 -> "%.1f km".format(m / 1000.0)
        else -> "$m m"
    }

    private fun refresh() {
        val g = gatt
        // Connection pill + button
        val (pillText, pillColor) = when {
            g == null -> "Offline" to muted
            g.isLive -> "Connected" to green
            else -> "Connecting" to amber
        }
        pill.text = "●  $pillText"
        pill.setTextColor(pillColor)
        pill.background = rounded(Color.argb(40, Color.red(pillColor), Color.green(pillColor), Color.blue(pillColor)), 20)
        connectBtn.text = if (g == null) "Connect to scooter" else "Disconnect"
        connectBtn.background = RippleDrawable(
            ColorStateList.valueOf(Color.parseColor("#33FFFFFF")),
            if (g == null) rounded(accent, 16) else rounded(card, 16, stroke), null,
        )
        connectBtn.setTextColor(if (g == null) bg else fg)

        // Fuel
        val now = g?.currentKmplRaw() ?: -1
        val avg = g?.averageKmplRaw() ?: -1
        kmplNow.text = if (now >= 0) "$now" else "--"
        kmplAvg.text = if (avg >= 0) "Average  $avg kmpl" else "Average —"

        // Navigation
        val n = NavBridge.current
        if (n != null && NavBridge.isActive) {
            navChip.visibility = View.VISIBLE
            navArrow.text = arrowFor(n.maneuver)
            navArrow.setTextColor(accent)
            navDist.text = fmtDist(n.distNextM)
            navInstr.text = n.instruction.ifBlank { "Follow the route" }
            navMeta.text = listOfNotNull(
                n.remainingMin?.let { "$it min left" },
                n.remainingM?.let { "${fmtDist(it)} to go" },
                if (n.maneuver == Maneuver.ROUNDABOUT && n.roundaboutExit > 0) "exit ${n.roundaboutExit}" else null,
            ).joinToString("  ·  ")
        } else {
            navChip.visibility = View.GONE
            navArrow.text = "➤"
            navArrow.setTextColor(muted)
            navDist.text = ""
            navInstr.text = "Start navigation in Google Maps"
            navMeta.text = "Turns appear here and on your cluster automatically."
        }

        // Setup card
        val granted = NotificationManagerCompat.getEnabledListenerPackages(this).contains(packageName)
        accessCard.visibility = if (granted) View.GONE else View.VISIBLE

    }

    // ---------------------------------------------------------------- connect

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
            status("Disconnected.")
            refresh()
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
        val scanner = adapter?.bluetoothLeScanner ?: run { toast("No Bluetooth LE scanner"); return }
        if (scanning) return
        scanning = true
        status("Looking for your scooter…")
        val settings = ScanSettings.Builder().setScanMode(ScanSettings.SCAN_MODE_LOW_LATENCY).build()
        scanner.startScan(null, settings, scanCallback)
        main.postDelayed({
            if (scanning) {
                scanning = false
                scanner.stopScan(scanCallback)
                if (gatt == null) status("Scooter not found. Is it switched on and nearby?")
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
        gatt = NtorqGatt(applicationContext, this).also { it.connect(dev) }
        NavBridge.gatt = gatt
        RideService.start(this)
        refresh()
    }

    // ---------------------------------------------------------------- listener

    override fun onStatus(text: String) {
        status(text)
        refresh()
    }

    override fun onFrame(frame: NtorqCodec.Frame, rawHex: String) {
        FrameLog.add("RX", "5A%02X  %s".format(frame.id, rawHex))
    }

    private fun status(text: String) {
        statusView.text = text
    }

    private fun toast(t: String) = Toast.makeText(this, t, Toast.LENGTH_SHORT).show()

    // ---------------------------------------------------------------- dev tools

    private fun onExportClicked() {
        try { startActivity(FrameLog.exportIntent(this)) } catch (e: Exception) { toast("Export failed: ${e.message}") }
    }

    override fun onDestroy() {
        // Keep the link alive (RideService) so Google Maps turns still reach the
        // cluster; just detach this screen. Use Disconnect to end the session.
        gatt?.listener = null
        NavBridge.statusListener = null
        super.onDestroy()
    }
}
