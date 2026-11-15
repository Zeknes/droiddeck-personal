package com.droiddeck.launcher.session

import android.content.ClipData
import android.content.Context
import android.content.Intent
import android.os.Handler
import android.os.Looper
import android.util.Log
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.core.content.FileProvider
import com.droiddeck.launcher.R
import java.io.File
import java.util.ArrayList

object SessionLogShare {
    data class Details(val sizeBytes: Long, val modifiedAt: Long)

    private val main = Handler(Looper.getMainLooper())

    var progress by mutableStateOf<Float?>(null)
        private set

    fun prepare(context: Context, folder: () -> File?, done: (File?) -> Unit) {
        if (progress != null) return
        progress = 0f
        Thread({
            val zip = runCatching { folder()?.let { dir -> zipProbe(context, dir) } }
                .onFailure { Log.w("SessionLogShare", "could not package session logs", it) }
                .getOrNull()
            main.post { progress = null; done(zip) }
        }, "share-logs").start()
    }

    fun latest(context: Context): File? = sessions(context).lastOrNull()

    fun sessions(context: Context): List<File> {
        val current = SessionPaths.current()?.takeIf { it.isDirectory && SessionPaths.isSessionFolder(it) }
        return (SessionPaths.sessionFolders(context) + listOfNotNull(current))
            .distinctBy { it.absoluteFile.toPath().normalize().toString() }
            .sortedWith(SessionPaths.chronological)
    }

    fun details(folder: File): Details {
        var size = 0L
        var modified = folder.lastModified()
        folder.walkTopDown().filter { it.isFile }.forEach { file ->
            size += file.length()
            modified = maxOf(modified, file.lastModified())
        }
        return Details(size, modified)
    }

    fun deleteSession(context: Context, folder: File): Boolean {
        if (runCatching { SessionPaths.current()?.canonicalFile == folder.canonicalFile }.getOrDefault(false)) return false
        val knownSession = runCatching { folder.canonicalFile }.getOrNull() in sessions(context).mapNotNull { runCatching { it.canonicalFile }.getOrNull() }
        return knownSession && folder.isDirectory && folder.deleteRecursively()
    }

    fun prepareEnhanced(context: Context, folder: () -> File?, done: (List<File>) -> Unit) {
        if (progress != null) return
        progress = 0f
        Thread({
            val zips = runCatching {
                folder()?.let { dir -> listOfNotNull(zipProbe(context, dir)) }.orEmpty()
            }.onFailure { Log.w("SessionLogShare", "could not package enhanced diagnostics", it) }
                .getOrDefault(emptyList())
            main.post { progress = null; done(zips) }
        }, "share-diagnostics").start()
    }


    fun zipFolder(context: Context, folder: File, out: File = shareDir(context), onProgress: ((Float) -> Unit)? = null): File? =
        EnhancedSessionLogShare.createLegacy(context, folder, onProgress, out)

    internal fun zipProbe(context: Context, folder: File, out: File = shareDir(context)): File? {
        val source = File(folder, "wayland.log.mobile-frames.log")
        if (!source.isFile || source.length() > 512 * 1024) return null
        val bytes = source.inputStream().use { input ->
            val output = java.io.ByteArrayOutputStream()
            val buffer = ByteArray(8192)
            while (output.size() <= 512 * 1024) {
                val n = input.read(buffer, 0, minOf(buffer.size, 512 * 1024 + 1 - output.size()))
                if (n < 0) break
                output.write(buffer, 0, n)
            }
            output.toByteArray()
        }
        if (bytes.size > 512 * 1024) return null
        val lines = bytes.toString(Charsets.US_ASCII).lines().dropLastWhile { it.isEmpty() }
        val valid = Regex("[0-9]+\\.[0-9]{9} [0-9]+ (create|toplevel|commit|frame-request|feedback-request|frame-done|frame-done-no-output|presented|discarded-undrawn|discarded-no-output|discarded-replaced|discarded-destroy|destroy|exit)")
        if (lines.size > 4096 || lines.any { !valid.matches(it) }) return null
        out.mkdirs()
        val zip = File(out, "mobile-frames.zip")
        java.util.zip.ZipOutputStream(zip.outputStream()).use { archive ->
            archive.putNextEntry(java.util.zip.ZipEntry("mobile-frames.log"))
            archive.write(lines.joinToString("\n", postfix = "\n").toByteArray(Charsets.US_ASCII))
            archive.closeEntry()
        }
        return zip
    }

    const val STORES_DIR = "stores"

    internal fun shareDir(context: Context): File = File(context.cacheDir, "share")

    fun clear(context: Context) {
        shareDir(context).deleteRecursively()
        File(context.cacheDir, "shared-logs").deleteRecursively()
    }

    fun shareIntent(context: Context, zip: File): Intent {
        val uri = FileProvider.getUriForFile(context, context.packageName + ".logs", zip)
        val send = Intent(Intent.ACTION_SEND)
            .setType("application/zip")
            .putExtra(Intent.EXTRA_STREAM, uri)
            .putExtra(Intent.EXTRA_SUBJECT, zip.nameWithoutExtension)
            .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        send.clipData = ClipData.newRawUri(zip.name, uri)
        return Intent.createChooser(send, context.getString(R.string.logshare_chooser)).addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
    }

    fun shareEnhancedIntent(context: Context, zips: List<File>): Intent {
        require(zips.isNotEmpty())
        val uris = ArrayList(zips.map { FileProvider.getUriForFile(context, context.packageName + ".logs", it) })
        val send = Intent(Intent.ACTION_SEND_MULTIPLE)
            .setType("application/zip")
            .putParcelableArrayListExtra(Intent.EXTRA_STREAM, uris)
            .putExtra(Intent.EXTRA_SUBJECT, zips.first().name.substringBeforeLast('-'))
            .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        send.clipData = ClipData.newUri(context.contentResolver, zips.first().name, uris.first()).also { clip ->
            for (i in 1 until uris.size) clip.addItem(ClipData.Item(uris[i]))
        }
        return Intent.createChooser(send, context.getString(R.string.logshare_chooser))
            .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
    }
}
