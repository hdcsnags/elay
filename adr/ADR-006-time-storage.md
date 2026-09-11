# ADR-006 — Time storage: UTC instant + IANA origin zone; wall-clock recurrence

**Status:** accepted (spec §4 is contractual; never revisit toward fixed offsets)

**Decision.** Every shared scheduled thing stores `starts_at_utc` / `ends_at_utc` (canonical instants), `origin_tz` (IANA zone preserving creator intent, e.g. "7 PM Vancouver"), and — for recurrence — the originating wall-clock rule (`recurrence_rule`, RRULE) expanded **in the origin zone**, because DST shifts the UTC offset. Viewer zone is presentation only, never event truth. All-day events use date semantics, not midnight UTC.

**Implementation rules.** All conversions go through `kotlinx-datetime` (+ its IANA zone database) in `shared/`; no `java.util.Date/Calendar` in domain code; no arithmetic like "Toronto = Vancouver + 3" anywhere (§14 rule 5). CI greps for hard-coded offsets. `commonTest` carries a parameterized DST matrix: Vancouver, Toronto, UTC, Asia/Kolkata (non-hour offset) across spring-forward and fall-back dates, midnight crossovers, and a changed-home-zone case (§13 test matrix).

**Recurrence semantics (Astra, 2026-09-11 — conversion tests alone don't define them).** One authoritative expansion implementation lives in `shared/` (single source for client preview and server jobs). Policy, fixed before the schema freezes: DST **gap** (nonexistent local time, e.g. 02:30 spring-forward) → shift forward to the first valid instant, duration preserved; DST **fold** (ambiguous local time) → take the FIRST occurrence (earlier offset); exceptions/cancellations stored as explicit exception dates against the rule; all-day = date-based, no instant math. Fixtures for gap, fold, exceptions, duration-across-transition, and date-only cases ship with the first schema migration.

**Gate hook.** Gate 3 cannot close without the Vancouver↔Toronto DST negotiation tests green and the offset grep clean.
