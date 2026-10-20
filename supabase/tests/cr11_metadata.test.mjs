// Isolated PostgreSQL smoke test. Fixtures stand in for already tested CR10 routines.
import {createRequire} from 'node:module';
const {PGlite}=createRequire(import.meta.url)(process.env.PGLITE_MODULE || '@electric-sql/pglite');
import fs from 'node:fs';
const db=new PGlite();
await db.exec(`CREATE SCHEMA auth; CREATE SCHEMA sanpo_private;
CREATE ROLE anon; CREATE ROLE authenticated;
CREATE FUNCTION auth.uid() RETURNS uuid LANGUAGE sql AS $$ select '00000000-0000-0000-0000-000000000001'::uuid $$;
CREATE FUNCTION public.sanpo_session_active_v1() RETURNS boolean LANGUAGE sql AS $$ select coalesce(current_setting('sanpo.test_active',true),'true') <> 'false' $$;
CREATE TABLE public.sanpo_routes(user_id uuid,local_id bigint,client_updated_at bigint,deleted_at bigint,primary key(user_id,local_id));
CREATE FUNCTION public.upsert_sanpo_route_v2(p_route jsonb) RETURNS void LANGUAGE plpgsql AS $$ BEGIN
 INSERT INTO public.sanpo_routes VALUES (auth.uid(),(p_route->>'local_id')::bigint,(p_route->>'client_updated_at')::bigint,null)
 ON CONFLICT(user_id,local_id) DO UPDATE SET client_updated_at=excluded.client_updated_at
 WHERE sanpo_routes.client_updated_at <= excluded.client_updated_at;
END; $$;`);
await db.exec(fs.readFileSync(new URL('../migrations/20261020150909_cr11_walk_metadata.sql',import.meta.url),'utf8'));
await db.query(`select public.upsert_sanpo_route_v3($1::jsonb)`,[JSON.stringify({local_id:1,client_updated_at:1,tags:['犬の散歩'],imported:true,import_key:'abc',gps_quality:{acceptedPoints:2},rest_intervals:[{startMs:1,endMs:2}]})]);
let row=(await db.query('select * from public.sanpo_routes')).rows[0];
if(!row.imported || row.tags[0]!=='犬の散歩' || row.gps_quality.acceptedPoints!==2) throw Error('roundtrip');
await db.exec(`update public.sanpo_routes set imported=false, import_key=null`);
row=(await db.query('select * from public.sanpo_routes')).rows[0];if(!row.imported || row.import_key!=='abc')throw Error('origin lost');
await db.query(`select public.upsert_sanpo_route_v3($1::jsonb)`,[JSON.stringify({local_id:1,client_updated_at:0,tags:['stale']})]);
if((await db.query('select tags from public.sanpo_routes')).rows[0].tags[0]!=='犬の散歩') throw Error('stale update');
await db.exec(`select set_config('sanpo.test_active','false',false)`);
let blocked=false;try{await db.query(`select public.upsert_sanpo_route_v3($1::jsonb)`,[JSON.stringify({local_id:2,client_updated_at:1})]);}catch(e){blocked=true}
if(!blocked)throw Error('inactive session allowed');
console.log('PASS: SQL metadata roundtrip, sticky import provenance, stale update and inactive-session guard');await db.close();
