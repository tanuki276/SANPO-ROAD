create schema if not exists sanpo_private;
revoke all on schema sanpo_private from public, anon;
-- Additive v1 preparation. Existing routes and shares are retained.
alter table public.sanpo_routes add column if not exists client_updated_at bigint not null default 0;
alter table public.sanpo_routes add column if not exists deleted_at bigint;
alter table public.sanpo_routes add column if not exists favorite boolean not null default false;
create index if not exists sanpo_shares_owner_route_idx on public.sanpo_shares(owner_id, route_local_id);

create or replace function sanpo_private.sanpo_session_active_v1() returns boolean
language sql stable security definer set search_path = '' as $$
  select auth.uid() is not null
    and not coalesce((auth.jwt()->>'is_anonymous')::boolean, false)
    and exists(select 1 from auth.sessions where user_id = auth.uid() and id::text = auth.jwt()->>'session_id');
$$;
revoke all on function sanpo_private.sanpo_session_active_v1() from public, anon;
grant usage on schema sanpo_private to authenticated;
grant execute on function sanpo_private.sanpo_session_active_v1() to authenticated;
create or replace function public.sanpo_session_active_v1() returns boolean
language sql stable security invoker set search_path = '' as $$ select sanpo_private.sanpo_session_active_v1(); $$;
revoke all on function public.sanpo_session_active_v1() from public, anon;
grant execute on function public.sanpo_session_active_v1() to authenticated;

create or replace function public.upsert_sanpo_route_v1(p_route jsonb) returns void
language plpgsql security invoker set search_path = '' as $$
declare
  uid uuid := auth.uid();
  rid bigint := (p_route->>'local_id')::bigint;
  stamp bigint := (p_route->>'client_updated_at')::bigint;
  deleted bigint := (p_route->>'deleted_at')::bigint;
  photo text := p_route->>'photo_object_path';
begin
  if not public.sanpo_session_active_v1() then raise exception 'authenticated session required'; end if;
  if (p_route->>'user_id')::uuid is distinct from uid or rid <= 0 or stamp is null or stamp < 0 then raise exception 'invalid identity'; end if;
  if photo is not null and split_part(photo, '/', 1) <> uid::text then raise exception 'invalid photo owner'; end if;
  if jsonb_typeof(p_route->'points') is distinct from 'array' or octet_length(p_route::text) > 4000000 then raise exception 'invalid route'; end if;
  insert into public.sanpo_routes(user_id, local_id, name, started_at, duration_ms, distance_meters, points, note, photo_object_path, client_updated_at, deleted_at, favorite)
  values(uid, rid, case when deleted is null then p_route->>'name' else '削除済み' end,
    (p_route->>'started_at')::timestamptz,
    case when deleted is null then (p_route->>'duration_ms')::bigint else 0 end,
    case when deleted is null then (p_route->>'distance_meters')::double precision else 0 end,
    case when deleted is null then p_route->'points' else '[]'::jsonb end,
    case when deleted is null then coalesce(p_route->>'note','') else '' end,
    case when deleted is null then photo else null end, stamp, deleted, coalesce((p_route->>'favorite')::boolean,false))
  on conflict(user_id,local_id) do update set
    name=excluded.name, started_at=excluded.started_at, duration_ms=excluded.duration_ms,
    distance_meters=excluded.distance_meters, points=excluded.points, note=excluded.note,
    photo_object_path=excluded.photo_object_path, client_updated_at=excluded.client_updated_at,
    deleted_at=excluded.deleted_at, favorite=excluded.favorite, updated_at=now()
  where sanpo_routes.deleted_at is null and (excluded.deleted_at is not null or excluded.client_updated_at >= sanpo_routes.client_updated_at);
  if deleted is not null then delete from public.sanpo_shares where owner_id=uid and route_local_id=rid; end if;
end; $$;
revoke all on function public.upsert_sanpo_route_v1(jsonb) from public, anon;
grant execute on function public.upsert_sanpo_route_v1(jsonb) to authenticated;

-- Old clients must not resurrect a tombstone or overwrite a newer version.
create or replace function sanpo_private.sanpo_guard_version_v1() returns trigger
language plpgsql security invoker set search_path = '' as $$
begin
  if old.deleted_at is not null or (new.deleted_at is null and new.client_updated_at < old.client_updated_at) then return old; end if;
  return new;
