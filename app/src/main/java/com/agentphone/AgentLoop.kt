package com.agentphone

import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext

/**
 * AgentLoop
 *
 * Core perceive → think → act loop.
 *
 * Coordinate handling:
 *   - ScreenCaptureService captures at 50% scale (captureW x captureH)
 *   - VisionLLMClient sends that image to Claude
 *   - Claude returns tap/swipe coordinates in capture space (e.g. 360,640)
 *   - AgentLoop scales those coordinates back to real screen space before
 *     passing to AccessibilityActionService (which taps on the real screen)
 *   - Scale factors: scaleX = screenW/captureW, scaleY = screenH/captureH
 */
class AgentLoop(
    private val llmClient: VisionLLMClient,
    private val onLog: (String) -> Unit,
    private val onConfirmNeeded: suspend (String) -> Boolean,
    private val onComplete: (String) -> Unit,
    private val onFailed: (String) -> Unit,
) {
    companion object {
        private const val TAG = "AgentLoop"
        private const val MAX_STEPS = 30
        private const val ACTION_SETTLE_MS = 700L   // wait after action for screen to settle
        private const val WAIT_SETTLE_MS = 2000L    // longer wait when LLM says "wait"
    }

    private var isRunning = false
    private val stepHistory = mutableListOf<String>()

    val running get() = isRunning

    suspend fun run(task: String) = withContext(Dispatchers.IO) {
        if (isRunning) { log("Already running"); return@withContext }
        isRunning = true
        stepHistory.clear()

        log("Task: \"$task\"")
        log("─".repeat(40))

        try {
            repeat(MAX_STEPS) { step ->
                if (!isRunning) return@withContext

                log("Step ${step + 1}/$MAX_STEPS")

                // ── PERCEIVE ──────────────────────────────────────────
                val captureSvc = ScreenCaptureService.instance ?: run {
                    fail("Screen capture service not running"); return@withContext
                }
                val screenshot = captureSvc.captureScreen() ?: run {
                    fail("Screenshot failed"); return@withContext
                }

                // Get scale factors BEFORE recycling screenshot
                val scaleX = captureSvc.scaleX
                val scaleY = captureSvc.scaleY

                val screenTree = AccessibilityActionService.instance?.getScreenTree()

                // ── THINK ─────────────────────────────────────────────
                log("Thinking...")
                val action = llmClient.getNextAction(
                    screenshot = screenshot,
                    task = task,
                    stepHistory = stepHistory.toList(),
                    screenTree = screenTree
                )
                screenshot.recycle()

                log("→ ${describe(action)}")

                // ── ACT ───────────────────────────────────────────────
                val a11y = AccessibilityActionService.instance

                when (action) {
                    is AgentAction.Tap -> {
                        if (a11y == null) { fail("Accessibility service disconnected"); return@withContext }
                        // Scale from capture space to real screen space
                        val sx = action.x * scaleX
                        val sy = action.y * scaleY
                        record("Tap (${action.x.toInt()},${action.y.toInt()}) → screen (${sx.toInt()},${sy.toInt()}): ${action.reason}")
                        val ok = withContext(Dispatchers.Main) { a11y.tap(sx, sy) }
                        if (!ok) log("⚠️ Tap gesture cancelled — retrying next step")
                        delay(ACTION_SETTLE_MS)
                    }

                    is AgentAction.Swipe -> {
                        if (a11y == null) { fail("Accessibility service disconnected"); return@withContext }
                        val sx1 = action.x1 * scaleX; val sy1 = action.y1 * scaleY
                        val sx2 = action.x2 * scaleX; val sy2 = action.y2 * scaleY
                        record("Swipe: ${action.reason}")
                        withContext(Dispatchers.Main) { a11y.swipe(sx1, sy1, sx2, sy2) }
                        delay(ACTION_SETTLE_MS)
                    }

                    is AgentAction.Type -> {
                        if (a11y == null) { fail("Accessibility service disconnected"); return@withContext }
                        record("Type \"${action.text}\": ${action.reason}")
                        withContext(Dispatchers.Main) { a11y.typeText(action.text) }
                        delay(ACTION_SETTLE_MS)
                    }

                    is AgentAction.Press -> {
                        if (a11y == null) { fail("Accessibility service disconnected"); return@withContext }
                        record("Press ${action.key}: ${action.reason}")
                        withContext(Dispatchers.Main) {
                            when (action.key.uppercase()) {
                                "BACK" -> a11y.pressBack()
                                "HOME" -> a11y.pressHome()
                                "RECENTS" -> a11y.pressRecents()
                                else -> log("Unknown key: ${action.key}")
                            }
                        }
                        delay(ACTION_SETTLE_MS)
                    }

                    is AgentAction.Wait -> {
                        record("Wait: ${action.reason}")
                        log("Waiting for screen to load...")
                        delay(WAIT_SETTLE_MS)
                    }

                    is AgentAction.Confirm -> {
                        log("⚠️ Confirm: ${action.message}")
                        val approved = withContext(Dispatchers.Main) {
                            onConfirmNeeded(action.message)
                        }
                        if (!approved) { fail("Cancelled by user at confirmation step"); return@withContext }
                        record("User approved: ${action.message}")
                        log("Approved — continuing")
                    }

                    is AgentAction.Done -> {
                        log("✅ ${action.result}")
                        complete(action.result)
                        return@withContext
                    }

                    is AgentAction.Fail -> {
                        fail(action.reason)
                        return@withContext
                    }
                }
            }

            fail("Reached $MAX_STEPS step limit without completing task")

        } finally {
            isRunning = false
        }
    }

    fun stop() {
        if (isRunning) { log("Stopping..."); isRunning = false }
    }

    // ── Private ───────────────────────────────────────────────────────

    private fun record(step: String) { stepHistory.add(step) }

    private fun log(msg: String) {
        Log.d(TAG, msg)
        onLog(msg)
    }

    private fun complete(result: String) {
        isRunning = false
        onComplete(result)
    }

    private fun fail(reason: String) {
        log("❌ $reason")
        isRunning = false
        onFailed(reason)
    }

    private fun describe(action: AgentAction) = when (action) {
        is AgentAction.Tap     -> "tap(${action.x.toInt()},${action.y.toInt()}) — ${action.reason}"
        is AgentAction.Swipe   -> "swipe — ${action.reason}"
        is AgentAction.Type    -> "type \"${action.text.take(40)}\" — ${action.reason}"
        is AgentAction.Press   -> "press ${action.key} — ${action.reason}"
        is AgentAction.Wait    -> "wait — ${action.reason}"
        is AgentAction.Confirm -> "confirm? ${action.message}"
        is AgentAction.Done    -> "done ✅ ${action.result}"
        is AgentAction.Fail    -> "fail ❌ ${action.reason}"
    }
}
