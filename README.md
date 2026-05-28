# ScreenWatcher

An Android app that watches a user-defined region of the screen for visual changes and triggers an alarm when movement is detected.

## How It Works

1. Tap **Select Region** to draw a selection box over any part of your screen using a full-screen overlay.
2. Grant screen capture permission when prompted.
3. The app captures the selected region every second and compares pixel values frame to frame.
4. When enough pixels change, the alarm fires. Tap **Recapture Region** in the notification to reselect a region, or tap **Stop** to stop the alarm and the service entirely.

The service runs in the foreground and survives the app being closed. The persistent notification shows live status and gives you direct controls without reopening the app.

## Compatibility

- **Minimum SDK**: 26 (Android 8.0)
- **Target SDK**: 34 (Android 14)
- **Device Types Supported**: Phone

## Building the Project

Clone the repository:

```sh
git clone https://github.com/bogdanjanjic04/ScreenWatcher
```

Build a debug APK from the project root:

```sh
./gradlew assembleDebug
```

Install directly to a connected device:

```sh
adb install app/build/outputs/apk/debug/app-debug.apk
```

## Permissions

| Permission | Reason |
|---|---|
| `FOREGROUND_SERVICE` | Keeps the monitor alive when the app is in the background |
| `FOREGROUND_SERVICE_MEDIA_PROJECTION` | Required on Android 14+ for screen capture foreground services |
| `FOREGROUND_SERVICE_DATA_SYNC` | Used when the service is running but not yet capturing |
| `POST_NOTIFICATIONS` | Shows the persistent status notification on Android 13+ |
| `SYSTEM_ALERT_WINDOW` | Draws the region selection overlay on top of other apps |

## Dependencies

- **AndroidX Core KTX** — Kotlin extensions for core Android APIs
- **AndroidX AppCompat** — Backwards compatible platform features
- **Material Components** — UI components

## License

MIT