end; $$;
create or replace trigger sanpo_guard_version_v1 before update on public.sanpo_routes
for each row execute function sanpo_private.sanpo_guard_version_v1();

create or replace function public.create_sanpo_share_v1(
 p_route_local_id bigint, p_name text, p_started_at timestamptz, p_duration_ms bigint,
 p_distance_meters double precision, p_points jsonb, p_note text, p_share_map boolean
) returns text language plpgsql security invoker set search_path = '' as $$
begin
  if not public.sanpo_session_active_v1() then raise exception 'authenticated session required'; end if;
  if exists(select 1 from public.sanpo_routes where user_id=auth.uid() and local_id=p_route_local_id and deleted_at is not null) then raise exception 'route deleted'; end if;
  return public.create_sanpo_share(p_route_local_id,p_name,p_started_at,p_duration_ms,p_distance_meters,
    case when p_share_map then p_points else '[]'::jsonb end,p_note,coalesce(p_share_map,false));
end; $$;
revoke all on function public.create_sanpo_share_v1(bigint,text,timestamptz,bigint,double precision,jsonb,text,boolean) from public, anon;
grant execute on function public.create_sanpo_share_v1(bigint,text,timestamptz,bigint,double precision,jsonb,text,boolean) to authenticated;

-- The Auth project is shared with other applications: refuse deletion if any other
-- table references this account, rather than cascading into unrelated app data.
create or replace function sanpo_private.sanpo_can_delete_account_v1() returns boolean
language plpgsql security definer set search_path = '' as $$
declare fk record; referenced boolean; uid uuid := auth.uid();
begin
  if not sanpo_private.sanpo_session_active_v1() then return false; end if;
  for fk in
    select n.nspname, c.relname, a.attname from pg_catalog.pg_constraint k
    join pg_catalog.pg_class c on c.oid=k.conrelid
    join pg_catalog.pg_namespace n on n.oid=c.relnamespace
    join pg_catalog.pg_attribute a on a.attrelid=c.oid and a.attnum=k.conkey[1]
    where k.contype='f' and k.confrelid='auth.users'::regclass
      and n.nspname <> 'auth' and not (n.nspname='public' and c.relname in ('sanpo_routes','sanpo_shares'))
  loop
    execute format('select exists(select 1 from %I.%I where %I::text = $1)',fk.nspname,fk.relname,fk.attname) into referenced using uid::text;
    if referenced then return false; end if;
  end loop;
  if exists(select 1 from storage.objects where owner_id=uid::text and bucket_id <> 'walk-photos') then return false; end if;
  if exists(select 1 from public.codug_results where player_id=uid) or exists(select 1 from public.codug_runs where player_id=uid) then return false; end if;
  return true;
end; $$;
revoke all on function sanpo_private.sanpo_can_delete_account_v1() from public, anon;
grant execute on function sanpo_private.sanpo_can_delete_account_v1() to authenticated;
create or replace function public.sanpo_can_delete_account_v1() returns boolean
language sql stable security invoker set search_path = '' as $$ select sanpo_private.sanpo_can_delete_account_v1(); $$;
revoke all on function public.sanpo_can_delete_account_v1() from public, anon;
grant execute on function public.sanpo_can_delete_account_v1() to authenticated;

-- A revoked session or deleted walk cannot upload/recreate a photo.
drop policy if exists sanpo_photo_live_insert_v1 on storage.objects;
create policy sanpo_photo_live_insert_v1 on storage.objects as restrictive for insert to authenticated
with check (bucket_id <> 'walk-photos' or (public.sanpo_session_active_v1() and not exists(
 select 1 from public.sanpo_routes r where r.user_id=auth.uid() and r.local_id::text=split_part(split_part(name,'/',2),'.',1) and r.deleted_at is not null
)));
drop policy if exists sanpo_photo_live_update_v1 on storage.objects;
create policy sanpo_photo_live_update_v1 on storage.objects as restrictive for update to authenticated
using (bucket_id <> 'walk-photos' or public.sanpo_session_active_v1())
with check (bucket_id <> 'walk-photos' or (public.sanpo_session_active_v1() and not exists(
 select 1 from public.sanpo_routes r where r.user_id=auth.uid() and r.local_id::text=split_part(split_part(name,'/',2),'.',1) and r.deleted_at is not null
)));
