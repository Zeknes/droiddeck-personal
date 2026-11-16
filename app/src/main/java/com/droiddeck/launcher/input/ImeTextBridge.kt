package com.droiddeck.launcher.input

import android.content.Context
import android.util.Log
import com.droiddeck.launcher.agent.AgentGuest
import org.json.JSONObject
import java.io.File
import java.io.FileOutputStream

// Characters the key map cannot type (CJK and the like) reach the desktop as text:
// droiddeck-ime in the guest commits them through KWin's input-method context.
internal object ImeTextBridge {
    private const val TAG = "ImeTextBridge"
    private val lock = Any()
    private var seq = 0L

    fun send(context: Context, text: String): Boolean {
        if (text.isEmpty()) return false
        val dir = AgentGuest.dir(context)
        if (!dir.isDirectory) return false
        synchronized(lock) {
            val temp = File(dir, "ime-text.json.tmp")
            val target = File(dir, "ime-text.json")
            return try {
                seq = maxOf(seq + 1, System.currentTimeMillis())
                val payload = JSONObject().put("text", text).put("seq", seq).toString()
                FileOutputStream(temp).use { out ->
                    out.write(payload.toByteArray(Charsets.UTF_8))
                    out.fd.sync()
                }
                temp.setReadable(false, false)
                temp.setWritable(false, false)
                temp.setReadable(true, true)
                temp.setWritable(true, true)
                if (temp.renameTo(target)) true else {
                    temp.delete()
                    false
                }
            } catch (e: Exception) {
                Log.w(TAG, "could not hand text to the desktop", e)
                temp.delete()
                false
            }
        }
    }
}
