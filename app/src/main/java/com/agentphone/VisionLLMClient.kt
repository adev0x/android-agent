package com.agentphone

import android.graphics.Bitmap
import android.util.Base64
import android.util.Log
import com.google.gson.Gson
import com.google.gson.JsonObject
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import java.io.ByteArrayOutputStream
import java.util.concurrent.TimeUnit

/**
 * VisionLLMClient
 *
 * Sends a screenshot (+ optional accessibility tree) to Claude's vision API
 * and gets back the next action to perform.
 *
 * The LLM acts as the "brain" — it sees the screen and decides:
 *   tap(x, y) | swipe(...) | type(text) | press(BACK|HOME) | wait() | done(result)
 */
class VisionLLMClient(private val apiKey: String) {

    companion object {
        private const val TAG = "VisionLLMClient"
        private const val API_URL = "https://api.anthropic.com/v1/messages"
        private const val MODEL = "claude-opus-4-6"  // best vision understanding
        private const val MAX_TOKENS = 1024

        private val SYSTEM_PROMPT_TEMPLATE = """
You are an AI agent controlling an Android phone on behalf of the user.
You will receive a screenshot of the current screen and information about the task.

Your job is to output the SINGLE next action to take. Be precise and deliberate.

## Coordinate system:
The screenshot is %dx%d pixels. Use coordinates within this range.
Your tap/swipe coordinates will be scaled to the real screen automatically — just use pixel coordinates that match what you see in the image.

## Available actions (output as JSON):

Tap a point on screen:
{"action": "tap", "x": 540, "y": 1200, "reason": "tapping the search bar"}

Swipe gesture:
{"action": "swipe", "x1": 540, "y1": 1500, "x2": 540, "y2": 500, "reason": "scrolling down"}

Type text into focused field:
{"action": "type", "text": "123 Main St, San Francisco", "reason": "entering destination address"}

Press system button:
{"action": "press", "key": "BACK", "reason": "going back to previous screen"}
{"action": "press", "key": "HOME", "reason": "returning to home screen"}

Wait for screen to load:
{"action": "wait", "reason": "waiting for app to load"}

Task is complete:
{"action": "done", "result": "Successfully booked UberX to 123 Main St"}

Task failed:
{"action": "fail", "reason": "Could not find the email with the address"}

## Rules:
- Output ONLY valid JSON. No explanation outside the JSON.
- Coordinates are in pixels matching the screenshot dimensions shown above.
- Always include a "reason" field explaining what you're doing.
- If you see a confirmation dialog or popup, handle it before continuing the main task.
- Before any destructive action (booking, purchasing, sending), output {"action": "confirm", "message": "About to book UberX for $14.50 — proceed?"} to pause for user confirmation.
- If the screen hasn't changed after an action, try a different approach.
        """.trimIndent()
    }

    private val client = OkHttpClient.Builder()
        .connectTimeout(30, TimeUnit.SECONDS)
        .readTimeout(60, TimeUnit.SECONDS)
        .build()

    private val gson = Gson()

    /**
     * Send screenshot to Claude and get back an AgentAction.
     *
     * @param screenshot  The current screen bitmap
     * @param task        The user's high-level task ("book uber from email address")
     * @param stepHistory Short summary of steps taken so far
     * @param screenTree  Optional accessibility tree dump for additional context
     */
    fun getNextAction(
        screenshot: Bitmap,
        task: String,
        stepHistory: List<String> = emptyList(),
        screenTree: String? = null,
        stuckHint: String? = null
    ): AgentAction {
        // Encode image and capture its dimensions BEFORE any downscaling
        val (imageBase64, imgW, imgH) = bitmapToBase64WithDims(screenshot)

        val systemPrompt = SYSTEM_PROMPT_TEMPLATE.format(imgW, imgH)

        val historyText = if (stepHistory.isEmpty()) "None yet."
        else stepHistory.takeLast(5).joinToString("\n") { "• $it" }

        val treeText = if (screenTree.isNullOrBlank()) ""
        else "\n\n## Accessibility tree (UI elements on screen):\n$screenTree"

        val stuckText = if (stuckHint.isNullOrBlank()) "" else "\n\n$stuckHint"

        val userMessage = """
## Task: $task

## Steps taken so far:
$historyText
$treeText$stuckText

## Current screen:
[screenshot attached — ${imgW}x${imgH}px]

What is the next action?
        """.trimIndent()

        val requestBody = buildRequestBody(imageBase64, userMessage, systemPrompt)

        Log.d(TAG, "Sending screenshot to Claude — task: $task")

        return try {
            val request = Request.Builder()
                .url(API_URL)
                .post(requestBody.toRequestBody("application/json".toMediaType()))
                .addHeader("x-api-key", apiKey)
                .addHeader("anthropic-version", "2023-06-01")
                .addHeader("content-type", "application/json")
                .build()

            val response = client.newCall(request).execute()
            val responseBody = response.body?.string() ?: ""

            if (!response.isSuccessful) {
                Log.e(TAG, "API error ${response.code}: $responseBody")
                return AgentAction.Fail("API error: ${response.code}")
            }

            parseResponse(responseBody)
        } catch (e: Exception) {
            Log.e(TAG, "Network error: ${e.message}", e)
            AgentAction.Fail("Network error: ${e.message}")
        }
    }

