package com.mytvs.ntorq

import android.content.Context
import android.content.Intent
import android.net.Uri
import androidx.core.content.FileProvider
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/** Timestamped, in-memory + on-disk log of everything on the link, exportable. */
object FrameLog {
    private const val MAX_LINES = 20000
    private val lines = ArrayDeque<String>()
    private val fmt = SimpleDateFormat("HH:mm:ss.SSS", Locale.US)
    private var file: File? = null
    private var writer: java.io.Writer? = null

    /**
     * Keep the log on disk too, so it survives the app being restarted or crashing,
     * and record any crash with its stack trace.
     */
    @Synchronized
    fun init(ctx: Context) {
        if (file != null) return
        val f = File(ctx.filesDir, "ride-log.txt")
        file = f
        try {
            if (f.exists()) {
                val old = f.readLines().takeLast(MAX_LINES)
                lines.addAll(old)
                f.writeText(old.joinToString("\n", postfix = if (old.isEmpty()) "" else "\n"))
            }
            writer = java.io.FileWriter(f, true).buffered()
        } catch (_: Exception) { }
        add("APP", "---- app started ${SimpleDateFormat("yyyy-MM-dd", Locale.US).format(Date())} " +
            "v${runCatching { ctx.packageManager.getPackageInfo(ctx.packageName, 0).versionName }.getOrNull()} ----")
        val prev = Thread.getDefaultUncaughtExceptionHandler()
        Thread.setDefaultUncaughtExceptionHandler { t, e ->
            add("CRASH", "${e.javaClass.name}: ${e.message}\n" + e.stackTrace.take(25).joinToString("\n") { "    at $it" })
            flush()
            prev?.uncaughtException(t, e)
        }
    }

    @Synchronized
    fun add(tag: String, msg: String) {
        val line = "${fmt.format(Date())}  $tag  $msg"
        lines.addLast(line)
        while (lines.size > MAX_LINES) lines.removeFirst()
        try { writer?.apply { write(line); write("\n") } } catch (_: Exception) { }
        if (tag != "RX" && tag != "TX-BLE") flush()
    }

    @Synchronized
    fun flush() { try { writer?.flush() } catch (_: Exception) { } }

    @Synchronized
    fun snapshot(): List<String> = lines.toList()

    @Synchronized
    fun clear() = lines.clear()

    /** Write the log to a shareable file and return a share intent. */
    @Synchronized
    fun exportIntent(ctx: Context): Intent {
        val dir = File(ctx.cacheDir, "logs").apply { mkdirs() }
        flush()
        val f = File(dir, "ntorq-log-${System.currentTimeMillis()}.txt")
        f.writeText(lines.joinToString("\n"))
        val uri: Uri = FileProvider.getUriForFile(ctx, ctx.packageName + ".fileprovider", f)
        return Intent(Intent.ACTION_SEND).apply {
            type = "text/plain"
            putExtra(Intent.EXTRA_STREAM, uri)
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
    }
}
