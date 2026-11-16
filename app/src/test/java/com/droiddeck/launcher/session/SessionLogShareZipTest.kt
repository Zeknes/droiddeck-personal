package com.droiddeck.launcher.session

import com.droiddeck.launcher.runtime.LinuxRuntime
import java.io.File
import java.text.SimpleDateFormat
import java.util.UUID
import java.util.Date
import java.util.Locale
import java.util.concurrent.TimeUnit
import java.util.zip.ZipFile
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.After
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33])
class SessionLogShareZipTest {
    private val app get() = RuntimeEnvironment.getApplication()

    @Before fun installSecureSourceOpener() = installJvmSecureSourceOpener()

    @After fun resetSourceOpener() = resetSecureSourceOpener()

    @Test fun startupRemovesAbandonedDiagnosticStagesAndPreservesActiveSnapshots() {
        val abandoned = File(app.cacheDir, "share-${UUID.randomUUID()}.diagnostic-stage").apply { mkdirs() }
        val active = EnhancedSessionLogShare.acquireDiagnosticStage(app, "share")
        try {
            EnhancedSessionLogShare.cleanupAbandonedStages(app)
            assertFalse(abandoned.exists())
            assertTrue(active.directory.isDirectory)
            EnhancedSessionLogShare.cleanupAbandonedStages(app)
            assertTrue(active.directory.isDirectory)
            val probeClassResource = checkNotNull(
                DiagnosticStageLockProbe::class.java.getResource("DiagnosticStageLockProbe.class"),
            )
            val probeClass = File(probeClassResource.toURI())
            var classPathRoot = checkNotNull(probeClass.parentFile)
            repeat(4) { classPathRoot = checkNotNull(classPathRoot.parentFile) }
            val classPath = classPathRoot.absolutePath
            val javaName = if (checkNotNull(System.getProperty("os.name")).startsWith("Windows")) {
                "java.exe"
            } else {
                "java"
            }
            val java = File(File(System.getProperty("java.home"), "bin"), javaName)
            val probe = ProcessBuilder(
                java.absolutePath,
                "-cp",
                classPath,
                DiagnosticStageLockProbe::class.java.name,
                File(active.directory, ".active").absolutePath,
            ).redirectErrorStream(true).start()
            val completed = probe.waitFor(5, TimeUnit.SECONDS)
            if (!completed) probe.destroyForcibly()
            assertTrue("lock probe timed out", completed)
            assertEquals("cleanup released the active stage lock", 0, probe.exitValue())
        } finally {
            active.close()
        }
    }

    @Test fun downloadExportUsesTheRedactedSessionArchive() {
        val session = File(LinuxRuntime.logDir(app), "2026-11-16-30-desktop").apply { mkdirs() }
        val out = File(app.cacheDir, "download-export-test").apply { mkdirs() }
        try {
            File(session, "events.jsonl").writeText("{\"t\":1,\"event\":\"ime.changed\",\"textField\":true}\n")
            File(session, "session.log").writeText("token=supersecretvalue123456789\n")
            val target = File(out, "saved.zip")
            assertTrue(SessionLogShare.exportDownloadArchive(app, session) { zip -> zip.copyTo(target); true })
            ZipFile(target).use { zip ->
                val contents = zip.entries().toList().associate { it.name.substringAfterLast('/') to zip.getInputStream(it).bufferedReader().readText() }
                assertTrue(contents.getValue("events.jsonl").contains("ime.changed"))
                assertFalse(contents.getValue("session.log").contains("supersecretvalue123456789"))
            }
            assertFalse(SessionLogShare.exportDownloadArchive(app, session) { false })
        } finally {
            session.deleteRecursively()
            out.deleteRecursively()
            SessionLogShare.clear(app)
        }
    }

