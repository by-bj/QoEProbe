package com.qoeprobe.app

import android.content.Context
import android.os.SystemClock
import java.io.File
import java.net.HttpURLConnection
import java.net.URL
import java.util.TreeMap
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicLong
import kotlin.concurrent.thread
import kotlin.math.abs
import kotlin.random.Random

data class NetResult(val testId: String, val type: String, val kpi: Map<String, String>, val radio: Map<String, String>, val text: String)

object NetTests {
    val NET_HDR = listOf(
        "test_id", "start_time", "type", "target", "streams", "duration_s",
        "avg_mbps", "peak_mbps", "p10_mbps", "p50_mbps", "p90_mbps", "total_MB",
        "sent", "received", "loss_pct", "rtt_min_ms", "rtt_avg_ms", "rtt_max_ms", "rtt_p95_ms", "jitter_ms",
        "errors", "rat", "pci", "arfcn", "band", "rsrp", "rsrq", "sinr", "nr_ss_rsrp", "nr_ss_sinr", "lat", "lon",
        "seq_id", "loop", "step"
    )
    private val RADIO_SUM = listOf("rat", "pci", "arfcn", "band", "rsrp", "rsrq", "sinr", "nr_ss_rsrp", "nr_ss_sinr", "lat", "lon")

    private fun tag3(t: List<String>) = (t + listOf("", "", "")).take(3)

    // ---------------- HTTP throughput (multi-stream) ----------------
    fun throughput(c: Context, down: Boolean, url: String, streams: Int, durSec: Int, say: (String) -> Unit,
                   tags: List<String> = emptyList()): NetResult {
        val id = Files.stamp(); val start = Files.now(); val dir = if (down) "DL" else "UL"
        val f = Files.newFile(c, "throughput_$dir", id)
        val csv = Csv(f, listOf("time", "test_id", "direction", "second", "mbps", "total_bytes") + Radio.KEYS)
        Radio.refresh()
        val r0 = Radio.snapshot()
        val bytes = AtomicLong(); val stop = AtomicBoolean(false); val errors = AtomicInteger()
        say("$dir test: $streams stream(s), $durSec s")
        val workers = (1..streams).map {
            thread(isDaemon = true) {
                while (!stop.get()) {
                    try { if (down) dlOnce(url, bytes, stop) else ulOnce(url, bytes, stop) }
                    catch (e: Exception) {
                        if (!stop.get()) { if (errors.incrementAndGet() <= 3) say("  stream error: ${e.javaClass.simpleName} ${e.message ?: ""}"); Thread.sleep(300) }
                    }
                }
            }
        }
        val per = ArrayList<Double>(); var last = 0L; val t0 = SystemClock.elapsedRealtime()
        for (sec in 1..durSec) {
            val wait = t0 + sec * 1000L - SystemClock.elapsedRealtime()
            if (wait > 0) Thread.sleep(wait)
            val b = bytes.get(); val mbps = (b - last) * 8 / 1e6; last = b
            per.add(mbps)
            Radio.refresh(); val s = Radio.snapshot()
            csv.row(listOf(Files.now(), id, dir, sec, d2(mbps), b) + Radio.KEYS.map { s[it] })
            say("  $dir ${sec}s  ${d1(mbps)} Mbps")
        }
        stop.set(true); workers.forEach { it.join(2000) }
        csv.close()
        val total = bytes.get()
        val avg = total * 8 / 1e6 / durSec
        // p10/p50/p90 exclude the first second (TCP slow start)
        val steady = if (per.size > 2) per.drop(1) else per
        Results.record(c, "network_summary.csv", "NetworkResults", NET_HDR, listOf(
            id, start, "HTTP_$dir", url, streams, durSec, d2(avg), d2(per.maxOrNull() ?: 0.0),
            d2(pct(steady, 0.1)), d2(pct(steady, 0.5)), d2(pct(steady, 0.9)), d2(total / 1e6),
            "", "", "", "", "", "", "", "", errors.get()
        ) + RADIO_SUM.map { r0[it] } + tag3(tags), listOf(f to "throughput"))
        val text = "$dir done: avg ${d1(avg)} Mbps, peak ${d1(per.maxOrNull() ?: 0.0)}, p50 ${d1(pct(steady, 0.5))} | ${f.name}"
        say(text)
        return NetResult(id, "HTTP_$dir", mapOf("avg_mbps" to d2(avg), "p50_mbps" to d2(pct(steady, 0.5))), r0, text)
    }

    private fun open(u: String): HttpURLConnection = (URL(u).openConnection() as HttpURLConnection).apply {
        connectTimeout = 5000; readTimeout = 5000; useCaches = false
        setRequestProperty("Accept-Encoding", "identity"); setRequestProperty("Cache-Control", "no-cache")
    }

