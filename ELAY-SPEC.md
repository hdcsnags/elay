<!-- Converted from Elay.docx on 2026-09-10 by Fable 5.1 (stdlib docx→md); the .docx stays the source of record
     for PRODUCT content. TECHNICAL sections §5, §6, §14, §17 are superseded by the AMENDMENTS block below
     (Gate 0a rulings, 2026-09-11) — where the .docx and the amendments disagree, the amendments win. -->

> ## AMENDMENTS — Gate 0a rulings (2026-09-11), supersede §5 / §6 / §14 / §17 below
> Decided by Michael (stack, local data, repo visibility) and the council + concierge under Michael's autonomy grant. Full record with rationale: `council/gate-0a-record/DECISIONS.md`; ADRs in `adr/`.
>
> - **§5 Recommended stack — replaced.** Mobile: **Kotlin Multiplatform + Compose Multiplatform** (not React Native + Expo). Navigation: **classic `org.jetbrains.androidx.navigation:navigation-compose`** (not Expo Router). Backend: **managed Supabase** unchanged, accessed via **supabase-kt** (community SDK, pinned, wrapped behind ELAY-owned interfaces). Local data: **Room 3 (androidx.room3, KSP)** + mutation outbox. Client secrets: platform keystore via ELAY interface (not Expo SecureStore). Push: FCM/APNs direct (not Expo Notifications). CI/CD: **GitHub Actions** incl. macOS runner for per-PR iOS simulator compiles (not EAS). The §5 "Supabase over Firebase" table stands.
> - **§6 Logical architecture — mobile box replaced.** "React Native + Expo / SecureStore" → "Compose Multiplatform (Android · iOS) / shared Kotlin domain + Room 3 cache/outbox / Ktor (supabase-kt)". Trust boundaries, Edge Functions, and offline strategy stand as written.
> - **§14 Suggested repository shape — replaced** by the KMP wizard layout: `composeApp/` (CMP UI), `shared/` (domain: types, state machines, timezone helpers, Room, supabase-kt adapters), `iosApp/` (Xcode shell), `supabase/` (migrations, functions, tests), `docs/adr/` → `adr/`, `tests/e2e` unchanged in intent. The ten non-negotiable engineering rules stand unchanged.
> - **§17 References — Expo rows superseded** (SecureStore, Notifications no longer apply); add: supabase-kt (github.com/supabase-community/supabase-kt, pinned 3.8.x), KMP/CMP docs (kotlinlang.org/docs/multiplatform), Room KMP (developer.android.com/kotlin/multiplatform/room), and `research/toolchain-2026-09.md` + `research/supabase-kt-and-ios-2026-09.md` for dated versions. All other rows stand.
> - **§16 decision register** rows "Mobile: React Native + Expo + TypeScript" and "Offline: deliberate cache + outbox" read accordingly (KMP + CMP; Room 3 outbox). Revisit triggers stand.
> - Note: the app is a Maestro orchestration test and will not ship (Michael, 2026-09-11); store-facing requirements (§9 onboarding, §13 store readiness) remain in the spec as the quality bar, not as delivery goals.

ELAY
# Shared Time & GoalPlanning Platform
Product vision, experience design, technical architecture, security model, data design, and phased build plan
TIME
Plan across time zones without mental arithmetic.
GOALS
Turn raw thoughts into realistic action.
TOGETHER
Coordinate without surrendering personal privacy.
Working product name: ELAY
Audience: product partner + builder AI + future engineering contributors
Roadmap is phase-based by design: progress is capability-driven, not tied to arbitrary week estimates.

North Star
A serious planning product with enough warmth for two people to enjoy together, but enough structure, permissions, security, and extensibility to become a credible Play Store / App Store product.

PRODUCT BRIEF
# 1. What we are building
ELAY is a shared time-management and goal-planning system for individuals, partners, families, study groups, and small trusted circles. Its distinctive job is not merely storing tasks. It translates intentions into negotiable blocks of time while respecting each person’s local time zone, private commitments, attention limits, and right to say no.

The key interaction
One person can propose a shared “time lock” in their own local time. Everyone else sees the exact same instant in their own local time, can accept it, decline it, or counter-propose. No one should ever have to calculate “7 PM there means 10 PM here.”

## Design principles
Principle
Product consequence
Capture before organize
A brain-dump can be one sentence, voice note, or messy list. Structure comes afterward.
Local time is presentation; UTC is truth
Shared events are stored as instants plus IANA time-zone context, then rendered locally.
Collaboration is consensual
Partners can suggest and negotiate; they cannot silently schedule into each other’s calendar.
Private by default
A family workspace grants collaboration, not blanket surveillance. Visibility is explicit per object/integration.
Plans must survive reality
Rescheduling is a first-class action, not a failure state. No shame mechanics or brittle streak dependence.
AI proposes; humans commit
AI can parse and suggest, but deterministic app logic and user confirmation control real changes.
Build for one person first, then two, then many
The individual planner must be useful even before another person joins.

