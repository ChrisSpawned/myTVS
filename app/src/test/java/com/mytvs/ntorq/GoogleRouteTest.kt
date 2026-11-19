package com.mytvs.ntorq

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class GoogleRouteTest {
    private fun encode(pts: List<LatLng>, precision: Int = 5): String {
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

    // L-shaped route in Kochi: ~1 km north, then right (east) ~500 m.
    private val a = LatLng(9.97000, 76.28000)
    private val b = LatLng(9.97900, 76.28000)
    private val c = LatLng(9.97900, 76.28456)

    private fun routeJson(): String {
        val geom = encode(listOf(a, LatLng(9.97450, 76.28000), b, LatLng(9.97900, 76.28230), c)).replace("\\", "\\\\")
        return """{"routes":[{"distanceMeters":1500,"duration":"180s","polyline":{"encodedPolyline":"$geom"},
          "legs":[{"steps":[
            {"distanceMeters":1000,"navigationInstruction":{"maneuver":"DEPART","instructions":"Head north on MG Road"}},
            {"distanceMeters":500,"navigationInstruction":{"maneuver":"TURN_RIGHT","instructions":"Turn right onto Club Rd\nDestination will be on the left"}}
          ]}]}]}"""
    }

    @Test fun polylineGoogleExample() {
        val g = Geo.decodePolyline("_p~iF~ps|U_ulLnnqC_mqNvxq`@", 5)
        assertEquals(38.5, g[0].lat, 1e-5); assertEquals(-120.2, g[0].lng, 1e-5)
        assertEquals(43.252, g[2].lat, 1e-5)
    }

    @Test fun parsesGoogleRouteAndTracks() {
        val r = GoogleApi.parseRoute(routeJson())
        assertEquals(2, r.steps.size)                       // DEPART dropped, ARRIVE added
        assertEquals(Maneuver.RIGHT, r.steps[0].maneuver)
        assertEquals("Turn right onto Club Rd", r.steps[0].instruction)
        assertEquals(1000.0, r.steps[0].alongM, 20.0)
        assertEquals(Maneuver.ARRIVE, r.steps[1].maneuver)
        assertEquals(180.0, r.durationS, 0.1)

        val t = RouteTracker(r)
        val s1 = t.update(LatLng(9.97450, 76.28001))
        assertEquals(500.0, s1.distToNextM.toDouble(), 20.0)
        assertEquals(Maneuver.RIGHT, s1.next!!.maneuver)
        assertTrue(s1.offRouteM < 5)
        val s2 = t.update(LatLng(9.97900, 76.28230))
        assertEquals(Maneuver.ARRIVE, s2.next!!.maneuver)
        assertEquals(250.0, s2.distToNextM.toDouble(), 20.0)
        assertTrue(t.update(LatLng(9.97900, 76.29000)).offRouteM > 300)
    }

    @Test fun maneuvers() {
        assertNull(GoogleApi.mapManeuver("DEPART", "Head north"))
        assertNull(GoogleApi.mapManeuver("NAME_CHANGE", "Continue onto NH66"))
        assertEquals(Maneuver.KEEP_LEFT, GoogleApi.mapManeuver("FORK_LEFT", "Keep left")!!.maneuver)
        assertEquals(Maneuver.UTURN, GoogleApi.mapManeuver("UTURN_RIGHT", "Make a U-turn")!!.maneuver)
        val rb = GoogleApi.mapManeuver("ROUNDABOUT_LEFT", "At the roundabout, take the 2nd exit onto Seaport - Airport Rd")!!
        assertEquals(Maneuver.ROUNDABOUT, rb.maneuver); assertEquals(2, rb.roundaboutExit)
        assertEquals(3, GoogleApi.mapManeuver("ROUNDABOUT_LEFT", "At the roundabout, take the third exit")!!.roundaboutExit)
    }

    @Test fun autocomplete() {
        val s = GoogleApi.parseAutocomplete("""{"suggestions":[{"placePrediction":{"placeId":"ChIJabc",
            "text":{"text":"Lulu Mall, Edappally, Kochi"},
            "structuredFormat":{"mainText":{"text":"Lulu Mall"},"secondaryText":{"text":"Edappally, Kochi, Kerala"}}}},
            {"queryPrediction":{"text":{"text":"lulu"}}}]}""")
        assertEquals(1, s.size)
        assertEquals("ChIJabc", s[0].placeId)
        assertEquals("Lulu Mall", s[0].main)
        assertEquals("Edappally, Kochi, Kerala", s[0].secondary)
        assertEquals(0, GoogleApi.parseAutocomplete("{}").size)
    }

    @Test fun coordsFromGoogleLinks() {
        assertEquals(10.0261, GoogleApi.coordsIn("https://www.google.com/maps/place/Lulu/@10.0261,76.3083,17z")!!.lat, 1e-6)
        assertEquals(76.30801, GoogleApi.coordsIn("https://www.google.com/maps/place/X/data=!3d10.02615!4d76.30801")!!.lng, 1e-6)
        assertNull(GoogleApi.coordsIn("Lulu Mall\nhttps://maps.app.goo.gl/abc123"))
    }

    @Test fun matchesGoogleTurn() {
        val t = RouteTracker(GoogleApi.parseRoute(routeJson()))
        t.update(LatLng(9.97520, 76.28000))
        assertTrue(t.upcoming(700.0).any { RouteTracker.family(it.maneuver) == 2 })
        assertTrue(t.upcoming(700.0).none { RouteTracker.family(it.maneuver) == 1 })
    }
}
