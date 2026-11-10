package com.urreal3d.adspotter

import android.app.Activity
import android.app.AlertDialog
import android.content.ClipData
import android.content.ClipboardManager
import android.content.ContentValues
import android.content.Intent
import android.os.Build
import android.os.Environment
import android.provider.MediaStore
import androidx.core.content.FileProvider
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.provider.Settings
import android.text.InputType
import android.widget.Button
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import java.io.File

class MainActivity : Activity() {

    private lateinit var status: TextView
    private lateinit var list: TextView
    private lateinit var minW: EditText
    private lateinit var maxW: EditText
    private lateinit var mins: EditText
    private val h = Handler(Looper.getMainLooper())
    private val loop = object : Runnable {
        override fun run() {
            refresh()
            h.postDelayed(this, 1500L)
        }
    }

    private fun dp(v: Int) = (v * resources.displayMetrics.density).toInt()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val scroll = ScrollView(this)
        val col = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(16), dp(28), dp(16), dp(28))
        }
        scroll.addView(col)
        setContentView(scroll)

        col.addView(text("Ad Spotter", 24f, true))
        col.addView(text("Scrolls Reels and saves the username of every sponsored Reel. Open the Reels tab, then press Start.", 13f, false))

        status = text("", 14f, false)
        col.addView(status)

        col.addView(btn("1. Enable accessibility service") {
            startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS))
        })

        col.addView(text("Check time per Reel in seconds (min / max) and session length (minutes)", 13f, false))
        val row = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        minW = num(Store.int(this, "chkMin", 2))
        maxW = num(Store.int(this, "chkMax", 3))
        mins = num(Store.int(this, "mins", 30))
        row.addView(minW, LinearLayout.LayoutParams(0, -2, 1f))
        row.addView(maxW, LinearLayout.LayoutParams(0, -2, 1f))
        row.addView(mins, LinearLayout.LayoutParams(0, -2, 1f))
        col.addView(row)

        col.addView(btn("2. Start") {
            if (SpotterService.instance == null) {
                toast("Enable the accessibility service first")
            } else {
                saveSettings()
                val i = packageManager.getLaunchIntentForPackage(SpotterService.IG)
                if (i == null) {
                    toast("Instagram not found")
                } else {
                    startActivity(i)
                    SpotterService.instance?.start()
                }
            }
        })
        col.addView(btn("Stop") { SpotterService.instance?.stop("Stopped by you") })

        list = text("", 14f, false)
        col.addView(list)

        col.addView(btn("Export CSV file (save to Downloads)") { exportCsv() })
        col.addView(btn("Share CSV file") { shareCsvFile() })
        col.addView(btn("Copy usernames") {
            (getSystemService(CLIPBOARD_SERVICE) as ClipboardManager)
                .setPrimaryClip(ClipData.newPlainText("usernames", Store.text(this)))
            toast("Copied")
        })
        col.addView(btn("Clear list") {
            AlertDialog.Builder(this)
                .setMessage("Delete all captured usernames?")
                .setPositiveButton("Delete") { _, _ -> Store.clear(this); refresh() }
                .setNegativeButton("Cancel", null)
                .show()
        })

        col.addView(text("Inspector (for tuning detection)", 15f, true))
        col.addView(btn("Dump screen in 6s") {
            val s = SpotterService.instance
            if (s == null) {
                toast("Enable the accessibility service first")
            } else {
                s.dumpIn(6_000L)
                toast("Open Instagram on a sponsored Reel now")
            }
        })
        col.addView(btn("Share last dump") {
            val f = File(getExternalFilesDir(null), "dump.txt")
            if (f.exists()) share(f.readText().take(200_000)) else toast("No dump yet")
        })
    }

    override fun onResume() {
        super.onResume()
        h.post(loop)
    }

    override fun onPause() {
        super.onPause()
        h.removeCallbacks(loop)
    }

    private fun refresh() {
        val on = SpotterService.instance != null
        status.text = "Service: " + (if (on) "enabled" else "NOT enabled") +
            "\nState: " + (if (SpotterService.running) "RUNNING" else "stopped") +
            "\n" + SpotterService.status
        val l = Store.all(this)
        list.text = "Captured: ${l.size}\n\n" +
            l.takeLast(25).reversed().joinToString("\n") { "@${it.user}  x${it.count}" }
    }

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

    private fun saveSettings() {
        Store.setInt(this, "chkMin", minW.text.toString().toIntOrNull() ?: 2)
        Store.setInt(this, "chkMax", maxW.text.toString().toIntOrNull() ?: 3)
        Store.setInt(this, "mins", mins.text.toString().toIntOrNull() ?: 30)
    }

    private fun share(t: String) {
        val i = Intent(Intent.ACTION_SEND).apply {
            type = "text/plain"
            putExtra(Intent.EXTRA_TEXT, t)
        }
        startActivity(Intent.createChooser(i, "Share"))
    }

    private fun toast(m: String) = Toast.makeText(this, m, Toast.LENGTH_SHORT).show()

    private fun text(t: String, size: Float, bold: Boolean) = TextView(this).apply {
        text = t
        textSize = size
        if (bold) setTypeface(typeface, android.graphics.Typeface.BOLD)
        setPadding(0, dp(8), 0, dp(8))
    }

    private fun num(v: Int) = EditText(this).apply {
        inputType = InputType.TYPE_CLASS_NUMBER
        setText(v.toString())
    }

    private fun btn(label: String, onClick: () -> Unit) = Button(this).apply {
        text = label
        isAllCaps = false
        setOnClickListener { onClick() }
    }
}