## Primary users
Individual planner: goals, tasks, focus blocks, routines, reminders, and daily review.
Partner pair: shared study sessions, appointments, planning windows, date/family time, and collaborative goal support.
Family / trusted circle: household tasks, shared calendars, availability, responsibilities, and delegated planning.
Future team-lite use: accountability groups, tutors/coaches, or small project circles without becoming a workplace project manager.
EXPERIENCE MODEL
# 2. Low-friction planning without becoming a productivity nag
The experience should reduce planning friction, mental overload, and the effort required to turn intentions into action. Clear priorities, visible time, forgiving capture, and lightweight structure should make the product useful without becoming rigid or overbearing.
Friction
Feature response
Avoid
Too much to hold in working memory
Universal quick capture; inbox; voice capture; widgets; “dump now, sort later.”
Forcing category/project/priority before saving.
Losing track of time
Visible duration estimates, start-in countdowns, travel/buffer fields, and local-time rendering.
A giant calendar with no sense of “now.”
Planning paralysis
AI-assisted decomposition; “smallest next action”; suggested duration ranges.
AI auto-creating 18 tasks without review.
Priority overload
Now / Next / Later, daily capacity budget, max 3 focus outcomes.
Seven competing priority systems.
Context switching
Focus mode showing one block, prerequisites, notes, and a clean done/extend/reschedule action.
Dense dashboards during execution.
Plans change
One-tap move, split, delegate, counter-propose, or “not today.”
Broken streaks or red failure banners.
Task overrun
Soft overrun alerts and next-commitment guardrails.
Aggressive interruptions every few minutes.

A useful tone rule
The app should behave like a calm operations assistant: clear, brief, practical, and non-judgmental. “Move this to tomorrow?” is better than “You missed your goal.”

## Core navigation
Surface
Purpose
Today
Current commitments, top outcomes, shared proposals, and immediate next action.
Plan
Calendar + unscheduled task rail + capacity view.
Goals
Outcome hierarchy: goal -> milestone -> task -> time block.
Inbox
Raw captures waiting to be clarified.
Together
Household/shared goals, availability, proposals, commitments, activity.
Review
Daily/weekly reflection, rollover, completion trends, and planning suggestions.

FEATURE ARCHITECTURE
# 3. Product feature map
Capability
MVP
Growth / Pro
Fast capture
Text dump, task, note, duration guess, due date.
Voice capture, photo/doc extraction, email-to-inbox.
Goals
Goal, milestone, status, target date, linked tasks.
Templates, goal scoring, dependencies, progress forecasting.
Tasks
Priority, effort, duration, due window, recurrence, tags.
Energy/context tags, subtasks, templates, smart batching.
Time blocks
Personal and shared blocks, buffers, recurring rules.
Travel-aware buffers, routines, auto-suggested reshuffle.
Time-lock negotiation
Propose, accept, decline, counter, expire, cancel.
Poll multiple options, quorum rules, group voting.
Time zones
Local display + origin zone + DST-safe recurrence.
Travel mode and temporary home-zone changes.
Households
Invite, member roles, shared goals/tasks.
Multiple circles/workspaces and delegated household admin.
Privacy
Private / free-busy / title-only / full-detail visibility.
Per-calendar and per-category sharing rules.
Calendar
Google read/import + dedicated ELAY calendar write.
Microsoft 365, Apple device calendar, CalDAV where viable.
Notifications
Proposal, start-soon, overdue choice, review prompt.
Adaptive quiet hours, notification bundles, escalation rules.
AI copilot
Parse dump, suggest structure/duration/time candidates.
Weekly planning, conflict explanations, conversational planning.
Insights
Completion, plan-vs-actual, rollover counts.
Capacity trends, interruption patterns, schedule realism score.

## Features that give the product “teeth”
Capacity planning: the day has a finite budget; tasks can be unscheduled because there is genuinely no room.
Negotiated commitments: shared blocks have state, ownership, responses, expiry, and history rather than acting like casual messages.
Availability without surveillance: “busy 6-7 PM” can be shareable even when “therapy / doctor / interview” remains private.
Conflict detection: warn when a proposal collides with an event, quiet hours, sleep window, travel buffer, or another accepted block.
Plan-vs-actual feedback: users can mark “finished early,” “ran long,” “interrupted,” or “rescheduled,” improving future duration suggestions.
Data portability: export goals/tasks/calendar blocks, delete account/workspace data, and avoid locking users into a proprietary black box.
TIME & COLLABORATION
# 4. Time-zone and “time lock” design
Time handling is foundational. Never store “7:00 PM” as the shared truth. Store the intended instant in UTC, preserve the creator’s IANA zone, and render the instant in each viewer’s current or preferred zone. Recurrence needs the originating wall-clock rule because daylight-saving transitions can change the UTC offset.
Field
Example
Reason
starts_at_utc
2026-09-11T02:00:00Z
Canonical instant for comparison, conflicts, notifications.
ends_at_utc
2026-09-11T03:00:00Z
Canonical end instant.
origin_tz
America/Vancouver
Preserves “7 PM Vancouver” intent.
viewer_tz
America/Toronto
User preference/device context; not copied into event truth.
recurrence_rule
FREQ=WEEKLY;BYDAY=TH
Defines recurring intent; expansion must use origin zone.
all_day
false
All-day events require date-based semantics, not midnight UTC assumptions.