    @Test fun mediaStoreDownloadPublishesOnlyAfterWritingAndDeletesFailures() {
        val output = File(app.cacheDir, "media-store-export.zip")
        val source = File(app.cacheDir, "media-store-source.zip").apply { writeText("zip bytes") }
        val provider = DownloadProvider(output)
        provider.attachInfo(app, android.content.pm.ProviderInfo().apply { authority = "media"; exported = true })
        org.robolectric.shadows.ShadowContentResolver.registerProviderInternal("media", provider)
        try {
            val saved = SessionLogShare.saveArchiveToDownloads(app, source)
            assertTrue("insert=${provider.inserted} update=${provider.updated} deleted=${provider.deleted}", saved)
            assertEquals("zip bytes", output.readText())
            assertEquals("Download/DroidDeck", provider.inserted!!.getAsString("relative_path"))
            assertEquals(1, provider.inserted!!.getAsInteger("is_pending").toInt())
            assertEquals(0, provider.updated!!.getAsInteger("is_pending").toInt())
            provider.failUpdate = true
            assertFalse(SessionLogShare.saveArchiveToDownloads(app, source))
            assertTrue(provider.deleted)
        } finally {
            source.delete()
            output.delete()
        }
    }

    private class DownloadProvider(private val output: File) : android.content.ContentProvider() {
        var inserted: android.content.ContentValues? = null
        var updated: android.content.ContentValues? = null
        var deleted = false
        var failUpdate = false
        override fun onCreate() = true
        override fun insert(uri: android.net.Uri, values: android.content.ContentValues?): android.net.Uri {
            inserted = values
            return android.content.ContentUris.withAppendedId(uri, 1)
        }
        override fun openFile(uri: android.net.Uri, mode: String): android.os.ParcelFileDescriptor =
            android.os.ParcelFileDescriptor.open(output, android.os.ParcelFileDescriptor.MODE_CREATE or android.os.ParcelFileDescriptor.MODE_WRITE_ONLY or android.os.ParcelFileDescriptor.MODE_TRUNCATE)
        override fun update(uri: android.net.Uri, values: android.content.ContentValues?, selection: String?, args: Array<out String>?): Int {
            updated = values
            return if (failUpdate) 0 else 1
        }
        override fun delete(uri: android.net.Uri, selection: String?, args: Array<out String>?): Int { deleted = true; return 1 }
        override fun getType(uri: android.net.Uri): String? = null
        override fun query(uri: android.net.Uri, projection: Array<out String>?, selection: String?, args: Array<out String>?, sort: String?): android.database.Cursor? = null
    }

    @Test fun anArchiveCanBeWrittenOutsideTheShareFolder() {
        SessionLogShare.clear(app)
        val session = File(LinuxRuntime.logDir(app), "2026-11-03-01-steam").apply { mkdirs() }
        val out = File(app.getExternalFilesDir(null), "agent-logs/request-1")
        val secondOut = File(app.getExternalFilesDir(null), "agent-logs/request-2")
        try {
            File(session, "session.log").writeText("booted\n")
            val zip = SessionLogShare.zipFolder(app, session, out)!!
            val secondZip = SessionLogShare.zipFolder(app, session, secondOut)!!
            assertEquals(out, zip.parentFile)
            assertFalse(File(app.cacheDir, "share").exists())
            assertTrue(zip.isFile)
            assertEquals(secondOut, secondZip.parentFile)
            assertTrue(secondZip.isFile)
        } finally {
            out.deleteRecursively()
            secondOut.deleteRecursively()
            session.deleteRecursively()
        }
    }

