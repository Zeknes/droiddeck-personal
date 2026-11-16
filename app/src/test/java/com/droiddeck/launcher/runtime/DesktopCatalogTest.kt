package com.droiddeck.launcher.runtime

import com.droiddeck.launcher.BuildConfig
import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

@RunWith(RobolectricTestRunner::class)
class DesktopCatalogTest {
    @get:Rule val temporary = TemporaryFolder()

    @Test fun desktopNeedsTheCompleteCurrentPinnedPackage() {
        val root = temporary.root
        val kwin = File(root, "usr/bin/kwin_wayland").apply {
            parentFile.mkdirs()
            writeText("fixture")
        }
        val marker = File(root, ".droiddeck-pkg-${DesktopCatalog.DESKTOP_ID}")
        assertFalse(DesktopCatalog.desktopInstalled(root))
        marker.writeText("older-package")
        assertFalse(DesktopCatalog.desktopInstalled(root))
        marker.writeText("${BuildConfig.DESKTOP_KDE_TAG}\n")
        assertTrue(DesktopCatalog.desktopInstalled(root))
        val pending = File(root, DesktopRemoval.PENDING).apply { writeText(BuildConfig.DESKTOP_KDE_TAG) }
        assertFalse(DesktopCatalog.desktopInstalled(root))
        pending.delete()
        assertTrue(DesktopCatalog.desktopInstalled(root))
        kwin.delete()
        assertFalse(DesktopCatalog.desktopInstalled(root))
    }

    @Test fun anInstalledPackIsRecognisedByTheMarkerItsOwnEntryWrites() {
        val app = org.robolectric.RuntimeEnvironment.getApplication()
        val root = LinuxRuntime.rootDir(app)
        val pack = File(root, "usr/share/droiddeck/desktop-pack.json").apply { parentFile.mkdirs() }
        val manifest = File(root, "usr/share/droiddeck/desktop/files.tsv.gz").apply { parentFile.mkdirs() }
        val kwin = File(root, "usr/bin/kwin_wayland").apply {
            parentFile.mkdirs()
            writeText("fixture")
        }
        val marker = File(root, ".droiddeck-pkg-${DesktopCatalog.DESKTOP_ID}")
        try {
            pack.writeText(
                """{"tag":"ubuntu-r1-rc5-desktop","url":"https://example.invalid/ubuntu-r1-rc5-desktop.tar.zst",""" +
                    """"sha256":"${"b".repeat(64)}","size":898306226}"""
            )
            manifest.writeBytes(byteArrayOf())
            val entry = DesktopCatalog.desktopEntry(app)
            assertEquals("ubuntu-r1-rc5-desktop", entry.version)
            assertEquals("https://example.invalid/ubuntu-r1-rc5-desktop.tar.zst", entry.url)
            marker.writeText(entry.version)
            assertTrue("the marker the install writes must satisfy desktopInstalled", DesktopCatalog.desktopInstalled(root))
            marker.writeText("ubuntu-r1-rc5")
            assertTrue("a pack installed by the first probes is already complete", DesktopCatalog.desktopInstalled(root))
            marker.writeText("older-package")
            assertFalse(DesktopCatalog.desktopInstalled(root))
        } finally {
            marker.delete()
            pack.delete()
            manifest.delete()
            kwin.delete()
        }
    }

    @Test fun anInterruptedInstallIsFoundAgainWithEverythingNeededToFinishIt() {
        val root = temporary.root
        val entry = DesktopCatalog.Entry(
            DesktopCatalog.DESKTOP_ID, "Desktop", 1, "desktop-kde-r2", "tar",
            "https://example.invalid/desktop-kde.tar.zst", "a".repeat(64), 846933224L, "", "", "",
        )
        assertTrue(DesktopCatalog.interrupted(root).isEmpty())
        File(root, ".droiddeck-pkg-${entry.id}.installing").writeText(DesktopCatalog.entryJson(entry).toString())
        File(root, ".droiddeck-pkg-emulators.installing").writeText("not json")
        File(root, ".droiddeck-pkg-emulators").writeText("1")

        val found = DesktopCatalog.interrupted(root).single()

        assertEquals(entry.id, found.id)
        assertEquals(entry.version, found.version)
        assertEquals(entry.url, found.url)
        assertEquals(entry.sha256, found.sha256)
        assertEquals(entry.size, found.size)
        assertEquals(entry.kind, found.kind)
    }
}

