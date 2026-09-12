# Stage 4 §A — honest-availability contract (Opus lane, 2026-09-12, conf 0.9)

*Adopted verbatim into `contracts/stage4-honest-availability.md` (the freeze records every
binding ruling and the three failure-mode guardrails). The lane's full prose was delivered
in-session; its complete rulings — provider seam shape (AvailabilityProvider/BusySnapshot/
BusyInterval), windowed snapshot sync [now-1d, now+35d], separate external_busy +
availability_sources tables with zero write grants, the four-value certainty ladder with
busy-always-wins, additive rpc_proposal_conflict_hints extension with union-and-coalesce
provenance masking + single new `certainty` key, rpc_self_conflict_hints,
manual-provider-as-UI with server-hard-coded source_tag, no new realtime events, hints
rate-limiting, the deferred Google adapter (Vault token_ref, free-busy scopes, incremental
sync via fn_sync_external_busy, webhooks-never-authorization), the A6/B7/C6/lead slicing,
and the pgTAP 0022 adversarial list — are restated in the freeze, which is the binding
document for seats.*
