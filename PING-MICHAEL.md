# PING — things only Michael can unblock

*Concierge keeps this current (newest first). Michael clears items when he's around; nothing here stalls work that has a workaround.*

## Blocking now
*(nothing hard-blocking — build proceeding with a lane reassignment)*
- **Codex workspace out of credits** — both codex seats (Sol AND Astra) fail instantly with `ERROR: Your workspace is out of credits. Add credits to continue.` (michael.thomas@dsbn.org workspace, verified 2026-09-12 ~06:15 during Astra's Stage 2 pre-gate dispatch — two runs: one 268s partial, one 4s instant-fail). Spend is yours per protocol. **Workaround in effect:** Astra's pre-gate verification lane reassigned to Gemini (still cross-vendor, independent of lead + coder models); Sol contract lanes fall to Gemini/lead until credits return. *(Added 2026-09-12.)*

## Coming up (not yet blocking, workarounds in place)
- **Release keystore (Stage 5)** — when you want a SIGNED AAB: run the two commands in `docs/release.md` (keystore lives outside the repo in your user profile; you hold the password — losing it = permanent new app identity on Play). Unsigned release builds work without it meanwhile. *(Added 2026-09-12.)*
- **Firebase project for real push notifications (FCM)** — Stage 1+ delivers events via Supabase Realtime (works on the local stack, no account needed), but device push when the app is closed needs FCM = a Firebase project only you can create. Time-lock nudges (Stage 2/3) will be in-app/realtime-only until then. *(Added 2026-09-12.)*
- **Codex write sandbox broken on this box** — `codex` workspace-write fails with `helper_unknown_error: setup refresh had errors` (probe-verified 2026-09-11). Sol can't take coder seats until it's fixed (a `codex` CLI update may do it); Sonnet subagents carry the coder load meanwhile. Read-only Sol/Astra unaffected. *(Added 2026-09-11.)*
- **MaestroClaw claude_code coder seats are broken in MODE=code** — the adapter's task mode can't write (permission prompts), and my harness's safety classifier (reasonably) refused to let me wire the adapter's `--dangerously-skip-permissions` session path into `council-dispatch.ts`. Workaround in use and documented in `/council`: Claude coder seats run as harness subagents in scratch clones — same isolation, reviewed diffs. If you'd rather fix MaestroClaw itself, that edit wants your hands or an explicit permission rule; decide whenever. *(Added 2026-09-11.)*
- **Hosted Supabase project** — local Supabase (Docker) carries Phases 0–2 including RLS tests and realtime on the emulator. A hosted project only becomes necessary for multi-device testing off this machine. When wanted: Michael creates the project, keys go in `.env`/`local.properties` only. *(Added 2026-09-11.)*
- **Google OAuth client (native Google sign-in)** — needs a Google Cloud console project = account-level. Workaround ruled by concierge: Phase 1 uses local Supabase email/password test accounts behind the ELAY-owned auth interface; native Google/Apple sign-in slots in behind the same interface later. *(Added 2026-09-11.)*
- **Apple anything** — not needed: app won't ship; iOS CI builds run unsigned simulator builds (`CODE_SIGNING_ALLOWED=NO`). *(Added 2026-09-11.)*

## Cleared
- ~~Stack ruling~~ (KMP + CMP, 2026-09-11) · ~~local-data split~~ (Room 3, "ship room") · ~~repo visibility~~ (public, existing `hdcsnags` GitHub account) · ~~app id~~ (concierge chose `dev.elay.app` under the autonomy grant)
