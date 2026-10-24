package com.qoeprobe.app

import android.app.Activity
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.Typeface
import android.net.TrafficStats
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.Process
import android.os.SystemClock
import android.view.WindowManager
import android.webkit.JavascriptInterface
import android.webkit.WebView
import android.widget.Button
import android.widget.LinearLayout
import android.widget.TextView
import androidx.webkit.WebViewCompat
import androidx.webkit.WebViewFeature
import org.json.JSONObject
import java.io.File

/**
 * Plays a YouTube / Facebook / Instagram URL in an instrumented WebView and logs
 * playback QoE (startup, stalls, ABR resolution switches, codec, bitrate, dropped frames)
 * every 500 ms together with radio + GPS.
 */
class VideoTestActivity : Activity() {
    companion object {
        val SAMPLE_HDR = listOf(
            "time", "elapsed_s", "test_id", "platform", "video_id", "state", "position_s", "duration_s",
            "buffer_ahead_s", "width", "height", "yt_quality", "delivery", "video_codec", "audio_codec",
            "dropped_frames", "total_frames", "media_bytes_total", "media_kbps", "app_rx_mbps",
            "stall_count", "stall_time_s"
        )
        val EVENT_HDR = listOf("time", "elapsed_s", "test_id", "event", "video_id", "width", "height", "position_s", "detail")
        val SUMMARY_HDR = listOf(
            "test_id", "start_time", "platform", "url", "logged_in", "end_reason",
            "page_load_ms", "time_to_first_frame_ms", "join_time_ms", "play_time_s",
            "stall_count", "stall_time_s", "rebuffer_ratio_pct", "stalls_per_min", "res_switches",
            "avg_height", "min_height", "max_height", "last_res", "video_codecs", "audio_codecs",
            "dropped_frames", "total_frames", "dropped_pct", "avg_media_kbps", "avg_app_rx_mbps", "peak_app_rx_mbps",
            "rat_seen", "pcis_seen", "avg_rsrp", "min_rsrp", "avg_rsrq", "avg_sinr", "avg_nr_ss_rsrp", "avg_nr_ss_sinr",
            "start_lat", "start_lon"
        )
    }

    private lateinit var wv: WebView
    private lateinit var status: TextView
    private lateinit var platform: String
    private lateinit var url: String
    private var durMs = 60_000L
    private val id = Files.stamp()
    private val startTime = Files.now()
    private val navStart = SystemClock.elapsedRealtime()
    private lateinit var samples: Csv
    private lateinit var events: Csv
    private val lock = Any()
    private val ui = Handler(Looper.getMainLooper())

    @Volatile private var finished = false
    @Volatile private var started = false
    @Volatile private var firstPlay = -1L
    private var pageLoadMs = -1L
    private var firstLoadstart = -1L
    private var activeId = Int.MIN_VALUE
    private var lastCt = -1.0
    private var lastH = 0
    private var lastSampleAt = 0L
    private var lastVideoSampleAt = 0L
    private var lastRadioAt = 0L
    private var frozen = false
    private var stalls = 0
    private var stallMs = 0.0
    private var playMs = 0.0
    private var switches = 0
    private var hTime = 0.0
    private var minH = Int.MAX_VALUE
    private var maxH = 0
    private var lastRes = ""
    private var dropped = 0L
    private var frames = 0L
    private val vcodecs = LinkedHashSet<String>()
    private val acodecs = LinkedHashSet<String>()
    private var rx0 = 0L
    private var lastRx = 0L
    private var peakRx = 0.0
    private var lastMedia = -1L
    private var mediaBytes = 0L
    private val rsrp = Acc(); private val rsrq = Acc(); private val sinr = Acc()
    private val nrRsrp = Acc(); private val nrSinr = Acc()
    private val pcis = LinkedHashSet<String>(); private val rats = LinkedHashSet<String>()
    private var startLat = ""; private var startLon = ""

