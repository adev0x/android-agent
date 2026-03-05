package com.agentphone

import android.accessibilityservice.AccessibilityService
import android.accessibilityservice.GestureDescription
import android.graphics.Path
import android.graphics.Rect
import android.os.Bundle
import android.util.Log
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlin.coroutines.resume

/**
 * AccessibilityActionService
 *
 * The "hands" of the agent. Executes physical actions on the phone.
 *
 * All gesture methods (tap, swipe) are suspend functions that wait for
 * the gesture callback before returning — so AgentLoop knows the action
 * actually completed before taking the next screenshot.
 */
class AccessibilityActionService : AccessibilityService() {

    companion object {
        private const val TAG = "A11yAction"
        var instance: AccessibilityActionService? = null
            private set
    }

    override fun onServiceConnected() {
        super.onServiceConnected()
        instance = this
        Log.d(TAG, "Connected")
    }

    override fun onDestroy() {
        instance = null
        super.onDestroy()
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {}
    override fun onInterrupt() {}

    // ── Gesture actions (suspend — wait for callback) ─────────────────

    /**
     * Tap at pixel coordinates on the REAL screen.
     * Coordinates must already be in real screen space (not capture space).
     * Returns true if gesture completed, false if cancelled/failed.
     */
    suspend fun tap(x: Float, y: Float): Boolean {
        Log.d(TAG, "tap(%.1f, %.1f)".format(x, y))
        val path = Path().apply { moveTo(x, y) }
        val stroke = GestureDescription.StrokeDescription(path, 0L, 50L)
        val gesture = GestureDescription.Builder().addStroke(stroke).build()

        return suspendCancellableCoroutine { cont ->
            val dispatched = dispatchGesture(gesture, object : GestureResultCallback() {
                override fun onCompleted(g: GestureDescription) {
                    Log.d(TAG, "tap completed")
                    if (cont.isActive) cont.resume(true)
                }
                override fun onCancelled(g: GestureDescription) {
                    Log.w(TAG, "tap cancelled")
                    if (cont.isActive) cont.resume(false)
                }
            }, null)

            if (!dispatched) {
                Log.e(TAG, "tap: dispatchGesture returned false")
                if (cont.isActive) cont.resume(false)
            }
        }
    }

    /**
     * Swipe from (x1,y1) to (x2,y2). Coordinates in real screen space.
     */
    suspend fun swipe(
        x1: Float, y1: Float,
        x2: Float, y2: Float,
        durationMs: Long = 300
    ): Boolean {
        Log.d(TAG, "swipe(%.0f,%.0f → %.0f,%.0f)".format(x1, y1, x2, y2))
        val path = Path().apply { moveTo(x1, y1); lineTo(x2, y2) }
        val stroke = GestureDescription.StrokeDescription(path, 0L, durationMs)
        val gesture = GestureDescription.Builder().addStroke(stroke).build()

        return suspendCancellableCoroutine { cont ->
            val dispatched = dispatchGesture(gesture, object : GestureResultCallback() {
                override fun onCompleted(g: GestureDescription) {
                    if (cont.isActive) cont.resume(true)
                }
                override fun onCancelled(g: GestureDescription) {
                    if (cont.isActive) cont.resume(false)
                }
            }, null)

            if (!dispatched && cont.isActive) cont.resume(false)
        }
    }

    // ── Synchronous actions ───────────────────────────────────────────

    /**
     * Type text into the currently focused input field.
     * Returns true on success.
     */
    fun typeText(text: String): Boolean {
        Log.d(TAG, "typeText: \"$text\"")
        val node = findFocusedInput() ?: run {
            Log.w(TAG, "typeText: no editable field found")
            return false
        }
        val args = Bundle().apply {
            putCharSequence(AccessibilityNodeInfo.ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE, text)
        }
        val ok = node.performAction(AccessibilityNodeInfo.ACTION_SET_TEXT, args)
        node.recycle()
        return ok
    }

    fun pressBack() { performGlobalAction(GLOBAL_ACTION_BACK) }
    fun pressHome() { performGlobalAction(GLOBAL_ACTION_HOME) }
    fun pressRecents() { performGlobalAction(GLOBAL_ACTION_RECENTS) }

    /**
     * Find an element by visible text or content description and tap its center.
     * Coordinates returned are in real screen space.
     * Returns true if found and tapped.
     */
    suspend fun findAndTap(label: String): Boolean {
        val root = rootInActiveWindow ?: return false
        val node = findNodeByText(root, label)
        root.recycle()
        if (node == null) {
            Log.w(TAG, "findAndTap: not found — \"$label\"")
            return false
        }
        val bounds = Rect()
        node.getBoundsInScreen(bounds)
        node.recycle()
        return tap(bounds.centerX().toFloat(), bounds.centerY().toFloat())
    }

    /**
     * Dump the current screen's accessibility tree as text.
     * Sent to the LLM alongside the screenshot for additional element context.
     */
    fun getScreenTree(): String {
        val root = rootInActiveWindow ?: return "(no accessibility tree)"
        val sb = StringBuilder()
        dumpNode(root, sb, 0)
        root.recycle()
        return sb.toString().take(4000)
    }

    // ── Private helpers ───────────────────────────────────────────────

    private fun findFocusedInput(): AccessibilityNodeInfo? {
        val root = rootInActiveWindow ?: return null
        var node = root.findFocus(AccessibilityNodeInfo.FOCUS_INPUT)
        if (node == null) node = findEditableNode(root)
        root.recycle()
        return node
    }

    private fun findEditableNode(node: AccessibilityNodeInfo): AccessibilityNodeInfo? {
        if (node.isEditable) return AccessibilityNodeInfo.obtain(node)
        for (i in 0 until node.childCount) {
            val child = node.getChild(i) ?: continue
            val found = findEditableNode(child)
            child.recycle()
            if (found != null) return found
        }
        return null
    }

    private fun findNodeByText(node: AccessibilityNodeInfo, label: String): AccessibilityNodeInfo? {
        val text = node.text?.toString() ?: ""
        val desc = node.contentDescription?.toString() ?: ""
        if (text.contains(label, ignoreCase = true) || desc.contains(label, ignoreCase = true)) {
            return AccessibilityNodeInfo.obtain(node)
        }
        for (i in 0 until node.childCount) {
            val child = node.getChild(i) ?: continue
            val found = findNodeByText(child, label)
            child.recycle()
            if (found != null) return found
        }
        return null
    }

    private fun dumpNode(node: AccessibilityNodeInfo, sb: StringBuilder, depth: Int) {
        val indent = "  ".repeat(depth)
        val bounds = Rect()
        node.getBoundsInScreen(bounds)

        val parts = mutableListOf<String>()
        node.text?.let { parts.add("text=\"$it\"") }
        node.contentDescription?.let { parts.add("desc=\"$it\"") }
        if (node.isClickable) parts.add("clickable")
        if (node.isEditable) parts.add("editable")
        node.className?.let { parts.add(it.split(".").last()) }
        parts.add("[${bounds.left},${bounds.top},${bounds.right},${bounds.bottom}]")

        if (parts.size > 1) sb.appendLine("$indent${parts.joinToString(" | ")}")

        for (i in 0 until node.childCount) {
            val child = node.getChild(i) ?: continue
            dumpNode(child, sb, depth + 1)
            child.recycle()
        }
    }
}
