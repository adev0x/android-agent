package com.agentphone

import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext

/**
 * AgentLoop — Phase 2
 *
 * Perceive → think → act loop with:
 *   1. Change detection  — after each action, compare before/after screenshots.
 *                          If nothing changed, retry up to MAX_ACTION_RETRIES times.
 *   2. Retry logic       — on NONE change: wait longer and retry the same step.
 *                          On MINOR change (loading): wait for settle, then continue.
 *   3. Stuck detection   — track perceptual hashes of recent screens. If the last
 *                          STUCK_WINDOW screens are all the same hash, inject a
 *                          "stuck" hint into the next LLM call so it tries a
 *                          different approach instead of repeating the same action.
 *
 * Coordinate handling (unchanged from Phase 1):
 *   LLM coordinates are in capture space → scaled to real screen space via
 *   scaleX / scaleY from ScreenCaptureService before gesture dispatch.
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
        private const val MAX_ACTION_RETRIES = 2       // retries per step when screen doesn't change
        private const val STUCK_WINDOW = 4             // consecutive same-screen steps to declare stuck
        private const val ACTION_SETTLE_MS = 700L      // wait after action for screen to settle
        private const val RETRY_SETTLE_MS = 1200L      // longer wait on retry (give app more time)
        private const val LOADING_SETTLE_MS = 1800L    // wait when MINOR change detected (still loading)
        private const val WAIT_SETTLE_MS = 2000L       // wait when LLM explicitly says "wait"
    }

    private var isRunning = false
    private val stepHistory = mutableListOf<String>()

    // Phase 2: stuck detection state
    private val recentScreenHashes = ArrayDeque<Long>(STUCK_WINDOW + 1)
    private var consecutiveStuckCount = 0

    val running get() = isRunning

    suspend fun run(task: String) = withContext(Dispatchers.IO) {
        if (isRunning) { log("Already running"); return@withContext }
        isRunning = true
        stepHistory.clear()
        recentScreenHashes.clear()
        consecutiveStuckCount = 0

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

                val beforeShot = captureSvc.captureScreen() ?: run {
                    fail("Screenshot failed"); return@withContext
                }

                val scaleX = captureSvc.scaleX
                val scaleY = captureSvc.scaleY

                // Track perceptual hash for stuck detection
                val beforeHash = ScreenChangeDetector.perceptualHash(beforeShot)
                updateStuckState(beforeHash)

                val screenTree = AccessibilityActionService.instance?.getScreenTree()

                // Build stuck hint if we've been on the same screen too long
                val stuckHint = buildStuckHint()
                if (stuckHint != null) log("⚠️ Stuck detected — injecting hint to LLM")

                // ── THINK ─────────────────────────────────────────────
                log("Thinking...")
                val action = llmClient.getNextAction(
                    screenshot = beforeShot,
                    task = task,
                    stepHistory = stepHistory.toList(),
                    screenTree = screenTree,
                    stuckHint = stuckHint
                )

                log("→ ${describe(action)}")

                // ── ACT + VERIFY ──────────────────────────────────────
                val a11y = AccessibilityActionService.instance

                when (action) {
                    is AgentAction.Tap -> {
                        if (a11y == null) { fail("Accessibility service disconnected"); return@withContext }
                        beforeShot.recycle()
                        val sx = action.x * scaleX
                        val sy = action.y * scaleY
                        record("Tap (${action.x.toInt()},${action.y.toInt()}) → screen (${sx.toInt()},${sy.toInt()}): ${action.reason}")
                        executeWithChangeDetection(captureSvc, a11y, action, sx, sy)
                    }

                    is AgentAction.Swipe -> {
                        if (a11y == null) { fail("Accessibility service disconnected"); return@withContext }
                        beforeShot.recycle()
                        val sx1 = action.x1 * scaleX; val sy1 = action.y1 * scaleY
                        val sx2 = action.x2 * scaleX; val sy2 = action.y2 * scaleY
                        record("Swipe: ${action.reason}")
                        withContext(Dispatchers.Main) { a11y.swipe(sx1, sy1, sx2, sy2) }
                        delay(ACTION_SETTLE_MS)
                        // Swipe almost always causes scroll — reset stuck state
                        consecutiveStuckCount = 0
                    }

                    is AgentAction.Type -> {
                        if (a11y == null) { fail("Accessibility service disconnected"); return@withContext }
                        beforeShot.recycle()
                        record("Type \"${action.text}\": ${action.reason}")
                        withContext(Dispatchers.Main) { a11y.typeText(action.text) }
                        delay(ACTION_SETTLE_MS)
                    }

                    is AgentAction.Press -> {
                        if (a11y == null) { fail("Accessibility service disconnected"); return@withContext }
                        beforeShot.recycle()
                        record("Press ${action.key}: ${action.reason}")
                        withContext(Dispatchers.Main) {
                            when (action.key.uppercase()) {
                                "BACK"    -> a11y.pressBack()
                                "HOME"    -> a11y.pressHome()
                                "RECENTS" -> a11y.pressRecents()
                                else      -> log("Unknown key: ${action.key}")
                            }
                        }
                        delay(ACTION_SETTLE_MS)
                        consecutiveStuckCount = 0
                    }

                    is AgentAction.Wait -> {
                        beforeShot.recycle()
                        record("Wait: ${action.reason}")
                        log("Waiting for screen to load...")
                        delay(WAIT_SETTLE_MS)
                    }

                    is AgentAction.Confirm -> {
                        beforeShot.recycle()
                        log("⚠️ Confirm: ${action.message}")
                        val approved = withContext(Dispatchers.Main) {
                            onConfirmNeeded(action.message)
                        }
                        if (!approved) {
                            fail("Cancelled by user at confirmation step")
                            return@withContext
                        }
                        record("User approved: ${action.message}")
                        log("Approved — continuing")
                        consecutiveStuckCount = 0
                        recentScreenHashes.clear()
                    }

                    is AgentAction.Done -> {
                        beforeShot.recycle()
                        log("✅ ${action.result}")
                        complete(action.result)
                        return@withContext
                    }

                    is AgentAction.Fail -> {
                        beforeShot.recycle()
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

    // ── Phase 2: change detection + retry ────────────────────────────

    /**
     * Execute a tap and verify the screen changed afterwards.
     * Retries up to MAX_ACTION_RETRIES times on no-change.
     * On MINOR change (loading animation): waits longer then accepts.
     */
    private suspend fun executeWithChangeDetection(
        captureSvc: ScreenCaptureService,
        a11y: AccessibilityActionService,
        action: AgentAction.Tap,
        scaledX: Float,
        scaledY: Float
    ) {
        var attempts = 0

        while (attempts <= MAX_ACTION_RETRIES && isRunning) {
            if (attempts > 0) {
                log("↩ Retry ${attempts}/$MAX_ACTION_RETRIES — screen unchanged, trying again")
            }

            val before = captureSvc.captureScreen()

            val gestureOk = withContext(Dispatchers.Main) { a11y.tap(scaledX, scaledY) }
            if (!gestureOk) log("⚠️ Gesture cancelled by system")

            val settleTime = if (attempts == 0) ACTION_SETTLE_MS else RETRY_SETTLE_MS
            delay(settleTime)

            val after = captureSvc.captureScreen()
            val changeLevel = ScreenChangeDetector.compare(before, after)
            before?.recycle()
            log("Screen change: $changeLevel")

            when (changeLevel) {
                ScreenChangeDetector.ChangeLevel.SIGNIFICANT -> {
                    consecutiveStuckCount = 0
                    recentScreenHashes.clear()
                    after?.recycle()
                    return
                }
                ScreenChangeDetector.ChangeLevel.MINOR -> {
                    log("Screen loading — waiting extra ${LOADING_SETTLE_MS}ms")
                    delay(LOADING_SETTLE_MS)
                    consecutiveStuckCount = 0
                    after?.recycle()
                    return
                }
                ScreenChangeDetector.ChangeLevel.NONE -> {
                    after?.recycle()
                    attempts++
                }
            }
        }

        log("⚠️ Screen unchanged after ${MAX_ACTION_RETRIES + 1} attempts")
    }

    // ── Phase 2: stuck detection ──────────────────────────────────────

    private fun updateStuckState(hash: Long) {
        recentScreenHashes.addLast(hash)
        if (recentScreenHashes.size > STUCK_WINDOW) recentScreenHashes.removeFirst()

        if (recentScreenHashes.size >= STUCK_WINDOW) {
            val allSame = recentScreenHashes.all { ScreenChangeDetector.isSameScreen(it, hash) }
            consecutiveStuckCount = if (allSame) consecutiveStuckCount + 1 else 0
        }
    }

    private fun buildStuckHint(): String? {
        if (consecutiveStuckCount < 1) return null
        val recentActions = stepHistory.takeLast(STUCK_WINDOW).joinToString("\n") { "  • $it" }
        return """
⚠️ STUCK ALERT: The screen has not changed for $consecutiveStuckCount consecutive steps.
Recent actions that had no effect:
$recentActions

You MUST try a completely different approach. Do NOT repeat the same action.
Consider: scrolling to find the element, pressing BACK and trying again,
looking for the element in a different part of the screen, or using a
different UI path to accomplish the same goal.
        """.trimIndent()
    }

    // ── Private helpers ───────────────────────────────────────────────

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
