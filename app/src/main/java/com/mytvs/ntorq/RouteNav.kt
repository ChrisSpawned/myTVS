package com.mytvs.ntorq

import android.annotation.SuppressLint
import android.content.Context
import android.content.Intent
import android.location.Location
import android.location.LocationListener
import android.location.LocationManager
import android.net.Uri
import android.os.Handler
import android.os.Looper
import org.json.JSONArray
import org.json.JSONObject
import java.util.concurrent.Executors

/**
 * The app's own copy of the route, followed with phone GPS once a second.
 *
 * Google Maps is still the navigator you see and hear; this gives NavBridge an
 * exact, continuously updated distance to each turn and the total remaining,
 * which Google Maps' notification only provides irregularly (or not at all).
 * It also works alone ("cluster only") when Google Maps isn't used.
 */
object RouteNav {
    private val main = Handler(Looper.getMainLooper())
    private val io = Executors.newSingleThreadExecutor()
    private lateinit var app: Context

    @Volatile var destination: Place? = null
        private set
    @Volatile var tracker: RouteTracker? = null
        private set
    @Volatile var state: TrackState? = null
        private set
    @Volatile var lastFix: Location? = null
        private set
    @Volatile var active = false
        private set
    /** True when started together with Google Maps (Maps ending also ends us). */
    @Volatile var withGoogleMaps = false
        private set
    @Volatile var note = ""
        private set

    private var lastFixAt = 0L
    private var offCount = 0
    private var lastRouteAt = 0L
    private var routing = false
    var onChange: (() -> Unit)? = null

    fun init(ctx: Context) { if (!::app.isInitialized) app = ctx.applicationContext }

    /** GPS fix younger than 5 s and on the route. */
    val isTracking: Boolean
        get() = active && tracker != null && System.currentTimeMillis() - lastFixAt < 5000 &&
            (state?.offRouteM ?: 999.0) < 45

    val lastPos: LatLng? get() = lastFix?.let { LatLng(it.latitude, it.longitude) } ?: lastKnown()

    // ------------------------------------------------------------- start/stop

    fun start(place: Place, launchMaps: Boolean) {
        destination = place
        withGoogleMaps = launchMaps
        active = true
        tracker = null; state = null; offCount = 0
        addRecent(place)
        startGps()
        note = "Getting route…"
        lastPos?.let { fetchRoute(it) }
        FrameLog.add("ROUTE", "start → ${place.name} (${place.pos.lat}, ${place.pos.lng}) maps=$launchMaps")
        if (launchMaps) launchGoogleMaps(place)
        NavBridge.onRouteChanged()
        changed()
    }

    fun stop(reason: String) {
        if (!active) return
        active = false
        stopGps()
        tracker = null; state = null; destination = null
        note = ""
        FrameLog.add("ROUTE", "stop: $reason")
        if (NavBridge.gatt == null) RideService.stop(app)
        NavBridge.onRouteChanged()
        changed()
    }

