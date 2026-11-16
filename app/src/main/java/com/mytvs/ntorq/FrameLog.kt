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
    private const val MAX_LINES = 4000
    private val lines = ArrayDeque<String>()
    private val fmt = SimpleDateFormat("HH:mm:ss.SSS", Locale.US)

    @Synchronized
    fun add(tag: String, msg: String) {
        val line = "${fmt.format(Date())}  $tag  $msg"
        lines.addLast(line)
        while (lines.size > MAX_LINES) lines.removeFirst()
    }

    @Synchronized
    fun snapshot(): List<String> = lines.toList()

    @Synchronized
    fun clear() = lines.clear()

    /** Write the log to a shareable file and return a share intent. */
    @Synchronized
    fun exportIntent(ctx: Context): Intent {
        val dir = File(ctx.cacheDir, "logs").apply { mkdirs() }
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
