package com.urreal3d.adspotter

import android.accessibilityservice.AccessibilityService
import android.accessibilityservice.GestureDescription
import android.graphics.Path
import android.graphics.Rect
import android.os.Handler
import android.os.Looper
import android.os.PowerManager
import android.os.SystemClock
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo
import android.widget.Toast
import java.io.File
import kotlin.random.Random

class SpotterService : AccessibilityService() {

    companion object {
        const val IG = "com.instagram.android"

        @Volatile var instance: SpotterService? = null
        @Volatile var running = false
        @Volatile var status = "Idle"

        // Words that look like usernames but are buttons/labels.
        private val BAD = setOf(
            "follow", "following", "sponsored", "learn more", "shop now", "sign up", "install now",
            "book now", "download", "like", "comment", "share", "more", "reels", "audio",
            "original audio", "message", "send message", "watch more", "get offer", "apply now",
            "contact us", "subscribe", "see more", "visit profile", "send", "save", "remix"
        )
        private val USER = Regex("^[A-Za-z0-9._]{2,30}$")
        private val BLOCK = Regex(
            "try again later|action blocked|confirm it.s you|suspicious activity|we restrict certain activity",
            RegexOption.IGNORE_CASE
        )
    }

    private class N(val text: String, val desc: String, val id: String, val r: Rect, val visible: Boolean)

    private val h = Handler(Looper.getMainLooper())
    private var endAt = 0L
    private var reelStart = 0L
    private var deadline = 0L
    private var offCount = 0
    private var capturedThisReel = false
    private var wake: PowerManager.WakeLock? = null

    override fun onServiceConnected() {
        super.onServiceConnected()
        instance = this
    }