    override fun onCreate(b: Bundle?) {
        super.onCreate(b)
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        Radio.init(this); Radio.start(); Radio.refresh()
        platform = intent.getStringExtra("platform") ?: "Web"
        url = intent.getStringExtra("url") ?: "about:blank"
        durMs = intent.getIntExtra("dur", 60) * 1000L
        rx0 = TrafficStats.getUidRxBytes(Process.myUid()); lastRx = rx0
        val tag = platform.lowercase()
        samples = Csv(Files.newFile(this, "video_samples_$tag", id), SAMPLE_HDR + Radio.KEYS)
        events = Csv(Files.newFile(this, "video_events_$tag", id), EVENT_HDR)
        Radio.snapshot().let { startLat = it["lat"] ?: ""; startLon = it["lon"] ?: "" }

        status = TextView(this).apply {
            textSize = 11f; typeface = Typeface.MONOSPACE; setPadding(16, 8, 16, 8)
            text = "Loading $platform… tap play if it doesn't start.\nTest runs ${durMs / 1000}s after playback starts."
        }
        val stop = Button(this).apply { text = "Stop & save"; isAllCaps = false; setOnClickListener { finishTest("user_stopped") } }
        wv = WebView(this)
        Web.setup(wv)
        wv.addJavascriptInterface(Bridge(), "QoE")
        val js = assets.open("qoe.js").bufferedReader().use { it.readText() }
        val early = WebViewFeature.isFeatureSupported(WebViewFeature.DOCUMENT_START_SCRIPT)
        if (early) WebViewCompat.addDocumentStartJavaScript(wv, js, setOf("*"))
        wv.webViewClient = object : Web.Client() {
            override fun onPageStarted(view: WebView?, u: String?, favicon: Bitmap?) {
                if (!early) view?.evaluateJavascript(js, null)
            }
            override fun onPageFinished(view: WebView?, u: String?) {
                if (!early) view?.evaluateJavascript(js, null)
                synchronized(lock) {
                    if (pageLoadMs < 0 && !finished) {
                        pageLoadMs = SystemClock.elapsedRealtime() - navStart
                        event("page_loaded", 0, 0, 0, 0.0, u ?: "")
                    }
                }
            }
        }
        setContentView(LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            addView(status)
            addView(wv, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, 0, 1f))
            addView(stop)
        })
        event("test_start", 0, 0, 0, 0.0, "$url logged_in=${Web.loggedIn(platform)} doc_start_hook=$early")
        wv.loadUrl(url)
        ui.postDelayed(watchdog, 1000)
    }

    private val watchdog = object : Runnable {
        override fun run() {
            if (finished) return
            val now = SystemClock.elapsedRealtime()
            when {
                started && now - firstPlay >= durMs -> finishTest("completed")
                !started && now - navStart >= 180_000 -> finishTest("no_playback_180s")
                else -> ui.postDelayed(this, 1000)
            }
        }
    }

    private fun el() = d2((SystemClock.elapsedRealtime() - navStart) / 1000.0)
    private fun event(name: String, vid: Int, w: Int, h: Int, ct: Double, detail: String) =
        events.row(listOf(Files.now(), el(), id, name, vid, w, h, d2(ct), detail))

    inner class Bridge {
        @JavascriptInterface
        fun post(s: String) {
            try {
                val o = JSONObject(s)
                when (o.optString("t")) {
                    "s" -> onSample(o)
                    "ev" -> onEvent(o)
                    "codec" -> onCodec(o)
                }
            } catch (_: Exception) {}
        }
    }

    private fun codecOf(mime: String) =
        Regex("codecs\\s*=\\s*\"?([^\";]+)").find(mime)?.groupValues?.get(1)?.trim() ?: mime

    private fun onCodec(o: JSONObject) {
        synchronized(lock) {
            if (finished) return
            val mime = o.optString("mime")
            val c = codecOf(mime)
            if (o.optString("k") == "v") vcodecs.add(c) else acodecs.add(c)
            event("codec_${o.optString("k")}", 0, 0, 0, 0.0, mime)
        }
    }

    private fun onEvent(o: JSONObject) {
        synchronized(lock) {
            if (finished) return
            val ev = o.optString("ev")
            if (ev == "loadstart" && firstLoadstart < 0) firstLoadstart = SystemClock.elapsedRealtime()
            event(ev, o.optInt("id"), o.optInt("w"), o.optInt("h"), o.optDouble("ct", 0.0),
                if (o.optInt("err") != 0) "media_error=${o.optInt("err")}" else "")
        }
    }

    private fun onSample(o: JSONObject) {
        var st = ""
        synchronized(lock) {
            if (finished) return
            val now = SystemClock.elapsedRealtime()
            val hasV = o.optInt("nov", 0) == 0
            // A top frame without video reports "no video"; ignore it while an iframe is reporting a video.
            if (!hasV && now - lastVideoSampleAt < 2000) return
            if (hasV) lastVideoSampleAt = now
            val dt = if (lastSampleAt > 0) ((now - lastSampleAt) / 1000.0).coerceIn(0.05, 5.0) else 0.5
            lastSampleAt = now

            val rx = TrafficStats.getUidRxBytes(Process.myUid())
            val rxMbps = if (rx >= lastRx) (rx - lastRx) * 8 / 1e6 / dt else 0.0
            lastRx = rx
            if (rxMbps > peakRx) peakRx = rxMbps
            val mb = o.optLong("vb") + o.optLong("ab")
            val mDelta = if (lastMedia >= 0 && mb >= lastMedia) mb - lastMedia else 0L
            lastMedia = mb; mediaBytes += mDelta
            val mediaKbps = mDelta * 8 / 1000.0 / dt
            val vc = o.optString("vc"); val ac = o.optString("ac")
            if (vc.isNotEmpty()) vcodecs.add(vc)
            if (ac.isNotEmpty()) acodecs.add(ac)

            var state = "no_video"; var ct = 0.0; var w = 0; var h = 0; var vid = 0
            if (hasV) {
                vid = o.optInt("id"); ct = o.optDouble("ct", 0.0)
                val paused = o.optBoolean("p"); val ended = o.optBoolean("e"); val seeking = o.optBoolean("sk")
                val rate = o.optDouble("r", 1.0)
                w = o.optInt("w"); h = o.optInt("h")
                if (vid != activeId) {
                    activeId = vid; lastCt = -1.0; frozen = false
                    event("active_video", vid, w, h, ct, "")
                }
                val adv = if (lastCt >= 0) ct - lastCt else 0.0
                if (!started && !paused && lastCt >= 0 && adv > 0.01) {
                    started = true; firstPlay = now
                    event("first_frame", vid, w, h, ct, "ttff_ms=${now - navStart}")
                }
                val was = frozen
                frozen = started && !paused && !ended && !seeking && lastCt >= 0 && rate > 0 &&
                        adv > -0.01 && adv < 0.25 * dt * rate
                if (frozen && !was) { stalls++; event("stall_start", vid, w, h, ct, "buffer_s=${d2(o.optDouble("ba", 0.0))}") }
                if (!frozen && was) event("stall_end", vid, w, h, ct, "")
                if (frozen) stallMs += dt * 1000
                else if (started && !paused && !ended && adv > 0) {
                    playMs += dt * 1000
                    if (h > 0) { hTime += h * dt; if (h < minH) minH = h }
                }
                if (h > 0) {
                    if (lastH > 0 && h != lastH) { switches++; event("res_switch", vid, w, h, ct, "${lastH}p->${h}p") }
                    lastH = h; if (h > maxH) maxH = h; lastRes = "${w}x$h"
                }
                lastCt = ct
                dropped = o.optLong("df"); frames = o.optLong("tf")
                state = when { !started -> "loading"; ended -> "ended"; frozen -> "stall"; paused -> "paused"; else -> "playing" }
            }

            if (now - lastRadioAt >= 1000) { Radio.refresh(); lastRadioAt = now }
            val r = Radio.snapshot()
            if (started) {
                rsrp.add(r["rsrp"]); rsrq.add(r["rsrq"]); sinr.add(r["sinr"])
                nrRsrp.add(r["nr_ss_rsrp"]); nrSinr.add(r["nr_ss_sinr"])
                r["pci"]?.takeIf { it.isNotEmpty() }?.let { pcis.add(it) }
                r["rat"]?.takeIf { it.isNotEmpty() }?.let { rats.add(it) }
            }
            val ba = o.optDouble("ba", 0.0)
            samples.row(listOf(
                Files.now(), el(), id, platform, vid, state, d2(ct), d1(o.optDouble("du", -1.0)), d2(ba),
                w, h, o.optString("ql"), o.optString("dl"), vc, ac, o.optLong("df"), o.optLong("tf"),
                mb, d0(mediaKbps), d2(rxMbps), stalls, d1(stallMs / 1000)
            ) + Radio.KEYS.map { r[it] })
            st = "$state  t=${d1(ct)}s  ${w}x$h ${o.optString("ql")}\n" +
                    "buffer ${d1(ba)}s  stalls $stalls (${d1(stallMs / 1000)}s)  switches $switches\n" +
                    "codec ${vc.ifEmpty { "?" }} / ${ac.ifEmpty { "?" }}  drop ${o.optLong("df")}/${o.optLong("tf")}\n" +
                    "rx ${d1(rxMbps)} Mbps  media ${d0(mediaKbps)} kbps\n" +
                    "${r["rat"]} PCI ${r["pci"]} RSRP ${r["rsrp"]} SINR ${r["sinr"]} NR ${r["nr_ss_rsrp"]}"
        }
        ui.post { if (!finished) status.text = st }
    }

    private fun finishTest(reason: String) {
        var text = ""
        synchronized(lock) {
            if (finished) return
            finished = true
            event("test_end", 0, 0, 0, 0.0, reason)
            val now = SystemClock.elapsedRealtime()
            val wall = ((now - navStart) / 1000.0).coerceAtLeast(0.001)
            val playS = playMs / 1000.0; val stallS = stallMs / 1000.0
            val rebuf = if (playS + stallS > 0) stallS / (playS + stallS) * 100 else 0.0
            val avgRx = (TrafficStats.getUidRxBytes(Process.myUid()) - rx0) * 8 / 1e6 / wall
            val avgMedia = mediaBytes * 8 / 1000.0 / wall
            val avgH = if (playS > 0) hTime / playS else 0.0
            val dropPct = if (frames > 0) dropped * 100.0 / frames else 0.0
            val ttff = if (firstPlay > 0) firstPlay - navStart else -1L
            val join = if (firstPlay > 0 && firstLoadstart > 0) firstPlay - firstLoadstart else -1L
            Csv.appendRow(File(Files.dir(this), "video_summary.csv"), SUMMARY_HDR, listOf(
                id, startTime, platform, url, Web.loggedIn(platform), reason,
                pageLoadMs, ttff, join, d1(playS), stalls, d1(stallS), d2(rebuf),
                d2(if (playS > 0) stalls / (playS / 60) else 0.0), switches,
                d0(avgH), if (minH == Int.MAX_VALUE) "" else minH, maxH, lastRes,
                vcodecs.joinToString(" | "), acodecs.joinToString(" | "),
                dropped, frames, d2(dropPct), d0(avgMedia), d2(avgRx), d2(peakRx),
                rats.joinToString("/"), pcis.joinToString("/"),
                rsrp.avg(), rsrp.minS(), rsrq.avg(), sinr.avg(), nrRsrp.avg(), nrSinr.avg(), startLat, startLon
            ))
            samples.close(); events.close()
            text = "$platform [$reason]: TTFF ${ttff}ms, stalls $stalls (${d1(stallS)}s, ${d1(rebuf)}%), " +
                    "switches $switches, avg ${d0(avgH)}p max ${maxH}p, codec ${vcodecs.lastOrNull() ?: "?"}, " +
                    "media ${d0(avgMedia)} kbps, rx ${d1(avgRx)} Mbps, RSRP ${rsrp.avg()}"
        }
        ui.removeCallbacks(watchdog)
        setResult(RESULT_OK, Intent().putExtra("summary", text))
        finish()
    }

    @Deprecated("Deprecated in Java")
    override fun onBackPressed() { finishTest("user_stopped") }

    override fun onDestroy() {
        if (!finished) finishTest("activity_closed")
        ui.removeCallbacksAndMessages(null)
        try { wv.stopLoading(); wv.removeJavascriptInterface("QoE"); wv.destroy() } catch (_: Exception) {}
        super.onDestroy()
    }
}
