package com.droiddeck.launcher.session

import org.json.JSONObject

/** The bridge's newline-delimited JSON state, kept free of Android classes so it is unit testable. */
class GnomeImeProtocol {
    data class State(
        val generation: Int,
        val connected: Boolean,
        val inner: Boolean,
        val outer: Boolean,
        val sensitive: Boolean,
        val shell: Boolean,
        val ready: Boolean,
    )

    private var generation = -1
    private var ready = false

    fun state(line: String): State? {
        val json = try { JSONObject(line) } catch (e: Exception) { return null }
        if (json.optString("type") != "state") return null
        val generation = json.optInt("generation", -1)
        if (generation < 0) return null
        this.generation = generation
        ready = json.optBoolean("ready")
        return State(
            generation = generation,
            connected = json.optBoolean("connected"),
            inner = json.optBoolean("inner"),
            outer = json.optBoolean("outer"),
            sensitive = json.optBoolean("sensitive"),
            shell = json.optBoolean("shell"),
            ready = ready,
        )
    }

    fun focus(): String? = message("focus")
    fun blur(): String? = message("blur")

    fun preedit(text: String, cursor: Int): String? {
        if (!ready) return null
        return message("preedit", JSONObject().put("text", text).put("cursor", cursor))
    }

    fun commit(text: String): String? {
        if (!ready || text.isEmpty()) return null
        return message("commit", JSONObject().put("text", text))
    }

    private fun message(type: String, extra: JSONObject? = null): String? {
        if (generation < 0) return null
        val json = JSONObject().put("type", type).put("generation", generation)
        extra?.keys()?.forEach { key -> json.put(key, extra.get(key)) }
        return json.toString()
    }
}