Concrete example
A Vancouver user proposes a one-hour study block at 7:00 PM local time. The Toronto user sees 10:00 PM local time for the same instant. The UI may show both: “10:00-11:00 PM for you · 7:00-8:00 PM for her.” The three-hour difference is calculated from zone rules, never hard-coded.

## Time-lock state machine
State
Who can cause it
Meaning
draft
creator
Private, incomplete proposal.
proposed
creator
Sent to one or more members.
accepted
invitee(s)
Required participants accepted.
countered
invitee
Alternative start/end proposed without mutating original history.
declined
invitee
Participant refused the proposal.
expired
system
Response deadline passed.
cancelled
creator/admin policy
Commitment withdrawn; notify affected users.
completed
participants/system
Block happened; optional actual-time feedback.

## Negotiation rules
Accepting a proposal creates a commitment record. Calendar write-back happens only if that user enabled it.
Counter-proposals create a new candidate revision; do not overwrite the original start/end invisibly.
The creator cannot view a private conflicting event unless the other user shared its details. The app may say “conflict” or “busy” only.
For group locks, define required vs optional attendees and a clear acceptance threshold rather than inventing ambiguous “maybe accepted” states.
TECHNICAL ARCHITECTURE
# 5. Recommended stack
Layer
Recommendation
Why
Mobile
React Native + Expo + TypeScript
One codebase for iOS/Android; mature notification/auth ecosystem; AI builders generally handle TS well.
Navigation
Expo Router
File-based routing and deep-link-friendly app structure.
Backend
Managed Supabase
Postgres relational model, Auth, RLS, Realtime, Storage, Edge Functions.
Database
PostgreSQL
Strong constraints, transactions, joins, indexing, JSONB where appropriate.
Server API
Supabase Edge Functions
OAuth callbacks, calendar sync, AI gateway, scheduled jobs, webhooks.
Auth
Supabase Auth
Google + Apple; email magic-link optional later.
Client secrets
Expo SecureStore
Small sensitive local values/tokens, not app data.
Push
Expo Notifications initially
Cross-platform token + notification plumbing; move direct APNs/FCM if scale/control demands it.
CI/CD
GitHub Actions + EAS Build/Submit
Automated lint/test/build; signed mobile builds; store deployment pipeline.
Observability
Sentry or equivalent + structured backend logs
Crash, performance, release, and sync diagnostics without logging sensitive content.

## Why Supabase over Firebase for ELAY
Decision area
Supabase
Firebase / Firestore
Verdict
Relational sharing model
Native SQL relations, constraints, joins, RLS.
Document/collection model; relationships often denormalized.
Supabase
Fine-grained authorization
Database-enforced RLS + grants.
Security Rules are capable but separate from relational database constraints.
Supabase
Realtime collaboration
Realtime database changes/broadcast/presence.
Excellent realtime listeners.
Tie
Offline mobile
Requires deliberate local cache/sync strategy.
Firestore offline persistence is a major built-in strength.
Firebase
Analytics / SQL
Postgres ecosystem and direct SQL.
Different query model; BigQuery often added for analysis.
Supabase
Self-host path
Core platform is open-source; self-host possible.
Primarily managed Google platform.
Supabase
Operational simplicity
Managed Supabase is straightforward.
Firebase is also very strong here.
Tie

Recommendation
Start on managed Supabase. Do not self-host for the first release. Self-hosting converts product work into database, auth, realtime, backup, patching, observability, and incident-response work. Keep a migration-friendly architecture and revisit self-hosting only when cost, compliance, or strategic control actually requires it.

SYSTEM DESIGN
# 6. Logical architecture and data flows
iOS / Android
React Native + ExpoSecureStorelocal cache/outbox
Push / deep links
Auth providers
Supabase AuthJWT sessionPKCE / OAuth
Google / Apple
Application data
Postgres + RLSGoals · Tasks · BlocksHouseholds · Proposals
Supabase Realtime
Trusted server
Edge FunctionsAI gatewayCalendar syncnotification jobs
Secrets / rate limits
External services
Google CalendarAI provideremail/push
Future M365 / other

