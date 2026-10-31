package com.qoeprobe.app

import android.app.Activity
import android.content.Context
import android.content.Intent
import android.graphics.Typeface
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.Gravity
import android.view.WindowManager
import android.webkit.WebView
import android.widget.Button
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.TextView
import kotlin.concurrent.thread

/**
 * Drive-test sequence: runs the configured steps (video platforms embedded in this screen, then
 * DL / UL / Ping) in loops, with a continuous coverage log. Uploads happen only between loops.
 */
class SequenceActivity : Activity() {
    companion object {
        val SEQ_HDR = listOf(
            "test_id", "seq_id", "loop", "step_no", "step", "target", "start_time", "end_time", "result",
            "time_to_first_frame_ms", "stall_count", "rebuffer_ratio_pct", "avg_height",
            "avg_mbps", "p50_mbps", "loss_pct", "rtt_avg_ms",
            "rat", "pci", "band", "rsrp", "rsrq", "sinr", "lat", "lon"
        )
        val VIDEO = mapOf("youtube" to "YouTube", "facebook" to "Facebook", "instagram" to "Instagram", "tiktok" to "TikTok")
        val URL_KEY = mapOf("YouTube" to "yt", "Facebook" to "fb", "Instagram" to "ig", "TikTok" to "tt")

        /** Plan value from the admin sheet, else the tester's own setting, else the default. */
        fun cfg(c: Context, k: String, def: String): String =
            Access.plan(k) ?: c.getSharedPreferences("cfg", Context.MODE_PRIVATE).getString(k, null)?.trim()?.ifEmpty { null } ?: def

        fun urls(c: Context, platform: String): List<String> {
            val k = URL_KEY[platform] ?: return emptyList()
            val list = cfg(c, k, "").split(Regex("[\\s,]+")).filter { it.startsWith("http") }
            return if (platform == "TikTok" && cfg(c, "tt_embed", "no").lowercase() == "yes") list.map { tiktokEmbed(it) } else list
        }

        fun tiktokEmbed(u: String): String =
            Regex("/video/(\\d+)").find(u)?.groupValues?.get(1)?.let { "https://www.tiktok.com/embed/v2/$it" } ?: u
    }

    private val ui = Handler(Looper.getMainLooper())
    private lateinit var head: TextView
    private lateinit var stepStatus: TextView
    private lateinit var logView: TextView
    private lateinit var container: FrameLayout
    private lateinit var stopBtn: Button
    private val seqId = Files.stamp()
    private lateinit var steps: List<String>
    private var loops = 0
    private var pauseS = 5
    private var timeoutS = 45
    private var durS = 60
    private var clearCache = true
    @Volatile private var stopping = false
    @Volatile private var done = false
    @Volatile private var covRunning = true
    @Volatile private var curStep = ""
    @Volatile private var curLoop = 1
    private var session: VideoSession? = null
    private var wv: WebView? = null
    private var phase = ""
    private var stepsRun = 0
    private var realThisLoop = 0
    private var held = false
    private fun hold() { if (!held) { held = true; Uploader.hold() } }
    private fun unhold() { if (held) { held = false; Uploader.release() } }

