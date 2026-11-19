package com.mytvs.ntorq

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder

/**
 * Foreground service that keeps the app process (and so the BLE link held by
 * NavBridge.gatt) alive while Google Maps is in front. It does no work itself.
 */
class RideService : Service() {

    companion object {
        private const val CHANNEL = "ride"
        private const val ID = 1

        fun start(ctx: Context) {
            val i = Intent(ctx, RideService::class.java)
            if (Build.VERSION.SDK_INT >= 26) ctx.startForegroundService(i) else ctx.startService(i)
        }

        fun stop(ctx: Context) {
            ctx.stopService(Intent(ctx, RideService::class.java))
        }
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val nm = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        if (Build.VERSION.SDK_INT >= 26) {
            nm.createNotificationChannel(
                NotificationChannel(CHANNEL, "Scooter link", NotificationManager.IMPORTANCE_LOW)
            )
        }
        val open = PendingIntent.getActivity(
            this, 0, Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
        val n: Notification = (if (Build.VERSION.SDK_INT >= 26) Notification.Builder(this, CHANNEL)
        else @Suppress("DEPRECATION") Notification.Builder(this))
            .setSmallIcon(android.R.drawable.stat_sys_data_bluetooth)
            .setContentTitle(if (RouteNav.active) "Riding to ${RouteNav.destination?.name ?: "destination"}" else "Connected to Ntorq")
            .setContentText("Turns are sent to the cluster")
            .setContentIntent(open)
            .setOngoing(true)
            .build()
        try {
            if (Build.VERSION.SDK_INT >= 29) {
                // Location type only when permission is granted (Android 14 rejects it otherwise).
                val loc = checkSelfPermission(android.Manifest.permission.ACCESS_FINE_LOCATION) ==
                    android.content.pm.PackageManager.PERMISSION_GRANTED
                val bt = Build.VERSION.SDK_INT < 31 || checkSelfPermission(android.Manifest.permission.BLUETOOTH_CONNECT) ==
                    android.content.pm.PackageManager.PERMISSION_GRANTED
                var type = 0
                if (bt) type = type or ServiceInfo.FOREGROUND_SERVICE_TYPE_CONNECTED_DEVICE
                if (loc) type = type or ServiceInfo.FOREGROUND_SERVICE_TYPE_LOCATION
                if (type == 0) { stopSelf(); return START_NOT_STICKY }
                startForeground(ID, n, type)
            } else {
                startForeground(ID, n)
            }
        } catch (e: Exception) {
            FrameLog.add("BLE", "foreground service not started: ${e.message}")
            stopSelf()
        }
        return START_STICKY
    }
}