## Trust boundaries
1.  The mobile app is an untrusted client. It may hold a publishable backend key, but authorization must never depend on hiding that key.
2.  Every direct database operation is constrained by grants + RLS; privileged operations use authenticated Edge Functions.
3.  Google refresh tokens and AI-provider secrets live server-side, encrypted/protected by the platform’s secret-management pattern.
4.  Realtime events are filtered by authorization. Presence/broadcast channels must not become a backdoor for data that RLS would deny.
5.  Notification payloads should avoid sensitive task/event descriptions by default; deep-link into authenticated UI for details.
## Offline strategy
Because Firebase’s automatic offline behavior is one of its strongest advantages, ELAY should explicitly design for intermittent connectivity rather than pretending it does not matter. Use a small local database/cache for today’s plan, captures, pending mutations, and recently used goals. Mutations enter an outbox with client-generated UUIDs and idempotency keys; the server acknowledges them and returns authoritative timestamps/versions. Resolve straightforward field conflicts automatically and surface human choices for competing schedule edits.
DATA MODEL
# 7. Core database schema
Table
Purpose
Critical fields / notes
profiles
User preferences and display identity.
user_id PK/FK, display_name, home_tz, locale, week_start, quiet_hours.
households
Shared collaboration space.
id, name, owner_id, plan_tier, created_at.
household_members
Many-to-many membership + role.
household_id, user_id, role, status, joined_at; unique pair.
goals
Personal or shared outcomes.
owner_id, household_id nullable, visibility, title, target_date, status.
milestones
Goal checkpoints.
goal_id, title, target_date, order, status.
tasks
Actionable work.
owner_id, goal_id, household_id, visibility, due_at/window, estimate_min, priority, recurrence.
captures
Raw unsorted input.
owner_id, body, source, captured_at, ai_parse_status.
time_blocks
Canonical scheduled work.
owner_id, task_id nullable, starts_at_utc, ends_at_utc, origin_tz, type, status.
time_lock_proposals
Negotiated shared block.
creator_id, household_id, candidate start/end, origin_tz, expiry, revision_of.
proposal_participants
Responses per member.
proposal_id, user_id, required, response, responded_at.
availability_rules
Shareable working/sleep/quiet windows.
user_id, household_id, weekday/rule, zone, visibility.
calendar_connections
External integration metadata.
user_id, provider, external_account_id, scopes, status; no raw token in client.
calendar_event_cache
Normalized conflict/free-busy cache.
connection_id, external_event_id hash/id, times, privacy-safe summary.
devices
Push/device registration.
user_id, platform, push_token, app_version, last_seen.
ai_requests
Auditable AI job metadata.
user_id, purpose, model, token/cost metadata, redaction flags; avoid raw prompts by default.
audit_events
Security/product history.
actor_id, household_id, action, entity, entity_id, metadata, timestamp.

Schema rule
If a record can be shared, it needs an explicit owner/workspace relationship and a visibility model. Do not infer sharing merely because two people belong to the same household.

## Visibility enum
Mode
What another authorized member can see
private
Nothing; conflict engine may still operate server-side if user opted into availability checks.
busy_only
Time range only; no title, notes, goal, location, or source calendar.
title_only
Time + sanitized title; no private notes/details.
full
All fields allowed for that shared object.

SECURITY MODEL
# 8. Authorization, security, and privacy
Security must be enforced on the backend even when the UI already hides actions. The mobile client is inspectable and modifiable; a user should gain no additional access by bypassing screens and calling the API directly.
Resource
Read
Create / update
Delete
profile
Self; limited fields exposed to accepted collaborators.
Self only.
Account deletion workflow.
household
Accepted member.
Owner/admin for metadata; invite flow for membership.
Owner with safeguards; cascade/archive policy.
goal/task
Owner, or member if visibility permits.
Owner; shared assignee only for explicitly delegated fields.
Owner/admin policy; audit shared deletions.
time block
Owner; shared participants according to visibility.
Owner, or server function converting an accepted proposal.
Owner; notify affected participants if shared.
proposal
Participants + creator.
Creator can propose; participants can only mutate their own response/counter flow.
Creator cancel; preserve history/audit.
calendar connection
Self only.
Self via OAuth flow / server callback.
Self disconnect; revoke/erase token material.
AI job
Self; workspace summary only if intentionally shared.
Self via rate-limited Edge Function.
Retention-based purge / self delete.

## Mandatory controls before public beta
RLS enabled on every exposed table, with explicit grants and allow/deny tests for anonymous, authenticated owner, household peer, former member, and attacker-shaped cases.
No service/secret key in the mobile bundle. Publishable keys are acceptable only with correctly enforced authorization; privileged keys remain server-side.
OAuth with PKCE where applicable; validate redirect URIs/deep links; rotate credentials; keep Google/Apple configuration separated by environment.
Secure local token storage using platform-backed storage; do not place tokens in AsyncStorage, logs, crash reports, or notification payloads.
Rate-limit AI, invite, calendar-sync, passwordless-login, and notification endpoints. Add abuse limits per user/device/IP as appropriate.
Validate all server inputs against schemas; use DB constraints for duration bounds, status enums, membership uniqueness, and time ordering.
Encrypt in transit; use provider encryption at rest; minimize calendar content copied into ELAY. Cache free/busy instead of full descriptions when full details are unnecessary.
Account export and deletion; workspace leave/remove semantics; calendar disconnect and token revocation; clear retention policy.
Dependency scanning, secrets scanning, protected main branch, mandatory CI tests, environment isolation, and production change audit.

