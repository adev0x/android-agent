# Contributing

Contributions are welcome. Here's how to get involved.

## What's most useful right now

Phase 1 (core loop) is done. The highest-value contributions are for Phase 2 and beyond:

- **Change detection** — compare before/after screenshots to verify an action had effect
- **Retry logic** — recover when a tap doesn't register or a screen doesn't load
- **Voice input** — SpeechRecognizer integration so you can speak tasks instead of typing
- **Task planner** — break complex multi-app tasks into verified sub-steps before executing
- **On-device LLM** — replace the Anthropic API call with a local model (Gemma 3, PaliGemma)
- **App-specific handlers** — optimized flows for Gmail, Maps, WhatsApp that skip the vision loop when the accessibility tree is sufficient

## Getting started

```bash
git clone https://github.com/adev0x/android-agent
cd android-agent
# Open in Android Studio (Hedgehog or later)
# Connect an Android 10+ device
# Run
```

You'll need an [Anthropic API key](https://console.anthropic.com) to test the vision loop.

## Project structure

```
app/src/main/java/com/agentphone/
├── ScreenCaptureService.kt       # MediaProjection — screenshots
├── AccessibilityActionService.kt # tap / swipe / type / press
├── VisionLLMClient.kt            # Claude API — screenshot → action
├── AgentLoop.kt                  # perceive → think → act loop
└── MainActivity.kt               # UI
```

Each file has a single responsibility. Changes should stay within that boundary where possible.

## Pull requests

- Keep PRs focused — one logical change per PR
- If adding a new capability, include a brief description of how to test it
- If modifying `AgentLoop` or `VisionLLMClient`, explain the reasoning — these are the most sensitive parts of the system
- No third-party dependencies without a good reason — the current stack (OkHttp, Gson, Coroutines) covers most needs

## Issues

Bug reports and feature requests are welcome. For bugs, include:
- Android version and device model
- What task you gave the agent
- What happened vs what you expected
- Logcat output from `AgentPhone` / `AgentLoop` / `VisionLLMClient` / `A11yAction` tags