    @Test fun theShareActionWorksForADesktopInstallThatFailedBeforeAnyFrame() {
        SessionLogShare.clear(app)
        val session = File(LinuxRuntime.logDir(app), "2026-11-16-01-desktop").apply { mkdirs() }
        File(session, "events.jsonl").writeText(
            """{"t":1,"event":"session.created","sessionId":"x"}""" + "\n" +
                """{"t":2,"event":"desktop.installing","sessionId":"x","component":"desktop"}""" + "\n" +
                """{"t":3,"event":"session.failed","sessionId":"x","code":"DESKTOP_INSTALL_FAILED","message":"Install failed (extract: IOException)"}""" + "\n"
        )
        File(session, "session.log").writeText("desktop.installing\nInstall failed (extract: IOException)\n")
        try {
            var offered: File? = null
            SessionLogShare.prepare(app, { session }) { offered = it }
            val deadline = System.currentTimeMillis() + 30_000
            while (offered == null && System.currentTimeMillis() < deadline) {
                org.robolectric.shadows.ShadowLooper.idleMainLooper()
                Thread.sleep(20)
            }
            checkNotNull(offered) { "no archive was offered for a session that failed during the desktop install" }
            val zip = checkNotNull(SessionLogShare.zipFolder(app, session)) { "the share action produced no archive" }
            val manifest = ZipFile(zip).use { z ->
                z.getEntry("diagnostics-manifest.json")?.let { e -> z.getInputStream(e).bufferedReader().readText() }.orEmpty()
            }
            val text = ZipFile(zip).use { z ->
                z.entries().toList().associate { it.name.substringAfter('/') to z.getInputStream(it).bufferedReader().readText() }
            }
            assertTrue("entries=${text.keys} manifest=$manifest", text.containsKey("events.jsonl"))
            assertTrue("entries=${text.keys} manifest=$manifest", text.containsKey("session.log"))
            assertTrue(text.getValue("events.jsonl").contains("DESKTOP_INSTALL_FAILED"))
            assertTrue(text.getValue("session.log").contains("extract: IOException"))
        } finally {
            SessionLogShare.clear(app)
            session.deleteRecursively()
        }
    }

    @Test fun aLargeSteamLogIsRedactedInTheSharedArchive() {
        val session = File(LinuxRuntime.logDir(app), "2026-10-08-02-steam").apply { mkdirs() }
        val raw = File(session, "steam-42.log")
        val earlySecret = "early-sessionid-abcdef123456"
        val trailingSecret = "late-refresh-token-abcdef123456"
        try {
            raw.bufferedWriter().use { out ->
                out.write("sessionid=$earlySecret")
                val chunk = "x".repeat(8192)
                repeat(8192) { out.write(chunk) }
                out.write("\nrefresh_token=$trailingSecret\n")
            }
            assertTrue(raw.length() > 64L * 1024 * 1024)

            val zip = SessionLogShare.zipFolder(app, session)!!
            ZipFile(zip).use { archive ->
                val entry = archive.getEntry("${session.name}/steam-42.log")!!
                val shared = archive.getInputStream(entry).bufferedReader().readText()
                assertTrue(shared.contains("diagnostic file truncated"))
                assertFalse("early secret survived", shared.contains(earlySecret))
                assertFalse("trailing secret survived", shared.contains(trailingSecret))
                assertTrue(shared.contains("refresh_token=<redacted:token>"))
            }
        } finally {
            SessionLogShare.clear(app)
            session.deleteRecursively()
        }
    }

