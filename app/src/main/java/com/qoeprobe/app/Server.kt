package com.qoeprobe.app

import android.content.Context
import android.content.SharedPreferences
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.os.Build
import android.provider.Settings
import android.webkit.CookieManager
import android.webkit.WebStorage
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.net.HttpURLConnection
import java.net.URL
import java.security.MessageDigest
import java.util.UUID
import kotlin.concurrent.thread

/** Connection to the admin Google Sheet (Apps Script web app). URL + key come from assets/server.txt. */
object Server {
    lateinit var app: Context
    var url = ""; private set
    var key = ""; private set

    fun init(c: Context) {
        if (::app.isInitialized) return
        app = c.applicationContext
        val lines = try {
            app.assets.open("server.txt").bufferedReader().readLines().map { it.trim() }
                .filter { it.isNotEmpty() && !it.startsWith("#") }
        } catch (_: Exception) { emptyList() }
        url = lines.getOrNull(0) ?: ""
        key = lines.getOrNull(1) ?: ""
        Access.load()
    }

    val configured get() = url.startsWith("https://") && !url.contains("PASTE_YOUR")

    /** POST JSON; follows the Apps Script redirect to the response manually (POST -> 302 -> GET). */
    fun post(req: JSONObject, timeoutMs: Int = 30000): JSONObject {
        val body = req.put("key", key).toString().toByteArray()
        var c = URL(url).openConnection() as HttpURLConnection
        c.instanceFollowRedirects = false
        c.connectTimeout = 15000; c.readTimeout = timeoutMs
        c.requestMethod = "POST"; c.doOutput = true
        c.setRequestProperty("Content-Type", "application/json")
        c.setFixedLengthStreamingMode(body.size)
        c.outputStream.use { it.write(body) }
        var code = c.responseCode
        var hops = 0
        while (code in 300..399 && hops < 5) {
            val loc = c.getHeaderField("Location") ?: break
            c.disconnect()
            c = URL(loc).openConnection() as HttpURLConnection
            c.instanceFollowRedirects = false
            c.connectTimeout = 15000; c.readTimeout = timeoutMs
            code = c.responseCode; hops++
        }
        val txt = try { (if (code < 400) c.inputStream else c.errorStream)?.bufferedReader()?.use { it.readText() } ?: "" }
                  finally { c.disconnect() }
        return try { JSONObject(txt) } catch (_: Exception) { JSONObject().put("ok", false).put("error", "http_$code") }
    }

    fun appVersion(): String = try { app.packageManager.getPackageInfo(app.packageName, 0).versionName ?: "" } catch (_: Exception) { "" }
    fun model(): String = "${Build.MANUFACTURER} ${Build.MODEL}".trim()
}

/** Device registration and access status, cached for offline use. */
object Access {
    private lateinit var p: SharedPreferences
    @Volatile var status = ""; private set
    @Volatile var message = ""; private set

    fun load() {
        p = Server.app.getSharedPreferences("access", Context.MODE_PRIVATE)
        status = p.getString("status", "") ?: ""
        message = p.getString("message", "") ?: ""
    }

    /** Stable per-install-key id (ANDROID_ID is per signing key on Android 8+). */
    val deviceId: String by lazy {
        val raw = Settings.Secure.getString(Server.app.contentResolver, Settings.Secure.ANDROID_ID) ?: UUID.randomUUID().toString()
        MessageDigest.getInstance("SHA-256").digest(raw.toByteArray()).joinToString("") { "%02x".format(it) }.take(8)
    }

    val registered get() = p.getBoolean("registered", false)
    fun name() = p.getString("name", "") ?: ""
    fun contact() = p.getString("contact", "") ?: ""
    fun market() = p.getString("market", "") ?: ""
    fun expiry() = p.getString("expiry", "") ?: ""
    fun lastOk() = p.getLong("last_ok", 0L)
    fun setting(k: String): String = try { JSONObject(p.getString("settings", "{}") ?: "{}").optString(k, "") } catch (_: Exception) { "" }
    fun plan(k: String): String? = try { JSONObject(p.getString("plan", "{}") ?: "{}").optString(k, "").trim().ifEmpty { null } } catch (_: Exception) { null }

    private fun base(action: String) = JSONObject().put("action", action).put("device_id", deviceId)
        .put("app_version", Server.appVersion()).put("model", Server.model())

    fun markets(): List<String> = try {
        val r = Server.post(JSONObject().put("action", "markets"), 15000)
        val a = r.optJSONArray("markets") ?: JSONArray()
        (0 until a.length()).map { a.getString(it) }
    } catch (_: Exception) { emptyList() }