    override fun onUnbind(intent: android.content.Intent?): Boolean {
        instance = null
        running = false
        h.removeCallbacksAndMessages(null)
        releaseWake()
        return super.onUnbind(intent)
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {}
    override fun onInterrupt() {}

    // ---------------------------------------------------------------- control

    fun start() {
        val c = applicationContext
        val mins = Store.int(c, "mins", 30).coerceIn(1, 240)
        running = true
        offCount = 0
        endAt = SystemClock.elapsedRealtime() + mins * 60_000L + 8_000L
        status = "Starting in 8s - open the Reels tab now"
        acquireWake(mins * 60_000L + 30_000L)
        h.removeCallbacksAndMessages(null)
        newReel(8_000L)
        h.postDelayed({ tick() }, 8_000L)
    }

    fun stop(reason: String) {
        running = false
        status = reason
        h.removeCallbacksAndMessages(null)
        releaseWake()
        toast(reason)
    }

    private fun newReel(delayMs: Long) {
        val c = applicationContext
        val minW = Store.int(c, "minW", 5).coerceAtLeast(2)
        val maxW = Store.int(c, "maxW", 12).coerceAtLeast(minW)
        var extra = 0L
        if (Random.nextInt(100) < 8) extra = Random.nextLong(6_000L, 15_000L) // occasional longer look
        reelStart = SystemClock.elapsedRealtime() + delayMs
        deadline = reelStart + Random.nextLong(minW * 1000L, maxW * 1000L + 1) + extra
        capturedThisReel = false
    }

    // ------------------------------------------------------------------- loop

    private fun tick() {
        if (!running) return
        val now = SystemClock.elapsedRealtime()
        if (now > endAt) { stop("Time limit reached"); return }

        val root = rootInActiveWindow
        if (root == null || root.packageName?.toString() != IG) {
            offCount++
            if (offCount >= 10) { stop("Instagram left the screen. Stopped."); return }
            status = "Waiting for Instagram..."
            h.postDelayed({ tick() }, 1500L)
            return
        }
        offCount = 0

        if (now < reelStart) { h.postDelayed({ tick() }, 400L); return }

        val nodes = flatten(root)
        if (nodes.any { it.text.length < 120 && BLOCK.containsMatchIn(it.text) }) {
            stop("Instagram showed a warning. Stopped.")
            return
        }

        if (!capturedThisReel) {
            val user = findAdUser(nodes)
            if (user != null) {
                val isNew = Store.add(applicationContext, user)
                capturedThisReel = true
                status = "Ad: @$user" + if (isNew) " (new)" else " (seen before)"
                deadline = minOf(deadline, now + 1_500L) // got it, move on
            } else {
                status = "Watching reel..."
            }
        }

        if (now >= deadline) {
            swipe()
            newReel(1_200L + Random.nextLong(0L, 600L))
        }
        h.postDelayed({ tick() }, 1_000L)
    }

    // -------------------------------------------------------------- detection

    private fun flatten(root: AccessibilityNodeInfo): List<N> {
        val out = ArrayList<N>()
        fun walk(n: AccessibilityNodeInfo?, d: Int) {
            if (n == null || d > 40) return
            val r = Rect()
            n.getBoundsInScreen(r)
            out.add(
                N(
                    n.text?.toString()?.trim() ?: "",
                    n.contentDescription?.toString()?.trim() ?: "",
                    n.viewIdResourceName ?: "",
                    r,
                    n.isVisibleToUser
                )
            )
            for (i in 0 until n.childCount) walk(n.getChild(i), d + 1)
        }
        walk(root, 0)
        return out
    }

    private fun isSponsoredExact(t: String) = t.equals("Sponsored", ignoreCase = true)

    private fun isSponsoredCombo(t: String) =
        t.length in 10..60 && t.contains("sponsored", ignoreCase = true) &&
            listOf("•", "·", "|", "\n").any { t.contains(it) }

    private fun okUser(t: String): Boolean {
        if (!USER.matches(t)) return false
        if (t.lowercase() in BAD) return false
        if (t.all { it.isDigit() }) return false
        return true
    }

    private fun findAdUser(nodes: List<N>): String? {
        val dm = resources.displayMetrics
        fun dp(v: Int) = (v * dm.density).toInt()

        val spons = nodes.filter {
            it.visible && (isSponsoredExact(it.text) || isSponsoredExact(it.desc) ||
                isSponsoredCombo(it.text) || isSponsoredCombo(it.desc))
        }

        for (s in spons) {
            // Case 1: "username • Sponsored" inside one node.
            val combo = listOf(s.text, s.desc).firstOrNull { isSponsoredCombo(it) }
            if (combo != null) {
                val first = combo.split("•", "·", "|", "\n").first().trim().removePrefix("@")
                if (okUser(first)) return first
            }

            // Case 2: username is a separate node above or on the same row as the label.
            var best: N? = null
            var bestScore = Int.MAX_VALUE
            for (c in nodes) {
                if (!c.visible || !okUser(c.text)) continue
                val sameCol = Math.abs(c.r.left - s.r.left) < dp(40) &&
                    c.r.bottom <= s.r.top + dp(8) && c.r.bottom >= s.r.top - dp(110)
                val sameRow = Math.abs(c.r.centerY() - s.r.centerY()) < dp(20) &&
                    c.r.right <= s.r.left + dp(12)
                if (!sameCol && !sameRow) continue
                var score = Math.abs(c.r.centerY() - s.r.centerY()) + Math.abs(c.r.left - s.r.left)
                if (c.id.contains("username", true) || c.id.contains("author", true)) score -= dp(200)
                if (score < bestScore) { bestScore = score; best = c }
            }
            if (best != null) return best.text.removePrefix("@")
        }
        return null
    }

    // ---------------------------------------------------------------- gesture

    private fun swipe() {
        val dm = resources.displayMetrics
        val w = dm.widthPixels.toFloat()
        val hh = dm.heightPixels.toFloat()
        val x = (w * Random.nextDouble(0.42, 0.58)).toFloat()
        val y1 = (hh * Random.nextDouble(0.72, 0.80)).toFloat()
        val y2 = (hh * Random.nextDouble(0.22, 0.32)).toFloat()
        val p = Path()
        p.moveTo(x, y1)
        p.lineTo(x + Random.nextInt(-40, 40), y2)
        val g = GestureDescription.Builder()
            .addStroke(GestureDescription.StrokeDescription(p, 0L, Random.nextLong(220L, 380L)))
            .build()
        dispatchGesture(g, null, null)
    }

    // -------------------------------------------------------------- inspector

    fun dumpIn(ms: Long) {
        h.postDelayed({
            val root = rootInActiveWindow
            if (root == null) {
                toast("Dump failed: Instagram is not on screen")
            } else {
                val sb = StringBuilder()
                sb.append("package=").append(root.packageName).append('\n')
                dumpNode(root, 0, sb)
                File(getExternalFilesDir(null), "dump.txt").writeText(sb.toString())
                toast("Screen dump saved. Open Ad Spotter and tap Share last dump")
            }
        }, ms)
    }

    private fun dumpNode(n: AccessibilityNodeInfo?, d: Int, sb: StringBuilder) {
        if (n == null || d > 40) return
        val r = Rect()
        n.getBoundsInScreen(r)
        sb.append("  ".repeat(d)).append(n.className?.toString()?.substringAfterLast('.') ?: "?")
        n.viewIdResourceName?.let { sb.append(" id=").append(it.substringAfter(":id/")) }
        if (!n.text.isNullOrEmpty()) sb.append(" text=\"").append(n.text).append("\"")
        if (!n.contentDescription.isNullOrEmpty()) sb.append(" desc=\"").append(n.contentDescription).append("\"")
        sb.append(" [").append(r.left).append(',').append(r.top).append(',').append(r.right).append(',').append(r.bottom).append(']')
        if (!n.isVisibleToUser) sb.append(" hidden")
        sb.append('\n')
        for (i in 0 until n.childCount) dumpNode(n.getChild(i), d + 1, sb)
    }

    // ---------------------------------------------------------------- helpers

    @Suppress("DEPRECATION")
    private fun acquireWake(ms: Long) {
        releaseWake()
        val pm = getSystemService(POWER_SERVICE) as PowerManager
        wake = pm.newWakeLock(PowerManager.SCREEN_DIM_WAKE_LOCK or PowerManager.ACQUIRE_CAUSES_WAKEUP, "adspotter:run")
        wake?.acquire(ms)
    }

    private fun releaseWake() {
        try { if (wake?.isHeld == true) wake?.release() } catch (_: Exception) {}
        wake = null
    }

    private fun toast(m: String) {
        Toast.makeText(applicationContext, m, Toast.LENGTH_LONG).show()
    }
}