Mobile security baseline
Use OWASP MASVS as the release checklist across secure storage, authentication, network communication, platform interactions, code/dependency hygiene, resilience, and privacy.

INTEGRATIONS
# 9. Authentication and calendar integration
Do not tie “family membership” to a shared Google account. Each person signs in as themselves, joins a household through an invite, and optionally connects one or more calendars. This keeps identity, collaboration, and third-party authorization separate and reversible.
## Recommended onboarding sequence
1.  Open app and see a lightweight product preview; do not demand every permission immediately.
2.  Create account with Apple or Google. On iOS, offering an equivalent privacy-preserving login is important when third-party social login is used for the primary account.
3.  Set preferred time zone (pre-fill from device, but let user confirm/change it).
4.  Create a personal first task / goal so the app is useful alone.
5.  Optionally create or join a household using an expiring invite.
6.  Only when the user enables calendar features, request Google Calendar authorization with the minimum scopes for the chosen behavior.
7.  Ask separately for notifications when there is a clear reason: “Remind me about accepted time locks.”
## Calendar integration levels
Level
Data access
Use
0 · none
No external calendar access.
ELAY still works as a standalone planner.
1 · conflict only
Read enough calendar data to detect availability; cache minimal normalized fields.
Warn “You are already busy” without importing content.
2 · imported view
User-selected calendars shown in planner.
Unified day/week view.
3 · write-back
Create/update events only for ELAY-managed commitments or explicitly selected target calendar.
Accepted locks appear in Google Calendar.

Scope minimization
Request only the Google Calendar scopes needed by enabled features. Avoid broad “manage all calendars and sharing” permissions when ELAY only needs event read/write. Separate sign-in scopes from calendar scopes so consent remains understandable.

## Token handling
Google refresh/access tokens belong in trusted backend storage, never in a shared household table or plaintext client storage.
Request offline access only when background synchronization truly requires it.
Disconnect means: stop sync, revoke where supported, delete stored token material, and remove cached data according to retention policy.
Calendar webhooks/push channels must validate provider identifiers and route through authenticated server logic; never trust a calendar event payload as authorization.
AI COPILOT
# 10. AI that is cheap, useful, and safe
The AI layer should be provider-agnostic. The first implementation may use a low-cost model, but the app calls an internal “planning gateway,” not a model vendor directly. That lets you switch providers, enforce cost ceilings, redact fields, validate output, and keep mobile releases independent of model changes.
AI action
Input
Structured output
Human gate
Brain-dump parser
Raw capture + optional goal context.
Candidate tasks, notes, dates, durations, tags.
Review before saving structured items.
Task splitter
Task + goal + user-requested granularity.
Ordered subtasks with estimates.
Accept/edit selected subtasks.
Schedule suggestions
Unscheduled tasks + free windows + constraints.
Ranked candidate blocks with rationale.
Tap to schedule; never silently commit.
Conflict explainer
Proposal + privacy-safe availability facts.
“10 PM is free but late; 9 PM conflicts.”
User chooses response/counter.
Weekly review
Completion + rollover + estimate accuracy.
Patterns and 3-5 actionable suggestions.
Suggestions only.

## AI gateway contract
Authenticated Edge Function accepts a narrow purpose enum and validated payload.
Server removes fields the selected AI task does not need, applies retention policy, and adds only permission-safe context.
Provider key is read from server secrets. Never ship it in Expo config, source maps, GitHub, or app bundles.
Model must return JSON conforming to a versioned schema. Invalid output is rejected or retried; prose never directly mutates the database.
Enforce max input length, output size, model allow-list, per-user quotas, timeout, and spend budget.
Log metadata such as purpose, latency, token/cost estimate, model, and success; avoid storing raw private prompts unless explicitly needed and disclosed.
Later: per-user AI opt-out and “private processing” mode that disables external model calls for selected captures/categories.

Rule of thumb
AI is allowed to be creative in suggestions, but never authoritative about permissions, membership, billing, time-zone conversion, calendar conflicts, or whether an action was actually committed. Those remain deterministic application logic.

PHASED BUILD PLAN
# 11. Capability-driven roadmap
Each phase has a usable outcome and acceptance criteria. Nothing here means “finish this in one week.” Move forward when the capability is stable enough to support the next layer.
PHASE 0
Product contract & repository foundation
Agree on the rules before the AI builder writes hundreds of files.

