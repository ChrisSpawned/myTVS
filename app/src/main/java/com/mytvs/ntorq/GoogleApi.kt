package com.mytvs.ntorq

import org.json.JSONArray
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL

data class Place(val name: String, val detail: String, val pos: LatLng)
data class Suggestion(val placeId: String, val main: String, val secondary: String)

/**
 * Google Maps Platform: Places (New) autocomplete + details for search, and the
 * Routes API (TWO_WHEELER) for the same route the Google Maps app rides.
 * Needs the rider's own API key (free monthly allowance covers personal use).
 * Fetch functions block — call off the main thread. Parsers are unit-tested.
 */
object GoogleApi {
    @Volatile var apiKey: String = ""
    /** Sent so a key restricted to "Android apps" accepts our calls. */
    @Volatile var androidPackage: String = ""
    @Volatile var androidCertSha1: String = ""

    private fun call(method: String, url: String, body: String?, fieldMask: String?): String {
        if (apiKey.isBlank()) throw IllegalStateException("Google Maps API key not set")
        val c = URL(url).openConnection() as HttpURLConnection
        c.requestMethod = method
        c.setRequestProperty("X-Goog-Api-Key", apiKey)
        if (androidPackage.isNotEmpty() && androidCertSha1.isNotEmpty()) {
            c.setRequestProperty("X-Android-Package", androidPackage)
            c.setRequestProperty("X-Android-Cert", androidCertSha1)
        }
        if (fieldMask != null) c.setRequestProperty("X-Goog-FieldMask", fieldMask)
        c.connectTimeout = 10000; c.readTimeout = 10000
        try {
            if (body != null) {
                c.doOutput = true
                c.setRequestProperty("Content-Type", "application/json")
                c.outputStream.use { it.write(body.toByteArray()) }
            }
            val code = c.responseCode
            val text = (if (code in 200..299) c.inputStream else c.errorStream)?.bufferedReader()?.readText() ?: ""
            val what = URL(url).path.substringAfterLast('/').substringBefore('?').take(40)
            if (code !in 200..299) {
                val msg = runCatching { JSONObject(text).getJSONObject("error").getString("message") }.getOrNull()
                FrameLog.add("GOOGLE", "$what → HTTP $code: ${msg ?: text.take(300)}")
                throw java.io.IOException(msg ?: "HTTP $code")
            }
            FrameLog.add("GOOGLE", "$what → OK (${text.length} bytes)")
            return text
        } catch (e: java.io.IOException) {
            if (e.message?.startsWith("HTTP") != true) FrameLog.add("GOOGLE", "network error: ${e.javaClass.simpleName}: ${e.message}")
            throw e
        } finally { c.disconnect() }
    }

    // ------------------------------------------------------------ Places

    fun autocomplete(input: String, near: LatLng?, session: String): List<Suggestion> {
        val b = JSONObject().put("input", input).put("sessionToken", session)
            .put("languageCode", "en").put("regionCode", "in")
        if (near != null) {
            b.put("locationBias", JSONObject().put("circle", JSONObject()
                .put("center", JSONObject().put("latitude", near.lat).put("longitude", near.lng))
                .put("radius", 50000.0)))
            b.put("origin", JSONObject().put("latitude", near.lat).put("longitude", near.lng))
        }
        return parseAutocomplete(call("POST", "https://places.googleapis.com/v1/places:autocomplete", b.toString(), null))
    }

    fun details(s: Suggestion, session: String): Place {
        val url = "https://places.googleapis.com/v1/places/${s.placeId}?sessionToken=$session"
        val o = JSONObject(call("GET", url, null, "location,formattedAddress"))
        val loc = o.getJSONObject("location")
        return Place(s.main, s.secondary.ifBlank { o.optString("formattedAddress") },
            LatLng(loc.getDouble("latitude"), loc.getDouble("longitude")))
    }

    /** Top Google result for free text (used for places shared without coordinates). */
    fun findPlace(text: String, near: LatLng?): Place? {
        val session = java.util.UUID.randomUUID().toString()
        val s = autocomplete(text, near, session).firstOrNull() ?: return null
        return details(s, session)
    }

    fun parseAutocomplete(json: String): List<Suggestion> {
        val a = JSONObject(json).optJSONArray("suggestions") ?: return emptyList()
        return (0 until a.length()).mapNotNull { i ->
            val p = a.getJSONObject(i).optJSONObject("placePrediction") ?: return@mapNotNull null
            val f = p.optJSONObject("structuredFormat")
            val main = f?.optJSONObject("mainText")?.optString("text")
                ?: p.optJSONObject("text")?.optString("text") ?: return@mapNotNull null
            val sec = f?.optJSONObject("secondaryText")?.optString("text") ?: ""
            Suggestion(p.getString("placeId"), main, sec)
        }
    }

    // ------------------------------------------------------------ Routes

    private const val ROUTE_MASK = "routes.duration,routes.distanceMeters,routes.polyline.encodedPolyline," +
        "routes.legs.steps.distanceMeters,routes.legs.steps.navigationInstruction"

