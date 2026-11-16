package com.droiddeck.launcher.session

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.File

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33])
class GnomeLaunchReportTest {
    @get:Rule val tmp = TemporaryFolder()

    @Test fun readsTheStageNameAndItsExitFromTheLoggedLine() {
        assertEquals(GnomeLaunchReport.Stage("session-bus", "ok"), GnomeLaunchReport.parse("== GNOME STAGE session-bus exit=ok"))
        assertEquals(GnomeLaunchReport.Stage("shell", "starting"), GnomeLaunchReport.parse(" == GNOME STAGE shell exit=starting"))
        assertEquals(GnomeLaunchReport.Stage("shell", "1"), GnomeLaunchReport.parse("== GNOME STAGE shell exit=1"))
    }

    @Test fun readsTheStageFromThePackFile() {
        assertEquals(
            GnomeLaunchReport.Stage("session-bus", "ok"),
            GnomeLaunchReport.parseJson("""{"stage":"session-bus","exit":"ok"}"""),
        )
        assertEquals(
            GnomeLaunchReport.Stage("extension", "failed"),
            GnomeLaunchReport.parseJson("""{"stage":"extension","exit":"failed"}"""),
        )
        assertNull(GnomeLaunchReport.parseJson("""{"stage":"","exit":"ok"}"""))
        assertNull(GnomeLaunchReport.parseJson("not json"))
    }

    @Test fun ignoresLinesThatAreNotStages() {
        assertNull(GnomeLaunchReport.parse("== DroidDeck GNOME on inner"))
        assertNull(GnomeLaunchReport.parse("== GNOME STAGE"))
        assertNull(GnomeLaunchReport.parse("== GNOME STAGE shell"))
        assertNull(GnomeLaunchReport.parse("session-bus ok"))
        assertEquals(
            listOf("session-bus", "input-bridge"),
            GnomeLaunchReport.stages("""
                startup
                == GNOME STAGE session-bus exit=ok
                == DroidDeck GNOME on inner
                == GNOME STAGE input-bridge exit=ok
            """.trimIndent().lineSequence()).map { it.name },
        )
    }

    @Test fun prefersThePackStageFileOverTheSessionLog() {
        val session = tmp.newFolder("2026-11-16-01-desktop")
        File(session, "gnome-stage.jsonl").writeText(
            """{"stage":"session-bus","exit":"ok"}""" + "\n" + """{"stage":"shell","exit":"starting"}""" + "\n",
        )
        File(session, "session.log").writeText("== GNOME STAGE shell exit=1\n")
        val report = GnomeLaunchReport.report(session, tmp.newFolder("sockets"))
        assertEquals(listOf("session-bus", "shell"), report.stages.map { it.name })
        assertEquals("starting", report.stageExit)
    }

    @Test fun fallsBackToTheSessionLogWhenThePackWritesNoStageFile() {
        val session = tmp.newFolder("2026-11-16-02-desktop")
        File(session, "session.log").writeText("startup\n== GNOME STAGE session-bus exit=failed\n")
        val report = GnomeLaunchReport.report(session, tmp.newFolder("sockets2"))
        assertEquals("session-bus", report.failure?.name)
        assertEquals("failed", report.failure?.exit)
    }

    @Test fun theStageTailKeepsTheLastLines() {
        val log = File(tmp.newFolder("tail"), "session.log")
        log.writeText(("x".repeat(1000) + "\n").repeat(10) + "== GNOME STAGE shell exit=139\n")
        assertEquals(
            listOf(GnomeLaunchReport.Stage("shell", "139")),
            GnomeLaunchReport.logStages(log, maxBytes = 64),
        )
    }

