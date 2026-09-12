// supabase/functions/rsvp/index.ts
//
// Stage 3 web RSVP — no-install browser response page.
// Contract: contracts/stage3-web-rsvp.md (FROZEN) + council/stage3-web-rsvp-security-opus.md (§A)
//           + council/stage3-web-rsvp-gemini.md (§B, design source of truth).
//
// Seat E1 grant: supabase/functions/rsvp/** ONLY. Do not touch SQL, migrations, or other seats' paths.
//
// Routes (per §A section 2):
//   GET  /rsvp/:token          -> server-rendered §B page from rpc_get_rsvp_render_data(p_token)
//   POST /rsvp/:token/respond  -> calls rpc_respond_proposal_web(...), renders §B success/edge page
//
// Hard rules honored here:
//   - The token path segment is the ONLY identifier. Any id-like fields in the POST body are
//     ignored; they are never forwarded to the RPC.
//   - Every response carries Cache-Control: no-store, Referrer-Policy: no-referrer,
//     X-Robots-Tag: noindex.
//   - The token is never logged. If we log at all (error paths only) we log
//     left(sha256hex(token), 8).
//   - No client-supplied recipient/proposal ids are ever sent to Postgres — only the token and
//     the action payload (§A section 2, guardrail #2).
//   - Zero npm/external imports. Raw fetch to PostgREST under the service-role key. No HMAC/crypto
//     is performed here — the token is opaque to this function; Postgres verifies MAC + row.
//
// ============================================================================================
// ASSUMPTIONS FLAGGED FOR THE LEAD'S MERGE DIFF (A4's SQL is being built in parallel from the
// same §A contract; these are the shapes this function codes against beyond what §A pins down
// verbatim as RPC names/params). Grep "ASSUMPTION" to find every spot that depends on these.
//
// 1. rpc_get_rsvp_render_data(p_token text) returns jsonb, one of:
//      {"outcome":"ready", "title": text, "response_deadline": timestamptz-iso,
//       "recipient_display_name": text, "recipient_home_tz": text,
//       "proposer_display_name": text, "proposer_origin_tz": text,
//       "candidates": [{"candidate_idx":int,"starts_at_utc":iso,"ends_at_utc":iso,"duration_min":int}, ...]}
//      {"outcome":"invalid_or_unavailable"}                                  -- token-layer failure
//      {"outcome":"conflict","status":text,"current_revision":int}          -- same envelope as
//        rpc_respond_proposal's non-mutating conflict path, reused here so the derived-revocation
//        cases (expired / countered-since-mint / terminal) can render §B's exact copy rows via the
//        same status->page mapping used for POST. This is the one genuine design assumption: §A
//        pins the *token verification* rules but not the render-data RPC's JSON shape. If A4 named
//        fields differently (e.g. camelCase, or nested under "recipient"/"proposer" objects), this
//        file needs a one-line adapter at `mapRenderData()` below.
// 2. rpc_respond_proposal_web(p_token, p_action, p_candidate_idx, p_new_origin_tz, p_new_deadline,
//    p_new_candidates) returns jsonb mirroring rpc_respond_proposal's own return shape exactly:
//      {"outcome":"applied","action":"accept_proposal"|"decline_proposal"|"counter_proposal",
//       "proposal": {...same fields as time_lock_proposal_to_jsonb...}, "commitment_id"?, "time_block_id"?, "revision"?}
//      {"outcome":"conflict","status":text,"current_revision":int}   -- no "action" key (§A section 2)
//      {"outcome":"invalid_or_unavailable"}                          -- pre-verification failure
// 3. Both RPCs are granted to `service_role` only (revoked from anon/authenticated), matching
//    "service-role-only" language in §A section 2. This function authenticates every call with
//    SUPABASE_SERVICE_ROLE_KEY.
// 4. §A's rate limiting (10/15min per token_hash, 60/15min per IP) is entirely a SQL-side concern.
//    The RPC signatures the lead handed down (`rpc_get_rsvp_render_data(p_token)`,
//    `rpc_respond_proposal_web(p_token, p_action, p_candidate_idx, p_new_origin_tz, p_new_deadline,
//    p_new_candidates)`) do not accept a client-IP parameter, so this function does not send one.
//    If per-IP limiting needs the caller's IP, that requires a signature amendment — flagging for
//    the lead rather than unilaterally adding an extra positional/named param.
// 5. "Open ELAY" / "Open in ELAY" primary actions all deep-link to the generic `elay://plan`
//    rather than a per-proposal deep link (`elay://together/proposals/{id}`), because the
//    render-data disclosure set in §A section 1 ("discloses": title, times, names) does not
//    include proposal_id, and this function never receives one. If A4/lead want the specific deep
//    link, the render-data RPC would need to add a disclosed proposal id.
// 6. GET /rsvp/:token/ics (lead amendment #2, stretch) is NOT implemented. It is explicitly scoped
//    as "stretch, not scope, ONLY if the core page + tests are done" — given deno was unavailable
//    on PATH for verification (see seat report), core correctness was prioritized over the stretch
//    goal. No route exists for it; requests to that path fall through to the generic 404 page.
// 7. "Network Failure" / "Confirming…" (§B's in-flight state) are treated as pure client-side,
//    JS-only ephemeral UI (disable-on-submit text swap), not server-rendered pages — the no-JS
//    fallback is a full-page form POST with no AJAX layer, so there is no fetch() call on this
//    page that could itself fail client-side. A real infra failure talking to PostgREST (network
//    error, non-2xx, malformed JSON) DOES render a server-side page using §B's "Unable to
//    connect" / "Try again" copy, at HTTP 500.
// ============================================================================================

const SUPABASE_URL = Deno.env.get("SUPABASE_URL") ?? "";
const SERVICE_ROLE_KEY = Deno.env.get("SUPABASE_SERVICE_ROLE_KEY") ?? "";

// ---------------------------------------------------------------------------
// Small utilities
// ---------------------------------------------------------------------------

