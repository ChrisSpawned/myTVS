package com.mytvs.ntorq

import android.content.Context
import android.os.Handler
import android.os.Looper

/**
 * Decides what the cluster shows, twice a second, from two sources:
 *
 *  1. Google Maps' turn notification (MapsListener) — *which* turn is next, as
 *     Google routes it. Its distance only updates every ~2–40 s and is sometimes
 *     missing.
 *  2. The app's own route + phone GPS (RouteNav) — *exactly how far* each turn is,
 *     every second, plus total km / minutes left.
 *
 * With both, Google's turn is matched to the same turn on our route and the GPS
 * distance is shown. If they don't line up (different route), Google's distance
 * is used, counted down with the scooter's speed. With only our route
 * ("cluster only" mode), our own turns are shown.
 */
object NavBridge {
    @Volatile var gatt: NtorqGatt? = null
    @Volatile var statusListener: ((String) -> Unit)? = null

    private val main = Handler(Looper.getMainLooper())
    @Volatile private var maps: NavInfo? = null
    private var mapsActive = false

    // Dead reckoning of Google's distance between its notification updates.
    private var estDistM: Double? = null
    private var estAt = 0L
    private var stepKey = ""
    private var lastMapsDist: Int? = null
    private var lockedStep: RouteStep? = null
    private var tickCount = 0

    /** What is on the cluster right now (for the dashboard). */
    @Volatile var shown: NavInfo? = null
        private set
    @Volatile var shownDistM: Int? = null
        private set
    /** "GPS" when the distance comes from our own route, "Maps" otherwise. */
    @Volatile var distSource = ""
        private set
    val current: NavInfo? get() = shown
    @Volatile var isActive = false
        private set
    var lastStatus = "Waiting for navigation…"
        private set

    fun init(ctx: Context) { RouteNav.init(ctx) }

    // ------------------------------------------------------------ sources

    /** Every parsed Google Maps notification update (main thread). */
    fun onMapsUpdate(n: NavInfo) {
        if (n != maps) FrameLog.add("NAV", describe(n) + " | \"${n.instruction}\"")
        val key = n.maneuver.name + "|" + n.instruction
        val newStep = key != stepKey
        if (newStep) lockedStep = null
        if (n.distNextM != null) {
            // Maps re-posts the same text every few seconds; only a new value re-anchors.
            if (newStep || n.distNextM != lastMapsDist) {
                estDistM = n.distNextM.toDouble()
                estAt = System.currentTimeMillis()
            }
            lastMapsDist = n.distNextM
        } else if (newStep) {
            estDistM = null
            lastMapsDist = null
        }
        stepKey = key
        maps = n
        mapsActive = true
        evaluate()
    }

    /** Google Maps' navigation notification went away. */
    fun onMapsEnded() {
        if (!mapsActive) return
        mapsActive = false
        maps = null; estDistM = null; lastMapsDist = null; stepKey = ""; lockedStep = null
        FrameLog.add("NAV", "Google Maps navigation ended")
        if (RouteNav.active && RouteNav.withGoogleMaps) RouteNav.stop("Google Maps navigation ended")
        evaluate()
    }

    /** Our own route started, changed or stopped. */
    fun onRouteChanged() {
        lockedStep = null
        evaluate()
    }

    private fun evaluate() {
        val want = mapsActive || (RouteNav.active && RouteNav.tracker != null)
        if (want && !isActive) {
            isActive = true
            FrameLog.add("NAV", "navigation started — switching cluster to nav screen")
            gatt?.navStart()
            main.removeCallbacks(tick)
            main.postDelayed(tick, 4300)   // after both assist-on pairs, like the app
        } else if (!want && isActive) {
            isActive = false
            main.removeCallbacks(tick)
            gatt?.navStop()
            shown = null; shownDistM = null
            FrameLog.add("NAV", "navigation ended — closing cluster nav screen")
        }
        status(shown?.let { describe(it) } ?: if (isActive) "Navigating…" else "Navigation ended.")
    }

    private val tick = object : Runnable {
        override fun run() {
            if (!isActive) return
            push()
            main.postDelayed(this, 500)
        }
    }

    // ------------------------------------------------------------ compose

    private fun push() {
        val g = gatt
        val now = System.currentTimeMillis()
        val speed = g?.speedKmh?.takeIf { it > 0 } ?: RouteNav.lastFix?.takeIf { it.hasSpeed() }?.let { (it.speed * 3.6f).toInt() } ?: 0

        // Count Google's distance down with road speed. Don't run it to 0 m on our own:
        // hold a small floor until Maps confirms we're at the turn.
        estDistM?.let { d ->
            val dt = (now - estAt).coerceIn(0, 2000) / 1000.0
            val floor = if ((lastMapsDist ?: 0) > 30) 20.0 else 0.0
            estDistM = (d - speed / 3.6 * dt).coerceAtLeast(minOf(floor, d))
        }
        estAt = now

        val t = RouteNav.tracker
        val s = RouteNav.state
        val tracking = RouteNav.isTracking && t != null && s != null
        val m = maps

        val out: NavInfo?
        var dist: Int?
        if (m != null && mapsActive) {
            dist = estDistM?.let { Math.round(it).toInt() }
            distSource = "Maps"
            if (tracking) {
                // Keep the matched step while it's still ahead; otherwise look for it.
                val step = lockedStep?.takeIf { t!!.distTo(it) != null }
                    ?: RouteTracker.match(t!!, m.maneuver, dist).also { lockedStep = it }
                if (step != null) {
                    dist = t.distTo(step)
                    distSource = "GPS"
                }
            }
            out = m.copy(
                distNextM = dist,
                remainingMin = m.remainingMin ?: if (tracking) s!!.remainingMin else null,
                remainingM = m.remainingM ?: if (tracking) s!!.remainingM else null,
            )
        } else if (tracking && s!!.next != null) {
            val st = s.next!!
            dist = s.distToNextM
            distSource = "GPS"
            out = NavInfo(st.instruction, st.maneuver, st.roundaboutExit, dist, s.remainingMin, s.remainingM)
        } else {
            out = shown          // e.g. GPS briefly lost in cluster-only mode: hold the last frame
            dist = shownDistM
        }
        if (out != shown) status(describe(out ?: return))
        shown = out
        shownDistM = dist
        if (out == null || g == null || !g.isSessionOn) return

        val d = dist ?: 0
        val minutes = out.remainingMin ?: 0
        val totalM = out.remainingM ?: 0
        val picto = MapsParser.picto(out.maneuver, out.roundaboutExit)
        if (tickCount++ % 4 == 0) g.navPush(out.instruction, d, minutes, totalM, picto)
        else g.navPushControl(out.instruction, d, minutes, totalM, picto)
    }

    private fun describe(n: NavInfo): String {
        val turn = if (n.maneuver == Maneuver.ROUNDABOUT) "ROUNDABOUT exit ${n.roundaboutExit}" else n.maneuver.name
        val d = n.distNextM?.let { "$it m" } ?: "? m"
        val rest = listOfNotNull(n.remainingMin?.let { "$it min" }, n.remainingM?.let { "%.1f km".format(it / 1000.0) })
            .joinToString(" · ")
        return "$turn in $d${if (rest.isNotEmpty()) " ($rest left)" else ""}"
    }

    private fun status(s: String) {
        lastStatus = s
        main.post { statusListener?.invoke(s) }
    }
}
