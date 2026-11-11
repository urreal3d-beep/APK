package com.urreal3d.adspotter

import android.app.Activity
import android.app.AlertDialog
import android.content.ClipData
import android.content.ClipboardManager
import android.content.ContentValues
import android.content.Intent
import android.content.res.ColorStateList
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.ColorDrawable
import android.graphics.drawable.GradientDrawable
import android.graphics.drawable.RippleDrawable
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Environment
import android.os.Handler
import android.os.Looper
import android.provider.MediaStore
import android.provider.Settings
import android.text.Editable
import android.text.InputType
import android.text.TextWatcher
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.BaseAdapter
import android.widget.Button
import android.widget.CheckBox
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.ListView
import android.widget.RadioButton
import android.widget.RadioGroup
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import androidx.core.content.FileProvider
import java.io.File

class MainActivity : Activity() {

    // ---- colors (dark theme) ----
    private val cBg = Color.parseColor("#0E1013")
    private val cSurface = Color.parseColor("#171A1F")
    private val cSurface2 = Color.parseColor("#20242B")
    private val cStroke = Color.parseColor("#2A2F37")
    private val cText = Color.parseColor("#E8EAED")
    private val cMuted = Color.parseColor("#9AA0A6")
    private val cAccent = Color.parseColor("#4C8DFF")
    private val cGreen = Color.parseColor("#3DD68C")
    private val cRed = Color.parseColor("#FF6B6B")
    private val cAmber = Color.parseColor("#F2B84B")

    private val MATCH = ViewGroup.LayoutParams.MATCH_PARENT
    private val WRAP = ViewGroup.LayoutParams.WRAP_CONTENT

    private val h = Handler(Looper.getMainLooper())
    private val loop = object : Runnable {
        override fun run() {
            refresh(false)
            h.postDelayed(this, 1500L)
        }
    }

    private val selected = HashSet<String>()
    private var filter = "all"
    private var shown: List<Entry> = emptyList()
    private var lastSig = 0

    private lateinit var tabViews: List<View>
    private lateinit var tabLabels: List<TextView>
    private lateinit var leadAdapter: LeadAdapter

    // scan tab
    private lateinit var svcStatus: TextView
    private lateinit var scanStatus: TextView
    private lateinit var scanCount: TextView
    private lateinit var chkMin: EditText
    private lateinit var chkMax: EditText
    private lateinit var sessMins: EditText

    // leads tab
    private lateinit var leadsCount: TextView
    private lateinit var chips: Map<String, TextView>

    // send tab
    private lateinit var msgBox: EditText
    private lateinit var sendMin: EditText
    private lateinit var sendMax: EditText
    private lateinit var rbAll: RadioButton
    private lateinit var rbSel: RadioButton
    private lateinit var sendProgressTv: TextView
    private lateinit var sendStateTv: TextView

    private fun dp(v: Int) = (v * resources.displayMetrics.density).toInt()

    // ------------------------------------------------------------------ setup

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.statusBarColor = cBg
        window.navigationBarColor = cSurface

