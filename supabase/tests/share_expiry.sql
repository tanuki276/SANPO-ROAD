-- Run after the v2 migration, inside a transaction and roll back.
do $$
declare t text := replace(gen_random_uuid()::text,'-','');
begin
 insert into public.sanpo_shares(token,owner_id,route_local_id,name,started_at,duration_ms,distance_meters,points,note,show_map,expires_at)
 values(t,null,9223372036854770000,'expiry-test',now(),1000,1,'[]','',false,now()-interval '1 second');
 if public.get_sanpo_share(t) is not null then raise exception 'expired link is visible'; end if;
 update public.sanpo_shares set expires_at=now()+interval '1 day' where token=t;
 if public.get_sanpo_share(t) is null then raise exception 'active link is hidden'; end if;
 delete from public.sanpo_shares where token=t;
 if public.get_sanpo_share(t) is not null then raise exception 'revoked link is visible'; end if;
end; $$;