Define product vocabulary: goal, task, capture, time block, time lock, household, availability, visibility.
Write threat model and privacy rules for private vs shared data.
Create monorepo/repo conventions, environments (local/dev/prod), branching, linting, formatting, tests, secrets policy.
Create Supabase project, local CLI workflow, migration folder, seed data, and RLS test harness.
Decide app IDs/bundle IDs and working brand assets; create architecture decision records (ADRs).
Exit gate: a builder AI can explain the domain model, data ownership rules, timezone rule, and “AI proposes/human commits” rule without contradiction.
PHASE 1
Personal planner skeleton
Make ELAY valuable for one person with no shared account at all.

Expo app shell, routing, theming, accessibility defaults, account/session handling.
Google + Apple authentication; profile and timezone settings.
CRUD for goals, milestones, tasks, captures, and personal time blocks.
Today view, Inbox, Goal detail, Task detail, simple Plan calendar.
Optimistic UI with explicit error recovery; initial local cache for Today + Inbox.
RLS tests proving User A cannot read/write User B data.
Exit gate: two test accounts are completely isolated, both platforms run, and a user can capture -> clarify -> schedule -> complete a task.
PHASE 2
Households & sharing
Add collaboration while preserving private-by-default semantics.

Create household, expiring invite, accept/decline, roles, leave/remove flows.
Visibility model on shared goals/tasks/blocks.
Together view with only authorized objects.
Audit events for membership and important shared actions.
Former-member tests: access disappears immediately after removal without breaking their private data.
Exit gate: two users can share selected objects but cannot infer or retrieve private objects through UI, API, realtime, or guessed IDs.
PHASE 3
Time-lock negotiation
Build the signature collaboration workflow.

Proposal composer using creator-local time and recipient-local preview.
Accept / decline / counter-propose / cancel / expiry / completion state machine.
Conflict checks against ELAY blocks and availability rules.
Push/in-app notifications and deep links to a proposal.
Revision history so a counter does not destroy the original proposal.
Exit gate: Vancouver/Toronto test accounts can negotiate an hour block correctly across DST test dates and no hard-coded offsets exist.
PHASED BUILD PLAN
# 12. Calendar, AI, focus, and hardening
PHASE 4
Calendar integration & availability
Make external commitments visible without turning the product into a surveillance tool.

Google Calendar OAuth as an optional integration separate from login.
Calendar selection and privacy level; normalize selected events into minimal conflict cache.
Planner overlay; accepted ELAY time lock can write to a dedicated ELAY calendar or user-selected target.
Incremental sync/webhook strategy; disconnect/revoke/delete flow.
All-day, recurring, cancelled, changed-zone, and daylight-saving test fixtures.
Exit gate: external conflicts render correctly in local time and family users never receive unauthorized event details.
PHASE 5
Planning copilot
Turn messy input into useful candidate structure.

Edge Function AI gateway with model abstraction, secrets, quotas, schema validation, and observability.
Brain-dump parser and task decomposition first; scheduling suggestions second.
Preview/diff screen showing what AI wants to create or change.
Cost telemetry and a kill switch per AI feature/provider.
Prompt-injection-safe design: retrieved user text is data, never trusted instructions for server permissions/actions.
Exit gate: AI can fail, hallucinate, time out, or be disabled without corrupting planner data or blocking core app use.
PHASE 6
Execution experience
Help the user actually do the plan.

Focus mode with one current block, countdown/elapsed state, notes, complete/extend/split/reschedule.
Now / Next / Later and daily capacity budget.
Quiet hours, notification bundles, “starting soon,” and soft overrun warnings.
Actual duration and interruption feedback to improve future estimates.
Daily reset/review flow that makes unfinished work a planning decision, not a failure badge.
Exit gate: a user can get through a full real-life day without needing another task app or manually repairing the calendar.
PHASE 7
Security & privacy hardening
Treat public beta as an adversarial environment.

Full RLS/grant negative-test suite; Edge Function authz tests; rate-limit tests.
OWASP MASVS-oriented review, secrets/dependency scanning, mobile storage inspection, deep-link validation.
Logging/privacy audit; sensitive values removed from telemetry and push payloads.
Account export/delete, household deletion, integration revocation, retention jobs.
Backup/restore exercise and production incident runbook.
Exit gate: independent test account attempts, manipulated requests, removed-member scenarios, and token leakage checks pass before wider beta.
PHASED BUILD PLAN
# 13. Resilience, release, and growth
PHASE 8
Offline & sync resilience
Make captures and today-planning reliable on bad networks.

Local cache/database for recent planner state and outbox for mutations.
Idempotency keys; retries with backoff; server versioning/updated_at checks.
Conflict UI for concurrent edits to time blocks/proposals; deterministic merge for safe field edits.
Airplane-mode test script: capture, edit, complete, reconnect, verify no duplicates/loss.
Exit gate: the app can survive network loss without losing a capture or silently overwriting a shared scheduling decision.
PHASE 9
Private beta -> store readiness
Prove the product on real devices and prepare for App Store / Play distribution.

