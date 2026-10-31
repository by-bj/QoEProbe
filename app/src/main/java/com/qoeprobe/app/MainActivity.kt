package com.qoeprobe.app

import android.Manifest
import android.app.Activity
import android.content.Intent
import android.content.SharedPreferences
import android.graphics.Typeface
import android.os.Bundle
import android.text.InputType
import android.webkit.CookieManager
import android.webkit.WebStorage
import android.widget.Button
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import java.util.concurrent.Executors
import kotlin.concurrent.thread

class MainActivity : Activity() {
    private lateinit var prefs: SharedPreferences
    private lateinit var log: TextView
    private lateinit var loginStatus: TextView
    private lateinit var accessStatus: TextView
    private lateinit var uploadStatus: TextView
    private lateinit var managedNote: TextView
    private lateinit var testBox: LinearLayout
    private lateinit var covBtn: Button
    private val fields = LinkedHashMap<String, EditText>()
    private val bg = Executors.newSingleThreadExecutor()
    @Volatile private var covRunning = false
    private var askedRegister = false

    private fun dp(x: Int) = (x * resources.displayMetrics.density).toInt()

    override fun onCreate(b: Bundle?) {
        super.onCreate(b)
        Server.init(this)
        Radio.init(this)
        Uploader.start(this)
        prefs = getSharedPreferences("cfg", MODE_PRIVATE)
        val outer = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(dp(14), dp(8), dp(14), dp(24)) }
        setContentView(ScrollView(this).apply { addView(outer) })
        val accessBox = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        testBox = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; visibility = android.view.View.GONE }
        outer.addView(accessBox); outer.addView(testBox)
        var root = accessBox

        fun head(t: String) = root.addView(TextView(this).apply {
            text = t; textSize = 17f; setTypeface(null, Typeface.BOLD); setPadding(0, dp(14), 0, dp(2))
        })
        fun note(t: String) = TextView(this).apply { text = t; textSize = 12f }.also { root.addView(it) }
        fun field(key: String, hint: String, def: String, num: Boolean = false, multi: Boolean = false) = root.addView(EditText(this).apply {
            this.hint = hint; setText(prefs.getString(key, def))
            inputType = when {
                num -> InputType.TYPE_CLASS_NUMBER or InputType.TYPE_NUMBER_FLAG_DECIMAL
                multi -> InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_URI or InputType.TYPE_TEXT_FLAG_MULTI_LINE
                else -> InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_URI
            }
            if (multi) { isSingleLine = false; maxLines = 3 } else isSingleLine = true
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

        head("Access")
        accessStatus = note("")
        row("Check access" to { refreshAccess(true) },
            "Register / edit details" to { startActivity(Intent(this, RegisterActivity::class.java)) })

        root = testBox
        head("Accounts")
        loginStatus = note("")
        row("Login Facebook" to { login("https://m.facebook.com/login") },
            "Login Instagram" to { login("https://www.instagram.com/accounts/login/") })
        row("Login TikTok" to { login("https://www.tiktok.com/login/phone-or-email/email") },
            "Clear all logins" to {
                CookieManager.getInstance().removeAllCookies { updateLogin() }
                WebStorage.getInstance().deleteAllData(); say("Cookies and site storage cleared")
            })
        note("YouTube needs no login for public videos (Google blocks sign-in inside embedded browsers).")

        managedNote = note("Test settings are managed by your admin.").apply { visibility = android.view.View.GONE }

        head("Drive-test sequence")
        field("seq_steps", "Steps, e.g. YouTube,Facebook,Instagram,TikTok,DL,UL,Ping", "YouTube,Facebook,Instagram,TikTok,DL,UL,Ping")
        field("loops", "Loops (0 = until stopped)", "0", true)
        field("pause", "Pause between steps (s)", "5", true)
        field("step_timeout", "Skip a video step if no playback within (s)", "45", true)
        field("cache_mode", "Cache each loop: clear or keep", "clear")
        row("Start drive-test sequence" to { guard { startSeq() } })
        note("Runs all steps in this app, one after another, with a continuous coverage log. Results upload between loops.")

        head("Video QoE")
        field("dur", "Test duration after playback starts (s)", "60", true)
        field("yt", "YouTube URL(s), one per line", "https://m.youtube.com/watch?v=aqz-KE-bpKQ", multi = true)
        row("Run YouTube test" to { guard { video("YouTube") } })
        field("fb", "Facebook video / reel URL(s)", "https://m.facebook.com/watch/", multi = true)
        row("Run Facebook test" to { guard { video("Facebook") } })
        field("ig", "Instagram reel URL(s)", "https://www.instagram.com/reels/", multi = true)
        row("Run Instagram test" to { guard { video("Instagram") } })
        field("tt", "TikTok video URL(s)", "https://www.tiktok.com/", multi = true)
        field("tt_embed", "TikTok via embed player: yes or no", "no")
        row("Run TikTok test" to { guard { video("TikTok") } })
        note("Single tests use the first link. Sequences rotate through all links, one per loop.")

        head("Throughput (HTTP)")
        field("dl", "Download URL", "https://speed.cloudflare.com/__down?bytes=200000000")
        field("ul", "Upload URL (accepts POST)", "https://speed.cloudflare.com/__up")
        field("streams", "Parallel streams", "4", true)
        field("tdur", "Duration (s)", "10", true)
        row("Download test" to { guard { tput(true) } }, "Upload test" to { guard { tput(false) } })

        head("Latency & packet loss (ICMP)")
        field("host", "Host / IP", "8.8.8.8")
        field("pcount", "Ping count", "100", true)
        field("pint", "Interval (s, min 0.2)", "0.2", true)
        row("Run ping test" to { guard { ping() } })

        head("Coverage logger")
        covBtn = row("Start coverage log (1 s)" to { guard { toggleCov() } })[0]

        head("Uploads and files")
        uploadStatus = note("")
        row("Upload now" to { uploadNow() }, "Export CSVs → Download" to { export() })
        field("keep_days", "Delete uploaded phone files older than (days)", "7", true)
        row("Clean up phone files now" to { cleanup(true) })

        root = accessBox
        head("Log")
        log = TextView(this).apply { typeface = Typeface.MONOSPACE; textSize = 11f; setTextIsSelectable(true) }
        root.addView(log)

        applyPlan()
        updateAccessText()
        requestPermissions(arrayOf(
            Manifest.permission.ACCESS_FINE_LOCATION, Manifest.permission.ACCESS_COARSE_LOCATION,
            Manifest.permission.READ_PHONE_STATE), 1)
    }

    override fun onRequestPermissionsResult(code: Int, perms: Array<out String>, res: IntArray) {
        Radio.start()
        if (!Radio.hasPerm()) say("Location permission denied: radio/GPS columns will be empty.")
        else say("Ready. Keep Location ON for cell info + GPS.")
    }

    override fun onResume() {
        super.onResume()
        updateLogin(); Radio.start(); updateUploads()
        if (Server.configured && !Access.registered && !askedRegister) {
            askedRegister = true
            startActivity(Intent(this, RegisterActivity::class.java))
        } else refreshAccess(false)
    }

    // ---------------------------------------------------------------- access + plan

    private fun refreshAccess(verbose: Boolean) {
        if (!Server.configured) { updateAccessText(); return }
        if (verbose) say("Checking access…")
        thread(isDaemon = true) {
            val st = Access.check()
            runOnUiThread {
                applyPlan(); updateAccessText(); updateUploads()
                if (verbose) say(if (st == "offline") "Could not reach the admin sheet; using cached status." else "Access: $st")
                if (Access.message.isNotBlank()) say("Admin: ${Access.message}")
                cleanup(false)
            }
        }
    }

    private fun updateAccessText() {
        val (ok, why) = Access.allowed()
        testBox.visibility = if (ok) android.view.View.VISIBLE else android.view.View.GONE
        accessStatus.text = if (!Server.configured) why else
            "Device ${Access.deviceId} · ${Access.name().ifEmpty { "not registered" }}${if (Access.market().isNotEmpty()) " (${Access.market()})" else ""}\n" +
            (if (ok) "Access: active${if (Access.expiry().isNotEmpty()) " until ${Access.expiry()}" else ""}" else why)
    }

    /**
     * Admin test plan values replace the matching fields. Testers see no settings values (URLs, servers)
     * unless the admin sets tester_settings = editable; then only fields the plan leaves blank are shown.
     */
    private fun applyPlan() {
        val editable = Access.setting("tester_settings").lowercase() == "editable"
        var managed = false
        fields.forEach { (k, f) ->
            val v = Access.plan(k)
            if (v != null) f.setText(v)
            val show = (editable && v == null) || k == "keep_days"
            f.visibility = if (show) android.view.View.VISIBLE else android.view.View.GONE
            if (!show) managed = true
        }
        managedNote.visibility = if (managed) android.view.View.VISIBLE else android.view.View.GONE
        save()
    }

    private fun guard(a: () -> Unit) {
        val (ok, why) = Access.allowed()
        if (ok) a() else { say(why); Toast.makeText(this, why, Toast.LENGTH_LONG).show() }
    }

    // ---------------------------------------------------------------- helpers

    private fun updateLogin() = runOnUiThread {
        fun s(p: String) = if (Web.loggedIn(p)) "logged in" else "not logged in"
        loginStatus.text = "Facebook: ${s("Facebook")}   Instagram: ${s("Instagram")}   TikTok: ${s("TikTok")}"
    }

    private fun updateUploads() = runOnUiThread {
        val n = Uploader.pending(this)
        uploadStatus.text = if (!Server.configured) "Uploads off (no admin server configured)." else
            "Waiting to upload: $n item(s)${if (Uploader.lastError.isNotEmpty()) " · last error: ${Uploader.lastError}" else ""}"
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

    // ---------------------------------------------------------------- tests

    private fun startSeq() {
        say("Drive-test sequence started…")
        @Suppress("DEPRECATION")
        startActivityForResult(Intent(this, SequenceActivity::class.java), 3)
    }

    private fun video(platform: String) {
        val u = SequenceActivity.urls(this, platform).firstOrNull()
        if (u == null) { say("$platform: enter a valid URL"); return }
        if (platform != "YouTube" && !Web.loggedIn(platform)) say("$platform: not logged in — some videos may be blocked.")
        say("$platform test started…")
        @Suppress("DEPRECATION")
        startActivityForResult(Intent(this, VideoTestActivity::class.java)
            .putExtra("platform", platform).putExtra("url", u).putExtra("dur", int("dur", 60)), 2)
    }

    @Deprecated("Deprecated in Java")
    override fun onActivityResult(req: Int, res: Int, data: Intent?) {
        data?.getStringExtra("summary")?.let { say(it) }
        updateUploads()
    }

    private fun tput(down: Boolean) {
        val url = str(if (down) "dl" else "ul")
        bg.execute {
            Uploader.hold()
            try { NetTests.throughput(this, down, url, int("streams", 4), int("tdur", 10), ::say) } catch (e: Exception) { say("Error: ${e.message}") }
            finally { Uploader.release() }
            Uploader.flushAsync(this) { updateUploads() }
        }
    }

    private fun ping() {
        val iv = str("pint").toDoubleOrNull()?.coerceAtLeast(0.2) ?: 0.2
        bg.execute {
            Uploader.hold()
            try { NetTests.ping(this, str("host"), int("pcount", 100), String.format(java.util.Locale.US, "%.1f", iv), ::say) }
            catch (e: Exception) { say("Error: ${e.message}") }
            finally { Uploader.release() }
            Uploader.flushAsync(this) { updateUploads() }
        }
    }

    private fun toggleCov() {
        if (covRunning) { covRunning = false; covBtn.text = "Start coverage log (1 s)"; return }
        covRunning = true; covBtn.text = "Stop coverage log"
        thread(isDaemon = true) {
            val id = Files.stamp()
            val f = Files.newFile(this, "coverage", id)
            val csv = Csv(f, listOf("time") + Radio.KEYS + listOf("neighbors"))
            say("Coverage logging → ${f.name}")
            var n = 0
            while (covRunning) {
                Radio.refresh()
                val s = Radio.snapshot()
                csv.row(listOf(Files.now()) + Radio.KEYS.map { s[it] } + listOf(Radio.neighbors()))
                if (++n % 10 == 0) say("  cov ${s["rat"]} B${s["band"]} PCI ${s["pci"]} RSRP ${s["rsrp"]} SINR ${s["sinr"]} CA ${s["ca_count"]}")
                Thread.sleep(1000)
            }
            csv.close(); Exporter.export(this, listOf(f)); Uploader.enqueueFile(this, f, "coverage", id)
            say("Coverage log stopped ($n rows) → Download/QoEProbe"); updateUploads()
        }
    }

    // ---------------------------------------------------------------- uploads + files

    private fun uploadNow() {
        if (!Server.configured) { say("No admin server configured."); return }
        say("Uploading…")
        Uploader.flushAsync(this) { n ->
            say("Uploaded $n item(s); ${Uploader.pending(this)} waiting${if (Uploader.lastError.isNotEmpty()) " (${Uploader.lastError})" else ""}")
            updateUploads()
        }
    }

    private fun export() = bg.execute {
        val n = Exporter.exportAll(this)
        say("Exported $n file(s) to Download/QoEProbe")
    }

    /** Manual run uses the field; automatic run (once a day) uses the admin setting when present. */
    private fun cleanup(manual: Boolean) {
        val today = Files.stamp().take(8)
        if (!manual && (!Server.configured || prefs.getString("last_cleanup", "") == today)) return
        val days = if (manual) int("keep_days", 7)
                   else Access.setting("phone_retention_days").toDoubleOrNull()?.toInt() ?: int("keep_days", 7)
        bg.execute {
            val n = Results.cleanup(this, days)
            prefs.edit().putString("last_cleanup", today).apply()
            if (manual || n > 0) say("Deleted $n uploaded file(s) older than $days day(s) from this phone")
        }
    }

    override fun onDestroy() { covRunning = false; super.onDestroy() }
}
