package com.urreal3d.adspotter

import android.content.Context

data class Entry(val user: String, val firstSeen: Long, val count: Int)

object Store {
    private const val P = "spotter"
    private val USER = Regex("^[a-z0-9._]{1,30}$")

    private fun sp(c: Context) = c.applicationContext.getSharedPreferences(P, Context.MODE_PRIVATE)

    @Synchronized
    fun all(c: Context): List<Entry> =
        (sp(c).getString("items", "") ?: "").lines().mapNotNull {
            val p = it.split(",")
            if (p.size == 3) Entry(p[0], p[1].toLongOrNull() ?: 0L, p[2].toIntOrNull() ?: 1) else null
        }

    private fun save(c: Context, l: List<Entry>) {
        sp(c).edit().putString("items", l.joinToString("\n") { "${it.user},${it.firstSeen},${it.count}" }).apply()
    }

    /** Returns true when the username was not stored before. */
    @Synchronized
    fun add(c: Context, raw: String): Boolean {
        val u = raw.trim().removePrefix("@").lowercase()
        if (!USER.matches(u)) return false
        val l = all(c).toMutableList()
        val i = l.indexOfFirst { it.user == u }
        return if (i >= 0) {
            l[i] = l[i].copy(count = l[i].count + 1)
            save(c, l)
            false
        } else {
            l.add(Entry(u, System.currentTimeMillis(), 1))
            save(c, l)
            true
        }
    }

    @Synchronized
    fun clear(c: Context) {
        sp(c).edit().remove("items").apply()
    }

    fun text(c: Context): String = all(c).joinToString("\n") { it.user }

    fun csv(c: Context): String =
        "username,first_seen,times_seen\n" + all(c).joinToString("\n") {
            "${it.user},${java.text.SimpleDateFormat("yyyy-MM-dd HH:mm", java.util.Locale.US).format(java.util.Date(it.firstSeen))},${it.count}"
        }

    fun int(c: Context, key: String, def: Int): Int = sp(c).getInt(key, def)

    fun setInt(c: Context, key: String, v: Int) {
        sp(c).edit().putInt(key, v).apply()
    }
}
