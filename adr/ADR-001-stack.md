# ADR-001 — Mobile stack: Kotlin Multiplatform + Compose Multiplatform

**Status:** accepted (Michael, 2026-09-11) · **Supersedes** the spec's original §5 (RN + Expo + TypeScript)

**Decision.** ELAY is built as KMP + CMP: `shared/` (pure Kotlin domain — types, time-lock state machine, timezone helpers, Room 3, supabase-kt adapters), `composeApp/` (shared Compose UI, Android entry), `iosApp/` (Xcode shell). Android first; iOS is a compile target from day one, never a fork.

**Context.** Michael reads Kotlin fluently and just shipped a Compose app (Eliana's Rhythm); he rejects RN/Expo aesthetics; Macs exist for the iOS toolchain; the build is agent-driven on Windows, and the human reviewer's fluency in the language is a verification axis.

**Consequences.** Windows cannot compile Apple targets (kotlinlang FAQ, re-verified 2026-09-11) → per-PR iOS-simulator compile on a GitHub Actions macOS runner is mandatory from Phase 0 (ADR-005). Both council seats: "iOS second" must not mean "compile later."

**Revisit trigger.** A native-only capability becomes central and CMP materially limits it.