    fun launchGoogleMaps(place: Place) {
        // mode=l = two-wheeler routing (India); Maps falls back to driving elsewhere.
        val uri = Uri.parse("google.navigation:q=%.6f,%.6f&mode=l".format(java.util.Locale.US, place.pos.lat, place.pos.lng))
        val i = Intent(Intent.ACTION_VIEW, uri).setPackage("com.google.android.apps.maps")
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        try { app.startActivity(i) } catch (e: Exception) {
            app.startActivity(Intent(Intent.ACTION_VIEW, uri).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
        }
    }

    // ------------------------------------------------------------------ GPS

    private val gps = object : LocationListener {
        override fun onLocationChanged(l: Location) = onFix(l)
        @Deprecated("") override fun onStatusChanged(p: String?, s: Int, b: android.os.Bundle?) {}
        override fun onProviderEnabled(p: String) {}
        override fun onProviderDisabled(p: String) { note = "Turn on location (GPS)" ; changed() }
    }

    @SuppressLint("MissingPermission")
    private fun startGps() {
        val lm = app.getSystemService(Context.LOCATION_SERVICE) as LocationManager
        try {
            lm.requestLocationUpdates(LocationManager.GPS_PROVIDER, 1000L, 0f, gps, Looper.getMainLooper())
        } catch (e: Exception) {
            note = "Location permission needed"
            FrameLog.add("ROUTE", "GPS not started: ${e.message}")
        }
        try {
            lm.requestLocationUpdates(LocationManager.NETWORK_PROVIDER, 3000L, 0f, gps, Looper.getMainLooper())
        } catch (_: Exception) { }
    }

    private fun stopGps() {
        try { (app.getSystemService(Context.LOCATION_SERVICE) as LocationManager).removeUpdates(gps) } catch (_: Exception) { }
    }

    @SuppressLint("MissingPermission")
    fun lastKnown(): LatLng? = try {
        val lm = app.getSystemService(Context.LOCATION_SERVICE) as LocationManager
        listOf(LocationManager.GPS_PROVIDER, LocationManager.NETWORK_PROVIDER, LocationManager.PASSIVE_PROVIDER)
            .mapNotNull { runCatching { lm.getLastKnownLocation(it) }.getOrNull() }
            .maxByOrNull { it.time }?.let { LatLng(it.latitude, it.longitude) }
    } catch (_: Exception) { null }

    private fun onFix(l: Location) {
        // Ignore coarse network fixes once GPS is delivering.
        if (l.provider == LocationManager.NETWORK_PROVIDER && System.currentTimeMillis() - lastFixAt < 4000) return
        if (l.hasAccuracy() && l.accuracy > 60) return
        lastFix = l
        lastFixAt = System.currentTimeMillis()
        val p = LatLng(l.latitude, l.longitude)
        val t = tracker
        if (t == null) {
            if (!routing) fetchRoute(p)
            changed(); return
        }
        val s = t.update(p)
        state = s
        // Off the route for a few fixes in a row → new route from here.
        offCount = if (s.offRouteM > 45) offCount + 1 else 0
        if (offCount >= 4 && System.currentTimeMillis() - lastRouteAt > 15000) {
            note = "Off route — rerouting…"
            FrameLog.add("ROUTE", "off route by ${s.offRouteM.toInt()} m — rerouting")
            fetchRoute(p)
        }
        // Arrived (cluster-only mode ends itself; with Google Maps, Maps ends it).
        val dest = destination
        if (dest != null && Geo.dist(p, dest.pos) < 35 && s.remainingM < 60) {
            note = "Arrived"
            if (!withGoogleMaps) main.postDelayed({ stop("arrived") }, 8000)
        }
        changed()
    }

    private fun fetchRoute(from: LatLng) {
        val dest = destination ?: return
        if (routing) return
        routing = true
        lastRouteAt = System.currentTimeMillis()
        io.execute {
            val r = runCatching { RouteApi.route(from, dest.pos) }
            main.post {
                routing = false
                if (!active) return@post
                r.onSuccess {
                    tracker = RouteTracker(it)
                    offCount = 0
                    note = "Route %.1f km · %d turns".format(it.lengthM / 1000.0, it.steps.size)
                    FrameLog.add("ROUTE", note)
                    lastFix?.let { f -> state = tracker?.update(LatLng(f.latitude, f.longitude)) }
                    NavBridge.onRouteChanged()
                }.onFailure {
                    note = "Couldn't get route (${it.message}) — using Google Maps' distances"
                    FrameLog.add("ROUTE", note)
                }
                changed()
            }
        }
    }

    private fun changed() = main.post { onChange?.invoke() }

    // ------------------------------------------------------------- recents

    private val prefs get() = app.getSharedPreferences("routes", Context.MODE_PRIVATE)

    fun recents(): List<Place> = try {
        val a = JSONArray(prefs.getString("recent", "[]"))
        (0 until a.length()).map { a.getJSONObject(it) }.map {
            Place(it.getString("n"), it.optString("d"), LatLng(it.getDouble("la"), it.getDouble("lo")))
        }
    } catch (_: Exception) { emptyList() }

    private fun addRecent(p: Place) {
        val list = (listOf(p) + recents().filter { Geo.dist(it.pos, p.pos) > 30 }).take(6)
        val a = JSONArray()
        list.forEach { a.put(JSONObject().put("n", it.name).put("d", it.detail).put("la", it.pos.lat).put("lo", it.pos.lng)) }
        prefs.edit().putString("recent", a.toString()).apply()
    }

    /** Runs [block] off the main thread and delivers the result on it. */
    fun <T> background(block: () -> T, done: (Result<T>) -> Unit) {
        io.execute {
            val r = runCatching(block)
            main.post { done(r) }
        }
    }
}
