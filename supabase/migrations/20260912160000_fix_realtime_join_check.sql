-- Stage 2 fix (lead, live E2E 2026-09-12): private-channel joins were rejected by the
-- Realtime server ("Unauthorized: You do not have permissions to read from this Channel
-- topic") even though the pair_broadcast_select policy passed every manual RLS check.
--
-- Root cause, proven by logging from inside Realtime's own authorization transaction
-- (RAISE LOG in a temporary debug policy): the probe row Realtime inserts to test read
-- access has `private = false` and `event = null`. Stage 1's policy required
-- `private is true`, so the probe row never matched, the join check computed read=false,
-- and every subscribe was refused — no pair broadcast (membership OR proposal events) was
-- ever delivered live. With the conjunct removed the same client received
-- pair.proposal_created.v1 end-to-end on an untouched foregrounded screen.
--
-- Security is unchanged: the scope is `realtime.topic() = caller_active_pair_topic()`,
-- which derives ONLY from the caller's own active membership (a non-member gets null and
-- matches nothing). The `private` column of a row was never load-bearing for that.
-- Rule for future policies on realtime.messages: never predicate on `private` or `event`
-- — Realtime's join probe rows do not carry them.

drop policy if exists pair_broadcast_select on realtime.messages;
create policy pair_broadcast_select on realtime.messages
    for select
    to authenticated
    using (
        extension = 'broadcast'
        and realtime.topic() = public.caller_active_pair_topic()
    );
