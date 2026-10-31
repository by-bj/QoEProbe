package com.qoeprobe.app

import android.app.Activity
import android.graphics.Typeface
import android.os.Bundle
import android.text.InputType
import android.widget.ArrayAdapter
import android.widget.Button
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.Spinner
import android.widget.TextView
import kotlin.concurrent.thread

/** First-launch registration: name, email/phone, market. The admin approves the row in Google Sheets. */
class RegisterActivity : Activity() {
    private var markets: List<String> = emptyList()

    override fun onCreate(b: Bundle?) {
        super.onCreate(b)
        Server.init(this)
        val pad = (16 * resources.displayMetrics.density).toInt()
        val root = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(pad, pad, pad, pad) }
        setContentView(ScrollView(this).apply { addView(root) })

        root.addView(TextView(this).apply { text = "Register this phone"; textSize = 20f; setTypeface(null, Typeface.BOLD) })
        root.addView(TextView(this).apply {
            text = "Device ID: ${Access.deviceId}\nYour admin approves access from the QoE Probe sheet."
            setPadding(0, pad / 2, 0, pad)
        })
        val name = EditText(this).apply { hint = "Full name"; setText(Access.name()); isSingleLine = true
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_CAP_WORDS }
        val contact = EditText(this).apply { hint = "Email or phone"; setText(Access.contact()); isSingleLine = true
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_EMAIL_ADDRESS }
        val marketLabel = TextView(this).apply { text = "Market"; setPadding(0, pad / 2, 0, 0) }
        val spinner = Spinner(this)
        val marketText = EditText(this).apply { hint = "Market (country)"; setText(Access.market()); isSingleLine = true }
        root.addView(name); root.addView(contact); root.addView(marketLabel); root.addView(spinner); root.addView(marketText)
        root.addView(TextView(this).apply {
            text = "Your name, contact and market are used only to manage access to this test tool and to label your results."
            textSize = 12f; setPadding(0, pad, 0, pad)
        })
        val status = TextView(this)
        val submit = Button(this).apply { text = "Submit"; isAllCaps = false }
        root.addView(submit); root.addView(status)

        thread(isDaemon = true) {
            val m = Access.markets()
            runOnUiThread {
                markets = m
                if (m.isNotEmpty()) {
                    spinner.adapter = ArrayAdapter(this, android.R.layout.simple_spinner_dropdown_item, m)
                    m.indexOf(Access.market()).takeIf { it >= 0 }?.let { spinner.setSelection(it) }
                    marketText.visibility = android.view.View.GONE
                } else spinner.visibility = android.view.View.GONE
            }
        }

        submit.setOnClickListener {
            val n = name.text.toString().trim(); val c = contact.text.toString().trim()
            val mk = if (markets.isNotEmpty()) spinner.selectedItem?.toString() ?: "" else marketText.text.toString().trim()
            if (n.length < 2 || c.length < 5 || mk.isEmpty()) { status.text = "Please fill in all three fields."; return@setOnClickListener }
            if (!Server.configured) { status.text = "No admin server is configured in this build."; return@setOnClickListener }
            submit.isEnabled = false; status.text = "Submitting…"
            thread(isDaemon = true) {
                val st = Access.register(n, c, mk)
                runOnUiThread {
                    submit.isEnabled = true
                    status.text = when (st) {
                        "active" -> "Registered and active. You can start testing."
                        "pending" -> "Registered. Waiting for admin approval; you can close this screen."
                        else -> "Result: $st"
                    }
                    if (st == "active" || st == "pending") submit.text = "Done"
                    if (st == "active" || st == "pending") submit.setOnClickListener { finish() }
                }
            }
        }
    }
}