    @Test fun everyTextFileInTheZipIsScrubbedAndTheStoresAndToolsLogsComeAlong() {
        val logs = LinuxRuntime.logDir(app)
        val session = File(logs, "2026-10-08-01-steam").apply { mkdirs() }
        File(session, "session.log").writeText(
            "fetch https://gog-cdn-fastly.gog.com/token=nva=1791500000~dirs=/x~token=0a1b2c3d4e5f/content-system/v2/store/1/ab/cd/f\n" +
                "fetch https://epicgames-download1.akamaized.net/Builds/Org/a/ChunksV4/12/ABCD.chunk?__token__=exp=1~hmac=deadbeefcafe\n" +
                "login refresh_token=abcdef123456 user someone@example.com code=AUTHCODE123\n" +
                "Authorization: Bearer abc.def.ghi\n" +
                "kept: EResult 5, app 1086940, steamapps/common/Game\n"
        )
        val storesLog = "stores-" + SimpleDateFormat("yyyy-MM-dd", Locale.US).format(Date()) + ".log"
        File(logs, "stores").apply { mkdirs() }.resolve(storesLog).writeText("epic: f_token=zzzzzzzz12 window 32\n")
        File(logs, SessionPaths.TOOLS_DIR).apply { mkdirs() }.resolve("flatpak-install.log").writeText("== install\nCookie: steamLoginSecure=76561198000000000%7C%7Ceyabc\n")

        val zip = SessionLogShare.zipFolder(app, session)!!
        assertTrue(zip.path.startsWith(File(app.cacheDir, "share").path))
        val text = ZipFile(zip).use { z -> z.entries().toList().associate { e -> e.name to z.getInputStream(e).bufferedReader().readText() } }
        assertTrue(text.keys.containsAll(listOf("2026-10-08-01-steam/session.log", "stores/$storesLog", "tools/flatpak-install.log")))
        val all = text.values.joinToString("\n")
        for (secret in listOf("0a1b2c3d4e5f", "deadbeefcafe", "abcdef123456", "someone@example.com", "AUTHCODE123", "abc.def.ghi", "zzzzzzzz12", "eyabc")) {
            assertFalse("$secret survived", all.contains(secret))
        }
        assertTrue(all.contains("kept: EResult 5, app 1086940, steamapps/common/Game"))
        assertTrue(all.contains("https://gog-cdn-fastly.gog.com/"))

        SessionLogShare.clear(app)
        assertFalse(zip.exists())
    }

    @Test fun battleNetAndBlizzardLogsComeAlongScrubbedAndNewestFirst() {
        val session = File(LinuxRuntime.logDir(app), "2026-10-25-05-battle-net").apply { mkdirs() }
        File(session, "session.log").writeText("droiddeck-battlenet: open (from the menu)\n")
        val root = LinuxRuntime.rootDir(app)
        File(root, "root/.cache/droiddeck/battlenet").apply { mkdirs() }.apply {
            resolve("battlenet.log").writeText("launching WoW\n")
            resolve("other.log").writeText("other\n")
        }
        val drive = File(com.droiddeck.launcher.runtime.BattleNet.prefix(app), "pfx/drive_c")
        val client = File(drive, "users/steamuser/AppData/Local/Battle.net/Logs").apply { mkdirs() }
        val now = System.currentTimeMillis()
        for (i in 1..8) client.resolve("battle.net-$i.log").apply { writeText("client $i accountId=1:0:5ca1ab1e\n"); setLastModified(now - i * 60_000L) }
        client.resolve("battle.net-old.log").apply { writeText("old\n"); setLastModified(now - 30L * 86_400_000L) }
        File(drive, "ProgramData/Battle.net/Agent/Agent.9824/Logs").apply { mkdirs() }.resolve("Agent-1.log").writeText("agent someone@example.com\n")
        File(drive, "ProgramData/Battle.net/Agent/Agent.9900/Logs").apply { mkdirs() }.resolve("Agent-1.log").writeText("agent 9900\n")
        File(drive, "ProgramData/Battle.net/Setup/bna_2/Logs").apply { mkdirs() }.resolve("battle.net-setup-1.log").writeText("setup\n")

        val zip = SessionLogShare.zipFolder(app, session)!!
        val text = ZipFile(zip).use { z -> z.entries().toList().associate { e -> e.name to z.getInputStream(e).bufferedReader().readText() } }
        assertTrue(text.keys.containsAll(listOf("battlenet/droiddeck/battlenet.log", "battlenet/agent/Agent.9824/Agent-1.log", "battlenet/agent/Agent.9900/Agent-1.log", "battlenet/setup/bna_2/battle.net-setup-1.log")))
        assertFalse(text.containsKey("battlenet/droiddeck/other.log"))
        assertEquals((1..6).map { "battlenet/client/battle.net-$it.log" }.toSet(), text.keys.filter { it.startsWith("battlenet/client/") }.toSet())
        val all = text.values.joinToString("\n")
        assertFalse(all.contains("5ca1ab1e"))
        assertFalse(all.contains("someone@example.com"))
        SessionLogShare.clear(app)
    }

