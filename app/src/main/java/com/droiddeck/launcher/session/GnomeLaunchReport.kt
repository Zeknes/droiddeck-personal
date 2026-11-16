package com.droiddeck.launcher.session

import org.json.JSONObject
import java.io.File

/** What the GNOME desktop reached before it stopped.
 *
 * The desktop pack reports each step as `{"stage":"...","exit":"..."}` in `gnome-stage.jsonl`
 * inside the session folder, and some pack versions also print
 * `== GNOME STAGE <name> exit=<value>` on the session log. Nothing else in the app can see the
 * guest's progress, and the shell's own socket (`inner`) is the evidence that GNOME started.
 */
object GnomeLaunchReport {
    const val STAGE_FILE = "gnome-stage.jsonl"
    const val PREFIX = "== GNOME STAGE "
    const val INNER = "inner"

    val SOCKETS = listOf("wayland-0", "session-bus.sock", "pipewire-0", "ibus.sock", "droiddeck-ime.sock", INNER)

    private val REQUIRED_FILES = listOf(
        "usr/bin/gnome-shell",
        "usr/libexec/mutter-devkit",
        "usr/local/bin/droiddeck-gnome-desktop",
        "usr/local/bin/droiddeck-gnome-extension",
        "usr/local/share/droiddeck/gnome-input/droiddeck-ime-bridge",
        "usr/local/share/droiddeck/gnome-input/extension/extension.js",
        "usr/bin/pipewire",
        "usr/bin/wireplumber",
        "usr/share/pipewire/pipewire.conf",
        "usr/lib/aarch64-linux-gnu/pipewire-0.3/libpipewire-module-protocol-native.so",
    )

    private const val SCAN_BYTES = 256L * 1024

    data class Stage(val name: String, val exit: String)

    data class Report(val stages: List<Stage>, val present: Set<String>, val failure: Stage?) {
        val last: Stage? get() = stages.lastOrNull()
        val innerUp: Boolean get() = INNER in present
        val missing: List<String> get() = SOCKETS.filter { it !in present }
        val stageName: String get() = failure?.name ?: last?.name ?: ""
        val stageExit: String get() = failure?.exit ?: last?.exit.orEmpty()
    }

    /** The pack's stage file is its own record; the session log is the fallback for pack versions
     * that only print there. */
    fun report(sessionDirectory: File, socketDirectory: File): Report {
        val stages = stageFile(sessionDirectory) ?: logStages(File(sessionDirectory, "session.log"))
        return report(stages, socketDirectory)
    }

    fun report(stages: List<Stage>, socketDirectory: File): Report =
        Report(stages, present(socketDirectory), failure(stages))

    internal fun stageFile(folder: File): List<Stage>? {
        val file = File(folder, STAGE_FILE)
        if (!file.isFile) return null
        return linesFromTail(file).mapNotNull { parseJson(it) }.toList().takeIf { it.isNotEmpty() }
    }

    internal fun logStages(log: File, maxBytes: Long = SCAN_BYTES): List<Stage> =
        if (!log.isFile) emptyList() else linesFromTail(log, maxBytes).mapNotNull { parse(it) }.toList()

    internal fun stages(lines: Sequence<String>): List<Stage> = lines.mapNotNull { parse(it) }.toList()

    fun parse(line: String): Stage? {
        val text = line.trim()
        if (!text.startsWith(PREFIX)) return null
        val body = text.substring(PREFIX.length)
        val marker = body.lastIndexOf(" exit=")
        if (marker <= 0) return null
        return stage(body.substring(0, marker), body.substring(marker + " exit=".length))
    }

    fun parseJson(line: String): Stage? {
        val text = line.trim()
        if (!text.startsWith("{")) return null
        val value = runCatching { JSONObject(text) }.getOrNull() ?: return null
        return stage(value.optString("stage"), value.optString("exit"))
    }

    private fun stage(name: String, exit: String): Stage? {
        val trimmedName = name.trim()
        val trimmedExit = exit.trim()
        if (trimmedName.isEmpty() || trimmedExit.isEmpty()) return null
        return Stage(trimmedName, trimmedExit)
    }

    /** A stage is appended as it happens, so the end of the file carries the last one. */
    private fun linesFromTail(file: File, maxBytes: Long = SCAN_BYTES): Sequence<String> = runCatching {
        val size = file.length()
        val start = maxOf(0L, size - maxBytes)
        java.io.RandomAccessFile(file, "r").use { handle ->
            handle.seek(start)
            val bytes = ByteArray((size - start).toInt())
            handle.readFully(bytes)
            String(bytes, Charsets.ISO_8859_1).lineSequence().drop(if (start > 0) 1 else 0)
        }
    }.getOrDefault(emptySequence())