function esc(v: unknown): string {
  const s = v === null || v === undefined ? "" : String(v);
  return s
    .replace(/&/g, "&amp;")
    .replace(/</g, "&lt;")
    .replace(/>/g, "&gt;")
    .replace(/"/g, "&quot;")
    .replace(/'/g, "&#39;");
}

// For inline <script> JSON embeds only — never used for the token itself.
function jsonForScript(v: unknown): string {
  return JSON.stringify(v).replace(/</g, "\\u003c").replace(/-->/g, "--\\>");
}

async function sha256Hex8(input: string): Promise<string> {
  const bytes = new TextEncoder().encode(input);
  const digest = await crypto.subtle.digest("SHA-256", bytes);
  const hex = Array.from(new Uint8Array(digest))
    .map((b) => b.toString(16).padStart(2, "0"))
    .join("");
  return hex.slice(0, 8);
}

function securityHeaders(extra?: Record<string, string>): Headers {
  const h = new Headers({
    "Content-Type": "text/html; charset=utf-8",
    "Cache-Control": "no-store",
    "Referrer-Policy": "no-referrer",
    "X-Robots-Tag": "noindex",
  });
  if (extra) for (const [k, v] of Object.entries(extra)) h.set(k, v);
  return h;
}

function htmlResponse(body: string, status = 200): Response {
  return new Response(body, { status, headers: securityHeaders() });
}

// ---------------------------------------------------------------------------
// Timezone math — Deno's Intl only, no libraries (contract instruction).
// ---------------------------------------------------------------------------

interface CivilParts {
  year: number;
  month: number; // 1-12
  day: number;
  hour: number; // 0-23
  minute: number;
  second: number;
}

function civilParts(date: Date, timeZone: string): CivilParts {
  const dtf = new Intl.DateTimeFormat("en-US", {
    timeZone,
    hourCycle: "h23",
    year: "numeric",
    month: "2-digit",
    day: "2-digit",
    hour: "2-digit",
    minute: "2-digit",
    second: "2-digit",
  });
  const parts = dtf.formatToParts(date);
  const map: Record<string, string> = {};
  for (const p of parts) map[p.type] = p.value;
  return {
    year: Number(map.year),
    month: Number(map.month),
    day: Number(map.day),
    hour: map.hour === "24" ? 0 : Number(map.hour),
    minute: Number(map.minute),
    second: Number(map.second),
  };
}

// Offset of `timeZone` from UTC, in minutes, at the instant `date`. Positive = ahead of UTC.
function getOffsetMinutes(date: Date, timeZone: string): number {
  const c = civilParts(date, timeZone);
  const asUtc = Date.UTC(c.year, c.month - 1, c.day, c.hour, c.minute, c.second);
  return Math.round((asUtc - date.getTime()) / 60000);
}

// Convert a "wall clock" date+time as experienced in `timeZone` to the UTC instant it denotes.
function zonedToUtc(
  year: number,
  month: number,
  day: number,
  hour: number,
  minute: number,
  timeZone: string,
): Date {
  const naive = Date.UTC(year, month - 1, day, hour, minute, 0);
  const offset1 = getOffsetMinutes(new Date(naive), timeZone);
  let utc = naive - offset1 * 60000;
  const offset2 = getOffsetMinutes(new Date(utc), timeZone);
  if (offset2 !== offset1) {
    utc = naive - offset2 * 60000;
  }
  return new Date(utc);
}

function civilDateKey(date: Date, timeZone: string): number {
  const c = civilParts(date, timeZone);
  return Date.UTC(c.year, c.month - 1, c.day);
}

function fmtDatePart(date: Date, timeZone: string): string {
  return new Intl.DateTimeFormat("en-US", {
    timeZone,
    weekday: "short",
    month: "short",
    day: "numeric",
  }).format(date);
}

function fmtWeekdayLong(date: Date, timeZone: string): string {
  return new Intl.DateTimeFormat("en-US", { timeZone, weekday: "long" }).format(date);
}

function fmtTimePart(date: Date, timeZone: string): string {
  return new Intl.DateTimeFormat("en-US", {
    timeZone,
    hour: "numeric",
    minute: "2-digit",
    hour12: true,
  }).format(date);
}

function formatDuration(minutes: number): string {
  const m = Math.abs(Math.round(minutes));
  if (m === 0) return "0 minutes";
  if (m % 60 === 0) {
    const h = m / 60;
    return `${h} hour${h === 1 ? "" : "s"}`;
  }
  if (m > 60 && m % 30 === 0) {
    const h = m / 60;
    return `${h} hours`;
  }
  return `${m} minute${m === 1 ? "" : "s"}`;
}

function countdownText(deadlineIso: string): string {
  const ms = new Date(deadlineIso).getTime() - Date.now();
  if (!Number.isFinite(ms) || ms <= 0) return "Expires soon";
  const totalMin = Math.floor(ms / 60000);
  const hours = Math.floor(totalMin / 60);
  const mins = totalMin % 60;
  if (hours <= 0) return `Expires in ${Math.max(mins, 1)}m`;
  return `Expires in ${hours}h ${mins}m`;
}

// ---------------------------------------------------------------------------
// Candidate dual-time view model (§B §1.3 / §3, dual-time rule inherited from Stage 2).
// ---------------------------------------------------------------------------

interface Candidate {
  candidate_idx: number;
  starts_at_utc: string;
  ends_at_utc: string;
  duration_min: number;
}

interface CandidateView {
  idx: number;
  primaryLine: string; // "Thu, Sep 12 · 7:00 – 8:00 PM for you"
  secondaryLine: string; // "Thu, Sep 12 · 10:00 – 11:00 PM for Alex (+1 day)"
  dstCaption: string | null;
  ariaLabel: string;
}

function buildCandidateView(
  cand: Candidate,
  recipientTz: string,
  proposerTz: string,
  proposerName: string,
): CandidateView {
  const start = new Date(cand.starts_at_utc);
  const end = new Date(cand.ends_at_utc);

  const primaryDate = fmtDatePart(start, recipientTz);
  const primaryStart = fmtTimePart(start, recipientTz);
  const primaryEnd = fmtTimePart(end, recipientTz);
  const primaryLine = `${primaryDate} · ${primaryStart} – ${primaryEnd} for you`;

  const secondaryDate = fmtDatePart(start, proposerTz);
  const secondaryStart = fmtTimePart(start, proposerTz);
  const secondaryEnd = fmtTimePart(end, proposerTz);

  const dayDiff = Math.round(
    (civilDateKey(start, proposerTz) - civilDateKey(start, recipientTz)) / 86400000,
  );
  const dayPill = dayDiff === 1 ? " (+1 day)" : dayDiff === -1 ? " (-1 day)" : dayDiff !== 0 ? ` (${dayDiff > 0 ? "+" : ""}${dayDiff} day)` : "";

  const secondaryLine =
    `${secondaryDate} · ${secondaryStart} – ${secondaryEnd} for ${proposerName}${dayPill}`;

  // DST / offset-divergence caption (§B §1.3): compare the zone-pair's offset difference on the
  // candidate date against their offset difference today; only speak up if it has changed.
  const offRecipCand = getOffsetMinutes(start, recipientTz);
  const offPropCand = getOffsetMinutes(start, proposerTz);
  const diffCandMin = Math.abs(offRecipCand - offPropCand);

  const now = new Date();
  const offRecipNow = getOffsetMinutes(now, recipientTz);
  const offPropNow = getOffsetMinutes(now, proposerTz);
  const diffNowMin = Math.abs(offRecipNow - offPropNow);

  let dstCaption: string | null = null;
  if (diffCandMin !== diffNowMin) {
    const deltaMin = Math.abs(diffCandMin - diffNowMin);
    const direction = diffCandMin < diffNowMin ? "less" : "more";
    dstCaption =
      `Time difference is ${formatDuration(diffCandMin)} on this day ` +
      `(${formatDuration(deltaMin)} ${direction} than usual due to clock change)`;
  }

  const weekdayPrimary = fmtWeekdayLong(start, recipientTz);
  const weekdaySecondary = fmtWeekdayLong(start, proposerTz);
  const nextDaySuffix = dayDiff !== 0 ? ", next day" : "";
  const ariaLabel =
    `Option ${cand.candidate_idx + 1}: ${primaryStart} to ${primaryEnd} ${weekdayPrimary} your time, ` +
    `which is ${secondaryStart} to ${secondaryEnd} ${weekdaySecondary}${nextDaySuffix} for ${proposerName}`;

  return {
    idx: cand.candidate_idx,
    primaryLine,
    secondaryLine,
    dstCaption,
    ariaLabel,
  };
}

// ---------------------------------------------------------------------------
// PostgREST RPC calls (service-role only; §A section 2).
// ---------------------------------------------------------------------------

class RpcInfraError extends Error {}

async function callRpc(name: string, args: Record<string, unknown>): Promise<unknown> {
  if (!SUPABASE_URL || !SERVICE_ROLE_KEY) {
    throw new RpcInfraError("missing SUPABASE_URL/SUPABASE_SERVICE_ROLE_KEY env");
  }
  let res: Response;
  try {
    res = await fetch(`${SUPABASE_URL}/rest/v1/rpc/${name}`, {
      method: "POST",
      headers: {
        "Content-Type": "application/json",
        apikey: SERVICE_ROLE_KEY,
        Authorization: `Bearer ${SERVICE_ROLE_KEY}`,
      },
      body: JSON.stringify(args),
    });
  } catch (e) {
    throw new RpcInfraError(`fetch failed: ${(e as Error).message}`);
  }
  if (!res.ok) {
    // Never include the token (args may contain p_token) in the thrown message.
    throw new RpcInfraError(`rpc ${name} returned HTTP ${res.status}`);
  }
  try {
    return await res.json();
  } catch (e) {
    throw new RpcInfraError(`rpc ${name} returned non-JSON body: ${(e as Error).message}`);
  }
}

// ---------------------------------------------------------------------------
// Render-data shape (ASSUMPTION #1 above) + shared outcome resolution.
// ---------------------------------------------------------------------------

interface ReadyData {
  title: string;
  response_deadline: string;
  recipient_display_name: string;
  recipient_home_tz: string;
  proposer_display_name: string;
  proposer_origin_tz: string;
  candidates: Candidate[];
}

type EdgeKind =
  | "ready"
  | "expired"
  | "already_accepted"
  | "already_declined"
  | "already_cancelled"
  | "countered_since_mint"
  | "invalid"
  | "network_error";

interface Resolved {
  kind: EdgeKind;
  ready?: ReadyData;
  // Pre-gate F13a: rpc_get_rsvp_render_data returns proposer_display_name even for
  // non-live states (minimal shape) -- edge pages use the real name, not "the other
  // person".
  proposerName?: string;
}

function mapRenderData(payload: unknown): Resolved {
  if (!payload || typeof payload !== "object") return { kind: "invalid" };
  const p = payload as Record<string, unknown>;
  const outcome = p.outcome;

  // Reconciled against A4's shipped rpc_get_rsvp_render_data at merge (2026-09-12):
  // the RPC returns {"outcome":"ok","state":"live"|<rich state>,...,"deadline":...} —
  // richer than this seat's assumed "ready"/conflict split, and the deadline key is
  // `deadline`, not `response_deadline`. Rich non-live states map straight to their
  // §B edge pages.
  if (outcome === "ok") {
    const state = String(p.state ?? "");
    if (state !== "live") {
      return {
        kind: mapTokenState(state),
        proposerName: typeof p.proposer_display_name === "string" ? p.proposer_display_name : undefined,
      };
    }
    const candidatesRaw = Array.isArray(p.candidates) ? p.candidates : [];
    const candidates: Candidate[] = candidatesRaw.map((c) => {
      const cc = c as Record<string, unknown>;
      return {
        candidate_idx: Number(cc.candidate_idx),
        starts_at_utc: String(cc.starts_at_utc),
        ends_at_utc: String(cc.ends_at_utc),
        duration_min: Number(cc.duration_min),
      };
    });
    return {
      kind: "ready",
      ready: {
        title: typeof p.title === "string" && p.title.length > 0 ? p.title : "Shared time lock",
        response_deadline: String(p.deadline ?? new Date().toISOString()),
        recipient_display_name: String(p.recipient_display_name ?? "Someone"),
        recipient_home_tz: String(p.recipient_home_tz ?? "UTC"),
        proposer_display_name: String(p.proposer_display_name ?? "Someone"),
        proposer_origin_tz: String(p.proposer_origin_tz ?? "UTC"),
        candidates,
      },
    };
  }

  if (outcome === "conflict") {
    return { kind: mapConflictStatus(String(p.status ?? "")) };
  }

  // outcome === "invalid_or_unavailable" or any unrecognized shape: fail calm and generic.
  return { kind: "invalid" };
}

/** A4's rich token states (post-MAC-verification only) → §B edge pages. */
function mapTokenState(state: string): EdgeKind {
  switch (state) {
    case "expired":
      return "expired";
    case "countered_since_mint":
      return "countered_since_mint";
    case "already_accepted":
      return "already_accepted";
    case "declined":
      return "already_declined";
    case "cancelled":
      return "already_cancelled";
    default:
      return "invalid";
  }
}

function mapConflictStatus(status: string): EdgeKind {
  switch (status) {
    case "expired":
      return "expired";
    case "accepted":
      return "already_accepted";
    case "declined":
      return "already_declined";
    case "cancelled":
      return "already_cancelled";
    case "proposed":
    case "countered":
      return "countered_since_mint";
    default:
      return "invalid";
  }
}

// ---------------------------------------------------------------------------
// HTML shell + page renderers (§B: mobile-first, inline CSS <8KB, zero frameworks).
// ---------------------------------------------------------------------------

// Kept intentionally compact; comments live outside the string. Well under the 8KB budget.
const STYLE = `
:root{color-scheme:light dark;--bg:#f4f3f7;--surface:#ffffff;--on:#1c1b1f;--on-var:#49454f;
--primary:#3a5b8c;--primary-on:#ffffff;--outline:#c9c5d0;--accent-bg:#e7edf7;--danger-bg:#fff3f0;
--pill-bg:#eef0f4}
@media (prefers-color-scheme:dark){:root{--bg:#141318;--surface:#201f26;--on:#e7e1e8;--on-var:#cac4d0;
--primary:#9db8e6;--primary-on:#0c2340;--outline:#49454f;--accent-bg:#1f2b3d;--danger-bg:#2b201d;
--pill-bg:#2a2830}}
*{box-sizing:border-box}
body{margin:0;background:var(--bg);color:var(--on);font:15px/1.45 -apple-system,BlinkMacSystemFont,"Segoe UI",Roboto,Helvetica,Arial,sans-serif;
-webkit-text-size-adjust:100%}
.wrap{max-width:480px;margin:0 auto;padding:16px 16px 40px}
.topbar{display:flex;align-items:baseline;justify-content:space-between;gap:12px;padding:8px 2px 16px;flex-wrap:wrap}
.brand{font-weight:700;letter-spacing:.04em;color:var(--primary)}
.identity{font-size:12.5px;color:var(--on-var);text-align:right}
.card{background:var(--surface);border:1px solid var(--outline);border-radius:16px;padding:20px}
.meta-row{display:flex;align-items:center;justify-content:space-between;gap:8px;flex-wrap:wrap}
.eyebrow{font-size:11px;letter-spacing:.08em;text-transform:uppercase;color:var(--on-var);font-weight:600}
.countdown{font-size:12.5px;color:var(--on-var);font-weight:600}
h1.title{font-size:20px;font-weight:600;margin:10px 0 2px}
.subtitle{margin:0 0 16px;color:var(--on-var);font-size:14px}
.candidates{display:flex;flex-direction:column;gap:10px;margin:0 0 16px}
.candidate{display:block;border:1px solid var(--outline);border-radius:12px;padding:12px 14px;cursor:pointer;position:relative}
.candidate input[type=radio]{position:absolute;top:14px;left:14px;width:20px;height:20px;margin:0}
.candidate .body{display:block;padding-left:30px}
.candidate input[type=radio]:checked ~ .body{color:var(--on)}
.candidate:has(input:checked){border-color:var(--primary);background:var(--accent-bg)}
.line{display:block}
.line.primary{font-weight:600;font-size:15px}
.line.secondary{font-weight:400;font-size:13px;color:var(--on-var);margin-top:2px}
.dst-caption{display:block;margin-top:6px;font-size:12px;color:var(--on-var);background:var(--pill-bg);
border-radius:8px;padding:5px 8px}
.btn{display:block;width:100%;min-height:48px;border-radius:12px;font-size:15px;font-weight:600;
border:1px solid transparent;cursor:pointer;text-align:center;padding:10px 16px;text-decoration:none;
box-sizing:border-box}
.btn-primary{background:var(--primary);color:var(--primary-on);margin-bottom:10px}
.btn-outline{background:transparent;border-color:var(--outline);color:var(--on)}
.btn-ghost{background:transparent;color:var(--on-var);border-color:transparent}
.btn[disabled]{opacity:.6;cursor:default}
.btn-row{display:flex;gap:10px}
.btn-row .btn{flex:1}
.footnote{text-align:center;color:var(--on-var);font-size:12.5px;margin:14px 0 0}
details.counter{margin-top:16px;border-top:1px solid var(--outline);padding-top:14px}
details.counter summary{list-style:none;cursor:pointer;display:block;width:100%;min-height:48px;
border-radius:12px;border:1px solid var(--outline);color:var(--on);font-weight:600;font-size:15px;
text-align:center;padding:12px 16px;box-sizing:border-box}
details.counter summary::-webkit-details-marker{display:none}
details.counter[open] summary{margin-bottom:14px}
.field{display:block;margin-bottom:12px;font-size:13px;color:var(--on-var);font-weight:600}
.field input[type=date],.field input[type=time]{display:block;width:100%;margin-top:6px;padding:10px;
border-radius:10px;border:1px solid var(--outline);background:var(--surface);color:var(--on);font-size:15px}
.pills{display:flex;gap:8px;flex-wrap:wrap;margin:0 0 12px}
.pill{position:relative}
.pill input{position:absolute;opacity:0;width:100%;height:100%;margin:0;cursor:pointer}
.pill span{display:inline-block;padding:8px 14px;border-radius:999px;border:1px solid var(--outline);
font-size:13.5px;min-height:32px;line-height:16px}
.pill input:checked ~ span{background:var(--primary);color:var(--primary-on);border-color:var(--primary)}
.preview{background:var(--pill-bg);border-radius:10px;padding:10px 12px;font-size:13px;margin:0 0 12px}
.preview .line{margin-top:2px}
.hint{font-size:12.5px;color:var(--on-var);margin:0 0 14px}
.edge-icon{width:44px;height:44px;border-radius:50%;background:var(--accent-bg);display:flex;
align-items:center;justify-content:center;margin:2px auto 16px;font-size:22px}
.edge-title{font-size:20px;font-weight:600;text-align:center;margin:0 0 8px}
.edge-body{color:var(--on-var);font-size:14.5px;text-align:center;margin:0 0 20px}
.lock-card{background:var(--accent-bg);border-radius:12px;padding:14px;margin:0 0 16px}
.lock-card .line.primary{font-size:16px}
.small-note{text-align:center;color:var(--on-var);font-size:12.5px;margin-top:10px}
`.trim();

const CLIENT_JS = `
(function(){
  var form=document.getElementById('rsvp-form');
  if(form){
    form.addEventListener('submit',function(){
      var btns=form.querySelectorAll('button[type=submit]');
      for(var i=0;i<btns.length;i++){
        btns[i].disabled=true;
        if(btns[i].value==='accept'){btns[i].textContent='Sending response…';}
      }
    });
  }
  var counterForm=document.getElementById('counter-form');
  if(counterForm){
    counterForm.addEventListener('submit',function(){
      var b=counterForm.querySelector('button[type=submit]');
      if(b){b.disabled=true;b.textContent='Sending response…';}
    });
  }
  try{
    var meta=document.getElementById('rsvp-meta');
    if(!meta||!counterForm) return;
    var data=JSON.parse(meta.textContent);
    var dateEl=document.getElementById('cf-date'),timeEl=document.getElementById('cf-time');
    var durEls=counterForm.querySelectorAll('input[name=duration_min]');
    var out=document.getElementById('counter-preview');
    function offsetMin(date,tz){
      var dtf=new Intl.DateTimeFormat('en-US',{timeZone:tz,hourCycle:'h23',year:'numeric',month:'2-digit',
        day:'2-digit',hour:'2-digit',minute:'2-digit',second:'2-digit'});
      var parts=dtf.formatToParts(date),m={};
      for(var i=0;i<parts.length;i++){m[parts[i].type]=parts[i].value;}
      var asUtc=Date.UTC(+m.year,+m.month-1,+m.day,m.hour==='24'?0:+m.hour,+m.minute,+m.second);
      return Math.round((asUtc-date.getTime())/60000);
    }
    function zonedToUtc(y,mo,d,h,mi,tz){
      var naive=Date.UTC(y,mo-1,d,h,mi,0);
      var o1=offsetMin(new Date(naive),tz);
      var utc=naive-o1*60000;
      var o2=offsetMin(new Date(utc),tz);
      if(o2!==o1){utc=naive-o2*60000;}
      return new Date(utc);
    }
    function fmtDate(d,tz){return new Intl.DateTimeFormat('en-US',{timeZone:tz,weekday:'short',month:'short',day:'numeric'}).format(d);}
    function fmtTime(d,tz){return new Intl.DateTimeFormat('en-US',{timeZone:tz,hour:'numeric',minute:'2-digit',hour12:true}).format(d);}
    function render(){
      if(!dateEl.value||!timeEl.value) return;
      var parts=dateEl.value.split('-').map(Number);
      var tparts=timeEl.value.split(':').map(Number);
      var dur=60;
      for(var i=0;i<durEls.length;i++){if(durEls[i].checked){dur=parseInt(durEls[i].value,10);}}
      var start=zonedToUtc(parts[0],parts[1],parts[2],tparts[0],tparts[1],data.recipientTz);
      var end=new Date(start.getTime()+dur*60000);
      out.innerHTML='Preview for your counter-proposal:<br>'+
        '<span class="line">'+fmtDate(start,data.recipientTz)+' · '+fmtTime(start,data.recipientTz)+' – '+fmtTime(end,data.recipientTz)+' for you</span>'+
        '<span class="line">'+fmtDate(start,data.proposerTz)+' · '+fmtTime(start,data.proposerTz)+' – '+fmtTime(end,data.proposerTz)+' for '+data.proposerName+'</span>';
    }
    dateEl.addEventListener('input',render);
    timeEl.addEventListener('input',render);
    for(var j=0;j<durEls.length;j++){durEls[j].addEventListener('change',render);}
    render();
  }catch(e){/* JS preview is an enhancement only; no-JS submission still works. */}
})();
`.trim();

function pageShell(opts: { identityLine?: string; bodyHtml: string; withScript?: boolean }): string {
  return `<!doctype html>
<html lang="en">
<head>
<meta charset="utf-8">
<meta name="viewport" content="width=device-width, initial-scale=1">
<meta name="robots" content="noindex, nofollow">
<title>ELAY · Time lock response</title>
<style>${STYLE}</style>
</head>
<body>
<div class="wrap">
<header class="topbar">
<span class="brand">ELAY</span>
${opts.identityLine ? `<span class="identity">${opts.identityLine}</span>` : ""}
</header>
${opts.bodyHtml}
</div>
${opts.withScript ? `<script>${CLIENT_JS}</script>` : ""}
</body>
</html>`;
}

function renderReadyPage(token: string, data: ReadyData): string {
  const views = data.candidates.map((c) =>
    buildCandidateView(c, data.recipient_home_tz, data.proposer_origin_tz, data.proposer_display_name)
  );

  const optionWord = data.candidates.length === 1 ? "option" : "options";
  const identityLine =
    `Responding as ${esc(data.recipient_display_name)} <span style="opacity:.75">(${esc(data.recipient_home_tz)})</span>`;

  const candidateCards = views
    .map((v, i) => {
      const dst = v.dstCaption
        ? `<span class="dst-caption">${esc(v.dstCaption)}</span>`
        : "";
      return `<label class="candidate" for="opt-${v.idx}">
  <input type="radio" name="candidate_idx" id="opt-${v.idx}" value="${v.idx}" aria-label="${esc(v.ariaLabel)}" ${i === 0 ? "checked" : ""}>
  <span class="body">
    <span class="line primary">${esc(v.primaryLine)}</span>
    <span class="line secondary">${esc(v.secondaryLine)}</span>
    ${dst}
  </span>
</label>`;
    })
    .join("\n");

  // Counter composer defaults: candidate 0's start, read in the recipient's own timezone.
  const first = data.candidates[0];
  let defDate = "";
  let defTime = "";
  if (first) {
    const c = civilParts(new Date(first.starts_at_utc), data.recipient_home_tz);
    defDate = `${String(c.year).padStart(4, "0")}-${String(c.month).padStart(2, "0")}-${String(c.day).padStart(2, "0")}`;
    defTime = `${String(c.hour).padStart(2, "0")}:${String(c.minute).padStart(2, "0")}`;
  }

  const meta = jsonForScript({
    recipientTz: data.recipient_home_tz,
    proposerTz: data.proposer_origin_tz,
    proposerName: data.proposer_display_name,
  });

  const body = `
<main class="card">
  <div class="meta-row">
    <span class="eyebrow">Time lock proposal · Incoming</span>
    <span class="countdown" aria-live="polite">${esc(countdownText(data.response_deadline))}</span>
  </div>
  <h1 class="title">${esc(data.title)}</h1>
  <p class="subtitle">${esc(data.proposer_display_name)} proposed ${data.candidates.length} ${optionWord}:</p>

  <form method="POST" action="/rsvp/${encodeURIComponent(token)}/respond" id="rsvp-form">
    <div class="candidates" role="radiogroup" aria-label="Proposed times">
${candidateCards}
    </div>
    <button type="submit" name="response" value="accept" class="btn btn-primary">Accept selected option</button>
    <div class="btn-row">
      <button type="submit" name="response" value="decline" class="btn btn-ghost">Can't make it</button>
    </div>
  </form>
  <p class="footnote">Locks directly onto both of your ELAY plans.</p>

  <details class="counter">
    <summary>Suggest a different time</summary>
    <form method="POST" action="/rsvp/${encodeURIComponent(token)}/respond" id="counter-form">
      <input type="hidden" name="response" value="counter">
      <label class="field">Date
        <input type="date" name="date" id="cf-date" value="${esc(defDate)}" required>
      </label>
      <label class="field">Start time
        <input type="time" name="time" id="cf-time" value="${esc(defTime)}" required>
      </label>
      <div class="pills" role="radiogroup" aria-label="Duration">
        <label class="pill"><input type="radio" name="duration_min" value="30"><span>30m</span></label>
        <label class="pill"><input type="radio" name="duration_min" value="45"><span>45m</span></label>
        <label class="pill"><input type="radio" name="duration_min" value="60" checked><span>60m</span></label>
        <label class="pill"><input type="radio" name="duration_min" value="90"><span>90m</span></label>
      </div>
      <div class="preview" id="counter-preview" aria-live="polite">Preview updates as you adjust the date, time and duration above.</div>
      <p class="hint">${esc(data.proposer_display_name)} will have 24 hours to respond.</p>
      <button type="submit" class="btn btn-primary">Send counter-proposal</button>
      <p class="hint">Need to offer multiple choices? Open the ELAY app to compose a multi-option counter.</p>
    </form>
  </details>
</main>
<script type="application/json" id="rsvp-meta">${meta}</script>
<p class="small-note">This link was sent to ${esc(data.recipient_display_name)}. If you are not ${esc(data.recipient_display_name)}, please close this page.</p>
`;

  return pageShell({ identityLine, bodyHtml: body, withScript: true });
}

function renderEdgePage(opts: {
  icon: string;
  title: string;
  body: string;
  primaryLabel: string;
  primaryHref: string;
  secondaryLabel?: string;
  secondaryHref?: string;
}): string {
  const body = `
<main class="card">
  <div class="edge-icon" aria-hidden="true">${opts.icon}</div>
  <h1 class="edge-title">${esc(opts.title)}</h1>
  <p class="edge-body">${opts.body}</p>
  <a class="btn btn-primary" href="${esc(opts.primaryHref)}">${esc(opts.primaryLabel)}</a>
  ${opts.secondaryLabel ? `<a class="btn btn-outline" href="${esc(opts.secondaryHref ?? "#")}">${esc(opts.secondaryLabel)}</a>` : ""}
</main>
`;
  return pageShell({ bodyHtml: body });
}

const OPEN_ELAY_HREF = "elay://plan";

function renderInvalidPage(): string {
  return renderEdgePage({
    icon: "○",
    title: "Link unavailable",
    body:
      "This response link isn't available. It may have expired, already been used, or no longer applies. " +
      "Open the app to check the latest status.",
    primaryLabel: "Open ELAY",
    primaryHref: OPEN_ELAY_HREF,
  });
}

function renderExpiredPage(): string {
  return renderEdgePage({
    icon: "◔",
    title: "Link expired",
    body: "This response link has expired for your security. You can view and respond to the proposal in the app.",
    primaryLabel: "Open in ELAY",
    primaryHref: OPEN_ELAY_HREF,
  });
}

function renderAlreadyAcceptedPage(): string {
  return renderEdgePage({
    icon: "✓",
    title: "Already accepted",
    body: "This time lock was already confirmed. It is on both your and their plans.",
    primaryLabel: "Open ELAY to view",
    primaryHref: OPEN_ELAY_HREF,
  });
}

function renderAlreadyDeclinedPage(): string {
  return renderEdgePage({
    icon: "○",
    title: "Proposal closed",
    body: "This proposal was already declined.",
    primaryLabel: "Open ELAY",
    primaryHref: OPEN_ELAY_HREF,
  });
}

function renderAlreadyCancelledPage(proposerName: string): string {
  return renderEdgePage({
    icon: "○",
    title: "Proposal withdrawn",
    body: `${esc(proposerName)} withdrew this proposal.`,
    primaryLabel: "Open ELAY",
    primaryHref: OPEN_ELAY_HREF,
  });
}

function renderCounteredSinceMintPage(proposerName: string): string {
  return renderEdgePage({
    icon: "↻",
    title: "New times suggested",
    // Generic voice (re-verify F9-copy note): on a revision-1 token the counter can only
    // have come from the RECIPIENT, so naming the proposer here would misattribute it.
    body: `New times were suggested after this link was sent. Open the app to view the latest options.`,
    primaryLabel: "Open latest in ELAY",
    primaryHref: OPEN_ELAY_HREF,
  });
}

function renderNetworkErrorPage(retryHref: string): string {
  return renderEdgePage({
    icon: "!",
    title: "Unable to connect",
    body: "We couldn't reach the server. Please check your connection and try again.",
    primaryLabel: "Try again",
    primaryHref: retryHref,
    secondaryLabel: "Open ELAY",
    secondaryHref: OPEN_ELAY_HREF,
  });
}

function renderNotFoundPage(): string {
  return renderEdgePage({
    icon: "?",
    title: "Page not found",
    body: "There's nothing to show here.",
    primaryLabel: "Open ELAY",
    primaryHref: OPEN_ELAY_HREF,
  });
}

function renderAcceptedSuccessPage(optionNumber: number, view: CandidateView): string {
  const body = `
<main class="card">
  <div class="edge-icon" aria-hidden="true">✓</div>
  <h1 class="edge-title">Time lock confirmed</h1>
  <p class="edge-body">Option ${optionNumber} is locked in. On both plans.</p>
  <div class="lock-card">
    <span class="line primary">${esc(view.primaryLine)}</span>
    <span class="line secondary">${esc(view.secondaryLine)}</span>
  </div>
  <p class="small-note">On both plans · Ready in your schedule</p>
  <a class="btn btn-primary" href="${OPEN_ELAY_HREF}">Open ELAY</a>
</main>
`;
  return pageShell({ bodyHtml: body });
}

function renderCounteredSuccessPage(proposerName: string): string {
  return renderEdgePage({
    icon: "↻",
    title: "Alternative suggested",
    body: `You proposed a new time to ${esc(proposerName)}. You'll get an update once they respond.`,
    primaryLabel: "Open ELAY",
    primaryHref: OPEN_ELAY_HREF,
  });
}

