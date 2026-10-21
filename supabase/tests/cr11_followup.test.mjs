import { PGlite } from '@electric-sql/pglite';
import fs from 'node:fs';
import assert from 'node:assert/strict';
const db = new PGlite();
await db.exec(`
CREATE SCHEMA auth; CREATE SCHEMA sanpo_private;
CREATE ROLE anon; CREATE ROLE authenticated;
CREATE FUNCTION auth.uid() RETURNS uuid LANGUAGE sql AS $$ select '00000000-0000-0000-0000-000000000001'::uuid $$;
CREATE FUNCTION public.sanpo_session_active_v1() RETURNS boolean LANGUAGE sql AS $$ select coalesce(current_setting('sanpo.test_active',true),'true') <> 'false' $$;
CREATE TABLE public.sanpo_routes(user_id uuid,local_id bigint,client_updated_at bigint,deleted_at bigint,primary key(user_id,local_id));
CREATE FUNCTION public.upsert_sanpo_route_v2(p_route jsonb) RETURNS void LANGUAGE plpgsql AS $$ BEGIN
 INSERT INTO public.sanpo_routes VALUES(auth.uid(),(p_route->>'local_id')::bigint,(p_route->>'client_updated_at')::bigint,null)
 ON CONFLICT(user_id,local_id) DO UPDATE SET client_updated_at=excluded.client_updated_at WHERE sanpo_routes.client_updated_at <= excluded.client_updated_at;
END; $$;
CREATE TABLE public.sanpo_shares(token text primary key,owner_id uuid,route_local_id bigint,name text,expires_at timestamptz,unique(owner_id,route_local_id));
CREATE FUNCTION public.create_sanpo_share_v1(bigint,text,timestamptz,bigint,double precision,jsonb,text,boolean) RETURNS text LANGUAGE plpgsql AS $$
DECLARE t text:=gen_random_uuid()::text;
BEGIN
 IF $2='' THEN RAISE EXCEPTION 'invalid name'; END IF;
 INSERT INTO public.sanpo_shares VALUES(t,auth.uid(),$1,$2,null); RETURN t;
END; $$;
`);
for (const name of ['20261020150909_cr11_walk_metadata.sql','20261021044423_cr11_followup.sql'])
 await db.exec(fs.readFileSync(new URL('../migrations/'+name,import.meta.url),'utf8'));
const create = async(name='walk') => (await db.query(`select public.create_sanpo_share_v2(1,$1,now(),1000,10,'[]','',false,now()+interval '7 days') as token`,[name])).rows[0].token;
let previous = await create();
for(let i=0;i<5;i++) {
 const next = await create(); assert.notEqual(next,previous);
 const rows = (await db.query('select * from sanpo_shares')).rows;
 assert.equal(rows.length,1); assert.equal(rows[0].token,next); previous=next;
}
await assert.rejects(create(''));
assert.equal((await db.query('select token from sanpo_shares')).rows[0].token,previous);
const upsert = data => db.query('select upsert_sanpo_route_v4($1::jsonb)',[JSON.stringify({local_id:1,client_updated_at:10,...data})]);
await upsert({distance_goal_meters:3000});
await upsert({client_updated_at:9,distance_goal_meters:5000});
assert.equal((await db.query('select distance_goal_meters from sanpo_routes')).rows[0].distance_goal_meters,3000);
await assert.rejects(upsert({distance_goal_meters:-1}));
await upsert({client_updated_at:11}); // Older payload preserves goal.
assert.equal((await db.query('select distance_goal_meters from sanpo_routes')).rows[0].distance_goal_meters,3000);
await db.exec(`select set_config('sanpo.test_active','false',false)`);
await assert.rejects(create()); await assert.rejects(upsert({}));
assert.equal((await db.query('select token from sanpo_shares')).rows[0].token,previous);
await db.close();
console.log('PASS: five share rotations, failure rollback, inactive session, goal roundtrip/stale/invalid/legacy payloads');
