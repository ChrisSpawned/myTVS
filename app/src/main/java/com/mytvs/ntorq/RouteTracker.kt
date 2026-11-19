package com.mytvs.ntorq

import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.roundToInt
import kotlin.math.sin
import kotlin.math.sqrt

/**
 * Pure-Kotlin route model and GPS tracker (unit-tested on CI).
 *
 * A [Route] is a polyline plus the list of maneuvers along it, each stored as its
 * distance from the start of the route. Given a GPS fix, [RouteTracker] projects
 * it onto the polyline and answers "how far to the next turn / to the end" every
 * second — no waiting for Google Maps' notification.
 */
data class LatLng(val lat: Double, val lng: Double)

data class RouteStep(
    val maneuver: Maneuver,
    val roundaboutExit: Int,
    val instruction: String,
    val road: String,
    /** Distance of this maneuver from the start of the route, metres. */
    val alongM: Double,
)

class Route(val points: List<LatLng>, val steps: List<RouteStep>, val durationS: Double) {
    /** Cumulative distance at each polyline vertex. */
    val cum: DoubleArray = DoubleArray(points.size).also { c ->
        for (i in 1 until points.size) c[i] = c[i - 1] + Geo.dist(points[i - 1], points[i])
    }
    val lengthM: Double get() = if (cum.isEmpty()) 0.0 else cum.last()
    val destination: LatLng get() = points.last()
}

object Geo {
    private const val R = 6371000.0

    fun dist(a: LatLng, b: LatLng): Double {
        val p1 = Math.toRadians(a.lat); val p2 = Math.toRadians(b.lat)
        val dp = p2 - p1; val dl = Math.toRadians(b.lng - a.lng)
        val h = sin(dp / 2) * sin(dp / 2) + cos(p1) * cos(p2) * sin(dl / 2) * sin(dl / 2)
        return 2 * R * atan2(sqrt(h), sqrt(1 - h))
    }

    /**
     * Projection of [p] onto segment a→b in a local flat frame.
     * Returns (fraction along segment 0..1, perpendicular distance metres).
     */
    fun project(p: LatLng, a: LatLng, b: LatLng): Pair<Double, Double> {
        val k = cos(Math.toRadians(a.lat))
        val ax = 0.0; val ay = 0.0
        val bx = (b.lng - a.lng) * k * 111320.0; val by = (b.lat - a.lat) * 110540.0
        val px = (p.lng - a.lng) * k * 111320.0; val py = (p.lat - a.lat) * 110540.0
        val len2 = (bx - ax) * (bx - ax) + (by - ay) * (by - ay)
        val t = if (len2 <= 1e-9) 0.0 else (((px - ax) * (bx - ax) + (py - ay) * (by - ay)) / len2).coerceIn(0.0, 1.0)
        val qx = ax + t * (bx - ax); val qy = ay + t * (by - ay)
        return t to sqrt((px - qx) * (px - qx) + (py - qy) * (py - qy))
    }

    /** Google encoded polyline (precision 5 or 6). */
    fun decodePolyline(s: String, precision: Int = 6): List<LatLng> {
        val factor = Math.pow(10.0, precision.toDouble())
        val out = ArrayList<LatLng>()
        var i = 0; var lat = 0L; var lng = 0L
        while (i < s.length) {
            for (axis in 0..1) {
                var shift = 0; var result = 0L
                while (true) {
                    val b = s[i++].code - 63
                    result = result or ((b and 0x1f).toLong() shl shift)
                    shift += 5
                    if (b < 0x20) break
                }
                val d = if (result and 1L != 0L) (result shr 1).inv() else result shr 1
                if (axis == 0) lat += d else lng += d
            }
            out.add(LatLng(lat / factor, lng / factor))
        }
        return out
    }
}

/** Result of one GPS update. */
data class TrackState(
    val alongM: Double,
    val offRouteM: Double,
    val next: RouteStep?,
    val nextIndex: Int,
    val distToNextM: Int,
    val remainingM: Int,
    val remainingMin: Int,
)

