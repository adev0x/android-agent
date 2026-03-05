# How It Works

## The core idea

Most phone automation tools work through APIs — they call `gmail.getEmails()` or `uber.bookRide()`. This is clean but brittle: it requires API access for every app, and breaks whenever an app removes or changes its API.

Agent Phone takes a different approach: it looks at your screen and figures out what to tap, exactly the way you would. It doesn't need special integration with any app. If you can see it, the agent can interact with it.

## The loop

Every task runs as a repeating cycle:

```
1. Take a screenshot of the current screen
2. Send it to a vision AI model
3. The model decides what to do next
4. Execute that action on the phone
5. Go back to step 1
```

This continues until the model says the task is done (or fails, or hits the 30-step limit).

## Seeing the screen

Android's `MediaProjection` API lets apps capture screenshots with explicit user permission. This is the same mechanism screen recorders use. The user approves it once via a system dialog, and the app can capture frames on demand.

Screenshots are taken at 50% resolution and encoded as JPEG to keep API calls fast and cheap. A typical screenshot is 150–300KB.

## Understanding the screen

The screenshot is sent to Claude along with:

- The task description
- A summary of the last few steps taken
- The accessibility tree — a text dump of all visible UI elements with their labels, positions, and whether they're clickable or editable

The accessibility tree gives the model structured information it can use to reason about the screen even before looking at the image. If there's a button labeled "Book" at coordinates [540, 1200], both the image and the tree confirm it.

The model responds with a single JSON action:

```json
{"action": "tap", "x": 540, "y": 1200, "reason": "tapping the Book button to confirm ride"}
```

## Executing actions

Android's `AccessibilityService` API allows apps to inject gestures and interact with other apps' UI — with user permission. This is what screen readers like TalkBack use, and what powers the TalkBack switch controls.

The agent uses it to:

- **Tap** at precise pixel coordinates (scaled from the screenshot's coordinate system to the real screen)
- **Swipe** for scrolling or navigation gestures
- **Type** text directly into focused input fields
- **Press** system buttons (Back, Home, Recents)

These actions are dispatched via `GestureDescription` and are asynchronous — the code waits for Android to confirm the gesture completed before taking the next screenshot.

## Coordinate scaling

The screenshot sent to the model is smaller than the real screen. If your phone has a 1440×3120 display, the screenshot might be 720×1560. The model returns coordinates in screenshot space (e.g. "tap at 360, 780").

Before executing, those coordinates are multiplied by scale factors:

```
real_x = llm_x × (screenWidth / captureWidth)
real_y = llm_y × (screenHeight / captureHeight)
```

So "tap at 360, 780" on a 720×1560 screenshot becomes "tap at 720, 1560" on the real 1440×3120 screen — the correct center of the screen.

## Confirmation before destructive actions

For actions with real-world consequences — booking a ride, placing an order, sending a message — the model outputs a `confirm` action instead of proceeding directly:

```json
{"action": "confirm", "message": "About to book UberX to 123 Main St for $14.50 — proceed?"}
```

The app pauses, shows a dialog, and only continues if the user approves. This is a hard requirement: the agent should never spend money or send messages without explicit user confirmation.

## What apps it can and can't control

**Can control:** Any app that doesn't explicitly block accessibility services. Gmail, Maps, Uber, Spotify, WhatsApp, Chrome, Settings — essentially everything except banking apps.

**Cannot control:** Apps that set `FLAG_SECURE` (most banking apps, some payment apps). This flag blocks both screenshots and accessibility tree access. When a banking app is open, Agent Phone literally cannot see the screen — this is intentional.

## Privacy

Screenshots are sent to Anthropic's API over HTTPS to determine the next action. They are not stored persistently on the device or anywhere else. The API key is stored in Android's `SharedPreferences` (local only).

A future version will support running a vision model entirely on-device (using the Qualcomm/MediaTek NPU on modern Android phones), making the whole loop fully offline.
