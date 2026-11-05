package com.example.phoneagent

import android.app.Activity
import android.content.Intent
import android.os.Bundle
import android.provider.Settings
import android.text.InputType
import android.widget.*

class MainActivity : Activity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val prefs = getSharedPreferences("p", MODE_PRIVATE)
        val pad = (16 * resources.displayMetrics.density).toInt()

        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(pad, pad * 2, pad, pad)
        }
        val title = TextView(this).apply { text = "Phone Agent"; textSize = 24f }
        val key = EditText(this).apply {
            hint = "Anthropic API key"
            setText(prefs.getString("key", ""))
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_PASSWORD
        }
        val task = EditText(this).apply {
            hint = "What should I do on your phone?"
            minLines = 3
        }
        val enable = Button(this).apply {
            text = "1. Enable accessibility service"
            setOnClickListener { startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS)) }
        }
        val out = TextView(this)
        val run = Button(this).apply {
            text = "2. Run"
            setOnClickListener {
                val svc = AgentService.instance
                if (svc == null) {
                    Toast.makeText(context, "Enable the accessibility service first", Toast.LENGTH_LONG).show()
                    return@setOnClickListener
                }
                val k = key.text.toString().trim()
                val t = task.text.toString().trim()
                if (k.isEmpty() || t.isEmpty()) return@setOnClickListener
                prefs.edit().putString("key", k).apply()
                out.text = ""
                Agent.start(svc, k, t) { msg -> runOnUiThread { out.append(msg + "\n") } }
                moveTaskToBack(true)
            }
        }
        root.addView(title); root.addView(key); root.addView(task)
        root.addView(enable); root.addView(run)
        root.addView(ScrollView(this).apply { addView(out) })
        setContentView(root)
    }
}
