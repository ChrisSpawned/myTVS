package com.mytvs.ntorq

import android.content.Context
import android.content.SharedPreferences
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
 * total distance remaining. With "kmpl in distance slot" on, the total-distance
 * field carries the CURRENT fuel economy instead (42 kmpl shows as "42.0km").
 */
object NavBridge {
    @Volatile var gatt: NtorqGatt? = null
    @Volatile var statusListener: ((String) -> Unit)? = null

    private val main = Handler(Looper.getMainLooper())
    private var prefs: SharedPreferences? = null
    @Volatile private var info: NavInfo? = null
    @Volatile var isActive = false
        private set
    var lastStatus = "Waiting for Google Maps navigation…"
        private set

    fun init(ctx: Context) {
        if (prefs == null) prefs = ctx.applicationContext.getSharedPreferences("nav", Context.MODE_PRIVATE)
    }

    var kmplInDistanceSlot: Boolean
        get() = prefs?.getBoolean("kmplInDistanceSlot", true) ?: true
        set(v) { prefs?.edit()?.putBoolean("kmplInDistanceSlot", v)?.apply() }

    /** Called on the main thread for every parsed Maps notification update. */
    fun onMapsUpdate(n: NavInfo) {
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
        FrameLog.add("NAV", "Google Maps navigation ended — closing cluster nav screen")
        status("Navigation ended.")
    }

    private val tick = object : Runnable {
        override fun run() {
            if (!isActive) return
            push()
            main.postDelayed(this, 1000)
        }
    }

    private fun push() {
        val g = gatt ?: return
        val n = info ?: return
        val minutes = n.remainingMin ?: 0
        val totalM = if (kmplInDistanceSlot) {
            g.currentKmplRaw().coerceAtLeast(0) * 1000
        } else {
            n.remainingM ?: 0
        }
        g.navPush(n.instruction, n.distNextM ?: 0, minutes, totalM, MapsParser.picto(n.maneuver, n.roundaboutExit))
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
