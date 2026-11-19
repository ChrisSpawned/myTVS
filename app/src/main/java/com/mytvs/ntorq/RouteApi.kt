package com.mytvs.ntorq

import org.json.JSONArray
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder

data class Place(val name: String, val detail: String, val pos: LatLng)

/**
 * Network side of the built-in route: OpenStreetMap routing (OSRM) and place search
 * (Nominatim), plus reading a place shared from Google Maps. Parsing is pure and
 * unit-tested; the fetch functions block, so call them off the main thread.
 */
object RouteApi {
    private const val UA = "NtorqRideCompanion/0.21 (personal scooter HUD app)"
    private const val OSRM = "https://router.project-osrm.org/route/v1/driving/"
    private const val NOMINATIM = "https://nominatim.openstreetmap.org/search"

    // ------------------------------------------------------------------ fetch

    private fun get(url: String, timeoutMs: Int = 12000): String {
        val c = URL(url).openConnection() as HttpURLConnection
        c.setRequestProperty("User-Agent", UA)
        c.setRequestProperty("Accept-Language", "en")
        c.connectTimeout = timeoutMs
        c.readTimeout = timeoutMs
        try {
            val code = c.responseCode
            val body = (if (code in 200..299) c.inputStream else c.errorStream)?.bufferedReader()?.readText() ?: ""
            if (code !in 200..299) throw java.io.IOException("HTTP $code")
            return body
        } finally {
            c.disconnect()
        }
    }

    fun route(from: LatLng, to: LatLng): Route {
        val url = OSRM + "%.6f,%.6f;%.6f,%.6f".format(java.util.Locale.US, from.lng, from.lat, to.lng, to.lat) +
            "?overview=full&geometries=polyline6&steps=true"
        return parseOsrm(get(url))
    }

    fun search(query: String, near: LatLng?): List<Place> {
        val q = URLEncoder.encode(query, "UTF-8")
        var url = "$NOMINATIM?q=$q&format=jsonv2&limit=8&addressdetails=0"
        if (near != null) {
            // Prefer results around the rider (≈ 60 km box) without excluding others.
            val d = 0.5
            url += "&viewbox=%.4f,%.4f,%.4f,%.4f".format(java.util.Locale.US, near.lng - d, near.lat + d, near.lng + d, near.lat - d)
        }
        return parseNominatim(get(url))
    }

    /**
     * A place shared from Google Maps ("Name\nhttps://maps.app.goo.gl/…"). Follows the
     * short link to find coordinates; falls back to searching the place name.
     */
    fun resolveShared(text: String, near: LatLng?): Place? {
        val name = text.lineSequence().map { it.trim() }.firstOrNull { it.isNotEmpty() && !it.startsWith("http") }
            ?: "Shared place"
        coordsIn(text)?.let { return Place(name, "Shared from Google Maps", it) }
        val link = Regex("""https?://\S+""").find(text)?.value
        if (link != null) {
            var u = link
            repeat(6) {
                val c = URL(u).openConnection() as HttpURLConnection
                c.instanceFollowRedirects = false
                c.setRequestProperty("User-Agent", "Mozilla/5.0")
                c.connectTimeout = 8000; c.readTimeout = 8000
                val loc = try { c.responseCode; c.getHeaderField("Location") } finally { c.disconnect() }
                if (loc == null) return@repeat
                coordsIn(java.net.URLDecoder.decode(loc, "UTF-8"))?.let { return Place(name, "Shared from Google Maps", it) }
                u = if (loc.startsWith("http")) loc else URL(URL(u), loc).toString()
            }
        }
        return search(name, near).firstOrNull()
    }

    // ------------------------------------------------------------------ parse

    /** Coordinates in a Google Maps URL or text: !3dLAT!4dLNG, @LAT,LNG, q=LAT,LNG, or bare "LAT, LNG". */
    fun coordsIn(s: String): LatLng? {
        val pats = listOf(
            Regex("""!3d(-?\d{1,2}\.\d+)!4d(-?\d{1,3}\.\d+)"""),
            Regex("""[?&](?:q|query|destination|daddr|ll)=(-?\d{1,2}\.\d+),\s*(-?\d{1,3}\.\d+)"""),
            Regex("""@(-?\d{1,2}\.\d+),(-?\d{1,3}\.\d+)"""),
            Regex("""^\s*(-?\d{1,2}\.\d{3,}),\s*(-?\d{1,3}\.\d{3,})\s*$""", RegexOption.MULTILINE),
        )
        for (p in pats) {
            val m = p.find(s) ?: continue
            val lat = m.groupValues[1].toDouble(); val lng = m.groupValues[2].toDouble()
            if (lat in -90.0..90.0 && lng in -180.0..180.0) return LatLng(lat, lng)
        }
        return null
    }

    fun parseNominatim(json: String): List<Place> {
        val a = JSONArray(json)
        return (0 until a.length()).map { i ->
            val o = a.getJSONObject(i)
            val full = o.optString("display_name")
            val name = o.optString("name").ifBlank { full.substringBefore(',') }
            Place(name, full.removePrefix(name).trim(',', ' '), LatLng(o.getString("lat").toDouble(), o.getString("lon").toDouble()))
        }
    }

