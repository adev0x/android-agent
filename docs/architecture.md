# Architecture

## Overview

Agent Phone is built around a single loop: **perceive → think → act**. Each iteration captures the screen, sends it to a vision LLM, receives an action decision, executes it, and repeats.

```
┌─────────────────────────────────────────────────────────┐
│                     AgentLoop                           │
│                                                         │
│   ┌──────────────┐     ┌──────────────┐                 │
│   │ScreenCapture │────▶│ VisionLLM    │                 │
│   │Service       │     │ Client       │                 │
│   │              │     │              │                 │
│   │ screenshot() │     │ Claude API   │                 │
│   │ → Bitmap     │     │ → AgentAction│                 │
│   └──────────────┘     └──────┬───────┘                 │
│                               │                         │
│                               ▼                         │
│                    ┌──────────────────┐                 │
│                    │ Accessibility    │                 │
│                    │ ActionService    │                 │
│                    │                 │                  │
│                    │ tap(x, y)       │                  │
│                    │ swipe(...)      │                  │
│                    │ typeText(...)   │                  │
│                    │ pressBack/Home  │                  │
│                    └──────────────────┘                 │
└─────────────────────────────────────────────────────────┘
```

## Components

### ScreenCaptureService

Runs as an Android foreground service using the `MediaProjection` API. This is the official Android mechanism for screen recording — the same API used by screen recorders and casting apps.

Key details:
- Captures at **50% of screen resolution** (e.g. 720×1560 on a 1440×3120 device) to reduce payload size
- Tracks both `screenW/H` (real device dimensions) and `captureW/H` (capture dimensions)
- Exposes `scaleX` / `scaleY` so coordinates can be scaled back to real screen space before tapping
- Returns a `Bitmap` per call — caller is responsible for recycling it

### AccessibilityActionService

An Android `AccessibilityService` that executes physical gestures on the real screen. To the phone's OS and all apps, these are indistinguishable from a human finger.

Key details:
- `tap()` and `swipe()` are **suspend functions** — they wait for `GestureResultCallback.onCompleted` before returning, ensuring the gesture fully completes before the next screenshot is taken
- `typeText()` uses `ACTION_SET_TEXT` on the focused node — faster and more reliable than simulating individual key events
- `getScreenTree()` dumps the accessibility tree as text, giving the LLM structural info about on-screen elements alongside the visual screenshot

### VisionLLMClient

Sends screenshots to Claude's vision API and parses the response into a typed `AgentAction`.

Key details:
- Images are capped at **1024px wide** before encoding — sufficient for Claude to read any mobile UI
- The system prompt includes the **actual image dimensions** so Claude's coordinates match what it sees
- Responses are expected as JSON. `extractJson()` strips any surrounding prose Claude might add
- All actions map to a sealed class: `Tap`, `Swipe`, `Type`, `Press`, `Wait`, `Confirm`, `Done`, `Fail`

### AgentLoop

Orchestrates the loop. Handles:
- Scaling LLM coordinates from capture space → screen space before passing to `AccessibilityActionService`
- Maintaining step history (last 5 steps sent as context on each LLM call)
- Pausing on `Confirm` actions and waiting for user approval
- Safety limit of 30 steps per task
- Clean stop via `stop()`

## Coordinate system

This is the most important thing to understand when modifying the code.

```
Real screen:     1440 × 3120  (example Pixel 9 Pro)
Capture:          720 × 1560  (50% — what ScreenCaptureService produces)
Sent to LLM:      720 × 1560  (or smaller if > 1024px wide)

LLM returns:  tap(360, 780)   ← in LLM image space
AgentLoop scales:             ← × scaleX, × scaleY
Tap executes: tap(720, 1560)  ← in real screen space
```

`scaleX = screenW / captureW` and `scaleY = screenH / captureH`.

If the image is further downscaled in `VisionLLMClient` (e.g. from 720 to 512 wide), that additional scaling is also baked into `imgW/imgH` which are reported in the system prompt, so Claude's coordinates are always relative to the image it actually received.

## Threading model

```
Main thread:   UI updates, gesture dispatch (required by AccessibilityService)
IO thread:     Network calls (LLM API), screenshot capture (ImageReader)
```

`AgentLoop.run()` runs on `Dispatchers.IO`. Gestures are dispatched via `withContext(Dispatchers.Main)`. LLM calls happen on IO. The callbacks (`onLog`, `onComplete`, `onFailed`) use `runOnUiThread` in `MainActivity`.

## Permissions

| Permission | Why |
|---|---|
| `FOREGROUND_SERVICE` | Required to keep ScreenCaptureService alive |
| `FOREGROUND_SERVICE_MEDIA_PROJECTION` | Android 14+ requirement for MediaProjection foreground services |
| `INTERNET` | LLM API calls |
| `SYSTEM_ALERT_WINDOW` | Reserved for future overlay UI |
| Accessibility Service | Declared in manifest; user grants in Settings |
| Screen Capture | User grants at runtime via MediaProjectionManager dialog |

## What the LLM receives each step

```
System prompt:
  - Role: Android agent
  - Coordinate system explanation (image dimensions)
  - Full action schema with examples
  - Confirmation requirement for destructive actions

User message:
  - Task description
  - Last 5 steps taken
  - Accessibility tree dump (if available)
  - Screenshot (base64 JPEG)
```

## Extension points

**Add a new action type:**
1. Add a new subclass to `AgentAction` sealed class in `VisionLLMClient.kt`
2. Add the JSON schema example to `SYSTEM_PROMPT_TEMPLATE`
3. Add a `when` branch in `AgentLoop.run()` to handle it
4. Add execution logic calling `AccessibilityActionService` or another service

**Swap the LLM:**
Replace the API call in `VisionLLMClient.getNextAction()`. The rest of the system is model-agnostic — it only cares about receiving a valid `AgentAction`.

**Add on-device inference:**
Replace `VisionLLMClient` with a local model runner (e.g. MediaPipe LLM Inference API with PaliGemma). The interface stays the same: `(Bitmap, String, List<String>, String?) → AgentAction`.
