# betterFlow

Root-first Android voice typing that stays available, transcribes through Wispr Flow, and inserts text into the focused editor.

## Architecture

- **Floating APK service:** draggable tap-to-record bubble using a foreground service.
- **Wispr client:** email login or session JSON import, automatic refresh-token renewal, HTTP transcription fallback.
- **Selectable insertion:**
  - **Auto:** direct `InputConnection.commitText()` through an LSPosed IME bridge, then root clipboard + `KEYCODE_PASTE` fallback.
  - **LSPosed:** direct InputConnection only.
  - **Clipboard/root paste:** compatibility fallback.
- **KernelSU module:** grants the overlay AppOp, keeps the service alive, lowers its OOM score, and checks releases.
- **Hot update:** the KernelSU Action downloads a SHA-256-verified runtime release, installs the APK in place, restarts the service, and does not request a reboot.

## First install

1. Install `betterflow-module.zip` from the latest GitHub release in KernelSU.
2. Open betterFlow once, grant microphone/notification permission, and sign in to Wispr or import a Wispr session JSON.
3. For direct insertion, enable betterFlow in LSPosed and scope it to your current keyboard. Gboard and AOSP LatinIME are predeclared.
4. Choose **Auto** in betterFlow. If the LSPosed bridge is unavailable, it falls back to root paste.

KernelSU's **Action** button is the fast-update button. It fetches and applies the newest release without rebooting. The watchdog also checks every six hours by default.

## CI

Android builds happen only in GitHub Actions on a Blacksmith runner. The development server is used for source editing, not Gradle compilation.

Release signing is supplied through repository secrets. User Wispr access/refresh tokens are never committed or embedded in release artifacts.

## Development status

This is an experimental root/LSPosed project. The initial build uses Wispr's HTTP audio endpoint for reliability; the recovered live gRPC protocol is kept out of the public runtime until its service credentials can be provisioned cleanly rather than compiled into the APK.