    fun parseOsrm(json: String): Route {
        val root = JSONObject(json)
        if (root.optString("code") != "Ok") throw java.io.IOException("No route (${root.optString("code")})")
        val r = root.getJSONArray("routes").getJSONObject(0)
        val pts = Geo.decodePolyline(r.getString("geometry"), 6)
        val proto = Route(pts, emptyList(), r.optDouble("duration", 0.0))
        val steps = ArrayList<RouteStep>()
        var searchFrom = 0
        val legs = r.getJSONArray("legs")
        for (l in 0 until legs.length()) {
            val ss = legs.getJSONObject(l).getJSONArray("steps")
            for (i in 0 until ss.length()) {
                val s = ss.getJSONObject(i)
                val m = s.getJSONObject("maneuver")
                val loc = m.getJSONArray("location").let { LatLng(it.getDouble(1), it.getDouble(0)) }
                // Place the maneuver on the polyline (monotonic search from the previous one).
                var bestI = searchFrom; var bestT = 0.0; var bestD = Double.MAX_VALUE
                for (k in searchFrom until (pts.size - 1).coerceAtLeast(searchFrom)) {
                    val (t, d) = Geo.project(loc, pts[k], pts[k + 1])
                    if (d < bestD - 0.01) { bestD = d; bestI = k; bestT = t }
                    if (bestD < 2 && k > bestI + 30) break
                }
                searchFrom = bestI
                val along = if (pts.size < 2) 0.0 else proto.cum[bestI] + bestT * (proto.cum[bestI + 1] - proto.cum[bestI])
                val mapped = mapStep(m.optString("type"), m.optString("modifier"), m.optInt("exit", 0),
                    s.optString("name").ifBlank { s.optString("ref") }) ?: continue
                steps.add(mapped.copy(alongM = along))
            }
        }
        return Route(pts, steps, proto.durationS)
    }

    /** OSRM maneuver → cluster maneuver + a short instruction. Null = not worth an arrow. */
    fun mapStep(type: String, modifier: String, exit: Int, road: String): RouteStep? {
        fun dir(mod: String, fork: Boolean = false): Maneuver? = when (mod) {
            "uturn" -> Maneuver.UTURN
            "sharp right" -> Maneuver.SHARP_RIGHT
            "right" -> Maneuver.RIGHT
            "slight right" -> if (fork) Maneuver.KEEP_RIGHT else Maneuver.SLIGHT_RIGHT
            "sharp left" -> Maneuver.SHARP_LEFT
            "left" -> Maneuver.LEFT
            "slight left" -> if (fork) Maneuver.KEEP_LEFT else Maneuver.SLIGHT_LEFT
            "straight" -> Maneuver.STRAIGHT
            else -> null
        }
        val onto = if (road.isNotBlank()) " onto $road" else ""
        fun side(m: Maneuver) = if (TURN_LEFT.contains(m)) "left" else "right"
        return when (type) {
            "depart", "new name", "notification", "exit roundabout", "exit rotary" -> null
            "arrive" -> RouteStep(Maneuver.ARRIVE, 0, "Arrive at destination", road, 0.0)
            "roundabout", "rotary", "roundabout turn" -> {
                val n = exit.coerceAtLeast(0)
                RouteStep(Maneuver.ROUNDABOUT, n,
                    if (n > 0) "At the roundabout, take exit $n$onto" else "Enter the roundabout", road, 0.0)
            }
            "fork", "on ramp", "off ramp" -> {
                val m = dir(modifier, fork = true) ?: return null
                if (m == Maneuver.STRAIGHT) return null
                RouteStep(m, 0, "Keep ${side(m)}$onto", road, 0.0)
            }
            "continue", "merge" -> {
                val m = dir(modifier) ?: return null
                if (m == Maneuver.STRAIGHT) return null
                val verb = if (m == Maneuver.UTURN) "Make a U-turn" else if (type == "merge") "Merge ${side(m)}" else "Continue ${side(m)}"
                RouteStep(m, 0, "$verb$onto", road, 0.0)
            }
            else -> { // turn, end of road, use lane, …
                val m = dir(modifier) ?: return null
                if (m == Maneuver.STRAIGHT) return null
                val verb = when (m) {
                    Maneuver.UTURN -> "Make a U-turn"
                    Maneuver.SLIGHT_LEFT, Maneuver.SLIGHT_RIGHT -> "Slight ${side(m)}"
                    Maneuver.SHARP_LEFT, Maneuver.SHARP_RIGHT -> "Sharp ${side(m)}"
                    else -> "Turn ${side(m)}"
                }
                RouteStep(m, 0, "$verb$onto", road, 0.0)
            }
        }
    }

    private val TURN_LEFT = setOf(Maneuver.LEFT, Maneuver.SHARP_LEFT, Maneuver.SLIGHT_LEFT, Maneuver.KEEP_LEFT)
}
