package com.d3dclone.viewer

import android.content.Context
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import org.json.JSONArray
import org.json.JSONObject

/** Favourites, recent destinations and the home address, kept on the phone. */
object PlaceBook {
    val favs = mutableStateListOf<Router.Place>()
    val recents = mutableStateListOf<Router.Place>()
    var home by mutableStateOf<Router.Place?>(null)
    private var ctx: Context? = null

    private fun same(a: Router.Place, b: Router.Place) = Math.abs(a.lat - b.lat) < 0.0002 && Math.abs(a.lon - b.lon) < 0.0002

    fun load(c: Context) {
        ctx = c.applicationContext
        val p = c.getSharedPreferences("d3d", Context.MODE_PRIVATE)
        favs.clear(); recents.clear()
        favs.addAll(read(p.getString("fav", null)))
        recents.addAll(read(p.getString("recent", null)))
        home = read(p.getString("home", null)).firstOrNull()
    }

    private fun read(s: String?): List<Router.Place> = runCatching {
        val a = JSONArray(s ?: return emptyList())
        List(a.length()) { val o = a.getJSONObject(it); Router.Place(o.getString("n"), o.getDouble("la"), o.getDouble("lo")) }
    }.getOrDefault(emptyList())

    private fun write(list: List<Router.Place>): String {
        val a = JSONArray()
        for (p in list) a.put(JSONObject().put("n", p.name).put("la", p.lat).put("lo", p.lon))
        return a.toString()
    }

    private fun save() {
        val c = ctx ?: return
        c.getSharedPreferences("d3d", Context.MODE_PRIVATE).edit()
            .putString("fav", write(favs)).putString("recent", write(recents))
            .putString("home", home?.let { write(listOf(it)) } ?: "[]").apply()
    }

    fun isFav(p: Router.Place) = favs.any { same(it, p) }
    fun isHome(p: Router.Place) = home?.let { same(it, p) } == true

    fun toggleFav(p: Router.Place) {
        val i = favs.indexOfFirst { same(it, p) }
        if (i >= 0) { if (isHome(p)) home = null; favs.removeAt(i) } else favs.add(0, p)
        save()
    }

    /** Marks a place as home (and makes it a favourite). */
    fun markHome(p: Router.Place) {
        if (!isFav(p)) favs.add(0, p)
        home = favs.first { same(it, p) }
        save()
    }

    fun addRecent(p: Router.Place) {
        recents.removeAll { same(it, p) }
        recents.add(0, p)
        while (recents.size > 12) recents.removeAt(recents.size - 1)
        save()
    }
}
