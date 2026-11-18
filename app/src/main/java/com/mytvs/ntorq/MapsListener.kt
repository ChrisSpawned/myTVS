package com.mytvs.ntorq

import android.app.Notification
import android.content.Context
import android.service.notification.NotificationListenerService
import android.service.notification.StatusBarNotification
import android.view.View
import android.view.ViewGroup
import android.widget.FrameLayout
import android.widget.TextView

/**
 * Reads Google Maps' turn-by-turn notification (needs "Notification access" for
 * this app). Only Google Maps' notifications are looked at; everything else is
 * ignored. Approach after GMapsParser (github.com/3v1n0/GMapsParser): use the
 * standard text fields, and if they hold no distance, inflate the notification's
 * custom layout and read its TextViews.
 */
class MapsListener : NotificationListenerService() {

    companion object {
        const val MAPS = "com.google.android.apps.maps"
    }

    private var navKey: String? = null
    private var lastDump = ""

    override fun onListenerConnected() {
        NavBridge.init(applicationContext)
        FrameLog.add("MAPS", "notification access active")
        try {
            activeNotifications?.forEach { onNotificationPosted(it) }
        } catch (e: Exception) { /* ignore */ }
    }

    override fun onNotificationPosted(sbn: StatusBarNotification) {
        if (sbn.packageName != MAPS) return
        val n = sbn.notification ?: return
        val isNavCandidate = n.category == "navigation" || sbn.isOngoing
        if (!isNavCandidate) return

        val texts = collectTexts(sbn)
        val dump = texts.joinToString(" | ")
        if (dump != lastDump) {
            lastDump = dump
            FrameLog.add("MAPS", "cat=${n.category} ongoing=${sbn.isOngoing}: $dump")
        }
        val info = MapsParser.parse(texts) ?: return
        if (info.distNextM == null && info.remainingMin == null) return   // not a nav notification
        navKey = sbn.key
        NavBridge.onMapsUpdate(info)
    }

    override fun onNotificationRemoved(sbn: StatusBarNotification) {
        if (sbn.packageName == MAPS && sbn.key == navKey) {
            navKey = null
            lastDump = ""
            NavBridge.onMapsEnded()
        }
    }

    private fun collectTexts(sbn: StatusBarNotification): List<String> {
        val out = mutableListOf<String>()
        val ex = sbn.notification.extras
        for (k in listOf(
            Notification.EXTRA_TITLE, Notification.EXTRA_TITLE_BIG, Notification.EXTRA_TEXT,
            Notification.EXTRA_BIG_TEXT, Notification.EXTRA_SUB_TEXT, Notification.EXTRA_INFO_TEXT,
            Notification.EXTRA_SUMMARY_TEXT,
        )) {
            ex.getCharSequence(k)?.toString()?.takeIf { it.isNotBlank() }?.let { out += it }
        }
        ex.getCharSequenceArray(Notification.EXTRA_TEXT_LINES)?.forEach { line ->
            line?.toString()?.takeIf { it.isNotBlank() }?.let { out += it }
        }
        if (out.none { MapsParser.distanceMeters(it) != null }) out += remoteViewTexts(sbn)
        return out.distinct()
    }

    /** Older Maps builds draw the notification with a custom layout; read its TextViews. */
    private fun remoteViewTexts(sbn: StatusBarNotification): List<String> {
        val texts = mutableListOf<String>()
        try {
            val pkgCtx = createPackageContext(sbn.packageName, Context.CONTEXT_IGNORE_SECURITY)
            val builder = Notification.Builder.recoverBuilder(this, sbn.notification)
            for (rv in listOfNotNull(builder.createContentView(), builder.createBigContentView())) {
                val v = rv.apply(pkgCtx, FrameLayout(pkgCtx))
                walk(v, texts)
            }
        } catch (e: Throwable) {
            FrameLog.add("MAPS", "custom layout not readable: ${e.javaClass.simpleName}")
        }
        return texts
    }

    private fun walk(v: View, out: MutableList<String>) {
        when (v) {
            is TextView -> v.text?.toString()?.takeIf { it.isNotBlank() }?.let { out += it }
            is ViewGroup -> for (i in 0 until v.childCount) walk(v.getChildAt(i), out)
        }
    }
}