TestFlight and Play internal/closed testing; crash/performance monitoring; feedback channel.
Accessibility pass: dynamic type, screen readers, contrast, hit targets, reduced motion, keyboard where relevant.
Privacy policy, terms, support contact, data-retention statement, Google Play Data safety declaration, Apple privacy disclosures.
Sign-in, account deletion, subscription behavior, permissions, and screenshots reviewed against store policies.
Release checklist with migration backup, rollback plan, feature flags, and staged rollout.
Exit gate: store artifacts, legal/privacy disclosures, support path, monitoring, and rollback procedures exist before public release.
PHASE 10
Productization & sustainable monetization
Expand only after the core loop proves useful.

Free: personal planning, basic goals/tasks/time blocks, one household, limited AI quota.
Family/Plus: larger circles, advanced shared planning, more calendar connections, greater history/insights, higher AI quota.
Potential Pro: advanced planning analytics, templates, multiple circles, integrations, export/backup options.
Do not paywall basic privacy, account deletion, security controls, or essential timezone correctness.
Exit gate: pricing maps to real incremental value and backend cost; no feature depends on invasive data collection or advertising surveillance.
## Minimum test matrix
Dimension
Must include
Devices
Recent iPhone + older supported iPhone; recent Android + at least one mid-range Android.
Time zones
Toronto, Vancouver, UTC, non-hour offset zone (e.g., India), and DST/no-DST combinations.
Time cases
DST spring-forward/fall-back, midnight crossover, all-day, recurrence, changed home zone.
Accounts
Owner, member, removed member, invitee, two households, expired invite.
Network
Fast, slow, offline, reconnect, duplicate retry, server timeout.
Security
Direct REST attempts, guessed UUIDs, realtime subscription, tampered role/household IDs, stolen/expired token.
AI
Malformed JSON, hallucinated date, huge input, timeout, provider outage, quota exceeded, malicious embedded instructions.

BUILDER AI HANDOFF
# 14. Implementation contract for an AI coding agent

Instruction to builder
Treat this blueprint as the product contract. When a requested code change conflicts with the security, ownership, timezone, or human-confirmation rules here, stop and surface the conflict rather than quietly changing the model.

## Non-negotiable engineering rules
1.  All schema changes are migrations. Never modify production schema manually without a migration equivalent.
2.  RLS + grants ship with the table/function they protect, plus negative tests.
3.  Never authorize from client-supplied owner_id, household_id, role, or email without deriving/validating membership server-side.
4.  Never expose backend secret/service keys, AI keys, OAuth client secrets, or provider refresh tokens to the mobile bundle.
5.  Shared scheduled instants use UTC plus IANA timezone context; no fixed offsets such as “Vancouver = Toronto - 3”.
6.  Recurrence is evaluated with timezone-aware libraries and test fixtures across DST boundaries.
7.  AI output is untrusted structured input and cannot directly perform privileged or destructive writes.
8.  Every shared action has a clear actor, state transition, permission rule, and audit behavior.
9.  Feature flags guard risky integrations and AI behavior; core planner continues when integrations are down.
10.  No sensitive content in analytics, crash breadcrumbs, logs, or push notification bodies unless explicitly designed and disclosed.
## Suggested repository shape
Path
Responsibility
apps/mobile
Expo React Native application.
packages/domain
Shared TypeScript types, schemas, state machines, timezone helpers.
packages/ui
Reusable design-system components.
supabase/migrations
Database schema, grants, RLS, functions, indexes.
supabase/functions
AI gateway, calendar OAuth/sync, notification jobs/webhooks.
supabase/tests
RLS and database contract tests.
tests/e2e
Cross-account and critical-flow end-to-end tests.
docs/adr
Architecture decisions and tradeoffs.
docs/threat-model
Assets, trust boundaries, abuse cases, mitigations.

## Definition of done for each feature
Happy path implemented on iOS and Android.
Loading, empty, validation, permission-denied, offline, and server-error states designed.
Authorization rule documented and tested.
Timezone/DST behavior tested if any time field is involved.
Analytics/logging emits metadata only and does not leak private content.
Accessibility labels/hit targets/dynamic text considered.
Migration rollback or forward-fix plan understood.
Builder updates relevant ADR/schema/API documentation.
SCOPE CONTROL
# 15. What belongs in V1 — and what should wait
Ship for V1
Delay until core loop proves itself
Personal tasks/goals/capture
Full project-management boards / enterprise roles
Time blocks + timezone-safe shared proposals
Chat/messaging platform
Household with privacy/visibility controls
Social feed / public profiles
Google Calendar integration
Every calendar provider on day one
Push + in-app notifications
Complex notification automation builder
AI dump parser + schedule suggestions
Autonomous AI rescheduling
Basic offline capture/outbox
Full local-first CRDT architecture unless usage demands it
Plan-vs-actual duration feedback
Wearables, location automation, health data
Export/delete/privacy controls
Ads / data-broker style monetization

