package com.mytvs.ntorq

/**
 * Turns the text of Google Maps' turn-by-turn notification into cluster data.
 *
 * Google Maps has shipped two notification layouts over the years (a custom
 * RemoteViews layout with nav_title / nav_description / nav_time, and standard
 * title / text / subText fields). We don't depend on either: the listener hands
 * us every text string it can find, and we pick fields out by their shape:
 *   - the field containing a duration ("31 min", "1 hr 5 min") is the trip
 *     status line -> minutes remaining + total distance remaining;
 *   - the first distance in any OTHER field is the distance to the next turn;
 *   - the field with turn words ("Turn left onto …") is the instruction.
 * Pure Kotlin, no Android dependencies, so it is unit-tested on CI.
 */
enum class Maneuver(val mmiId: Int) {
    LEFT(0), SHARP_LEFT(1), SLIGHT_LEFT(2),
    RIGHT(3), SHARP_RIGHT(4), SLIGHT_RIGHT(5),
    UTURN(6), STRAIGHT(7), ARRIVE(9),
    KEEP_LEFT(15), KEEP_RIGHT(16),
    ROUNDABOUT(65),
}

data class NavInfo(
    val instruction: String,
    val maneuver: Maneuver,
    val roundaboutExit: Int,
    val distNextM: Int?,
    val remainingMin: Int?,
    val remainingM: Int?,
)

object MapsParser {
    private val DIST = Regex("""(\d+(?:[.,]\d+)?)\s*(km|m|mi|ft|yd)\b""", RegexOption.IGNORE_CASE)
    private val DUR_H = Regex("""(\d+)\s*(?:h|hr|hrs|hour|hours)\b""", RegexOption.IGNORE_CASE)
    private val DUR_M = Regex("""(\d+)\s*(?:min|mins|minute|minutes)\b""", RegexOption.IGNORE_CASE)
    private val SPLIT = Regex("""\s+[·•|–—-]\s+""")
    private val ARRIVE_AT = Regex("""\b(?:arrive|arriving|arrival|eta)\b\D{0,6}(\d{1,2})[:.](\d{2})\s*([ap])?\.?\s*m?\b""", RegexOption.IGNORE_CASE)
    /** Notification chrome that is not route information. */
    private val JUNK = Regex("""^(exit navigation|maps|google maps|•|·|-|\d{1,2}[:.]\d{2}\s*([ap]\.?m\.?)?)$""", RegexOption.IGNORE_CASE)
    private val EXIT_NUM = Regex("""(\d+)\s*(?:st|nd|rd|th)\s+exit""", RegexOption.IGNORE_CASE)
    private val ORDINALS = listOf("first", "second", "third", "fourth", "fifth", "sixth", "seventh", "eighth")
    private val TURN_WORDS = Regex(
        """\b(turn|left|right|slight|sharp|keep|stay|bear|u-?turn|roundabout|rotary|continue|head|merge|straight|destination|arrive|exit|ramp|fork)\b""",
        RegexOption.IGNORE_CASE,
    )

    /** "300 m" -> 300, "1.2 km" -> 1200, "0,4 mi" -> 644. Null if no distance. */
    fun distanceMeters(s: String): Int? {
        val m = DIST.find(s) ?: return null
        val v = m.groupValues[1].replace(',', '.').toDoubleOrNull() ?: return null
        val metres = when (m.groupValues[2].lowercase()) {
            "km" -> v * 1000.0
            "m" -> v
            "mi" -> v * 1609.344
            "ft" -> v * 0.3048
            "yd" -> v * 0.9144
            else -> return null
        }
        return Math.round(metres).toInt()
    }

    /** "31 min" -> 31, "1 hr 5 min" -> 65, "2 h" -> 120. Null if no duration. */
    fun durationMinutes(s: String): Int? {
        val h = DUR_H.find(s)?.groupValues?.get(1)?.toIntOrNull()
        val m = DUR_M.find(s)?.groupValues?.get(1)?.toIntOrNull()
        if (h == null && m == null) return null
        return (h ?: 0) * 60 + (m ?: 0)
    }

    /**
     * "Arrive 3:20 pm" -> minutes from [nowMinOfDay] until then (wraps past midnight).
     * Newer Google Maps shows only the arrival clock time, not "31 min".
     */
    fun minutesUntilArrival(s: String, nowMinOfDay: Int): Int? {
        val m = ARRIVE_AT.find(s) ?: return null
        var h = m.groupValues[1].toIntOrNull() ?: return null
        val min = m.groupValues[2].toIntOrNull() ?: return null
        when (m.groupValues[3].lowercase()) {
            "a" -> if (h == 12) h = 0
            "p" -> if (h != 12) h += 12
        }
        if (h !in 0..23 || min !in 0..59) return null
        return ((h * 60 + min) - nowMinOfDay + 1440) % 1440
    }

    private fun nowMinOfDay(): Int {
        val c = java.util.Calendar.getInstance()
        return c.get(java.util.Calendar.HOUR_OF_DAY) * 60 + c.get(java.util.Calendar.MINUTE)
    }

    private fun firstIndex(text: String, vararg words: String): Int =
        words.map { Regex("""\b${Regex.escape(it)}\b""").find(text)?.range?.first ?: Int.MAX_VALUE }.minOrNull() ?: Int.MAX_VALUE

