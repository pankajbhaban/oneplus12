# Walk Cam — 4K60 stabilized walking camera (OnePlus 12)

Open it → it's straight in the camera, landscape, 4K 60 fps, stabilized.

## Controls
- **START / PAUSE / RESUME** — big round button. Either **volume key** does the same (easy while walking).
- **END & SAVE** — *hold* it (a single tap won't end by accident). Joins every clip of the session into one video in **Gallery › WalkCam**.
- **LIGHT** — tap to cycle **AUTO → ON → OFF**. AUTO turns the flashlight on when the scene gets dark and off again when you're back in bright light.
- **Quick Settings tile** — pull down the shade › edit (pencil) › drag **Walk Cam** in. One tap opens the camera from anywhere.

## Resume-where-you-left-off
Every start/resume records a clip into the app's own folder and the session is remembered on disk.
Close the app, swipe it away, lock the phone — when you open it again it **continues recording automatically**
(if you'd paused, it waits on **RESUME**). Nothing is joined until you hold **END & SAVE**; joining is lossless (no re-encode), so a long 4K60 walk saves in seconds.

## What the top-left shows
`4K · 60 fps (live 60) · Steady EIS + OIS` — the **live** number is the real frame rate coming off the sensor, so you can see that 60 fps is actually happening. If the phone refuses a mode, the app falls back automatically: 4K60 → 4K30 → 1080p60, stabilized first.

## Build the APK (no Android Studio needed)
1. Create a new **private** repository on github.com.
2. **Add file › Upload files** → drag in everything in this folder.
   The `.github` folder is hidden on some PCs — if it doesn't upload, use **Add file › Create new file**, name it
   `.github/workflows/build-apk.yml` and paste the contents of that file.
3. Open the **Actions** tab → the "Build Walk Cam APK" run (≈4 min) → download **WalkCam-apk** at the bottom.
4. Copy the `.apk` to the phone, open it, allow "Install unknown apps" for your file manager.

Or open the folder in **Android Studio** and press Run with the phone plugged in (USB debugging on).

## Tuning
- Torch sensitivity: `DARK_ISO` / `BRIGHT_ISO` in `CameraHelpers.kt` (lower DARK_ISO = torch comes on sooner).
- Bitrates: `bitrateFor()` in `MainActivity.kt` (4K60 = 100 Mbps ≈ 750 MB per minute).
