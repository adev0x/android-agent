package com.agentphone

import android.Manifest
import android.app.AlertDialog
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.media.projection.MediaProjectionManager
import android.os.Bundle
import android.provider.Settings
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.lifecycle.lifecycleScope
import com.agentphone.databinding.ActivityMainBinding
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import kotlin.coroutines.resume

class MainActivity : AppCompatActivity() {

    private lateinit var binding: ActivityMainBinding
    private var agentLoop: AgentLoop? = null

    // Phase 3: voice input
    private var speechHandler: SpeechInputHandler? = null
    private var isListening = false

    companion object {
        private const val PREFS_NAME = "agent_phone_prefs"
        private const val PREF_API_KEY = "api_key"
        private const val CONFIRM_TIMEOUT_MS = 60_000L
        // Keep the log view from growing without bound — drop oldest lines when exceeded
        private const val MAX_LOG_LINES = 200
    }

    // ── Permission launchers ──────────────────────────────────────────

    private val screenCaptureLauncher = registerForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) { result ->
        if (result.resultCode == RESULT_OK && result.data != null) {
            val serviceIntent = Intent(this, ScreenCaptureService::class.java).apply {
                putExtra(ScreenCaptureService.EXTRA_RESULT_CODE, result.resultCode)
                putExtra(ScreenCaptureService.EXTRA_RESULT_DATA, result.data)
            }
            startForegroundService(serviceIntent)
            updateStatus()
            log("✓ Screen capture granted")
        } else {
            log("✗ Screen capture denied")
        }
    }

    // Phase 3: RECORD_AUDIO runtime permission request
    private val micPermissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { granted ->
        if (granted) {
            log("✓ Microphone permission granted")
            startListening()
        } else {
            toast("Microphone permission needed for voice input")
        }
    }

    // ── Lifecycle ─────────────────────────────────────────────────────

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)

        restoreApiKey()
        setupButtons()
        updateStatus()
    }

    override fun onResume() {
        super.onResume()
        updateStatus()
    }

    override fun onDestroy() {
        super.onDestroy()
        speechHandler?.destroy()
        speechHandler = null
    }

    // ── Button wiring ─────────────────────────────────────────────────

    private fun setupButtons() {
        binding.btnScreenCapture.setOnClickListener {
            val mpm = getSystemService(Context.MEDIA_PROJECTION_SERVICE) as MediaProjectionManager
            screenCaptureLauncher.launch(mpm.createScreenCaptureIntent())
        }

        binding.btnAccessibility.setOnClickListener {
            if (isAccessibilityEnabled()) {
                toast("Accessibility already granted ✓")
            } else {
                startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS))
                toast("Enable 'Agent Phone' under Downloaded apps")
            }
        }

        binding.btnRun.setOnClickListener { startAgent() }

        binding.btnStop.setOnClickListener {
            agentLoop?.stop()
            setRunning(false)
        }

        // Phase 3: mic button
        binding.btnMic.setOnClickListener {
            if (isListening) {
                stopListening()
            } else {
                requestMicAndListen()
            }
        }
    }

    // ── Agent lifecycle ───────────────────────────────────────────────

    private fun startAgent() {
        // Guard: don't start a second agent while one is already running
        if (agentLoop?.running == true) return

        val task = binding.etTask.text?.toString()?.trim().orEmpty()
        if (task.isEmpty()) { toast("Enter a task"); return }

        val apiKey = binding.etApiKey.text?.toString()?.trim().orEmpty()
        if (apiKey.isEmpty()) { toast("Enter your Anthropic API key"); return }

        if (ScreenCaptureService.instance == null) {
            toast("Grant screen capture permission first"); return
        }
        if (!isAccessibilityEnabled()) {
            toast("Enable accessibility service first"); return
        }
        if (AccessibilityActionService.instance == null) {
            toast("Accessibility service connected — try again in a moment"); return
        }

        saveApiKey(apiKey)
        clearLog()
        setRunning(true)

        agentLoop = AgentLoop(
            llmClient = VisionLLMClient(apiKey),
            onLog = { msg -> runOnUiThread { log(msg) } },
            onConfirmNeeded = { msg -> showConfirmDialog(msg) },
            onComplete = { result ->
                runOnUiThread {
                    log("─".repeat(40))
                    log("✅ Done: $result")
                    setRunning(false)
                }
            },
            onFailed = { reason ->
                runOnUiThread {
                    log("─".repeat(40))
                    log("❌ Failed: $reason")
                    setRunning(false)
                }
            }
        )

        lifecycleScope.launch {
            agentLoop?.run(task)
        }
    }

    // ── Phase 3: Voice input ──────────────────────────────────────────

    /**
     * Check RECORD_AUDIO permission; if not yet granted, request it.
     * Once granted, starts the listen flow.
     */
    private fun requestMicAndListen() {
        when {
            ContextCompat.checkSelfPermission(this, Manifest.permission.RECORD_AUDIO)
                    == PackageManager.PERMISSION_GRANTED -> startListening()

            shouldShowRequestPermissionRationale(Manifest.permission.RECORD_AUDIO) -> {
                AlertDialog.Builder(this)
                    .setTitle("Microphone access")
                    .setMessage("Agent Phone uses the microphone to let you speak your tasks instead of typing.")
                    .setPositiveButton("Grant") { _, _ ->
                        micPermissionLauncher.launch(Manifest.permission.RECORD_AUDIO)
                    }
                    .setNegativeButton("Cancel", null)
                    .show()
            }

            else -> micPermissionLauncher.launch(Manifest.permission.RECORD_AUDIO)
        }
    }

    /**
     * Start listening for a spoken task.
     * Updates the mic button appearance while listening.
     * On success, fills the task field with the transcription.
     */
    private fun startListening() {
        if (isListening) return
        isListening = true
        setMicActive(true)
        log("🎤 Listening...")

        if (speechHandler == null) {
            speechHandler = SpeechInputHandler(this)
        }

        lifecycleScope.launch(Dispatchers.Main) {
            val result = speechHandler!!.listen(
                onPartial = { partial ->
                    // Show partial transcription in the task field as feedback
                    binding.etTask.setText(partial)
                    binding.etTask.setSelection(partial.length)
                },
                onState = { state -> log("🎤 $state") }
            )

            isListening = false
            setMicActive(false)

            if (result != null) {
                binding.etTask.setText(result)
                binding.etTask.setSelection(result.length)
                log("🎤 Heard: \"$result\"")
            } else {
                log("🎤 No speech detected — try again")
            }
        }
    }

    /**
     * Stop an active listening session early.
     */
    private fun stopListening() {
        speechHandler?.stop()
        isListening = false
        setMicActive(false)
        log("🎤 Stopped")
    }

    /**
     * Toggle mic button appearance: active (red tint) vs idle (grey tint).
     */
    private fun setMicActive(active: Boolean) {
        binding.btnMic.setColorFilter(
            if (active) 0xFFE53935.toInt()   // red — recording
            else        0xFFAAAAAA.toInt()    // grey — idle
        )
    }

    // ── Confirmation dialog (suspend, with timeout) ───────────────────

    private suspend fun showConfirmDialog(message: String): Boolean {
        return withTimeoutOrNull(CONFIRM_TIMEOUT_MS) {
            suspendCancellableCoroutine { cont ->
                val dialog = AlertDialog.Builder(this@MainActivity)
                    .setTitle("Confirm Action")
                    .setMessage(message)
                    .setPositiveButton("Yes, proceed") { _, _ ->
                        if (cont.isActive) cont.resume(true)
                    }
                    .setNegativeButton("Cancel") { _, _ ->
                        if (cont.isActive) cont.resume(false)
                    }
                    .setOnCancelListener {
                        if (cont.isActive) cont.resume(false)
                    }
                    .show()

                cont.invokeOnCancellation { dialog.dismiss() }
            }
        } ?: false
    }

    // ── UI helpers ────────────────────────────────────────────────────

    private fun updateStatus() {
        val captureOk = ScreenCaptureService.instance != null
        val a11yOk = isAccessibilityEnabled()
        val svcOk = AccessibilityActionService.instance != null

        binding.btnScreenCapture.text = if (captureOk) "✓ Screen Capture" else "Screen Capture"
        binding.btnAccessibility.text = if (a11yOk) "✓ Accessibility" else "Accessibility"

        when {
            captureOk && a11yOk && svcOk -> {
                binding.tvStatus.text = "● Ready"
                binding.tvStatus.setTextColor(0xFF4CAF50.toInt())
            }
            captureOk && a11yOk -> {
                binding.tvStatus.text = "● Connecting..."
                binding.tvStatus.setTextColor(0xFFFFEB3B.toInt())
            }
            else -> {
                binding.tvStatus.text = "● Setup needed"
                binding.tvStatus.setTextColor(0xFFFF9800.toInt())
            }
        }
    }

    private fun isAccessibilityEnabled(): Boolean {
        val enabled = Settings.Secure.getString(
            contentResolver,
            Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES
        ) ?: return false
        return enabled.contains(packageName, ignoreCase = true)
    }

    private fun setRunning(running: Boolean) {
        binding.btnRun.isEnabled = !running
        binding.btnStop.isEnabled = running
        binding.etTask.isEnabled = !running
        binding.etApiKey.isEnabled = !running
        binding.btnMic.isEnabled = !running
        updateStatus()
    }

    private fun log(msg: String) {
        val current = binding.tvLog.text.toString()
        val appended = if (current.startsWith("Ready.")) msg else "$current\n$msg"

        // Trim from the top once the log exceeds MAX_LOG_LINES to avoid unbounded growth
        val lines = appended.lines()
        val trimmed = if (lines.size > MAX_LOG_LINES) {
            lines.takeLast(MAX_LOG_LINES).joinToString("\n")
        } else appended

        binding.tvLog.text = trimmed
        binding.scrollLog.post { binding.scrollLog.fullScroll(android.view.View.FOCUS_DOWN) }
    }

    private fun clearLog() { binding.tvLog.text = "" }

    private fun toast(msg: String) = Toast.makeText(this, msg, Toast.LENGTH_SHORT).show()

    private fun saveApiKey(key: String) =
        getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE).edit().putString(PREF_API_KEY, key).apply()

    private fun restoreApiKey() {
        val key = getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE).getString(PREF_API_KEY, "") ?: ""
        binding.etApiKey.setText(key)
    }
}
