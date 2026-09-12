-- Stage 2 fix (lead, live E2E 2026-09-12): rpc_list_proposals must return the SAME
-- participant-safe projection as rpc_get_proposal — "snapshots, revisions, responses, and
-- caller-owned commitment" (council/stage2-timelock-sol.md §2, contracts/stage2-timelock.md).
-- 20260912140000 shipped list items as the bare core shape only; the client's accepted/
-- incoming cards render from revisions[].candidates, so every card silently dropped
-- (ProposalSummaryDto defaults revisions to [] and the card mapper returns null without a
-- max revision). Same signature, pagination, scoping and grants as before — each item just
-- gains the three keys rpc_get_proposal already appends.

create or replace function public.rpc_list_proposals(
    p_scope  text default 'active',
    p_cursor text default null
)
returns jsonb
language plpgsql
security definer
set search_path = ''
as $$
declare
    v_caller  uuid := auth.uid();
    v_pair_id uuid;
    v_statuses text[];
    v_cursor_updated_at timestamptz := null;
    v_cursor_id uuid := null;
    v_page_size constant int := 20;
    v_items jsonb;
    v_next_cursor text := null;
    v_last_updated_at timestamptz;
    v_last_id uuid;
    v_count int;
begin
    if v_caller is null then
        raise exception 'authentication required' using errcode = '28000';
    end if;
    if p_scope not in ('active', 'history') then
        raise exception 'p_scope must be active or history' using errcode = '22023';
    end if;

    v_statuses := case p_scope
        when 'active' then array['proposed', 'countered', 'accepted']
        else array['declined', 'expired', 'cancelled', 'completed']
    end;

    select pm.pair_id into v_pair_id
    from public.pair_members pm
    where pm.user_id = v_caller and pm.left_at is null;

    if v_pair_id is null then
        return jsonb_build_object('items', '[]'::jsonb, 'next_cursor', null);
    end if;

    if p_cursor is not null then
        v_cursor_updated_at := split_part(p_cursor, ',', 1)::timestamptz;
        v_cursor_id := split_part(p_cursor, ',', 2)::uuid;
    end if;

    with page as (
        select * from public.time_lock_proposals p
        where p.pair_id = v_pair_id
          and p.status = any (v_statuses)
          and (
              p_cursor is null
              or (p.updated_at, p.id) < (v_cursor_updated_at, v_cursor_id)
          )
        order by p.updated_at desc, p.id desc
        limit v_page_size
    )
    select
        jsonb_agg(
            public.time_lock_proposal_to_jsonb(page) || jsonb_build_object(
                'revisions', coalesce((
                    select jsonb_agg(public.proposal_revision_to_jsonb(r) order by r.revision_no)
                    from public.proposal_revisions r
                    where r.proposal_id = page.id
                ), '[]'::jsonb),
                'responses', coalesce((
                    select jsonb_agg(public.proposal_response_to_jsonb(resp) order by resp.responded_at)
                    from public.proposal_responses resp
                    where resp.proposal_id = page.id
                ), '[]'::jsonb),
                'my_commitment', (
                    select public.commitment_to_jsonb(c)
                    from public.commitments c
                    where c.proposal_id = page.id and c.user_id = v_caller
                )
            )
            order by page.updated_at desc, page.id desc
        ),
        count(*),
        (array_agg(page.updated_at order by page.updated_at desc, page.id desc))[v_page_size],
        (array_agg(page.id order by page.updated_at desc, page.id desc))[v_page_size]
    into v_items, v_count, v_last_updated_at, v_last_id
    from page;

    if v_count = v_page_size then
        v_next_cursor := public.iso_utc(v_last_updated_at) || ',' || v_last_id::text;
    end if;

    return jsonb_build_object('items', coalesce(v_items, '[]'::jsonb), 'next_cursor', v_next_cursor);
end;
$$;

-- create or replace keeps the existing ACL, but restate it explicitly (house rule: Supabase
-- auto-grants new functions to anon — never rely on inherited state).
revoke all on function public.rpc_list_proposals(text, text) from public, anon;
grant execute on function public.rpc_list_proposals(text, text) to authenticated;
