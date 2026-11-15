package com.droiddeck.launcher.stores

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Color
import org.json.JSONObject
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import java.io.ByteArrayOutputStream
import java.io.File

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33])
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class StoreLogoTest {
    @get:Rule val tmp = TemporaryFolder()

    private fun encoded(format: Bitmap.CompressFormat): ByteArray {
        val bmp = Bitmap.createBitmap(8, 4, Bitmap.Config.ARGB_8888)
        bmp.eraseColor(Color.TRANSPARENT)
        bmp.setPixel(1, 1, Color.RED)
        return ByteArrayOutputStream().also { bmp.compress(format, 100, it) }.toByteArray()
    }

    @Test fun aPngLogoIsKeptAsItCameWithItsTransparency() {
        val png = encoded(Bitmap.CompressFormat.PNG)
        val dst = File(tmp.root, StoreArt.LOGO)
        assertTrue(StoreArt.writeLogo(png, dst))
        assertArrayEquals(png, dst.readBytes())
        val back = BitmapFactory.decodeFile(dst.path)
        assertEquals(0, Color.alpha(back.getPixel(0, 0)))
        assertEquals(Color.RED, back.getPixel(1, 1))
        assertFalse(File(dst.path + ".tmp").exists())
    }

    @Test fun anotherImageFormatIsStoredAsPng() {
        val dst = File(tmp.root, StoreArt.LOGO)
        assertTrue(StoreArt.writeLogo(encoded(Bitmap.CompressFormat.JPEG), dst))
        val head = dst.readBytes().take(4)
        assertEquals(listOf(0x89.toByte(), 'P'.code.toByte(), 'N'.code.toByte(), 'G'.code.toByte()), head)
        assertEquals(8, BitmapFactory.decodeFile(dst.path).width)
    }

    @Test fun whatIsNotAnImageIsRefusedAndAnExistingLogoStays() {
        val dst = File(tmp.root, StoreArt.LOGO)
        assertFalse(StoreArt.writeLogo("<html>not found</html>".toByteArray(), dst))
        assertFalse(dst.exists())
        dst.writeBytes(byteArrayOf(1, 2, 3))
        assertTrue(StoreArt.writeLogo(encoded(Bitmap.CompressFormat.PNG), dst))
        assertArrayEquals(byteArrayOf(1, 2, 3), dst.readBytes())
        assertTrue(StoreArt.fetchLogo(tmp.root, "https://invalid.example/logo.png"))
    }
}
