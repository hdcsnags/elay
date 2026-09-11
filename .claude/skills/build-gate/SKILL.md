---
name: build-gate
description: The green-build gate for ELAY on this Windows machine — JDK, the verified KMP/Gradle commands, Supabase pgTAP harness, emulator install and screenshot loop, CI (GitHub Actions incl. macOS iOS job), and how to record a gate in STATE.md.
---

# Build gate (verified working 2026-09-11 on this machine)

## Environment
- `export JAVA_HOME='C:\Program Files\Android\Android Studio1\jbr'` (the `1` matters).
- adb `D:\AndroidStudio\platform-tools\adb.exe`; emulator `D:\AndroidStudio\emulator\emulator.exe`; AVDs `Pixel_9_Pro_XL12` (Android 17), `Pixel_Tablet`. Check `adb devices` first — one is often already running.
- Android SDK `D:\AndroidStudio` (`local.properties` → `sdk.dir`). Platform `android-37.0` is a MINOR-versioned SDK: apps need `compileSdk = 37` **and** `compileSdkMinor = 0`; the AGP 9.1 KMP `androidLibrary` DSL has no `compileSdkMinor`, so `shared` compiles against 36.
- Screenshots: `adb exec-out screencap -p > shot.png`, then Read the PNG. Taps: `adb shell input tap X Y` (physical px; 1344×2992 on the Pixel 9 Pro XL — screenshots read back at 898×2000, multiply by 1.5). Long-press: `input swipe X Y X Y 900`.

## The Windows gate (all must pass; verified green as one invocation)
```
cd "C:\New folder\Elay"
export JAVA_HOME='C:\Program Files\Android\Android Studio1\jbr'
./gradlew.bat ktlintCheck detekt :shared:allTests :androidApp:assembleDebug :androidApp:lintDebug --console=plain
```
**Hollow-green check (Astra's rule):** `:shared:allTests` succeeds with zero tests unless host tests exist — count them:
`grep -rho 'tests="[0-9]*"' shared/build/test-results` must sum > 0. (`withHostTestBuilder {}` in shared's androidLibrary block makes commonTest run on Windows.)

## Supabase harness (local Docker; no hosted account needed)
```
npx supabase start          # stack up (Docker must be running)
npx supabase db reset --local   # apply migrations from zero
npx supabase db lint --local --level error
npx supabase test db --local    # pgTAP suites in supabase/tests/ — assert FIELD SETS, not just row counts
```
Every migration ships WITH grants + RLS + paired allow/deny pgTAP tests (spec §14.2, ADR-007). `supabase stop` when done.

## Emulator pass
```
"D:/AndroidStudio/platform-tools/adb.exe" devices
"D:/AndroidStudio/emulator/emulator.exe" -avd Pixel_9_Pro_XL12 -no-snapshot-load -no-boot-anim &   # -no-window for headless
adb wait-for-device shell 'while [ "$(getprop sys.boot_completed)" != "1" ]; do sleep 2; done'
adb install -r -g androidApp/build/outputs/apk/debug/androidApp-debug.apk
adb shell am start -n dev.elay.app/dev.elay.MainActivity; sleep 8
adb exec-out screencap -p > research/shots/<gate>-01.png
```
First screenshot may be the splash — wait and re-shoot. Screenshot after every tap; Read each PNG and judge it. A seat's report includes screenshot paths + what each shows.

## iOS (never on Windows — GitHub Actions macOS runner, ADR-005)
Repo: `hdcsnags/elay` (public → free standard runners). `.github/workflows/ios-verify.yml` runs Kotlin/Native tests, framework link, unsigned xcodebuild on every push/PR. Drive from Windows:
```
gh run list --limit 3
gh run watch <id> --exit-status
gh run view <id> --log-failed
```
No phase gate closes while ios-verify is red. Simulator-launch + Room smoke joins when Room lands (Phase 1).

## Recording a gate
In `STATE.md`: gate name, date, each checklist line with evidence (command + last lines, screenshot path under `research/shots/`, CI run id). A gate without evidence is not closed. Release-gate builds also record versionCode, AAB path, `jarsigner -verify`.

## Pitfalls seen on this machine
Python text mode writes CRLF (`newline=""`); heredoc backslashes get mangled EXCEPT python heredocs with quoted 'EOF' work — still prefer script files; a trailing `\` before a closing quote in bash eats the quote; Gradle lock waits when two builds overlap (be patient); `am force-stop` invalidates pending intents (`am kill` for cold start); ktlint: `.editorconfig` needs `ktlint_function_naming_ignore_when_annotated_with = Composable`; iOS entry `fun MainViewController()` needs a ktlint function-naming suppress.
