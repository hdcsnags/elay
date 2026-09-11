# PING — things only Michael can unblock

*Concierge keeps this current (newest first). Michael clears items when he's around; nothing here stalls work that has a workaround.*

## Blocking now
*(nothing — build proceeding)*

## Coming up (not yet blocking, workarounds in place)
- **Hosted Supabase project** — local Supabase (Docker) carries Phases 0–2 including RLS tests and realtime on the emulator. A hosted project only becomes necessary for multi-device testing off this machine. When wanted: Michael creates the project, keys go in `.env`/`local.properties` only. *(Added 2026-09-11.)*
- **Google OAuth client (native Google sign-in)** — needs a Google Cloud console project = account-level. Workaround ruled by concierge: Phase 1 uses local Supabase email/password test accounts behind the ELAY-owned auth interface; native Google/Apple sign-in slots in behind the same interface later. *(Added 2026-09-11.)*
- **Apple anything** — not needed: app won't ship; iOS CI builds run unsigned simulator builds (`CODE_SIGNING_ALLOWED=NO`). *(Added 2026-09-11.)*

## Cleared
- ~~Stack ruling~~ (KMP + CMP, 2026-09-11) · ~~local-data split~~ (Room 3, "ship room") · ~~repo visibility~~ (public, existing `hdcsnags` GitHub account) · ~~app id~~ (concierge chose `dev.elay.app` under the autonomy grant)
