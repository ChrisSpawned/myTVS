package com.mytvs.ntorq

import android.content.Context
import android.os.Handler
import android.os.Looper

/**
 * Glue between Google Maps (via MapsListener) and the cluster (via NtorqGatt).
 *
 * When Maps starts navigating, the cluster is switched to its nav screen; then
 * every second the latest turn data is pushed (the official app also streams at
 * ~1 Hz). When the Maps navigation notification disappears, the nav screen is
 * closed.
 *
 * Cluster nav screen layout: arrow + distance-to-turn, and a bottom row with
 * ETA (derived by the cluster from the minutes field), minutes remaining and
 * total distance remaining. The total-distance field always carries the CURRENT
 * fuel economy instead (42 kmpl shows as "42.0km"), as the rider asked.
 */
object NavBridge {
    @Volatile var gatt: NtorqGatt? = null
    @Volatile var statusListener: ((String) -> Unit)? = null

    private val main = Handler(Looper.getMainLooper())
    @Volatile private var info: NavInfo? = null
    // Distance-to-turn estimate, counted down with the scooter's live speed between
    // Google Maps updates (Maps updates its notification only every ~10–40 s at times,
    // and sometimes drops the distance altogether).
    private var estDistM: Double? = null
    private var estAt = 0L
    private var stepKey = ""
    private var tickCount = 0
    /** Distance currently shown on the cluster (for the dashboard). */
    val shownDistM: Int? get() = estDistM?.toInt()
    /** The turn currently being shown on the cluster, or null when not navigating. */
    val current: NavInfo? get() = info
    @Volatile var isActive = false
        private set
    var lastStatus = "Waiting for Google Maps navigation…"
        private set

    @Suppress("UNUSED_PARAMETER")
    fun init(ctx: Context) { /* reserved */ }

    /** Called on the main thread for every parsed Maps notification update. */
    fun onMapsUpdate(n: NavInfo) {
        if (n != info) FrameLog.add("NAV", describe(n) + " | \"${n.instruction}\"")
        val key = n.maneuver.name + "|" + n.instruction
        if (n.distNextM != null) {
            estDistM = n.distNextM.toDouble()
            estAt = System.currentTimeMillis()
        } else if (key != stepKey) {
            estDistM = null      // new step and Maps gave no distance: unknown
        }
        stepKey = key
        info = n
        if (!isActive) {
            isActive = true
            FrameLog.add("NAV", "Google Maps navigation started — switching cluster to nav screen")
            gatt?.navStart()
            main.postDelayed(tick, 4300)   // after both assist-on pairs, like the app
        }
        status(describe(n))
    }

    /** Called when Maps' navigation notification is removed. */
    fun onMapsEnded() {
        if (!isActive) return
        isActive = false
        main.removeCallbacks(tick)
        gatt?.navStop()
        info = null
        estDistM = null
        stepKey = ""
        FrameLog.add("NAV", "Google Maps navigation ended — closing cluster nav screen")
        status("Navigation ended.")
    }

    private val tick = object : Runnable {
        override fun run() {
            if (!isActive) return
            push()
            main.postDelayed(this, 500)
        }
    }

    private fun push() {
        val g = gatt ?: return
        val n = info ?: return
        // Dead-reckon the distance with road speed from the cluster.
        val now = System.currentTimeMillis()
        estDistM?.let { d ->
            val dt = (now - estAt).coerceIn(0, 2000) / 1000.0
            estDistM = (d - g.speedKmh / 3.6 * dt).coerceAtLeast(0.0)
        }
        estAt = now
        val dist = estDistM?.let { Math.round(it).toInt() } ?: 0
        val minutes = n.remainingMin ?: 0
        // kmpl slot ("km" on the cluster): live kmpl if the cluster ever sends it,
        // otherwise the cluster's own average (the W626 cluster leaves live kmpl at 0).
        val kmpl = g.currentKmplRaw().takeIf { it > 0 } ?: g.averageKmplRaw().coerceAtLeast(0)
        val totalM = kmpl * 1000
        val picto = MapsParser.picto(n.maneuver, n.roundaboutExit)
        if (tickCount++ % 4 == 0) g.navPush(n.instruction, dist, minutes, totalM, picto)
        else g.navPushControl(n.instruction, dist, minutes, totalM, picto)
    }

    private fun describe(n: NavInfo): String {
        val turn = if (n.maneuver == Maneuver.ROUNDABOUT) "ROUNDABOUT exit ${n.roundaboutExit}" else n.maneuver.name
        val d = n.distNextM?.let { "$it m" } ?: "? m"
        val rest = listOfNotNull(n.remainingMin?.let { "$it min" }, n.remainingM?.let { "%.1f km".format(it / 1000.0) })
            .joinToString(" · ")
        return "Maps → cluster: $turn in $d${if (rest.isNotEmpty()) " ($rest left)" else ""}"
    }

    private fun status(s: String) {
        lastStatus = s
        main.post { statusListener?.invoke(s) }
    }
}
