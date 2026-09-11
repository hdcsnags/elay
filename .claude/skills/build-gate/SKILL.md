---
name: build-gate
description: The green-build gate for ELAY on this Windows machine — JDK, Gradle/KMP commands (or Expo/EAS if that stack is chosen), emulator install and screenshot loop, what cannot be verified without a Mac, and how to record a gate in STATE.md.
---

# Build gate

## Environment
- `export JAVA_HOME='C:\Program Files\Android\Android Studio1\jbr'` (the `1` matters).
- adb `D:\AndroidStudio\platform-tools\adb.exe`; emulator `D:\AndroidStudio\emulator\emulator.exe`; AVDs `Pixel_9_Pro_XL12` (Android 17), `Pixel_Tablet`. Check `adb devices` first — Android Studio often has one running.
- Screenshots: `adb exec-out screencap -p > shot.png`, then Read the PNG. Taps: `adb shell input tap X Y` (physical px; 1344×2992 on the Pixel 9 Pro XL). Long-press: `input swipe X Y X Y 900`. Drag: `input motionevent DOWN/MOVE/UP`.
- adb extras with spaces: `adb shell "am start -n <pkg>/.MainActivity --es key 'two words'"`.

## Spawning the emulator yourself (concierge or a coder seat)
```
"D:/AndroidStudio/platform-tools/adb.exe" devices                      # anything running?
"D:/AndroidStudio/emulator/emulator.exe" -avd Pixel_9_Pro_XL12 -no-snapshot-load -no-boot-anim &   # add -no-window for headless
"D:/AndroidStudio/platform-tools/adb.exe" wait-for-device shell 'while [ "$(getprop sys.boot_completed)" != "1" ]; do sleep 2; done'
adb install -r -g app/build/outputs/apk/debug/app-debug.apk
adb shell am start -n <package>/.MainActivity; sleep 8; adb exec-out screencap -p > shots/01.png
```
Then tap/swipe/type with `adb shell input …` and screenshot after each step; Read the PNGs and judge them. A seat's report must include the screenshot paths and what each shows. Headless (`-no-window`) still supports `screencap`.

## KMP / Compose Multiplatform
```
./gradlew.bat :composeApp:assembleDebug --console=plain -q
./gradlew.bat :shared:allTests --console=plain -q          # common + Android unit tests
./gradlew.bat :composeApp:lintDebug ktlintCheck detekt -q  # whichever are wired
./gradlew.bat :composeApp:bundleRelease --console=plain -q # gate builds only
```
Kotlin/Native iOS targets (`iosArm64`, `iosSimulatorArm64`) do **not** compile on Windows. iOS builds run on a Mac: Michael's local Mac, a GitHub Actions `macos-*` runner (Xcode preinstalled; minutes billed at the macOS rate on private repos), or a hosted CI (Codemagic/Bitrise). Gate 0a picks; until then record "iOS: pending" at each gate. Keep `expect/actual` surfaces small and named in `adr/`.

## Recording a gate
In `STATE.md`: gate name, date, each checklist line with its evidence (command + last lines, or screenshot path under `research/shots/`), versionCode, AAB path and signature check (`jarsigner -verify`). A gate without evidence is not closed.

## Pitfalls seen on this machine
Python text mode writes CRLF (use `newline=""`); heredoc backslashes get mangled (write script files); Gradle lock waits when two builds overlap (fine, be patient); `am force-stop` invalidates widget/notification pending intents (use `am kill` to simulate a cold start).