    private fun dlOnce(u: String, bytes: AtomicLong, stop: AtomicBoolean) {
        val sep = if (u.contains('?')) '&' else '?'
        val c = open("$u${sep}r=${Random.nextInt(1_000_000_000)}")   // cache-buster
        try {
            c.inputStream.use { ins ->
                val buf = ByteArray(65536)
                while (!stop.get()) { val n = ins.read(buf); if (n < 0) break; bytes.addAndGet(n.toLong()) }
            }
        } finally { c.disconnect() }
    }

    private fun ulOnce(u: String, bytes: AtomicLong, stop: AtomicBoolean) {
        val c = open(u)
        c.doOutput = true; c.requestMethod = "POST"; c.setChunkedStreamingMode(65536)
        c.setRequestProperty("Content-Type", "application/octet-stream")
        val buf = ByteArray(65536).also { Random.nextBytes(it) }
        var sent = 0L
        try {
            c.outputStream.use { out ->
                while (!stop.get() && sent < 100_000_000L) { out.write(buf); bytes.addAndGet(buf.size.toLong()); sent += buf.size }
            }
            try { c.responseCode } catch (_: Exception) {}
        } finally { c.disconnect() }
    }

    // ---------------- ICMP latency / packet loss ----------------
    fun ping(c: Context, host: String, count: Int, intervalS: String, say: (String) -> Unit,
             tags: List<String> = emptyList()): NetResult {
        val id = Files.stamp(); val start = Files.now()
        Radio.refresh(); val r0 = Radio.snapshot()
        say("Ping $host x$count (interval ${intervalS}s)…")
        val replies = TreeMap<Int, Triple<String, Double, Map<String, String>>>()
        var sent = count; var recv = -1
        val re = Regex("icmp_seq=(\\d+).*time=([\\d.]+)")
        val sumRe = Regex("(\\d+) packets transmitted, (\\d+) received")
        try {
            val p = ProcessBuilder("/system/bin/ping", "-c", count.toString(), "-i", intervalS, "-W", "2", host)
                .redirectErrorStream(true).start()
            p.inputStream.bufferedReader().forEachLine { line ->
                re.find(line)?.let { m ->
                    val seq = m.groupValues[1].toInt(); val rtt = m.groupValues[2].toDouble()
                    if (!replies.containsKey(seq)) {
                        if (seq % 5 == 0) Radio.refresh()
                        replies[seq] = Triple(Files.now(), rtt, Radio.snapshot())
                        if (seq % 10 == 0) say("  seq $seq  $rtt ms")
                    }
                }
                sumRe.find(line)?.let { sent = it.groupValues[1].toInt(); recv = it.groupValues[2].toInt() }
                if (line.contains("unknown host", true) || line.contains("not permitted", true) ||
                    line.contains("invalid", true)) say("  $line")
            }
            p.waitFor()
        } catch (e: Exception) {
            say("Ping failed: ${e.message}")
            return NetResult(id, "ICMP", emptyMap(), r0, "Ping failed: ${e.message}")
        }
        if (recv < 0) recv = replies.size
        val f = Files.newFile(c, "ping", id)
        val csv = Csv(f, listOf("time", "test_id", "host", "seq", "status", "rtt_ms", "rat", "pci", "rsrp", "sinr", "nr_ss_rsrp", "lat", "lon"))
        for (seq in 1..sent) {
            val r = replies[seq]
            val s = r?.third ?: emptyMap()
            csv.row(listOf(r?.first ?: "", id, host, seq, if (r != null) "ok" else "lost", r?.second ?: "",
                s["rat"], s["pci"], s["rsrp"], s["sinr"], s["nr_ss_rsrp"], s["lat"], s["lon"]))
        }
        csv.close()
        val rtts = replies.values.map { it.second }
        val jitter = if (rtts.size > 1) rtts.zipWithNext { a, b -> abs(b - a) }.average() else 0.0
        val loss = if (sent > 0) (sent - recv) * 100.0 / sent else 0.0
        val avgRtt = if (rtts.isEmpty()) 0.0 else rtts.average()
        Results.record(c, "network_summary.csv", "NetworkResults", NET_HDR, listOf(
            id, start, "ICMP", host, 1, "", "", "", "", "", "", "",
            sent, recv, d2(loss), d1(rtts.minOrNull() ?: 0.0), d1(avgRtt),
            d1(rtts.maxOrNull() ?: 0.0), d1(pct(rtts, 0.95)), d2(jitter), ""
        ) + RADIO_SUM.map { r0[it] } + tag3(tags), listOf(f to "ping"))
        val text = "Ping done: loss ${d2(loss)}% ($recv/$sent), avg ${d1(avgRtt)} ms, jitter ${d1(jitter)} ms | ${f.name}"
        say(text)
        return NetResult(id, "ICMP", mapOf("loss_pct" to d2(loss), "rtt_avg_ms" to d1(avgRtt)), r0, text)
    }
}
