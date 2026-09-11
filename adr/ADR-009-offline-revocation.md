# ADR-009 — Offline caches vs immediate revocation

**Status:** accepted (concierge, 2026-09-11, adopting Astra's Gate 0a verification finding)

**The tension.** §11 promises a removed household member loses access *immediately*; §6 requires offline caches of shared data. Server RLS cannot retract plaintext already on a device. Pretending otherwise would make Gate 2's "removed member loses access" claim dishonest.

**Contract.**
1. **Server truth:** revocation is immediate at the API/RLS/realtime layer — the removed member's next request returns nothing shared.
2. **Cache partitioning:** local Room databases are partitioned per signed-in account (separate DB file per user id); logout closes and leaves the other account's data untouchable.
3. **Purge on detection:** the client purges cached shared objects for a household on (a) sign-out, (b) a `member_removed` event for self, (c) any authorized fetch that returns membership-gone. Purge is a tested code path, not best-effort.
4. **Outbox reauthorization:** queued offline writes replay through server validation; writes against revoked membership are rejected server-side and the client discards them with a user-visible note — never silently retried forever.
5. **Bounded shared retention:** offline cache of shared objects is limited to the planning horizon (Today + 7 days + open proposals), so stale plaintext exposure is bounded by design.
6. **Honest gate language:** Gate 2 asserts "server access revoked immediately; cached copies purged on next app contact; retention bounded" — that is what the tests prove.
