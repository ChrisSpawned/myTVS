package com.mytvs.ntorq

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class RouteTest {
    private fun encode(pts: List<LatLng>, precision: Int = 6): String {
        val f = Math.pow(10.0, precision.toDouble())
        val sb = StringBuilder()
        var pLat = 0L; var pLng = 0L
        fun enc(v: Long) {
            var x = if (v < 0) (v shl 1).inv() else v shl 1
            while (x >= 0x20) { sb.append(((0x20 or (x and 0x1f).toInt()) + 63).toChar()); x = x shr 5 }
            sb.append((x + 63).toInt().toChar())
        }
        for (p in pts) {
            val la = Math.round(p.lat * f); val lo = Math.round(p.lng * f)
            enc(la - pLat); enc(lo - pLng); pLat = la; pLng = lo
        }
        return sb.toString()
    }

    // An L-shaped route in Kochi: ~1 km north, then right (east) ~500 m.
    private val a = LatLng(9.9700, 76.2800)
    private val b = LatLng(9.9790, 76.2800)
    private val c = LatLng(9.9790, 76.2846)

    private fun osrmJson(): String {
        val geom = encode(listOf(a, LatLng(9.9745, 76.2800), b, LatLng(9.9790, 76.2823), c)).replace("\\", "\\\\")
        return """{"code":"Ok","routes":[{"geometry":"$geom","duration":180.0,"distance":1500,
          "legs":[{"steps":[
            {"name":"MG Road","maneuver":{"type":"depart","modifier":"","location":[76.28,9.97]}},
            {"name":"Club Rd","maneuver":{"type":"turn","modifier":"right","location":[76.28,9.979]}},
            {"name":"","maneuver":{"type":"arrive","modifier":"","location":[76.2846,9.979]}}
          ]}]}]}"""
    }

    @Test fun polylineRoundTrip() {
        val pts = listOf(a, b, c)
        val d = Geo.decodePolyline(encode(pts))
        assertEquals(3, d.size)
        assertEquals(b.lat, d[1].lat, 1e-6)
        assertEquals(c.lng, d[2].lng, 1e-6)
        // Google's documented example, precision 5.
        val g = Geo.decodePolyline("_p~iF~ps|U_ulLnnqC_mqNvxq`@", 5)
        assertEquals(38.5, g[0].lat, 1e-5); assertEquals(-120.2, g[0].lng, 1e-5)
        assertEquals(43.252, g[2].lat, 1e-5)
    }

    @Test fun parsesOsrmAndTracks() {
        val r = RouteApi.parseOsrm(osrmJson())
        assertEquals(2, r.steps.size)                 // depart dropped
        assertEquals(Maneuver.RIGHT, r.steps[0].maneuver)
        assertEquals("Turn right onto Club Rd", r.steps[0].instruction)
        assertEquals(1000.0, r.steps[0].alongM, 15.0)
        assertEquals(Maneuver.ARRIVE, r.steps[1].maneuver)

        val t = RouteTracker(r)
        val s1 = t.update(LatLng(9.9745, 76.28001))   // halfway up the first leg
        assertEquals(500, s1.distToNextM.toDouble().toInt(), 15)
        assertTrue(s1.offRouteM < 5)
        assertEquals(Maneuver.RIGHT, s1.next!!.maneuver)
        val s2 = t.update(LatLng(9.9790, 76.2823))    // after the turn
        assertEquals(Maneuver.ARRIVE, s2.next!!.maneuver)
        assertEquals(250, s2.distToNextM.toDouble().toInt(), 15)
        val off = t.update(LatLng(9.9790, 76.2900))   // 600 m off the end
        assertTrue(off.offRouteM > 300)
    }

    @Test fun matchesGoogleTurnToOurRoute() {
        val t = RouteTracker(RouteApi.parseOsrm(osrmJson()))
        t.update(LatLng(9.9745, 76.28))
        assertNotNull(RouteTracker.match(t, Maneuver.RIGHT, 480))     // close to 500 m
        assertNotNull(RouteTracker.match(t, Maneuver.RIGHT, null))    // Maps gave no distance
        assertNull(RouteTracker.match(t, Maneuver.LEFT, 500))         // wrong direction
        assertNull(RouteTracker.match(t, Maneuver.RIGHT, 1400))       // too far off
    }

    @Test fun osrmStepMapping() {
        assertNull(RouteApi.mapStep("new name", "straight", 0, "X"))
        assertNull(RouteApi.mapStep("continue", "straight", 0, "X"))
        assertEquals(Maneuver.KEEP_LEFT, RouteApi.mapStep("fork", "slight left", 0, "NH66")!!.maneuver)
        val rb = RouteApi.mapStep("roundabout", "right", 2, "Seaport - Airport Rd")!!
        assertEquals(Maneuver.ROUNDABOUT, rb.maneuver); assertEquals(2, rb.roundaboutExit)
        assertEquals(Maneuver.UTURN, RouteApi.mapStep("turn", "uturn", 0, "")!!.maneuver)
        assertEquals(Maneuver.SLIGHT_RIGHT, RouteApi.mapStep("turn", "slight right", 0, "")!!.maneuver)
    }

    @Test fun coordsFromGoogleLinks() {
        assertEquals(10.0261, RouteApi.coordsIn("https://www.google.com/maps/place/Lulu/@10.0261,76.3083,17z")!!.lat, 1e-6)
        assertEquals(76.30801, RouteApi.coordsIn("https://www.google.com/maps/place/X/data=!3d10.02615!4d76.30801")!!.lng, 1e-6)
        assertEquals(9.98, RouteApi.coordsIn("https://maps.google.com/?q=9.98,76.29")!!.lat, 1e-6)
        assertNull(RouteApi.coordsIn("Lulu Mall\nhttps://maps.app.goo.gl/abc123"))
    }

    @Test fun nominatim() {
        val p = RouteApi.parseNominatim("""[{"lat":"10.0270","lon":"76.3080","name":"Lulu Mall","display_name":"Lulu Mall, Edappally, Kochi, Kerala"}]""")
        assertEquals("Lulu Mall", p[0].name)
        assertEquals("Edappally, Kochi, Kerala", p[0].detail)
    }
}
