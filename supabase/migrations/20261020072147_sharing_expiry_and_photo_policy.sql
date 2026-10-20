-- Additive: existing links remain available until explicitly revoked.
alter table public.sanpo_shares add column if not exists expires_at timestamptz;
create or replace function public.create_sanpo_share_v2(
 p_route_local_id bigint, p_name text, p_started_at timestamptz, p_duration_ms bigint,
 p_distance_meters double precision, p_points jsonb, p_note text, p_share_map boolean,
 p_expires_at timestamptz
) returns text language plpgsql security invoker set search_path = '' as $$
declare token_value text;
begin
 if not public.sanpo_session_active_v1() then raise exception 'authenticated session required'; end if;
 if p_expires_at is not null and (p_expires_at <= now() or p_expires_at > now()+interval '366 days') then raise exception 'invalid expiry'; end if;
 token_value := public.create_sanpo_share_v1(p_route_local_id,p_name,p_started_at,p_duration_ms,p_distance_meters,p_points,p_note,p_share_map);
 update public.sanpo_shares set expires_at=p_expires_at where token=token_value and owner_id=auth.uid();
 if not found then raise exception 'share not owned'; end if;
 return token_value;
end; $$;
revoke all on function public.create_sanpo_share_v2(bigint,text,timestamptz,bigint,double precision,jsonb,text,boolean,timestamptz) from public, anon;
grant execute on function public.create_sanpo_share_v2(bigint,text,timestamptz,bigint,double precision,jsonb,text,boolean,timestamptz) to authenticated;
-- Keep the existing public lookup contract, but enforce expiry on the server.
create or replace function public.get_sanpo_share(share_token text) returns jsonb
language sql stable security definer set search_path = '' as $$
 select jsonb_build_object('name',s.name,'started_at',s.started_at,'duration_ms',s.duration_ms,
  'distance_meters',s.distance_meters,'points',case when s.show_map then s.points else '[]'::jsonb end,
  'note',s.note,'show_map',s.show_map)
 from public.sanpo_shares s where s.token=share_token and (s.expires_at is null or s.expires_at > now())
 and not exists(select 1 from public.sanpo_routes r where r.user_id=s.owner_id and r.local_id=s.route_local_id and r.deleted_at is not null)
 limit 1;
$$;
-- name must refer to the Storage object, not the route's name column.
drop policy if exists sanpo_photo_live_insert_v1 on storage.objects;
create policy sanpo_photo_live_insert_v1 on storage.objects as restrictive for insert to authenticated
with check (bucket_id <> 'walk-photos' or (public.sanpo_session_active_v1()
 and split_part(storage.objects.name,'/',1)=auth.uid()::text and not exists (
 select 1 from public.sanpo_routes r where r.user_id=auth.uid()
 and r.local_id::text=split_part(split_part(storage.objects.name,'/',2),'.',1) and r.deleted_at is not null)));
drop policy if exists sanpo_photo_live_update_v1 on storage.objects;
create policy sanpo_photo_live_update_v1 on storage.objects as restrictive for update to authenticated
using (bucket_id <> 'walk-photos' or (public.sanpo_session_active_v1() and split_part(storage.objects.name,'/',1)=auth.uid()::text))
with check (bucket_id <> 'walk-photos' or (public.sanpo_session_active_v1()
 and split_part(storage.objects.name,'/',1)=auth.uid()::text and not exists (
 select 1 from public.sanpo_routes r where r.user_id=auth.uid()
 and r.local_id::text=split_part(split_part(storage.objects.name,'/',2),'.',1) and r.deleted_at is not null)));

alter table public.sanpo_routes add column if not exists photo_hash text;
alter table public.sanpo_routes add column if not exists source_route_id bigint;
alter table public.sanpo_routes add column if not exists rest_duration_ms bigint not null default 0;
create or replace function public.upsert_sanpo_route_v2(p_route jsonb) returns void
language plpgsql security invoker set search_path = '' as $$
begin
 perform public.upsert_sanpo_route_v1(p_route);
 update public.sanpo_routes set photo_hash=p_route->>'photo_hash',
 source_route_id=(p_route->>'source_route_id')::bigint,
 rest_duration_ms=greatest(0,coalesce((p_route->>'rest_duration_ms')::bigint,0))
 where user_id=auth.uid() and local_id=(p_route->>'local_id')::bigint
 and client_updated_at=(p_route->>'client_updated_at')::bigint and deleted_at is null;
end; $$;
revoke all on function public.upsert_sanpo_route_v2(jsonb) from public, anon;
grant execute on function public.upsert_sanpo_route_v2(jsonb) to authenticated;
