package com.qoeprobe.app

import android.content.Context
import android.graphics.Bitmap
import android.net.TrafficStats
import android.os.Handler
import android.os.Looper
import android.os.Process
import android.os.SystemClock
import android.webkit.JavascriptInterface
import android.webkit.WebView
import androidx.webkit.WebViewCompat
import androidx.webkit.WebViewFeature
import org.json.JSONObject
import java.io.File

data class VideoResult(val testId: String, val reason: String, val ttffMs: Long, val stalls: Int,
                       val rebufPct: Double, val avgHeight: Double, val radio: Map<String, String>, val text: String)

/**
 * One instrumented video test on a given WebView: plays [url], logs playback QoE every 500 ms with
 * radio + GPS, and records the summary when done. Used by the single-test screen and the sequence runner.
 */
class VideoSession(
    private val ctx: Context,
    private val wv: WebView,
    val platform: String,
    val url: String,
    private val durMs: Long,
    private val noPlayMs: Long,
    private val tags: List<String>,                 // seq_id, loop, step (blank for single tests)
    private val onStatus: (String) -> Unit,
    private val onDone: (VideoResult) -> Unit
) {
    companion object {
        val SAMPLE_HDR = listOf(
            "time", "elapsed_s", "test_id", "platform", "video_id", "state", "position_s", "duration_s",
            "buffer_ahead_s", "width", "height", "yt_quality", "delivery", "video_codec", "audio_codec",
            "dropped_frames", "total_frames", "media_bytes_total", "media_kbps", "nominal_kbps", "app_rx_mbps",
            "in_burst", "stall_count", "stall_time_s"
        )
        val EVENT_HDR = listOf("time", "elapsed_s", "test_id", "event", "video_id", "width", "height", "position_s", "detail")
        val SUMMARY_HDR = listOf(
            "test_id", "start_time", "platform", "url", "logged_in", "end_reason",
            "page_load_ms", "time_to_first_frame_ms", "join_time_ms", "play_time_s",
            "stall_count", "stall_time_s", "rebuffer_ratio_pct", "stalls_per_min", "res_switches", "videos_played",
            "avg_height", "min_height", "max_height", "last_res", "video_codecs", "audio_codecs",
            "dropped_frames", "total_frames", "dropped_pct", "avg_media_kbps", "avg_nominal_kbps", "avg_app_rx_mbps", "peak_app_rx_mbps",
            "bursts", "avg_burst_mbps", "min_burst_mbps", "avg_buffer_at_burst_s", "min_buffer_at_burst_s",
            "rat_seen", "pcis_seen", "bands_seen", "avg_rsrp", "min_rsrp", "avg_rsrq", "avg_sinr", "avg_nr_ss_rsrp", "avg_nr_ss_sinr",
            "start_lat", "start_lon", "seq_id", "loop", "step"
        )
        private const val BURST_MIN_BYTES = 32 * 1024L     // per 0.5 s sample (~0.5 Mbps)
        private const val BURST_MIN_TOTAL = 200 * 1024L    // ignore small page traffic
    }

    val id = Files.stamp()
    private val startTime = Files.now()
    private val navStart = SystemClock.elapsedRealtime()
    private val samplesFile = Files.newFile(ctx, "video_samples_${platform.lowercase()}", id)
    private val eventsFile = Files.newFile(ctx, "video_events_${platform.lowercase()}", id)
    private val samples = Csv(samplesFile, SAMPLE_HDR + Radio.KEYS)
    private val events = Csv(eventsFile, EVENT_HDR)
    private val lock = Any()
    private val ui = Handler(Looper.getMainLooper())
    private val startRadio = Radio.snapshot()

    @Volatile var finished = false; private set
    @Volatile private var started = false
    @Volatile private var firstPlay = -1L
    private var pageLoadMs = -1L
    private var firstLoadstart = -1L
    private var activeId = Int.MIN_VALUE
    private var lastCt = -1.0
    private var lastH = 0
    private var videosPlayed = 0
    private val srcInfo = HashMap<Int, Pair<String, Int>>()
    private val rxWin = ArrayDeque<Pair<Long, Long>>()
    private var nomSum = 0.0; private var nomT = 0.0
    private var lastSampleAt = 0L; private var lastVideoSampleAt = 0L; private var lastRadioAt = 0L
    private var frozen = false
    private var stalls = 0; private var stallMs = 0.0; private var playMs = 0.0; private var switches = 0
    private var hTime = 0.0; private var minH = Int.MAX_VALUE; private var maxH = 0; private var lastRes = ""
    private var dropped = 0L; private var frames = 0L
    private val vcodecs = LinkedHashSet<String>(); private val acodecs = LinkedHashSet<String>()
    private val rx0 = TrafficStats.getUidRxBytes(Process.myUid())
    private var lastRawRx = rx0
    private var peakRx = 0.0
    private var lastMedia = -1L; private var mediaBytes = 0L
    // bursts
    private var inBurst = false; private var bStart = 0L; private var bLast = 0L; private var bBytes = 0L
    private var bBuf = 0.0; private var quiet = 0
    private var burstCount = 0; private var burstBytes = 0L; private var burstMs = 0L
    private var minBurstMbps = Double.MAX_VALUE; private var bufSum = 0.0; private var bufMin = Double.MAX_VALUE
    // radio
    private val rsrp = Acc(); private val rsrq = Acc(); private val sinr = Acc()
    private val nrRsrp = Acc(); private val nrSinr = Acc()
    private val pcis = LinkedHashSet<String>(); private val rats = LinkedHashSet<String>(); private val bands = LinkedHashSet<String>()

    fun start() {
        Web.setup(wv)
        wv.addJavascriptInterface(Bridge(), "QoE")
        val js = ctx.assets.open("qoe.js").bufferedReader().use { it.readText() }
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
        event("test_start", 0, 0, 0, 0.0, "$url logged_in=${Web.loggedIn(platform)} doc_start_hook=$early")
        wv.loadUrl(url)
        ui.postDelayed(watchdog, 1000)
    }

    fun stop(reason: String) = finish(reason)

    private val watchdog = object : Runnable {
        override fun run() {
            if (finished) return
            val now = SystemClock.elapsedRealtime()
            when {
                started && now - firstPlay >= durMs -> finish("completed")
                !started && now - navStart >= noPlayMs -> finish("no_playback_${noPlayMs / 1000}s")
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
                    "src" -> onSrc(o)
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

    /** Single-file video: Meta CDN encodes profile + bitrate in "efg"; TikTok-style URLs may carry "br" (kbps, unverified). */
    private fun onSrc(o: JSONObject) {
        synchronized(lock) {
            if (finished) return
            val vid = o.optInt("id"); val url = o.optString("url")
            var tag = ""; var kbps = -1; var raw = ""
            val uri = try { android.net.Uri.parse(url) } catch (_: Exception) { null }
            try {
                val efg = uri?.getQueryParameter("efg")
                if (!efg.isNullOrEmpty()) {
                    var b = efg.replace('-', '+').replace('_', '/')
                    while (b.length % 4 != 0) b += "="
                    raw = String(android.util.Base64.decode(b, android.util.Base64.DEFAULT))
                    val j = JSONObject(raw)
                    tag = j.optString("vencode_tag")
                    val br = j.optLong("bitrate", 0L)
                    if (br > 0) kbps = (br / 1000).toInt()
                } else {
                    uri?.getQueryParameter("br")?.toIntOrNull()?.takeIf { it in 50..50000 }?.let { kbps = it; tag = "br" }
                }
            } catch (_: Exception) {}
            val t = tag.lowercase()
            val codec = when {
                t.isEmpty() || t == "br" -> "progressive-mp4"
                "av1" in t -> "av1"
                "vp9" in t -> "vp9"
                "hevc" in t || "h265" in t -> "hevc"
                else -> "h264"
            } + listOf("baseline", "main", "high").firstOrNull { it in t }?.let { "-$it" }.orEmpty()
            srcInfo[vid] = codec to kbps
            vcodecs.add(codec)
            event("src", vid, 0, 0, 0.0, "codec=$codec nominal_kbps=$kbps tag=$tag host=${uri?.host ?: ""} efg=${raw.take(300)}")
        }
    }

    private fun endBurst() {
        inBurst = false; quiet = 0
        if (bBytes < BURST_MIN_TOTAL) return
        val ms = (bLast - bStart).coerceAtLeast(250L)
        val mbps = bBytes * 8 / 1e6 / (ms / 1000.0)
        burstCount++; burstBytes += bBytes; burstMs += ms
        if (mbps < minBurstMbps) minBurstMbps = mbps
        bufSum += bBuf; if (bBuf < bufMin) bufMin = bBuf
        event("burst", activeId, 0, 0, 0.0, "bytes=$bBytes dur_s=${d2(ms / 1000.0)} mbps=${d2(mbps)} buffer_start_s=${d1(bBuf)}")
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
            val prevAt = if (lastSampleAt > 0) lastSampleAt else now - 500
            val dt = ((now - prevAt) / 1000.0).coerceIn(0.05, 5.0)
            lastSampleAt = now
            val ba = o.optDouble("ba", 0.0)

            val rx = TrafficStats.getUidRxBytes(Process.myUid())
            rxWin.addLast(now to rx)
            while (rxWin.size > 2 && now - rxWin[1].first >= 2000) rxWin.removeFirst()
            val w0 = rxWin.first()
            val rxMbps = if (now > w0.first && rx >= w0.second) (rx - w0.second) * 8 / 1e6 / ((now - w0.first) / 1000.0) else 0.0
            if (rxMbps > peakRx) peakRx = rxMbps
            val d = if (rx >= lastRawRx) rx - lastRawRx else 0L
            lastRawRx = rx
            if (started) {
                if (d >= BURST_MIN_BYTES) {
                    if (!inBurst) { inBurst = true; bStart = prevAt; bBytes = 0; bBuf = ba }
                    bBytes += d; bLast = now; quiet = 0
                } else if (inBurst && ++quiet >= 2) endBurst()
            }

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
                    activeId = vid; lastCt = -1.0; frozen = false; lastH = 0
                    if (vid != 0) videosPlayed++
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
                if (frozen && !was) { stalls++; event("stall_start", vid, w, h, ct, "buffer_s=${d2(ba)}") }
                if (!frozen && was) event("stall_end", vid, w, h, ct, "")
                if (frozen) stallMs += dt * 1000
                else if (started && !paused && !ended && adv > 0) {
                    playMs += dt * 1000
                    if (h > 0) { hTime += h * dt; if (h < minH) minH = h }
                    srcInfo[vid]?.second?.takeIf { it > 0 }?.let { nomSum += it * dt; nomT += dt }
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
                r["band"]?.takeIf { it.isNotEmpty() }?.let { bands.add(it) }
            }
            val si = srcInfo[vid]
            val vcOut = vc.ifEmpty { si?.first ?: "" }
            val nomOut = si?.second?.takeIf { it > 0 }?.toString() ?: ""
            samples.row(listOf(
                Files.now(), el(), id, platform, vid, state, d2(ct), d1(o.optDouble("du", -1.0)), d2(ba),
                w, h, o.optString("ql"), o.optString("dl"), vcOut, ac, o.optLong("df"), o.optLong("tf"),
                mb, d0(mediaKbps), nomOut, d2(rxMbps), if (inBurst) 1 else 0, stalls, d1(stallMs / 1000)
            ) + Radio.KEYS.map { r[it] })
            st = "$platform  $state  t=${d1(ct)}s  ${w}x$h ${o.optString("ql")}\n" +
                    "buffer ${d1(ba)}s  stalls $stalls (${d1(stallMs / 1000)}s)  switches $switches  bursts $burstCount\n" +
                    "codec ${vcOut.ifEmpty { "?" }} / ${ac.ifEmpty { "?" }}  drop ${o.optLong("df")}/${o.optLong("tf")}\n" +
                    "rx ${d1(rxMbps)} Mbps  media ${d0(mediaKbps)} kbps  nominal ${nomOut.ifEmpty { "-" }} kbps  videos $videosPlayed\n" +
                    "${r["rat"]} B${r["band"]} PCI ${r["pci"]} RSRP ${r["rsrp"]} SINR ${r["sinr"]} CA ${r["ca_count"]}"
        }
        ui.post { if (!finished) onStatus(st) }
    }

    private fun finish(reason: String) {
        val res: VideoResult
        synchronized(lock) {
            if (finished) return
            if (inBurst) endBurst()
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
            val avgBurst = if (burstMs > 0) burstBytes * 8 / 1e6 / (burstMs / 1000.0) else 0.0
            val row = listOf(
                id, startTime, platform, url, Web.loggedIn(platform), reason,
                pageLoadMs, ttff, join, d1(playS), stalls, d1(stallS), d2(rebuf),
                d2(if (playS > 0) stalls / (playS / 60) else 0.0), switches, videosPlayed,
                d0(avgH), if (minH == Int.MAX_VALUE) "" else minH, maxH, lastRes,
                vcodecs.joinToString(" | "), acodecs.joinToString(" | "),
                dropped, frames, d2(dropPct), d0(avgMedia), if (nomT > 0) d0(nomSum / nomT) else "", d2(avgRx), d2(peakRx),
                burstCount, if (burstCount > 0) d1(avgBurst) else "", if (burstCount > 0) d1(minBurstMbps) else "",
                if (burstCount > 0) d1(bufSum / burstCount) else "", if (burstCount > 0) d1(bufMin) else "",
                rats.joinToString("/"), pcis.joinToString("/"), bands.joinToString("/"),
                rsrp.avg(), rsrp.minS(), rsrq.avg(), sinr.avg(), nrRsrp.avg(), nrSinr.avg(),
                startRadio["lat"] ?: "", startRadio["lon"] ?: ""
            ) + (tags + listOf("", "", "")).take(3)
            samples.close(); events.close()
            Results.record(ctx, "video_summary.csv", "VideoResults", SUMMARY_HDR, row,
                listOf(samplesFile to "video_samples", eventsFile to "video_events"))
            val text = "$platform [$reason]: TTFF ${ttff}ms, stalls $stalls (${d1(stallS)}s, ${d1(rebuf)}%), " +
                    "switches $switches, avg ${d0(avgH)}p, codec ${vcodecs.lastOrNull() ?: "?"}, " +
                    "bursts $burstCount @ ${d1(avgBurst)} Mbps, RSRP ${rsrp.avg()}"
            res = VideoResult(id, reason, ttff, stalls, rebuf, avgH, startRadio, text)
        }
        ui.removeCallbacks(watchdog)
        try { wv.stopLoading(); wv.removeJavascriptInterface("QoE") } catch (_: Exception) {}
        onDone(res)
    }
}
