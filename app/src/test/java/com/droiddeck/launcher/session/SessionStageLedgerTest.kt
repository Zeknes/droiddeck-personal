package com.droiddeck.launcher.session

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.nio.file.Files

class SessionStageLedgerTest {
    @Test fun aFileTheAppStagedIsNotStagedAgain() {
        val dir = Files.createTempDirectory("stage").toFile()
        val target = File(dir, "bin/shell").apply { parentFile.mkdirs(); writeText("app") }
        val ledgerFile = File(dir, ".droiddeck-staged")
        SessionFiles.ledgerForTest(ledgerFile, "identity").apply { staged("bin/shell", target); save() }
        assertTrue(SessionFiles.ledgerForTest(ledgerFile, "identity").current("bin/shell", target))
    }

    @Test fun aPackThatOverwroteTheFileIsStagedAgain() {
        val dir = Files.createTempDirectory("stage").toFile()
        val target = File(dir, "bin/shell").apply { parentFile.mkdirs(); writeText("app") }
        val ledgerFile = File(dir, ".droiddeck-staged")
        SessionFiles.ledgerForTest(ledgerFile, "identity").apply { staged("bin/shell", target); save() }
        target.writeText("pack")
        assertFalse("the pack's copy differs from the app's", SessionFiles.ledgerForTest(ledgerFile, "identity").current("bin/shell", target))
    }

    @Test fun afterAPackExtractionTheNextSessionRestagesEveryFile() {
        val dir = Files.createTempDirectory("stage").toFile()
        val target = File(dir, "bin/shell").apply { parentFile.mkdirs(); writeText("app") }
        val ledgerFile = File(dir, ".droiddeck-staged")
        SessionFiles.ledgerForTest(ledgerFile, "identity").apply { staged("bin/shell", target); save() }
        SessionFiles.ledgerForTest(ledgerFile, "identity", fromPack = true).apply { staged("bin/shell", target); save() }
        assertEquals("identity", ledgerFile.readLines().first())
        assertEquals(1, ledgerFile.readLines().size)
        assertFalse(SessionFiles.ledgerForTest(ledgerFile, "identity").current("bin/shell", target))
    }

    @Test fun everyOtherDesktopSessionStillTakesItsFilesAsCurrent() {
        val dir = Files.createTempDirectory("stage").toFile()
        val target = File(dir, "bin/shell").apply { parentFile.mkdirs(); writeText("app") }
        val ledgerFile = File(dir, ".droiddeck-staged")
        SessionFiles.ledgerForTest(ledgerFile, "identity").apply { staged("bin/shell", target); save() }
        SessionFiles.ledgerForTest(ledgerFile, "identity", fromPack = false).apply { assertTrue(current("bin/shell", target)); save() }
        assertTrue(SessionFiles.ledgerForTest(ledgerFile, "identity").current("bin/shell", target))
    }

    @Test fun anotherApkStagesEverythingAgain() {
        val dir = Files.createTempDirectory("stage").toFile()
        val target = File(dir, "bin/shell").apply { parentFile.mkdirs(); writeText("app") }
        val ledgerFile = File(dir, ".droiddeck-staged")
        SessionFiles.ledgerForTest(ledgerFile, "old").apply { staged("bin/shell", target); save() }
        assertFalse(SessionFiles.ledgerForTest(ledgerFile, "new").current("bin/shell", target))
    }
}
