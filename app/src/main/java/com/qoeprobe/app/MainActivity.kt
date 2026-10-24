package com.qoeprobe.app

import android.Manifest
import android.app.Activity
import android.content.ContentValues
import android.content.Intent
import android.content.SharedPreferences
import android.graphics.Typeface
import android.os.Bundle
import android.os.Environment
import android.provider.MediaStore
import android.text.InputType
import android.webkit.CookieManager
import android.webkit.WebStorage
import android.widget.Button
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import java.util.concurrent.Executors
import kotlin.concurrent.thread

class MainActivity : Activity() {
    private lateinit var prefs: SharedPreferences
    private lateinit var log: TextView
    private lateinit var loginStatus: TextView
    private lateinit var covBtn: Button
    private val fields = LinkedHashMap<String, EditText>()
    private val bg = Executors.newSingleThreadExecutor()
    @Volatile private var covRunning = false

    private fun dp(x: Int) = (x * resources.displayMetrics.density).toInt()

    override fun onCreate(b: Bundle?) {
        super.onCreate(b)
        Radio.init(this)
        prefs = getSharedPreferences("cfg", MODE_PRIVATE)
        val root = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(dp(14), dp(8), dp(14), dp(24)) }
        setContentView(ScrollView(this).apply { addView(root) })

