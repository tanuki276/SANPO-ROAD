-- Rotate shares atomically. An exception (including validation/rate limiting)
-- rolls back the deletion, preserving the previous URL. Serialize per owner/walk.
create or replace function public.create_sanpo_share_v2(
 p_route_local_id bigint, p_name text, p_started_at timestamptz, p_duration_ms bigint,
 p_distance_meters double precision, p_points jsonb, p_note text, p_share_map boolean,
 p_expires_at timestamptz
) returns text language plpgsql security invoker set search_path = '' as $$
declare token_value text;
begin
 if not public.sanpo_session_active_v1() then raise exception 'authenticated session required'; end if;
 if p_expires_at is not null and (p_expires_at <= now() or p_expires_at > now()+interval '366 days') then raise exception 'invalid expiry'; end if;
 perform pg_catalog.pg_advisory_xact_lock(pg_catalog.hashtextextended(auth.uid()::text || ':' || p_route_local_id::text, 0));
 delete from public.sanpo_shares where owner_id=auth.uid() and route_local_id=p_route_local_id;
 token_value := public.create_sanpo_share_v1(p_route_local_id,p_name,p_started_at,p_duration_ms,p_distance_meters,p_points,p_note,p_share_map);
 update public.sanpo_shares set expires_at=p_expires_at where token=token_value and owner_id=auth.uid();
 if not found then raise exception 'share not owned'; end if;
 return token_value;
end; $$;
revoke all on function public.create_sanpo_share_v2(bigint,text,timestamptz,bigint,double precision,jsonb,text,boolean,timestamptz) from public, anon;
grant execute on function public.create_sanpo_share_v2(bigint,text,timestamptz,bigint,double precision,jsonb,text,boolean,timestamptz) to authenticated;

alter table public.sanpo_routes add column if not exists distance_goal_meters double precision
 check (distance_goal_meters > 0 and distance_goal_meters <= 1000000);
create or replace function public.upsert_sanpo_route_v4(p_route jsonb) returns void
language plpgsql security invoker set search_path = '' as $$
begin
 perform public.upsert_sanpo_route_v3(p_route);
 update public.sanpo_routes set distance_goal_meters=(p_route->>'distance_goal_meters')::double precision
 where user_id=auth.uid() and local_id=(p_route->>'local_id')::bigint
 and client_updated_at=(p_route->>'client_updated_at')::bigint and deleted_at is null
 and p_route ? 'distance_goal_meters';
end; $$;
revoke all on function public.upsert_sanpo_route_v4(jsonb) from public, anon;
grant execute on function public.upsert_sanpo_route_v4(jsonb) to authenticated;
