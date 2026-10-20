-- Apply after CR10 migrations. Additive; existing records remain readable.
alter table public.sanpo_routes add column if not exists tags jsonb not null default '[]';
alter table public.sanpo_routes add column if not exists imported boolean not null default false;
alter table public.sanpo_routes add column if not exists import_key text;
alter table public.sanpo_routes add column if not exists gps_quality jsonb;
alter table public.sanpo_routes add column if not exists rest_intervals jsonb not null default '[]';

create or replace function public.upsert_sanpo_route_v3(p_route jsonb) returns void
language plpgsql security invoker set search_path = '' as $$
begin
 if not public.sanpo_session_active_v1() then raise exception 'authenticated session required'; end if;
 if jsonb_typeof(coalesce(p_route->'tags','[]')) <> 'array' or jsonb_array_length(coalesce(p_route->'tags','[]')) > 20
 or jsonb_typeof(coalesce(p_route->'rest_intervals','[]')) <> 'array' then raise exception 'invalid metadata'; end if;
 perform public.upsert_sanpo_route_v2(p_route);
 update public.sanpo_routes set tags=coalesce(p_route->'tags','[]'),
 imported=imported or coalesce((p_route->>'imported')::boolean,false),
 import_key=coalesce(import_key,p_route->>'import_key'), gps_quality=nullif(p_route->'gps_quality','null'),
 rest_intervals=coalesce(p_route->'rest_intervals','[]')
 where user_id=auth.uid() and local_id=(p_route->>'local_id')::bigint
 and client_updated_at=(p_route->>'client_updated_at')::bigint and deleted_at is null;
end; $$;
revoke all on function public.upsert_sanpo_route_v3(jsonb) from public, anon;
grant execute on function public.upsert_sanpo_route_v3(jsonb) to authenticated;

-- Preserve import provenance even when an older client updates the same route.
create or replace function sanpo_private.preserve_import_origin_v3() returns trigger
language plpgsql security invoker set search_path = '' as $$
begin
 new.imported := old.imported or new.imported;
 new.import_key := coalesce(old.import_key,new.import_key);
 return new;
end; $$;
create trigger preserve_import_origin_v3 before update on public.sanpo_routes
for each row execute function sanpo_private.preserve_import_origin_v3();
