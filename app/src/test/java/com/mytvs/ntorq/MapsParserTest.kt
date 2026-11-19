package com.mytvs.ntorq

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Test

class MapsParserTest {

    @Test fun separateFields_likeStandardNotification() {
        val n = MapsParser.parse(listOf("300 m", "Turn left onto Club Road", "31 min · 15.6 km · 3:23 PM"))
        assertNotNull(n); n!!
        assertEquals(300, n.distNextM)
        assertEquals(Maneuver.LEFT, n.maneuver)
        assertEquals(31, n.remainingMin)
        assertEquals(15600, n.remainingM)
        assertEquals(0, MapsParser.picto(n.maneuver))
    }

    @Test fun combinedTitle_distanceAndInstruction() {
        val n = MapsParser.parse(listOf("150 m · Turn right", "Kakkanad · ETA 3:25 PM", "12 min · 4.2 km · 3:25 PM"))!!
        assertEquals(150, n.distNextM)
        assertEquals(Maneuver.RIGHT, n.maneuver)
        assertEquals(12, n.remainingMin)
        assertEquals(4200, n.remainingM)
        assertEquals(16, MapsParser.picto(n.maneuver))
    }

    @Test fun hoursAndMinutes() {
        val n = MapsParser.parse(listOf("1.2 km", "Continue on NH 66", "1 hr 5 min · 52 km · 4:30 PM"))!!
        assertEquals(1200, n.distNextM)
        assertEquals(Maneuver.STRAIGHT, n.maneuver)
        assertEquals(65, n.remainingMin)
        assertEquals(52000, n.remainingM)
    }

    @Test fun minutesAreNotMetres() {
        assertNull(MapsParser.distanceMeters("15 min"))
        assertEquals(15, MapsParser.durationMinutes("15 min"))
    }

    @Test fun firstDirectionWordWins() {
        assertEquals(Maneuver.LEFT, MapsParser.maneuver("Turn left onto Right Street").first)
    }

    @Test fun slightSharpKeepUturn() {
        assertEquals(Maneuver.SLIGHT_RIGHT, MapsParser.maneuver("Slight right toward NH 66").first)
        assertEquals(Maneuver.SHARP_LEFT, MapsParser.maneuver("Sharp left onto MG Road").first)
        assertEquals(Maneuver.KEEP_LEFT, MapsParser.maneuver("Keep left at the fork").first)
        assertEquals(Maneuver.UTURN, MapsParser.maneuver("Make a U-turn").first)
    }

    @Test fun roundaboutExit() {
        val (m, exit) = MapsParser.maneuver("At the roundabout, take the 2nd exit onto MG Road")
        assertEquals(Maneuver.ROUNDABOUT, m)
        assertEquals(2, exit)
        assertEquals(0x28, MapsParser.picto(m, exit))
        assertEquals(3, MapsParser.maneuver("At the roundabout, take the third exit").second)
    }

    @Test fun destinationIsNotATurn() {
        assertEquals(Maneuver.ARRIVE, MapsParser.maneuver("Destination will be on the right").first)
        assertEquals(0x22, MapsParser.picto(Maneuver.ARRIVE))
    }

    @Test fun nothingUsable() {
        assertNull(MapsParser.parse(listOf("", "  ")))
    }

    // Real notification captured on the user's phone (Google Maps, 2026-11-18 15:18).
    @Test fun realCapture_headSouth_arriveTime() {
        val now = 15 * 60 + 18
        val n = MapsParser.parse(listOf("Head south on Zveda Villas Rd", "Arrive 3:20 pm", "•", "Exit navigation"), now)!!
        assertEquals("Head south on Zveda Villas Rd", n.instruction)
        assertEquals(Maneuver.STRAIGHT, n.maneuver)
        assertEquals(2, n.remainingMin)
        assertNull(n.distNextM)
    }

    @Test fun realCapture_startingNavigationIsIgnored() {
        assertNull(MapsParser.parse(listOf("Starting navigation…", "•", "3:18 pm", "Maps"), 15 * 60 + 18))
    }

    @Test fun arrivalTimeFormats() {
        assertEquals(62, MapsParser.minutesUntilArrival("Arrive 4:20 pm", 15 * 60 + 18))
        assertEquals(10, MapsParser.minutesUntilArrival("ETA 00:05", 23 * 60 + 55))
        assertEquals(30, MapsParser.minutesUntilArrival("Arrive 12:30 am", 0))
    }

    @Test fun turnWithDistanceAndArrival() {
        val n = MapsParser.parse(listOf("200 m", "Turn right onto Club Road", "Arrive 3:30 pm", "Exit navigation"), 15 * 60)!!
        assertEquals(200, n.distNextM)
        assertEquals(Maneuver.RIGHT, n.maneuver)
        assertEquals(30, n.remainingMin)
    }

    // Real captures from the user's ride (2026-11-18 15:32–15:35).
    @Test fun realCapture_distanceAndPlaceName() {
        val n = MapsParser.parse(listOf("190 m · V B Mist", "Arrive 3:33 pm"), 15 * 60 + 32)!!
        assertEquals(190, n.distNextM)
        assertEquals("V B Mist", n.instruction)
        assertEquals(1, n.remainingMin)
    }

    @Test fun realCapture_turnRightToward() {
        val n = MapsParser.parse(listOf("290 m · Turn right toward Zveda Villas Rd", "Arrive 3:35 pm"), 15 * 60 + 33)!!
        assertEquals(290, n.distNextM)
        assertEquals(Maneuver.RIGHT, n.maneuver)
        assertEquals(16, MapsParser.picto(n.maneuver))
    }

    @Test fun realCapture_arriving() {
        val n = MapsParser.parse(listOf("Arriving", "at V B Mist", "•", "3:33 pm"), 15 * 60 + 33)!!
        assertEquals(Maneuver.ARRIVE, n.maneuver)
    }

    @Test fun reroutingIsIgnored() {
        assertNull(MapsParser.parse(listOf("Rerouting...", "Arrive ", "•", "Exit navigation"), 13 * 60 + 50))
    }
}
