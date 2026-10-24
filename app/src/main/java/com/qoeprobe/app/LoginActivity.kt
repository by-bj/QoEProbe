package com.qoeprobe.app

import android.app.Activity
import android.os.Bundle
import android.webkit.CookieManager
import android.webkit.WebView
import android.widget.Button
import android.widget.LinearLayout
import android.widget.TextView

/** In-app login for Facebook / Instagram. Cookies persist in the shared WebView cookie store used by video tests. */
class LoginActivity : Activity() {
    private lateinit var wv: WebView

    override fun onCreate(b: Bundle?) {
        super.onCreate(b)
        val url = intent.getStringExtra("url") ?: "https://m.facebook.com/login"
        wv = WebView(this)
        Web.setup(wv)
        wv.webViewClient = Web.Client()
        val info = TextView(this).apply {
            text = "Log in with your test account, complete any 2FA/checkpoint, then tap Done."
            setPadding(24, 12, 24, 12)
        }
        val done = Button(this).apply {
            text = "Done"; isAllCaps = false
            setOnClickListener { CookieManager.getInstance().flush(); finish() }
        }
        setContentView(LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            addView(info)
            addView(wv, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, 0, 1f))
            addView(done)
        })
        wv.loadUrl(url)
    }

    @Deprecated("Deprecated in Java")
    override fun onBackPressed() {
        if (wv.canGoBack()) wv.goBack() else { CookieManager.getInstance().flush(); finish() }
    }

    override fun onDestroy() {
        CookieManager.getInstance().flush()
        try { wv.destroy() } catch (_: Exception) {}
        super.onDestroy()
    }
}