    // ── Private ───────────────────────────────────────────────────────

    private fun buildRequestBody(imageBase64: String, userMessage: String, systemPrompt: String): String {
        val body = JsonObject().apply {
            addProperty("model", MODEL)
            addProperty("max_tokens", MAX_TOKENS)
            addProperty("system", systemPrompt)
            add("messages", gson.toJsonTree(listOf(
                mapOf(
                    "role" to "user",
                    "content" to listOf(
                        mapOf(
                            "type" to "image",
                            "source" to mapOf(
                                "type" to "base64",
                                "media_type" to "image/jpeg",
                                "data" to imageBase64
                            )
                        ),
                        mapOf(
                            "type" to "text",
                            "text" to userMessage
                        )
                    )
                )
            )))
        }
        return gson.toJson(body)
    }

    private fun parseResponse(responseBody: String): AgentAction {
        return try {
            val json = gson.fromJson(responseBody, JsonObject::class.java)
            val content = json.getAsJsonArray("content")
                .get(0).asJsonObject
                .get("text").asString
                .trim()

            Log.d(TAG, "Claude response: $content")

            // Extract JSON from response (Claude sometimes adds surrounding text)
            val jsonStr = extractJson(content)
            val actionJson = gson.fromJson(jsonStr, JsonObject::class.java)

            when (val action = actionJson.get("action").asString) {
                "tap" -> AgentAction.Tap(
                    x = actionJson.get("x").asFloat,
                    y = actionJson.get("y").asFloat,
                    reason = actionJson.get("reason")?.asString ?: ""
                )
                "swipe" -> AgentAction.Swipe(
                    x1 = actionJson.get("x1").asFloat,
                    y1 = actionJson.get("y1").asFloat,
                    x2 = actionJson.get("x2").asFloat,
                    y2 = actionJson.get("y2").asFloat,
                    reason = actionJson.get("reason")?.asString ?: ""
                )
                "type" -> AgentAction.Type(
                    text = actionJson.get("text").asString,
                    reason = actionJson.get("reason")?.asString ?: ""
                )
                "press" -> AgentAction.Press(
                    key = actionJson.get("key").asString,
                    reason = actionJson.get("reason")?.asString ?: ""
                )
                "wait" -> AgentAction.Wait(
                    reason = actionJson.get("reason")?.asString ?: ""
                )
                "confirm" -> AgentAction.Confirm(
                    message = actionJson.get("message").asString
                )
                "done" -> AgentAction.Done(
                    result = actionJson.get("result")?.asString ?: "Task complete"
                )
                "fail" -> AgentAction.Fail(
                    reason = actionJson.get("reason")?.asString ?: "Unknown failure"
                )
                else -> AgentAction.Fail("Unknown action: $action")
            }
        } catch (e: Exception) {
            Log.e(TAG, "Failed to parse LLM response: ${e.message}")
            AgentAction.Fail("Failed to parse response: ${e.message}")
        }
    }

    private fun extractJson(text: String): String {
        // Find first { and last } to extract JSON even if surrounded by text
        val start = text.indexOf('{')
        val end = text.lastIndexOf('}')
        return if (start >= 0 && end > start) text.substring(start, end + 1)
        else text
    }

    /**
     * Encode bitmap to base64 JPEG.
     * Returns Triple(base64String, widthPx, heightPx) where dimensions
     * reflect what was actually sent to the LLM (after any downscaling).
     * These are the coordinate bounds Claude should work within.
     */
    private fun bitmapToBase64WithDims(bitmap: Bitmap): Triple<String, Int, Int> {
        // Cap at 1024px wide — large enough for Claude to read UI, small enough to be fast
        val maxW = 1024
        val (finalBitmap, w, h) = if (bitmap.width > maxW) {
            val ratio = maxW.toFloat() / bitmap.width
            val scaled = Bitmap.createScaledBitmap(
                bitmap, maxW, (bitmap.height * ratio).toInt(), true
            )
            Triple(scaled, scaled.width, scaled.height)
        } else {
            Triple(bitmap, bitmap.width, bitmap.height)
        }

        val stream = ByteArrayOutputStream()
        finalBitmap.compress(Bitmap.CompressFormat.JPEG, 85, stream)
        val bytes = stream.toByteArray()

        if (finalBitmap != bitmap) finalBitmap.recycle()

        Log.d(TAG, "Image sent to LLM: ${w}x${h}, ${bytes.size / 1024}KB")
        return Triple(Base64.encodeToString(bytes, Base64.NO_WRAP), w, h)
    }
}

// ── Action sealed class ───────────────────────────────────────────────

/**
 * All possible actions the LLM can decide to take.
 * AgentLoop pattern-matches on these to execute the right thing.
 */
sealed class AgentAction {
    data class Tap(val x: Float, val y: Float, val reason: String) : AgentAction()
    data class Swipe(val x1: Float, val y1: Float, val x2: Float, val y2: Float, val reason: String) : AgentAction()
    data class Type(val text: String, val reason: String) : AgentAction()
    data class Press(val key: String, val reason: String) : AgentAction()
    data class Wait(val reason: String) : AgentAction()
    data class Confirm(val message: String) : AgentAction()
    data class Done(val result: String) : AgentAction()
    data class Fail(val reason: String) : AgentAction()
}