    override fun onCreate(b: Bundle?) {
        super.onCreate(b)
        Server.init(this)
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        Radio.init(this); Radio.start(); Radio.refresh()

        steps = cfg(this, "seq_steps", "YouTube,Facebook,Instagram,TikTok,DL,UL,Ping").split(",").map { it.trim() }
            .mapNotNull { s ->
                VIDEO[s.lowercase()] ?: when (s.lowercase()) { "dl" -> "DL"; "ul" -> "UL"; "ping" -> "Ping"; else -> null }
            }
        loops = cfg(this, "loops", "0").toDoubleOrNull()?.toInt() ?: 0
        pauseS = cfg(this, "pause", "5").toDoubleOrNull()?.toInt()?.coerceAtLeast(0) ?: 5
        timeoutS = cfg(this, "step_timeout", "45").toDoubleOrNull()?.toInt()?.coerceAtLeast(10) ?: 45
        durS = cfg(this, "dur", "60").toDoubleOrNull()?.toInt()?.coerceAtLeast(5) ?: 60
        clearCache = cfg(this, "cache_mode", "clear").lowercase() != "keep"

        head = TextView(this).apply { textSize = 13f; setTypeface(null, Typeface.BOLD); setPadding(16, 10, 16, 2) }
        stepStatus = TextView(this).apply { textSize = 11f; typeface = Typeface.MONOSPACE; setPadding(16, 2, 16, 6) }
        container = FrameLayout(this)
        logView = TextView(this).apply { textSize = 10f; typeface = Typeface.MONOSPACE; setPadding(16, 4, 16, 4); maxLines = 4 }
        stopBtn = Button(this).apply { text = "Stop sequence"; isAllCaps = false; setOnClickListener { onStopClick() } }
        setContentView(LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            addView(head); addView(stepStatus)
            addView(container, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, 0, 1f))
            addView(logView); addView(stopBtn)
        })

        if (steps.isEmpty()) { head.text = "No valid steps configured"; done = true; stopBtn.text = "Close"; return }
        hold()
        startCoverage()
        log("Sequence $seqId: ${steps.joinToString(" → ")}, loops ${if (loops > 0) loops else "∞"}, cache ${if (clearCache) "clear" else "keep"}")
        runStep(1, 0)
    }

    private fun log(s: String) = ui.post {
        val lines = (logView.text.toString().lines() + s).filter { it.isNotBlank() }.takeLast(4)
        logView.text = lines.joinToString("\n")
    }

    private fun placeholder(t: String) {
        container.removeAllViews()
        container.addView(TextView(this).apply { text = t; gravity = Gravity.CENTER; textSize = 15f },
            FrameLayout.LayoutParams(FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.MATCH_PARENT))
    }

    private fun runStep(loop: Int, idx: Int) {
        if (done) return
        if (stopping) { finishSeq("stopped"); return }
        if (idx >= steps.size) { endOfLoop(loop); return }
        if (idx == 0) realThisLoop = 0
        curLoop = loop
        val step = steps[idx]; curStep = step
        if (idx == 0 && clearCache) try { WebView(this).apply { clearCache(true); destroy() } } catch (_: Exception) {}
        head.text = "Loop $loop${if (loops > 0) "/$loops" else ""} · step ${idx + 1}/${steps.size}: $step"
        val start = Files.now()
        val tags = listOf(seqId, loop.toString(), step)
        if (step in VIDEO.values) {
            if (urls(this, step).isNotEmpty()) realThisLoop++
            val list = urls(this, step)
            if (list.isEmpty()) {
                log("$step skipped: no URL configured")
                record(loop, idx, step, "", start, "skipped_no_url", null, null)
                next(loop, idx, 0); return
            }
            val url = list[(loop - 1) % list.size]
            phase = "video"
            val w = WebView(this); wv = w
            container.removeAllViews()
            container.addView(w, FrameLayout.LayoutParams(FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.MATCH_PARENT))
            session = VideoSession(this, w, step, url, durS * 1000L, timeoutS * 1000L, tags,
                onStatus = { stepStatus.text = it },
                onDone = { r ->
                    session = null
                    container.removeView(w); try { w.destroy() } catch (_: Exception) {}; if (wv === w) wv = null
                    log(r.text)
                    record(loop, idx, step, url, start, r.reason, r, null)
                    next(loop, idx, pauseS)
                }).also { it.start() }
        } else {
            realThisLoop++
            phase = "net"
            placeholder("$step test running…")
            val c = applicationContext
            val prefsCfg = { k: String, d: String -> cfg(c, k, d) }
            thread(isDaemon = true) {
                val say = { s: String -> ui.post { stepStatus.text = s }; Unit }
                val r = try {
                    when (step) {
                        "DL" -> NetTests.throughput(c, true, prefsCfg("dl", "https://speed.cloudflare.com/__down?bytes=200000000"),
                            prefsCfg("streams", "4").toIntOrNull() ?: 4, prefsCfg("tdur", "10").toIntOrNull() ?: 10, say, tags)
                        "UL" -> NetTests.throughput(c, false, prefsCfg("ul", "https://speed.cloudflare.com/__up"),
                            prefsCfg("streams", "4").toIntOrNull() ?: 4, prefsCfg("tdur", "10").toIntOrNull() ?: 10, say, tags)
                        else -> NetTests.ping(c, prefsCfg("host", "8.8.8.8"), prefsCfg("pcount", "50").toIntOrNull() ?: 50,
                            String.format(java.util.Locale.US, "%.1f", (prefsCfg("pint", "0.2").toDoubleOrNull() ?: 0.2).coerceAtLeast(0.2)), say, tags)
                    }
                } catch (e: Exception) { NetResult("", step, emptyMap(), Radio.snapshot(), "$step error: ${e.message}") }
                ui.post {
                    log(r.text)
                    record(loop, idx, step, r.type, start, if (r.testId.isEmpty()) "error" else "completed", null, r)
                    next(loop, idx, pauseS)
                }
            }
        }
    }

    private fun next(loop: Int, idx: Int, pause: Int) {
        if (done) return
        stepsRun++
        if (stopping) { finishSeq("stopped"); return }
        phase = "pause"
        if (pause > 0 && idx + 1 < steps.size) placeholder("Next step in $pause s")
        ui.postDelayed({ runStep(loop, idx + 1) }, pause * 1000L)
    }

    /** Between loops: upload the queue (no test running), re-check access, then continue. */
    private fun endOfLoop(loop: Int) {
        if (loops in 1..loop) { finishSeq("completed"); return }
        if (realThisLoop == 0) { log("No step could run (no URLs configured)"); finishSeq("no_runnable_steps"); return }
        phase = "upload"
        placeholder("Loop $loop done. Uploading results…")
        unhold()
        var resumed = false
        val resume = {
            if (!resumed && !done) {
                resumed = true
                hold()
                val (ok, why) = Access.allowed()
                if (!ok) { log(why); finishSeq("access_${Access.status}") }
                else if (stopping) finishSeq("stopped")
                else runStep(loop + 1, 0)
            }
        }
        thread(isDaemon = true) {
            Access.check()
            val n = Uploader.flush(applicationContext)
            log("Loop $loop: uploaded $n item(s), ${Uploader.pending(applicationContext)} pending")
            ui.post { resume() }
        }
        // After 90 s, ask the uploader to stop after its current item, then continue once it is idle.
        val waitIdle = object : Runnable {
            override fun run() { if (Uploader.inFlight && !done) ui.postDelayed(this, 1000) else resume() }
        }
        ui.postDelayed({ if (!resumed) { hold(); waitIdle.run() } }, 90_000L)
    }

    private fun record(loop: Int, idx: Int, step: String, target: String, start: String, result: String,
                       v: VideoResult?, n: NetResult?) {
        val rad = v?.radio ?: n?.radio ?: Radio.snapshot()
        val row = listOf(
            v?.testId ?: n?.testId ?: "", seqId, loop, idx + 1, step, target, start, Files.now(), result,
            v?.ttffMs ?: "", v?.stalls ?: "", v?.let { d2(it.rebufPct) } ?: "", v?.let { d0(it.avgHeight) } ?: "",
            n?.kpi?.get("avg_mbps") ?: "", n?.kpi?.get("p50_mbps") ?: "", n?.kpi?.get("loss_pct") ?: "", n?.kpi?.get("rtt_avg_ms") ?: "",
            rad["rat"], rad["pci"], rad["band"], rad["rsrp"], rad["rsrq"], rad["sinr"], rad["lat"], rad["lon"]
        )
        Results.record(applicationContext, "sequence_summary.csv", "SequenceResults", SEQ_HDR, row, emptyList())
    }

    private fun startCoverage() {
        val c = applicationContext
        thread(isDaemon = true) {
            val f = Files.newFile(c, "coverage_seq", seqId)
            val csv = Csv(f, listOf("time", "seq_id", "loop", "step") + Radio.KEYS + listOf("neighbors"))
            while (covRunning) {
                Radio.refresh()
                val s = Radio.snapshot()
                csv.row(listOf(Files.now(), seqId, curLoop, curStep) + Radio.KEYS.map { s[it] } + listOf(Radio.neighbors()))
                try { Thread.sleep(1000) } catch (_: InterruptedException) {}
            }
            csv.close()
            Exporter.export(c, listOf(f))
            Uploader.enqueueFile(c, f, "coverage", seqId)
        }
    }

    private fun onStopClick() {
        if (done) { finish(); return }
        if (stopping) return
        stopping = true
        stopBtn.text = "Stopping after current step…"
        when (phase) {
            "video" -> session?.stop("sequence_stopped")
            "pause", "upload" -> { ui.removeCallbacksAndMessages(null); finishSeq("stopped") }
        }
    }

    private fun finishSeq(reason: String) {
        if (done) return
        done = true
        ui.removeCallbacksAndMessages(null)
        covRunning = false
        unhold()
        session?.stop("sequence_stopped")
        try { wv?.let { container.removeView(it); it.destroy() } } catch (_: Exception) {}
        wv = null
        head.text = "Sequence finished ($reason): $stepsRun step(s)"
        placeholder("Results saved to Download/QoEProbe\nand queued for upload.")
        stopBtn.text = "Close"
        ui.postDelayed({ Uploader.flushAsync(applicationContext) }, 3000)
        setResult(RESULT_OK, Intent().putExtra("summary", "Sequence $seqId finished ($reason): $stepsRun step(s)"))
    }

    @Deprecated("Deprecated in Java")
    override fun onBackPressed() { if (done) finish() else onStopClick() }

    override fun onDestroy() {
        if (!done) { stopping = true; session?.stop("activity_closed"); finishSeq("closed") }
        covRunning = false
        unhold()
        try { wv?.destroy() } catch (_: Exception) {}
        super.onDestroy()
    }
}