        leadAdapter = LeadAdapter()
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(cBg)
        }
        val content = FrameLayout(this)
        root.addView(content, LinearLayout.LayoutParams(MATCH, 0, 1f))
        tabViews = listOf(buildScan(), buildLeads(), buildSend(), buildSettings())
        tabViews.forEach { content.addView(it, FrameLayout.LayoutParams(MATCH, MATCH)) }
        root.addView(buildTabBar(), LinearLayout.LayoutParams(MATCH, WRAP))
        setContentView(root)
        show(0)
    }

    override fun onResume() {
        super.onResume()
        h.post(loop)
    }

    override fun onPause() {
        super.onPause()
        h.removeCallbacks(loop)
    }

    private fun show(i: Int) {
        tabViews.forEachIndexed { idx, v -> v.visibility = if (idx == i) View.VISIBLE else View.GONE }
        tabLabels.forEachIndexed { idx, t ->
            t.setTextColor(if (idx == i) cAccent else cMuted)
            t.setTypeface(null, if (idx == i) Typeface.BOLD else Typeface.NORMAL)
        }
        refresh(true)
    }

    private fun buildTabBar(): View {
        val bar = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            setBackgroundColor(cSurface)
        }
        val names = listOf("Scan", "Leads", "Send", "Settings")
        tabLabels = names.mapIndexed { i, n ->
            TextView(this).apply {
                text = n
                textSize = 14f
                gravity = Gravity.CENTER
                setPadding(0, dp(16), 0, dp(16))
                setOnClickListener { show(i) }
            }
        }
        tabLabels.forEach { bar.addView(it, LinearLayout.LayoutParams(0, WRAP, 1f)) }
        return bar
    }

    // ---------------------------------------------------------------- ui bits

    private fun rounded(color: Int, r: Int, stroke: Int = 0) = GradientDrawable().apply {
        setColor(color)
        cornerRadius = dp(r).toFloat()
        if (stroke != 0) setStroke(dp(1), stroke)
    }

    private fun tv(t: String, size: Float = 14f, color: Int = cText, bold: Boolean = false, topPad: Int = 0): TextView =
        TextView(this).apply {
            text = t
            textSize = size
            setTextColor(color)
            if (bold) setTypeface(null, Typeface.BOLD)
            if (topPad > 0) setPadding(0, dp(topPad), 0, 0)
        }

    private fun card(vararg parts: View): LinearLayout = LinearLayout(this).apply {
        orientation = LinearLayout.VERTICAL
        background = rounded(cSurface, 14, cStroke)
        setPadding(dp(16), dp(14), dp(16), dp(16))
        layoutParams = LinearLayout.LayoutParams(MATCH, WRAP).apply { bottomMargin = dp(12) }
        parts.forEach { addView(it) }
    }

    private fun page(vararg parts: View): ScrollView {
        val col = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(16), dp(20), dp(16), dp(16))
        }
        parts.forEach { col.addView(it) }
        return ScrollView(this).apply { addView(col) }
    }

    private fun button(label: String, fill: Int, txt: Int = Color.WHITE, onClick: () -> Unit): Button =
        Button(this).apply {
            text = label
            isAllCaps = false
            textSize = 15f
            setTextColor(txt)
            setTypeface(null, Typeface.BOLD)
            background = RippleDrawable(ColorStateList.valueOf(Color.parseColor("#33FFFFFF")), rounded(fill, 12), null)
            stateListAnimator = null
            minHeight = dp(48)
            minimumHeight = dp(48)
            layoutParams = LinearLayout.LayoutParams(MATCH, WRAP).apply { topMargin = dp(10) }
            setOnClickListener { onClick() }
        }

    private fun row(vararg parts: View): LinearLayout = LinearLayout(this).apply {
        orientation = LinearLayout.HORIZONTAL
        parts.forEachIndexed { i, v ->
            v.layoutParams = LinearLayout.LayoutParams(0, WRAP, 1f).apply {
                topMargin = dp(10)
                if (i > 0) marginStart = dp(8)
            }
            addView(v)
        }
    }

    private fun field(label: String, value: String, number: Boolean): Pair<LinearLayout, EditText> {
        val et = EditText(this).apply {
            setText(value)
            setTextColor(cText)
            setHintTextColor(cMuted)
            textSize = 15f
            background = rounded(cSurface2, 10, cStroke)
            setPadding(dp(12), dp(10), dp(12), dp(10))
            if (number) inputType = InputType.TYPE_CLASS_NUMBER
        }
        val col = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            addView(tv(label, 12f, cMuted))
            addView(et, LinearLayout.LayoutParams(MATCH, WRAP).apply { topMargin = dp(4) })
        }
        return Pair(col, et)
    }

    private fun toast(m: String) = Toast.makeText(this, m, Toast.LENGTH_SHORT).show()

    private fun confirm(msg: String, yes: String, onYes: () -> Unit) {
        AlertDialog.Builder(this)
            .setMessage(msg)
            .setPositiveButton(yes) { _, _ -> onYes() }
            .setNegativeButton("Cancel", null)
            .show()
    }

    private fun num(e: EditText, def: Int) = e.text.toString().trim().toIntOrNull() ?: def

    // -------------------------------------------------------------- scan tab

    private fun buildScan(): View {
        svcStatus = tv("", 15f, cText, true, 6)
        val setup = card(
            tv("Setup", 17f, cText, true),
            svcStatus,
            tv(
                "Turn Ad Spotter on in Accessibility. If Android says \"Restricted setting\": Settings > Apps > Ad Spotter > top-right menu > Allow restricted settings.",
                12f, cMuted, false, 6
            ),
            button("Open accessibility settings", cSurface2, cText) {
                startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS))
            }
        )

        val a = field("Check time per Reel, min (sec)", Store.int(this, "chkMin", 2).toString(), true)
        val b = field("Check time per Reel, max (sec)", Store.int(this, "chkMax", 3).toString(), true)
        val c = field("Session length (minutes)", Store.int(this, "mins", 30).toString(), true)
        chkMin = a.second
        chkMax = b.second
        sessMins = c.second
        val settings = card(
            tv("Scan settings", 17f, cText, true),
            tv("How long to look for the Ad label on each Reel before moving on.", 12f, cMuted, false, 4),
            row(a.first, b.first),
            c.first.also { it.setPadding(0, dp(10), 0, 0) }
        )

        scanStatus = tv("", 15f, cText, true, 6)
        scanCount = tv("", 13f, cMuted, false, 4)
        val status = card(tv("Status", 17f, cText, true), scanStatus, scanCount)

        return page(
            tv("Scan", 24f, cText, true),
            tv("Scrolls Reels and saves the username of every ad.", 13f, cMuted, false, 2),
            tv("", 8f).also { it.setPadding(0, dp(8), 0, 0) },
            setup, settings, status,
            button("Start scan", cAccent) { startScan() },
            button("Stop", cSurface2, cText) { SpotterService.instance?.stop("Stopped by you") }
        )
    }

    private fun startScan() {
        val s = SpotterService.instance
        if (s == null) { toast("Turn on the accessibility service first"); return }
        if (SpotterService.sending) { toast("Stop sending first"); return }
        Store.setInt(this, "chkMin", num(chkMin, 2))
        Store.setInt(this, "chkMax", num(chkMax, 3))
        Store.setInt(this, "mins", num(sessMins, 30))
        val i = packageManager.getLaunchIntentForPackage(SpotterService.IG)
        if (i == null) {
            toast("Instagram not found")
        } else {
            startActivity(i)
            s.start()
        }
    }

    // ------------------------------------------------------------- leads tab

    private fun buildLeads(): View {
        val col = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(16), dp(20), dp(16), dp(8))
        }
        col.addView(tv("Leads", 24f, cText, true))
        leadsCount = tv("", 13f, cMuted, false, 2)
        col.addView(leadsCount)

        val chipRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            setPadding(0, dp(12), 0, dp(10))
        }
        val m = LinkedHashMap<String, TextView>()
        for ((key, label) in listOf("all" to "All", "new" to "New", "sent" to "Sent", "failed" to "Failed")) {
            val chip = TextView(this).apply {
                text = label
                textSize = 13f
                gravity = Gravity.CENTER
                setPadding(dp(14), dp(8), dp(14), dp(8))
                setOnClickListener { filter = key; refresh(true) }
            }
            chipRow.addView(chip, LinearLayout.LayoutParams(WRAP, WRAP).apply { marginEnd = dp(8) })
            m[key] = chip
        }
        chips = m
        col.addView(chipRow)

        val list = ListView(this).apply {
            adapter = leadAdapter
            divider = ColorDrawable(Color.TRANSPARENT)
            dividerHeight = dp(8)
            selector = ColorDrawable(Color.TRANSPARENT)
            isVerticalScrollBarEnabled = false
            setOnItemClickListener { _, _, pos, _ ->
                if (pos < shown.size) {
                    val u = shown[pos].user
                    if (!selected.add(u)) selected.remove(u)
                    refresh(true)
                }
            }
            setOnItemLongClickListener { _, _, pos, _ ->
                if (pos < shown.size) {
                    startActivity(Intent(Intent.ACTION_VIEW, Uri.parse("https://www.instagram.com/${shown[pos].user}/")))
                }
                true
            }
        }
        col.addView(list, LinearLayout.LayoutParams(MATCH, 0, 1f))
        col.addView(tv("Tap to select. Hold to open the profile.", 12f, cMuted, false, 6))
        col.addView(row(
            button("Export CSV", cAccent) { exportCsv() },
            button("Share CSV", cSurface2, cText) { shareCsvFile() }
        ))
        col.addView(button("More actions", cSurface2, cText) { showActions() })
        return col
    }

    private inner class Holder(
        val root: LinearLayout,
        val cb: CheckBox,
        val name: TextView,
        val note: TextView,
        val chip: TextView
    )

    private inner class LeadAdapter : BaseAdapter() {
        override fun getCount() = shown.size
        override fun getItem(position: Int): Any = shown[position]
        override fun getItemId(position: Int) = position.toLong()

        override fun getView(position: Int, convertView: View?, parent: ViewGroup?): View {
            val hd = (convertView?.tag as? Holder) ?: makeHolder()
            val e = shown[position]
            hd.cb.isChecked = e.user in selected
            hd.name.text = "@" + e.user
            val note = when (e.status) {
                "failed" -> e.note
                "sent" -> "Sent " + java.text.SimpleDateFormat("d MMM, HH:mm", java.util.Locale.US).format(java.util.Date(e.sentAt))
                else -> "Seen ${e.count}x"
            }
            hd.note.text = note
            hd.note.visibility = if (note.isBlank()) View.GONE else View.VISIBLE
            hd.chip.text = e.status.replaceFirstChar { it.uppercase() }
            hd.chip.setTextColor(
                when (e.status) {
                    "sent" -> cGreen
                    "failed" -> cRed
                    else -> cAmber
                }
            )
            return hd.root
        }

        private fun makeHolder(): Holder {
            val cb = CheckBox(this@MainActivity).apply {
                isClickable = false
                isFocusable = false
                buttonTintList = ColorStateList.valueOf(cAccent)
            }
            val name = tv("", 15f, cText, true)
            val note = tv("", 12f, cMuted)
            val mid = LinearLayout(this@MainActivity).apply {
                orientation = LinearLayout.VERTICAL
                addView(name)
                addView(note)
            }
            val chip = TextView(this@MainActivity).apply {
                textSize = 12f
                background = rounded(cSurface2, 20)
                setPadding(dp(10), dp(4), dp(10), dp(4))
            }
            val root = LinearLayout(this@MainActivity).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
                background = rounded(cSurface, 12, cStroke)
                setPadding(dp(12), dp(12), dp(12), dp(12))
                addView(cb)
                addView(mid, LinearLayout.LayoutParams(0, WRAP, 1f).apply { marginStart = dp(8) })
                addView(chip)
            }
            val holder = Holder(root, cb, name, note, chip)
            root.tag = holder
            return holder
        }
    }

    private fun showActions() {
        val items = arrayOf(
            "Select all shown", "Clear selection", "Delete selected",
            "Reset failed to New", "Copy usernames", "Delete all leads"
        )
        AlertDialog.Builder(this).setItems(items) { _, which ->
            when (which) {
                0 -> { shown.forEach { selected.add(it.user) }; refresh(true) }
                1 -> { selected.clear(); refresh(true) }
                2 -> if (selected.isEmpty()) toast("Nothing selected") else
                    confirm("Delete ${selected.size} selected leads?", "Delete") {
                        Store.delete(this, HashSet(selected)); selected.clear(); refresh(true)
                    }
                3 -> { toast("${Store.resetFailed(this)} leads reset to New"); refresh(true) }
                4 -> {
                    (getSystemService(CLIPBOARD_SERVICE) as ClipboardManager)
                        .setPrimaryClip(ClipData.newPlainText("usernames", Store.text(this)))
                    toast("Copied")
                }
                5 -> confirm("Delete ALL leads? This cannot be undone.", "Delete all") {
                    Store.clear(this); selected.clear(); refresh(true)
                }
            }
        }.show()
    }

    // -------------------------------------------------------------- send tab

    private fun buildSend(): View {
        msgBox = EditText(this).apply {
            setText(Store.str(this@MainActivity, "msgText", ""))
            setTextColor(cText)
            setHintTextColor(cMuted)
            hint = "Write your message here.\n\nUse {username} to insert their handle.\nTo rotate several versions, put --- on its own line between them."
            textSize = 15f
            minLines = 7
            gravity = Gravity.TOP or Gravity.START
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_MULTI_LINE or InputType.TYPE_TEXT_FLAG_CAP_SENTENCES
            background = rounded(cSurface2, 10, cStroke)
            setPadding(dp(12), dp(10), dp(12), dp(10))
            addTextChangedListener(object : TextWatcher {
                override fun beforeTextChanged(s: CharSequence?, st: Int, c: Int, a: Int) {}
                override fun onTextChanged(s: CharSequence?, st: Int, b: Int, c: Int) {}
                override fun afterTextChanged(s: Editable?) {
                    Store.setStr(this@MainActivity, "msgText", s?.toString() ?: "")
                }
            })
        }
        val msgCard = card(
            tv("Message", 17f, cText, true),
            tv("Saved automatically.", 12f, cMuted, false, 2),
            msgBox.also { it.layoutParams = LinearLayout.LayoutParams(MATCH, WRAP).apply { topMargin = dp(10) } }
        )

        val a = field("Wait between messages, min (sec)", Store.int(this, "sendMin", 30).toString(), true)
        val b = field("Wait between messages, max (sec)", Store.int(this, "sendMax", 60).toString(), true)
        sendMin = a.second
        sendMax = b.second
        val timing = card(
            tv("Timing", 17f, cText, true),
            tv("A random wait in this range is used between each message.", 12f, cMuted, false, 2),
            row(a.first, b.first)
        )

        rbAll = RadioButton(this).apply {
            text = "All new leads"
            setTextColor(cText)
            buttonTintList = ColorStateList.valueOf(cAccent)
        }
        rbSel = RadioButton(this).apply {
            text = "Selected leads"
            setTextColor(cText)
            buttonTintList = ColorStateList.valueOf(cAccent)
        }
        val group = RadioGroup(this).apply {
            orientation = RadioGroup.VERTICAL
            addView(rbAll)
            addView(rbSel)
        }
        rbAll.isChecked = true
        val target = card(
            tv("Send to", 17f, cText, true),
            group,
            tv("Leads already marked Sent are never messaged again. Select leads in the Leads tab.", 12f, cMuted, false, 4)
        )

        sendProgressTv = tv("", 13f, cMuted, false, 4)
        sendStateTv = tv("", 15f, cText, true, 6)
        val status = card(tv("Status", 17f, cText, true), sendStateTv, sendProgressTv)

        return page(
            tv("Send", 24f, cText, true),
            tv("Sends your message to captured leads on Instagram.", 13f, cMuted, false, 2),
            tv("", 8f).also { it.setPadding(0, dp(8), 0, 0) },
            msgCard, timing, target, status,
            button("Start sending", cAccent) { startSending() },
            button("Stop", cSurface2, cText) { SpotterService.instance?.stopSend() }
        )
    }

    private fun startSending() {
        val s = SpotterService.instance
        if (s == null) { toast("Turn on the accessibility service first"); return }
        if (SpotterService.running) { toast("Stop scanning first"); return }
        if (msgBox.text.toString().isBlank()) { toast("Write your message first"); return }
        Store.setInt(this, "sendMin", num(sendMin, 30))
        Store.setInt(this, "sendMax", num(sendMax, 60))
        val all = Store.all(this)
        val targets = if (rbSel.isChecked) all.filter { it.user in selected && it.status != "sent" }
        else all.filter { it.status == "new" }
        if (targets.isEmpty()) { toast("No leads to send to"); return }
        confirm(
            "Send your message to ${targets.size} leads now? Keep the screen on and leave the phone alone while it runs.",
            "Start"
        ) { s.startSend(targets.map { it.user }) }
    }

    // ---------------------------------------------------------- settings tab

    private fun buildSettings(): View {
        val inspector = card(
            tv("Inspector", 17f, cText, true),
            tv("For fixing detection. Tap Dump, switch to Instagram on the screen you want to capture, wait 6 seconds, then share the dump.", 12f, cMuted, false, 4),
            button("Dump screen in 6s", cSurface2, cText) {
                val s = SpotterService.instance
                if (s == null) {
                    toast("Turn on the accessibility service first")
                } else {
                    s.dumpIn(6_000L)
                    toast("Open Instagram on the screen now")
                }
            },
            button("Share last dump", cSurface2, cText) {
                val f = File(getExternalFilesDir(null), "dump.txt")
                if (f.exists()) shareText(f.readText().take(200_000)) else toast("No dump yet")
            }
        )
        val ver = try {
            packageManager.getPackageInfo(packageName, 0).versionName
        } catch (e: Exception) {
            "?"
        }
        val about = card(
            tv("About", 17f, cText, true),
            tv("Version $ver", 13f, cMuted, false, 4),
            tv("Installing a newer APK from the same GitHub project updates this app and keeps your leads and settings.", 12f, cMuted, false, 4)
        )
        return page(
            tv("Settings", 24f, cText, true),
            tv("", 8f).also { it.setPadding(0, dp(8), 0, 0) },
            inspector, about
        )
    }

    // ---------------------------------------------------------------- refresh

    private fun refresh(force: Boolean) {
        val all = Store.all(this)
        val nNew = all.count { it.status == "new" }
        val nSent = all.count { it.status == "sent" }
        val nFailed = all.count { it.status == "failed" }

        val on = SpotterService.instance != null
        svcStatus.text = if (on) "Accessibility service: ON" else "Accessibility service: OFF"
        svcStatus.setTextColor(if (on) cGreen else cRed)
        scanStatus.text = (if (SpotterService.running) "Running. " else "Stopped. ") + SpotterService.status
        scanCount.text = "Captured ${all.size}, new ${nNew}"

        leadsCount.text = "${all.size} total, $nNew new, $nSent sent, $nFailed failed, ${selected.size} selected"
        val counts = mapOf("all" to all.size, "new" to nNew, "sent" to nSent, "failed" to nFailed)
        val names = mapOf("all" to "All", "new" to "New", "sent" to "Sent", "failed" to "Failed")
        for ((k, v) in chips) {
            v.text = "${names[k]} ${counts[k]}"
            val sel = k == filter
            v.setTextColor(if (sel) Color.WHITE else cMuted)
            v.background = rounded(if (sel) cAccent else cSurface2, 20)
        }
        val list = if (filter == "all") all else all.filter { it.status == filter }
        val sig = list.hashCode() * 31 + selected.hashCode()
        if (force || sig != lastSig) {
            shown = list
            lastSig = sig
            leadAdapter.notifyDataSetChanged()
        }

        val selUnsent = all.count { it.user in selected && it.status != "sent" }
        rbAll.text = "All new leads ($nNew)"
        rbSel.text = "Selected leads ($selUnsent unsent)"
        sendProgressTv.text = if (SpotterService.sending) "Progress: " + SpotterService.sendProgress else ""
        sendStateTv.text = (if (SpotterService.sending) "Sending. " else "") + SpotterService.sendStatus
    }

    // ------------------------------------------------------------------ files

    private fun csvName() =
        "adspotter_" + java.text.SimpleDateFormat("yyyyMMdd_HHmm", java.util.Locale.US).format(java.util.Date()) + ".csv"

    private fun exportCsv() {
        if (Store.all(this).isEmpty()) { toast("Nothing captured yet"); return }
        val name = csvName()
        if (Build.VERSION.SDK_INT >= 29) {
            val v = ContentValues().apply {
                put(MediaStore.Downloads.DISPLAY_NAME, name)
                put(MediaStore.Downloads.MIME_TYPE, "text/csv")
                put(MediaStore.Downloads.RELATIVE_PATH, Environment.DIRECTORY_DOWNLOADS)
            }
            val uri = contentResolver.insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, v)
            if (uri == null) {
                toast("Could not save file")
            } else {
                contentResolver.openOutputStream(uri)?.use { it.write(Store.csv(this).toByteArray()) }
                toast("Saved to Downloads: $name")
            }
        } else {
            shareCsvFile()
        }
    }

    private fun shareCsvFile() {
        if (Store.all(this).isEmpty()) { toast("Nothing captured yet"); return }
        val dir = File(cacheDir, "exports").apply { mkdirs() }
        val f = File(dir, csvName())
        f.writeText(Store.csv(this))
        val uri = FileProvider.getUriForFile(this, "$packageName.fileprovider", f)
        val i = Intent(Intent.ACTION_SEND).apply {
            type = "text/csv"
            putExtra(Intent.EXTRA_STREAM, uri)
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
        startActivity(Intent.createChooser(i, "Share CSV"))
    }

    private fun shareText(t: String) {
        val i = Intent(Intent.ACTION_SEND).apply {
            type = "text/plain"
            putExtra(Intent.EXTRA_TEXT, t)
        }
        startActivity(Intent.createChooser(i, "Share"))
    }
}