function renderDeclinedSuccessPage(proposerName: string): string {
  return renderEdgePage({
    icon: "○",
    title: "Proposal declined",
    body: `You let ${esc(proposerName)} know you can't make these times. You can propose a new time anytime.`,
    primaryLabel: "Open ELAY",
    primaryHref: OPEN_ELAY_HREF,
  });
}

// Renders whichever §B page corresponds to a `Resolved` non-ready outcome.
function renderResolved(kind: EdgeKind, proposerName: string): string {
  switch (kind) {
    case "expired":
      return renderExpiredPage();
    case "already_accepted":
      return renderAlreadyAcceptedPage();
    case "already_declined":
      return renderAlreadyDeclinedPage();
    case "already_cancelled":
      return renderAlreadyCancelledPage(proposerName);
    case "countered_since_mint":
      return renderCounteredSinceMintPage(proposerName);
    default:
      return renderInvalidPage();
  }
}

// ---------------------------------------------------------------------------
// Request body parsing — both application/x-www-form-urlencoded (no-JS form) and JSON.
// Per contract: the token path segment is the only identifier; any id-like keys here
// (proposal_id, recipient_id, token, etc.) are read into `raw` but never forwarded to the RPC —
// only response/candidate_idx/date/time/duration_min are ever used.
// ---------------------------------------------------------------------------

