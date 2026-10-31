package com.qoeprobe.app

import android.app.Activity
import android.content.Intent
import android.graphics.Typeface
import android.os.Bundle
import android.view.WindowManager
import android.webkit.WebView
import android.widget.Button
import android.widget.LinearLayout
import android.widget.TextView

/** Single video test screen: one platform, one URL. */
class VideoTestActivity : Activity() {
    private lateinit var wv: WebView
    private var session: VideoSession? = null
    private var held = false
    private fun unhold() { if (held) { held = false; Uploader.release() } }

    override fun onCreate(b: Bundle?) {
        super.onCreate(b)
        Server.init(this)
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        Radio.init(this); Radio.start(); Radio.refresh()
        val platform = intent.getStringExtra("platform") ?: "Web"
        val url = intent.getStringExtra("url") ?: "about:blank"
        val durMs = intent.getIntExtra("dur", 60) * 1000L

        val status = TextView(this).apply {
            textSize = 11f; typeface = Typeface.MONOSPACE; setPadding(16, 8, 16, 8)
            text = "Loading $platform… tap play if it doesn't start.\nTest runs ${durMs / 1000}s after playback starts."
        }
        val stop = Button(this).apply { text = "Stop & save"; isAllCaps = false; setOnClickListener { session?.stop("user_stopped") } }
        wv = WebView(this)
        setContentView(LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            addView(status)
            addView(wv, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, 0, 1f))
            addView(stop)
        })
        Uploader.hold(); held = true
        session = VideoSession(this, wv, platform, url, durMs, 180_000L, emptyList(),
            onStatus = { status.text = it },
            onDone = { r ->
                unhold()
                Uploader.flushAsync(this)
                setResult(RESULT_OK, Intent().putExtra("summary", r.text))
                finish()
            }).also { it.start() }
    }

    @Deprecated("Deprecated in Java")
    override fun onBackPressed() { session?.stop("user_stopped") }

    override fun onDestroy() {
        session?.let { if (!it.finished) it.stop("activity_closed") }
        unhold()
        try { wv.destroy() } catch (_: Exception) {}
        super.onDestroy()
    }
}