## V1 success metrics
Metric
Why it matters
Capture-to-clarified rate
Does raw input become actionable instead of dying in an inbox?
Scheduled-task completion / intentional reschedule
Measures planning usefulness without punishing changed priorities.
Proposal response time and acceptance/counter rate
Does the signature collaboration flow actually reduce coordination friction?
Estimate accuracy over time
Is the planner helping users understand realistic durations?
Weekly retained planners
Are users returning to plan, not merely checking reminders?
Calendar disconnect / permission denial reasons
Find trust or scope problems early.
AI suggestion acceptance/edit/rejection
Measures usefulness without pretending model output is correct.

Product litmus test
If ELAY disappeared tomorrow, the user should miss the way it turns “we should study sometime” and “I have too much in my head” into specific, realistic, mutually agreed actions. That is more defensible than merely having another calendar or another to-do list.

DECISION REGISTER
# 16. Initial architecture decisions
Decision
Choice now
Revisit trigger
Backend
Managed Supabase
Compliance, scale, cost, or strategic need justifies self-hosting.
Mobile
React Native + Expo + TypeScript
A native-only capability becomes central and Expo path is materially limiting.
Identity
Supabase Auth: Google + Apple
Enterprise/education SSO becomes a target segment.
Calendar V1
Google Calendar
Microsoft demand or Apple-calendar-only users become material.
Shared model
Household/workspace + explicit visibility
New circle types require different governance.
Time truth
UTC instant + IANA origin zone
Never replace with fixed offsets.
AI
Server-side provider-agnostic gateway
Keep abstraction even if provider changes.
Offline
Deliberate cache + mutation outbox
High collaboration concurrency may justify CRDT/local-first redesign.
Security baseline
RLS/grants + MASVS-informed mobile review
Increase rigor with threat/usage profile, not decrease.

## Open product questions to answer during Phase 0
Does “household” need only owner/member roles, or owner/admin/member from day one?
Can a shared task be edited by both people, or does it have one owner with delegated completion rights?
Do counter-proposals support exactly one alternate time or several candidates?
What does “busy-only” expose when a conflict is generated from a private ELAY task vs an external calendar event?
Should accepted shared locks be independent commitments per participant so one person can remove calendar write-back without cancelling the group event?
How much historical activity should a household retain on the free tier?
Which AI requests may include private calendar titles, if any? Default answer should be none unless the user explicitly enables it.
REFERENCE NOTES
# 17. Current technical references (verified September 2026)
These references support the architectural choices in this blueprint. Product behavior should still be rechecked against current platform documentation before store submission because OAuth, SDK, and store-policy requirements evolve.
Reference
URL / use
Supabase Row Level Security
https://supabase.com/docs/guides/database/postgres/row-level-securityDatabase grants + row-level policies; test allow and deny cases.
Supabase Realtime
https://supabase.com/docs/guides/realtimeBroadcast, Presence, and Postgres Changes for collaborative experiences.
Supabase Edge Functions
https://supabase.com/docs/guides/functionsServer-side TypeScript functions, secrets, auth, webhooks, AI orchestration.
Supabase Function Secrets
https://supabase.com/docs/guides/functions/secretsKeep secret keys/server credentials out of clients and source control.
Supabase Sign in with Google
https://supabase.com/docs/guides/auth/social-login/auth-googleGoogle OAuth for web/native, provider tokens, and configuration.
Supabase Sign in with Apple
https://supabase.com/docs/guides/auth/social-login/auth-appleApple sign-in support for native/web flows.
Google Calendar OAuth Scopes
https://developers.google.com/workspace/calendar/api/authChoose the minimum Calendar API scope needed for the enabled feature.
Firestore Offline Data
https://firebase.google.com/docs/firestore/manage-data/enable-offlineConfirms Firestore’s strong built-in mobile offline persistence; key tradeoff in backend comparison.
Expo SecureStore
https://docs.expo.dev/versions/v55.0.0/sdk/securestore/Platform-backed secure storage for small sensitive values on iOS/Android.
Expo Notifications
https://docs.expo.dev/versions/latest/sdk/notifications/Cross-platform notification token/scheduling/response APIs.
OWASP MASVS
https://mas.owasp.org/MASVS/Mobile application security baseline covering storage, auth, network, platform, code, resilience, privacy.
Apple App Review Guidelines 4.8
https://developer.apple.com/app-store/review/guidelines/Login-service requirements relevant when third-party/social login is used for a primary account.
Google Play Data Safety
https://support.google.com/googleplay/android-developer/answer/10787469Store disclosure requirements for collected/shared user data and protection practices.

## One-sentence build direction

Build this first
A cross-platform Expo app backed by managed Supabase where individuals can capture and schedule work, join an explicitly permissioned household, negotiate timezone-safe shared time locks, optionally connect Google Calendar, and use a server-side AI copilot to propose structure without ever bypassing human confirmation or backend authorization.