    @Test fun aFailedStageOrANonZeroShellExitIsTheFailure() {
        assertNull(GnomeLaunchReport.failure(GnomeLaunchReport.stages(listOf(
            "== GNOME STAGE session-bus exit=ok",
            "== GNOME STAGE input-bridge exit=ok",
            "== GNOME STAGE shell exit=starting",
        ).asSequence())))
        assertEquals("session-bus", GnomeLaunchReport.failure(GnomeLaunchReport.stages(listOf(
            "== GNOME STAGE session-bus exit=failed",
        ).asSequence()))?.name)
        assertEquals("1", GnomeLaunchReport.failure(GnomeLaunchReport.stages(listOf(
            "== GNOME STAGE shell exit=starting", "== GNOME STAGE shell exit=1",
        ).asSequence()))?.exit)
        assertNull(GnomeLaunchReport.failure(GnomeLaunchReport.stages(listOf(
            "== GNOME STAGE shell exit=starting", "== GNOME STAGE shell exit=0",
        ).asSequence())))
    }

    @Test fun theReportNamesTheSocketsTheSessionDidAndDidNotCreate() {
        val runtime = tmp.newFolder("wayland-rt")
        File(runtime, "wayland-0").createNewFile()
        File(runtime, "ibus.sock").createNewFile()
        val report = GnomeLaunchReport.report(
            GnomeLaunchReport.stages(listOf("== GNOME STAGE shell exit=starting").asSequence()), runtime,
        )
        assertFalse(report.innerUp)
        assertTrue("wayland-0" in report.present)
        assertTrue("inner" in report.missing)
        assertTrue("pipewire-0" in report.missing)
        assertEquals("missing session-bus.sock, pipewire-0, droiddeck-ime.sock, inner", GnomeLaunchReport.detail(report))
        assertEquals("shell", report.stageName)
        assertEquals("starting", report.stageExit)
    }

    @Test fun aStallIsNoFrameNoInnerSocketAndNoReportedFailure() {
        val runtime = tmp.newFolder("stall")
        val stages = GnomeLaunchReport.stages(listOf("== GNOME STAGE shell exit=starting").asSequence())
        assertTrue(GnomeLaunchReport.stalled(GnomeLaunchReport.report(stages, runtime), firstFrameSeen = false))
        assertFalse(GnomeLaunchReport.stalled(GnomeLaunchReport.report(stages, runtime), firstFrameSeen = true))
        File(runtime, GnomeLaunchReport.INNER).createNewFile()
        assertFalse(GnomeLaunchReport.stalled(GnomeLaunchReport.report(stages, runtime), firstFrameSeen = false))
    }

    @Test fun theDetailCarriesAStageExitWhenThereIsOne() {
        val runtime = tmp.newFolder("detail")
        File(runtime, GnomeLaunchReport.INNER).createNewFile()
        val report = GnomeLaunchReport.report(
            GnomeLaunchReport.stages(listOf("== GNOME STAGE shell exit=1").asSequence()), runtime,
        )
        assertEquals("exit 1", GnomeLaunchReport.detail(report))
    }

    @Test fun aSessionWithNoStageIsUnreachedOnlyWhenThePackIsAbsentOrTheLauncherSaidSo() {
        assertFalse(GnomeLaunchReport.unreached(emptyList(), emptyList(), 1))
        assertTrue(GnomeLaunchReport.unreached(emptyList(), listOf("usr/bin/gnome-shell"), 1))
        assertTrue(GnomeLaunchReport.unreached(emptyList(), emptyList(), 66))
        assertFalse(GnomeLaunchReport.unreached(
            listOf(GnomeLaunchReport.Stage("session-bus", "ok")), listOf("usr/bin/gnome-shell"), 66,
        ))
    }

    @Test fun missingFilesListWhatTheDesktopPackShouldHaveInstalled() {
        val root = tmp.newFolder("root")
        assertEquals(
            listOf(
                "usr/bin/gnome-shell",
                "usr/libexec/mutter-devkit",
                "usr/local/bin/droiddeck-gnome-desktop",
                "usr/local/bin/droiddeck-gnome-extension",
                "usr/local/share/droiddeck/gnome-input/droiddeck-ime-bridge",
                "usr/local/share/droiddeck/gnome-input/extension/extension.js",
            ),
            GnomeLaunchReport.missingFiles(root),
        )
        File(root, "usr/bin").mkdirs()
        File(root, "usr/bin/gnome-shell").createNewFile()
        assertFalse("usr/bin/gnome-shell" in GnomeLaunchReport.missingFiles(root))
    }
}
