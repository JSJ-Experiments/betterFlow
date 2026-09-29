# betterFlow

Root-first Android voice typing that stays available, transcribes through Wispr Flow, and inserts text into the focused editor.

## Architecture

- **Gboard trigger:** in-process, event-driven mic interception and transcription. It does not ping or wake Gboard in the background.
- **Optional floating APK service:** draggable tap-to-record bubble using a foreground service only while the bubble is enabled.
- **Wispr client:** email login or session JSON import, automatic refresh-token renewal, HTTP transcription fallback.
- **Capture history:** every completed bubble/Gboard recording is saved locally as lossless 16 kHz mono PCM WAV plus JSON metadata, transcript, provider response, attempt count, and insertion outcome. The app's History screen plays audio, retries transcription from the saved sample, copies text, and exports a portable ZIP with `dataset.jsonl`.
- **Selectable insertion:**
  - **Auto:** direct `InputConnection.commitText()` through an LSPosed IME bridge, then root clipboard + `KEYCODE_PASTE` fallback.
  - **LSPosed:** direct InputConnection only.
  - **Clipboard/root paste:** compatibility fallback.
- **KernelSU module:** grants required permissions once at boot and restores the bubble only when it was enabled. It does not run a persistent watchdog or adjust the app's OOM score.
- **Manual hot update:** the KernelSU Action downloads a SHA-256-verified runtime release, installs the APK in place, and restores the bubble only when enabled. It does not request a reboot.

## First install

1. Install `betterflow-module.zip` from the latest GitHub release in KernelSU.
2. Open betterFlow once, grant microphone/notification permission, and sign in to Wispr or import a Wispr session JSON.
3. Use an LSPosed build supporting Xposed API 101, then enable betterFlow and scope it to your current keyboard. Gboard and AOSP LatinIME are predeclared. Manual APK updates require restarting the keyboard process so LSPosed loads the new hook code; the module's hot-update Action does this for the active Gboard/LatinIME automatically.
4. Choose **Auto** in betterFlow. If the LSPosed bridge is unavailable, it falls back to root paste.

KernelSU's **Action** button checks for and applies the newest release without rebooting. Updates are manual, so betterFlow performs no periodic network or process polling.

With the floating microphone disabled, betterFlow has no long-running app service. Gboard voice typing remains available through the LSPosed hook and only does work in response to keyboard lifecycle and touch events.

History retries and exports are manual: there is no periodic sync, background retry loop, or idle audio work. Captures live in app-private storage and survive app updates, but not uninstall/data clearing; export a ZIP before either. Audio and transcripts may be sensitive, so the ZIP should be handled accordingly. If automatic insertion fails, betterFlow copies the transcript to the clipboard and keeps it in History. Failed transcription/insertion can also raise a tap-to-open History notification when notification permission is granted.

## CI

Android builds happen only in GitHub Actions on a Blacksmith runner. The development server is used for source editing, not Gradle compilation.

Release signing is supplied through repository secrets. User Wispr access/refresh tokens are never committed or embedded in release artifacts.

## Development status

This is an experimental root/LSPosed project. The initial build uses Wispr's HTTP audio endpoint for reliability; the recovered live gRPC protocol is kept out of the public runtime until its service credentials can be provisioned cleanly rather than compiled into the APK.
