# ELAY release lane (Stage 5)

## Keystore (Michael's hands — PING)
```
mkdir %USERPROFILE%\.elay
keytool -genkeypair -v -keystore %USERPROFILE%\.elay\elay-release.jks -alias elay -keyalg RSA -keysize 4096 -validity 10000
```
Then create `%USERPROFILE%\.elay\keystore.properties`:
```
storeFile=C:/Users/<you>/.elay/elay-release.jks
storePassword=...
keyAlias=elay
keyPassword=...
```
Nothing under `%USERPROFILE%\.elay` is ever committed; `keystore.properties`/`*.jks`/`*.p12`
are gitignored belt-and-braces. **Losing the password means a permanently new app identity
on Play** — store it in a password manager.

## Building
- Signed (keystore present): `./gradlew :androidApp:bundleRelease` → signed AAB.
- Unsigned (CI/clean clone): same command; the signing config is omitted when the
  properties file is absent.
- Version: bump `elay-version` in `gradle/libs.versions.toml` only
  (code = MAJOR*10000 + MINOR*100 + PATCH; never reused).
- Release refuses to build with a non-https or loopback SUPABASE_URL / RSVP_LINK_BASE
  (hardening item 3). On THIS box (local stack), every release build is therefore an
  explicit `-Pelay.allowInsecureRelease=true` build — smoke-test only, never distribute.

## Minified smoke procedure (what the lead actually runs)
`./gradlew :androidApp:bundleRelease -Pelay.allowInsecureRelease=true` (unsigned AAB) and
`:androidApp:assembleRelease -Pelay.allowInsecureRelease=true`, sign the APK with a
THROWAWAY scratch key via apksigner (never the identity keystore), `adb uninstall` the
debug build (signature clash), install, sign in through the UI, verify Today renders with
live data. Restore the debug install afterwards.

## Gate additions (see /build-gate)
`bundleRelease` under pipefail + GATE_GREEN marker; `jarsigner -verify -verbose -certs
androidApp/build/outputs/bundle/release/androidApp-release.aab` must print `jar verified`
(signed builds only); bundletool connected-device install + minified smoke (sign-in → Today
→ one live RPC); record versionCode, AAB sha256, and the jarsigner line in STATE.
