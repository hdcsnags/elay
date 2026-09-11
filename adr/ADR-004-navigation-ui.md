# ADR-004 — Navigation: classic navigation-compose · UI: Material 3 + ELAY tokens

**Status:** accepted (council converged; concierge adopted)

**Decision.** Routing via `org.jetbrains.androidx.navigation:navigation-compose` with typed, serializable route contracts for the six §2 surfaces (Today, Plan, Goals, Inbox, Together, Review) + detail/negotiation sheets. **Not** Navigation 3 — rationale corrected 2026-09-11 after Astra's challenge + web verification: Nav3 IS published for iOS/desktop/web since CMP 1.10 (`navigation3-ui` 1.1.1), but non-JVM targets need explicit `SerializersModule`/`SavedStateConfiguration` wiring (no reflection). Classic nav is a **deliberate simplicity choice** for a six-surface tab graph, not a forced one. UI on `org.jetbrains.compose.material3:material3` wrapped in an ELAY layer (`ElayTheme`: tokens for color/type/shape/spacing/motion + a small component catalog incl. the signature time-lock card). Material 3 Expressive excluded — corrected rationale: it IS usable from common code since CMP 1.9, but **experimental/alpha opt-in** (`ExperimentalMaterial3ExpressiveApi`, alpha artifact) — excluded for maturity, not platform. Note Material3's artifact versions independently of CMP; pin what Maven Central actually serves.

**Consequences.** Route contracts live in `shared/` and are written before UI work (coder-seat contract rule). "Material 3" is a primitives layer, not the product's look — ELAY owns the visible design.

**Revisit trigger.** Nav3's serialization ergonomics mature AND it offers something the six-surface graph actually needs; or Expressive graduates from alpha and the design layer wants it.
