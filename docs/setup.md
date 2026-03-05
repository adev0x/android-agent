# Setup Guide

## Requirements

- Android device running **Android 10 (API 29)** or later
- **Android Studio Hedgehog** (2023.1.1) or later
- An **Anthropic API key** — get one at [console.anthropic.com](https://console.anthropic.com)
- USB cable or wireless ADB to connect device

> The app works on emulators but gesture dispatch behaves differently. A real device is recommended for testing.

## Step 1: Clone and open

```bash
git clone https://github.com/adev0x/android-agent
cd android-agent
```

Open the project in Android Studio. Let Gradle sync complete.

## Step 2: Connect your device

Enable Developer Options on your Android device:

1. Go to **Settings → About phone**
2. Tap **Build number** 7 times
3. Go back to **Settings → Developer options**
4. Enable **USB debugging**
5. Connect via USB and accept the debug prompt on your phone

You should see your device in the Android Studio device selector.

## Step 3: Build and install

Click **Run** (▶) in Android Studio, or from the terminal:

```bash
./gradlew installDebug
```

## Step 4: Grant permissions in the app

Open **Agent Phone** on your device. You'll see two setup buttons:

### Screen Capture

Tap **Screen Capture**. Android will show its standard system dialog:

> "Agent Phone will start capturing everything that's displayed on your screen."

Tap **Start now**. A persistent notification will appear — this is required by Android for any app doing screen capture.

### Accessibility Service

Tap **Accessibility**. This opens Android's Accessibility Settings.

Navigate to **Downloaded apps** (or **Installed apps** on some devices) and find **Agent Phone**. Tap it and enable the toggle.

Android will warn:

> "Agent Phone can observe actions you take, including text you type such as passwords and credit card numbers."

This is the standard Android warning for all accessibility services. Tap **Allow**.

Once both are granted, the status indicator turns green: **● Ready**

## Step 5: Add your API key

Paste your Anthropic API key into the **Anthropic API key** field. It's saved locally in Android SharedPreferences and never transmitted anywhere except directly to the Anthropic API.

## Step 6: Run a task

The default task in the input field is a safe starting point:

> "Open Gmail, find the most recent email, extract any address, then open Google Maps with that address"

Replace it with anything you want, then tap **▶ Run Agent**.

Watch the log panel — the agent narrates every step it takes.

## Troubleshooting

**"Screen capture service not running"**
The foreground service was killed by Android (battery optimization). Tap **Screen Capture** again to re-grant.

**"Accessibility service disconnected"**
The accessibility service was killed. Go to Settings → Accessibility → Agent Phone and re-enable it.

**Taps landing in wrong place**
This would indicate a coordinate scaling bug. File an issue with your device model and Android version.

**Agent loops without making progress**
The LLM is confused by the screen. Try a simpler task first, or check the log for what action it's repeatedly attempting.

**API errors**
Check that your API key is correct and has available credits at [console.anthropic.com](https://console.anthropic.com).

## Notes on banking apps

Most banking apps set `FLAG_SECURE`, which blocks both screenshots and accessibility content reading. Agent Phone cannot see or interact with these apps — this is an intentional Android security feature, not a bug.
