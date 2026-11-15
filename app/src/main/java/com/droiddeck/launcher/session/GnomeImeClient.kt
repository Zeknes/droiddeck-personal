package com.droiddeck.launcher.session

import android.net.LocalSocket
import android.net.LocalSocketAddress
import android.os.Handler
import android.os.Looper
import android.util.Log
import java.io.File
import java.nio.charset.StandardCharsets

/**
 * Connects the Android keyboard to the GNOME desktop bridge. The bridge owns the
 * focus generation and the sensitive-field check; this class only carries text
 * while the latest state says the inner field is ready.
 */
class GnomeImeClient(
    private val socket: File,
    private val listener: (GnomeImeProtocol.State?) -> Unit,
) {
    private val protocol = GnomeImeProtocol()
    private val main = Handler(Looper.getMainLooper())
    private val lock = Any()
    private var connection: LocalSocket? = null
    private var stopped = false
    private var thread: Thread? = null
    private var lastLog = 0L

    fun start() = synchronized(lock) {
        if (thread != null) return
        stopped = false
        thread = Thread({
            while (!stopped) {
                val socket = LocalSocket()
                try {
                    socket.connect(LocalSocketAddress(this.socket.path, LocalSocketAddress.Namespace.FILESYSTEM))
                    socket.soTimeout = 0
                    synchronized(lock) { connection = socket }
                    read(socket)
                } catch (e: Exception) {
                    val now = System.currentTimeMillis()
                    if (!stopped && now - lastLog > 60_000) {
                        lastLog = now
                        Log.i(TAG, "GNOME input bridge not reachable: ${e.message}")
                    }
                } finally {
                    synchronized(lock) { if (connection === socket) connection = null }
                    runCatching { socket.close() }
                    main.post { if (!stopped) listener(null) }
                }
                if (!stopped) try { Thread.sleep(RETRY_MS) } catch (e: InterruptedException) { return@Thread }
            }
        }, "gnome-ime").apply { isDaemon = true; start() }
    }

    fun close() = synchronized(lock) {
        stopped = true
        runCatching { connection?.close() }
        connection = null
        thread?.interrupt()
        thread = null
        main.post { listener(null) }
    }

    fun focus(): Boolean = send(protocol.focus())
    fun blur(): Boolean = send(protocol.blur())
    fun preedit(text: String, cursor: Int): Boolean = send(protocol.preedit(text, cursor))
    fun commit(text: String): Boolean = send(protocol.commit(text))

    private fun send(line: String?): Boolean {
        if (line == null) return false
        synchronized(lock) {
            val socket = connection ?: return false
            return try {
                socket.outputStream.write((line + "\n").toByteArray(StandardCharsets.UTF_8))
                socket.outputStream.flush()
                true
            } catch (e: Exception) {
                Log.i(TAG, "GNOME input bridge write failed: ${e.message}")
                runCatching { socket.close() }
                connection = null
                false
            }
        }
    }

    private fun read(socket: LocalSocket) {
        val buffer = StringBuilder()
        socket.inputStream.bufferedReader(StandardCharsets.UTF_8).use { reader ->
            while (!stopped) {
                val line = reader.readLine() ?: break
                val state = protocol.state(line) ?: continue
                main.post { if (!stopped) listener(state) }
            }
        }
    }

    private companion object {
        const val TAG = "GnomeImeClient"
        const val RETRY_MS = 500L
    }
}
