# ADR-004 — Navigation: classic navigation-compose · UI: Material 3 + ELAY tokens

**Status:** accepted (council converged; concierge adopted)

**Decision.** Routing via `org.jetbrains.androidx.navigation:navigation-compose` with typed, serializable route contracts for the six §2 surfaces (Today, Plan, Goals, Inbox, Together, Review) + detail/negotiation sheets. **Not** Navigation 3: its ui artifact is unpublished for non-JVM targets (toolchain sheet line 16) — risk without Phase 0 value. UI on `org.jetbrains.compose.material3:material3` wrapped in an ELAY layer (`ElayTheme`: tokens for color/type/shape/spacing/motion + a small component catalog incl. the signature time-lock card). Material 3 Expressive excluded (Android-only today).

**Consequences.** Route contracts live in `shared/` and are written before UI work (coder-seat contract rule). "Material 3" is a primitives layer, not the product's look — ELAY owns the visible design.

**Revisit trigger.** Nav3 publishes stable non-JVM ui artifacts AND offers something the six-surface graph actually needs.
