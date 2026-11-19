package com.mytvs.ntorq

import android.content.Context
import android.os.Handler
import android.os.Looper

/**
 * Decides what the cluster shows, twice a second.
 *
 * Primary: the Google route fetched by the app (RouteNav, Routes API, two-wheeler)
 * followed with phone GPS — the next turn and an exact, smoothly counting distance,
 * total km and minutes. Google Maps (the app on screen) rides the same route; its
 * notification is only watched to notice when it reroutes, so we fetch the new
 * route too.
 *
 * Fallback (navigation started directly in Google Maps, no API key, or no GPS):
 * Google Maps' notification, counted down with road speed, with flicker suppression.
 */
object NavBridge {
    @Volatile var gatt: NtorqGatt? = null
    @Volatile var statusListener: ((String) -> Unit)? = null

    private val main = Handler(Looper.getMainLooper())
    @Volatile private var maps: NavInfo? = null
    private var mapsActive = false

    // Fallback: Google's distance between its notification updates.
    private var estDistM: Double? = null
    private var estAt = 0L
    private var stepKey = ""
    private var prevKey = ""
    private var stepAt = 0L
    private var lastMapsDist: Int? = null
    private var mismatchSince = 0L

    // Primary: smoothing of the GPS distance.
    private var shownStep = -1
    private var tickCount = 0

    @Volatile var shown: NavInfo? = null
        private set
    @Volatile var shownDistM: Int? = null
        private set
    /** "GPS" when driven by our Google route, "Maps" when by the notification. */
    @Volatile var distSource = ""
        private set
    val current: NavInfo? get() = shown
    @Volatile var isActive = false
        private set
    var lastStatus = "Waiting for navigation…"
        private set

    fun init(ctx: Context) { RouteNav.init(ctx) }

    private fun speedKmh(): Int =
        gatt?.speedKmh?.takeIf { it > 0 }
            ?: RouteNav.lastFix?.takeIf { it.hasSpeed() }?.let { (it.speed * 3.6f).toInt() } ?: 0

    // ------------------------------------------------------------ sources

    fun onMapsUpdate(n: NavInfo) {
        val now = System.currentTimeMillis()
        val key = n.maneuver.name + "|" + n.instruction
        // Flicker guard: at low speed Google sometimes alternates A→B→A every few
        // seconds (GPS noise at the start). Ignore a flip straight back to the step
        // we just left.
        if (key != stepKey && key == prevKey && now - stepAt < 15000 && speedKmh() < 8) return
        if (n != maps) FrameLog.add("NAV", "Maps: " + describe(n) + " | \"${n.instruction}\"")
        val newStep = key != stepKey
        if (newStep) { prevKey = stepKey; stepAt = now }
        if (n.distNextM != null) {
            if (newStep || n.distNextM != lastMapsDist) {   // Maps re-posts identical text
                estDistM = n.distNextM.toDouble()
                estAt = now
            }
            lastMapsDist = n.distNextM
        } else if (newStep) {
            estDistM = null
            lastMapsDist = null
        }
        stepKey = key
        maps = n
        mapsActive = true
        checkAgainstRoute(n)
        evaluate()
    }

    /** Is Google's next turn on our route? If not for a while, fetch Google's new route. */
    private fun checkAgainstRoute(n: NavInfo) {
        val t = RouteNav.tracker ?: return
        if (!RouteNav.isTracking) return
        val fam = RouteTracker.family(n.maneuver)
        if (fam == 0 || fam == 5) { mismatchSince = 0; return }
        val within = (n.distNextM ?: 300) + 250.0
        val ok = t.upcoming(within).any { RouteTracker.family(it.maneuver) == fam }
        val now = System.currentTimeMillis()
        if (ok) { mismatchSince = 0; return }
        if (mismatchSince == 0L) mismatchSince = now
        else if (now - mismatchSince > 8000) {
            mismatchSince = 0
            RouteNav.reroute("Google Maps shows ${n.maneuver} not on our route")
        }
    }

    fun onMapsRerouting() {
        main.postDelayed({ RouteNav.reroute("Google Maps rerouted") }, 4000)
    }

    fun onMapsEnded() {
        if (!mapsActive) return
        mapsActive = false
        maps = null; estDistM = null; lastMapsDist = null; stepKey = ""; prevKey = ""
        FrameLog.add("NAV", "Google Maps navigation ended")
        if (RouteNav.active && RouteNav.withGoogleMaps) RouteNav.stop("Google Maps navigation ended")
        evaluate()
    }

    fun onRouteChanged() {
        shownStep = -1
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
            shown = null; shownDistM = null; shownStep = -1
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
        val speed = speedKmh()

        estDistM?.let { d ->
            val dt = (now - estAt).coerceIn(0, 2000) / 1000.0
            val floor = if ((lastMapsDist ?: 0) > 30) 20.0 else 0.0
            estDistM = (d - speed / 3.6 * dt).coerceAtLeast(minOf(floor, d))
        }
        estAt = now

        val s = RouteNav.state
        val m = maps
        val out: NavInfo?
        val dist: Int?
        if (RouteNav.isTracking && s?.next != null) {
            // Our Google route: GPS distance, advanced with speed between 1 Hz fixes,
            // never counting back up within a step (except a real GPS correction).
            val st = s.next
            // Between fixes (and through GPS gaps up to 30 s) move along the route at road speed.
            val age = (now - RouteNav.lastFixAt).coerceIn(0, 30000) / 1000.0
            var d = (s.distToNextM - speed / 3.6 * age).coerceAtLeast(0.0).toInt()
            val prev = shownDistM
            if (s.nextIndex == shownStep && prev != null && d > prev && d < prev + 25) d = prev
            shownStep = s.nextIndex
            dist = d
            distSource = "GPS"
            val minutes = if (mapsActive && m?.remainingMin != null) m.remainingMin else s.remainingMin
            out = NavInfo(st.instruction, st.maneuver, st.roundaboutExit, d, minutes, s.remainingM)
        } else if (mapsActive && m != null) {
            dist = estDistM?.let { Math.round(it).toInt() }
            distSource = "Maps"
            out = m.copy(distNextM = dist, remainingM = m.remainingM ?: RouteNav.state?.remainingM)
        } else {
            out = shown          // e.g. GPS briefly lost: hold the last frame
            dist = shownDistM
        }
        if (out == null) return
        if (out.instruction != shown?.instruction) FrameLog.add("NAV", "cluster [$distSource]: ${describe(out)} | \"${out.instruction}\"")
        if (out != shown) status(describe(out))
        shown = out
        shownDistM = dist
        if (g == null || !g.isSessionOn) return

        val minutes = out.remainingMin ?: 0
        val totalM = out.remainingM ?: 0
        val picto = MapsParser.picto(out.maneuver, out.roundaboutExit)
        if (tickCount++ % 4 == 0) g.navPush(out.instruction, dist ?: 0, minutes, totalM, picto)
        else g.navPushControl(out.instruction, dist ?: 0, minutes, totalM, picto)
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