async function parseBody(req: Request): Promise<Record<string, string>> {
  const contentType = req.headers.get("content-type") ?? "";
  const out: Record<string, string> = {};
  if (contentType.includes("application/json")) {
    try {
      const j = await req.json();
      if (j && typeof j === "object") {
        for (const [k, v] of Object.entries(j as Record<string, unknown>)) {
          if (v === null || v === undefined) continue;
          out[k] = String(v);
        }
      }
    } catch {
      // malformed JSON: leave `out` empty, caller renders the invalid/generic page.
    }
    return out;
  }
  // Default: treat as form-urlencoded (the no-JS form's native encoding), tolerant of a missing
  // or slightly-off content-type header from odd clients.
  try {
    const text = await req.text();
    const params = new URLSearchParams(text);
    for (const [k, v] of params.entries()) out[k] = v;
  } catch {
    // leave empty
  }
  return out;
}

// ---------------------------------------------------------------------------
// Main handler
// ---------------------------------------------------------------------------

function splitRoute(pathname: string): { token: string; sub: string } {
  const parts = pathname.split("/").filter(Boolean);
  const idx = parts.lastIndexOf("rsvp");
  const rest = idx === -1 ? parts : parts.slice(idx + 1);
  return { token: rest[0] ?? "", sub: rest[1] ?? "" };
}