    /** Blocking; call off the main thread. Returns the server status or an error text. */
    fun register(name: String, contact: String, market: String): String {
        p.edit().putString("name", name).putString("contact", contact).putString("market", market).apply()
        return try {
            val r = Server.post(base("register").put("name", name).put("contact", contact).put("market", market))
            if (r.optBoolean("ok")) { p.edit().putBoolean("registered", true).apply(); apply(r); status }
            else "error: ${r.optString("error")}"
        } catch (e: Exception) { "error: ${e.message ?: "no connection"}" }
    }

    /** Blocking; call off the main thread. Keeps the cached status when the server can't be reached. */
    fun check(): String {
        if (!Server.configured) return "open"
        return try {
            val r = Server.post(base("check"), 20000)
            if (r.optBoolean("ok")) apply(r) else message = "Server error: ${r.optString("error")}"
            status
        } catch (e: Exception) { "offline" }
    }

    private fun apply(r: JSONObject) {
        val st = r.optString("status")
        val was = status
        status = st; message = r.optString("message")
        val e = p.edit().putString("status", st).putString("message", message)
            .putString("expiry", r.optString("expiry"))
            .putString("settings", (r.optJSONObject("settings") ?: JSONObject()).toString())
            .putString("plan", (r.optJSONObject("plan") ?: JSONObject()).toString())
        if (st == "active") e.putLong("last_ok", System.currentTimeMillis())
        if (r.optString("name").isNotEmpty()) e.putString("name", r.optString("name")).putString("market", r.optString("market"))
        if (st == "unregistered") e.putBoolean("registered", false)
        e.apply()
        // Only an explicit revoke wipes data; kill switch / expiry just block.
        if (st == "revoked" && was != st && setting("wipe_on_revoke").lowercase() != "no") wipe()
    }

    /** Removes stored logins and local results (revoked phones). */
    fun wipe() {
        try { CookieManager.getInstance().removeAllCookies(null); CookieManager.getInstance().flush() } catch (_: Exception) {}
        try { WebStorage.getInstance().deleteAllData() } catch (_: Exception) {}
        Files.dir(Server.app).listFiles()?.forEach { it.delete() }
        Uploader.qdir(Server.app).listFiles()?.forEach { it.delete() }
    }

    /** (allowed, reason). Open mode when no server is configured. */
    fun allowed(): Pair<Boolean, String> {
        if (!Server.configured) return true to "Open mode: no admin server configured"
        if (!registered) return false to "Register this phone first"
        return when (status) {
            "active" -> {
                val grace = (setting("grace_days").toDoubleOrNull() ?: 3.0) * 86_400_000
                if (System.currentTimeMillis() - lastOk() <= grace) true to "Active"
                else false to "Offline too long: connect to the internet and tap Check access"
            }
            "pending", "" -> false to "Waiting for admin approval"
            "revoked" -> false to "Access revoked by admin"
            "expired" -> false to "Access expired on ${expiry()}"
            "disabled" -> false to "Testing is disabled by admin"
            "update_required" -> false to "Update required: install the latest QoE Probe"
            "unregistered" -> false to "Not registered: register this phone"
            else -> false to "Access status: $status"
        }
    }
}

/**
 * Upload queue: each item is a small JSON file in files/upload_queue. Items are sent in order
 * only while no measurement is running (busy = false), so uploads never compete with a test.
 */
object Uploader {
    private val holds = java.util.concurrent.atomic.AtomicInteger(0)
    /** True while any measurement is running; uploads wait. */
    val busy get() = holds.get() > 0
    fun hold() { holds.incrementAndGet() }
    fun release() { if (holds.decrementAndGet() < 0) holds.set(0) }
    @Volatile var inFlight = false; private set
    @Volatile var lastError = ""; private set
    private var started = false

    fun qdir(c: Context) = File(c.filesDir, "upload_queue").apply { mkdirs() }
    fun pending(c: Context) = qdir(c).list()?.count { it.endsWith(".json") } ?: 0

    private fun put(c: Context, o: JSONObject) {
        if (!Server.configured) return
        val uid = UUID.randomUUID().toString().take(12)
        o.put("uid", uid)
        val name = "${System.currentTimeMillis()}_$uid"
        val tmp = File(qdir(c), "$name.tmp")
        tmp.writeText(o.toString())
        tmp.renameTo(File(qdir(c), "$name.json"))   // atomic: flush never sees half-written items
    }

    fun enqueueRows(c: Context, sheet: String, header: List<String>, row: List<Any?>) =
        put(c, JSONObject().put("type", "rows").put("sheet", sheet)
            .put("header", JSONArray(header)).put("row", JSONArray(row.map { it?.toString() ?: "" })))

    fun enqueueFile(c: Context, f: File, kind: String, testId: String) =
        put(c, JSONObject().put("type", "file").put("path", f.absolutePath).put("name", f.name)
            .put("kind", kind).put("test_id", testId))

