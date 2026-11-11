package com.urreal3d.adspotter

import android.content.Context

/** status: "new", "sent" or "failed" */
data class Entry(
    val user: String,
    val firstSeen: Long,
    val count: Int,
    val status: String = "new",
    val sentAt: Long = 0L,
    val note: String = ""
)

object Store {
    private const val P = "spotter"
    private val USER = Regex("^[a-z0-9._]{1,30}$")

    private fun sp(c: Context) = c.applicationContext.getSharedPreferences(P, Context.MODE_PRIVATE)

    private fun clean(s: String) = s.replace(',', ' ').replace('\n', ' ').replace('\r', ' ').trim().take(80)

    @Synchronized
    fun all(c: Context): List<Entry> =
        (sp(c).getString("items", "") ?: "").lines().mapNotNull {
            val p = it.split(",")
            if (p.size >= 3 && p[0].isNotBlank()) {
                Entry(
                    user = p[0],
                    firstSeen = p[1].toLongOrNull() ?: 0L,
                    count = p[2].toIntOrNull() ?: 1,
                    status = if (p.size > 3 && p[3].isNotBlank()) p[3] else "new",
                    sentAt = if (p.size > 4) (p[4].toLongOrNull() ?: 0L) else 0L,
                    note = if (p.size > 5) p[5] else ""
                )
            } else null
        }

    private fun save(c: Context, l: List<Entry>) {
        sp(c).edit().putString(
            "items",
            l.joinToString("\n") { "${it.user},${it.firstSeen},${it.count},${it.status},${it.sentAt},${clean(it.note)}" }
        ).apply()
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
    fun setStatus(c: Context, user: String, status: String, note: String) {
        val l = all(c).toMutableList()
        val i = l.indexOfFirst { it.user == user }
        if (i < 0) return
        l[i] = l[i].copy(
            status = status,
            note = note,
            sentAt = if (status == "sent") System.currentTimeMillis() else l[i].sentAt
        )
        save(c, l)
    }

    @Synchronized
    fun resetFailed(c: Context): Int {
        val l = all(c).toMutableList()
        var n = 0
        for (i in l.indices) {
            if (l[i].status == "failed") { l[i] = l[i].copy(status = "new", note = ""); n++ }
        }
        save(c, l)
        return n
    }

    @Synchronized
    fun delete(c: Context, users: Set<String>) {
        save(c, all(c).filter { it.user !in users })
    }

    @Synchronized
    fun clear(c: Context) {
        sp(c).edit().remove("items").apply()
    }

    fun text(c: Context): String = all(c).joinToString("\n") { it.user }

    fun csv(c: Context): String {
        val f = java.text.SimpleDateFormat("yyyy-MM-dd HH:mm", java.util.Locale.US)
        return "username,profile_url,first_seen,times_seen,status,sent_at,note\r\n" + all(c).joinToString("") {
            val sent = if (it.sentAt > 0) f.format(java.util.Date(it.sentAt)) else ""
            "${it.user},https://www.instagram.com/${it.user}/,${f.format(java.util.Date(it.firstSeen))},${it.count},${it.status},$sent,${clean(it.note)}\r\n"
        }
    }

    fun int(c: Context, key: String, def: Int): Int = sp(c).getInt(key, def)

    fun setInt(c: Context, key: String, v: Int) {
        sp(c).edit().putInt(key, v).apply()
    }

    fun str(c: Context, key: String, def: String): String = sp(c).getString(key, def) ?: def

    fun setStr(c: Context, key: String, v: String) {
        sp(c).edit().putString(key, v).apply()
    }
}