class RouteTracker(val route: Route) {
    private var seg = 0
    var last: TrackState? = null
        private set

    fun update(p: LatLng): TrackState {
        val pts = route.points
        // Search a window around the last matched segment (routes can loop back
        // past themselves, so a global nearest search would jump around).
        var bestSeg = seg; var bestT = 0.0; var bestD = Double.MAX_VALUE
        val from = (seg - 5).coerceAtLeast(0)
        val to = (seg + 120).coerceAtMost(pts.size - 2)
        for (i in from..to) {
            val (t, d) = Geo.project(p, pts[i], pts[i + 1])
            if (d < bestD - 0.01) { bestD = d; bestSeg = i; bestT = t }
        }
        if (bestD > 80) {
            // Far off the window: try the whole route once (e.g. GPS jumped after a tunnel).
            for (i in 0 until pts.size - 1) {
                val (t, d) = Geo.project(p, pts[i], pts[i + 1])
                if (d < bestD - 0.01) { bestD = d; bestSeg = i; bestT = t }
            }
        }
        seg = bestSeg
        val along = if (pts.size < 2) 0.0
        else route.cum[bestSeg] + bestT * (route.cum[bestSeg + 1] - route.cum[bestSeg])

        val idx = route.steps.indexOfFirst { it.alongM > along + 3 }
        val next = route.steps.getOrNull(idx)
        val remaining = (route.lengthM - along).coerceAtLeast(0.0)
        val minutes = if (route.lengthM > 0) route.durationS * remaining / route.lengthM / 60.0 else 0.0
        return TrackState(
            alongM = along,
            offRouteM = bestD,
            next = next,
            nextIndex = idx,
            distToNextM = next?.let { (it.alongM - along).coerceAtLeast(0.0).roundToInt() } ?: 0,
            remainingM = remaining.roundToInt(),
            remainingMin = kotlin.math.ceil(minutes).toInt(),
        ).also { last = it }
    }

    /** Distance from the last fix to a given step (metres), or null if behind us. */
    fun distTo(step: RouteStep): Int? {
        val a = last?.alongM ?: return null
        val d = step.alongM - a
        return if (d < -3) null else d.coerceAtLeast(0.0).roundToInt()
    }

    /** Upcoming steps within [withinM] metres of the last fix. */
    fun upcoming(withinM: Double): List<RouteStep> {
        val a = last?.alongM ?: return emptyList()
        return route.steps.filter { it.alongM > a + 3 && it.alongM - a <= withinM }
    }

    companion object {
        /** Direction family used to compare our route's maneuvers with Google Maps'. */
        fun family(m: Maneuver): Int = when (m) {
            Maneuver.LEFT, Maneuver.SHARP_LEFT, Maneuver.SLIGHT_LEFT, Maneuver.KEEP_LEFT -> 1
            Maneuver.RIGHT, Maneuver.SHARP_RIGHT, Maneuver.SLIGHT_RIGHT, Maneuver.KEEP_RIGHT -> 2
            Maneuver.UTURN -> 3
            Maneuver.ROUNDABOUT -> 4
            Maneuver.ARRIVE -> 5
            Maneuver.STRAIGHT -> 0
        }

        /**
         * Find the step on our route that Google Maps is talking about: same direction
         * family, and — when Maps gave a distance — about that far ahead.
         */
        fun match(t: RouteTracker, m: Maneuver, mapsDistM: Int?): RouteStep? {
            val fam = family(m)
            if (fam == 0) return null
            val cands = t.upcoming(if (mapsDistM != null) mapsDistM * 1.6 + 300 else 500.0)
                .filter { family(it.maneuver) == fam }
            if (cands.isEmpty()) return null
            if (mapsDistM == null) return cands.first()
            val best = cands.minByOrNull { kotlin.math.abs((t.distTo(it) ?: 0) - mapsDistM) } ?: return null
            val diff = kotlin.math.abs((t.distTo(best) ?: 0) - mapsDistM)
            return if (diff <= maxOf(60.0, mapsDistM * 0.3)) best else null
        }
    }
}