// clientIp: A4's shipped RPCs take a trailing p_client_ip for the contract's per-IP
// rate limit (reconciled at merge, 2026-09-12) — pass the edge request's peer address.
async function handleGet(token: string, clientIp: string | null): Promise<Response> {
  let payload: unknown;
  try {
    payload = await callRpc("rpc_get_rsvp_render_data", { p_token: token, p_client_ip: clientIp });
  } catch (e) {
    const hash = await sha256Hex8(token).catch(() => "????????");
    console.error(`[rsvp] render-data infra error token=${hash} ${(e as Error).message}`);
    return htmlResponse(renderNetworkErrorPage(`/rsvp/${encodeURIComponent(token)}`), 500);
  }

  const resolved = mapRenderData(payload);
  if (resolved.kind === "ready" && resolved.ready) {
    return htmlResponse(renderReadyPage(token, resolved.ready));
  }
  const proposerName = resolved.proposerName ?? resolved.ready?.proposer_display_name ?? "the other person";
  return htmlResponse(renderResolved(resolved.kind, proposerName));
}

async function handlePostRespond(token: string, req: Request, clientIp: string | null): Promise<Response> {
  const fields = await parseBody(req);

  // Always fetch render-data first: needed (a) to short-circuit an already-dead token without
  // attempting a mutation, and (b) to get recipient_home_tz/proposer_origin_tz for building a
  // counter's UTC candidate, and (c) to render the accepted candidate's dual-time strings, since
  // rpc_respond_proposal's own "applied" envelope for accept does not carry the candidate's
  // starts_at_utc/ends_at_utc (see contracts/fixtures/proposal-accept.json).
  let renderPayload: unknown;
  try {
    renderPayload = await callRpc("rpc_get_rsvp_render_data", { p_token: token, p_client_ip: clientIp });
  } catch (e) {
    const hash = await sha256Hex8(token).catch(() => "????????");
    console.error(`[rsvp] pre-respond render-data infra error token=${hash} ${(e as Error).message}`);
    // Retry link points at the plain GET page (a link can only GET) so "Try again" always works
    // even without JS, rather than pointing at the POST-only /respond path.
    return htmlResponse(renderNetworkErrorPage(`/rsvp/${encodeURIComponent(token)}`), 500);
  }
  const resolved = mapRenderData(renderPayload);
  if (resolved.kind !== "ready" || !resolved.ready) {
    return htmlResponse(renderResolved(resolved.kind, resolved.proposerName ?? "the other person"));
  }
  const data = resolved.ready;

  const action = (fields.response ?? fields.action ?? "").toLowerCase();
  if (action !== "accept" && action !== "decline" && action !== "counter") {
    return htmlResponse(renderInvalidPage(), 400);
  }

  const rpcArgs: Record<string, unknown> = {
    p_token: token,
    p_action: action,
    p_client_ip: clientIp,
    p_candidate_idx: null,
    p_new_origin_tz: null,
    p_new_deadline: null,
    p_new_candidates: null,
  };

  let chosenIdx = 0;
  if (action === "accept") {
    const idx = Number.parseInt(fields.candidate_idx ?? "", 10);
    if (!Number.isInteger(idx) || idx < 0 || idx >= data.candidates.length) {
      return htmlResponse(renderInvalidPage(), 400);
    }
    chosenIdx = idx;
    rpcArgs.p_candidate_idx = idx;
  } else if (action === "counter") {
    const dateStr = fields.date ?? "";
    const timeStr = fields.time ?? "";
    const durMin = Number.parseInt(fields.duration_min ?? "60", 10);
    const dateMatch = /^(\d{4})-(\d{2})-(\d{2})$/.exec(dateStr);
    const timeMatch = /^(\d{2}):(\d{2})$/.exec(timeStr);
    if (!dateMatch || !timeMatch || ![30, 45, 60, 90].includes(durMin)) {
      return htmlResponse(renderInvalidPage(), 400);
    }
    const start = zonedToUtc(
      Number(dateMatch[1]),
      Number(dateMatch[2]),
      Number(dateMatch[3]),
      Number(timeMatch[1]),
      Number(timeMatch[2]),
      data.recipient_home_tz,
    );
    const end = new Date(start.getTime() + durMin * 60000);
    // Lead amendment #3: 24h default deadline, sent through the shipped counter path unchanged.
    const newDeadline = new Date(Date.now() + 24 * 60 * 60 * 1000);
    rpcArgs.p_new_origin_tz = data.recipient_home_tz;
    rpcArgs.p_new_deadline = newDeadline.toISOString();
    rpcArgs.p_new_candidates = [
      {
        candidate_idx: 0,
        starts_at_utc: start.toISOString(),
        ends_at_utc: end.toISOString(),
        duration_min: durMin,
      },
    ];
  }

  let result: unknown;
  try {
    result = await callRpc("rpc_respond_proposal_web", rpcArgs);
  } catch (e) {
    const hash = await sha256Hex8(token).catch(() => "????????");
    console.error(`[rsvp] respond infra error token=${hash} ${(e as Error).message}`);
    // Same reasoning as above: point "Try again" at the GET page, not the POST-only path.
    return htmlResponse(renderNetworkErrorPage(`/rsvp/${encodeURIComponent(token)}`), 500);
  }

  if (!result || typeof result !== "object") {
    return htmlResponse(renderInvalidPage());
  }
  const r = result as Record<string, unknown>;

  if (r.outcome === "conflict") {
    const kind = mapConflictStatus(String(r.status ?? ""));
    return htmlResponse(renderResolved(kind, data.proposer_display_name));
  }
  if (r.outcome !== "applied") {
    // Covers "invalid_or_unavailable" and any other unrecognized shape — calm, generic, no oracle.
    return htmlResponse(renderInvalidPage());
  }

  const appliedAction = String(r.action ?? "");
  if (appliedAction === "accept_proposal") {
    const view = buildCandidateView(
      data.candidates[chosenIdx],
      data.recipient_home_tz,
      data.proposer_origin_tz,
      data.proposer_display_name,
    );
    return htmlResponse(renderAcceptedSuccessPage(chosenIdx + 1, view));
  }
  if (appliedAction === "decline_proposal") {
    return htmlResponse(renderDeclinedSuccessPage(data.proposer_display_name));
  }
  if (appliedAction === "counter_proposal") {
    return htmlResponse(renderCounteredSuccessPage(data.proposer_display_name));
  }
  // Unrecognized "applied" action name: render generically rather than guess.
  return htmlResponse(renderInvalidPage());
}

Deno.serve(async (req: Request) => {
  const url = new URL(req.url);
  const { token, sub } = splitRoute(url.pathname);
  // LAST element, not first (re-verify note): the first XFF hop is client-supplied;
  // the trusted proxy appends the real peer last.
  const clientIp = req.headers.get("x-forwarded-for")?.split(",").pop()?.trim() ?? null;

  if (!token) {
    return htmlResponse(renderNotFoundPage(), 404);
  }

  if (req.method === "GET" && sub === "") {
    return handleGet(token, clientIp);
  }
  if (req.method === "POST" && sub === "respond") {
    return handlePostRespond(token, req, clientIp);
  }

  return htmlResponse(renderNotFoundPage(), 404);
});
