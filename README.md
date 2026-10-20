# betterFlow

Root-first Android voice typing that stays available, transcribes through Wispr Flow, and inserts text into the focused editor.

## Architecture

- **Gboard trigger:** in-process, event-driven mic interception and transcription. It does not ping or wake Gboard in the background.
- **Optional floating APK service:** draggable tap-to-record bubble using a foreground service only while the bubble is enabled.
- **Wispr client:** current streaming host discovery, browser/Google device-code sign-in, WorkOS/Supabase sessions, existing email/password + OTP login and session JSON import, automatic refresh-token renewal, and HTTP transcription fallback.
- **Saved accounts and usage:** retain multiple private sessions, switch the active account manually, and view its weekly mobile words, usage bar, allowance, reset time and hard-limit warning. Existing single-account sessions migrate automatically.
- **Optional auto-copy:** copy every completed transcript to the clipboard as well as inserting it, including Gboard and History retries.
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

Gboard completed captures use a durable, acknowledged outbox. Failed handoffs retry on keyboard/capture lifecycle events, not an idle timer. Opening/resuming History and **Refresh history** actively ask the hooked Gboard process to drain pending recordings, then reload local rows after acknowledgement. Incoming captures also update the list immediately. Missing-hook/time-out feedback is displayed instead of silently showing stale history. Restart Gboard after a manual APK update to load the new hook. A kill before a recording is staged, disk failure, or clearing Gboard data can still lose unsynced audio.

**Sign in with Google** and **Other browser sign-in** open Wispr's official HTTPS login page. Only during that explicit attempt, betterFlow checks the device-code endpoint for up to three minutes. Cancel stops the attempt without changing existing accounts. Tokens are received directly from Wispr over HTTPS, not from an exported callback activity. An interrupted/recreated activity requires starting sign-in again. Existing email/password, OTP and session-import options remain available.

History retries and exports are manual: there is no periodic sync, background retry loop, or idle audio work. Captures live in app-private storage and survive app updates, but not uninstall/data clearing; export a ZIP before either. Audio and transcripts may be sensitive, so the ZIP should be handled accordingly. If automatic insertion fails, betterFlow copies the transcript to the clipboard and keeps it in History. Failed transcription/insertion can also raise a tap-to-open History notification when notification permission is granted.

## CI

Android builds happen only in GitHub Actions on a Blacksmith runner. The development server is used for source editing, not Gradle compilation.

Release signing is supplied through repository secrets. User Wispr access/refresh tokens are never committed or embedded in release artifacts.

Manual CI builds publish APK/module/runtime assets to GitHub Releases by default (`publish_release` can be disabled). Feature-branch builds are prereleases; main builds remain the stable latest release, so an experimental branch does not silently replace users' stable updater target.
Generated manifests pin their download URLs to the exact build tag, including prereleases, rather than accidentally mixing a new manifest/hash with an older stable bundle.

## Development status

This is an experimental root/LSPosed project. Streaming still requires a privately provisioned service credential (build secret or device setting); no extracted service key is embedded in source. HTTP fallback remains available. See [Wispr 2.8.0 compatibility notes](docs/wispr-api-2.8.0.md) for artifact provenance, API contracts and validation limits.