    fun route(from: LatLng, to: LatLng, headingDeg: Float?): Route {
        fun wp(p: LatLng, heading: Float? = null) = JSONObject().put("location", JSONObject()
            .put("latLng", JSONObject().put("latitude", p.lat).put("longitude", p.lng))
            .apply { if (heading != null) put("heading", heading.toInt()) })
        val b = JSONObject()
            .put("origin", wp(from, headingDeg))
            .put("destination", wp(to))
            .put("travelMode", "TWO_WHEELER")
            .put("languageCode", "en-IN")
            .put("units", "METRIC")
        return parseRoute(call("POST", "https://routes.googleapis.com/directions/v2:computeRoutes", b.toString(), ROUTE_MASK))
    }

    /** Google route → polyline + maneuvers (each step's maneuver happens at the step's start). */
    fun parseRoute(json: String): Route {
        val routes = JSONObject(json).optJSONArray("routes")
        if (routes == null || routes.length() == 0) throw java.io.IOException("Google found no route")
        val r = routes.getJSONObject(0)
        val pts = Geo.decodePolyline(r.getJSONObject("polyline").getString("encodedPolyline"), 5)
        val durationS = r.optString("duration", "0s").removeSuffix("s").toDoubleOrNull() ?: 0.0
        val proto = Route(pts, emptyList(), durationS)
        val steps = ArrayList<RouteStep>()
        var along = 0.0
        val legs = r.optJSONArray("legs") ?: JSONArray()
        for (l in 0 until legs.length()) {
            val ss = legs.getJSONObject(l).optJSONArray("steps") ?: continue
            for (i in 0 until ss.length()) {
                val s = ss.getJSONObject(i)
                val ni = s.optJSONObject("navigationInstruction")
                val mapped = ni?.let { mapManeuver(it.optString("maneuver"), it.optString("instructions")) }
                if (mapped != null && along > 1) steps.add(mapped.copy(alongM = along))
                along += s.optDouble("distanceMeters", 0.0)
            }
        }
        // Scale step positions to the decoded polyline (they can differ by a few metres).
        val k = if (along > 0 && proto.lengthM > 0) proto.lengthM / along else 1.0
        val scaled = steps.map { it.copy(alongM = it.alongM * k) }.toMutableList()
        scaled.add(RouteStep(Maneuver.ARRIVE, 0, "Arrive at destination", "", proto.lengthM))
        return Route(pts, scaled, durationS)
    }

    private val ORD = listOf("first", "second", "third", "fourth", "fifth", "sixth", "seventh", "eighth")

    fun mapManeuver(m: String, text: String): RouteStep? {
        val firstLine = text.lineSequence().firstOrNull()?.trim() ?: ""
        val man = when (m) {
            "TURN_LEFT" -> Maneuver.LEFT
            "TURN_RIGHT" -> Maneuver.RIGHT
            "TURN_SLIGHT_LEFT" -> Maneuver.SLIGHT_LEFT
            "TURN_SLIGHT_RIGHT" -> Maneuver.SLIGHT_RIGHT
            "TURN_SHARP_LEFT" -> Maneuver.SHARP_LEFT
            "TURN_SHARP_RIGHT" -> Maneuver.SHARP_RIGHT
            "UTURN_LEFT", "UTURN_RIGHT" -> Maneuver.UTURN
            "RAMP_LEFT", "FORK_LEFT" -> Maneuver.KEEP_LEFT
            "RAMP_RIGHT", "FORK_RIGHT" -> Maneuver.KEEP_RIGHT
            "ROUNDABOUT_LEFT", "ROUNDABOUT_RIGHT", "ROUNDABOUT_CLOCKWISE", "ROUNDABOUT_COUNTERCLOCKWISE" -> Maneuver.ROUNDABOUT
            "MERGE" -> MapsParser.maneuver(firstLine).first.takeIf { it != Maneuver.STRAIGHT } ?: return null
            else -> return null   // DEPART, STRAIGHT, NAME_CHANGE, FERRY…: no arrow needed
        }
        var exit = 0
        if (man == Maneuver.ROUNDABOUT) {
            val t = firstLine.lowercase()
            exit = Regex("""(\d+)(?:st|nd|rd|th)\s+exit""").find(t)?.groupValues?.get(1)?.toIntOrNull()
                ?: ORD.indexOfFirst { t.contains("$it exit") }.let { if (it >= 0) it + 1 else 0 }
        }
        return RouteStep(man, exit, firstLine.ifBlank { man.name }, "", 0.0)
    }

    // ------------------------------------------------------------ shared links

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

    /** "Name\nhttps://maps.app.goo.gl/…" shared from Google Maps → place. */
    fun resolveShared(text: String, near: LatLng?): Place? {
        val name = text.lineSequence().map { it.trim() }.firstOrNull { it.isNotEmpty() && !it.startsWith("http") }
            ?: "Shared place"
        coordsIn(text)?.let { return Place(name, "Shared from Google Maps", it) }
        val link = Regex("""https?://\S+""").find(text)?.value
        if (link != null) {
            var u = link
            for (hop in 0 until 6) {
                val c = URL(u).openConnection() as HttpURLConnection
                c.instanceFollowRedirects = false
                c.setRequestProperty("User-Agent", "Mozilla/5.0")
                c.connectTimeout = 8000; c.readTimeout = 8000
                val loc: String?
                try { c.responseCode; loc = c.getHeaderField("Location") } finally { c.disconnect() }
                if (loc == null) break
                coordsIn(java.net.URLDecoder.decode(loc, "UTF-8"))?.let { return Place(name, "Shared from Google Maps", it) }
                u = if (loc.startsWith("http")) loc else URL(URL(u), loc).toString()
            }
        }
        return findPlace(name, near)
    }
}
