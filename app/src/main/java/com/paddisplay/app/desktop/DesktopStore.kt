package com.paddisplay.app.desktop

import android.content.Context
import com.paddisplay.app.display.DisplaySnapshot

class DesktopStore(context: Context) {
    private val prefs = context.getSharedPreferences("desktop_preferences", Context.MODE_PRIVATE)
    fun favorites(): Set<String> = prefs.getStringSet("favorites", emptySet())!!.toSet()
    fun toggleFavorite(pkg: String) {
        val next = favorites().toMutableSet()
        if (!next.add(pkg)) next.remove(pkg)
        prefs.edit().putStringSet("favorites", next).apply()
    }
    var freeform: Boolean
        get() = if (prefs.getInt("freeform_version", 0) < 2) true else prefs.getBoolean("freeform", true)
        set(value) { prefs.edit().putBoolean("freeform", value).putInt("freeform_version", 2).apply() }
    var autoReconnect: Boolean
        get() = prefs.getBoolean("reconnect", true)
        set(value) { prefs.edit().putBoolean("reconnect", value).apply() }
    var hideForGames: Boolean
        get() = prefs.getBoolean("hide_games", true)
        set(value) { prefs.edit().putBoolean("hide_games", value).apply() }
    fun displayKey(d: DisplaySnapshot) = "${d.name}|${d.address}|${d.physicalDisplayId}"
    fun saveScale(d: DisplaySnapshot, percent: Int) {
        check(prefs.edit().putInt("scale:${displayKey(d)}", percent).commit())
    }
    fun scale(d: DisplaySnapshot): Int? = prefs.getInt("scale:${displayKey(d)}", 0).takeIf { it in 75..200 }
    fun clearScale(d: DisplaySnapshot) { prefs.edit().remove("scale:${displayKey(d)}").apply() }
}
