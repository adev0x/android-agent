# Agent Phone

An Android app that acts as an autonomous personal assistant — it sees your screen, thinks about what to do next, and controls your phone to complete multi-step tasks.

> "Get the address from my last email and open it in Google Maps"

No APIs. No app integrations. Works on any app by reading the screen visually, just like a human would.

**Docs:** [How it works](docs/how-it-works.md) · [Setup guide](docs/setup.md) · [Architecture](docs/architecture.md) · [Contributing](CONTRIBUTING.md)

---

## How it works

```
┌─────────────────────────────────────────────┐
│            PERCEIVE → THINK → ACT           │
│                                             │
│  1. Capture screenshot (MediaProjection)    │
│  2. Get UI element tree (Accessibility)     │
│  3. Send to Claude vision model             │
│  4. Claude decides: tap / swipe / type      │
│  5. Execute action on phone                 │
│  6. Loop until task complete                │
└─────────────────────────────────────────────┘
```

The vision LLM sees what's on screen and decides the next action. Your phone executes it. Repeats until done.

---

## Architecture

```
app/src/main/java/com/agentphone/
│
├── MainActivity.kt              # UI — task input, log output, permission setup
│
├── ScreenCaptureService.kt      # Captures screenshots via MediaProjection API
│   └── captureScreen() → Bitmap
│
├── AccessibilityActionService.kt # Executes actions on any app
│   ├── tap(x, y)
│   ├── swipe(x1, y1, x2, y2)
│   ├── typeText(text)
│   ├── pressBack() / pressHome()
│   ├── findAndTap(label)         # find element by text, tap it
│   └── getScreenTree() → String  # dump UI hierarchy for LLM context
│
├── VisionLLMClient.kt           # Claude API client
│   └── getNextAction(screenshot, task, history) → AgentAction
│
└── AgentLoop.kt                 # The core perceive→think→act loop
    └── run(task)                # suspending, runs until done/fail/stop
```

---

## Setup

### Requirements
- Android 10+ (API 29)
- An [Anthropic API key](https://console.anthropic.com)
- Android Studio Hedgehog or later

### Build
```bash
git clone https://github.com/yourname/agent-phone
cd agent-phone
# Open in Android Studio and run on device or emulator
```

### Permissions (two required)

**1. Screen Capture** — tap the button in the app. Android shows its standard "Start recording?" dialog. Approve it.

**2. Accessibility Service** — tap the button, go to Settings → Accessibility → Downloaded apps → Agent Phone → Enable.

These are the only two permissions needed. No root required.

---

## Usage

1. Grant both permissions (Screen Capture + Accessibility)
2. Enter your Anthropic API key
3. Type a task in plain English
4. Tap **Run Agent**
5. Watch the log — the agent narrates each step
6. For destructive actions (booking, sending), the app pauses and asks for confirmation

### Example tasks

```
Open Gmail and tell me who sent the most recent email

Find the address in my last email from John and open it in Google Maps

Open Spotify and play something from my Liked Songs

Go to Settings and turn on Do Not Disturb

Open WhatsApp and send "On my way" to the last conversation
```

---

## Agent actions

The LLM can output any of these actions:

| Action | Description |
|--------|-------------|
| `tap(x, y)` | Tap at pixel coordinates |
| `swipe(x1,y1,x2,y2)` | Swipe gesture |
| `type(text)` | Type into focused field |
| `press(BACK\|HOME)` | System buttons |
| `wait()` | Pause for screen to load |
| `confirm(message)` | Pause and ask user before proceeding |
| `done(result)` | Task complete |
| `fail(reason)` | Task could not be completed |

---

## Phase roadmap

- [x] **Phase 1** — Core loop: screen capture + vision LLM + action execution
- [ ] **Phase 2** — Autonomous loop improvements: change detection, error recovery, retry logic
- [ ] **Phase 3** — Voice input: speak your task instead of typing
- [ ] **Phase 4** — Task planner: break complex tasks into verified sub-steps
- [ ] **Phase 5** — On-device LLM option: run Gemma locally, zero data leaves phone

---

## How it compares

| Approach | Works on | Requires |
|----------|----------|----------|
| **This app** | Any app, any screen | Screen capture + accessibility |
| API-based (Gemini) | Partnered apps only | App-specific API access |
| Rooted device | Everything | Root |

The key insight: a vision model that can read any screen doesn't need special API access to any app. It works the same way a human looking at the screen would.

---

## Privacy

- Screenshots are sent to Anthropic's API to determine the next action
- No screenshots are stored persistently
- API key is stored in Android's SharedPreferences (local only)
- A future phase will support on-device vision models for fully offline operation

---

## License

MIT