    @Test fun theSharePassChangesNothingInACleanLine() {
        val line = "dxvk: Device 0x5 (Adreno 750) https://github.com/doitsujin/dxvk/releases code 403"
        assertEquals(line, com.droiddeck.launcher.core.LogRedactor.redactForShare(line))
    }

    private fun olderFolder(parent: File, name: String, marker: String = ".scrubbed-2"): File {
        val dir = File(parent, name).apply { mkdirs() }
        File(dir, marker).writeText("scrubbed earlier\n")
        File(dir, SessionArtifacts.SCRUBBED_TREE_MARKER).writeText("rules 2\n")
        File(dir, "steam").apply { mkdirs() }.resolve("webhelper_js.txt").writeText(
            "[2026-10-01 20:00:00] SteamUI: INFO: Login: OnLoginStateChange someone.masked@example.com 2 1 0 0\n" +
                "[2026-10-01 20:00:01] SteamUI: INFO: Login: OnLoginStateChange maskeduser42 2 1 0 0\n" +
                "[2026-10-01 20:00:02] SteamUI: INFO: Login: OnLoginStateChange  0 1 0 0\n" +
                "0024:trace:seh:dispatch_exception code=c0000005 flags=0\n"
        )
        return dir
    }

    private fun assertScrubbed(dir: File) {
        val text = File(dir, "steam/webhelper_js.txt").readText()
        for (secret in listOf("someone.masked", "example.com", "maskeduser42")) assertFalse("$secret survived in $dir", text.contains(secret))
        assertTrue(text.contains("Login: OnLoginStateChange <redacted:account> 2 1 0 0"))
        assertTrue(text.contains("Login: OnLoginStateChange  0 1 0 0"))
        assertTrue(text.contains("code=c0000005"))
        val markers = dir.list()!!.filter { it.startsWith(".scrubbed-") && it != SessionArtifacts.SCRUBBED_TREE_MARKER }
        assertEquals("$dir", listOf(".scrubbed-r${com.droiddeck.launcher.core.LogRedactor.RULES_VERSION}"), markers)
        assertTrue(File(dir, SessionArtifacts.SCRUBBED_TREE_MARKER).readText()
            .startsWith("rules ${com.droiddeck.launcher.core.LogRedactor.RULES_VERSION}\n"))
    }

    @Test fun aFolderMarkedUnderOlderRulesIsScrubbedAgainAtStart() {
        val dir = olderFolder(LinuxRuntime.logDir(app), "2026-10-01-01-steam")
        SessionArtifacts.scrubOlder(app)
        assertScrubbed(dir)
    }

    @Test fun everyMigratedFolderIsScrubbedUnderTheCurrentRulesInOneStart() {
        val legacy = LinuxRuntime.legacyLogDir().apply { mkdirs() }
        val names = (1..9).map { "2026-10-0${it}-01-steam" }
        names.forEachIndexed { i, name ->
            olderFolder(legacy, name, marker = listOf(".scrubbed-2", ".scrubbed-1", ".scrubbed-r${com.droiddeck.launcher.core.LogRedactor.RULES_VERSION}")[i % 3])
        }
        File(legacy, "Saves").apply { mkdirs() }.resolve("keep.txt").writeText("save\n")

        LogMigration.run(app)
        SessionArtifacts.scrubOlder(app)

        val logs = LinuxRuntime.logDir(app)
        for (name in names) assertScrubbed(File(logs, name))
        assertEquals(listOf("Saves"), legacy.list()!!.toList())
    }
}