    /** `shell` also reports its own exit code, so a non-zero one there is a failed stage too. */
    fun failure(stages: List<Stage>): Stage? = stages.lastOrNull { stage ->
        stage.exit == "failed" || (stage.name == "shell" && (stage.exit.toIntOrNull() ?: 0) != 0)
    }

    fun present(socketDirectory: File): Set<String> =
        SOCKETS.filter { File(socketDirectory, it).exists() }.toSet()

    /** A session that has drawn nothing, created no inner socket and reported no failed stage is stuck. */
    fun stalled(report: Report, firstFrameSeen: Boolean): Boolean =
        !firstFrameSeen && !report.innerUp && report.failure == null

    fun missingFiles(root: File): List<String> = REQUIRED_FILES.filter { !File(root, it).isFile }

    /** No stage at all, and the pack is absent or the launcher reported it missing (exit 66), is
     * the session that never reached the desktop script. */
    fun unreached(stages: List<Stage>, missingFiles: List<String>, status: Int): Boolean =
        stages.isEmpty() && (missingFiles.isNotEmpty() || status == 66)

    /** The guest services create these in XDG_RUNTIME_DIR, nothing removes them when a session
     * dies, and the launcher starts each of them only when its socket is absent (`[ ! -S ... ]`).
     * A stale socket from an earlier failed session therefore makes the next one skip PipeWire or
     * the bus, and mutter-devkit then exits on "Failed to connect pipewire context". A new session
     * starts with no guest process, so anything left behind is dead and safe to remove. */
    val SERVICE_SOCKETS = listOf("pipewire-0", "pipewire-0.lock", "ibus.sock", "session-bus.sock", "system-bus.sock")

    fun clearStaleServiceSockets(runtimeDirectory: File): List<String> {
        val removed = ArrayList<String>()
        SERVICE_SOCKETS.forEach { name ->
            val file = File(runtimeDirectory, name)
            if (!file.exists()) return@forEach
            if (runCatching { file.delete() }.getOrDefault(false)) removed += name
        }
        return removed
    }

    /** Non-secret detail for the end screen and the export: stage exit and the missing sockets. */
    fun detail(report: Report): String {
        val parts = ArrayList<String>()
        report.failure?.let { parts += "exit ${it.exit}" }
        if (!report.innerUp) {
            report.missing.filter { it != "wayland-0" }
                .let { if (it.isNotEmpty()) parts += "missing ${it.joinToString(", ")}" }
        }
        return parts.joinToString("; ")
    }

    const val RENDERER_SURFACELESS = "renderer-surfaceless"
    const val PIPEWIRE_CONTEXT = "pipewire-context"
    const val SHELL_SHUTDOWN = "shell-shutdown"
    const val IBUS_REFUSED = "ibus-refused"
    const val GDM_MISSING = "gdm-missing"

    /** Lines the shell writes when a subsystem it needs is missing. The devkit one is fatal: it
     * shuts the shell down, and GNOME exits 0, so the status alone says nothing. */
    private val FAULT_LINES = listOf(
        RENDERER_SURFACELESS to "Created surfaceless renderer without GPU",
        PIPEWIRE_CONTEXT to "Failed to connect pipewire context",
        SHELL_SHUTDOWN to "Shutting down GNOME Shell",
        IBUS_REFUSED to "Unable to connect to ibus",
        GDM_MISSING to "Error registering session with GDM",
    )

    /** The guest's own service logs live in the runtime dir, outside the session folder the log
     * browser exports; a failing launch needs them next to the session. */
    val SERVICE_LOGS = listOf("pipewire.log", "wireplumber.log", "ibus.log", "session-bus.log", "droiddeck-ime.log")

    fun faults(lines: Sequence<String>): List<String> {
        val found = LinkedHashSet<String>()
        lines.forEach { line ->
            FAULT_LINES.forEach { (code, marker) -> if (line.contains(marker)) found += code }
        }
        return found.toList()
    }

    fun faults(log: File, maxBytes: Long = SCAN_BYTES): List<String> =
        if (!log.isFile) emptyList() else faults(linesFromTail(log, maxBytes))

    /** Copies what the guest services wrote into the session folder, keeping each file's head and
     * tail when it is long. */
    fun copyServiceLogs(runtimeDirectory: File, sessionDirectory: File, maxBytes: Long = 256L * 1024): List<String> {
        val copied = ArrayList<String>()
        SERVICE_LOGS.forEach { name ->
            val source = File(runtimeDirectory, name)
            if (!source.isFile || source.length() == 0L) return@forEach
            val target = File(sessionDirectory, name)
            runCatching {
                val size = source.length()
                if (size <= maxBytes) {
                    source.copyTo(target, overwrite = true)
                } else {
                    val text = linesFromTail(source, maxBytes).joinToString("\n")
                    target.writeText("(the first $size bytes are not included)\n" + text + "\n")
                }
            }.onSuccess { copied += name }
        }
        return copied
    }
}