        fun head(t: String) = root.addView(TextView(this).apply {
            text = t; textSize = 17f; setTypeface(null, Typeface.BOLD); setPadding(0, dp(14), 0, dp(2))
        })
        fun note(t: String) = root.addView(TextView(this).apply { text = t; textSize = 12f })
        fun field(key: String, hint: String, def: String, num: Boolean = false) = root.addView(EditText(this).apply {
            this.hint = hint; setText(prefs.getString(key, def)); isSingleLine = true
            inputType = if (num) InputType.TYPE_CLASS_NUMBER or InputType.TYPE_NUMBER_FLAG_DECIMAL else InputType.TYPE_TEXT_VARIATION_URI
            fields[key] = this
        })
        fun row(vararg bs: Pair<String, () -> Unit>): List<Button> {
            val l = LinearLayout(this)
            val out = bs.map { (t, a) ->
                Button(this).apply { text = t; isAllCaps = false; setOnClickListener { save(); a() } }
                    .also { l.addView(it, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)) }
            }
            root.addView(l); return out
        }

        head("Accounts")
        loginStatus = TextView(this).also { root.addView(it) }
        row("Login Facebook" to { login("https://m.facebook.com/login") },
            "Login Instagram" to { login("https://www.instagram.com/accounts/login/") })
        row("Clear all logins & site data" to {
            CookieManager.getInstance().removeAllCookies { updateLogin() }
            WebStorage.getInstance().deleteAllData(); say("Cookies and site storage cleared")
        })
        note("YouTube needs no login for public videos (Google blocks sign-in inside embedded browsers).")

        head("Video QoE")
        field("dur", "Test duration after playback starts (s)", "60", true)
        field("yt", "YouTube URL", "https://m.youtube.com/watch?v=aqz-KE-bpKQ")
        row("Run YouTube test" to { video("YouTube", "yt") })
        field("fb", "Facebook video / reel URL", "https://m.facebook.com/watch/")
        row("Run Facebook test" to { video("Facebook", "fb") })
        field("ig", "Instagram reel / post URL", "https://www.instagram.com/reels/")
        row("Run Instagram test" to { video("Instagram", "ig") })
        note("Paste a specific video/reel link for repeatable results. Feeds work too: the visible playing video is tracked.")

        head("Throughput (HTTP)")
        field("dl", "Download URL", "https://speed.cloudflare.com/__down?bytes=200000000")
        field("ul", "Upload URL (accepts POST)", "https://speed.cloudflare.com/__up")
        field("streams", "Parallel streams", "4", true)
        field("tdur", "Duration (s)", "10", true)
        row("Download test" to { tput(true) }, "Upload test" to { tput(false) })

        head("Latency & packet loss (ICMP)")
        field("host", "Host / IP", "8.8.8.8")
        field("pcount", "Ping count", "100", true)
        field("pint", "Interval (s, min 0.2)", "0.2", true)
        row("Run ping test" to { ping() })

        head("Coverage logger")
        covBtn = row("Start coverage log (1 s)" to { toggleCov() })[0]

        head("Export")
        row("Export CSVs → Download/QoEProbe" to { export() })
        note("Files are also in Android/data/com.qoeprobe.app/files/csv")

        head("Log")
        log = TextView(this).apply { typeface = Typeface.MONOSPACE; textSize = 11f; setTextIsSelectable(true) }
        root.addView(log)

        requestPermissions(arrayOf(
            Manifest.permission.ACCESS_FINE_LOCATION, Manifest.permission.ACCESS_COARSE_LOCATION,
            Manifest.permission.READ_PHONE_STATE), 1)
    }

    override fun onRequestPermissionsResult(code: Int, perms: Array<out String>, res: IntArray) {
        Radio.start()
        if (!Radio.hasPerm()) say("Location permission denied: radio/GPS columns will be empty.")
        else say("Ready. Keep Location ON for cell info + GPS.")
    }

    override fun onResume() { super.onResume(); updateLogin(); Radio.start() }

    private fun updateLogin() = runOnUiThread {
        fun s(p: String) = if (Web.loggedIn(p)) "logged in" else "not logged in"
        loginStatus.text = "Facebook: ${s("Facebook")}   Instagram: ${s("Instagram")}"
    }

    private fun save() {
        val e = prefs.edit(); fields.forEach { (k, v) -> e.putString(k, v.text.toString().trim()) }; e.apply()
    }
    private fun str(k: String) = prefs.getString(k, "") ?: ""
    private fun int(k: String, def: Int) = str(k).toDoubleOrNull()?.toInt()?.takeIf { it > 0 } ?: def

    private fun say(s: String) = runOnUiThread {
        if (log.lineCount > 400) log.text = ""
        log.append(s + "\n")
    }

    private fun login(url: String) = startActivity(Intent(this, LoginActivity::class.java).putExtra("url", url))

    private fun video(platform: String, key: String) {
        val u = str(key)
        if (!u.startsWith("http")) { say("$platform: enter a valid URL"); return }
        if (platform != "YouTube" && !Web.loggedIn(platform)) say("$platform: not logged in — some videos may be blocked.")
        say("$platform test started…")
        @Suppress("DEPRECATION")
        startActivityForResult(Intent(this, VideoTestActivity::class.java)
            .putExtra("platform", platform).putExtra("url", u).putExtra("dur", int("dur", 60)), 2)
    }

    @Deprecated("Deprecated in Java")
    override fun onActivityResult(req: Int, res: Int, data: Intent?) {
        data?.getStringExtra("summary")?.let { say(it) }
    }

    private fun tput(down: Boolean) {
        val url = str(if (down) "dl" else "ul")
        bg.execute { try { NetTests.throughput(this, down, url, int("streams", 4), int("tdur", 10), ::say) } catch (e: Exception) { say("Error: ${e.message}") } }
    }

    private fun ping() {
        val iv = str("pint").toDoubleOrNull()?.coerceAtLeast(0.2) ?: 0.2
        bg.execute { NetTests.ping(this, str("host"), int("pcount", 100), String.format(java.util.Locale.US, "%.1f", iv), ::say) }
    }

    private fun toggleCov() {
        if (covRunning) { covRunning = false; covBtn.text = "Start coverage log (1 s)"; return }
        covRunning = true; covBtn.text = "Stop coverage log"
        thread(isDaemon = true) {
            val f = Files.newFile(this, "coverage", Files.stamp())
            val csv = Csv(f, listOf("time") + Radio.KEYS + listOf("neighbors"))
            say("Coverage logging → ${f.name}")
            var n = 0
            while (covRunning) {
                Radio.refresh()
                val s = Radio.snapshot()
                csv.row(listOf(Files.now()) + Radio.KEYS.map { s[it] } + listOf(Radio.neighbors()))
                if (++n % 10 == 0) say("  cov ${s["rat"]} PCI ${s["pci"]} RSRP ${s["rsrp"]} SINR ${s["sinr"]} NR ${s["nr_ss_rsrp"]}")
                Thread.sleep(1000)
            }
            csv.close(); say("Coverage log stopped ($n rows)")
        }
    }

    private fun export() = bg.execute {
        val since = prefs.getLong("lastExport", 0)
        var n = 0
        Files.dir(this).listFiles()?.filter { it.isFile && it.lastModified() > since }?.forEach { f ->
            try {
                val cv = ContentValues().apply {
                    put(MediaStore.MediaColumns.DISPLAY_NAME, f.name)
                    put(MediaStore.MediaColumns.MIME_TYPE, "text/csv")
                    put(MediaStore.MediaColumns.RELATIVE_PATH, Environment.DIRECTORY_DOWNLOADS + "/QoEProbe")
                }
                val uri = contentResolver.insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, cv) ?: return@forEach
                contentResolver.openOutputStream(uri)?.use { out -> f.inputStream().use { it.copyTo(out) } }
                n++
            } catch (e: Exception) { say("Export failed for ${f.name}: ${e.message}") }
        }
        prefs.edit().putLong("lastExport", System.currentTimeMillis()).apply()
        say("Exported $n new/updated file(s) to Download/QoEProbe")
    }

    override fun onDestroy() { covRunning = false; super.onDestroy() }
}