    /** Background loop: tries the queue every 30 s while idle. */
    fun start(c: Context) {
        if (started) return
        started = true
        val app = c.applicationContext
        thread(isDaemon = true, name = "uploader") {
            while (true) {
                try { Thread.sleep(30_000) } catch (_: InterruptedException) {}
                if (!busy) try { flush(app) } catch (_: Exception) {}
            }
        }
    }

    fun flushAsync(c: Context, done: ((Int) -> Unit)? = null) {
        val app = c.applicationContext
        thread(isDaemon = true) { val n = try { flush(app) } catch (_: Exception) { 0 }; done?.invoke(n) }
    }

    private fun wifi(c: Context): Boolean = try {
        val cm = c.getSystemService(ConnectivityManager::class.java)
        cm.getNetworkCapabilities(cm.activeNetwork)?.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) == true
    } catch (_: Exception) { false }

    /** Sends queued items; stops at the first failure (retried later) or when a test starts. */
    @Synchronized fun flush(c: Context): Int {
        if (!Server.configured || !Access.allowed().first) return 0
        val items = qdir(c).listFiles()?.filter { it.name.endsWith(".json") }?.sortedBy { it.name } ?: return 0
        val rawOn = Access.setting("raw_upload").lowercase() != "no"
        val wifiOnly = Access.setting("raw_wifi_only").lowercase() == "yes"
        var sent = 0
        inFlight = true
        try {
            for (q in items) {
                if (busy) break
                val o = try { JSONObject(q.readText()) } catch (_: Exception) { q.delete(); continue }
                val req = JSONObject().put("device_id", Access.deviceId).put("app_version", Server.appVersion()).put("uid", o.optString("uid"))
                if (o.optString("type") == "rows") {
                    req.put("action", "rows").put("sheet", o.optString("sheet"))
                        .put("header", o.optJSONArray("header")).put("row", o.optJSONArray("row"))
                } else {
                    if (!rawOn) { q.delete(); continue }
                    if (wifiOnly && !wifi(c)) continue
                    val f = File(o.optString("path"))
                    if (!f.isFile) { q.delete(); continue }
                    req.put("action", "file").put("name", o.optString("name")).put("type", o.optString("kind"))
                        .put("test_id", o.optString("test_id")).put("content", f.readText())
                }
                val r = try { Server.post(req, 60000) } catch (e: Exception) { lastError = e.message ?: "no connection"; break }
                when {
                    r.optBoolean("ok") -> { q.delete(); sent++; lastError = "" }
                    r.optString("error").startsWith("bad_sheet") || r.optString("error") == "bad_json" -> q.delete()
                    r.optString("error").startsWith("not_active") || r.optString("error") == "unregistered" -> {
                        lastError = r.optString("error"); break   // access problem: keep everything, retry later
                    }
                    else -> {
                        // Repeating server error on this item: set it aside after 5 tries so the rest still uploads.
                        lastError = r.optString("error")
                        val tries = o.optInt("tries", 0) + 1
                        if (tries >= 5) q.renameTo(File(q.parentFile, q.name.removeSuffix(".json") + ".failed"))
                        else q.writeText(o.put("tries", tries).toString())
                    }
                }
            }
        } finally { inFlight = false }
        return sent
    }
}

/** Records a finished test: local summary CSV + Download copy + upload queue. */
object Results {
    fun record(c: Context, summaryFile: String, sheet: String, header: List<String>, row: List<Any?>,
               raw: List<Pair<File, String>>) {
        val sf = File(Files.dir(c), summaryFile)
        Csv.appendRow(sf, header, row)
        Exporter.exportAsync(c, raw.map { it.first } + sf)
        Uploader.enqueueRows(c, sheet, header, row)
        val testId = row.firstOrNull()?.toString() ?: ""
        raw.forEach { (f, kind) -> Uploader.enqueueFile(c, f, kind, testId) }
    }

    /** Deletes local result files older than N days that are no longer waiting to upload. Summaries are kept. */
    fun cleanup(c: Context, days: Int): Int {
        if (days <= 0) return 0
        val queued = Uploader.qdir(c).listFiles()?.mapNotNull {
            try { JSONObject(it.readText()).optString("path").ifEmpty { null } } catch (_: Exception) { null }
        }?.toSet() ?: emptySet()
        val cutoff = System.currentTimeMillis() - days * 86_400_000L
        var n = 0
        Files.dir(c).listFiles()?.forEach { f ->
            if (f.isFile && !f.name.contains("summary") && f.lastModified() < cutoff && f.absolutePath !in queued) {
                Exporter.deleteCopy(c, f.name)
                if (f.delete()) n++
            }
        }
        return n
    }
}
