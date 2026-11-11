package com.urreal3d.adspotter

import android.accessibilityservice.AccessibilityService
import android.accessibilityservice.GestureDescription
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Intent
import android.graphics.Path
import android.graphics.Rect
import android.net.Uri
import android.os.Build
import android.os.Bundle
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
        @Volatile var sending = false
        @Volatile var sendStatus = "Idle"
        @Volatile var sendProgress = ""

        // Words that look like usernames but are buttons/labels.
        private val BAD = setOf(
            "follow", "following", "sponsored", "learn more", "shop now", "sign up", "install now",
            "book now", "download", "like", "comment", "share", "more", "reels", "audio",
            "original audio", "message", "send message", "watch more", "get offer", "apply now",
            "contact us", "subscribe", "see more", "visit profile", "send", "save", "remix"
        )
        private val USER = Regex("^[A-Za-z0-9._]{2,30}$")
        private val UNAVAILABLE = Regex(
            "isn.t available|user not found|sorry, this page",
            RegexOption.IGNORE_CASE
        )
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
        if (sending) { toast("Stop sending first"); return }
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
        val minW = Store.int(c, "chkMin", 2).coerceAtLeast(1)
        val maxW = Store.int(c, "chkMax", 3).coerceAtLeast(minW)
        reelStart = SystemClock.elapsedRealtime() + delayMs
        deadline = reelStart + Random.nextLong(minW * 1000L, maxW * 1000L + 1)
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

        if (now < reelStart) { h.postDelayed({ tick() }, 250L); return }

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
                deadline = minOf(deadline, now + 350L) // got it, move on fast
            } else {
                status = "Checking reel for Ad label..."
            }
        }

        if (now >= deadline) {
            swipe()
            newReel(650L + Random.nextLong(0L, 250L))
        }
        h.postDelayed({ tick() }, 400L)
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

    private fun isFollowBtn(t: String) =
        t.equals("Follow", ignoreCase = true) || t.equals("Following", ignoreCase = true)

    private fun findAdUser(nodes: List<N>): String? {
        val dm = resources.displayMetrics
        fun dp(v: Int) = (v * dm.density).toInt()
        val sw = dm.widthPixels
        val sh = dm.heightPixels

        // Ad label: "Ad" in the bottom-right corner, or "Sponsored" anywhere.
        fun isAdLabel(n: N): Boolean {
            if (!n.visible) return false
            val exactAd = n.text.equals("Ad", true) || n.desc.equals("Ad", true)
            if (exactAd && n.r.left > sw * 0.6 && n.r.top > sh * 0.6) return true
            return isSponsoredExact(n.text) || isSponsoredExact(n.desc) ||
                isSponsoredCombo(n.text) || isSponsoredCombo(n.desc)
        }

        val labels = nodes.filter { isAdLabel(it) }
        if (labels.isEmpty()) return null

        // Case 1: "username • Sponsored" inside one node.
        for (s in labels) {
            val combo = listOf(s.text, s.desc).firstOrNull { isSponsoredCombo(it) }
            if (combo != null) {
                val first = combo.split("•", "·", "|", "\n").first().trim().removePrefix("@")
                if (okUser(first)) return first
            }
        }

        // Case 2: username sits left of the Follow button on the same row.
        val follows = nodes.filter { it.visible && (isFollowBtn(it.text) || isFollowBtn(it.desc)) }
        var best: N? = null
        var bestScore = Int.MAX_VALUE
        for (f in follows) {
            for (c in nodes) {
                if (!c.visible || !okUser(c.text)) continue
                if (Math.abs(c.r.centerY() - f.r.centerY()) > dp(30)) continue
                if (c.r.right > f.r.left + dp(12)) continue
                var score = f.r.left - c.r.right
                if (c.id.contains("username", true) || c.id.contains("author", true)) score -= dp(300)
                if (score < bestScore) { bestScore = score; best = c }
            }
        }
        if (best != null) return best.text.removePrefix("@")

        // Case 3: username-like text near the label (above it or on its row).
        for (s in labels) {
            for (c in nodes) {
                if (!c.visible || !okUser(c.text)) continue
                val above = c.r.bottom <= s.r.top + dp(8) && c.r.bottom >= s.r.top - dp(140)
                if (!above) continue
                val score = Math.abs(c.r.centerY() - s.r.centerY()) + Math.abs(c.r.left - s.r.left)
                if (score < bestScore) { bestScore = score; best = c }
            }
        }
        return best?.text?.removePrefix("@")
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
            .addStroke(GestureDescription.StrokeDescription(p, 0L, Random.nextLong(140L, 240L)))
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
        h.post { Toast.makeText(applicationContext, m, Toast.LENGTH_LONG).show() }
    }

    // ---------------------------------------------------------------- sending

    private class BlockedEx : Exception()
    private class SendResult(val ok: Boolean, val note: String, val blocked: Boolean = false)

    @Volatile private var sendStop = false

    fun startSend(users: List<String>) {
        if (sending || running) { toast("Stop the other task first"); return }
        if (users.isEmpty()) { toast("No leads to send to"); return }
        sending = true
        sendStop = false
        sendProgress = "0 of ${users.size}"
        sendStatus = "Starting..."
        Thread { runSend(users) }.start()
    }

    fun stopSend() {
        sendStop = true
        sendStatus = "Stopping..."
    }

    private fun runSend(users: List<String>) {
        val c = applicationContext
        val minWait = Store.int(c, "sendMin", 30).coerceAtLeast(3)
        val maxWait = Store.int(c, "sendMax", 60).coerceAtLeast(minWait)
        acquireWake(users.size * (maxWait + 90L) * 1000L + 60_000L)
        var sent = 0
        var failed = 0
        var blockedStop = false
        try {
            for ((idx, u) in users.withIndex()) {
                if (sendStop) break
                sendProgress = "${idx + 1} of ${users.size}"
                sendStatus = "Messaging @$u"
                val r = try {
                    sendOne(u)
                } catch (e: BlockedEx) {
                    SendResult(false, "Instagram warning", true)
                } catch (e: InterruptedException) {
                    throw e
                } catch (e: Exception) {
                    SendResult(false, "Error: " + (e.message ?: "unknown"))
                }
                Store.setStatus(c, u, if (r.ok) "sent" else "failed", r.note)
                if (r.ok) sent++ else failed++
                if (r.blocked) { blockedStop = true; break }
                if (idx < users.size - 1 && !sendStop) {
                    val wait = Random.nextInt(minWait, maxWait + 1)
                    for (sec in wait downTo 1) {
                        if (sendStop) break
                        sendStatus = "Sent $sent, failed $failed. Next in ${sec}s"
                        Thread.sleep(1000L)
                    }
                }
            }
        } catch (e: InterruptedException) {
            // stopped by user
        } finally {
            sendStatus = when {
                blockedStop -> "Stopped: Instagram showed a warning. Sent $sent, failed $failed"
                sendStop -> "Stopped by you. Sent $sent, failed $failed"
                else -> "Done. Sent $sent, failed $failed"
            }
            sending = false
            releaseWake()
            toast(sendStatus)
        }
    }

    private fun pickMessage(u: String): String {
        val raw = Store.str(applicationContext, "msgText", "")
        val variants = raw.split(Regex("(?m)^\\s*---\\s*$")).map { it.trim() }.filter { it.isNotEmpty() }
        if (variants.isEmpty()) return ""
        return variants.random().replace("{username}", u, ignoreCase = true)
    }

    private fun snapshot(): List<N>? {
        val root = rootInActiveWindow ?: return null
        if (root.packageName?.toString() != IG) return null
        return flatten(root)
    }

    private fun waitFor(timeoutMs: Long, check: (List<N>) -> Boolean): Boolean {
        val end = SystemClock.elapsedRealtime() + timeoutMs
        while (SystemClock.elapsedRealtime() < end) {
            if (sendStop) throw InterruptedException()
            val n = snapshot()
            if (n != null) {
                if (n.any { it.text.length < 120 && BLOCK.containsMatchIn(it.text) }) throw BlockedEx()
                if (check(n)) return true
            }
            Thread.sleep(400L)
        }
        return false
    }

    private fun findLive(pred: (AccessibilityNodeInfo) -> Boolean): AccessibilityNodeInfo? {
        val root = rootInActiveWindow ?: return null
        var found: AccessibilityNodeInfo? = null
        fun walk(n: AccessibilityNodeInfo?, d: Int) {
            if (n == null || found != null || d > 40) return
            if (n.isVisibleToUser && pred(n)) { found = n; return }
            for (i in 0 until n.childCount) walk(n.getChild(i), d + 1)
        }
        walk(root, 0)
        return found
    }

    private fun clickNode(n: AccessibilityNodeInfo): Boolean {
        var cur: AccessibilityNodeInfo? = n
        var depth = 0
        while (cur != null && depth < 6) {
            if (cur.isClickable && cur.performAction(AccessibilityNodeInfo.ACTION_CLICK)) return true
            cur = cur.parent
            depth++
        }
        return false
    }

    private fun tapNode(n: AccessibilityNodeInfo) {
        val r = Rect()
        n.getBoundsInScreen(r)
        val p = Path()
        p.moveTo(r.centerX().toFloat(), r.centerY().toFloat())
        val g = GestureDescription.Builder()
            .addStroke(GestureDescription.StrokeDescription(p, 0L, 60L))
            .build()
        dispatchGesture(g, null, null)
    }

    private fun press(n: AccessibilityNodeInfo) {
        if (!clickNode(n)) tapNode(n)
    }

    private fun hintShown(e: AccessibilityNodeInfo): Boolean =
        e.text.isNullOrEmpty() || (Build.VERSION.SDK_INT >= 26 && e.isShowingHintText)

    private fun sendOne(u: String): SendResult {
        val msg = pickMessage(u)
        if (msg.isBlank()) return SendResult(false, "No message text set")

        // 1. open the profile (two attempts)
        var loaded = false
        var gone = false
        for (attempt in 1..2) {
            sendStatus = "Opening @$u"
            val i = Intent(Intent.ACTION_VIEW, Uri.parse("instagram://user?username=$u")).apply {
                setPackage(IG)
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            }
            try { startActivity(i) } catch (e: Exception) { return SendResult(false, "Could not open Instagram") }
            Thread.sleep(1500L)
            loaded = waitFor(9_000L) { n ->
                if (n.any { it.text.length < 120 && UNAVAILABLE.containsMatchIn(it.text) }) {
                    gone = true
                    true
                } else {
                    n.any { it.visible && (it.text.equals(u, true) || it.desc.contains(u, true)) }
                }
            }
            if (loaded) break
        }
        if (gone) return SendResult(false, "Account not found")
        if (!loaded) return SendResult(false, "Profile did not open")

        // 2. tap Message
        var msgBtn: AccessibilityNodeInfo? = null
        waitFor(4_000L) { _ ->
            msgBtn = findLive {
                it.text?.toString()?.trim().equals("Message", true) ||
                    it.contentDescription?.toString()?.trim().equals("Message", true)
            }
            msgBtn != null
        }
        val mb = msgBtn ?: return SendResult(false, "No Message button on profile")
        sendStatus = "Opening chat with @$u"
        press(mb)

        // 3. wait for the text box
        var edit: AccessibilityNodeInfo? = null
        val boxOk = waitFor(8_000L) { _ ->
            edit = findLive { it.className?.toString()?.contains("EditText") == true }
            edit != null
        }
        val e = edit
        if (!boxOk || e == null) return SendResult(false, "Message box did not open")

        // 4. type the message
        sendStatus = "Typing to @$u"
        e.performAction(AccessibilityNodeInfo.ACTION_FOCUS)
        e.performAction(AccessibilityNodeInfo.ACTION_CLICK)
        Thread.sleep(300L)
        val args = Bundle()
        args.putCharSequence(AccessibilityNodeInfo.ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE, msg)
        var typed = e.performAction(AccessibilityNodeInfo.ACTION_SET_TEXT, args)
        if (!typed) {
            val cm = getSystemService(CLIPBOARD_SERVICE) as ClipboardManager
            cm.setPrimaryClip(ClipData.newPlainText("msg", msg))
            typed = e.performAction(AccessibilityNodeInfo.ACTION_PASTE)
        }
        Thread.sleep(700L)
        e.refresh()
        if (!typed || hintShown(e)) return SendResult(false, "Could not type the message")

        // 5. tap Send
        var sendBtn: AccessibilityNodeInfo? = null
        val sendOk = waitFor(3_000L) { _ ->
            sendBtn = findLive {
                val t = it.text?.toString()?.trim() ?: ""
                val d = it.contentDescription?.toString()?.trim() ?: ""
                t.equals("Send", true) || d.equals("Send", true) || d.equals("Send message", true)
            }
            sendBtn != null
        }
        val sb = sendBtn
        if (!sendOk || sb == null) return SendResult(false, "Send button not found")
        press(sb)

        // 6. confirm it went through
        Thread.sleep(1500L)
        val after = findLive { it.className?.toString()?.contains("EditText") == true }
        val cleared = after != null && run { after.refresh(); hintShown(after) }
        val snippet = msg.trim().take(20)
        val appeared = snapshot()?.any { it.text.contains(snippet) || it.desc.contains(snippet) } == true
        if (!cleared && !appeared) return SendResult(false, "Not confirmed - check manually")

        // 7. back out
        performGlobalAction(GLOBAL_ACTION_BACK)
        Thread.sleep(800L)
        performGlobalAction(GLOBAL_ACTION_BACK)
        Thread.sleep(800L)
        return SendResult(true, "")
    }
}
