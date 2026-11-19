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
import android.view.inputmethod.EditorInfo
import android.view.inputmethod.InputMethodManager
import android.text.Editable
import android.text.TextWatcher
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat

/**
 * Rider dashboard: connection and the Google Maps turn that is being mirrored
 * to the cluster. Export log lives under Developer tools.
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
    private lateinit var whereCard: LinearLayout
    private lateinit var searchBox: EditText
    private lateinit var results: LinearLayout
    private lateinit var picked: LinearLayout
    private lateinit var pickedName: TextView
    private lateinit var pickedDetail: TextView
    private lateinit var rideCard: LinearLayout
    private lateinit var rideTitle: TextView
    private lateinit var rideNote: TextView
    private var pickedPlace: Place? = null
    private lateinit var keyCard: LinearLayout
    private lateinit var keyBox: EditText
    private var session = java.util.UUID.randomUUID().toString()
    private var searchSeq = 0
    private val searchRunnable = Runnable { runAutocomplete() }
    private var pendingStart: Pair<Place, Boolean>? = null

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
    private val locPermLauncher =
        registerForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { grants ->
            val p = pendingStart
            pendingStart = null
            if (grants[Manifest.permission.ACCESS_FINE_LOCATION] == true || grants[Manifest.permission.ACCESS_COARSE_LOCATION] == true) {
                if (p != null) beginRide(p.first, p.second)
            } else {
                toast("Without location the cluster uses Google Maps' distances only")
                if (p != null && p.second) RouteNav.launchGoogleMaps(p.first)
            }
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
        RouteNav.onChange = { refresh() }
        handleShare(intent)
        if (Build.VERSION.SDK_INT >= 33 &&
            ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED
        ) notifPermLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
        refresh()
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        handleShare(intent)
    }

    /** Google Maps → Share → this app. */
    private fun handleShare(i: Intent?) {
        if (i?.action != Intent.ACTION_SEND) return
        val text = i.getStringExtra(Intent.EXTRA_TEXT) ?: return
        i.action = null
        FrameLog.add("ROUTE", "shared: ${text.replace('\n', ' ')}")
        status("Finding the shared place…")
        RouteNav.background({ GoogleApi.resolveShared(text, RouteNav.lastPos) }) { r ->
            val p = r.getOrNull()
            if (p != null) { pick(p); status("Ready — choose how to ride there.") }
            else status("Couldn't read that place (${r.exceptionOrNull()?.message ?: "not found"}). Try searching.")
        }
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

        // Where to?
        whereCard = cardBox()
        whereCard.addView(label("Where to?"))
        val searchRow = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL }
        searchBox = EditText(this).apply {
            hint = "Search Google Maps"
            setHintTextColor(muted)
            setTextColor(fg)
            textSize = 16f
            isSingleLine = true
            imeOptions = EditorInfo.IME_ACTION_SEARCH
            background = rounded(Color.parseColor("#1D2530"), 14)
            setPadding(dp(14), dp(13), dp(14), dp(13))
            setOnEditorActionListener { _, a, _ ->
                if (a == EditorInfo.IME_ACTION_SEARCH) { main.removeCallbacks(searchRunnable); runAutocomplete(); true } else false
            }
            addTextChangedListener(object : TextWatcher {
                override fun beforeTextChanged(s: CharSequence?, a: Int, b: Int, c: Int) {}
                override fun onTextChanged(s: CharSequence?, a: Int, b: Int, c: Int) {}
                override fun afterTextChanged(e: Editable?) {
                    main.removeCallbacks(searchRunnable)
                    if ((e?.length ?: 0) >= 2) main.postDelayed(searchRunnable, 250)
                    else { searchSeq++; results.removeAllViews(); if (pickedPlace == null) showRecents() }
                }
            })
        }
        searchRow.addView(searchBox, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f))
        whereCard.addView(searchRow, lp(10))
        results = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        whereCard.addView(results, lp(6))
        picked = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; visibility = View.GONE }
        pickedName = text(18f, fg, true)
        pickedDetail = text(13f, muted, false).apply { maxLines = 2 }
        picked.addView(pickedName, lp(6))
        picked.addView(pickedDetail, lp(2))
        picked.addView(button("Navigate with Google Maps", accent, bg) { pickedPlace?.let { startRide(it, true) } }, lp(12))
        picked.addView(button("Cluster only (no Maps)", Color.parseColor("#1D2530"), fg) { pickedPlace?.let { startRide(it, false) } }
            .apply { textSize = 14f }, lp(8))
        whereCard.addView(picked)
        col.addView(whereCard, lp(20))

        // Google Maps API key (one-time)
        keyCard = cardBox().apply { background = rounded(Color.parseColor("#1F1A10"), 20, Color.parseColor("#4A3A17")) }
        keyCard.addView(label("One-time setup").apply { setTextColor(amber) })
        keyCard.addView(text(16f, fg, true, "Connect Google Maps search & routes"), lp(6))
        keyCard.addView(text(13f, muted, false,
            "1. Open console.cloud.google.com → create a project (billing must be on; personal use stays inside the free monthly allowance).\n" +
                "2. APIs & Services → Library → enable \"Places API (New)\" and \"Routes API\".\n" +
                "3. Credentials → Create credentials → API key. Restrict it to those two APIs.\n" +
                "4. Paste the key here."), lp(4))
        keyBox = EditText(this).apply {
            hint = "AIza…"
            setHintTextColor(muted); setTextColor(fg); textSize = 14f; isSingleLine = true
            background = rounded(Color.parseColor("#1D2530"), 14)
            setPadding(dp(14), dp(12), dp(14), dp(12))
        }
        keyCard.addView(keyBox, lp(12))
        val keyBtns = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        keyBtns.addView(button("Open Google Cloud", Color.parseColor("#1D2530"), fg) {
            try { startActivity(Intent(Intent.ACTION_VIEW, android.net.Uri.parse("https://console.cloud.google.com/google/maps-apis/credentials"))) } catch (_: Exception) {}
        }.apply { textSize = 13f }, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f))
        keyBtns.addView(button("Save key", amber, bg) { saveKey() }.apply { textSize = 13f },
            LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f).apply { leftMargin = dp(8) })
        keyCard.addView(keyBtns, lp(10))
        col.addView(keyCard, lp(14))

        // Active ride
        rideCard = cardBox().apply { visibility = View.GONE }
        rideCard.addView(label("Riding to"))
        rideTitle = text(20f, fg, true)
        rideCard.addView(rideTitle, lp(4))
        rideNote = text(13f, muted, false)
        rideCard.addView(rideNote, lp(2))
        val rideBtns = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        rideBtns.addView(button("Open Google Maps", Color.parseColor("#1D2530"), fg) {
            RouteNav.destination?.let { RouteNav.launchGoogleMaps(it) }
        }.apply { textSize = 14f }, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f))
        rideBtns.addView(button("End ride", Color.parseColor("#3A1E22"), Color.parseColor("#FF8A8A")) {
            RouteNav.stop("ended by rider")
        }.apply { textSize = 14f }, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f).apply { leftMargin = dp(8) })
        rideCard.addView(rideBtns, lp(12))
        col.addView(rideCard, lp(20))

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
        col.addView(nav, lp(20))

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
        devBox.addView(devRow("Export log" to ::onExportClicked, "Google API key" to {
            keyBox.setText(GoogleApi.apiKey); keyCard.visibility = View.VISIBLE
        }), lp(8))
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

        // Navigation
        val n = NavBridge.current
        if (n != null && NavBridge.isActive) {
            navChip.visibility = View.VISIBLE
            navChip.text = if (NavBridge.distSource == "GPS") "LIVE · GPS" else "LIVE ON CLUSTER"
            navArrow.text = arrowFor(n.maneuver)
            navArrow.setTextColor(accent)
            navDist.text = fmtDist(NavBridge.shownDistM ?: n.distNextM)
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
            navInstr.text = if (RouteNav.active) RouteNav.note.ifBlank { "Getting route…" } else "Pick a destination above"
            navMeta.text = "Or start navigation in Google Maps — turns appear here and on your cluster automatically."
        }

        // Where-to / ride cards
        if (GoogleApi.apiKey.isBlank()) keyCard.visibility = View.VISIBLE
        val riding = RouteNav.active
        whereCard.visibility = if (riding) View.GONE else View.VISIBLE
        rideCard.visibility = if (riding) View.VISIBLE else View.GONE
        if (riding) {
            rideTitle.text = RouteNav.destination?.name ?: "Destination"
            rideNote.text = listOf(
                RouteNav.note,
                if (RouteNav.isTracking) "GPS locked" else "Waiting for GPS",
                if (RouteNav.withGoogleMaps) "with Google Maps" else "cluster only",
            ).filter { it.isNotBlank() }.joinToString("  ·  ")
        } else if (results.childCount == 0 && pickedPlace == null && searchBox.text.isEmpty()) {
            showRecents()
        }

        // Setup card
        val granted = NotificationManagerCompat.getEnabledListenerPackages(this).contains(packageName)
        accessCard.visibility = if (granted) View.GONE else View.VISIBLE

    }

    // ---------------------------------------------------------------- destination

    private fun hideKeyboard() {
        (getSystemService(Context.INPUT_METHOD_SERVICE) as InputMethodManager).hideSoftInputFromWindow(searchBox.windowToken, 0)
    }

    private fun placeRow(p: Place, icon: String): View = LinearLayout(this).apply {
        orientation = LinearLayout.HORIZONTAL
        gravity = Gravity.CENTER_VERTICAL
        setPadding(dp(4), dp(10), dp(4), dp(10))
        isClickable = true
        background = RippleDrawable(ColorStateList.valueOf(Color.parseColor("#22FFFFFF")), null, rounded(Color.WHITE, 12))
        addView(text(18f, accent, true, icon).apply { minWidth = dp(32) })
        val t = LinearLayout(this@MainActivity).apply { orientation = LinearLayout.VERTICAL }
        t.addView(text(15f, fg, true, p.name).apply { maxLines = 1 })
        if (p.detail.isNotBlank()) t.addView(text(12f, muted, false, p.detail).apply { maxLines = 1 })
        addView(t, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f))
        RouteNav.lastPos?.let { here ->
            addView(text(12f, muted, false, fmtDist(Geo.dist(here, p.pos).toInt())))
        }
        setOnClickListener { pick(p) }
    }

    private fun showRecents() {
        results.removeAllViews()
        val rec = RouteNav.recents()
        if (rec.isEmpty()) return
        results.addView(text(11f, muted, true, "RECENT").apply { letterSpacing = 0.15f; setPadding(dp(4), dp(8), 0, dp(2)) })
        rec.forEach { results.addView(placeRow(it, "↺")) }
    }

    private fun suggestionRow(sg: Suggestion): View = LinearLayout(this).apply {
        orientation = LinearLayout.HORIZONTAL
        gravity = Gravity.CENTER_VERTICAL
        setPadding(dp(4), dp(10), dp(4), dp(10))
        isClickable = true
        background = RippleDrawable(ColorStateList.valueOf(Color.parseColor("#22FFFFFF")), null, rounded(Color.WHITE, 12))
        addView(text(18f, accent, true, "⌖").apply { minWidth = dp(32) })
        val t = LinearLayout(this@MainActivity).apply { orientation = LinearLayout.VERTICAL }
        t.addView(text(15f, fg, true, sg.main).apply { maxLines = 1 })
        if (sg.secondary.isNotBlank()) t.addView(text(12f, muted, false, sg.secondary).apply { maxLines = 1 })
        addView(t, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f))
        setOnClickListener { choose(sg) }
    }

    /** Google Places autocomplete as you type (debounced; stale replies dropped). */
    private fun runAutocomplete() {
        val q = searchBox.text.toString().trim()
        if (q.length < 2) return
        if (GoogleApi.apiKey.isBlank()) { toast("Add your Google Maps API key first"); return }
        pickedPlace = null
        picked.visibility = View.GONE
        val seq = ++searchSeq
        val sess = session
        RouteNav.background({ GoogleApi.autocomplete(q, RouteNav.lastPos, sess) }) { r ->
            if (seq != searchSeq) return@background
            results.removeAllViews()
            val list = r.getOrNull()
            when {
                list == null -> results.addView(text(13f, amber, false, "Google search failed: ${r.exceptionOrNull()?.message}")
                    .apply { setPadding(dp(4), dp(8), 0, dp(8)) })
                list.isEmpty() -> results.addView(text(13f, muted, false, "No places found").apply { setPadding(dp(4), dp(8), 0, dp(8)) })
                else -> list.forEach { results.addView(suggestionRow(it)) }
            }
        }
    }

    private fun choose(sg: Suggestion) {
        hideKeyboard()
        searchSeq++
        results.removeAllViews()
        results.addView(text(13f, muted, false, "Loading ${sg.main}…").apply { setPadding(dp(4), dp(8), 0, dp(8)) })
        val sess = session
        session = java.util.UUID.randomUUID().toString()   // a details call closes the session
        RouteNav.background({ GoogleApi.details(sg, sess) }) { r ->
            results.removeAllViews()
            r.onSuccess { pick(it) }.onFailure { results.addView(text(13f, amber, false, "Couldn't load place: ${it.message}")) }
        }
    }

    private fun saveKey() {
        val k = keyBox.text.toString().trim()
        if (k.length < 20) { toast("That doesn't look like an API key"); return }
        RouteNav.saveApiKey(k)
        hideKeyboard()
        status("Checking the key…")
        RouteNav.background({ GoogleApi.autocomplete("Kochi", RouteNav.lastPos, java.util.UUID.randomUUID().toString()) }) { r ->
            if (r.isSuccess) { keyCard.visibility = View.GONE; status("Google Maps connected ✓") }
            else status("Key saved, but Google said: ${r.exceptionOrNull()?.message}")
            refresh()
        }
    }

    private fun pick(p: Place) {
        pickedPlace = p
        results.removeAllViews()
        pickedName.text = p.name
        pickedDetail.text = p.detail
        picked.visibility = View.VISIBLE
        refresh()
    }

    private fun startRide(p: Place, withMaps: Boolean) {
        val need = listOf(Manifest.permission.ACCESS_FINE_LOCATION, Manifest.permission.ACCESS_COARSE_LOCATION)
            .filter { ContextCompat.checkSelfPermission(this, it) != PackageManager.PERMISSION_GRANTED }
        if (need.isNotEmpty()) {
            pendingStart = p to withMaps
            locPermLauncher.launch(need.toTypedArray())
            return
        }
        beginRide(p, withMaps)
    }

    private fun beginRide(p: Place, withMaps: Boolean) {
        RouteNav.start(p, withMaps)
        RideService.start(this)   // keeps GPS + the scooter link alive behind Google Maps
        pickedPlace = null
        picked.visibility = View.GONE
        searchBox.setText("")
        if (GoogleApi.apiKey.isBlank()) toast("No Google API key: using Google Maps' own distances")
        if (gatt == null) toast("Tip: connect to the scooter to see turns on the cluster")
        refresh()
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
            if (!RouteNav.active) RideService.stop(this)
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

    private val lastRx = HashMap<Int, Pair<String, Long>>()

    override fun onFrame(frame: NtorqCodec.Frame, rawHex: String) {
        // The cluster streams ~10 frames/s; log a frame type only when it changes
        // (or every 5 s) so a long ride still fits in the exported log.
        val now = System.currentTimeMillis()
        val prev = lastRx[frame.id]
        if (prev != null && prev.first == rawHex && now - prev.second < 5000) return
        lastRx[frame.id] = rawHex to now
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
        RouteNav.onChange = null
        super.onDestroy()
    }
}
