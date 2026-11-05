package com.example.phoneagent

import android.accessibilityservice.AccessibilityService
import android.accessibilityservice.GestureDescription
import android.content.Intent
import android.graphics.Path
import android.graphics.PixelFormat
import android.graphics.Rect
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.Gravity
import android.view.WindowManager
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo
import android.widget.Button
import org.json.JSONObject

class AgentService : AccessibilityService() {
    companion object { @Volatile var instance: AgentService? = null }

    private var stopBtn: Button? = null
    private val main = Handler(Looper.getMainLooper())

    override fun onServiceConnected() { instance = this }
    override fun onAccessibilityEvent(event: AccessibilityEvent?) {}
    override fun onInterrupt() {}
    override fun onDestroy() { instance = null; super.onDestroy() }

    fun showStop() = main.post {
        if (stopBtn != null) return@post
        val wm = getSystemService(WINDOW_SERVICE) as WindowManager
        val b = Button(this).apply {
            text = "STOP AI"
            setOnClickListener { Agent.stopped = true }
        }
        val lp = WindowManager.LayoutParams(
            WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE,
            PixelFormat.TRANSLUCENT
        ).apply { gravity = Gravity.TOP or Gravity.END }
        wm.addView(b, lp)
        stopBtn = b
    }

    fun hideStop() = main.post {
        stopBtn?.let { (getSystemService(WINDOW_SERVICE) as WindowManager).removeView(it) }
        stopBtn = null
    }

    fun readScreen(): Pair<String, List<AccessibilityNodeInfo>> {
        val root = rootInActiveWindow ?: return Pair("(screen not readable)", emptyList())
        val nodes = mutableListOf<AccessibilityNodeInfo>()
        val sb = StringBuilder()
        fun walk(n: AccessibilityNodeInfo) {
            val t = n.text?.toString()?.take(80)
            val d = n.contentDescription?.toString()?.take(80)
            val interesting = n.isClickable || n.isEditable || n.isScrollable ||
                !t.isNullOrBlank() || !d.isNullOrBlank()
            if (interesting && n.isVisibleToUser) {
                sb.append("[${nodes.size}] ${n.className?.toString()?.substringAfterLast('.')}")
                if (!t.isNullOrBlank()) sb.append(" \"$t\"")
                if (!d.isNullOrBlank()) sb.append(" desc=\"$d\"")
                if (n.isClickable) sb.append(" click")
                if (n.isEditable) sb.append(" edit")
                if (n.isScrollable) sb.append(" scroll")
                sb.append('\n')
                nodes.add(n)
            }
            for (i in 0 until n.childCount) n.getChild(i)?.let { walk(it) }
        }
        walk(root)
        return Pair(sb.toString().take(6000), nodes)
    }

    fun act(a: JSONObject, nodes: List<AccessibilityNodeInfo>): String {
        when (a.optString("action")) {
            "tap" -> {
                val n = nodes.getOrNull(a.optInt("id", -1)) ?: return "bad id"
                return if (click(n)) "ok" else "tap failed"
            }
            "type" -> {
                val n = nodes.getOrNull(a.optInt("id", -1)) ?: return "bad id"
                val b = Bundle()
                b.putCharSequence(
                    AccessibilityNodeInfo.ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE,
                    a.optString("text")
                )
                n.performAction(AccessibilityNodeInfo.ACTION_FOCUS)
                return if (n.performAction(AccessibilityNodeInfo.ACTION_SET_TEXT, b)) "ok" else "type failed"
            }
            "scroll" -> {
                val n = nodes.firstOrNull { it.isScrollable } ?: return "nothing scrollable"
                val fwd = a.optString("direction") != "up"
                n.performAction(
                    if (fwd) AccessibilityNodeInfo.ACTION_SCROLL_FORWARD
                    else AccessibilityNodeInfo.ACTION_SCROLL_BACKWARD
                )
                return "ok"
            }
            "enter" -> {
                if (Build.VERSION.SDK_INT >= 30) {
                    val f = rootInActiveWindow?.findFocus(AccessibilityNodeInfo.FOCUS_INPUT)
                    f?.performAction(AccessibilityNodeInfo.AccessibilityAction.ACTION_IME_ENTER.id)
                    return "ok"
                }
                return "enter unsupported on this Android version; tap the search/submit button"
            }
            "back" -> { performGlobalAction(GLOBAL_ACTION_BACK); return "ok" }
            "home" -> { performGlobalAction(GLOBAL_ACTION_HOME); return "ok" }
            "wait" -> { Thread.sleep(2000); return "ok" }
            "open_app" -> {
                val q = a.optString("name").lowercase()
                val pm = packageManager
                val i = Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER)
                val apps = pm.queryIntentActivities(i, 0)
                val hit = apps.firstOrNull { it.loadLabel(pm).toString().lowercase() == q }
                    ?: apps.firstOrNull { it.loadLabel(pm).toString().lowercase().contains(q) }
                    ?: return "app not found"
                val launch = pm.getLaunchIntentForPackage(hit.activityInfo.packageName)
                    ?: return "cannot launch"
                launch.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                startActivity(launch)
                return "ok"
            }
        }
        return "unknown action"
    }

    private fun click(n: AccessibilityNodeInfo): Boolean {
        var c: AccessibilityNodeInfo? = n
        while (c != null && !c.isClickable) c = c.parent
        if (c != null && c.performAction(AccessibilityNodeInfo.ACTION_CLICK)) return true
        val r = Rect()
        n.getBoundsInScreen(r)
        val p = Path().apply { moveTo(r.exactCenterX(), r.exactCenterY()) }
        val g = GestureDescription.Builder()
            .addStroke(GestureDescription.StrokeDescription(p, 0, 60)).build()
        return dispatchGesture(g, null, null)
    }
}
