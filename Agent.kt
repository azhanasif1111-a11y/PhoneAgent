package com.example.phoneagent

import org.json.JSONArray
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL

object Agent {
    const val MODEL = "claude-sonnet-5-5"
    const val MAX_STEPS = 25
    @Volatile var stopped = false

    private val SYSTEM = """
You control an Android phone through accessibility. Each turn you get the user's task, the steps done so far, and the current screen as a numbered list of UI elements.
Reply with ONE JSON object only, no prose. Actions:
{"action":"open_app","name":"Chrome"}
{"action":"tap","id":N}
{"action":"type","id":N,"text":"..."}
{"action":"scroll","direction":"down"} (or "up")
{"action":"enter"}
{"action":"back"}  {"action":"home"}  {"action":"wait"}
{"action":"ask_user","message":"..."}
{"action":"done","message":"..."}
Add a short "why" field to every action.
Before anything irreversible (payments, sending messages or emails, deleting, posting, changing settings), use ask_user unless the task explicitly told you to do it. Never enter passwords or payment details. If you are stuck, use ask_user.
""".trimIndent()

    fun start(svc: AgentService, key: String, task: String, log: (String) -> Unit) {
        stopped = false
        svc.showStop()
        Thread {
            val steps = mutableListOf<String>()
            try {
                for (i in 1..MAX_STEPS) {
                    if (stopped) { log("Stopped."); break }
                    Thread.sleep(1200)
                    val (screen, nodes) = svc.readScreen()
                    val a = parse(ask(key, task, steps, screen))
                    log("$i. ${a.optString("action")} - ${a.optString("why")}")
                    when (a.optString("action")) {
                        "done", "ask_user" -> { log(a.optString("message")); break }
                    }
                    val result = svc.act(a, nodes)
                    steps.add("$i. ${a} -> $result")
                    if (i == MAX_STEPS) log("Reached step limit.")
                }
            } catch (e: Exception) {
                log("Error: ${e.message}")
            } finally {
                svc.hideStop()
            }
        }.start()
    }

    private fun parse(text: String): JSONObject {
        val s = text.indexOf('{'); val e = text.lastIndexOf('}')
        return JSONObject(text.substring(s, e + 1))
    }

    private fun ask(key: String, task: String, steps: List<String>, screen: String): String {
        val prompt = "Task: $task\n\nSteps so far:\n" +
            (if (steps.isEmpty()) "(none)" else steps.takeLast(15).joinToString("\n")) +
            "\n\nCurrent screen:\n$screen"
        val body = JSONObject()
            .put("model", MODEL)
            .put("max_tokens", 300)
            .put("system", SYSTEM)
            .put("messages", JSONArray().put(JSONObject().put("role", "user").put("content", prompt)))
        val c = URL("https://api.anthropic.com/v1/messages").openConnection() as HttpURLConnection
        c.requestMethod = "POST"
        c.doOutput = true
        c.connectTimeout = 20000
        c.readTimeout = 60000
        c.setRequestProperty("x-api-key", key)
        c.setRequestProperty("anthropic-version", "2023-06-01")
        c.setRequestProperty("content-type", "application/json")
        c.outputStream.use { it.write(body.toString().toByteArray()) }
        val code = c.responseCode
        val resp = (if (code < 400) c.inputStream else c.errorStream).bufferedReader().readText()
        if (code >= 400) throw Exception("API $code: ${resp.take(200)}")
        return JSONObject(resp).getJSONArray("content").getJSONObject(0).getString("text")
    }
}
