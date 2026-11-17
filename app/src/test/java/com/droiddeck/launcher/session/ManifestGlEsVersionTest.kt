package com.droiddeck.launcher.session

import java.io.File
import org.junit.Assert.assertTrue
import org.junit.Test

class ManifestGlEsVersionTest {
    @Test fun theManifestAsksForTheGlesTheMobileShellDrawsWith() {
        // Plasma's scene graph checks the app's GLES feature before anything boots; without it the Mobile
        // shell has no OpenGL surface to draw into and boots into the plain software path.
        val manifest = File("src/main/AndroidManifest.xml").readText()
        assertTrue(Regex("<uses-feature android:glEsVersion=\"0x00030000\" android:required=\"true\" ?/>").containsMatchIn(manifest))
    }
}
