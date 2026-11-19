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

    @Volatile var lastFixAt = 0L
        private set
    private var offCount = 0
    private var lastRouteAt = 0L
    private var routing = false
    var onChange: (() -> Unit)? = null

    fun init(ctx: Context) {
        if (!::app.isInitialized) app = ctx.applicationContext
        FrameLog.init(app)
        GoogleApi.apiKey = app.getSharedPreferences("google", Context.MODE_PRIVATE).getString("key", "") ?: ""
        GoogleApi.androidPackage = app.packageName
        GoogleApi.androidCertSha1 = certSha1(app)
    }

    /** SHA-1 of the app's signing certificate, uppercase hex without colons. */
    @Suppress("DEPRECATION")
    fun certSha1(ctx: Context, colons: Boolean = false): String = try {
        val pm = ctx.packageManager
        val sig = if (android.os.Build.VERSION.SDK_INT >= 28) {
            pm.getPackageInfo(ctx.packageName, android.content.pm.PackageManager.GET_SIGNING_CERTIFICATES)
                .signingInfo?.apkContentsSigners?.firstOrNull()
        } else {
            pm.getPackageInfo(ctx.packageName, android.content.pm.PackageManager.GET_SIGNATURES).signatures?.firstOrNull()
        }
        val d = java.security.MessageDigest.getInstance("SHA-1").digest(sig!!.toByteArray())
        d.joinToString(if (colons) ":" else "") { "%02X".format(it) }
    } catch (_: Exception) { "" }

    fun saveApiKey(key: String) {
        GoogleApi.apiKey = key.trim()
        FrameLog.add("GOOGLE", "API key saved (${key.trim().take(6)}…, ${key.trim().length} chars)")
        app.getSharedPreferences("google", Context.MODE_PRIVATE).edit().putString("key", key.trim()).apply()
    }

    /** GPS fix younger than 5 s and on the route. */
    /**
     * On our route with a recent fix. Short GPS gaps (up to 30 s) are bridged by
     * NavBridge moving along the route at the scooter's own speed, instead of
     * falling back to Google Maps' slow notification.
     */
    val isTracking: Boolean
        get() = active && tracker != null && System.currentTimeMillis() - lastFixAt < 30000 &&
            (state?.offRouteM ?: 999.0) < 40

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

    private var fused: com.google.android.gms.location.FusedLocationProviderClient? = null
    private val fusedCb = object : com.google.android.gms.location.LocationCallback() {
        override fun onLocationResult(r: com.google.android.gms.location.LocationResult) {
            r.lastLocation?.let { onFix(it) }
        }
    }

    /** Same location engine Google Maps uses (GPS + sensors), 1 fix per second; raw GPS as fallback. */
    @SuppressLint("MissingPermission")
    private fun startGps() {
        fixCount = 0; statsAt = System.currentTimeMillis()
        try {
            val ok = com.google.android.gms.common.GoogleApiAvailability.getInstance()
                .isGooglePlayServicesAvailable(app) == com.google.android.gms.common.ConnectionResult.SUCCESS
            if (ok) {
                val req = com.google.android.gms.location.LocationRequest.Builder(
                    com.google.android.gms.location.Priority.PRIORITY_HIGH_ACCURACY, 1000L)
                    .setMinUpdateIntervalMillis(500L)
                    .setWaitForAccurateLocation(false)
                    .build()
                fused = com.google.android.gms.location.LocationServices.getFusedLocationProviderClient(app).also {
                    it.requestLocationUpdates(req, fusedCb, Looper.getMainLooper())
                }
                FrameLog.add("ROUTE", "location: fused provider, 1 Hz high accuracy")
                return
            }
        } catch (e: Exception) {
            FrameLog.add("ROUTE", "fused location unavailable: ${e.message}")
        }
        val lm = app.getSystemService(Context.LOCATION_SERVICE) as LocationManager
        try {
            lm.requestLocationUpdates(LocationManager.GPS_PROVIDER, 1000L, 0f, gps, Looper.getMainLooper())
            FrameLog.add("ROUTE", "location: raw GPS provider")
        } catch (e: Exception) {
            note = "Location permission needed"
            FrameLog.add("ROUTE", "GPS not started: ${e.message}")
        }
    }

    private fun stopGps() {
        try { fused?.removeLocationUpdates(fusedCb) } catch (_: Exception) { }
        fused = null
        try { (app.getSystemService(Context.LOCATION_SERVICE) as LocationManager).removeUpdates(gps) } catch (_: Exception) { }
    }

    private var fixCount = 0
    private var accSum = 0.0
    private var statsAt = 0L

    @SuppressLint("MissingPermission")
    fun lastKnown(): LatLng? = try {
        val lm = app.getSystemService(Context.LOCATION_SERVICE) as LocationManager
        listOf(LocationManager.GPS_PROVIDER, LocationManager.NETWORK_PROVIDER, LocationManager.PASSIVE_PROVIDER)
            .mapNotNull { runCatching { lm.getLastKnownLocation(it) }.getOrNull() }
            .maxByOrNull { it.time }?.let { LatLng(it.latitude, it.longitude) }
    } catch (_: Exception) { null }

    private fun onFix(l: Location) {
        val nowMs = System.currentTimeMillis()
        if (nowMs - statsAt >= 15000) {
            FrameLog.add("GPS", "%d fixes/15 s · avg ±%.0f m · off route %s".format(
                fixCount, if (fixCount > 0) accSum / fixCount else 0.0,
                state?.offRouteM?.let { "%.0f m".format(it) } ?: "-"))
            fixCount = 0; accSum = 0.0; statsAt = nowMs
        }
        if (l.hasAccuracy() && l.accuracy > 50) return
        fixCount++; accSum += if (l.hasAccuracy()) l.accuracy.toDouble() else 0.0
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
        offCount = if (s.offRouteM > 40) offCount + 1 else 0
        if (offCount >= 3 && System.currentTimeMillis() - lastRouteAt > 10000) {
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

    /** Fetch a fresh Google route from where we are (Maps rerouted, or our route disagrees). */
    fun reroute(reason: String) {
        if (!active || routing || System.currentTimeMillis() - lastRouteAt < 10000) return
        val p = lastFix?.let { LatLng(it.latitude, it.longitude) } ?: return
        note = "Rerouting…"
        FrameLog.add("ROUTE", "reroute: $reason")
        fetchRoute(p)
    }

    private fun fetchRoute(from: LatLng) {
        val dest = destination ?: return
        if (routing) return
        routing = true
        lastRouteAt = System.currentTimeMillis()
        io.execute {
            val heading = lastFix?.takeIf { it.hasBearing() && it.hasSpeed() && it.speed > 2f }?.bearing
            FrameLog.add("ROUTE", "Google route request from %.5f,%.5f".format(from.lat, from.lng))
            val r = runCatching { GoogleApi.route(from, dest.pos, heading) }
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
                    note = "Couldn't get Google route (${it.message}) — using Maps' distances"
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
