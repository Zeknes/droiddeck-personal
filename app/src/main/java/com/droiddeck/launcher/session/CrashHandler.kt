package com.droiddeck.launcher.session

import android.content.Context
import android.util.Log
import java.io.File
import java.io.PrintWriter
import java.io.StringWriter

object CrashHandler {
    private const val TAG = "CrashHandler"

    fun install(context: Context) {
        val previous = Thread.getDefaultUncaughtExceptionHandler()
        Thread.setDefaultUncaughtExceptionHandler { thread, error ->
            try {
                Log.e(TAG, "uncaught on ${thread.name}", error)
                record(context, thread, error)
            } catch (e: Throwable) {
            } finally {
                previous?.uncaughtException(thread, error) ?: Runtime.getRuntime().halt(2)
            }
        }
    }

    internal fun record(context: Context, thread: Thread, error: Throwable) {
        if (QuestExperimental.isSupported()) runCatching {
            File(context.filesDir, "quest-last-crash.txt").writeText(
                "${java.time.Instant.now()} thread=${thread.name}\n" + trace(error)
            )
        }
        val current = SessionPaths.current()?.takeIf { it.isDirectory }
        val dir = folderFor(context)
        if (dir != null) {
            writeTrace(dir, thread, error)
            if (current != null) {
                val worker = Thread({ SessionArtifacts.collect(context, dir, "app crash") }, "crash-collect")
                worker.start()
                worker.join(8000)
            }
        }
        OrphanReaper.reap("app crash")
    }

    internal fun folderFor(context: Context): File? =
        SessionPaths.current()?.takeIf { it.isDirectory } ?: SessionLogShare.latest(context)

    /** The trace goes into a session folder the log browser can share, so a crash before or after
     * a session is still exportable instead of only reaching Android's crash buffer. */
    internal fun writeTrace(folder: File, thread: Thread, error: Throwable) {
        File(folder, "crash-app.txt").writeText(
            "The app itself crashed on thread \"${thread.name}\":\n\n" + trace(error)
        )
    }

    private fun trace(error: Throwable): String =
        StringWriter().also { error.printStackTrace(PrintWriter(it)) }.toString()
}