    /** Which way the instruction points; checks the most specific phrases first. */
    fun maneuver(instruction: String): Pair<Maneuver, Int> {
        val t = instruction.lowercase()
        if (Regex("""\bu[- ]?turn\b""").containsMatchIn(t)) return Maneuver.UTURN to 0
        if (Regex("""\b(roundabout|rotary|traffic circle)\b""").containsMatchIn(t)) {
            val n = EXIT_NUM.find(t)?.groupValues?.get(1)?.toIntOrNull()
                ?: ORDINALS.indexOfFirst { Regex("""\b$it\s+exit\b""").containsMatchIn(t) }.let { if (it >= 0) it + 1 else 0 }
            return Maneuver.ROUNDABOUT to n
        }
        if (Regex("""\b(destination|arrive|arriving|arrived)\b""").containsMatchIn(t)) return Maneuver.ARRIVE to 0

        fun side(leftM: Maneuver, rightM: Maneuver, vararg prefixes: String): Maneuver? {
            val l = prefixes.map { firstIndex(t, "$it left") }.minOrNull() ?: Int.MAX_VALUE
            val r = prefixes.map { firstIndex(t, "$it right") }.minOrNull() ?: Int.MAX_VALUE
            if (l == Int.MAX_VALUE && r == Int.MAX_VALUE) return null
            return if (l <= r) leftM else rightM
        }
        side(Maneuver.SHARP_LEFT, Maneuver.SHARP_RIGHT, "sharp")?.let { return it to 0 }
        side(Maneuver.SLIGHT_LEFT, Maneuver.SLIGHT_RIGHT, "slight", "bear")?.let { return it to 0 }
        side(Maneuver.KEEP_LEFT, Maneuver.KEEP_RIGHT, "keep", "stay")?.let { return it to 0 }
        if (Regex("""\b(fork|ramp|exit)\b""").containsMatchIn(t)) {
            side(Maneuver.KEEP_LEFT, Maneuver.KEEP_RIGHT, "the", "on the", "to the")?.let { return it to 0 }
        }
        // Plain turns: whichever of left/right is mentioned first ("Turn left onto Right St" = left).
        val l = firstIndex(t, "left")
        val r = firstIndex(t, "right")
        if (l != Int.MAX_VALUE || r != Int.MAX_VALUE) return (if (l <= r) Maneuver.LEFT else Maneuver.RIGHT) to 0
        return Maneuver.STRAIGHT to 0
    }

    /**
     * Cluster pictogram for vehicle type 38 (U577 Premium), exactly as the
     * official app computes it: parseInt(String(table[mmiId]), 16), default 64.
     * Table from BleNavigationDataSourceImp.getClusterPictogramMappingForU577PremiumMappls.
     */
    private val U577_PREMIUM = mapOf(
        0 to 0, 1 to 1, 2 to 2, 3 to 10, 4 to 11, 5 to 12, 6 to 9, 7 to 57, 8 to 22, 9 to 22,
        10 to 22, 11 to 0, 12 to 10, 13 to 0, 14 to 10, 15 to 7, 16 to 17, 17 to 1, 18 to 11,
        19 to 2, 20 to 12, 21 to 57, 22 to 0, 23 to 0, 24 to 10, 25 to 10, 36 to 23, 41 to 19,
        50 to 57, 51 to 57, 52 to 57, 53 to 57, 54 to 57, 55 to 57, 56 to 57, 57 to 57,
        65 to 27, 66 to 28, 67 to 29, 68 to 48, 69 to 31, 70 to 32, 71 to 33, 72 to 61,
        73 to 7, 74 to 7, 75 to 17,
    )

    fun picto(m: Maneuver, roundaboutExit: Int = 0): Int {
        val id = if (m == Maneuver.ROUNDABOUT) 64 + roundaboutExit.coerceIn(1, 8) else m.mmiId
        val v = U577_PREMIUM[id] ?: 64
        return v.toString().toInt(16)
    }

    /** Parse the notification's text fields (in notification order: title first). */
    fun parse(fields: List<String>, nowMinOfDay: Int = nowMinOfDay()): NavInfo? {
        val clean = fields.map { it.trim() }.filter { it.isNotEmpty() && !JUNK.matches(it) }.distinct()
        if (clean.isEmpty()) return null
        if (clean.any { it.contains("starting navigation", ignoreCase = true) }) return null

        val status = clean.firstOrNull { durationMinutes(it) != null }
        val arrival = clean.firstOrNull { it != status && ARRIVE_AT.containsMatchIn(it) }
        val others = clean.filter { it != status && it != arrival }

        var distNext: Int? = null
        var instruction: String? = null
        for (f in others) {
            val parts = f.split(SPLIT).map { it.trim() }.filter { it.isNotEmpty() }
            for (p in parts) {
                val isPureDistance = DIST.matchEntire(p.trim()) != null
                if (distNext == null && distanceMeters(p) != null && (isPureDistance || TURN_WORDS.containsMatchIn(p))) {
                    distNext = distanceMeters(p)
                }
                if (instruction == null && !isPureDistance && TURN_WORDS.containsMatchIn(p)) {
                    instruction = p
                }
            }
        }
        if (instruction == null) {
            instruction = others.filter { DIST.matchEntire(it) == null }.maxByOrNull { it.length }
        }
        if (distNext == null && instruction == null) return null

        val instr = instruction ?: ""
        val (man, exit) = maneuver(instr)
        return NavInfo(
            instruction = instr,
            maneuver = man,
            roundaboutExit = exit,
            distNextM = distNext,
            remainingMin = status?.let { durationMinutes(it) } ?: arrival?.let { minutesUntilArrival(it, nowMinOfDay) },
            remainingM = status?.let { distanceMeters(it) },
        )
    }
}
