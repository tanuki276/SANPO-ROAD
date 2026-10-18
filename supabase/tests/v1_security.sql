-- Run inside BEGIN + migration + this script + ROLLBACK. No test users persist.
do $$
declare a uuid:=gen_random_uuid(); b uuid:=gen_random_uuid(); session uuid:=gen_random_uuid();
begin
 insert into auth.users(id,aud,role,email) values(a,'authenticated','authenticated','sanpo-v1-a@invalid.example'),(b,'authenticated','authenticated','sanpo-v1-b@invalid.example');
 insert into auth.sessions(id,user_id) values(session,a);
 perform set_config('request.jwt.claims',json_build_object('sub',a,'role','authenticated','is_anonymous',false,'session_id',session)::text,true);
 perform set_config('sanpo.test_uid',a::text,true);
 insert into public.sanpo_routes(user_id,local_id,name,started_at,duration_ms,distance_meters,points) values(b,100,'private',now(),100,1,'[]');
end $$;
set local role authenticated;
do $$
declare payload jsonb; v_token text; uid uuid:=auth.uid();
begin
 if exists(select 1 from public.sanpo_routes where user_id<>uid) then raise exception 'RLS isolation failed'; end if;
 payload:=jsonb_build_object('user_id',uid,'local_id',100,'name','walk','started_at',now(),'duration_ms',1000,'distance_meters',10,'points','[{"lat":35,"lng":139,"timeMs":1000}]'::jsonb,'client_updated_at',100,'favorite',true);
 perform public.upsert_sanpo_route_v1(payload);
 if not exists(select 1 from public.sanpo_routes where user_id=uid and local_id=100 and favorite) then raise exception 'upsert failed'; end if;
 v_token:=public.create_sanpo_share_v1(100,'walk',now(),1000,10,'[{"lat":35,"lng":139}]','note',false);
 if public.get_sanpo_share(v_token)->'points' <> '[]'::jsonb then raise exception 'map off leaked points'; end if;
 delete from public.sanpo_shares where sanpo_shares.token=v_token;
 if public.get_sanpo_share(v_token) is not null then raise exception 'revocation failed'; end if;
 perform public.upsert_sanpo_route_v1(payload||'{"deleted_at":200,"client_updated_at":200}'::jsonb);
 perform public.upsert_sanpo_route_v1(payload||'{"client_updated_at":9000}'::jsonb);
 if not exists(select 1 from public.sanpo_routes where user_id=uid and local_id=100 and deleted_at=200 and points='[]'::jsonb and note='') then raise exception 'resurrection prevention failed'; end if;
 begin
   perform public.upsert_sanpo_route_v1(payload||jsonb_build_object('user_id',gen_random_uuid()));
   raise exception 'cross-account write unexpectedly succeeded';
 exception when others then
   if sqlerrm='cross-account write unexpectedly succeeded' then raise; end if;
 end;
end $$;
reset role;
delete from auth.sessions where user_id=current_setting('sanpo.test_uid')::uuid;
set local role authenticated;
do $$ begin
 if public.sanpo_session_active_v1() then raise exception 'revoked session accepted'; end if;
end $$;
reset role;
