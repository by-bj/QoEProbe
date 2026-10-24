package com.qoeprobe.app

import android.annotation.SuppressLint
import android.content.Context
import android.webkit.CookieManager
import android.webkit.WebChromeClient
import android.webkit.WebResourceRequest
import android.webkit.WebSettings
import android.webkit.WebView
import android.webkit.WebViewClient
import java.io.BufferedWriter
import java.io.File
import java.io.FileWriter
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import kotlin.math.roundToInt

fun d0(x: Double) = String.format(Locale.US, "%.0f", x)
fun d1(x: Double) = String.format(Locale.US, "%.1f", x)
fun d2(x: Double) = String.format(Locale.US, "%.2f", x)

fun pct(l: List<Double>, p: Double): Double {
    if (l.isEmpty()) return 0.0
    val s = l.sorted()
    return s[((s.size - 1) * p).roundToInt()]
}

/** Running average/min of numeric strings (blank values ignored). */
class Acc {
    private var sum = 0.0; private var n = 0; private var min = Double.MAX_VALUE
    fun add(s: String?) { val x = s?.toDoubleOrNull() ?: return; sum += x; n++; if (x < min) min = x }
    fun avg() = if (n > 0) d1(sum / n) else ""
    fun minS() = if (n > 0) d1(min) else ""
}

object Files {
    private val stampFmt = SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US)
    private val tsFmt = SimpleDateFormat("yyyy-MM-dd HH:mm:ss.SSS", Locale.US)
    fun dir(c: Context) = File(c.getExternalFilesDir(null), "csv").apply { mkdirs() }
    fun stamp(): String = synchronized(stampFmt) { stampFmt.format(Date()) }
    fun now(): String = synchronized(tsFmt) { tsFmt.format(Date()) }
    fun newFile(c: Context, prefix: String, id: String) = File(dir(c), "${prefix}_$id.csv")
}

class Csv(f: File, header: List<String>, append: Boolean = false) {
    private val w: BufferedWriter
    init {
        val hasHeader = append && f.exists() && f.length() > 0
        w = BufferedWriter(FileWriter(f, append))
        if (!hasHeader) { w.write(header.joinToString(",")); w.newLine(); w.flush() }
    }
    @Synchronized fun row(vals: List<Any?>) {
        try { w.write(vals.joinToString(",") { esc(it) }); w.newLine(); w.flush() } catch (_: Exception) {}
    }
    @Synchronized fun close() { try { w.close() } catch (_: Exception) {} }

    companion object {
        fun esc(x: Any?): String {
            val s = x?.toString() ?: ""
            return if (s.contains(',') || s.contains('"') || s.contains('\n')) "\"" + s.replace("\"", "\"\"") + "\"" else s
        }
        fun appendRow(f: File, header: List<String>, vals: List<Any?>) =
            Csv(f, header, true).run { row(vals); close() }
    }
}

object Web {
    /** Strip WebView markers so FB/IG serve the normal mobile-web player instead of an "open app" wall. */
    fun ua(c: Context): String = WebSettings.getDefaultUserAgent(c)
        .replace("; wv", "").replace(Regex("Version/[\\d.]+ "), "")

    @SuppressLint("SetJavaScriptEnabled")
    fun setup(wv: WebView) {
        val s = wv.settings
        s.javaScriptEnabled = true
        s.domStorageEnabled = true
        s.mediaPlaybackRequiresUserGesture = false
        s.loadWithOverviewMode = true
        s.useWideViewPort = true
        s.userAgentString = ua(wv.context)
        CookieManager.getInstance().setAcceptCookie(true)
        CookieManager.getInstance().setAcceptThirdPartyCookies(wv, true)
        wv.webChromeClient = WebChromeClient()
    }

    fun cookie(u: String): String = CookieManager.getInstance().getCookie(u) ?: ""

    fun loggedIn(platform: String): Boolean = when (platform) {
        "Facebook" -> cookie("https://www.facebook.com").contains("c_user=") ||
                cookie("https://m.facebook.com").contains("c_user=")
        "Instagram" -> cookie("https://www.instagram.com").contains("sessionid=")
        "YouTube" -> cookie("https://www.youtube.com").contains("SAPISID=")
        else -> false
    }

    /** Keeps navigation inside the WebView; blocks fb://, intent:// etc. that would jump to native apps. */
    open class Client : WebViewClient() {
        override fun shouldOverrideUrlLoading(view: WebView?, request: WebResourceRequest?): Boolean {
            val sch = request?.url?.scheme ?: return false
            return sch != "http" && sch != "https"
        }
    }
}
