-- Stage 3: no-install web RSVP (contracts/stage3-web-rsvp.md, council/stage3-web-rsvp-
-- security-opus.md §A -- binding full text). Seat A4 (SQL): this migration only.
--
-- House patterns reused verbatim (per the seat brief):
--   * rsvp_token_hmac_keys is a second no-grant singleton private-key table on the exact
--     pattern of pair_invite_hmac_keys (20260912010000_stage1_pairing.sql section 0):
--     RLS enabled, revoked from everyone, no policies, key generated once at migration
--     time and never committed as a literal.
--   * The token is HMAC-derived and hash-at-rest, mirroring pair_invite_code /
--     pair_invites.code_hash: the plaintext is never stored, and a replay re-derives the
--     identical string from (key, token_id) instead of persisting it anywhere (including
--     mutation_receipts -- the mint receipt is stored WITHOUT its 'token' key).
--   * Every mutation RPC is SECURITY DEFINER, `set search_path = ''`, checks auth.uid()
--     explicitly (rpc_mint_rsvp_token) or derives identity from the verified token itself
--     (rpc_get_rsvp_render_data / rpc_respond_proposal_web, both service_role-only), locks
--     rows FOR UPDATE before mutating, and uses the shared mutation_receipts ledger with an
--     "action" tag for cross-action replay rejection (rpc_mint_rsvp_token only -- the
--     respond path rides rpc_respond_proposal's OWN receipt/cross-action machinery
--     unchanged, per contract).
--   * Every new object gets an explicit REVOKE ALL FROM PUBLIC, ANON[, AUTHENTICATED] plus
--     only the grants the contract calls for (house rule: Supabase auto-grants to anon).
--
-- UNVERIFIED / assumption flags (also called out in this seat's report; the lead verifies
-- against the live stack):
--   1. uuid-ossp / extensions.uuid_generate_v5 is not used anywhere else in this repo (grep
--      confirms zero hits), unlike the pgcrypto-family functions (hmac/digest/gen_random_
--      bytes) that Stage 1 already proved live under `extensions.`. Rather than depend on an
--      unconfirmed extension, `rsvp_web_operation_id` builds a UUIDv5-*shaped* value (version
--      nibble forced to 5, variant nibble forced to 10xx) directly from extensions.digest(...,
--      'sha256'), per the brief's documented fallback ("encode(digest(...))::uuid trick").
--   2. The contract's own prose gives two mutually-inconsistent readings of the respond-path
--      operation_id: "operation_id := uuid_v5(token_id, p_action)" (varies per action) versus
--      the required adversarial behavior "a different action on the same token hits the
--      shipped cross-action guard (22023)" (only reachable if the SAME operation_id is reused
--      across actions -- i.e. token_id ALONE). This seat derives operation_id from token_id
--      alone (fixed domain-separation salt, not the caller's action) because that is the only
--      reading that makes the explicitly-tested 22023 case reachable; see
--      rsvp_web_operation_id's comment. Flagged for lead review.
--   3. Neither rpc_get_rsvp_render_data nor rpc_respond_proposal_web's signature in the brief
--      carries a client-IP parameter, yet the contract requires a 60/15min per-IP limit. Both
--      gained a trailing optional `p_client_ip text default null` so the (E1-owned) Edge
--      Function can pass the request IP through; the per-token limit (10/15min) does not
--      depend on this and is always enforced.

-- =====================================================================================
-- 0. Private HMAC signing key for RSVP tokens (contract §A/§1, same pattern as
--    pair_invite_hmac_keys): no-grant singleton, generated here, never committed as a
--    literal. A replayed rpc_mint_rsvp_token reconstructs the identical presented token
--    from (key, token_id) without persisting the plaintext anywhere.
-- =====================================================================================

create table public.rsvp_token_hmac_keys (
    id  boolean primary key default true,
    key bytea not null,

    constraint rsvp_token_hmac_keys_singleton_check check (id)
);

comment on table public.rsvp_token_hmac_keys is
    'Singleton private key for deriving RSVP capability tokens via HMAC-SHA256 (council/stage3-web-rsvp-security-opus.md §1). No grants to any role; readable only from inside functions running as the migration owner (SECURITY DEFINER RPCs, or plain helpers called from within them).';

insert into public.rsvp_token_hmac_keys (id, key) values (true, extensions.gen_random_bytes(32));

alter table public.rsvp_token_hmac_keys enable row level security;
revoke all on table public.rsvp_token_hmac_keys from public, anon, authenticated;
-- no policies: authenticated/anon/service_role get zero rows even if a future migration
-- grants SELECT (matches pair_invite_hmac_keys exactly).

-- =====================================================================================
-- 1. rsvp_tokens (contract §A/§3): hash-at-rest, full binding set recorded at mint,
--    partial unique "one live token" index, FK into proposal_revisions.
-- =====================================================================================

create table public.rsvp_tokens (
    id                         uuid primary key default gen_random_uuid(),
    proposal_id                uuid not null,
    recipient_user_id          uuid not null references auth.users (id),
    minted_by                  uuid not null references auth.users (id),
    revision_at_mint            int not null check (revision_at_mint between 1 and 20),
    channel_generation_at_mint uuid not null,
    token_hash                 bytea not null unique,
    expires_at                 timestamptz not null,
    created_at                 timestamptz not null default now(),
    revoked_at                 timestamptz null,
    consumed_at                timestamptz null,
    consumed_action            text null,

    constraint rsvp_tokens_expiry_after_created_check check (expires_at > created_at),
    constraint rsvp_tokens_recipient_not_minter_check check (recipient_user_id <> minted_by),
    constraint rsvp_tokens_consumed_action_check check (
        consumed_action is null or consumed_action in ('accept', 'decline')
    ),
    constraint rsvp_tokens_consumed_action_iff_consumed_at_check check (
        (consumed_action is not null) = (consumed_at is not null)
    ),
    -- Bindings live on the row (contract §1): a holder learns nothing from the string
    -- itself, and this FK ties the mint to a revision that actually existed.
    constraint rsvp_tokens_revision_fk
        foreign key (proposal_id, revision_at_mint) references public.proposal_revisions (proposal_id, revision_no)
);

comment on table public.rsvp_tokens is 'HMAC capability tokens for the no-install web RSVP flow (council/stage3-web-rsvp-security-opus.md §1/§3). Plaintext is never stored -- token_hash is sha256 of the presented string, and the string itself is re-derivable from (id, HMAC key) for idempotent mint replay. Validity is always DERIVED at verify time (see rsvp_resolve_token), never trusted from a flag.';
comment on column public.rsvp_tokens.token_hash is 'sha256(presented token string). Used both as an integrity cross-check after MAC-derived id lookup and as the rate-limit/attempt-log key (contract §1).';
comment on column public.rsvp_tokens.revoked_at is 'Set opportunistically on re-mint of a live token for the same (proposal_id, revision_at_mint, recipient_user_id); belt only -- derived revocation (generation/revision/status/membership) is what actually holds at verify time.';
comment on column public.rsvp_tokens.consumed_at is 'Stamped on the FIRST successful (outcome=applied) response through this token; a replay leaves it unchanged (contract: viewing is multi-view, responding is single-effective-use).';

-- At most one live token per (proposal, revision, recipient); a re-mint revokes its
-- predecessor before inserting the new row (contract §1: "the invite pattern").
create unique index rsvp_tokens_one_live_per_proposal_revision_recipient
    on public.rsvp_tokens (proposal_id, revision_at_mint, recipient_user_id)
    where revoked_at is null;

alter table public.rsvp_tokens enable row level security;
revoke all on table public.rsvp_tokens from public, anon, authenticated;
-- no policies at all (contract §3): reachable only from inside the SECURITY DEFINER RPCs
-- below, never via a direct client SELECT even under authenticated.

-- =====================================================================================
-- 2. rsvp_token_attempts (contract §A/§1/§3): unconditional attempt log backing both rate
--    limits (10/15min per token_hash, 60/15min per IP). No client access whatsoever.
-- =====================================================================================

create table public.rsvp_token_attempts (
    token_hash   bytea not null,
    client_ip    inet null,
    attempted_at timestamptz not null default now()
);

comment on table public.rsvp_token_attempts is 'Unconditional per-attempt log for the RSVP token verification path (council/stage3-web-rsvp-security-opus.md §1): every attempt is logged, including a forged/garbage token and an over-limit attempt, so a brute-force loop cannot dodge the limiter by varying the presented string.';

create index rsvp_token_attempts_hash_time_idx on public.rsvp_token_attempts (token_hash, attempted_at);
create index rsvp_token_attempts_ip_time_idx on public.rsvp_token_attempts (client_ip, attempted_at);

alter table public.rsvp_token_attempts enable row level security;
revoke all on table public.rsvp_token_attempts from public, anon, authenticated;
-- no policies: written only from inside rsvp_resolve_token (itself only reachable from the
-- two service_role-only RPCs below).

-- =====================================================================================
-- 3. Base32 (Crockford, lowercase, url-safe) codec -- generic arbitrary-length helper used
--    to present the token as one opaque string. Reuses the exact alphabet
--    pair_invite_code already established (0-9, A-Z minus I/L/O/U), lowercased, so the
--    house's "which characters can appear in a code" convention stays singular across
--    Stage 1 and Stage 3. Implemented via `numeric` (arbitrary precision) base conversion,
--    not bit-shifting a fixed-width integer, since a 16-byte (128-bit) value does not fit
--    in a bigint -- no extension dependency either way.
-- =====================================================================================

create or replace function public.rsvp_base32_encode(p_bytes bytea)
returns text
language plpgsql
immutable
set search_path = ''
as $$
declare
    v_alphabet text := '0123456789abcdefghjkmnpqrstvwxyz';
    v_num      numeric := 0;
    v_chars    int;
    v_out      text := '';
    v_rem      int;
    i          int;
begin
    if p_bytes is null then
        return null;
    end if;

    for i in 0 .. octet_length(p_bytes) - 1 loop
        v_num := v_num * 256 + get_byte(p_bytes, i);
    end loop;

    v_chars := ceil((octet_length(p_bytes) * 8) / 5.0)::int;

    for i in 1 .. v_chars loop
        v_rem := (v_num % 32)::int;
        v_out := substr(v_alphabet, v_rem + 1, 1) || v_out;
        v_num := div(v_num, 32);
    end loop;

    return v_out;
end;
$$;

comment on function public.rsvp_base32_encode(bytea) is 'Crockford-alphabet (lowercased) base32 encoding of an arbitrary byte string, via numeric base conversion (no fixed-width bit-shift limit). Not grant-reachable; pure/immutable helper.';

revoke all on function public.rsvp_base32_encode(bytea) from public;

create or replace function public.rsvp_base32_decode(p_text text, p_byte_len int)
returns bytea
language plpgsql
immutable
set search_path = ''
as $$
declare
    v_alphabet text := '0123456789abcdefghjkmnpqrstvwxyz';
    v_num      numeric := 0;
    v_idx      int;
    v_hex      text := '';
    v_bytes    int[] := '{}';
    i          int;
    v_char     text;
begin
    if p_text is null or length(p_text) = 0 or p_byte_len is null or p_byte_len < 1 then
        return null;
    end if;

    for i in 1 .. length(p_text) loop
        v_char := lower(substr(p_text, i, 1));
        v_idx := strpos(v_alphabet, v_char) - 1;
        if v_idx < 0 then
            return null; -- character outside the alphabet: not a valid encoding
        end if;
        v_num := v_num * 32 + v_idx;
    end loop;

    for i in 1 .. p_byte_len loop
        v_bytes := array_prepend((v_num % 256)::int, v_bytes);
        v_num := div(v_num, 256);
    end loop;

    for i in 1 .. p_byte_len loop
        v_hex := v_hex || lpad(to_hex(v_bytes[i]), 2, '0');
    end loop;

    return decode(v_hex, 'hex');
end;
$$;

comment on function public.rsvp_base32_decode(text, int) is 'Inverse of rsvp_base32_encode: decodes to exactly p_byte_len bytes, or NULL for any malformed/out-of-alphabet input (garbage never raises -- callers treat NULL as "invalid token", contract §1 no-presence-oracle rule). Not grant-reachable.';

revoke all on function public.rsvp_base32_decode(text, int) from public;

-- =====================================================================================
-- 4. Token derivation + presentation (contract §1: "token = base32url(token_id) || '.' ||
--    base32url(left(hmac(token_id, key, 'sha256'), 16))").
-- =====================================================================================

create or replace function public.rsvp_token_mac(p_token_id uuid)
returns bytea
language plpgsql
stable
set search_path = ''
as $$
declare
    v_key bytea;
begin
    select key into v_key from public.rsvp_token_hmac_keys where id;
    if v_key is null then
        raise exception 'rsvp token signing key is not configured' using errcode = 'XX000';
    end if;

    -- substr, not left(): Postgres has no left(bytea, n) (found on first live run at merge).
    return substr(extensions.hmac(convert_to(p_token_id::text, 'UTF8'), v_key, 'sha256'), 1, 16);
end;
$$;

comment on function public.rsvp_token_mac(uuid) is 'Deterministic 128-bit MAC over a token_id (council/stage3-web-rsvp-security-opus.md §1). Never granted to any role -- reachable only from inside the SECURITY DEFINER RPCs below (their bypass-RLS context is what lets this read rsvp_token_hmac_keys), exactly like pair_invite_code.';

revoke all on function public.rsvp_token_mac(uuid) from public;

create or replace function public.rsvp_token_encode(p_token_id uuid)
returns text
language sql
stable
set search_path = ''
as $$
    select public.rsvp_base32_encode(decode(replace(p_token_id::text, '-', ''), 'hex'))
        || '.'
        || public.rsvp_base32_encode(public.rsvp_token_mac(p_token_id));
$$;

comment on function public.rsvp_token_encode(uuid) is 'Builds the one presented token string for a token_id: base32(id-bytes) . base32(mac). Deterministic -- a mint replay re-derives the identical string without ever having stored it (contract §1/§2).';

revoke all on function public.rsvp_token_encode(uuid) from public;

-- Constant-time verification: recovers token_id from a presented string, or NULL for
-- anything malformed or forged -- BEFORE any table lookup (contract §1: "the MAC rejects
-- garbage before any row lookup (no presence oracle)"). Enumeration resistance via
-- double-HMAC compare with a per-call random nonce (bytea `=` is not constant-time).
create or replace function public.rsvp_verify_token_mac(p_token text)
returns uuid
language plpgsql
stable
set search_path = ''
as $$
declare
    v_dot_pos       int;
    v_id_part       text;
    v_mac_part      text;
    v_id_bytes      bytea;
    v_token_id      uuid;
    v_presented_mac bytea;
    v_expected_mac  bytea;
    v_nonce         bytea;
begin
    if p_token is null or length(p_token) = 0 or length(p_token) > 200 then
        return null;
    end if;

    v_dot_pos := position('.' in p_token);
    if v_dot_pos = 0 or v_dot_pos = 1 or v_dot_pos = length(p_token) then
        return null;
    end if;

    v_id_part  := substr(p_token, 1, v_dot_pos - 1);
    v_mac_part := substr(p_token, v_dot_pos + 1);

    v_id_bytes := public.rsvp_base32_decode(v_id_part, 16);
    if v_id_bytes is null or octet_length(v_id_bytes) <> 16 then
        return null;
    end if;

    begin
        v_token_id := encode(v_id_bytes, 'hex')::uuid;
    exception when others then
        return null;
    end;

    v_presented_mac := public.rsvp_base32_decode(v_mac_part, 16);
    if v_presented_mac is null or octet_length(v_presented_mac) <> 16 then
        return null;
    end if;

    v_expected_mac := public.rsvp_token_mac(v_token_id);

    v_nonce := extensions.gen_random_bytes(32);
    if extensions.hmac(v_presented_mac, v_nonce, 'sha256')
       <> extensions.hmac(v_expected_mac, v_nonce, 'sha256')
    then
        return null;
    end if;

    return v_token_id;
end;
$$;

comment on function public.rsvp_verify_token_mac(text) is 'Recovers token_id from a presented RSVP token string iff its MAC verifies (constant-time double-HMAC compare), else NULL. Never a row lookup, never an exception on garbage input -- callers fold NULL into the single opaque invalid_or_unavailable shape (contract §1).';

revoke all on function public.rsvp_verify_token_mac(text) from public;

-- =====================================================================================
-- 5. rsvp_resolve_token: the shared "token verification helper" (contract §A/§1) --
--    unconditional attempt logging, both rate limits, MAC-before-lookup, then the full
--    derived-revocation predicate list. Returns a discriminated state:
--      'opaque'               -- every pre-verification failure AND the two
--                                non-user-facing post-verification revocations (explicit
--                                revoke / membership-generation change) collapse here.
--      'live'                 -- fully valid; the caller may act on it.
--      'expired' | 'countered_since_mint' | 'already_accepted' | 'declined' | 'cancelled'
--                              -- rich, non-security-sensitive states (contract §1: "Rich
--                                state ... is returned only after MAC and row verification
--                                succeed").
-- =====================================================================================

create or replace function public.rsvp_resolve_token(
    p_token     text,
    p_client_ip text default null,
    out o_state    text,
    out o_token    public.rsvp_tokens,
    out o_proposal public.time_lock_proposals
)
language plpgsql
set search_path = ''
as $$
declare
    v_token_hash     bytea;
    v_ip             inet;
    v_token_attempts int;
    v_ip_attempts    int;
    v_token_id       uuid;
    v_pair           public.pairs;
    v_recipient_left boolean;
begin
    o_state := 'opaque';

    if p_token is null or length(p_token) = 0 then
        return; -- nothing to hash/log; opaque
    end if;

    v_token_hash := extensions.digest(convert_to(p_token, 'UTF8'), 'sha256');

    begin
        v_ip := p_client_ip::inet;
    exception when others then
        v_ip := null;
    end;

    -- Unconditional logging (contract §1): even a forged/garbage/oversized token is logged
    -- keyed by its own hash, so a brute-force loop cannot dodge the limiter by varying the
    -- string.
    insert into public.rsvp_token_attempts (token_hash, client_ip) values (v_token_hash, v_ip);

    select count(*) into v_token_attempts
    from public.rsvp_token_attempts
    where token_hash = v_token_hash and attempted_at > now() - interval '15 minutes';

    if v_token_attempts > 10 then
        return; -- opaque: rate-limited, identical shape to every other failure
    end if;

    if v_ip is not null then
        select count(*) into v_ip_attempts
        from public.rsvp_token_attempts
        where client_ip = v_ip and attempted_at > now() - interval '15 minutes';

        if v_ip_attempts > 60 then
            return;
        end if;
    end if;

    -- MAC verification BEFORE any row lookup (no presence oracle).
    v_token_id := public.rsvp_verify_token_mac(p_token);
    if v_token_id is null then
        return;
    end if;

    select * into o_token
    from public.rsvp_tokens
    where id = v_token_id and token_hash = v_token_hash;

    if not found then
        return;
    end if;

    select * into o_proposal from public.time_lock_proposals where id = o_token.proposal_id;
    if not found then
        return;
    end if;

    -- Derived revocation, part 1 (contract §1): explicit revoke (re-mint supersession) and
    -- pair membership/generation change. Neither has a safe user-facing label -- both
    -- collapse back to the single opaque shape, same as a forged token.
    if o_token.revoked_at is not null then
        return;
    end if;

    select * into v_pair from public.pairs where id = o_proposal.pair_id;
    if not found or v_pair.channel_generation <> o_token.channel_generation_at_mint then
        return;
    end if;

    select (pm.left_at is null) into v_recipient_left
    from public.pair_members pm
    where pm.pair_id = o_proposal.pair_id and pm.user_id = o_token.recipient_user_id
    order by pm.joined_at desc
    limit 1;

    if v_recipient_left is null or v_recipient_left is false then
        return;
    end if;

    -- Derived revocation, part 2: rich, non-security-sensitive proposal-lifecycle state
    -- (contract §1: these five are safe to disclose to a holder already bound to this
    -- exact proposal/revision/recipient). expires_at is the token's OWN TTL snapshot
    -- (= response_deadline at mint time); current_revision/status are the proposal's LIVE
    -- values, so a counter that also pushed the deadline out still surfaces as
    -- countered_since_mint OR expired depending on which condition the old snapshot hits
    -- first -- either way the token stops being 'live' ("stale twice over").
    if now() >= o_token.expires_at then
        o_state := 'expired';
    elsif o_proposal.current_revision <> o_token.revision_at_mint then
        o_state := 'countered_since_mint';
    elsif o_proposal.status in ('accepted', 'completed') then
        o_state := 'already_accepted';
    elsif o_proposal.status = 'declined' then
        o_state := 'declined';
    elsif o_proposal.status = 'cancelled' then
        o_state := 'cancelled';
    elsif o_proposal.status = 'expired' then
        o_state := 'expired';
    elsif o_proposal.status in ('proposed', 'countered') then
        o_state := 'live';
    else
        o_state := 'opaque';
    end if;
end;
$$;

comment on function public.rsvp_resolve_token(text, text) is 'The token verification helper (council/stage3-web-rsvp-security-opus.md §A): unconditional attempt logging, both rate limits, MAC-before-lookup, full derived-revocation predicate list. Not grant-reachable -- called only from inside rpc_get_rsvp_render_data and rpc_respond_proposal_web (both service_role-only, SECURITY DEFINER).';

revoke all on function public.rsvp_resolve_token(text, text) from public, anon, authenticated;

-- =====================================================================================
-- 6. rpc_mint_rsvp_token (contract §A/§2): authenticated; caller must be an active member
--    of the proposal's pair AND the current revision's author; recipient derived
--    server-side; TTL = response_deadline exactly; re-mint revokes the predecessor;
--    plaintext token never enters the receipt.
-- =====================================================================================

create or replace function public.rpc_mint_rsvp_token(
    p_operation_id uuid,
    p_proposal_id  uuid
)
returns jsonb
language plpgsql
security definer
set search_path = ''
as $$
declare
    v_caller     uuid := auth.uid();
    v_receipt    public.mutation_receipts;
    v_proposal   public.time_lock_proposals;
    v_pair       public.pairs;
    v_recipient  uuid;
    v_is_author  boolean;
    v_token_id   uuid;
    v_expires_at timestamptz;
    v_token_text text;
    v_token_hash bytea;
    v_result     jsonb;
begin
    if v_caller is null then
        raise exception 'authentication required' using errcode = '28000';
    end if;
    if p_operation_id is null then
        raise exception 'p_operation_id is required' using errcode = '22004';
    end if;
    if p_proposal_id is null then
        raise exception 'p_proposal_id is required' using errcode = '22004';
    end if;

    select * into v_receipt
    from public.mutation_receipts
    where owner_id = v_caller and operation_id = p_operation_id;

    if found then
        if (v_receipt.result ->> 'action') is distinct from 'mint_rsvp_token' then
            raise exception 'operation_id already used for a different action' using errcode = '22023';
        end if;
        -- Replay: re-derive the identical token from token_id (contract §2: "the plaintext
        -- token never enters a receipt") -- the receipt itself never stored it.
        v_token_id := (v_receipt.result ->> 'token_id')::uuid;
        return v_receipt.result || jsonb_build_object('token', public.rsvp_token_encode(v_token_id));
    end if;

    select * into v_proposal from public.time_lock_proposals where id = p_proposal_id for update;
    if not found then
        raise exception 'proposal not found' using errcode = 'P0002';
    end if;

    if not exists (
        select 1 from public.pair_members pm
        where pm.pair_id = v_proposal.pair_id and pm.user_id = v_caller and pm.left_at is null
    ) then
        raise exception 'caller is not an active member of this proposal''s pair' using errcode = '42501';
    end if;

    -- Expire-before-mutate (house convention).
    if v_proposal.status in ('proposed', 'countered') and v_proposal.response_deadline <= now() then
        update public.time_lock_proposals
        set status = 'expired', expired_at = now(), version = version + 1, updated_at = now()
        where id = v_proposal.id
        returning * into v_proposal;

        perform realtime.send(
            public.proposal_broadcast_payload(v_proposal),
            'pair.proposal_updated.v1',
            public.pair_topic(v_proposal.pair_id),
            true
        );
    end if;

    if v_proposal.status not in ('proposed', 'countered') then
        return jsonb_build_object(
            'outcome', 'conflict',
            'current_revision', v_proposal.current_revision,
            'status', v_proposal.status
        );
    end if;

    -- Only the CURRENT revision's author may mint a share link for it (contract §2); the
    -- recipient is derived as the other active member, never client-supplied.
    select exists (
        select 1 from public.proposal_revisions r
        where r.proposal_id = v_proposal.id
          and r.revision_no = v_proposal.current_revision
          and r.author_id = v_caller
    ) into v_is_author;

    if not v_is_author then
        raise exception 'only the author of the current revision may mint a share link' using errcode = '42501';
    end if;

    select pm.user_id into v_recipient
    from public.pair_members pm
    where pm.pair_id = v_proposal.pair_id and pm.user_id <> v_caller and pm.left_at is null;

    if v_recipient is null then
        raise exception 'proposal''s pair no longer has an active partner' using errcode = '42501';
    end if;

    select p.* into v_pair from public.pairs p where p.id = v_proposal.pair_id;

    -- Re-mint revokes any predecessor for the same (proposal, revision, recipient) --
    -- "at most one live token" (contract §1); the partial unique index backstops this.
    update public.rsvp_tokens
    set revoked_at = now()
    where proposal_id = v_proposal.id
      and revision_at_mint = v_proposal.current_revision
      and recipient_user_id = v_recipient
      and revoked_at is null;

    v_token_id   := gen_random_uuid();
    v_expires_at := v_proposal.response_deadline; -- TTL = response_deadline, exactly (contract §1)
    v_token_text := public.rsvp_token_encode(v_token_id);
    v_token_hash := extensions.digest(convert_to(v_token_text, 'UTF8'), 'sha256');

    insert into public.rsvp_tokens (
        id, proposal_id, recipient_user_id, minted_by, revision_at_mint,
        channel_generation_at_mint, token_hash, expires_at
    ) values (
        v_token_id, v_proposal.id, v_recipient, v_caller, v_proposal.current_revision,
        v_pair.channel_generation, v_token_hash, v_expires_at
    );

    v_result := jsonb_build_object(
        'outcome', 'applied',
        'action', 'mint_rsvp_token',
        'token_id', v_token_id,
        'token', v_token_text,
        'expires_at', public.iso_utc(v_expires_at),
        'discloses', jsonb_build_array('title', 'times', 'names')
    );

    -- The plaintext token never enters a receipt (contract §1/§2): persist everything
    -- EXCEPT 'token', re-derived deterministically from token_id on replay (above).
    insert into public.mutation_receipts (owner_id, operation_id, result_version, result)
    values (v_caller, p_operation_id, v_proposal.version, v_result - 'token');

    return v_result;
end;
$$;

comment on function public.rpc_mint_rsvp_token(uuid, uuid) is 'Mints (or re-derives, on replay) a share-link RSVP token for the proposal''s current revision (council/stage3-web-rsvp-security-opus.md §2). Caller must be an active pair member AND the current revision''s author; recipient is derived server-side, never client-supplied.';

revoke all on function public.rpc_mint_rsvp_token(uuid, uuid) from public, anon;
grant execute on function public.rpc_mint_rsvp_token(uuid, uuid) to authenticated;

-- =====================================================================================
-- 7. rpc_get_rsvp_render_data (contract §A/§2): service_role only. Returns EXACTLY the §A
--    disclosure set for the CURRENT revision -- title, proposer+recipient display names
--    (LEFT JOIN profiles, 'Someone' fallback), recipient home_tz + proposer origin_tz,
--    current-revision candidates, deadline, state. Nothing else -- no ids, no pair_id, no
--    revision history, no responses/commitments/conflict hints.
-- =====================================================================================

create or replace function public.rpc_get_rsvp_render_data(
    p_token     text,
    p_client_ip text default null
)
returns jsonb
language plpgsql
security definer
set search_path = ''
as $$
declare
    v_state     text;
    v_token_row public.rsvp_tokens;
    v_proposal  public.time_lock_proposals;
    v_resolved  record;
    v_result    jsonb;
begin
    -- PL/pgSQL forbids composite targets in a multi-column INTO list; unpack via a
    -- record variable instead (found on first live run at merge, 2026-09-12).
    select * into v_resolved from public.rsvp_resolve_token(p_token, p_client_ip);
    v_state := v_resolved.o_state;
    v_token_row := v_resolved.o_token;
    v_proposal := v_resolved.o_proposal;

    if v_state = 'opaque' or v_state is null then
        return jsonb_build_object('outcome', 'invalid_or_unavailable');
    end if;

    select jsonb_build_object(
        'outcome', 'ok',
        'state', v_state,
        'title', v_proposal.title,
        'proposer_display_name', coalesce(prof_proposer.display_name, 'Someone'),
        'recipient_display_name', coalesce(prof_recipient.display_name, 'Someone'),
        'recipient_home_tz', coalesce(prof_recipient.home_tz, 'Etc/UTC'),
        'proposer_origin_tz', v_revision.origin_tz,
        'candidates', v_revision.candidates,
        'deadline', public.iso_utc(v_proposal.response_deadline)
    )
    into v_result
    from public.proposal_revisions v_revision
    left join public.profiles prof_proposer on prof_proposer.user_id = v_revision.author_id
    left join public.profiles prof_recipient on prof_recipient.user_id = v_token_row.recipient_user_id
    where v_revision.proposal_id = v_proposal.id
      and v_revision.revision_no = v_proposal.current_revision;

    return v_result;
end;
$$;

comment on function public.rpc_get_rsvp_render_data(text, text) is 'service_role-only render-data read for the web RSVP page (council/stage3-web-rsvp-security-opus.md §A leaked-link threat model): returns ONLY {title, proposer_display_name, recipient_display_name, recipient_home_tz, proposer_origin_tz, candidates, deadline, state} for a live/rich-state token, or the single opaque {"outcome":"invalid_or_unavailable"} shape otherwise. Never pair_id, any user uuid, revision history, responses, commitments, or conflict hints.';

revoke all on function public.rpc_get_rsvp_render_data(text, text) from public, anon, authenticated;
grant execute on function public.rpc_get_rsvp_render_data(text, text) to service_role;

-- =====================================================================================
-- 8. rsvp_web_operation_id (contract §A/§1): deterministic per-token operation_id for the
--    respond path -- see the migration-header UNVERIFIED note #2 for why this is derived
--    from token_id alone rather than varying by the caller's action.
-- =====================================================================================

create or replace function public.rsvp_web_operation_id(p_token_id uuid)
returns uuid
language plpgsql
stable
set search_path = ''
as $$
declare
    v_hash    text;
    v_nibble  int;
    v_variant int;
begin
    v_hash := encode(
        extensions.digest(convert_to('elay:rsvp_respond:' || p_token_id::text, 'UTF8'), 'sha256'),
        'hex'
    );

    -- Force RFC-4122-shaped version (5) and variant (10xx) nibbles so the value is a
    -- well-formed uuid literal; this is a deterministic v5-STYLE derivation via SHA-256; it
    -- is not the literal RFC 4122 UUIDv5 algorithm (SHA-1 over a namespace+name), chosen
    -- because uuid-ossp's real uuid_generate_v5 is unconfirmed on this local stack (see
    -- migration header UNVERIFIED #1).
    v_nibble  := strpos('0123456789abcdef', substr(v_hash, 17, 1)) - 1;
    v_variant := 8 | (v_nibble & 3);

    return (
        substr(v_hash, 1, 8) || '-' ||
        substr(v_hash, 9, 4) || '-' ||
        '5' || substr(v_hash, 14, 3) || '-' ||
        to_hex(v_variant) || substr(v_hash, 18, 3) || '-' ||
        substr(v_hash, 21, 12)
    )::uuid;
end;
$$;

comment on function public.rsvp_web_operation_id(uuid) is 'Deterministic operation_id for the web respond path, bound to token_id alone (see migration header UNVERIFIED #2): a double-submit of the SAME action replays rpc_respond_proposal''s own receipt byte-identically, and a DIFFERENT action against the same token collides on this same operation_id and hits rpc_respond_proposal''s cross-action 22023 guard.';

revoke all on function public.rsvp_web_operation_id(uuid) from public, anon, authenticated;

-- =====================================================================================
-- 9. rpc_respond_proposal_web (contract §A/§2): service_role only. Verifies the token,
--    impersonates the bound recipient transaction-locally, and calls
--    public.rpc_respond_proposal UNCHANGED -- no second response implementation, no
--    client-supplied recipient/proposal id, no web-mintable counter.
-- =====================================================================================

create or replace function public.rpc_respond_proposal_web(
    p_token          text,
    p_action         text,
    p_candidate_idx  int default null,
    p_new_origin_tz  text default null,
    p_new_deadline   timestamptz default null,
    p_new_candidates jsonb default null,
    p_client_ip      text default null
)
returns jsonb
language plpgsql
security definer
set search_path = ''
as $$
declare
    v_state         text;
    v_token_row     public.rsvp_tokens;
    v_proposal      public.time_lock_proposals;
    v_resolved      record;
    v_operation_id  uuid;
    v_saved_claims  text;
    v_result        jsonb;
begin
    -- Web counter mints nothing (contract §2: it would let a link holder manufacture a
    -- fresh capability -- a privilege-escalation loop). Only accept/decline are reachable
    -- through this path; rejected before any token is even looked at.
    if p_action not in ('accept', 'decline') then
        raise exception 'p_action must be accept or decline (web counter is not permitted)'
            using errcode = '22023';
    end if;

    -- PL/pgSQL forbids composite targets in a multi-column INTO list; unpack via a
    -- record variable instead (found on first live run at merge, 2026-09-12).
    select * into v_resolved from public.rsvp_resolve_token(p_token, p_client_ip);
    v_state := v_resolved.o_state;
    v_token_row := v_resolved.o_token;
    v_proposal := v_resolved.o_proposal;

    if v_state is null or v_state = 'opaque' then
        return jsonb_build_object('outcome', 'invalid_or_unavailable');
    end if;

    v_operation_id := public.rsvp_web_operation_id(v_token_row.id);

    if v_state <> 'live' then
        -- Receipt replay FIRST (contract §1: "A double-submit therefore replays the receipt
        -- and returns the identical {"outcome":"applied",…}"). After a successful web accept
        -- the proposal is 'accepted', so the resolver reports already_accepted -- but if THIS
        -- token's own response is what got it there, the double-submit must return the
        -- byte-identical applied envelope, not a conflict (found by T-WEB-REPLAY on the first
        -- live run at merge, 2026-09-12).
        select r.result into v_result
        from public.mutation_receipts r
        where r.owner_id = v_token_row.recipient_user_id
          and r.operation_id = v_operation_id;
        if found and (v_result ->> 'outcome') = 'applied' then
            if v_token_row.consumed_action = p_action then
                return v_result;
            end if;
            -- Same token, DIFFERENT action ("accept, then decline from the back button"):
            -- the shipped cross-action guard's behavior, surfaced here since the inner RPC
            -- is no longer reached once the proposal left the live states (contract §1).
            raise exception 'operation_id already used for a different action'
                using errcode = '22023';
        end if;
        -- Verified row, but not currently actionable (expired / countered_since_mint /
        -- already_accepted / declined / cancelled): the SAME conflict envelope shape
        -- rpc_respond_proposal itself returns for a stale call, so the page's existing
        -- conflict handling (contract §2: "map the shipped shape exactly") covers this too.
        return jsonb_build_object(
            'outcome', 'conflict',
            'current_revision', v_proposal.current_revision,
            'status', v_proposal.status
        );
    end if;

    -- Transaction-local impersonation of the token's bound recipient (contract §2). This is
    -- transaction-scoped (set_config(..., true)), not call-scoped, so it is explicitly
    -- saved and restored around the nested call -- the Stage 1 lesson
    -- (pair_members_broadcast's elay.pair_broadcast_topic stash) applied here to
    -- request.jwt.claims, so a lingering impersonation can never leak into whatever runs
    -- next on the same connection/transaction.
    v_saved_claims := current_setting('request.jwt.claims', true);

    perform set_config(
        'request.jwt.claims',
        jsonb_build_object('sub', v_token_row.recipient_user_id, 'role', 'authenticated')::text,
        true
    );

    v_result := public.rpc_respond_proposal(
        v_operation_id,
        v_token_row.proposal_id,
        v_token_row.revision_at_mint,
        p_action,
        p_candidate_idx,
        p_new_origin_tz,
        p_new_deadline,
        p_new_candidates
    );

    perform set_config('request.jwt.claims', coalesce(v_saved_claims, ''), true);

    if (v_result ->> 'outcome') = 'applied' then
        update public.rsvp_tokens
        set consumed_at = coalesce(consumed_at, now()),
            consumed_action = coalesce(consumed_action, p_action)
        where id = v_token_row.id;
    end if;

    return v_result;
end;
$$;

comment on function public.rpc_respond_proposal_web(text, text, int, text, timestamptz, jsonb, text) is 'service_role-only web respond path (council/stage3-web-rsvp-security-opus.md §A/§2). Verifies the token, impersonates the bound recipient transaction-locally, and calls public.rpc_respond_proposal UNCHANGED -- returns its envelope verbatim. The token is the only accepted identifier; there is no p_proposal_id/p_recipient parameter to mismatch.';

revoke all on function public.rpc_respond_proposal_web(text, text, int, text, timestamptz, jsonb, text) from public, anon, authenticated;
grant execute on function public.rpc_respond_proposal_web(text, text, int, text, timestamptz, jsonb, text) to service_role;
